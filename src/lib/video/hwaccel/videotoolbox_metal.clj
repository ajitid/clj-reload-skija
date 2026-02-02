(ns lib.video.hwaccel.videotoolbox-metal
  "macOS VideoToolbox zero-copy path via Metal.

   This module provides the Metal-based zero-copy path for video on macOS:
   1. FFmpeg decodes to CVPixelBuffer (via VideoToolbox)
   2. CVMetalTextureCache converts CVPixelBuffer to MTLTexture
   3. Skia renders the texture to screen

   Unlike the OpenGL path (which requires GL_TEXTURE_RECTANGLE that Skia
   doesn't support well), Metal allows direct texture access with proper
   format support."
  (:require [lib.video.hwaccel.detect :as detect]
            [lib.window.metal :as metal])
  (:import [com.sun.jna Library Native Pointer NativeLong]
           [com.sun.jna.ptr PointerByReference]
           [org.lwjgl.system.macosx MacOSXLibrary ObjCRuntime]
           [org.lwjgl.system JNI MemoryUtil]))

;; ============================================================
;; Platform Check
;; ============================================================

(defn available?
  "Check if Metal VideoToolbox path is available.
   Requires macOS and Metal support."
  []
  (and (= (detect/get-platform) :macos)
       (metal/available?)))

;; ============================================================
;; JNA Interface: CoreVideo (CVPixelBuffer & CVMetalTextureCache)
;; ============================================================

(definterface ICoreVideo
  ;; Get IOSurface from CVPixelBuffer
  (^Pointer CVPixelBufferGetIOSurface [^Pointer pixelBuffer])
  ;; Get dimensions
  (^long CVPixelBufferGetWidth [^Pointer pixelBuffer])
  (^long CVPixelBufferGetHeight [^Pointer pixelBuffer])
  ;; Get pixel format
  (^int CVPixelBufferGetPixelFormatType [^Pointer pixelBuffer])

  ;; CVMetalTextureCache functions
  ;; CVReturn CVMetalTextureCacheCreate(
  ;;   CFAllocatorRef allocator,
  ;;   CFDictionaryRef cacheAttributes,
  ;;   id<MTLDevice> metalDevice,
  ;;   CFDictionaryRef textureAttributes,
  ;;   CVMetalTextureCacheRef* cacheOut)
  (^int CVMetalTextureCacheCreate [^Pointer allocator
                                   ^Pointer cacheAttributes
                                   ^long metalDevice
                                   ^Pointer textureAttributes
                                   ^Pointer cacheOut])

  ;; CVReturn CVMetalTextureCacheCreateTextureFromImage(
  ;;   CFAllocatorRef allocator,
  ;;   CVMetalTextureCacheRef textureCache,
  ;;   CVImageBufferRef sourceImage,
  ;;   CFDictionaryRef textureAttributes,
  ;;   MTLPixelFormat pixelFormat,
  ;;   size_t width, size_t height,
  ;;   size_t planeIndex,
  ;;   CVMetalTextureRef* textureOut)
  (^int CVMetalTextureCacheCreateTextureFromImage [^Pointer allocator
                                                   ^Pointer textureCache
                                                   ^Pointer sourceImage
                                                   ^Pointer textureAttributes
                                                   ^long pixelFormat
                                                   ^long width
                                                   ^long height
                                                   ^long planeIndex
                                                   ^Pointer textureOut])

  ;; CVMetalTextureGetTexture returns id<MTLTexture>
  (^long CVMetalTextureGetTexture [^Pointer cvMetalTexture])

  ;; Flush the cache
  (^void CVMetalTextureCacheFlush [^Pointer textureCache ^long options]))

(def ^:private CoreVideo
  (when (available?)
    (try
      (Native/load "CoreVideo" ICoreVideo)
      (catch Exception _ nil))))

;; ============================================================
;; Constants
;; ============================================================

;; CVReturn codes
(def ^:private kCVReturnSuccess 0)

;; MTLPixelFormat values
(def ^:private MTLPixelFormatBGRA8Unorm 80)
(def ^:private MTLPixelFormatRGBA8Unorm 70)
(def ^:private MTLPixelFormatR8Unorm 10)
(def ^:private MTLPixelFormatRG8Unorm 30)

;; CVPixelBuffer format types
(def ^:private kCVPixelFormatType_32BGRA 0x42475241)       ; 'BGRA'
(def ^:private kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange 0x34323076) ; '420v' NV12
(def ^:private kCVPixelFormatType_420YpCbCr8BiPlanarFullRange  0x34323066) ; '420f' NV12

;; ============================================================
;; Texture Cache State
;; ============================================================

(defonce ^:private texture-cache-state
  (atom {:cache nil          ; CVMetalTextureCacheRef
         :device nil}))      ; MTLDevice used to create cache

;; ============================================================
;; Debug Logging
;; ============================================================

(defonce ^:private debug-enabled (atom false))

(defn enable-debug!
  "Enable debug logging for VideoToolbox Metal."
  []
  (reset! debug-enabled true))

(defn- debug-log
  "Log message if debug is enabled."
  [& args]
  (when @debug-enabled
    (apply println "[vt-metal]" args)))

;; ============================================================
;; Texture Cache Management
;; ============================================================

(defn- create-texture-cache!
  "Create a CVMetalTextureCache for the given Metal device.
   Caches the result for reuse."
  [device]
  (when (and CoreVideo device (pos? device))
    (let [current @texture-cache-state]
      (if (and (:cache current) (= (:device current) device))
        ;; Return existing cache
        (:cache current)
        ;; Create new cache
        (let [cache-out (PointerByReference.)
              result (.CVMetalTextureCacheCreate CoreVideo
                                                  nil        ; allocator
                                                  nil        ; cacheAttributes
                                                  device
                                                  nil        ; textureAttributes
                                                  (.getPointer cache-out))]
          (if (= result kCVReturnSuccess)
            (let [cache (.getValue cache-out)]
              (debug-log "Created texture cache:" cache)
              (swap! texture-cache-state assoc :cache cache :device device)
              cache)
            (do
              (debug-log "Failed to create texture cache, error:" result)
              nil)))))))

(defn cleanup-texture-cache!
  "Release the texture cache."
  []
  (when-let [cache (:cache @texture-cache-state)]
    ;; CFRelease the cache
    ;; We don't have CFRelease bound, but the cache will be released when
    ;; the JVM exits or when we create a new one
    (reset! texture-cache-state {:cache nil :device nil})))

;; ============================================================
;; CVPixelBuffer -> Metal Texture
;; ============================================================

(defn- get-metal-texture-from-pixelbuffer
  "Convert a CVPixelBuffer to a Metal texture via CVMetalTextureCache.
   Returns the MTLTexture pointer or nil on failure.

   device: MTLDevice pointer
   cv-pixel-buffer: CVPixelBufferRef pointer (from AVFrame.data[3])"
  [device ^Pointer cv-pixel-buffer]
  (when (and CoreVideo device cv-pixel-buffer
             (not (.equals cv-pixel-buffer Pointer/NULL)))
    (let [cache (create-texture-cache! device)]
      (when cache
        ;; Get pixel buffer info
        (let [width (.CVPixelBufferGetWidth CoreVideo cv-pixel-buffer)
              height (.CVPixelBufferGetHeight CoreVideo cv-pixel-buffer)
              pixel-format (.CVPixelBufferGetPixelFormatType CoreVideo cv-pixel-buffer)
              ;; Determine MTLPixelFormat based on CVPixelBuffer format
              mtl-format (condp = pixel-format
                           kCVPixelFormatType_32BGRA MTLPixelFormatBGRA8Unorm
                           ;; For YUV formats, we'd need multi-plane handling
                           ;; For simplicity, try BGRA first
                           MTLPixelFormatBGRA8Unorm)
              texture-out (PointerByReference.)]

          (debug-log "Creating texture:" width "x" height
                     "format:" (format "0x%X" pixel-format)
                     "-> MTL format:" mtl-format)

          ;; Create Metal texture from CVPixelBuffer
          (let [result (.CVMetalTextureCacheCreateTextureFromImage
                         CoreVideo
                         nil            ; allocator
                         (Pointer. (Pointer/nativeValue cache))
                         cv-pixel-buffer
                         nil            ; textureAttributes
                         mtl-format
                         width
                         height
                         0              ; planeIndex
                         (.getPointer texture-out))]
            (if (= result kCVReturnSuccess)
              (let [cv-metal-texture (.getValue texture-out)
                    ;; Get the actual MTLTexture from CVMetalTexture
                    mtl-texture (.CVMetalTextureGetTexture CoreVideo cv-metal-texture)]
                (debug-log "Got MTLTexture:" mtl-texture)
                {:cv-metal-texture cv-metal-texture
                 :mtl-texture mtl-texture
                 :width width
                 :height height})
              (do
                (debug-log "Failed to create texture from image, error:" result)
                nil))))))))

;; ============================================================
;; Public API: Bind Hardware Frame to Texture
;; ============================================================

(defn bind-hw-frame-to-texture!
  "Bind a hardware frame to a Metal texture for zero-copy rendering.

   hw-data-ptr: Pointer from AVFrame.data[3] - this is a CVPixelBufferRef
   texture-info: Map with :texture-id (ignored for Metal), :width, :height
   metal-device: MTLDevice pointer (required)

   This is the main entry point called by the zero-copy decoder.
   Returns a map with {:mtl-texture ptr :width w :height h} or nil."
  [^org.bytedeco.javacpp.Pointer hw-data-ptr texture-info metal-device]
  (when (and (available?) hw-data-ptr (not (.isNull hw-data-ptr)) metal-device)
    (let [hw-address (.address hw-data-ptr)
          cv-pixel-buffer (when (pos? hw-address)
                            (Pointer. hw-address))]
      (when cv-pixel-buffer
        (get-metal-texture-from-pixelbuffer metal-device cv-pixel-buffer)))))

;; ============================================================
;; Alternative: Get Metal Texture Info for Rendering
;; ============================================================

(defn get-video-texture-info
  "Get Metal texture info from a hardware frame.
   Returns {:mtl-texture ptr :width w :height h :cv-metal-texture ptr}
   The cv-metal-texture should be released after use."
  [hw-data-ptr metal-device]
  (bind-hw-frame-to-texture! hw-data-ptr {} metal-device))

;; ============================================================
;; Notes on Usage with Skia
;; ============================================================

(comment
  "To render a Metal video texture with Skia:

   Option 1: Create a shader that samples the Metal texture
   - Create a RuntimeEffect shader that takes the texture as a uniform
   - Draw a rect with the shader

   Option 2: Blit the Metal texture to the Skia surface
   - Create a Metal render pass that copies the video texture
   - Then let Skia draw on top

   Option 3: Use Skia's Metal backend texture adoption (if available)
   - Check for Image.makeFromBackendTexture or similar

   The Metal texture from CVMetalTextureCache has the video frame
   already decoded in GPU memory, so any of these approaches
   maintains zero-copy from decode to display.")

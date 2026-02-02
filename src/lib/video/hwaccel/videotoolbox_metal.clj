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
  (:import [com.sun.jna NativeLibrary Function Pointer]
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
;; JNA Dynamic Function API: CoreVideo (CVPixelBuffer & CVMetalTextureCache)
;; ============================================================
;; Using NativeLibrary.getInstance + Function.invoke* instead of definterface
;; because JNA's Native.load() requires the interface to extend com.sun.jna.Library,
;; which Clojure's definterface cannot do.

;; Lazy-load CoreVideo library
(defonce ^:private cv-library-delay
  (delay
    (try
      (NativeLibrary/getInstance "CoreVideo")
      (catch Exception e
        (println "[vt-metal] Failed to load CoreVideo:" (.getMessage e))
        nil))))

(defn- get-cv-library
  "Get CoreVideo library handle, loading lazily on first use."
  ^NativeLibrary []
  @cv-library-delay)

;; Helper functions to call CoreVideo functions dynamically
;; Note: Cannot use primitive type hints with variadic functions in Clojure
(defn- cv-call-long
  "Call a CoreVideo function that returns a long."
  [^String fn-name & args]
  (if-let [lib (get-cv-library)]
    (let [^Function func (.getFunction lib fn-name)]
      (long (.invokeLong func (object-array args))))
    0))

(defn- cv-call-int
  "Call a CoreVideo function that returns an int."
  [^String fn-name & args]
  (if-let [lib (get-cv-library)]
    (let [^Function func (.getFunction lib fn-name)]
      (int (.invokeInt func (object-array args))))
    -1))

(defn- cv-call-pointer
  "Call a CoreVideo function that returns a Pointer."
  [^String fn-name & args]
  (if-let [lib (get-cv-library)]
    (let [^Function func (.getFunction lib fn-name)]
      (.invokePointer func (object-array args)))
    nil))

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
  (when (get-cv-library)
    (when (and device (pos? device))
      (let [current @texture-cache-state]
        (if (and (:cache current) (= (:device current) device))
          ;; Return existing cache
          (:cache current)
          ;; Create new cache
          (let [cache-out (PointerByReference.)
                result (cv-call-int "CVMetalTextureCacheCreate"
                                    nil        ; allocator
                                    nil        ; cacheAttributes
                                    (long device)
                                    nil        ; textureAttributes
                                    (.getPointer cache-out))]
            (if (= result kCVReturnSuccess)
              (let [cache (.getValue cache-out)]
                (debug-log "Created texture cache:" cache)
                (swap! texture-cache-state assoc :cache cache :device device)
                cache)
              (do
                (debug-log "Failed to create texture cache, error:" result)
                nil))))))))

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

(defn- nv12-format?
  "Check if pixel format is NV12 (bi-planar YUV 4:2:0)."
  [pixel-format]
  (or (= pixel-format kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
      (= pixel-format kCVPixelFormatType_420YpCbCr8BiPlanarFullRange)))

(defn- create-plane-texture
  "Create Metal texture for a specific plane of CVPixelBuffer.
   Returns {:cv-metal-texture :mtl-texture} or nil on failure."
  [cache cv-pixel-buffer plane-index mtl-format width height]
  (let [texture-out (PointerByReference.)
        result (cv-call-int "CVMetalTextureCacheCreateTextureFromImage"
                            nil            ; allocator
                            (Pointer. (Pointer/nativeValue cache))
                            cv-pixel-buffer
                            nil            ; textureAttributes
                            (long mtl-format)
                            (long width)
                            (long height)
                            (long plane-index)
                            (.getPointer texture-out))]
    (if (= result kCVReturnSuccess)
      (let [cv-metal-texture (.getValue texture-out)
            mtl-texture (cv-call-long "CVMetalTextureGetTexture" cv-metal-texture)]
        {:cv-metal-texture cv-metal-texture
         :mtl-texture mtl-texture})
      (do
        (debug-log "Failed to create plane" plane-index "texture, error:" result)
        nil))))

(defn- get-nv12-textures
  "Extract Y and UV plane textures from NV12 CVPixelBuffer.
   Returns {:format :nv12 :y-texture :uv-texture :width :height ...} or nil."
  [cache cv-pixel-buffer width height]
  (debug-log "Creating NV12 textures:" width "x" height)

  ;; Plane 0: Y (luma) - R8Unorm at full resolution
  (when-let [y-info (create-plane-texture cache cv-pixel-buffer 0
                                          MTLPixelFormatR8Unorm
                                          width height)]
    (debug-log "Y texture (R8Unorm):" (:mtl-texture y-info))

    ;; Plane 1: UV (chroma) - RG8Unorm at half width/height
    (let [uv-width (quot width 2)
          uv-height (quot height 2)]
      (when-let [uv-info (create-plane-texture cache cv-pixel-buffer 1
                                               MTLPixelFormatRG8Unorm
                                               uv-width uv-height)]
        (debug-log "UV texture (RG8Unorm):" (:mtl-texture uv-info) "at" uv-width "x" uv-height)
        {:format :nv12
         :y-texture (:mtl-texture y-info)
         :uv-texture (:mtl-texture uv-info)
         :cv-metal-texture-y (:cv-metal-texture y-info)
         :cv-metal-texture-uv (:cv-metal-texture uv-info)
         :width width
         :height height
         :uv-width uv-width
         :uv-height uv-height}))))

(defn- get-bgra-texture
  "Extract BGRA texture from CVPixelBuffer.
   Returns {:format :bgra :mtl-texture :width :height ...} or nil."
  [cache cv-pixel-buffer width height]
  (debug-log "Creating BGRA texture:" width "x" height)

  (when-let [tex-info (create-plane-texture cache cv-pixel-buffer 0
                                            MTLPixelFormatBGRA8Unorm
                                            width height)]
    (debug-log "BGRA texture:" (:mtl-texture tex-info))
    {:format :bgra
     :mtl-texture (:mtl-texture tex-info)
     :cv-metal-texture (:cv-metal-texture tex-info)
     :width width
     :height height}))

(defn- get-metal-texture-from-pixelbuffer
  "Convert a CVPixelBuffer to Metal texture(s) via CVMetalTextureCache.

   For BGRA format: Returns {:format :bgra :mtl-texture :width :height}
   For NV12 format: Returns {:format :nv12 :y-texture :uv-texture :width :height}

   device: MTLDevice pointer
   cv-pixel-buffer: CVPixelBufferRef pointer (from AVFrame.data[3])"
  [device ^Pointer cv-pixel-buffer]
  (when (get-cv-library)
    (when (and device cv-pixel-buffer
               (not (.equals cv-pixel-buffer Pointer/NULL)))
      (let [cache (create-texture-cache! device)]
        (when cache
          ;; Get pixel buffer info
          (let [width (cv-call-long "CVPixelBufferGetWidth" cv-pixel-buffer)
                height (cv-call-long "CVPixelBufferGetHeight" cv-pixel-buffer)
                pixel-format (cv-call-int "CVPixelBufferGetPixelFormatType" cv-pixel-buffer)]

            (debug-log "CVPixelBuffer:" width "x" height
                       "format:" (format "0x%X" pixel-format)
                       (cond
                         (= pixel-format kCVPixelFormatType_32BGRA) "(BGRA)"
                         (nv12-format? pixel-format) "(NV12)"
                         :else "(unknown)"))

            ;; Dispatch based on pixel format
            (cond
              ;; NV12: Extract Y and UV plane textures for GPU conversion
              ;; The conversion to BGRA is done in zero_copy.clj using Metal compute shader
              (nv12-format? pixel-format)
              (get-nv12-textures cache cv-pixel-buffer width height)

              ;; BGRA: Single texture - direct adoption
              (= pixel-format kCVPixelFormatType_32BGRA)
              (get-bgra-texture cache cv-pixel-buffer width height)

              ;; Unknown format: try as BGRA (may fail)
              :else
              (do
                (debug-log "Unknown pixel format, trying as BGRA")
                (get-bgra-texture cache cv-pixel-buffer width height)))))))))

;; ============================================================
;; Public API: Pixel Format Detection
;; ============================================================

(defn check-pixel-format
  "Check the pixel format of a CVPixelBuffer without creating textures.
   Returns :bgra, :nv12, or :unknown. Does not require Metal device."
  [^org.bytedeco.javacpp.Pointer hw-data-ptr]
  (when (and (available?) hw-data-ptr (not (.isNull hw-data-ptr)))
    (when (get-cv-library)
      (let [hw-address (.address hw-data-ptr)
            cv-pixel-buffer (when (pos? hw-address)
                              (Pointer. hw-address))]
        (when cv-pixel-buffer
          (let [pixel-format (cv-call-int "CVPixelBufferGetPixelFormatType" cv-pixel-buffer)]
            (cond
              (= pixel-format kCVPixelFormatType_32BGRA) :bgra
              (nv12-format? pixel-format) :nv12
              :else :unknown)))))))

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

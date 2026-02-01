(ns lib.video.hwaccel.videotoolbox
  "macOS VideoToolbox zero-copy path: IOSurface -> OpenGL texture.

   This module provides JNA bindings for the zero-copy path on macOS:
   1. FFmpeg decodes to CVPixelBuffer (via VideoToolbox)
   2. Get IOSurface from CVPixelBuffer via CVPixelBufferGetIOSurface
   3. Use CGLTexImageIOSurface2D to bind IOSurface directly to GL texture

   The zero-copy path avoids GPU->CPU->GPU copies, keeping the decoded
   frame in GPU memory throughout.

   NOTE: This requires accessing the hardware frame data from FFmpeg,
   which JavaCV abstracts away. For full zero-copy, we need raw FFmpeg API.
   This module provides the infrastructure; full integration is WIP."
  (:require [lib.video.hwaccel.detect :as detect])
  (:import [com.sun.jna Library Native Pointer NativeLong]
           [com.sun.jna.ptr PointerByReference IntByReference]
           [org.lwjgl.opengl GL11]))

;; ============================================================
;; Platform Check
;; ============================================================

(defn available?
  "Check if VideoToolbox zero-copy path is available.
   Only works on macOS."
  []
  (= (detect/get-platform) :macos))

;; ============================================================
;; JNA Interface: Core Foundation
;; ============================================================

(definterface ICoreFoundation
  (^void CFRelease [^Pointer cf]))

(def ^:private CoreFoundation
  (when (available?)
    (try
      (Native/load "CoreFoundation" ICoreFoundation)
      (catch Exception _ nil))))

;; ============================================================
;; JNA Interface: Core Video (CVPixelBuffer)
;; ============================================================

(definterface ICoreVideo
  ;; CVPixelBufferGetIOSurface returns IOSurfaceRef (Pointer)
  (^Pointer CVPixelBufferGetIOSurface [^Pointer pixelBuffer])
  ;; Get pixel buffer dimensions
  (^long CVPixelBufferGetWidth [^Pointer pixelBuffer])
  (^long CVPixelBufferGetHeight [^Pointer pixelBuffer])
  ;; Get pixel format
  (^int CVPixelBufferGetPixelFormatType [^Pointer pixelBuffer])
  ;; Lock/unlock for CPU access
  (^int CVPixelBufferLockBaseAddress [^Pointer pixelBuffer ^long lockFlags])
  (^int CVPixelBufferUnlockBaseAddress [^Pointer pixelBuffer ^long lockFlags])
  ;; Get base address for CPU access
  (^Pointer CVPixelBufferGetBaseAddress [^Pointer pixelBuffer]))

(def ^:private CoreVideo
  (when (available?)
    (try
      (Native/load "CoreVideo" ICoreVideo)
      (catch Exception _ nil))))

;; ============================================================
;; JNA Interface: IOSurface
;; ============================================================

(definterface IIOSurface
  ;; Get IOSurface ID (for debugging/tracking)
  (^int IOSurfaceGetID [^Pointer surface])
  ;; Get surface dimensions
  (^long IOSurfaceGetWidth [^Pointer surface])
  (^long IOSurfaceGetHeight [^Pointer surface])
  ;; Get bytes per row
  (^long IOSurfaceGetBytesPerRow [^Pointer surface])
  ;; Lock/unlock for CPU access (usually not needed for GL path)
  (^int IOSurfaceLock [^Pointer surface ^int options ^IntByReference seed])
  (^int IOSurfaceUnlock [^Pointer surface ^int options ^IntByReference seed]))

(def ^:private IOSurface
  (when (available?)
    (try
      (Native/load "IOSurface" IIOSurface)
      (catch Exception _ nil))))

;; ============================================================
;; JNA Interface: OpenGL (CGL)
;; ============================================================

;; CGLTexImageIOSurface2D binds an IOSurface to a GL texture (zero-copy)
;; This is the key function for zero-copy rendering

(definterface ICGL
  ;; CGLTexImageIOSurface2D(ctx, target, internal_format, width, height,
  ;;                        format, type, surface, plane)
  ;; Returns CGLError (0 = success)
  (^int CGLTexImageIOSurface2D [^Pointer ctx
                                ^int target
                                ^int internal_format
                                ^int width
                                ^int height
                                ^int format
                                ^int type
                                ^Pointer surface
                                ^int plane])
  ;; Get current CGL context
  (^Pointer CGLGetCurrentContext []))

(def ^:private CGL
  (when (available?)
    (try
      (Native/load "OpenGL" ICGL)
      (catch Exception _ nil))))

;; ============================================================
;; OpenGL Constants
;; ============================================================

(def ^:private GL_TEXTURE_RECTANGLE 0x84F5)
(def ^:private GL_RGBA 0x1908)
(def ^:private GL_BGRA 0x80E1)
(def ^:private GL_UNSIGNED_INT_8_8_8_8_REV 0x8367)
(def ^:private GL_RGBA8 0x8058)

;; ============================================================
;; Public API
;; ============================================================

(defn get-iosurface-from-cvpixelbuffer
  "Get IOSurface from a CVPixelBuffer pointer.
   Returns IOSurface pointer or nil if not available."
  [^Pointer cv-pixel-buffer]
  (when (and CoreVideo cv-pixel-buffer)
    (.CVPixelBufferGetIOSurface CoreVideo cv-pixel-buffer)))

(defn get-iosurface-dimensions
  "Get dimensions of an IOSurface.
   Returns [width height] or nil."
  [^Pointer io-surface]
  (when (and IOSurface io-surface)
    [(.IOSurfaceGetWidth IOSurface io-surface)
     (.IOSurfaceGetHeight IOSurface io-surface)]))

(defn bind-iosurface-to-texture!
  "Bind an IOSurface to an OpenGL texture (zero-copy).

   texture-id: GL texture ID (must be bound to GL_TEXTURE_RECTANGLE)
   io-surface: IOSurface pointer from get-iosurface-from-cvpixelbuffer
   width, height: Texture dimensions

   Returns true on success, false on failure.

   NOTE: This uses GL_TEXTURE_RECTANGLE, not GL_TEXTURE_2D.
   Skia can work with rectangle textures, but you may need to adjust
   texture coordinates (they're in pixels, not normalized 0-1)."
  [texture-id ^Pointer io-surface width height]
  (when (and CGL io-surface)
    (let [cgl-ctx (.CGLGetCurrentContext CGL)]
      (when cgl-ctx
        ;; Bind our texture
        (GL11/glBindTexture GL_TEXTURE_RECTANGLE texture-id)
        ;; Zero-copy bind IOSurface to texture
        (let [result (.CGLTexImageIOSurface2D CGL
                                               cgl-ctx
                                               GL_TEXTURE_RECTANGLE
                                               GL_RGBA8
                                               (int width)
                                               (int height)
                                               GL_BGRA
                                               GL_UNSIGNED_INT_8_8_8_8_REV
                                               io-surface
                                               0)] ; plane 0
          (GL11/glBindTexture GL_TEXTURE_RECTANGLE 0)
          (zero? result))))))

(defn create-rectangle-texture
  "Create an OpenGL rectangle texture suitable for IOSurface binding.
   Returns texture ID."
  [width height]
  (let [tex-id (GL11/glGenTextures)]
    (GL11/glBindTexture GL_TEXTURE_RECTANGLE tex-id)
    ;; Set texture parameters for rectangle texture
    (GL11/glTexParameteri GL_TEXTURE_RECTANGLE GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
    (GL11/glTexParameteri GL_TEXTURE_RECTANGLE GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
    (GL11/glTexParameteri GL_TEXTURE_RECTANGLE GL11/GL_TEXTURE_WRAP_S GL11/GL_CLAMP)
    (GL11/glTexParameteri GL_TEXTURE_RECTANGLE GL11/GL_TEXTURE_WRAP_T GL11/GL_CLAMP)
    (GL11/glBindTexture GL_TEXTURE_RECTANGLE 0)
    tex-id))

(defn delete-rectangle-texture!
  "Delete a rectangle texture."
  [texture-id]
  (when (and texture-id (pos? texture-id))
    (GL11/glDeleteTextures texture-id)))

;; ============================================================
;; Pixel Format Detection
;; ============================================================

(def ^:private kCVPixelFormatType_32BGRA 0x42475241) ; 'BGRA'
(def ^:private kCVPixelFormatType_32RGBA 0x52474241) ; 'RGBA'
(def ^:private kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange 0x34323076) ; '420v'
(def ^:private kCVPixelFormatType_420YpCbCr8BiPlanarFullRange  0x34323066) ; '420f'

(defn pixel-format-name
  "Get human-readable name for CVPixelBuffer format type."
  [format-type]
  (condp = format-type
    kCVPixelFormatType_32BGRA "BGRA"
    kCVPixelFormatType_32RGBA "RGBA"
    kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange "420v (NV12)"
    kCVPixelFormatType_420YpCbCr8BiPlanarFullRange  "420f (NV12)"
    (format "unknown (0x%08X)" format-type)))

(defn get-cvpixelbuffer-format
  "Get the pixel format of a CVPixelBuffer."
  [^Pointer cv-pixel-buffer]
  (when (and CoreVideo cv-pixel-buffer)
    (let [fmt (.CVPixelBufferGetPixelFormatType CoreVideo cv-pixel-buffer)]
      {:format-type fmt
       :format-name (pixel-format-name fmt)})))

;; ============================================================
;; Integration Notes
;; ============================================================

(comment
  "To use true zero-copy with VideoToolbox:

   1. Configure FFmpegFrameGrabber with hwaccel=videotoolbox
   2. Access the hardware frame from FFmpeg (requires raw FFmpeg API)
   3. Extract CVPixelBufferRef from AVFrame.data[3]
   4. Use get-iosurface-from-cvpixelbuffer to get IOSurface
   5. Use bind-iosurface-to-texture! to bind to GL texture
   6. Wrap texture with Skia Image.adoptGLTextureFrom

   Current limitation: JavaCV's grabImage() copies to CPU memory,
   losing the zero-copy benefit. For true zero-copy, we need to
   use JavaCPP's raw FFmpeg bindings to access AVFrame.data[3].

   This module provides all the macOS-side infrastructure.
   Full integration requires changes to the decoder to expose
   the raw hardware frame."

  ;; Example of what the full integration would look like:
  (defn zero-copy-frame->skia-image [hw-frame texture-id direct-context]
    (let [cv-pixel-buffer (get-cvpixelbuffer-from-avframe hw-frame)
          io-surface (get-iosurface-from-cvpixelbuffer cv-pixel-buffer)
          [w h] (get-iosurface-dimensions io-surface)]
      (when (bind-iosurface-to-texture! texture-id io-surface w h)
        ;; Note: Would need Skia support for rectangle textures
        ;; or convert to regular texture
        nil))))

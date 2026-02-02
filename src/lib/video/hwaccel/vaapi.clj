(ns lib.video.hwaccel.vaapi
  "Linux VAAPI zero-copy path: VASurface -> DMA-BUF -> EGLImage -> GL texture.

   This module provides JNA bindings and infrastructure for the zero-copy
   path on Linux with Intel and AMD GPUs (via VAAPI):
   1. FFmpeg decodes to VASurface (via VAAPI hardware decoder)
   2. Export VASurface as DMA-BUF fd via vaExportSurfaceHandle
   3. Import DMA-BUF as EGLImage via eglCreateImageKHR
   4. Bind EGLImage to GL texture via glEGLImageTargetTexture2DOES

   Requirements:
   - Linux with Intel or AMD GPU
   - VAAPI drivers installed (intel-media-va-driver or mesa-va-drivers)
   - EGL context (not GLX) - SDL3 can create EGL contexts
   - libva, libEGL, libGL libraries

   The key function for the zero-copy decoder is `bind-hw-frame-to-texture!`
   which takes the raw hardware frame pointer (VASurfaceID from AVFrame.data[3])
   and binds it directly to a GL texture."
  (:require [lib.video.hwaccel.detect :as detect]
            [lib.video.hwaccel.hw-protocol :as hw-proto])
  (:import [com.sun.jna Library Native Pointer NativeLong Memory]
           [com.sun.jna.ptr PointerByReference IntByReference]
           [org.lwjgl.opengl GL11]))

;; ============================================================
;; Platform Check
;; ============================================================

(defn available?
  "Check if VAAPI zero-copy path is available.
   Requires Linux with Intel or AMD GPU."
  []
  (and (= (detect/get-platform) :linux)
       (#{:intel :amd} (detect/get-gpu-vendor))
       (detect/available? :vaapi)))

;; ============================================================
;; Debug Logging
;; ============================================================

(defonce ^:private debug-enabled (atom false))

(defn enable-debug!
  "Enable debug logging for VAAPI."
  []
  (reset! debug-enabled true))

(defn- debug-log
  "Log message if debug is enabled."
  [& args]
  (when @debug-enabled
    (apply println "[vaapi]" args)))

;; ============================================================
;; EGL Constants
;; ============================================================

(def ^:private EGL_LINUX_DMA_BUF_EXT         0x3270)
(def ^:private EGL_WIDTH                     0x3057)
(def ^:private EGL_HEIGHT                    0x3056)
(def ^:private EGL_LINUX_DRM_FOURCC_EXT      0x3271)
(def ^:private EGL_DMA_BUF_PLANE0_FD_EXT     0x3272)
(def ^:private EGL_DMA_BUF_PLANE0_OFFSET_EXT 0x3273)
(def ^:private EGL_DMA_BUF_PLANE0_PITCH_EXT  0x3274)
(def ^:private EGL_DMA_BUF_PLANE1_FD_EXT     0x3275)
(def ^:private EGL_DMA_BUF_PLANE1_OFFSET_EXT 0x3276)
(def ^:private EGL_DMA_BUF_PLANE1_PITCH_EXT  0x3277)
(def ^:private EGL_NO_CONTEXT                (Pointer. 0))
(def ^:private EGL_NONE                      0x3038)
(def ^:private EGL_TRUE                      1)
(def ^:private EGL_FALSE                     0)

;; DRM/FourCC constants
(def ^:private DRM_FORMAT_NV12 0x3231564E) ; 'NV12' little-endian

;; VAAPI constants
(def ^:private VA_EXPORT_SURFACE_READ_ONLY       0x0001)
(def ^:private VA_EXPORT_SURFACE_SEPARATE_LAYERS 0x0004)
(def ^:private VA_SURFACE_ATTRIB_MEM_TYPE_DRM_PRIME_2 0x40000000)

;; GL constants
(def ^:private GL_TEXTURE_2D 0x0DE1)

;; ============================================================
;; JNA Interface: libva
;; ============================================================

(definterface ILibVA
  ;; vaExportSurfaceHandle exports a surface as DMA-BUF
  ;; vaExportSurfaceHandle(VADisplay display, VASurfaceID surface,
  ;;                       uint32_t mem_type, uint32_t flags,
  ;;                       VADRMPRIMESurfaceDescriptor *descriptor)
  (^int vaExportSurfaceHandle [^Pointer display
                                ^int surface_id
                                ^int mem_type
                                ^int flags
                                ^Pointer descriptor])
  ;; vaSyncSurface waits for surface operations to complete
  (^int vaSyncSurface [^Pointer display ^int surface_id]))

(def ^:private libva
  (when (available?)
    (try
      (Native/load "va" ILibVA)
      (catch Exception e
        (debug-log "Failed to load libva:" (.getMessage e))
        nil))))

;; ============================================================
;; JNA Interface: EGL
;; ============================================================

(definterface IEGL
  ;; Get current EGL display
  (^Pointer eglGetCurrentDisplay [])
  ;; Get current EGL context
  (^Pointer eglGetCurrentContext [])
  ;; Create EGLImage from DMA-BUF
  ;; eglCreateImageKHR(display, context, target, buffer, attribs)
  (^Pointer eglCreateImageKHR [^Pointer display
                                ^Pointer context
                                ^int target
                                ^Pointer buffer
                                ^Pointer attribs])
  ;; Destroy EGLImage
  (^int eglDestroyImageKHR [^Pointer display ^Pointer image])
  ;; Check for errors
  (^int eglGetError []))

(def ^:private libEGL
  (when (available?)
    (try
      (Native/load "EGL" IEGL)
      (catch Exception e
        (debug-log "Failed to load libEGL:" (.getMessage e))
        nil))))

;; ============================================================
;; JNA Interface: GL Extension (glEGLImageTargetTexture2DOES)
;; ============================================================

;; The glEGLImageTargetTexture2DOES function is typically obtained via
;; eglGetProcAddress, but we can try loading from GL library directly.

(definterface IGLExt
  ;; glEGLImageTargetTexture2DOES(target, image)
  (^void glEGLImageTargetTexture2DOES [^int target ^Pointer image]))

(def ^:private libGL-ext
  (when (available?)
    (try
      ;; Try GLESv2 first (common on EGL systems)
      (Native/load "GLESv2" IGLExt)
      (catch Exception _
        (try
          ;; Try GL
          (Native/load "GL" IGLExt)
          (catch Exception e
            (debug-log "Failed to load GL extension library:" (.getMessage e))
            nil))))))

;; ============================================================
;; VADRMPRIMESurfaceDescriptor Structure
;; ============================================================

;; The descriptor structure returned by vaExportSurfaceHandle
;; is quite complex. We'll use JNA Memory to read the fields.

(defn- read-drm-prime-descriptor
  "Read DRM PRIME surface descriptor from memory.
   Returns a map with :fourcc, :width, :height, :num-layers, :layers, :num-objects, :objects"
  [^Memory mem]
  (let [fourcc    (.getInt mem 0)
        width     (.getInt mem 4)
        height    (.getInt mem 8)
        num-obj   (.getInt mem 12)
        num-layer (.getInt mem 16)
        ;; Objects array starts at offset 20 (each object is 16 bytes: fd, size, modifier)
        objects (vec (for [i (range num-obj)]
                       (let [base (+ 20 (* i 16))]
                         {:fd       (.getInt mem base)
                          :size     (.getInt mem (+ base 4))
                          :modifier (.getLong mem (+ base 8))})))
        ;; Layers array follows objects (each layer: offset, pitch, etc.)
        layer-base (+ 20 (* 4 16))  ; After 4 max objects
        layers (vec (for [i (range num-layer)]
                      (let [base (+ layer-base (* i 32))]  ; Approximate size
                        {:drm-format (.getInt mem base)
                         :num-planes (.getInt mem (+ base 4))
                         :object-idx-0 (.getInt mem (+ base 8))
                         :offset-0     (.getInt mem (+ base 12))
                         :pitch-0      (.getInt mem (+ base 16))})))]
    {:fourcc     fourcc
     :width      width
     :height     height
     :num-objects num-obj
     :objects    objects
     :num-layers num-layer
     :layers     layers}))

;; ============================================================
;; Zero-Copy Frame State
;; ============================================================

;; Track EGL images so we can clean them up
(defonce ^:private egl-image-cache (atom {}))

(defn- cleanup-egl-image!
  "Destroy an EGLImage."
  [^Pointer egl-display ^Pointer egl-image]
  (when (and libEGL egl-display egl-image)
    (.eglDestroyImageKHR libEGL egl-display egl-image)))

;; ============================================================
;; Zero-Copy Frame Binding
;; ============================================================

(defn- export-va-surface-to-dmabuf
  "Export a VASurface as DMA-BUF file descriptor.
   Returns descriptor map or nil on failure."
  [va-display va-surface-id]
  (when (and libva va-display (>= va-surface-id 0))
    ;; Allocate memory for descriptor (256 bytes should be enough)
    (let [desc-mem (Memory. 256)]
      ;; Sync the surface first
      (let [sync-ret (.vaSyncSurface libva va-display va-surface-id)]
        (when (not (zero? sync-ret))
          (debug-log "vaSyncSurface failed:" sync-ret)))

      ;; Export as DRM PRIME
      (let [ret (.vaExportSurfaceHandle libva
                                         va-display
                                         va-surface-id
                                         VA_SURFACE_ATTRIB_MEM_TYPE_DRM_PRIME_2
                                         (bit-or VA_EXPORT_SURFACE_READ_ONLY
                                                 VA_EXPORT_SURFACE_SEPARATE_LAYERS)
                                         desc-mem)]
        (if (zero? ret)
          (do
            (debug-log "Exported VASurface" va-surface-id "as DMA-BUF")
            (read-drm-prime-descriptor desc-mem))
          (do
            (debug-log "vaExportSurfaceHandle failed:" ret)
            nil))))))

(defn- create-egl-image-from-dmabuf
  "Create an EGLImage from a DMA-BUF descriptor.
   Returns EGLImage pointer or nil on failure."
  [egl-display descriptor width height]
  (when (and libEGL egl-display descriptor)
    (let [{:keys [objects layers]} descriptor
          ;; Get plane 0 info
          plane0-fd (get-in objects [0 :fd])
          plane0-offset (get-in layers [0 :offset-0] 0)
          plane0-pitch (get-in layers [0 :pitch-0] (* width 4))
          ;; Build attribute list
          attribs (int-array [EGL_WIDTH width
                              EGL_HEIGHT height
                              EGL_LINUX_DRM_FOURCC_EXT DRM_FORMAT_NV12
                              EGL_DMA_BUF_PLANE0_FD_EXT plane0-fd
                              EGL_DMA_BUF_PLANE0_OFFSET_EXT plane0-offset
                              EGL_DMA_BUF_PLANE0_PITCH_EXT plane0-pitch
                              EGL_NONE])
          attrib-mem (doto (Memory. (* 4 (count attribs)))
                       (.write 0 attribs 0 (count attribs)))]

      (debug-log "Creating EGLImage - fd:" plane0-fd
                 "size:" width "x" height
                 "pitch:" plane0-pitch)

      (let [image (.eglCreateImageKHR libEGL
                                       egl-display
                                       EGL_NO_CONTEXT
                                       EGL_LINUX_DMA_BUF_EXT
                                       nil  ; buffer (not used for DMA-BUF)
                                       attrib-mem)]
        (if (and image (not (.equals image (Pointer. 0))))
          (do
            (debug-log "Created EGLImage:" image)
            image)
          (do
            (debug-log "eglCreateImageKHR failed, error:" (.eglGetError libEGL))
            nil))))))

(defn- bind-egl-image-to-texture!
  "Bind an EGLImage to a GL texture."
  [texture-id ^Pointer egl-image]
  (when (and libGL-ext egl-image)
    (GL11/glBindTexture GL_TEXTURE_2D texture-id)
    (.glEGLImageTargetTexture2DOES libGL-ext GL_TEXTURE_2D egl-image)
    (GL11/glBindTexture GL_TEXTURE_2D 0)
    (debug-log "Bound EGLImage to texture" texture-id)
    true))

(defn bind-hw-frame-to-texture!
  "Bind a VAAPI hardware frame to an OpenGL texture (zero-copy).

   hw-data-ptr: Pointer from AVFrame.data[3] - contains VASurfaceID and VADisplay
                For VAAPI, data[3] is VASurfaceID (as uintptr_t)
                The VADisplay can be obtained from hw_frames_ctx->device_ctx->hwctx
   texture-info: Map with :texture-id, :texture-type, :width, :height,
                          :va-display (required for VAAPI)

   Returns true on success, false on failure."
  [^org.bytedeco.javacpp.Pointer hw-data-ptr texture-info]
  (when (and (available?) hw-data-ptr (not (.isNull hw-data-ptr)))
    (let [{:keys [texture-id width height va-display]} texture-info]
      (debug-log "Binding VAAPI frame to texture" texture-id
                 "dims:" width "x" height)

      ;; The hw-data-ptr points to VASurfaceID (as pointer-sized integer)
      ;; We need to read the surface ID from it
      (let [surface-id (int (.address hw-data-ptr))]

        (debug-log "VASurfaceID:" surface-id)

        ;; For VAAPI, we need the VADisplay which should be passed via texture-info
        ;; or obtained from the decoder context
        (if-not va-display
          (do
            (debug-log "No VADisplay in texture-info, cannot bind")
            false)

          ;; Export VASurface to DMA-BUF
          (if-let [descriptor (export-va-surface-to-dmabuf va-display surface-id)]
            ;; Create EGLImage from DMA-BUF
            (let [egl-display (when libEGL (.eglGetCurrentDisplay libEGL))]
              (if-let [egl-image (create-egl-image-from-dmabuf
                                   egl-display descriptor width height)]
                ;; Bind EGLImage to GL texture
                (let [success? (bind-egl-image-to-texture! texture-id egl-image)]
                  ;; Cache for cleanup
                  (swap! egl-image-cache assoc texture-id
                         {:egl-display egl-display :egl-image egl-image})
                  ;; Close DMA-BUF fd
                  (doseq [{:keys [fd]} (:objects descriptor)]
                    (when (>= fd 0)
                      ;; Would need to close fd here
                      ))
                  success?)
                false))
            false))))))

;; ============================================================
;; HardwareFrame Implementation
;; ============================================================

(defrecord VAAPIFrame [va-display va-surface-id egl-image egl-display
                       width height released?]
  hw-proto/HardwareFrame

  (bind-to-texture! [_frame texture-info]
    (when (and egl-image (not @released?))
      (bind-egl-image-to-texture! (:texture-id texture-info) egl-image)))

  (get-dimensions [_frame]
    [width height])

  (get-pixel-format [_frame]
    :nv12)

  (release! [_frame]
    (when (compare-and-set! released? false true)
      (when (and egl-display egl-image)
        (cleanup-egl-image! egl-display egl-image)))))

(defn create-vaapi-frame
  "Create a VAAPIFrame from a VASurfaceID.

   va-display: VADisplay pointer
   va-surface-id: VASurfaceID (integer)
   width, height: Frame dimensions"
  [va-display va-surface-id width height]
  (when (and va-display (>= va-surface-id 0))
    ;; Export to EGL image
    (let [egl-display (when libEGL (.eglGetCurrentDisplay libEGL))]
      (when-let [descriptor (export-va-surface-to-dmabuf va-display va-surface-id)]
        (when-let [egl-image (create-egl-image-from-dmabuf
                               egl-display descriptor width height)]
          (->VAAPIFrame va-display va-surface-id egl-image egl-display
                        width height (atom false)))))))

;; ============================================================
;; Cleanup
;; ============================================================

(defn cleanup-cached-egl-images!
  "Clean up all cached EGL images."
  []
  (doseq [[_tex-id {:keys [egl-display egl-image]}] @egl-image-cache]
    (cleanup-egl-image! egl-display egl-image))
  (reset! egl-image-cache {}))

;; ============================================================
;; Zero-Copy Availability Check
;; ============================================================

(defn zero-copy-available?
  "Check if all components for VAAPI zero-copy are available."
  []
  (and (available?)
       (some? libva)
       (some? libEGL)
       (some? libGL-ext)))

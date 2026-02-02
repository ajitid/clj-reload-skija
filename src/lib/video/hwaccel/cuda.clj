(ns lib.video.hwaccel.cuda
  "Linux/Windows NVIDIA CUDA zero-copy path: CUDA buffer -> OpenGL texture.

   This module provides JNA bindings and infrastructure for the zero-copy
   path on NVIDIA GPUs using CUDA/NVDEC:
   1. FFmpeg decodes to CUDA device buffer (via NVDEC hardware decoder)
   2. Register GL texture with CUDA via cuGraphicsGLRegisterImage (once)
   3. Map texture as CUDA resource via cuGraphicsMapResources
   4. Copy decoded frame to texture using cuMemcpy2D (GPU-to-GPU)
   5. Unmap resource via cuGraphicsUnmapResources

   Requirements:
   - NVIDIA GPU with NVDEC support
   - NVIDIA drivers with CUDA support
   - libcuda.so (Linux) or nvcuda.dll (Windows)

   Note: This is technically a GPU-to-GPU copy, not true zero-copy like
   VideoToolbox, but it's still very fast as data never touches CPU memory.

   The key function for the zero-copy decoder is `bind-hw-frame-to-texture!`
   which takes the raw hardware frame pointer (CUdeviceptr from AVFrame.data[0])
   and copies it to a GL texture."
  (:require [lib.video.hwaccel.detect :as detect]
            [lib.video.hwaccel.hw-protocol :as hw-proto])
  (:import [com.sun.jna Library Native Pointer NativeLong Memory Platform]
           [com.sun.jna.ptr PointerByReference IntByReference LongByReference]
           [org.lwjgl.opengl GL11]))

;; ============================================================
;; Platform Check
;; ============================================================

(defn available?
  "Check if CUDA/NVDEC zero-copy path is available.
   Requires NVIDIA GPU with CUDA support."
  []
  (and (= (detect/get-gpu-vendor) :nvidia)
       (detect/available? :nvdec-cuda)))

;; ============================================================
;; Debug Logging
;; ============================================================

(defonce ^:private debug-enabled (atom false))

(defn enable-debug!
  "Enable debug logging for CUDA."
  []
  (reset! debug-enabled true))

(defn- debug-log
  "Log message if debug is enabled."
  [& args]
  (when @debug-enabled
    (apply println "[cuda]" args)))

;; ============================================================
;; CUDA Constants
;; ============================================================

;; CUDA error codes
(def ^:private CUDA_SUCCESS 0)
(def ^:private CUDA_ERROR_INVALID_VALUE 1)
(def ^:private CUDA_ERROR_OUT_OF_MEMORY 2)

;; cuGraphicsGLRegisterImage flags
(def ^:private CU_GRAPHICS_REGISTER_FLAGS_NONE 0x00)
(def ^:private CU_GRAPHICS_REGISTER_FLAGS_READ_ONLY 0x01)
(def ^:private CU_GRAPHICS_REGISTER_FLAGS_WRITE_DISCARD 0x02)

;; cuGraphicsMapResources flags
(def ^:private CU_GRAPHICS_MAP_RESOURCE_FLAGS_NONE 0x00)
(def ^:private CU_GRAPHICS_MAP_RESOURCE_FLAGS_READ_ONLY 0x01)
(def ^:private CU_GRAPHICS_MAP_RESOURCE_FLAGS_WRITE_DISCARD 0x02)

;; Memory copy kind
(def ^:private CU_MEMORYTYPE_HOST 0x01)
(def ^:private CU_MEMORYTYPE_DEVICE 0x02)
(def ^:private CU_MEMORYTYPE_ARRAY 0x03)

;; GL constants
(def ^:private GL_TEXTURE_2D 0x0DE1)

;; ============================================================
;; CUDA Library Name
;; ============================================================

(defn- cuda-lib-name
  "Get the CUDA library name for the current platform."
  []
  (if (Platform/isWindows)
    "nvcuda"    ; nvcuda.dll on Windows
    "cuda"))    ; libcuda.so on Linux

;; ============================================================
;; JNA Interface: CUDA Driver API
;; ============================================================

(definterface ICUDADriver
  ;; Initialize CUDA
  (^int cuInit [^int flags])

  ;; Get device
  (^int cuDeviceGet [^IntByReference device ^int ordinal])

  ;; Create context
  (^int cuCtxCreate [^PointerByReference pctx ^int flags ^int dev])

  ;; Get current context
  (^int cuCtxGetCurrent [^PointerByReference pctx])

  ;; Set current context
  (^int cuCtxSetCurrent [^Pointer ctx])

  ;; Register GL texture with CUDA
  ;; cuGraphicsGLRegisterImage(CUgraphicsResource*, GLuint, GLenum, unsigned int)
  (^int cuGraphicsGLRegisterImage [^PointerByReference resource
                                    ^int image
                                    ^int target
                                    ^int flags])

  ;; Map graphics resources
  ;; cuGraphicsMapResources(unsigned int, CUgraphicsResource*, CUstream)
  (^int cuGraphicsMapResources [^int count ^Pointer resources ^Pointer stream])

  ;; Get mapped array from resource
  ;; cuGraphicsSubResourceGetMappedArray(CUarray*, CUgraphicsResource, int, int)
  (^int cuGraphicsSubResourceGetMappedArray [^PointerByReference array
                                              ^Pointer resource
                                              ^int arrayIndex
                                              ^int mipLevel])

  ;; Unmap graphics resources
  (^int cuGraphicsUnmapResources [^int count ^Pointer resources ^Pointer stream])

  ;; Unregister graphics resource
  (^int cuGraphicsUnregisterResource [^Pointer resource])

  ;; 2D memory copy
  ;; cuMemcpy2D takes a pointer to CUDA_MEMCPY2D struct
  (^int cuMemcpy2D [^Pointer pCopy])

  ;; Async 2D memory copy
  (^int cuMemcpy2DAsync [^Pointer pCopy ^Pointer stream])

  ;; Synchronize stream
  (^int cuStreamSynchronize [^Pointer stream])

  ;; Get error string
  (^int cuGetErrorString [^int error ^PointerByReference pStr]))

(def ^:private libcuda
  (when (available?)
    (try
      (Native/load (cuda-lib-name) ICUDADriver)
      (catch Exception e
        (debug-log "Failed to load CUDA driver:" (.getMessage e))
        nil))))

;; ============================================================
;; CUDA_MEMCPY2D Structure
;; ============================================================

;; CUDA_MEMCPY2D is a structure that describes a 2D memory copy
;; We need to create it in native memory

(defn- create-memcpy2d-params
  "Create a CUDA_MEMCPY2D parameter structure in native memory.

   src-device-ptr: Source CUDA device pointer
   src-pitch: Source pitch (bytes per row)
   dst-array: Destination CUDA array (from mapped GL texture)
   width: Width in bytes
   height: Height in pixels"
  [src-device-ptr src-pitch dst-array width height]
  ;; CUDA_MEMCPY2D structure layout (64-bit):
  ;; size_t srcXInBytes;      // 0
  ;; size_t srcY;             // 8
  ;; CUmemorytype srcMemoryType; // 16
  ;; const void *srcHost;     // 24
  ;; CUdeviceptr srcDevice;   // 32
  ;; CUarray srcArray;        // 40
  ;; size_t srcPitch;         // 48
  ;; size_t dstXInBytes;      // 56
  ;; size_t dstY;             // 64
  ;; CUmemorytype dstMemoryType; // 72
  ;; void *dstHost;           // 80
  ;; CUdeviceptr dstDevice;   // 88
  ;; CUarray dstArray;        // 96
  ;; size_t dstPitch;         // 104
  ;; size_t WidthInBytes;     // 112
  ;; size_t Height;           // 120
  ;; Total: 128 bytes
  (let [struct-size 128
        mem (Memory. struct-size)]
    ;; Clear all bytes
    (.clear mem struct-size)

    ;; Source: Device memory
    (.setLong mem 0 0)               ; srcXInBytes
    (.setLong mem 8 0)               ; srcY
    (.setInt mem 16 CU_MEMORYTYPE_DEVICE) ; srcMemoryType
    (.setLong mem 24 0)              ; srcHost (null)
    (.setLong mem 32 src-device-ptr) ; srcDevice
    (.setLong mem 40 0)              ; srcArray (null)
    (.setLong mem 48 src-pitch)      ; srcPitch

    ;; Destination: Array (from GL texture)
    (.setLong mem 56 0)              ; dstXInBytes
    (.setLong mem 64 0)              ; dstY
    (.setInt mem 72 CU_MEMORYTYPE_ARRAY) ; dstMemoryType
    (.setLong mem 80 0)              ; dstHost (null)
    (.setLong mem 88 0)              ; dstDevice (0)
    (.setLong mem 96 (Pointer/nativeValue dst-array)) ; dstArray
    (.setLong mem 104 0)             ; dstPitch (ignored for array)

    ;; Size
    (.setLong mem 112 width)         ; WidthInBytes
    (.setLong mem 120 height)        ; Height

    mem))

;; ============================================================
;; CUDA/GL Resource Cache
;; ============================================================

;; Cache registered GL textures to avoid re-registering
(defonce ^:private registered-textures (atom {}))

(defn- get-or-register-texture!
  "Get or create CUDA graphics resource for a GL texture."
  [texture-id]
  (if-let [resource (get @registered-textures texture-id)]
    resource
    (when libcuda
      (let [resource-ptr (PointerByReference.)]
        (let [ret (.cuGraphicsGLRegisterImage libcuda
                                               resource-ptr
                                               (int texture-id)
                                               GL_TEXTURE_2D
                                               CU_GRAPHICS_REGISTER_FLAGS_WRITE_DISCARD)]
          (if (zero? ret)
            (let [resource (.getValue resource-ptr)]
              (debug-log "Registered GL texture" texture-id "with CUDA")
              (swap! registered-textures assoc texture-id resource)
              resource)
            (do
              (debug-log "cuGraphicsGLRegisterImage failed:" ret)
              nil)))))))

(defn- unregister-texture!
  "Unregister a GL texture from CUDA."
  [texture-id]
  (when-let [resource (get @registered-textures texture-id)]
    (when libcuda
      (.cuGraphicsUnregisterResource libcuda resource))
    (swap! registered-textures dissoc texture-id)))

;; ============================================================
;; Zero-Copy Frame Binding
;; ============================================================

(defn bind-hw-frame-to-texture!
  "Copy a CUDA hardware frame to an OpenGL texture via CUDA/GL interop.

   hw-data-ptr: Pointer from AVFrame.data[0] - this is a CUdeviceptr
                pointing to the decoded frame in GPU memory
   texture-info: Map with :texture-id, :texture-type, :width, :height
                          :cuda-pitch (bytes per row, from AVFrame.linesize[0])

   This performs a GPU-to-GPU copy using CUDA's cuMemcpy2D.
   While not strictly zero-copy, it's very fast as data stays on GPU.

   Returns true on success, false on failure."
  [^org.bytedeco.javacpp.Pointer hw-data-ptr texture-info]
  (when (and (available?) libcuda hw-data-ptr (not (.isNull hw-data-ptr)))
    (let [{:keys [texture-id width height cuda-pitch]} texture-info
          ;; Default pitch to width * 4 (RGBA) if not specified
          pitch (or cuda-pitch (* width 4))]

      (debug-log "Binding CUDA frame to texture" texture-id
                 "dims:" width "x" height "pitch:" pitch)

      ;; Get the CUDA device pointer from hw-data-ptr
      ;; AVFrame.data[0] for CUDA frames is the CUdeviceptr
      (let [cuda-device-ptr (.address hw-data-ptr)]

        (debug-log "CUDA device ptr:" cuda-device-ptr)

        ;; Register or get cached CUDA resource for this texture
        (if-let [cuda-resource (get-or-register-texture! texture-id)]
          ;; Map the resource
          (let [resource-array (doto (Memory. 8)
                                 (.setLong 0 (Pointer/nativeValue cuda-resource)))
                map-ret (.cuGraphicsMapResources libcuda
                                                  1
                                                  resource-array
                                                  nil)] ; null stream = default
            (if (zero? map-ret)
              ;; Get the CUDA array from mapped resource
              (let [array-ptr (PointerByReference.)
                    get-ret (.cuGraphicsSubResourceGetMappedArray libcuda
                                                                   array-ptr
                                                                   cuda-resource
                                                                   0    ; array index
                                                                   0)]  ; mip level
                (if (zero? get-ret)
                  ;; Perform the GPU-to-GPU copy
                  (let [copy-params (create-memcpy2d-params
                                      cuda-device-ptr
                                      pitch
                                      (.getValue array-ptr)
                                      (* width 4)  ; RGBA = 4 bytes/pixel
                                      height)
                        copy-ret (.cuMemcpy2D libcuda copy-params)]

                    ;; Unmap the resource
                    (.cuGraphicsUnmapResources libcuda 1 resource-array nil)

                    (if (zero? copy-ret)
                      (do
                        (debug-log "CUDA->GL copy successful")
                        true)
                      (do
                        (debug-log "cuMemcpy2D failed:" copy-ret)
                        false)))

                  ;; Failed to get array
                  (do
                    (.cuGraphicsUnmapResources libcuda 1 resource-array nil)
                    (debug-log "cuGraphicsSubResourceGetMappedArray failed:" get-ret)
                    false)))

              ;; Failed to map
              (do
                (debug-log "cuGraphicsMapResources failed:" map-ret)
                false)))

          ;; Failed to register
          (do
            (debug-log "Failed to register texture with CUDA")
            false))))))

;; ============================================================
;; HardwareFrame Implementation
;; ============================================================

(defrecord CUDAFrame [cuda-device-ptr cuda-pitch width height released?]
  hw-proto/HardwareFrame

  (bind-to-texture! [_frame texture-info]
    (when-not @released?
      (let [;; Create a JavaCPP-like pointer from the address
            ptr (when (> cuda-device-ptr 0)
                  (org.bytedeco.javacpp.Pointer. cuda-device-ptr))]
        (when ptr
          (bind-hw-frame-to-texture! ptr
                                      (assoc texture-info :cuda-pitch cuda-pitch))))))

  (get-dimensions [_frame]
    [width height])

  (get-pixel-format [_frame]
    :nv12) ; NVDEC typically outputs NV12

  (release! [_frame]
    (reset! released? true)
    ;; CUDA buffer is owned by FFmpeg/AVFrame, we don't release it
    nil))

(defn create-cuda-frame
  "Create a CUDAFrame from a CUDA device pointer.

   cuda-device-ptr: CUdeviceptr (as long) pointing to decoded frame
   cuda-pitch: Pitch in bytes (row stride)
   width, height: Frame dimensions"
  [cuda-device-ptr cuda-pitch width height]
  (when (> cuda-device-ptr 0)
    (->CUDAFrame cuda-device-ptr cuda-pitch width height (atom false))))

;; ============================================================
;; Cleanup
;; ============================================================

(defn cleanup-registered-textures!
  "Unregister all textures from CUDA."
  []
  (doseq [texture-id (keys @registered-textures)]
    (unregister-texture! texture-id)))

;; ============================================================
;; Zero-Copy Availability Check
;; ============================================================

(defn zero-copy-available?
  "Check if all components for CUDA/GL interop are available."
  []
  (and (available?)
       (some? libcuda)))

;; ============================================================
;; CUDA Context Management
;; ============================================================

(defonce ^:private cuda-initialized? (atom false))

(defn ensure-cuda-initialized!
  "Ensure CUDA is initialized (call once at startup)."
  []
  (when (and libcuda (not @cuda-initialized?))
    (let [ret (.cuInit libcuda 0)]
      (if (zero? ret)
        (do
          (debug-log "CUDA initialized successfully")
          (reset! cuda-initialized? true)
          true)
        (do
          (debug-log "cuInit failed:" ret)
          false)))))

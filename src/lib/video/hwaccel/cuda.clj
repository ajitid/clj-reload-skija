(ns lib.video.hwaccel.cuda
  "Linux/Windows NVIDIA CUDA zero-copy path: CUDA buffer -> OpenGL texture.

   This module provides infrastructure for the zero-copy path on
   NVIDIA GPUs using CUDA/NVDEC:
   1. FFmpeg decodes to CUDA buffer (via NVDEC)
   2. Register GL texture with CUDA via cuGraphicsGLRegisterImage
   3. Map texture as CUDA resource
   4. Copy decoded frame to texture (GPU-to-GPU, still fast)

   NOTE: True zero-copy on NVIDIA requires careful CUDA/GL interop.
   The CUDA->GL copy is GPU-resident but not strictly zero-copy.

   Status: Stub - implementation pending."
  (:require [lib.video.hwaccel.detect :as detect]))

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
;; Stub API
;; ============================================================

(defn bind-cuda-frame-to-texture!
  "Copy a CUDA frame to an OpenGL texture via CUDA/GL interop.

   Status: Not yet implemented.

   Would require:
   - JNA bindings for CUDA runtime or driver API
   - cuGraphicsGLRegisterImage
   - cuGraphicsMapResources
   - cuGraphicsSubResourceGetMappedArray
   - cuMemcpy2D (for the actual copy)"
  [texture-id cuda-frame width height]
  (throw (ex-info "CUDA zero-copy not yet implemented"
                  {:texture-id texture-id
                   :cuda-frame cuda-frame})))

(comment
  "CUDA/GL interop implementation outline:

   ;; JNA interface for CUDA driver API:
   ;; libcuda.so (Linux) or nvcuda.dll (Windows)
   ;;
   ;; Key functions:
   ;; - cuGraphicsGLRegisterImage(resource*, image, target, flags)
   ;; - cuGraphicsMapResources(count, resources*, stream)
   ;; - cuGraphicsSubResourceGetMappedArray(array*, resource, index, level)
   ;; - cuMemcpy2DAsync(params*, stream)
   ;; - cuGraphicsUnmapResources(count, resources*, stream)
   ;; - cuGraphicsUnregisterResource(resource)

   ;; Workflow:
   ;; 1. Create GL texture
   ;; 2. Register texture with CUDA (once, cached)
   ;; 3. For each frame:
   ;;    a. Map the GL texture as CUDA resource
   ;;    b. Get the CUDA array from the mapped resource
   ;;    c. Copy decoded CUDA frame to the array (GPU-to-GPU)
   ;;    d. Unmap the resource
   ;; 4. GL texture now contains the frame, use with Skia")

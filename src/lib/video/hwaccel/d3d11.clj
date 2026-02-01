(ns lib.video.hwaccel.d3d11
  "Windows D3D11 zero-copy path: D3D11 texture -> OpenGL texture.

   This module provides infrastructure for the zero-copy path on Windows
   using D3D11VA (works with all GPU vendors):
   1. FFmpeg decodes to D3D11 texture (via D3D11VA/DXVA2)
   2. Use WGL_NV_DX_interop extension to share texture with OpenGL
   3. Register D3D11 texture with GL via wglDXRegisterObjectNV
   4. Lock/unlock for each frame via wglDXLockObjectsNV/wglDXUnlockObjectsNV

   NOTE: WGL_NV_DX_interop works on NVIDIA, AMD, and Intel GPUs despite
   the 'NV' in the name. It's part of the standard Windows GL driver.

   Status: Stub - implementation pending."
  (:require [lib.video.hwaccel.detect :as detect]))

;; ============================================================
;; Platform Check
;; ============================================================

(defn available?
  "Check if D3D11/GL interop zero-copy path is available.
   Requires Windows with D3D11 support."
  []
  (and (= (detect/get-platform) :windows)
       (detect/available? :d3d11va)))

;; ============================================================
;; Stub API
;; ============================================================

(defn bind-d3d11-texture-to-gl!
  "Bind a D3D11 texture to an OpenGL texture via WGL_NV_DX_interop.

   Status: Not yet implemented.

   Would require:
   - JNA bindings for WGL extensions
   - wglDXOpenDeviceNV
   - wglDXRegisterObjectNV
   - wglDXLockObjectsNV / wglDXUnlockObjectsNV"
  [gl-texture-id d3d11-texture width height]
  (throw (ex-info "D3D11/GL interop not yet implemented"
                  {:gl-texture-id gl-texture-id
                   :d3d11-texture d3d11-texture})))

(comment
  "D3D11/GL interop implementation outline:

   ;; WGL_NV_DX_interop extension functions (via JNA):
   ;; opengl32.dll + wglGetProcAddress
   ;;
   ;; Key functions:
   ;; - wglDXOpenDeviceNV(d3d_device) -> HANDLE
   ;; - wglDXCloseDeviceNV(device_handle)
   ;; - wglDXRegisterObjectNV(device, d3d_object, name, type, access) -> HANDLE
   ;; - wglDXUnregisterObjectNV(device, object)
   ;; - wglDXLockObjectsNV(device, count, objects*)
   ;; - wglDXUnlockObjectsNV(device, count, objects*)

   ;; Workflow:
   ;; 1. Get D3D11 device from FFmpeg hwaccel context
   ;; 2. Open D3D11 device with wglDXOpenDeviceNV (once, cached)
   ;; 3. Create GL texture
   ;; 4. For each frame:
   ;;    a. Get D3D11 texture from decoded frame
   ;;    b. Register D3D11 texture with GL (or use cached registration)
   ;;    c. Lock the object for GL access
   ;;    d. GL texture now contains the frame, use with Skia
   ;;    e. Unlock the object

   ;; Access flags:
   (def WGL_ACCESS_READ_ONLY_NV 0x0000)
   (def WGL_ACCESS_READ_WRITE_NV 0x0001)
   (def WGL_ACCESS_WRITE_DISCARD_NV 0x0002)")

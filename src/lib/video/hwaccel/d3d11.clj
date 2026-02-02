(ns lib.video.hwaccel.d3d11
  "Windows D3D11 zero-copy path: D3D11 texture -> OpenGL texture.

   This module provides JNA bindings and infrastructure for the zero-copy
   path on Windows using D3D11VA/WGL_NV_DX_interop:
   1. FFmpeg decodes to D3D11 texture (via D3D11VA hardware decoder)
   2. Open D3D11 device with WGL via wglDXOpenDeviceNV (once)
   3. Register D3D11 texture with GL via wglDXRegisterObjectNV (per texture)
   4. Lock/unlock for each frame via wglDXLockObjectsNV/wglDXUnlockObjectsNV

   Requirements:
   - Windows 8+ with D3D11 support
   - OpenGL driver with WGL_NV_DX_interop extension
     (works on NVIDIA, AMD, and Intel despite the 'NV' name)
   - opengl32.dll (standard Windows)

   The key function for the zero-copy decoder is `bind-hw-frame-to-texture!`
   which takes the raw hardware frame pointer (ID3D11Texture2D from
   AVFrame.data[0]) and binds it to a GL texture."
  (:require [lib.video.hwaccel.detect :as detect]
            [lib.video.hwaccel.hw-protocol :as hw-proto])
  (:import [com.sun.jna Library Native Pointer NativeLong Memory Function Platform]
           [com.sun.jna.ptr PointerByReference IntByReference]
           [com.sun.jna.win32 StdCallLibrary]
           [org.lwjgl.opengl GL11]))

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
;; Debug Logging
;; ============================================================

(defonce ^:private debug-enabled (atom false))

(defn enable-debug!
  "Enable debug logging for D3D11."
  []
  (reset! debug-enabled true))

(defn- debug-log
  "Log message if debug is enabled."
  [& args]
  (when @debug-enabled
    (apply println "[d3d11]" args)))

;; ============================================================
;; WGL_NV_DX_interop Constants
;; ============================================================

;; Access flags for wglDXRegisterObjectNV
(def ^:private WGL_ACCESS_READ_ONLY_NV 0x0000)
(def ^:private WGL_ACCESS_READ_WRITE_NV 0x0001)
(def ^:private WGL_ACCESS_WRITE_DISCARD_NV 0x0002)

;; Object types for wglDXRegisterObjectNV
(def ^:private WGL_TEXTURE_2D 0x0DE1)  ; Same as GL_TEXTURE_2D

;; GL constants
(def ^:private GL_TEXTURE_2D 0x0DE1)

;; ============================================================
;; JNA Interface: OpenGL32 (for wglGetProcAddress)
;; ============================================================

(definterface IOpenGL32
  ;; Get extension function pointer
  (^Pointer wglGetProcAddress [^String name])
  ;; Get current DC
  (^Pointer wglGetCurrentDC [])
  ;; Get current context
  (^Pointer wglGetCurrentContext []))

(def ^:private opengl32
  (when (available?)
    (try
      (Native/load "opengl32" IOpenGL32)
      (catch Exception e
        (debug-log "Failed to load opengl32.dll:" (.getMessage e))
        nil))))

;; ============================================================
;; WGL Extension Function Pointers
;; ============================================================

;; We need to get the extension function pointers via wglGetProcAddress
;; Store them as atoms so we can initialize lazily

(defonce ^:private wgl-dx-open-device-nv (atom nil))
(defonce ^:private wgl-dx-close-device-nv (atom nil))
(defonce ^:private wgl-dx-register-object-nv (atom nil))
(defonce ^:private wgl-dx-unregister-object-nv (atom nil))
(defonce ^:private wgl-dx-lock-objects-nv (atom nil))
(defonce ^:private wgl-dx-unlock-objects-nv (atom nil))

(defn- get-proc-address
  "Get a WGL extension function by name."
  [^String name]
  (when opengl32
    (let [ptr (.wglGetProcAddress opengl32 name)]
      (when (and ptr (not (.equals ptr Pointer/NULL)))
        (debug-log "Got proc address for" name ":" ptr)
        ptr))))

(defn- init-wgl-extensions!
  "Initialize WGL_NV_DX_interop extension function pointers."
  []
  (when (and opengl32 (nil? @wgl-dx-open-device-nv))
    (debug-log "Initializing WGL_NV_DX_interop extension")

    (reset! wgl-dx-open-device-nv
            (get-proc-address "wglDXOpenDeviceNV"))
    (reset! wgl-dx-close-device-nv
            (get-proc-address "wglDXCloseDeviceNV"))
    (reset! wgl-dx-register-object-nv
            (get-proc-address "wglDXRegisterObjectNV"))
    (reset! wgl-dx-unregister-object-nv
            (get-proc-address "wglDXUnregisterObjectNV"))
    (reset! wgl-dx-lock-objects-nv
            (get-proc-address "wglDXLockObjectsNV"))
    (reset! wgl-dx-unlock-objects-nv
            (get-proc-address "wglDXUnlockObjectsNV"))

    (let [all-found? (and @wgl-dx-open-device-nv
                          @wgl-dx-close-device-nv
                          @wgl-dx-register-object-nv
                          @wgl-dx-unregister-object-nv
                          @wgl-dx-lock-objects-nv
                          @wgl-dx-unlock-objects-nv)]
      (debug-log "WGL extension init" (if all-found? "successful" "FAILED"))
      all-found?)))

;; ============================================================
;; WGL Function Wrappers
;; ============================================================

;; These wrap the raw function pointers with proper calling convention

(defn- call-wgl-dx-open-device
  "Call wglDXOpenDeviceNV(d3d_device) -> HANDLE"
  [d3d-device]
  (when-let [proc @wgl-dx-open-device-nv]
    (let [fn (Function/getFunction proc Function/C_CONVENTION)]
      (.invoke fn Pointer (to-array [d3d-device])))))

(defn- call-wgl-dx-close-device
  "Call wglDXCloseDeviceNV(device_handle) -> BOOL"
  [device-handle]
  (when-let [proc @wgl-dx-close-device-nv]
    (let [fn (Function/getFunction proc Function/C_CONVENTION)]
      (not (zero? (.invoke fn Integer (to-array [device-handle])))))))

(defn- call-wgl-dx-register-object
  "Call wglDXRegisterObjectNV(device, d3d_object, name, type, access) -> HANDLE"
  [device-handle d3d-object gl-name gl-type access]
  (when-let [proc @wgl-dx-register-object-nv]
    (let [fn (Function/getFunction proc Function/C_CONVENTION)]
      (.invoke fn Pointer (to-array [device-handle d3d-object
                                     (int gl-name) (int gl-type) (int access)])))))

(defn- call-wgl-dx-unregister-object
  "Call wglDXUnregisterObjectNV(device, object) -> BOOL"
  [device-handle object-handle]
  (when-let [proc @wgl-dx-unregister-object-nv]
    (let [fn (Function/getFunction proc Function/C_CONVENTION)]
      (not (zero? (.invoke fn Integer (to-array [device-handle object-handle])))))))

(defn- call-wgl-dx-lock-objects
  "Call wglDXLockObjectsNV(device, count, objects*) -> BOOL"
  [device-handle ^Memory objects-array count]
  (when-let [proc @wgl-dx-lock-objects-nv]
    (let [fn (Function/getFunction proc Function/C_CONVENTION)]
      (not (zero? (.invoke fn Integer (to-array [device-handle (int count) objects-array])))))))

(defn- call-wgl-dx-unlock-objects
  "Call wglDXUnlockObjectsNV(device, count, objects*) -> BOOL"
  [device-handle ^Memory objects-array count]
  (when-let [proc @wgl-dx-unlock-objects-nv]
    (let [fn (Function/getFunction proc Function/C_CONVENTION)]
      (not (zero? (.invoke fn Integer (to-array [device-handle (int count) objects-array])))))))

;; ============================================================
;; D3D11 Device Handle Cache
;; ============================================================

;; The WGL device handle created by wglDXOpenDeviceNV
(defonce ^:private wgl-device-handle (atom nil))
;; The D3D11 device pointer
(defonce ^:private d3d11-device-ptr (atom nil))

(defn- ensure-wgl-device-open!
  "Ensure the D3D11 device is opened with WGL.
   d3d11-device should be ID3D11Device pointer."
  [^Pointer d3d11-device]
  (when (and d3d11-device (nil? @wgl-device-handle))
    (init-wgl-extensions!)
    (when-let [handle (call-wgl-dx-open-device d3d11-device)]
      (if (not (.equals handle Pointer/NULL))
        (do
          (debug-log "Opened D3D11 device with WGL, handle:" handle)
          (reset! wgl-device-handle handle)
          (reset! d3d11-device-ptr d3d11-device)
          handle)
        (do
          (debug-log "wglDXOpenDeviceNV returned NULL")
          nil)))))

(defn close-wgl-device!
  "Close the WGL device handle."
  []
  (when-let [handle @wgl-device-handle]
    (call-wgl-dx-close-device handle)
    (reset! wgl-device-handle nil)
    (reset! d3d11-device-ptr nil)
    (debug-log "Closed WGL device")))

;; ============================================================
;; Registered Object Cache
;; ============================================================

;; Cache of registered D3D11 textures
;; Key: [gl-texture-id d3d11-texture-ptr]
;; Value: WGL object handle
(defonce ^:private registered-objects (atom {}))

(defn- get-or-register-object!
  "Get or create a WGL object handle for D3D11 texture -> GL texture binding."
  [gl-texture-id ^Pointer d3d11-texture]
  (let [key [gl-texture-id (Pointer/nativeValue d3d11-texture)]]
    (or (get @registered-objects key)
        (when-let [wgl-handle @wgl-device-handle]
          (let [obj-handle (call-wgl-dx-register-object
                             wgl-handle
                             d3d11-texture
                             gl-texture-id
                             GL_TEXTURE_2D
                             WGL_ACCESS_READ_ONLY_NV)]
            (if (and obj-handle (not (.equals obj-handle Pointer/NULL)))
              (do
                (debug-log "Registered D3D11 texture with GL texture" gl-texture-id)
                (swap! registered-objects assoc key obj-handle)
                obj-handle)
              (do
                (debug-log "wglDXRegisterObjectNV failed")
                nil)))))))

(defn- unregister-object!
  "Unregister a D3D11/GL texture binding."
  [gl-texture-id d3d11-texture-ptr]
  (let [key [gl-texture-id d3d11-texture-ptr]]
    (when-let [obj-handle (get @registered-objects key)]
      (when-let [wgl-handle @wgl-device-handle]
        (call-wgl-dx-unregister-object wgl-handle obj-handle))
      (swap! registered-objects dissoc key))))

;; ============================================================
;; Zero-Copy Frame Binding
;; ============================================================

(defn bind-hw-frame-to-texture!
  "Bind a D3D11 hardware frame to an OpenGL texture via WGL_NV_DX_interop.

   hw-data-ptr: Pointer from AVFrame.data[0] - this is an ID3D11Texture2D*
   texture-info: Map with :texture-id, :texture-type, :width, :height
                          :d3d11-device (ID3D11Device*, required for first call)

   The binding works by:
   1. Opening the D3D11 device with WGL (once, cached)
   2. Registering the D3D11 texture with the GL texture (cached)
   3. Locking the object for GL access
   4. After this, the GL texture contains the D3D11 texture contents

   Returns true on success, false on failure."
  [^org.bytedeco.javacpp.Pointer hw-data-ptr texture-info]
  (when (and (available?) hw-data-ptr (not (.isNull hw-data-ptr)))
    (let [{:keys [texture-id width height d3d11-device]} texture-info]

      (debug-log "Binding D3D11 frame to texture" texture-id
                 "dims:" width "x" height)

      ;; Convert JavaCPP pointer to JNA pointer
      (let [d3d11-texture (Pointer. (.address hw-data-ptr))]

        ;; Ensure WGL device is open
        (when (and (nil? @wgl-device-handle) d3d11-device)
          (ensure-wgl-device-open! (Pointer. (.address d3d11-device))))

        (if-not @wgl-device-handle
          (do
            (debug-log "No WGL device handle, cannot bind")
            false)

          ;; Register or get cached object handle
          (if-let [obj-handle (get-or-register-object! texture-id d3d11-texture)]
            ;; Lock the object for GL access
            (let [objects-array (doto (Memory. 8)
                                  (.setLong 0 (Pointer/nativeValue obj-handle)))]
              (if (call-wgl-dx-lock-objects @wgl-device-handle objects-array 1)
                (do
                  (debug-log "Locked D3D11 texture for GL access")
                  ;; The GL texture now contains the D3D11 texture contents
                  ;; Note: We immediately unlock since Skia will sample the texture
                  ;; In a real implementation, we'd track lock state
                  (call-wgl-dx-unlock-objects @wgl-device-handle objects-array 1)
                  true)
                (do
                  (debug-log "wglDXLockObjectsNV failed")
                  false)))
            false))))))

;; ============================================================
;; HardwareFrame Implementation
;; ============================================================

(defrecord D3D11Frame [d3d11-texture d3d11-device wgl-handle width height released?]
  hw-proto/HardwareFrame

  (bind-to-texture! [_frame texture-info]
    (when (and d3d11-texture (not @released?))
      ;; Create a JavaCPP-like pointer from JNA pointer
      (let [ptr (org.bytedeco.javacpp.Pointer. (Pointer/nativeValue d3d11-texture))]
        (bind-hw-frame-to-texture! ptr
                                    (assoc texture-info :d3d11-device d3d11-device)))))

  (get-dimensions [_frame]
    [width height])

  (get-pixel-format [_frame]
    :nv12) ; D3D11VA typically outputs NV12

  (release! [_frame]
    (reset! released? true)
    ;; D3D11 texture is owned by FFmpeg/AVFrame, we don't release it
    nil))

(defn create-d3d11-frame
  "Create a D3D11Frame from a D3D11 texture pointer.

   d3d11-texture-ptr: ID3D11Texture2D* (JNA Pointer)
   d3d11-device-ptr: ID3D11Device* (JNA Pointer)
   width, height: Frame dimensions"
  [^Pointer d3d11-texture-ptr ^Pointer d3d11-device-ptr width height]
  (when (and d3d11-texture-ptr
             (not (.equals d3d11-texture-ptr Pointer/NULL)))
    (->D3D11Frame d3d11-texture-ptr d3d11-device-ptr nil
                   width height (atom false))))

;; ============================================================
;; Cleanup
;; ============================================================

(defn cleanup-registered-objects!
  "Unregister all D3D11/GL texture bindings."
  []
  (when-let [wgl-handle @wgl-device-handle]
    (doseq [[_key obj-handle] @registered-objects]
      (call-wgl-dx-unregister-object wgl-handle obj-handle)))
  (reset! registered-objects {}))

(defn cleanup-all!
  "Clean up all WGL resources."
  []
  (cleanup-registered-objects!)
  (close-wgl-device!))

;; ============================================================
;; Zero-Copy Availability Check
;; ============================================================

(defn zero-copy-available?
  "Check if all components for D3D11/GL interop are available."
  []
  (and (available?)
       (some? opengl32)
       (or (some? @wgl-dx-open-device-nv)
           (init-wgl-extensions!))))

;; ============================================================
;; Extension Query
;; ============================================================

(defn wgl-nv-dx-interop-available?
  "Check if WGL_NV_DX_interop extension is available.
   Must be called from GL context thread."
  []
  (when opengl32
    (init-wgl-extensions!)
    (and @wgl-dx-open-device-nv
         @wgl-dx-register-object-nv
         @wgl-dx-lock-objects-nv)))

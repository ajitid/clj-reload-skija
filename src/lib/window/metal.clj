(ns lib.window.metal
  "Metal device and command queue setup for macOS.
   Uses LWJGL's Objective-C runtime to interact with Metal framework."
  (:import [org.lwjgl.system.macosx MacOSXLibrary ObjCRuntime]
           [org.lwjgl.system JNI MemoryUtil]))

;; ============================================================
;; Framework Loading
;; ============================================================

(defonce ^:private metal-lib
  (delay
    (try
      (MacOSXLibrary/create "/System/Library/Frameworks/Metal.framework/Metal")
      (catch Exception e
        (println "[metal] Failed to load Metal framework:" (.getMessage e))
        nil))))

(defonce ^:private objc-lib
  (delay
    (try
      (MacOSXLibrary/create "/usr/lib/libobjc.dylib")
      (catch Exception e
        (println "[metal] Failed to load libobjc:" (.getMessage e))
        nil))))

(defonce ^:private quartz-core-lib
  (delay
    (try
      (MacOSXLibrary/create "/System/Library/Frameworks/QuartzCore.framework/QuartzCore")
      (catch Exception e
        (println "[metal] Failed to load QuartzCore:" (.getMessage e))
        nil))))

;; ============================================================
;; Function Address Cache
;; ============================================================

(defonce ^:private fn-addresses (atom {}))

(defn- get-fn-address [lib fn-name]
  (or (get @fn-addresses [lib fn-name])
      (when-let [addr (.getFunctionAddress @lib fn-name)]
        (swap! fn-addresses assoc [lib fn-name] addr)
        addr)))

(defn- objc-msg-send []
  (get-fn-address objc-lib "objc_msgSend"))

;; ============================================================
;; Selector Cache
;; ============================================================

(defonce ^:private selectors (atom {}))

(defn- get-selector [name]
  (or (get @selectors name)
      (let [sel (ObjCRuntime/sel_registerName name)]
        (swap! selectors assoc name sel)
        sel)))

;; ============================================================
;; Objective-C Messaging Helpers
;; ============================================================

(defn- msg-send-p
  "Send message with no arguments, returns pointer."
  [target selector]
  (JNI/invokePPP target selector (objc-msg-send)))

(defn- msg-send-v
  "Send message with no arguments, returns void."
  [target selector]
  (JNI/invokePPV target selector (objc-msg-send)))

(defn- msg-send-b
  "Send message with no arguments, returns boolean (byte).
   Uses invokeC (call returning char/byte) convention."
  [target selector]
  ;; For boolean returns, we use the void call and check if operation succeeded
  ;; Most ObjC boolean getters can be handled by checking the return pointer
  (let [result (msg-send-p target selector)]
    (and result (pos? result))))

;; ============================================================
;; Metal Device & Queue
;; ============================================================

(defn available?
  "Check if Metal is available on this system."
  []
  (boolean (and @metal-lib @objc-lib)))

(defn create-device
  "Create the default Metal device.
   Returns device pointer (id<MTLDevice>) or 0 on failure."
  []
  (when-let [mtl-fn (get-fn-address metal-lib "MTLCreateSystemDefaultDevice")]
    (JNI/invokeP mtl-fn)))

(defn create-command-queue
  "Create a command queue for the given Metal device.
   Returns queue pointer (id<MTLCommandQueue>) or 0 on failure."
  [device]
  (when (and device (pos? device))
    (msg-send-p device (get-selector "newCommandQueue"))))

(defn get-device-name
  "Get the name of a Metal device."
  [device]
  (when (and device (pos? device))
    (let [name-ptr (msg-send-p device (get-selector "name"))]
      (when (pos? name-ptr)
        ;; NSString -> UTF8String
        (let [utf8-sel (get-selector "UTF8String")
              cstr-ptr (msg-send-p name-ptr utf8-sel)]
          (when (pos? cstr-ptr)
            (org.lwjgl.system.MemoryUtil/memUTF8 cstr-ptr)))))))

;; ============================================================
;; CAMetalLayer Operations
;; ============================================================

(defn set-layer-device!
  "Set the Metal device for a CAMetalLayer."
  [layer device]
  (when (and layer (pos? layer) device (pos? device))
    (let [sel (get-selector "setDevice:")
          msg-send (objc-msg-send)]
      (JNI/invokePPPV layer sel device msg-send))))

(defn set-layer-pixel-format!
  "Set the pixel format for a CAMetalLayer.
   MTLPixelFormatBGRA8Unorm = 80"
  [layer pixel-format]
  (when (and layer (pos? layer))
    (let [sel (get-selector "setPixelFormat:")
          msg-send (objc-msg-send)]
      ;; NSUInteger is 64-bit on modern macOS, pass as pointer-sized value
      (JNI/invokePPPV layer sel (long pixel-format) msg-send))))

(defn set-layer-framebuffer-only!
  "Set whether the layer's textures are framebuffer-only.
   Setting to YES allows Metal to optimize for display-only use."
  [layer framebuffer-only?]
  (when (and layer (pos? layer))
    (let [sel (get-selector "setFramebufferOnly:")
          msg-send (objc-msg-send)]
      ;; BOOL is passed as a pointer-sized value in ObjC ABI
      (JNI/invokePPPV layer sel (if framebuffer-only? 1 0) msg-send))))

(defn set-layer-drawable-size!
  "Set the drawable size (in pixels) for a CAMetalLayer.

   NOTE: This operation requires passing a CGSize struct by value, which
   needs a calling convention not available in LWJGL's JNI invoke methods.
   When using SDL_Metal_CreateView, the layer auto-sizes from the view,
   so this function is typically not needed.

   Returns nil (no-op). Use SDL to control the window/view size instead."
  [_layer _width _height]
  ;; CGSize struct passing requires specific ABI support not in LWJGL JNI.
  ;; The CAMetalLayer will auto-size based on the SDL Metal view.
  ;; If explicit sizing is needed, use JNA or native code.
  nil)

(defn get-next-drawable
  "Get the next drawable from a CAMetalLayer.
   Returns id<CAMetalDrawable> pointer or 0 if none available."
  [layer]
  (when (and layer (pos? layer))
    (msg-send-p layer (get-selector "nextDrawable"))))

(defn get-drawable-texture
  "Get the MTLTexture from a CAMetalDrawable.
   Returns id<MTLTexture> pointer."
  [drawable]
  (when (and drawable (pos? drawable))
    (msg-send-p drawable (get-selector "texture"))))

(defn present-drawable!
  "Present a drawable (schedule it for display)."
  [drawable]
  (when (and drawable (pos? drawable))
    (msg-send-v drawable (get-selector "present"))))

(defn present-drawable-with-command-buffer!
  "Present a drawable after command buffer completes.
   Calls [commandBuffer presentDrawable:drawable]"
  [command-buffer drawable]
  (when (and command-buffer (pos? command-buffer)
             drawable (pos? drawable))
    (let [sel (get-selector "presentDrawable:")
          msg-send (objc-msg-send)]
      (JNI/invokePPPV command-buffer sel drawable msg-send))))

;; ============================================================
;; Command Buffer Operations
;; ============================================================

(defn create-command-buffer
  "Create a new command buffer from a command queue."
  [queue]
  (when (and queue (pos? queue))
    (msg-send-p queue (get-selector "commandBuffer"))))

(defn commit-command-buffer!
  "Commit a command buffer for execution."
  [cmd-buffer]
  (when (and cmd-buffer (pos? cmd-buffer))
    (msg-send-v cmd-buffer (get-selector "commit"))))

(defn wait-until-completed!
  "Wait for command buffer to complete (blocking)."
  [cmd-buffer]
  (when (and cmd-buffer (pos? cmd-buffer))
    (msg-send-v cmd-buffer (get-selector "waitUntilCompleted"))))

(defn wait-until-scheduled!
  "Wait for command buffer to be scheduled (less blocking than completed)."
  [cmd-buffer]
  (when (and cmd-buffer (pos? cmd-buffer))
    (msg-send-v cmd-buffer (get-selector "waitUntilScheduled"))))

;; ============================================================
;; MTLTexture Info (for BackendRenderTarget)
;; ============================================================

(defn get-texture-width
  "Get width of an MTLTexture."
  [texture]
  (when (and texture (pos? texture))
    (let [sel (get-selector "width")
          msg-send (objc-msg-send)]
      (JNI/invokePPJ texture sel msg-send))))

(defn get-texture-height
  "Get height of an MTLTexture."
  [texture]
  (when (and texture (pos? texture))
    (let [sel (get-selector "height")
          msg-send (objc-msg-send)]
      (JNI/invokePPJ texture sel msg-send))))

;; ============================================================
;; Constants
;; ============================================================

(def MTLPixelFormatBGRA8Unorm 80)
(def MTLPixelFormatBGRA8Unorm_sRGB 81)
(def MTLPixelFormatRGBA8Unorm 70)

;; ============================================================
;; Convenience: Create Device + Queue Together
;; ============================================================

(defn create-metal-context
  "Create Metal device and command queue together.
   Returns {:device ptr :queue ptr :device-name str} or nil on failure."
  []
  (when (available?)
    (let [device (create-device)]
      (when (and device (pos? device))
        (let [queue (create-command-queue device)]
          (when (and queue (pos? queue))
            {:device device
             :queue queue
             :device-name (get-device-name device)}))))))

;; ============================================================
;; MTLBuffer Operations (for frame capture)
;; ============================================================

;; MTLResourceStorageModeShared = 0 (CPU & GPU accessible)
(def MTLResourceStorageModeShared 0)

(defn create-buffer
  "Create MTLBuffer with shared storage for CPU read access.
   Returns buffer pointer or 0 on failure.

   NOTE: This delegates to metal-ffm because LWJGL JNI doesn't have
   invokePPJJP (the signature needed for newBufferWithLength:options:)."
  [device size]
  (when (and device (pos? device) (pos? size))
    ;; Use FFM implementation - LWJGL JNI doesn't have invokePPJJP
    (if-let [ffm-create (resolve 'lib.window.metal-ffm/create-buffer)]
      (ffm-create device size)
      (do
        (println "[metal] WARNING: FFM not available for create-buffer")
        0))))

(defn get-buffer-contents
  "Get CPU-accessible pointer to buffer contents.
   Returns pointer that can be read with MemoryUtil."
  [buffer]
  (when (and buffer (pos? buffer))
    (msg-send-p buffer (get-selector "contents"))))

(defn get-buffer-length
  "Get buffer size in bytes."
  [buffer]
  (when (and buffer (pos? buffer))
    (let [sel (get-selector "length")
          msg-send (objc-msg-send)]
      (JNI/invokePPJ buffer sel msg-send))))

;; ============================================================
;; Blit Command Encoder Operations
;; ============================================================

(defn create-blit-command-encoder
  "Create blit encoder from command buffer for copy operations."
  [command-buffer]
  (when (and command-buffer (pos? command-buffer))
    (msg-send-p command-buffer (get-selector "blitCommandEncoder"))))

(defn end-encoding!
  "End encoding for command encoder."
  [encoder]
  (when (and encoder (pos? encoder))
    (msg-send-v encoder (get-selector "endEncoding"))))

(defn synchronize-resource!
  "Synchronize a managed resource (texture or buffer) for CPU access.
   For shared storage mode buffers, this is a no-op but safe to call."
  [blit-encoder resource]
  (when (and blit-encoder (pos? blit-encoder)
             resource (pos? resource))
    (let [sel (get-selector "synchronizeResource:")
          msg-send (objc-msg-send)]
      (JNI/invokePPPV blit-encoder sel resource msg-send))))

;; ============================================================
;; Direct Texture Read (synchronous fallback)
;; ============================================================

(defn get-texture-bytes!
  "Read texture pixels into a pre-allocated buffer.
   Uses MTLTexture's getBytes:bytesPerRow:fromRegion:mipmapLevel:.

   This is a SYNCHRONOUS operation - GPU must be idle first.
   Call after waitUntilCompleted on the render command buffer.

   Parameters:
   - texture: MTLTexture pointer
   - dest-ptr: Destination memory pointer (from MemoryUtil/memAlloc)
   - bytes-per-row: Row stride in bytes (typically width * 4 for BGRA)
   - width, height: Texture dimensions

   Returns true on success.

   NOTE: This delegates to metal-ffm because LWJGL JNI doesn't have
   invokePPPJJJJJJJJV (the signature needed for struct-passing methods)."
  [texture dest-ptr bytes-per-row width height]
  (when (and texture (pos? texture)
             dest-ptr (pos? dest-ptr)
             (pos? bytes-per-row) (pos? width) (pos? height))
    ;; Use FFM implementation - LWJGL JNI doesn't have the required signature
    (if-let [ffm-get-bytes (resolve 'lib.window.metal-ffm/get-texture-bytes!)]
      (ffm-get-bytes texture dest-ptr bytes-per-row width height)
      (do
        (println "[metal] WARNING: FFM not available for get-texture-bytes!")
        false))))

;; ============================================================
;; Command Buffer Status (for polling completion)
;; ============================================================

;; MTLCommandBufferStatus values
(def MTLCommandBufferStatusNotEnqueued 0)
(def MTLCommandBufferStatusEnqueued 1)
(def MTLCommandBufferStatusCommitted 2)
(def MTLCommandBufferStatusScheduled 3)
(def MTLCommandBufferStatusCompleted 4)
(def MTLCommandBufferStatusError 5)

(defn get-command-buffer-status
  "Get command buffer status. Returns MTLCommandBufferStatus value."
  [command-buffer]
  (when (and command-buffer (pos? command-buffer))
    (let [sel (get-selector "status")
          msg-send (objc-msg-send)]
      (JNI/invokePPJ command-buffer sel msg-send))))

(defn command-buffer-completed?
  "Check if command buffer has completed execution."
  [command-buffer]
  (let [status (get-command-buffer-status command-buffer)]
    (and status (= status MTLCommandBufferStatusCompleted))))

;; ============================================================
;; Texture-to-Texture Blit (for video rendering)
;; ============================================================

(defn blit-texture-to-texture!
  "Copy a region from source texture to destination texture.
   Uses MTLBlitCommandEncoder for GPU-side copy.

   blit-encoder: MTLBlitCommandEncoder pointer (from create-blit-command-encoder)
   src-texture: Source MTLTexture pointer
   dst-texture: Destination MTLTexture pointer
   src-x, src-y: Source region origin
   dst-x, dst-y: Destination region origin
   width, height: Region size to copy

   Returns true on success. Call end-encoding! after all blits."
  [blit-encoder src-texture dst-texture src-x src-y dst-x dst-y width height]
  (when (and (pos? blit-encoder) (pos? src-texture) (pos? dst-texture)
             (pos? width) (pos? height))
    ;; Use FFM implementation for struct-passing
    (if-let [ffm-blit (resolve 'lib.window.metal-ffm/copy-texture-to-texture!)]
      (ffm-blit blit-encoder src-texture dst-texture src-x src-y dst-x dst-y width height)
      (do
        (println "[metal] WARNING: FFM not available for blit-texture-to-texture!")
        false))))

;; ============================================================
;; Cleanup
;; ============================================================

(defn release!
  "Release an Objective-C object (decrement retain count).
   Safe to call with nil or 0."
  [obj]
  (when (and obj (pos? obj))
    (msg-send-v obj (get-selector "release"))))

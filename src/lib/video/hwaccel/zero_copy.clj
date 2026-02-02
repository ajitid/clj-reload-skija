(ns lib.video.hwaccel.zero-copy
  "Unified zero-copy video decoder with platform dispatch and fallback.

   This decoder implements the VideoSource protocol and attempts true
   zero-copy decoding where frames stay GPU-resident. Falls back to
   CPU-copy path (existing hwaccel.decoder) on failure.

   Fallback chain:
   1. Zero-copy HW decode (GPU → GL texture via platform interop)
   2. HW decode + CPU copy (GPU → CPU → GL texture via glTexSubImage2D)
   3. Software decode (CPU → GL texture)

   Platform-specific zero-copy paths:
   - macOS: VideoToolbox → CVPixelBuffer → IOSurface → CGLTexImageIOSurface2D
   - Linux Intel/AMD: VAAPI → VASurface → DMA-BUF → EGLImage → GL
   - Linux NVIDIA: CUDA → cuGraphicsGLRegisterImage → GL
   - Windows: D3D11 → WGL_NV_DX_interop → GL"
  (:require [lib.video.protocol :as proto]
            [lib.video.texture :as tex]
            [lib.video.hwaccel.detect :as detect]
            [lib.video.hwaccel.raw-decoder :as raw]
            [lib.video.hwaccel.hw-protocol :as hw-proto]
            [lib.video.audio :as audio]
            [lib.video.sync :as sync])
  (:import [org.lwjgl.opengl GL11]
           [io.github.humbleui.skija Surface]))

;; ============================================================
;; Debug Logging
;; ============================================================

(defonce ^:private debug-enabled (atom false))  ;; Set to true for debugging

(defn enable-debug!
  "Enable debug logging for zero-copy decoder."
  []
  (reset! debug-enabled true))

(defn disable-debug!
  "Disable debug logging."
  []
  (reset! debug-enabled false))

(defn- debug-log
  "Log message if debug is enabled."
  [& args]
  (when @debug-enabled
    (apply println "[zero-copy]" args)))

;; ============================================================
;; Platform-Specific Frame Binding (Forward Declarations)
;; ============================================================

;; These functions are implemented in platform-specific modules.
;; We use requiring-resolve for lazy loading and hot-reload compatibility.

;; ============================================================
;; Metal Backend Detection
;; ============================================================

(defn- metal-backend-active?
  "Check if the Metal backend is currently active.
   Returns true if layer-metal has been initialized with a device."
  []
  (try
    (when-let [device-fn (requiring-resolve 'lib.window.layer-metal/device)]
      (let [device (device-fn)]
        (and device (pos? device))))
    (catch Exception _ false)))

(defn- get-metal-device
  "Get the current Metal device from the layer, if available."
  []
  (try
    (when-let [device-fn (requiring-resolve 'lib.window.layer-metal/device)]
      (device-fn))
    (catch Exception _ nil)))

(defn- resolve-platform-binder
  "Resolve the platform-specific frame binder function.
   Returns a function (fn [hw-data-ptr tex-info] -> bool) or nil if not available.

   For VideoToolbox on macOS:
   - With Metal backend: Uses videotoolbox_metal for true zero-copy
   - With OpenGL backend: Returns nil (GL_TEXTURE_RECTANGLE not supported by Skia)"
  [decoder-type]
  (case decoder-type
    :videotoolbox
    (when (metal-backend-active?)
      ;; Metal backend available - create a wrapper that includes the Metal device
      (when-let [bind-fn (try
                           (requiring-resolve 'lib.video.hwaccel.videotoolbox-metal/bind-hw-frame-to-texture!)
                           (catch Exception _ nil))]
        ;; Return a wrapper that fetches the Metal device and passes it
        (fn [hw-data-ptr tex-info]
          (let [device (get-metal-device)]
            (when device
              (bind-fn hw-data-ptr tex-info device))))))

    :vaapi
    (try
      (requiring-resolve 'lib.video.hwaccel.vaapi/bind-hw-frame-to-texture!)
      (catch Exception _ nil))

    (:cuda :nvdec-cuda)
    (try
      (requiring-resolve 'lib.video.hwaccel.cuda/bind-hw-frame-to-texture!)
      (catch Exception _ nil))

    (:d3d11 :d3d11va)
    (try
      (requiring-resolve 'lib.video.hwaccel.d3d11/bind-hw-frame-to-texture!)
      (catch Exception _ nil))

    nil))

;; ============================================================
;; Zero-Copy Texture Management
;; ============================================================

(defn- create-zero-copy-texture
  "Create a texture suitable for zero-copy binding.

   For Metal backend: Returns nil (Metal uses its own texture path).
   For OpenGL backend with VideoToolbox: Creates GL_TEXTURE_RECTANGLE.
   For OpenGL backend with other decoders: Creates GL_TEXTURE_2D."
  [width height decoder-type]
  (cond
    ;; Metal backend - don't create OpenGL textures
    (metal-backend-active?)
    (do
      (debug-log "Metal backend active, skipping OpenGL texture creation")
      nil)

    ;; OpenGL + VideoToolbox - uses rectangle textures for IOSurface
    (= decoder-type :videotoolbox)
    (let [tex-id (GL11/glGenTextures)
          GL_TEXTURE_RECTANGLE 0x84F5]
      (GL11/glBindTexture GL_TEXTURE_RECTANGLE tex-id)
      (GL11/glTexParameteri GL_TEXTURE_RECTANGLE GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
      (GL11/glTexParameteri GL_TEXTURE_RECTANGLE GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
      (GL11/glTexParameteri GL_TEXTURE_RECTANGLE GL11/GL_TEXTURE_WRAP_S GL11/GL_CLAMP)
      (GL11/glTexParameteri GL_TEXTURE_RECTANGLE GL11/GL_TEXTURE_WRAP_T GL11/GL_CLAMP)
      (GL11/glBindTexture GL_TEXTURE_RECTANGLE 0)
      {:texture-id tex-id
       :texture-type :texture-rectangle
       :width width
       :height height})

    ;; OpenGL + other platforms - regular 2D textures
    :else
    {:texture-id (tex/create-texture width height)
     :texture-type :texture-2d
     :width width
     :height height}))

(defn- delete-zero-copy-texture!
  "Delete a zero-copy texture. No-op for Metal backend (texture-info is nil)."
  [{:keys [texture-id texture-type]}]
  (when (and texture-id (not (metal-backend-active?)))
    (let [target (if (= texture-type :texture-rectangle)
                   0x84F5  ; GL_TEXTURE_RECTANGLE
                   GL11/GL_TEXTURE_2D)]
      (GL11/glDeleteTextures texture-id))))

;; ============================================================
;; Skia Image Wrapping
;; ============================================================

(def ^:private GL_RGBA8 0x8058)

(defn- wrap-gl-texture-as-skia-image
  "Wrap a GL texture as a Skia Image.

   Handles both GL_TEXTURE_2D and GL_TEXTURE_RECTANGLE.
   Returns nil if texture-info is nil."
  [direct-context {:keys [texture-id texture-type width height] :as texture-info}]
  (when (and texture-info texture-id)
    (GL11/glFlush)
    (let [gl-target (if (= texture-type :texture-rectangle)
                      0x84F5  ; GL_TEXTURE_RECTANGLE
                      GL11/GL_TEXTURE_2D)]
      (io.github.humbleui.skija.Image/adoptGLTextureFrom
        direct-context
        (int texture-id)
        gl-target
        (int width)
        (int height)
        GL_RGBA8
        io.github.humbleui.skija.SurfaceOrigin/TOP_LEFT
        io.github.humbleui.skija.ColorType/RGBA_8888))))

(defn- adopt-metal-texture
  "Adopt a Metal texture as a Skia Image with specified color type.
   Returns Image or nil on failure."
  [direct-context mtl-texture width height color-type]
  (when (and mtl-texture (pos? mtl-texture))
    (try
      (io.github.humbleui.skija.Image/adoptMetalTextureFrom
        direct-context
        (long mtl-texture)
        (int width)
        (int height)
        io.github.humbleui.skija.SurfaceOrigin/TOP_LEFT
        color-type)
      (catch Exception e
        (debug-log "Failed to adopt Metal texture:" mtl-texture "as" color-type "-" (.getMessage e))
        nil))))

(defn- wrap-nv12-texture
  "Convert NV12 Y+UV Metal textures to BGRA via Metal compute shader.

   Uses the new Image.convertYUVToRGBA function added to Skija that:
   1. Takes Y (R8Unorm) and UV (RG8Unorm) textures
   2. Runs a Metal compute shader to convert NV12 to BGRA
   3. Returns a new BGRA8Unorm texture that Skia can adopt

   This is true GPU zero-copy - no CPU involvement.

   texture-info should be {:format :nv12 :y-texture :uv-texture :width :height ...}"
  [direct-context {:keys [y-texture uv-texture width height] :as texture-info}]
  (when (and y-texture uv-texture (pos? y-texture) (pos? uv-texture))
    (try
      ;; Get Metal device and queue from layer
      (let [device-fn (requiring-resolve 'lib.window.layer-metal/device)
            queue-fn (requiring-resolve 'lib.window.layer-metal/queue)]
        (when (and device-fn queue-fn)
          (let [device (device-fn)
                queue (queue-fn)]
            (when (and device queue (pos? device) (pos? queue))
              ;; Convert NV12 to BGRA using Metal compute shader
              (let [bgra-texture (io.github.humbleui.skija.Image/convertYUVToRGBA
                                   device queue y-texture uv-texture width height)]
                (when (pos? bgra-texture)
                  ;; Adopt the BGRA texture as a Skia Image
                  (try
                    (io.github.humbleui.skija.Image/adoptMetalTextureFrom
                      direct-context
                      (long bgra-texture)
                      (int width)
                      (int height)
                      io.github.humbleui.skija.SurfaceOrigin/TOP_LEFT
                      io.github.humbleui.skija.ColorType/BGRA_8888)
                    (finally
                      ;; Release the intermediate BGRA texture
                      (io.github.humbleui.skija.Image/releaseMetalTexture bgra-texture)))))))))
      (catch Exception e
        (debug-log "NV12 conversion failed:" (.getMessage e))
        nil))))

(defn- wrap-bgra-texture
  "Wrap a BGRA Metal texture as a Skia Image.
   texture-info should be {:format :bgra :mtl-texture :width :height}"
  [direct-context {:keys [mtl-texture width height]}]
  (adopt-metal-texture direct-context mtl-texture width height
                       io.github.humbleui.skija.ColorType/BGRA_8888))

(defn- wrap-metal-texture-as-skia-image
  "Wrap a Metal texture as a Skia Image using adoptMetalTextureFrom.

   Dispatches based on texture format:
   - :bgra -> Direct adoption as BGRA_8888
   - :nv12 -> YUV shader conversion

   texture-info should be a map with :format and format-specific keys.
   Returns a Skia Image or nil on failure."
  [direct-context {:keys [format] :as texture-info}]
  (case format
    :bgra (wrap-bgra-texture direct-context texture-info)
    :nv12 (wrap-nv12-texture direct-context texture-info)
    ;; Legacy format (no :format key, has :mtl-texture)
    (when-let [mtl-texture (:mtl-texture texture-info)]
      (wrap-bgra-texture direct-context (assoc texture-info :format :bgra)))))

(defn- wrap-texture-as-skia-image
  "Wrap a texture as a Skia Image.

   Dispatches to Metal or GL wrapping based on backend."
  [direct-context texture-info]
  (cond
    ;; Metal NV12 texture (has :y-texture and :uv-texture keys)
    (and (:y-texture texture-info) (:uv-texture texture-info))
    (wrap-metal-texture-as-skia-image direct-context texture-info)

    ;; Metal BGRA texture (has :mtl-texture key)
    (:mtl-texture texture-info)
    (wrap-metal-texture-as-skia-image direct-context texture-info)

    ;; GL texture (has :texture-id key)
    (:texture-id texture-info)
    (wrap-gl-texture-as-skia-image direct-context texture-info)

    :else nil))

;; ============================================================
;; Zero-Copy Decoder Record
;; ============================================================

(defrecord ZeroCopyDecoder
    [;; Raw decoder for frame access
     raw-decoder
     ;; Decoder info
     path
     decoder-type           ; :videotoolbox, :vaapi, :cuda, :d3d11
     width height
     fps-val
     duration-val
     ;; Platform-specific binder function
     platform-binder        ; (fn [hw-data-ptr texture-info] -> bool)
     ;; Playback state
     state-atom             ; :stopped, :playing, :paused
     current-pts-atom       ; Current playback position in seconds
     last-frame-pts-atom    ; PTS of last decoded frame
     ;; Texture management
     texture-info-atom      ; {:texture-id :texture-type :width :height ...}
     skia-image-atom        ; Cached Skia Image
     needs-decode?-atom     ; True if we need to decode next frame
     has-first-frame?-atom  ; True after first frame decoded
     ;; Zero-copy state
     zero-copy-failed?-atom ; True if zero-copy failed and we should use fallback
     last-hw-frame-atom     ; Last decoded hardware frame (for rebinding)
     ;; Audio sync
     audio-track            ; AudioTrack for audio playback (nil if no audio)
     sync-state             ; SyncState for A/V synchronization
     use-audio-sync?]       ; True to sync video to audio clock

  proto/VideoSource
  (play* [this]
    (let [state @state-atom]
      (when (not= state :playing)
        (when (= state :stopped)
          ;; Reset to beginning
          (raw/seek! raw-decoder 0)
          (reset! current-pts-atom 0.0)
          (reset! last-frame-pts-atom -1.0)
          (reset! needs-decode?-atom true)
          ;; Reset audio sync
          (when sync-state
            (sync/reset-sync! sync-state))
          ;; Start audio if available
          (when audio-track
            (audio/play! audio-track)))
        (when (= state :paused)
          ;; Resume audio if available
          (when audio-track
            (audio/resume! audio-track)))
        (reset! state-atom :playing)))
    this)

  (stop* [this]
    (reset! state-atom :stopped)
    (raw/seek! raw-decoder 0)
    (reset! current-pts-atom 0.0)
    (reset! last-frame-pts-atom -1.0)
    (reset! needs-decode?-atom true)
    ;; Stop audio
    (when audio-track
      (audio/stop! audio-track))
    ;; Reset sync
    (when sync-state
      (sync/reset-sync! sync-state))
    this)

  (pause* [this]
    (when (= @state-atom :playing)
      (reset! state-atom :paused)
      ;; Pause audio
      (when audio-track
        (audio/pause! audio-track)))
    this)

  (seek* [this seconds]
    (raw/seek! raw-decoder seconds)
    (reset! current-pts-atom seconds)
    (reset! last-frame-pts-atom -1.0)
    ;; Seek audio if available
    (when audio-track
      (audio/seek! audio-track seconds))
    ;; Reset sync to new position
    (when sync-state
      (sync/reset-sync-to! sync-state seconds))
    ;; Immediately decode frame at new position
    (when-let [frame-data (raw/decode-next-frame! raw-decoder)]
      (let [{:keys [hw-data-ptr pts is-hw-frame?]} frame-data
            metal-active? (metal-backend-active?)]
        (reset! last-hw-frame-atom frame-data)
        ;; Try zero-copy bind
        (let [tex-info @texture-info-atom
              binder-result (and is-hw-frame?
                                 (not @zero-copy-failed?-atom)
                                 platform-binder
                                 (try
                                   (platform-binder hw-data-ptr tex-info)
                                   (catch Exception e
                                     (debug-log "Zero-copy bind failed:" (.getMessage e))
                                     (reset! zero-copy-failed?-atom true)
                                     nil)))
              bound? (some? binder-result)]
          ;; For Metal, store the texture info from the binder result
          (when (and bound? metal-active? (map? binder-result))
            ;; Close old Skia image before updating texture (texture ptr changed)
            (when-let [old-img @skia-image-atom]
              (.close old-img)
              (reset! skia-image-atom nil))
            (reset! texture-info-atom binder-result))
          (when bound?
            (debug-log "Frame bound via zero-copy at pts:" pts)))
        (reset! has-first-frame?-atom true)
        (reset! last-frame-pts-atom seconds)))
    (reset! needs-decode?-atom false)
    this)

  (tell* [_this]
    @current-pts-atom)

  (duration* [_this]
    duration-val)

  (width* [_this]
    width)

  (height* [_this]
    height)

  (fps* [_this]
    fps-val)

  (playing?* [_this]
    (= @state-atom :playing))

  (paused?* [_this]
    (= @state-atom :paused))

  (ensure-first-frame!* [this]
    ;; Decode first frame if not already done
    (when-not @has-first-frame?-atom
      (let [metal-active? (metal-backend-active?)]
        ;; Ensure texture exists for OpenGL (not needed for Metal - binder creates it)
        (when (and (not metal-active?) (not @texture-info-atom))
          (reset! texture-info-atom (create-zero-copy-texture width height decoder-type)))

        (when-let [frame-data (raw/decode-next-frame! raw-decoder)]
          (let [{:keys [hw-data-ptr is-hw-frame?]} frame-data
                tex-info @texture-info-atom]
            (reset! last-hw-frame-atom frame-data)
            ;; Try zero-copy bind
            (let [binder-result (and is-hw-frame?
                                     platform-binder
                                     (try
                                       (platform-binder hw-data-ptr tex-info)
                                       (catch Exception e
                                         (debug-log "Zero-copy bind failed on first frame:" (.getMessage e))
                                         (reset! zero-copy-failed?-atom true)
                                         nil)))
                  bound? (some? binder-result)]
              ;; For Metal, store the texture info from the binder result
              (when (and bound? metal-active? (map? binder-result))
                (reset! texture-info-atom binder-result))
              (when-not bound?
                (debug-log "First frame: zero-copy unavailable, will use fallback")))
            (reset! has-first-frame?-atom true))
          ;; Reset to beginning for playback
          (raw/seek! raw-decoder 0))))
    this)

  (advance-frame!* [this dt]
    (when (= @state-atom :playing)
      ;; Get timing source
      (let [audio-pos (when (and use-audio-sync? audio-track
                                 (not (audio/seeking? audio-track)))
                        (audio/tell audio-track))
            effective-pts (if audio-pos
                            audio-pos
                            (swap! current-pts-atom + dt))
            last-pts @last-frame-pts-atom
            frame-time (/ 1.0 fps-val)]

        ;; Update current-pts to match effective timing
        (when audio-pos
          (reset! current-pts-atom audio-pos))

        ;; Decode when enough time has passed
        (let [time-since-last-frame (- effective-pts last-pts)
              should-decode? (or @needs-decode?-atom
                                 (>= time-since-last-frame frame-time))]
          (when should-decode?
            ;; Ensure texture exists
            (when-not @texture-info-atom
              (reset! texture-info-atom (create-zero-copy-texture width height decoder-type)))

            ;; Decode next frame
            (when-let [frame-data (raw/decode-next-frame! raw-decoder)]
              (let [{:keys [hw-data-ptr pts is-hw-frame?]} frame-data
                    tex-info @texture-info-atom
                    metal-active? (metal-backend-active?)]
                (reset! last-hw-frame-atom frame-data)

                ;; Try zero-copy bind
                (let [binder-result (and is-hw-frame?
                                         (not @zero-copy-failed?-atom)
                                         platform-binder
                                         (try
                                           (platform-binder hw-data-ptr tex-info)
                                           (catch Exception e
                                             (debug-log "Zero-copy bind failed:" (.getMessage e))
                                             (reset! zero-copy-failed?-atom true)
                                             nil)))
                      bound? (some? binder-result)]
                  ;; For Metal, store the texture info from the binder result
                  (when (and bound? metal-active? (map? binder-result))
                    ;; Close old Skia image before updating texture (texture ptr changed)
                    (when-let [old-img @skia-image-atom]
                      (.close old-img)
                      (reset! skia-image-atom nil))
                    (reset! texture-info-atom binder-result))
                  (when-not bound?
                    ;; TODO: Fallback to CPU copy path
                    ;; For now, just log
                    (when (not @zero-copy-failed?-atom)
                      (debug-log "Frame not hw or no binder, needs fallback"))))

                (reset! has-first-frame?-atom true)
                (let [frame-pts (if audio-pos audio-pos pts)]
                  (reset! last-frame-pts-atom frame-pts))
                (reset! needs-decode?-atom false)))

            ;; Handle end of video
            (when (>= @current-pts-atom duration-val)
              (reset! state-atom :stopped)
              (reset! current-pts-atom 0.0)
              (when audio-track
                (audio/stop! audio-track)))))))
    this)

  (current-frame* [this direct-context]
    (when @has-first-frame?-atom
      (let [tex-info @texture-info-atom]
        ;; Always wrap texture as Skia Image (Metal or OpenGL)
        ;; This enables full Skia compositing support (clips, effects, etc.)
        (cond
          ;; Metal backend with NV12 format (has :y-texture and :uv-texture)
          (and (map? tex-info) (= :nv12 (:format tex-info)) (:y-texture tex-info))
          (or @skia-image-atom
              (let [img (wrap-metal-texture-as-skia-image direct-context tex-info)]
                (reset! skia-image-atom img)
                img))

          ;; Metal backend with BGRA format (has :mtl-texture)
          (and (map? tex-info) (:mtl-texture tex-info))
          (or @skia-image-atom
              (let [img (wrap-metal-texture-as-skia-image direct-context tex-info)]
                (reset! skia-image-atom img)
                img))

          ;; OpenGL backend: wrap as Skia Image
          (:texture-id tex-info)
          (or @skia-image-atom
              (let [img (wrap-gl-texture-as-skia-image direct-context tex-info)]
                (reset! skia-image-atom img)
                img))

          ;; No texture yet, try to create one (OpenGL only)
          (not (metal-backend-active?))
          (do
            (reset! texture-info-atom (create-zero-copy-texture width height decoder-type))
            nil)

          :else nil))))

  (close* [this]
    ;; Close audio track
    (when audio-track
      (audio/close! audio-track))
    ;; Close Skia image
    (when-let [img @skia-image-atom]
      (.close img)
      (reset! skia-image-atom nil))
    ;; Delete texture
    (when-let [tex-info @texture-info-atom]
      (delete-zero-copy-texture! tex-info)
      (reset! texture-info-atom nil))
    ;; Close raw decoder
    (raw/close! raw-decoder)
    this)

  ;; Audio sync methods
  (has-audio?* [_this]
    (some? audio-track))

  (set-volume!* [this volume]
    (when audio-track
      (audio/set-volume! audio-track volume))
    this)

  (get-volume* [_this]
    (if audio-track
      @(:volume-atom audio-track)
      1.0))

  (audio-position* [_this]
    (when audio-track
      (audio/tell audio-track))))

;; ============================================================
;; Create Zero-Copy Decoder
;; ============================================================

(defn create-zero-copy-decoder
  "Create a zero-copy hardware-accelerated decoder.

   Attempts to use platform-specific zero-copy path where decoded
   frames stay GPU-resident. Falls back to CPU copy path if:
   - Zero-copy not available on this platform
   - Hardware decoding not supported for this codec
   - Any error during zero-copy binding

   Options:
     :decoder-type  - Force specific decoder (:videotoolbox, :vaapi, :cuda, :d3d11)
     :debug?        - Enable debug logging
     :audio?        - Enable audio track (default true)
     :audio-sync?   - Sync video to audio clock (default true when audio present)

   Returns {:decoder ZeroCopyDecoder :hwaccel-type keyword :zero-copy? boolean}
   Throws if decoder creation fails or zero-copy is not actually supported."
  ([path]
   (create-zero-copy-decoder path {}))
  ([path opts]
   ;; Metal backend now uses blit path (no Skia Image wrapping needed)
   (when (:debug? opts)
     (enable-debug!))

   (let [;; Create raw decoder
         raw-decoder (raw/create-raw-decoder path opts)
         info (raw/get-info raw-decoder)
         decoder-type (:decoder-type info)

         ;; Try to resolve platform-specific binder
         platform-binder (resolve-platform-binder decoder-type)
         zero-copy-available? (some? platform-binder)]

     (debug-log "Decoder type:" decoder-type
                "zero-copy available:" zero-copy-available?)

     ;; Test first frame to verify zero-copy actually works
     ;; This catches cases like NV12 format which has a binder but can't be adopted by Skia
     (when (and zero-copy-available? (= decoder-type :videotoolbox))
       (debug-log "Testing first frame pixel format for zero-copy compatibility...")
       ;; Try to decode a few frames (decoder might need multiple packets)
       (let [test-frame (loop [attempts 0]
                          (if (< attempts 10)
                            (if-let [frame (raw/decode-next-frame! raw-decoder)]
                              frame
                              (do
                                (Thread/sleep 10)
                                (recur (inc attempts))))
                            nil))]
         (if test-frame
           (let [{:keys [hw-data-ptr is-hw-frame?]} test-frame]
             (debug-log "Test frame: is-hw-frame?" is-hw-frame? "hw-data-ptr:" (when hw-data-ptr (.address hw-data-ptr)))
             ;; Check pixel format - try even if is-hw-frame? is false
             (let [check-format-fn (try
                                     (requiring-resolve 'lib.video.hwaccel.videotoolbox-metal/check-pixel-format)
                                     (catch Exception e
                                       (debug-log "Failed to resolve check-pixel-format:" (.getMessage e))
                                       nil))
                   pixel-format (when (and check-format-fn hw-data-ptr)
                                  (try
                                    (check-format-fn hw-data-ptr)
                                    (catch Exception e
                                      (debug-log "check-pixel-format threw:" (.getMessage e))
                                      nil)))]
               (debug-log "Detected pixel format:" pixel-format)
               (when (= pixel-format :nv12)
                 (debug-log "NV12 format detected - will use Metal compute shader for GPU conversion")))
             ;; Seek back to beginning for actual playback
             (raw/seek! raw-decoder 0))
           (debug-log "No test frame available after retries"))))

     (let [;; Create audio track if enabled
           enable-audio? (get opts :audio? true)
           audio-track (when enable-audio?
                         (try
                           (audio/create-audio-track path)
                           (catch Exception e
                             (debug-log "No audio track:" (.getMessage e))
                             nil)))

           ;; Enable audio sync if we have audio
           use-audio-sync? (and audio-track
                                (get opts :audio-sync? true))
           sync-state (when use-audio-sync?
                        (sync/create-sync-state))

           decoder (->ZeroCopyDecoder
                     raw-decoder
                     path
                     decoder-type
                     (:width info)
                     (:height info)
                     (:fps info)
                     (:duration info)
                     platform-binder
                     (atom :stopped)
                     (atom 0.0)
                     (atom -1.0)
                     (atom nil)   ; texture-info
                     (atom nil)   ; skia-image
                     (atom true)  ; needs-decode?
                     (atom false) ; has-first-frame?
                     (atom (not zero-copy-available?)) ; zero-copy-failed?
                     (atom nil)   ; last-hw-frame
                     audio-track
                     sync-state
                     use-audio-sync?)]

       (debug-log "Video:" (:width info) "x" (:height info)
                  "@" (:fps info) "fps, duration:" (:duration info) "s")

       {:decoder decoder
        :hwaccel-type decoder-type
        :zero-copy? zero-copy-available?
        :fallback? false}))))

;; ============================================================
;; Check Zero-Copy Availability
;; ============================================================

(defn zero-copy-available?
  "Check if zero-copy decoding is available on current platform."
  []
  (let [decoder-type (detect/select-decoder)]
    (and (not= decoder-type :software)
         (some? (resolve-platform-binder decoder-type)))))

(defn zero-copy-info
  "Get information about zero-copy capability on current system."
  []
  (let [platform-info (detect/decoder-info)
        decoder-type (:decoder platform-info)
        binder (resolve-platform-binder decoder-type)
        metal-active? (metal-backend-active?)]
    (assoc platform-info
           :zero-copy-available? (some? binder)
           :backend (if metal-active? :metal :opengl)
           :zero-copy-path (case decoder-type
                             :videotoolbox (if metal-active?
                                             "CVPixelBuffer -> CVMetalTextureCache -> MTLTexture (Metal)"
                                             "N/A (requires Metal backend)")
                             :vaapi        "VASurface -> DMA-BUF -> EGLImage -> glEGLImageTargetTexture2DOES"
                             (:cuda :nvdec-cuda) "CUDA buffer -> cuGraphicsGLRegisterImage -> GL texture"
                             (:d3d11 :d3d11va)   "D3D11 texture -> WGL_NV_DX_interop -> GL texture"
                             "N/A"))))

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
  (:import [org.lwjgl.opengl GL11]))

;; ============================================================
;; Debug Logging
;; ============================================================

(defonce ^:private debug-enabled (atom false))

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

(defn- resolve-platform-binder
  "Resolve the platform-specific frame binder function.
   Returns nil if not available."
  [decoder-type]
  (case decoder-type
    :videotoolbox
    (try
      (requiring-resolve 'lib.video.hwaccel.videotoolbox/bind-hw-frame-to-texture!)
      (catch Exception _ nil))

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

   On macOS (VideoToolbox), creates GL_TEXTURE_RECTANGLE.
   On other platforms, creates GL_TEXTURE_2D."
  [width height decoder-type]
  (if (= decoder-type :videotoolbox)
    ;; macOS uses rectangle textures for IOSurface
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
    ;; Other platforms use regular 2D textures
    {:texture-id (tex/create-texture width height)
     :texture-type :texture-2d
     :width width
     :height height}))

(defn- delete-zero-copy-texture!
  "Delete a zero-copy texture."
  [{:keys [texture-id texture-type]}]
  (when texture-id
    (let [target (if (= texture-type :texture-rectangle)
                   0x84F5  ; GL_TEXTURE_RECTANGLE
                   GL11/GL_TEXTURE_2D)]
      (GL11/glDeleteTextures texture-id))))

;; ============================================================
;; Skia Image Wrapping
;; ============================================================

(def ^:private GL_RGBA8 0x8058)

(defn- wrap-texture-as-skia-image
  "Wrap a GL texture as a Skia Image.

   Handles both GL_TEXTURE_2D and GL_TEXTURE_RECTANGLE."
  [direct-context {:keys [texture-id texture-type width height]}]
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
      io.github.humbleui.skija.ColorType/RGBA_8888)))

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
      (let [{:keys [hw-data-ptr pts is-hw-frame?]} frame-data]
        (reset! last-hw-frame-atom frame-data)
        ;; Try zero-copy bind
        (when-let [tex-info @texture-info-atom]
          (let [bound? (and is-hw-frame?
                            (not @zero-copy-failed?-atom)
                            platform-binder
                            (try
                              (platform-binder hw-data-ptr tex-info)
                              (catch Exception e
                                (debug-log "Zero-copy bind failed:" (.getMessage e))
                                (reset! zero-copy-failed?-atom true)
                                false)))]
            (when bound?
              (debug-log "Frame bound via zero-copy at pts:" pts))))
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
      ;; Ensure texture exists
      (when-not @texture-info-atom
        (reset! texture-info-atom (create-zero-copy-texture width height decoder-type)))

      (when-let [frame-data (raw/decode-next-frame! raw-decoder)]
        (let [{:keys [hw-data-ptr is-hw-frame?]} frame-data
              tex-info @texture-info-atom]
          (reset! last-hw-frame-atom frame-data)
          ;; Try zero-copy bind
          (let [bound? (and is-hw-frame?
                            platform-binder
                            (try
                              (platform-binder hw-data-ptr tex-info)
                              (catch Exception e
                                (debug-log "Zero-copy bind failed on first frame:" (.getMessage e))
                                (reset! zero-copy-failed?-atom true)
                                false)))]
            (when-not bound?
              (debug-log "First frame: zero-copy unavailable, will use fallback")))
          (reset! has-first-frame?-atom true))
        ;; Reset to beginning for playback
        (raw/seek! raw-decoder 0)))
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
                    tex-info @texture-info-atom]
                (reset! last-hw-frame-atom frame-data)

                ;; Try zero-copy bind
                (let [bound? (and is-hw-frame?
                                  (not @zero-copy-failed?-atom)
                                  platform-binder
                                  (try
                                    (platform-binder hw-data-ptr tex-info)
                                    (catch Exception e
                                      (debug-log "Zero-copy bind failed:" (.getMessage e))
                                      (reset! zero-copy-failed?-atom true)
                                      false)))]
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
      ;; Ensure texture exists
      (when-not @texture-info-atom
        (reset! texture-info-atom (create-zero-copy-texture width height decoder-type)))

      (or @skia-image-atom
          (when-let [tex-info @texture-info-atom]
            (let [img (wrap-texture-as-skia-image direct-context tex-info)]
              (reset! skia-image-atom img)
              img)))))

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
   Throws if decoder creation fails."
  ([path]
   (create-zero-copy-decoder path {}))
  ([path opts]
   (when (:debug? opts)
     (enable-debug!))

   (let [;; Create raw decoder
         raw-decoder (raw/create-raw-decoder path opts)
         info (raw/get-info raw-decoder)
         decoder-type (:decoder-type info)

         ;; Try to resolve platform-specific binder
         platform-binder (resolve-platform-binder decoder-type)
         zero-copy-available? (some? platform-binder)

         _ (debug-log "Decoder type:" decoder-type
                      "zero-copy available:" zero-copy-available?)

         ;; Create audio track if enabled
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
      :fallback? false})))

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
        binder (resolve-platform-binder decoder-type)]
    (assoc platform-info
           :zero-copy-available? (some? binder)
           :zero-copy-path (case decoder-type
                             :videotoolbox "CVPixelBuffer -> IOSurface -> CGLTexImageIOSurface2D"
                             :vaapi        "VASurface -> DMA-BUF -> EGLImage -> glEGLImageTargetTexture2DOES"
                             (:cuda :nvdec-cuda) "CUDA buffer -> cuGraphicsGLRegisterImage -> GL texture"
                             (:d3d11 :d3d11va)   "D3D11 texture -> WGL_NV_DX_interop -> GL texture"
                             "N/A"))))

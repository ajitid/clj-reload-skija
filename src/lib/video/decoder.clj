(ns lib.video.decoder
  "Video decoder using JavaCV's FFmpegFrameGrabber.
   Handles software decoding with optional hardware acceleration hints.
   Supports audio sync when audio track is available."
  (:require [lib.video.protocol :as proto]
            [lib.video.texture :as tex]
            [lib.video.audio :as audio]
            [lib.video.sync :as sync])
  (:import [org.bytedeco.javacv FFmpegFrameGrabber Frame]
           [java.nio ByteBuffer]))

(defonce ^:private debug-frame-count (atom 0))

(defn- get-frame-info
  "Extract frame info including stride for proper texture upload.
   Returns {:buffer ByteBuffer :stride int :channels int} or nil."
  [^Frame frame]
  (when (and frame (.-image frame) (pos? (alength (.-image frame))))
    (let [^java.nio.Buffer buf (aget (.-image frame) 0)
          stride (.-imageStride frame)
          channels (.-imageChannels frame)
          width (.-imageWidth frame)
          height (.-imageHeight frame)]
      ;; Debug logging for first few frames
      (when (< @debug-frame-count 5)
        (swap! debug-frame-count inc)
        (println (format "Frame %d: %dx%d, stride=%d, channels=%d, buffer=%s"
                         @debug-frame-count width height stride channels
                         (type buf))))
      (when (instance? ByteBuffer buf)
        {:buffer   ^ByteBuffer buf
         :stride   stride
         :channels channels
         :width    width
         :height   height}))))

(defn- calculate-pts
  "Calculate presentation timestamp in seconds from frame."
  [^Frame frame]
  (if-let [ts (.-timestamp frame)]
    (/ ts 1000000.0)  ; microseconds -> seconds
    0.0))

(defrecord SoftwareDecoder
    [^FFmpegFrameGrabber grabber
     path
     width height
     fps-val
     duration-val
     ;; Playback state
     state-atom      ; :stopped, :playing, :paused
     current-pts     ; Current playback position in seconds
     last-frame-pts  ; PTS of last decoded frame
     ;; Frame buffer
     texture-id      ; OpenGL texture for current frame
     skia-image      ; Cached Skia Image
     needs-decode?   ; True if we need to decode next frame
     has-first-frame? ; True after first frame decoded
     ;; Audio sync
     audio-track     ; AudioTrack for audio playback (nil if no audio)
     sync-state      ; SyncState for A/V synchronization
     use-audio-sync?] ; True to sync video to audio clock

  proto/VideoSource
  (play* [this]
    (let [state @state-atom]
      (when (not= state :playing)
        (when (= state :stopped)
          ;; Reset to beginning
          (.setTimestamp grabber 0)
          (reset! current-pts 0.0)
          (reset! last-frame-pts -1.0)
          (reset! needs-decode? true)
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
    (.setTimestamp grabber 0)
    (reset! current-pts 0.0)
    (reset! last-frame-pts -1.0)
    (reset! needs-decode? true)
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
    (let [target-us (long (* seconds 1000000))]
      (.setTimestamp grabber target-us)
      (reset! current-pts seconds)
      (reset! last-frame-pts -1.0)
      ;; Seek audio if available
      (when audio-track
        (audio/seek! audio-track seconds))
      ;; Reset sync to new position
      (when sync-state
        (sync/reset-sync-to! sync-state seconds))
      ;; Immediately decode frame at new position
      ;; This ensures seeking while paused shows the correct frame
      (when-let [frame (.grabImage grabber)]
        (when-let [{:keys [buffer stride channels]} (get-frame-info frame)]
          (when (= channels 4)
            (when-not @texture-id
              (reset! texture-id (tex/create-texture width height)))
            (tex/update-texture-with-stride! @texture-id width height buffer stride channels)
            (reset! has-first-frame? true)
            (reset! last-frame-pts seconds))))
      (reset! needs-decode? false))
    this)

  (tell* [_this]
    @current-pts)

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
    (when-not @has-first-frame?
      (when-let [frame (.grabImage grabber)]
        (when-let [{:keys [buffer stride channels]} (get-frame-info frame)]
          ;; Ensure we have 4 channels (RGBA)
          (when (= channels 4)
            ;; Create texture if needed
            (when-not @texture-id
              (reset! texture-id (tex/create-texture width height)))
            ;; Upload first frame
            (tex/update-texture-with-stride! @texture-id width height buffer stride channels)
            (reset! has-first-frame? true))
          ;; Reset to beginning for playback
          (.setTimestamp grabber 0))))
    this)

  (advance-frame!* [this dt]
    (when (= @state-atom :playing)
      ;; Get timing source - audio position if syncing, otherwise advance by dt
      (let [audio-pos (when (and use-audio-sync? audio-track)
                        (audio/tell audio-track))
            ;; Use audio position if available, otherwise advance by dt
            effective-pts (if audio-pos
                            audio-pos
                            (swap! current-pts + dt))
            last-pts @last-frame-pts
            frame-time (/ 1.0 fps-val)]
        ;; Update current-pts to match effective timing
        (when audio-pos
          (reset! current-pts audio-pos))
        ;; Simple frame-rate based decoding: decode when enough time has passed
        ;; This keeps decoding smooth regardless of audio sync
        (let [time-since-last-frame (- effective-pts last-pts)
              should-decode? (or @needs-decode?
                                 (>= time-since-last-frame frame-time))]
          (when should-decode?
            ;; Decode next frame
            (when-let [frame (.grabImage grabber)]
              (when-let [{:keys [buffer stride channels]} (get-frame-info frame)]
                ;; Ensure we have 4 channels (RGBA)
                (when (= channels 4)
                  ;; Ensure texture exists
                  (when-not @texture-id
                    (reset! texture-id (tex/create-texture width height)))
                  ;; Upload to GPU with proper stride handling
                  (tex/update-texture-with-stride! @texture-id width height buffer stride channels)
                  (reset! has-first-frame? true))
                ;; Update frame tracking - use audio position if available for smoother sync
                (let [frame-pts (if audio-pos audio-pos (calculate-pts frame))]
                  (reset! last-frame-pts frame-pts))
                (reset! needs-decode? false)))
            ;; Handle end of video
            (when (>= @current-pts duration-val)
              (reset! state-atom :stopped)
              (reset! current-pts 0.0)
              (when audio-track
                (audio/stop! audio-track)))))))
    this)

  (current-frame* [this direct-context]
    ;; Return cached Skia image or create new one
    (when (and @texture-id @has-first-frame?)
      (or @skia-image
          (let [img (tex/wrap-as-skia-image direct-context @texture-id width height)]
            (reset! skia-image img)
            img))))

  (close* [this]
    ;; Close audio track
    (when audio-track
      (audio/close! audio-track))
    ;; Close Skia image
    (when-let [img @skia-image]
      (.close img)
      (reset! skia-image nil))
    ;; Delete texture
    (when-let [tex @texture-id]
      (tex/delete-texture! tex)
      (reset! texture-id nil))
    ;; Close grabber
    (try
      (.stop grabber)
      (.close grabber)
      (catch Exception _))
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

(defn- decode-first-frame!
  "Decode and upload the first frame for preview.
   Returns true if successful."
  [grabber texture-id-atom has-first-frame-atom width height]
  (when-let [frame (.grabImage grabber)]
    (when-let [{:keys [buffer stride channels]} (get-frame-info frame)]
      ;; Create texture if needed
      (when-not @texture-id-atom
        (reset! texture-id-atom (tex/create-texture width height)))
      ;; Upload first frame
      (tex/update-texture-with-stride! @texture-id-atom width height buffer stride channels)
      (reset! has-first-frame-atom true)
      ;; Reset to beginning for playback
      (.setTimestamp grabber 0)
      true)))

(defn create-software-decoder
  "Create a software video decoder for the given file path.
   Options:
     :hw-accel? - Hint to try hardware acceleration (may not be available)
     :audio?    - Enable audio track (default true)
     :audio-sync? - Sync video to audio clock (default true when audio present)"
  ([path]
   (create-software-decoder path {}))
  ([path opts]
   (let [grabber (FFmpegFrameGrabber. ^String path)]
     ;; Configure grabber - use RGBA for direct OpenGL compatibility
     ;; RGBA avoids BGR/RGB swap issues
     (.setPixelFormat grabber org.bytedeco.ffmpeg.global.avutil/AV_PIX_FMT_RGBA)
     ;; Try to enable hardware acceleration if requested
     (when (:hw-accel? opts)
       (try
         (let [os-name (System/getProperty "os.name")]
           (cond
             (.contains os-name "Mac")
             (.setVideoCodecName grabber "h264_videotoolbox")
             (.contains os-name "Linux")
             (.setVideoCodecName grabber "h264_vaapi")
             (.contains os-name "Windows")
             (.setVideoCodecName grabber "h264_d3d11va")))
         (catch Exception _)))
     ;; Start grabber
     (.start grabber)
     ;; Get video properties
     (let [width (.getImageWidth grabber)
           height (.getImageHeight grabber)
           fps (let [r (.getFrameRate grabber)]
                 (if (pos? r) r 30.0))
           duration (/ (.getLengthInTime grabber) 1000000.0) ; us -> s
           texture-id-atom (atom nil)
           has-first-frame-atom (atom false)
           ;; Create audio track if enabled and video has audio
           enable-audio? (get opts :audio? true)
           audio-track (when enable-audio?
                         (try
                           (audio/create-audio-track path)
                           (catch Exception e
                             (println "[decoder] No audio track:" (.getMessage e))
                             nil)))
           ;; Enable audio sync if we have audio and it's enabled
           use-audio-sync? (and audio-track
                                (get opts :audio-sync? true))
           ;; Create sync state if syncing
           sync-state (when use-audio-sync?
                        (sync/create-sync-state))
           decoder (->SoftwareDecoder
                     grabber
                     path
                     width height
                     fps
                     duration
                     (atom :stopped)
                     (atom 0.0)
                     (atom -1.0)
                     texture-id-atom
                     (atom nil)  ; skia-image
                     (atom true) ; needs-decode?
                     has-first-frame-atom
                     audio-track
                     sync-state
                     use-audio-sync?)]
       ;; Log audio status
       (when audio-track
         (println "[decoder] Audio track enabled, sync:" use-audio-sync?))
       ;; Decode first frame for preview (deferred - will happen on first draw)
       decoder))))

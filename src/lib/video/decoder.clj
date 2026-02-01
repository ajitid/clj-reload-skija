(ns lib.video.decoder
  "Video decoder using JavaCV's FFmpegFrameGrabber.
   Handles software decoding with optional hardware acceleration hints."
  (:require [lib.video.protocol :as proto]
            [lib.video.texture :as tex])
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
     has-first-frame?] ; True after first frame decoded

  proto/VideoSource
  (play* [this]
    (let [state @state-atom]
      (when (not= state :playing)
        (when (= state :stopped)
          ;; Reset to beginning
          (.setTimestamp grabber 0)
          (reset! current-pts 0.0)
          (reset! last-frame-pts -1.0)
          (reset! needs-decode? true))
        (reset! state-atom :playing)))
    this)

  (stop* [this]
    (reset! state-atom :stopped)
    (.setTimestamp grabber 0)
    (reset! current-pts 0.0)
    (reset! last-frame-pts -1.0)
    (reset! needs-decode? true)
    this)

  (pause* [this]
    (when (= @state-atom :playing)
      (reset! state-atom :paused))
    this)

  (seek* [this seconds]
    (let [target-us (long (* seconds 1000000))]
      (.setTimestamp grabber target-us)
      (reset! current-pts seconds)
      (reset! last-frame-pts -1.0)
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
      ;; Advance playback position
      (swap! current-pts + dt)
      ;; Check if we need a new frame
      (let [pts @current-pts
            last-pts @last-frame-pts
            frame-time (/ 1.0 fps-val)]
        (when (or @needs-decode?
                  (>= (- pts last-pts) frame-time))
          ;; Decode next frame
          (when-let [frame (.grabImage grabber)]
            (when-let [{:keys [buffer stride channels]} (get-frame-info frame)]
              ;; Ensure we have 4 channels (RGBA)
              (when (= channels 4)
                ;; Ensure texture exists
                (when-not @texture-id
                  (reset! texture-id (tex/create-texture width height)))
                ;; Upload to GPU with proper stride handling
                ;; Don't close/recreate Skia image - the texture is updated in place
                ;; The existing Skia Image wrapper will show the new content
                (tex/update-texture-with-stride! @texture-id width height buffer stride channels)
                (reset! has-first-frame? true))
              ;; Update frame tracking
              (reset! last-frame-pts (calculate-pts frame))
              (reset! needs-decode? false)))
          ;; Handle end of video
          (when (>= @current-pts duration-val)
            (reset! state-atom :stopped)
            (reset! current-pts 0.0)))))
    this)

  (current-frame* [this direct-context]
    ;; Return cached Skia image or create new one
    (when (and @texture-id @has-first-frame?)
      (or @skia-image
          (let [img (tex/wrap-as-skia-image direct-context @texture-id width height)]
            (reset! skia-image img)
            img))))

  (close* [this]
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
    this))

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
     :hw-accel? - Hint to try hardware acceleration (may not be available)"
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
                     has-first-frame-atom)]
       ;; Decode first frame for preview (deferred - will happen on first draw)
       decoder))))

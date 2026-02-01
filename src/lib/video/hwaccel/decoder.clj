(ns lib.video.hwaccel.decoder
  "Hardware-accelerated video decoder using JavaCV's FFmpegFrameGrabber.
   Configures FFmpeg to use VideoToolbox/VAAPI/NVDEC/D3D11VA based on platform.

   The hardware acceleration happens at the decode stage - FFmpeg uses the
   GPU to decode video frames. The frames are then transferred to CPU memory
   by JavaCV, and uploaded to an OpenGL texture for Skia rendering.

   For true zero-copy (GPU-resident frames), see videotoolbox.clj (macOS)."
  (:require [lib.video.protocol :as proto]
            [lib.video.texture :as tex]
            [lib.video.hwaccel.detect :as detect])
  (:import [org.bytedeco.javacv FFmpegFrameGrabber Frame]
           [java.nio ByteBuffer]))

(defonce ^:private debug-enabled (atom false))

(defn enable-debug!
  "Enable debug logging for hwaccel decoder."
  []
  (reset! debug-enabled true))

(defn- debug-log
  "Log message if debug is enabled."
  [& args]
  (when @debug-enabled
    (apply println "[hwaccel]" args)))

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

(defn- configure-hwaccel!
  "Configure FFmpegFrameGrabber for hardware acceleration.
   Returns the decoder type actually configured, or nil if failed."
  [^FFmpegFrameGrabber grabber decoder-type]
  (try
    (case decoder-type
      :videotoolbox
      (do
        ;; VideoToolbox on macOS
        (.setOption grabber "hwaccel" "videotoolbox")
        ;; Allow software fallback for unsupported formats
        (.setOption grabber "hwaccel_output_format" "nv12")
        (debug-log "Configured VideoToolbox hwaccel")
        :videotoolbox)

      :vaapi
      (do
        ;; VAAPI on Linux
        (.setOption grabber "hwaccel" "vaapi")
        (.setOption grabber "hwaccel_device" "/dev/dri/renderD128")
        (.setOption grabber "hwaccel_output_format" "vaapi")
        (debug-log "Configured VAAPI hwaccel")
        :vaapi)

      :nvdec-cuda
      (do
        ;; NVDEC/CUDA on Linux/Windows NVIDIA
        (.setOption grabber "hwaccel" "cuda")
        (.setOption grabber "hwaccel_output_format" "cuda")
        (debug-log "Configured CUDA hwaccel")
        :nvdec-cuda)

      :d3d11va
      (do
        ;; D3D11VA on Windows
        (.setOption grabber "hwaccel" "d3d11va")
        (.setOption grabber "hwaccel_output_format" "d3d11")
        (debug-log "Configured D3D11VA hwaccel")
        :d3d11va)

      ;; Software or unknown - no hwaccel
      nil)
    (catch Exception e
      (debug-log "Failed to configure hwaccel:" (.getMessage e))
      nil)))

(defrecord HardwareDecoder
    [^FFmpegFrameGrabber grabber
     path
     decoder-type       ; :videotoolbox, :vaapi, :nvdec-cuda, :d3d11va, or nil
     width height
     fps-val
     duration-val
     ;; Playback state
     state-atom         ; :stopped, :playing, :paused
     current-pts        ; Current playback position in seconds
     last-frame-pts     ; PTS of last decoded frame
     ;; Frame buffer
     texture-id         ; OpenGL texture for current frame
     skia-image         ; Cached Skia Image
     needs-decode?      ; True if we need to decode next frame
     has-first-frame?]  ; True after first frame decoded

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
          (when (= channels 4)
            (when-not @texture-id
              (reset! texture-id (tex/create-texture width height)))
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
              (when (= channels 4)
                (when-not @texture-id
                  (reset! texture-id (tex/create-texture width height)))
                (tex/update-texture-with-stride! @texture-id width height buffer stride channels)
                (reset! has-first-frame? true))
              (reset! last-frame-pts (calculate-pts frame))
              (reset! needs-decode? false)))
          ;; Handle end of video
          (when (>= @current-pts duration-val)
            (reset! state-atom :stopped)
            (reset! current-pts 0.0)))))
    this)

  (current-frame* [this direct-context]
    (when (and @texture-id @has-first-frame?)
      (or @skia-image
          (let [img (tex/wrap-as-skia-image direct-context @texture-id width height)]
            (reset! skia-image img)
            img))))

  (close* [this]
    (when-let [img @skia-image]
      (.close img)
      (reset! skia-image nil))
    (when-let [tex @texture-id]
      (tex/delete-texture! tex)
      (reset! texture-id nil))
    (try
      (.stop grabber)
      (.close grabber)
      (catch Exception _))
    this))

(defn- make-decoder-result
  "Create a decoder result map."
  [grabber path hwaccel-type fallback?]
  (let [width (.getImageWidth grabber)
        height (.getImageHeight grabber)
        fps (let [r (.getFrameRate grabber)]
              (if (pos? r) r 30.0))
        duration (/ (.getLengthInTime grabber) 1000000.0)]
    (debug-log "Video:" width "x" height "@" fps "fps, duration:" duration "s")
    (debug-log "Hwaccel:" (or hwaccel-type "none (software)"))
    {:decoder (->HardwareDecoder
                grabber path hwaccel-type
                width height fps duration
                (atom :stopped) (atom 0.0) (atom -1.0)
                (atom nil) (atom nil) (atom true) (atom false))
     :hwaccel-type hwaccel-type
     :fallback? fallback?}))

(defn- try-start-grabber!
  "Try to start grabber, return true on success."
  [^FFmpegFrameGrabber grabber]
  (try
    (.start grabber)
    true
    (catch Exception e
      (debug-log "Grabber start failed:" (.getMessage e))
      false)))

(defn- create-software-grabber
  "Create a software-only grabber for fallback."
  [path]
  (let [grabber (FFmpegFrameGrabber. ^String path)]
    (.setPixelFormat grabber org.bytedeco.ffmpeg.global.avutil/AV_PIX_FMT_RGBA)
    (when (try-start-grabber! grabber)
      grabber)))

(defn create-hwaccel-decoder
  "Create a hardware-accelerated video decoder for the given file path.
   Attempts to use platform-appropriate hwaccel, falls back to software.

   Options:
     :decoder - Force a specific decoder (:videotoolbox, :vaapi, :nvdec-cuda, :d3d11va, :software)
     :debug?  - Enable debug logging

   Returns {:decoder HardwareDecoder :hwaccel-type keyword :fallback? boolean}"
  ([path]
   (create-hwaccel-decoder path {}))
  ([path opts]
   (when (:debug? opts)
     (enable-debug!))

   (let [;; Select decoder type
         requested-decoder (:decoder opts)
         auto-decoder (detect/select-decoder)
         decoder-type (or requested-decoder
                          (when (detect/available? auto-decoder) auto-decoder))
         use-hwaccel? (and decoder-type (not= decoder-type :software))]

     (if-not use-hwaccel?
       ;; Software-only path
       (if-let [grabber (create-software-grabber path)]
         (make-decoder-result grabber path nil false)
         (throw (ex-info "Failed to create video decoder" {:path path})))

       ;; Try hwaccel path
       (let [grabber (FFmpegFrameGrabber. ^String path)
             _ (.setPixelFormat grabber org.bytedeco.ffmpeg.global.avutil/AV_PIX_FMT_RGBA)
             actual-decoder (configure-hwaccel! grabber decoder-type)]

         (if (and actual-decoder (try-start-grabber! grabber))
           ;; Hwaccel succeeded
           (make-decoder-result grabber path actual-decoder false)

           ;; Hwaccel failed, try software fallback
           (do
             (debug-log "Hwaccel failed, falling back to software decode")
             (try (.close grabber) (catch Exception _))
             (if-let [sw-grabber (create-software-grabber path)]
               (make-decoder-result sw-grabber path nil true)
               (throw (ex-info "Failed to create video decoder" {:path path}))))))))))

(defn hwaccel-type
  "Get the hardware acceleration type used by a decoder.
   Returns :videotoolbox, :vaapi, :nvdec-cuda, :d3d11va, or nil (software)."
  [decoder]
  (:decoder-type decoder))

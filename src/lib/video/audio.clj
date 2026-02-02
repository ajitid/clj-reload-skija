(ns lib.video.audio
  "Audio extraction and playback for video files.

   Uses JavaCV's FFmpegFrameGrabber to extract audio frames and
   Java Sound's SourceDataLine for streaming playback.

   The audio track serves as the master clock for A/V sync.
   Video frame timing is adjusted to match audio position."
  (:import [org.bytedeco.javacv FFmpegFrameGrabber Frame]
           [javax.sound.sampled AudioSystem AudioFormat
            SourceDataLine DataLine$Info
            FloatControl FloatControl$Type]
           [java.nio ByteBuffer ShortBuffer]))

;; ============================================================
;; Audio State
;; ============================================================

(defrecord AudioTrack
    [^FFmpegFrameGrabber grabber
     ^AudioFormat format
     ^SourceDataLine line
     path
     sample-rate
     channels
     duration-val
     ;; Playback state
     state-atom          ; :stopped, :playing, :paused
     seek-target         ; atom - the target position when we last seeked/started
     line-pos-at-seek    ; atom - the line's microsecond position when we seeked
     volume-atom
     thread-atom
     lock
     ;; Seek handling
     seek-request        ; atom - {:target-seconds :was-playing?} or nil
     seeking?])          ; atom - true while seek in progress

;; ============================================================
;; Audio Format Conversion
;; ============================================================

(defn- create-audio-format
  "Create Java Sound AudioFormat matching FFmpeg output."
  [sample-rate channels]
  (AudioFormat.
    (float sample-rate)     ; sample rate
    16                      ; bits per sample (16-bit PCM)
    channels                ; channels (mono/stereo)
    true                    ; signed
    false))                 ; big endian

(defn- frame-to-bytes
  "Convert FFmpeg audio Frame to byte array for SourceDataLine.
   FFmpeg outputs audio as ShortBuffer (16-bit samples), we convert to bytes."
  [^Frame frame]
  (when (and frame (.-samples frame) (pos? (alength (.-samples frame))))
    (let [^java.nio.Buffer sample-buf (aget (.-samples frame) 0)]
      (when (instance? ShortBuffer sample-buf)
        (let [^ShortBuffer sbuf sample-buf
              num-samples (.remaining sbuf)
              bytes (byte-array (* num-samples 2))]
          ;; Convert shorts to little-endian bytes
          (dotimes [i num-samples]
            (let [sample (.get sbuf)
                  idx (* i 2)]
              (aset bytes idx (unchecked-byte (bit-and sample 0xFF)))
              (aset bytes (inc idx) (unchecked-byte (bit-shift-right sample 8)))))
          bytes)))))

;; ============================================================
;; Playback Thread
;; ============================================================

(defn- calculate-audio-pts
  "Calculate presentation timestamp in seconds from audio frame."
  [^Frame frame]
  (if-let [ts (.-timestamp frame)]
    (/ ts 1000000.0)  ; microseconds -> seconds
    0.0))

(defn- playback-loop
  "Main audio playback loop run in a thread.
   Reads audio frames from grabber, writes to SourceDataLine."
  [^AudioTrack track]
  (let [{:keys [grabber line state-atom seek-target line-pos-at-seek lock
                seek-request seeking? duration-val]} track
        ^SourceDataLine sdl line]
    (try
      (loop []
        ;; Handle pending seek request
        (when-let [{:keys [target-seconds was-playing?]} @seek-request]
          (reset! seek-request nil)
          (reset! seeking? true)
          (try
            (.stop sdl)
            (.flush sdl)
            ;; Seek grabber to target position
            (let [target-us (long (* target-seconds 1000000))]
              (.setTimestamp grabber target-us)
              ;; Record seek: target position and line position at this moment
              (reset! seek-target target-seconds)
              (reset! line-pos-at-seek (.getMicrosecondPosition sdl)))
            (when was-playing?
              (.start sdl)
              (reset! state-atom :playing))
            (finally
              (reset! seeking? false))))

        (let [current-state @state-atom]
          (cond
            ;; Stopped - exit thread
            (= current-state :stopped)
            nil

            ;; Paused - wait for resume or seek
            (= current-state :paused)
            (do
              (locking lock
                (when (and (= @state-atom :paused) (nil? @seek-request))
                  (.wait ^Object lock)))
              (recur))

            ;; Playing - read audio and write to line
            (= current-state :playing)
            (let [frame (.grabSamples grabber)]
              (cond
                ;; End of stream
                (nil? frame)
                (do
                  (reset! state-atom :stopped)
                  (.drain sdl)
                  (.stop sdl))

                ;; Got audio data - write to line
                :else
                (do
                  ;; Convert and write audio data
                  ;; Position is tracked via line.getMicrosecondPosition() + offset
                  (when-let [audio-bytes (frame-to-bytes frame)]
                    (.write sdl audio-bytes 0 (alength audio-bytes)))
                  (recur))))

            :else
            nil)))
      (catch Exception e
        (when-not (= @state-atom :stopped)
          (println "Audio playback error:" (.getMessage e)))))))

(defn- start-playback-thread!
  "Start the audio playback thread."
  [^AudioTrack track]
  (let [thread (Thread.
                 ^Runnable (fn [] (playback-loop track))
                 "video-audio-playback")]
    (.setDaemon thread true)
    (reset! (:thread-atom track) thread)
    (.start thread)))

;; ============================================================
;; Volume Control
;; ============================================================

(defn- apply-volume!
  "Apply volume to the SourceDataLine."
  [^SourceDataLine line ^double volume]
  (when (.isControlSupported line FloatControl$Type/MASTER_GAIN)
    (let [ctrl (.getControl line FloatControl$Type/MASTER_GAIN)
          min-db (.getMinimum ^FloatControl ctrl)
          max-db (.getMaximum ^FloatControl ctrl)
          db (if (<= volume 0.0)
               min-db
               (let [log-vol (* 20.0 (Math/log10 volume))]
                 (max min-db (min max-db log-vol))))]
      (.setValue ^FloatControl ctrl (float db)))))

;; ============================================================
;; Public API
;; ============================================================

(defn create-audio-track
  "Create an audio track from a video file.
   Returns AudioTrack or nil if video has no audio."
  [^String path]
  (let [grabber (FFmpegFrameGrabber. path)]
    ;; Start grabber to probe audio
    (.start grabber)
    (let [audio-channels (.getAudioChannels grabber)
          sample-rate (.getSampleRate grabber)
          duration (/ (.getLengthInTime grabber) 1000000.0)]
      ;; Close and return nil if no audio
      (if (or (nil? audio-channels) (<= audio-channels 0))
        (do
          (.stop grabber)
          (.close grabber)
          nil)
        ;; Has audio - create track
        (let [;; Create audio format
              format (create-audio-format sample-rate audio-channels)
              ;; Create SourceDataLine
              info (DataLine$Info. SourceDataLine format)
              line (AudioSystem/getLine info)]
          (.open ^SourceDataLine line format)
          ;; Reset grabber to beginning
          (.setTimestamp grabber 0)
          (->AudioTrack
            grabber
            format
            line
            path
            sample-rate
            audio-channels
            duration
            (atom :stopped)     ; state
            (atom 0.0)          ; seek-target
            (atom 0)            ; line-pos-at-seek (microseconds)
            (atom 1.0)          ; volume
            (atom nil)          ; thread
            (Object.)           ; lock
            (atom nil)          ; seek-request
            (atom false)))))))  ; seeking?

(defn play!
  "Start or restart audio playback."
  [^AudioTrack track]
  (when track
    (let [{:keys [grabber line state-atom seek-target line-pos-at-seek thread-atom lock]} track
          ^SourceDataLine sdl line]
      ;; Stop any existing playback
      (reset! (:seek-request track) nil)
      (reset! state-atom :stopped)
      (when-let [t @thread-atom]
        (locking lock (.notifyAll ^Object lock))
        (.join ^Thread t 100))
      (.stop sdl)
      (.flush sdl)
      ;; Reset and start fresh
      (.setTimestamp grabber 0)
      (reset! seek-target 0.0)
      (reset! line-pos-at-seek (.getMicrosecondPosition sdl))
      (reset! state-atom :playing)
      (apply-volume! sdl @(:volume-atom track))
      (.start sdl)
      (start-playback-thread! track)))
  track)

(defn stop!
  "Stop audio playback and reset to beginning."
  [^AudioTrack track]
  (when track
    (let [{:keys [grabber line state-atom seek-target line-pos-at-seek thread-atom lock]} track
          ^SourceDataLine sdl line]
      (reset! (:seek-request track) nil)
      (reset! state-atom :stopped)
      (when-let [t @thread-atom]
        (locking lock (.notifyAll ^Object lock))
        (.join ^Thread t 100))
      (.stop sdl)
      (.flush sdl)
      (.setTimestamp grabber 0)
      (reset! seek-target 0.0)
      (reset! line-pos-at-seek (.getMicrosecondPosition sdl))))
  track)

(defn pause!
  "Pause audio playback."
  [^AudioTrack track]
  (when track
    (when (= @(:state-atom track) :playing)
      (reset! (:state-atom track) :paused)
      (.stop ^SourceDataLine (:line track))))
  track)

(defn resume!
  "Resume audio playback."
  [^AudioTrack track]
  (when track
    (when (= @(:state-atom track) :paused)
      (reset! (:state-atom track) :playing)
      (.start ^SourceDataLine (:line track))
      (locking (:lock track)
        (.notifyAll ^Object (:lock track)))))
  track)

(defn seek!
  "Seek audio to a position in seconds."
  [^AudioTrack track seconds]
  (when track
    (let [clamped-seconds (max 0.0 (min (double seconds) (:duration-val track)))
          ^SourceDataLine sdl (:line track)]
      ;; Immediately update for UI responsiveness
      ;; Record current line position so tell() works during seek
      (reset! (:seek-target track) clamped-seconds)
      (reset! (:line-pos-at-seek track) (.getMicrosecondPosition sdl))
      ;; Queue seek request for playback thread
      (reset! (:seek-request track)
              {:target-seconds clamped-seconds
               :was-playing? (= @(:state-atom track) :playing)})
      ;; Wake playback thread
      (locking (:lock track)
        (.notifyAll ^Object (:lock track)))))
  track)

(defn tell
  "Get current audio playback position in seconds.
   Uses SourceDataLine position for smooth, accurate timing."
  [^AudioTrack track]
  (when track
    (let [^SourceDataLine sdl (:line track)
          ;; Get current line position
          line-micros (.getMicrosecondPosition sdl)
          ;; Get the line position when we last seeked
          base-micros @(:line-pos-at-seek track)
          ;; Elapsed since last seek/start
          elapsed-micros (- line-micros base-micros)
          elapsed-seconds (/ elapsed-micros 1000000.0)
          ;; Add to seek target
          seek-pos @(:seek-target track)]
      (+ seek-pos elapsed-seconds))))

(defn playing?
  "Check if audio is currently playing."
  [^AudioTrack track]
  (when track
    (= @(:state-atom track) :playing)))

(defn paused?
  "Check if audio is paused."
  [^AudioTrack track]
  (when track
    (= @(:state-atom track) :paused)))

(defn seeking?
  "Check if audio is currently seeking."
  [^AudioTrack track]
  (when track
    @(:seeking? track)))

(defn set-volume!
  "Set audio volume (0.0 to 1.0)."
  [^AudioTrack track volume]
  (when track
    (reset! (:volume-atom track) volume)
    (apply-volume! (:line track) volume))
  track)

(defn close!
  "Close the audio track and release resources."
  [^AudioTrack track]
  (when track
    (let [{:keys [grabber line state-atom thread-atom lock]} track
          ^SourceDataLine sdl line]
      ;; Signal thread to stop
      (reset! state-atom :stopped)
      (when-let [t @thread-atom]
        (locking lock (.notifyAll ^Object lock))
        (.join ^Thread t 200))
      ;; Close resources
      (.stop sdl)
      (.close sdl)
      (try
        (.stop grabber)
        (.close grabber)
        (catch Exception _)))))

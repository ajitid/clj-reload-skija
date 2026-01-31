(ns lib.audio.streaming
  "SourceDataLine-based streaming audio playback.
   Used for long audio files (music, ambience) to avoid loading
   entire audio into memory like Clip does.

   Thread model:
   - Each stream has a daemon playback thread
   - Thread reads from AudioInputStream, writes to SourceDataLine
   - Control via atom state machine: :stopped -> :playing -> :paused -> :playing
   - Thread auto-terminates on stop or end-of-stream"
  (:require [lib.audio.protocol :as proto]
            [lib.audio.internal :as internal])
  (:import [javax.sound.sampled AudioSystem AudioInputStream AudioFormat
            SourceDataLine DataLine$Info
            FloatControl FloatControl$Type]
           [java.io File]))

;; ============================================================
;; Forward declarations for playback thread
;; ============================================================

(declare start-playback-thread!)

;; ============================================================
;; Internal helpers
;; ============================================================

(defn- skip-by-reading!
  "Skip bytes by reading and discarding them.
   Required for decoded audio streams where skip() is broken
   (it skips on the underlying encoded stream, corrupting decoder state)."
  [^AudioInputStream stream ^long bytes-to-skip]
  (let [buffer-size 8192
        buffer (byte-array buffer-size)]
    (loop [remaining bytes-to-skip]
      (when (pos? remaining)
        (let [to-read (int (min remaining buffer-size))
              bytes-read (.read stream buffer 0 to-read)]
          (when (pos? bytes-read)
            (recur (- remaining bytes-read))))))))

(defn- compute-duration
  "Compute duration in seconds from AudioInputStream.
   Must be called before consuming the stream."
  ^double [^AudioInputStream stream]
  (let [format (.getFormat stream)
        frame-length (.getFrameLength stream)
        frame-rate (.getFrameRate format)]
    (if (and (pos? frame-length) (pos? frame-rate))
      (/ (double frame-length) frame-rate)
      0.0)))

(defn- compute-duration-from-file
  "Get duration from AudioFileFormat properties (MP3/OGG via SPIs).
   Returns duration in seconds, or nil if not available.
   MP3 files return -1 for getFrameLength() but mp3spi provides
   a 'duration' property in microseconds via AudioFileFormat."
  [^String path]
  (try
    (let [file (File. path)
          file-format (AudioSystem/getAudioFileFormat file)
          props (.properties file-format)]
      (when-let [duration-micros (get props "duration")]
        (/ (double duration-micros) 1000000.0)))
    (catch Exception _ nil)))

(defn- open-stream!
  "Open a fresh AudioInputStream for the source."
  [source]
  (let [stream (internal/load-audio-stream (:path source))]
    (reset! (:stream source) stream)
    stream))

(defn- close-current-stream!
  "Close the current stream if open."
  [source]
  (when-let [stream @(:stream source)]
    (try (.close ^AudioInputStream stream) (catch Exception _))
    (reset! (:stream source) nil)))

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
;; Playback thread
;; ============================================================

(defn- playback-loop
  "Main playback loop run in a thread.
   Reads from stream, writes to line, handles pause/loop/seek."
  [source]
  (let [{:keys [line format state position lock looping? seek-request seeking? display-pos]} source
        ^SourceDataLine sdl line
        ^AudioFormat fmt format
        buffer-size 4096
        buffer (byte-array buffer-size)
        frame-size (.getFrameSize fmt)
        frame-rate (.getFrameRate fmt)]
    (try
      (loop []
        ;; Handle pending seek request first
        (when-let [{:keys [target-seconds was-playing?]} @seek-request]
          (reset! seek-request nil)
          (reset! seeking? true)
          (try
            (.stop sdl)
            (.flush sdl)
            (close-current-stream! source)
            (let [stream (open-stream! source)
                  target-frame (long (* target-seconds frame-rate))
                  target-bytes (* target-frame frame-size)]
              (when (pos? target-bytes)
                (skip-by-reading! stream target-bytes))
              (reset! position target-frame)
              (reset! display-pos target-frame))
            (when was-playing?
              (.start sdl)
              (reset! state :playing))
            (finally
              (reset! seeking? false))))

        (let [current-state @state]
          (cond
            ;; Stopped - exit thread
            (= current-state :stopped)
            nil

            ;; Paused - wait for resume or seek
            (= current-state :paused)
            (do
              (locking lock
                (when (and (= @state :paused) (nil? @seek-request))
                  (.wait ^Object lock)))
              (recur))

            ;; Playing - read and write
            (= current-state :playing)
            (let [^AudioInputStream stream @(:stream source)
                  bytes-read (when stream (.read stream buffer 0 buffer-size))]
              (cond
                ;; End of stream
                (or (nil? bytes-read) (neg? bytes-read))
                (if @looping?
                  ;; Loop: rewind and continue
                  (do
                    (close-current-stream! source)
                    (open-stream! source)
                    (reset! position 0)
                    (reset! display-pos 0)
                    (recur))
                  ;; No loop: stop
                  (do
                    (reset! state :stopped)
                    (.drain sdl)
                    (.stop sdl)))

                ;; Got data - write to line
                (pos? bytes-read)
                (let [frames-written (/ bytes-read frame-size)]
                  (.write sdl buffer 0 bytes-read)
                  (swap! position + frames-written)
                  (reset! display-pos @position)
                  (recur))

                :else
                (recur)))

            :else
            nil)))
      (catch Exception e
        (when-not (= @state :stopped)
          (println "Streaming playback error:" (.getMessage e)))))))

(defn- start-playback-thread!
  "Start the playback thread for a source."
  [source]
  (let [thread (Thread.
                 ^Runnable (fn [] (playback-loop source))
                 "audio-stream-playback")]
    (.setDaemon thread true)
    (reset! (:thread source) thread)
    (.start thread)))

;; ============================================================
;; StreamSource record
;; ============================================================

(defrecord StreamSource [path format line duration stream state position thread volume looping? lock
                         seek-request seeking? display-pos]
  proto/AudioSource

  (play* [this]
    (let [^SourceDataLine sdl line]
      ;; Stop any existing playback
      (reset! seek-request nil)
      (reset! state :stopped)
      (when-let [t @thread]
        (locking lock (.notifyAll ^Object lock))
        (.join ^Thread t 100))
      (.stop sdl)
      (.flush sdl)
      ;; Reset and start fresh
      (close-current-stream! this)
      (open-stream! this)
      (reset! position 0)
      (reset! display-pos 0)
      (reset! state :playing)
      (.start sdl)
      (start-playback-thread! this))
    this)

  (stop* [this]
    (let [^SourceDataLine sdl line]
      (reset! seek-request nil)
      (reset! state :stopped)
      (when-let [t @thread]
        (locking lock (.notifyAll ^Object lock))
        (.join ^Thread t 100))
      (.stop sdl)
      (.flush sdl)
      (close-current-stream! this)
      (reset! position 0)
      (reset! display-pos 0))
    this)

  (pause* [this]
    (when (= @state :playing)
      (reset! state :paused)
      (.stop ^SourceDataLine line))
    this)

  (resume* [this]
    (when (= @state :paused)
      (reset! state :playing)
      (.start ^SourceDataLine line)
      (locking lock
        (.notifyAll ^Object lock)))
    this)

  (seek* [this seconds]
    (let [^AudioFormat fmt format
          frame-rate (.getFrameRate fmt)
          clamped-seconds (max 0.0 (min (double seconds) duration))
          target-frame (long (* clamped-seconds frame-rate))]
      ;; Immediately update display position (UI responsiveness)
      (reset! display-pos target-frame)
      ;; Queue seek request for playback thread
      (reset! seek-request {:target-seconds clamped-seconds
                            :was-playing? (= @state :playing)})
      ;; Wake playback thread
      (locking lock
        (.notifyAll ^Object lock)))
    this)

  (tell* [this]
    (/ (double @display-pos) (.getFrameRate ^AudioFormat format)))

  (duration* [this]
    duration)

  (set-volume* [this vol]
    (reset! volume vol)
    (apply-volume! line vol)
    this)

  (set-loop* [this loop?]
    (reset! looping? loop?)
    this)

  (playing?* [this]
    (= @state :playing))

  (paused?* [this]
    (= @state :paused))

  (looping?* [this]
    @looping?)

  (seeking?* [this]
    @seeking?)

  (get-volume* [this]
    @volume)

  (close* [this]
    (let [^SourceDataLine sdl line]
      ;; Signal thread to stop
      (reset! state :stopped)
      (when-let [t @thread]
        (locking lock (.notifyAll ^Object lock))
        (.join ^Thread t 200))
      ;; Close resources
      (close-current-stream! this)
      (.stop sdl)
      (.close sdl))))

;; ============================================================
;; Constructor
;; ============================================================

(defn create-stream-source
  "Create a StreamSource from an audio file path."
  [^String path]
  (let [file (File. path)
        _ (when-not (.exists file)
            (throw (ex-info "Audio file not found" {:path path})))
        ;; Load stream to compute duration, then close
        initial-stream (internal/load-audio-stream path)
        format (.getFormat initial-stream)
        ;; Try properties first (works for MP3/OGG), fallback to frame-based
        duration (or (compute-duration-from-file path)
                     (compute-duration initial-stream))
        _ (.close initial-stream)
        ;; Create the SourceDataLine
        info (DataLine$Info. SourceDataLine format)
        line (AudioSystem/getLine info)]
    (.open ^SourceDataLine line format)
    (->StreamSource path
                    format
                    line
                    duration
                    (atom nil)           ;; stream
                    (atom :stopped)      ;; state
                    (atom 0)             ;; position
                    (atom nil)           ;; thread
                    (atom 1.0)           ;; volume
                    (atom false)         ;; looping?
                    (Object.)            ;; lock
                    (atom nil)           ;; seek-request
                    (atom false)         ;; seeking?
                    (atom 0))))          ;; display-pos

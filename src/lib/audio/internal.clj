(ns lib.audio.internal
  "Java Sound API interop layer.
   Provides low-level Clip operations for audio playback.
   Supports WAV (built-in), OGG (vorbisspi), and MP3 (mp3spi) via SPIs."
  (:import [javax.sound.sampled AudioSystem AudioInputStream Clip
            FloatControl FloatControl$Type LineEvent LineEvent$Type]
           [java.io File]))

(defn load-audio-stream
  "Load audio file, returns AudioInputStream.
   Supports WAV/OGG/MP3 via SPIs on the classpath."
  ^AudioInputStream [^String path]
  (let [file (File. path)]
    (when-not (.exists file)
      (throw (ex-info "Audio file not found" {:path path})))
    (AudioSystem/getAudioInputStream file)))

(defn create-clip
  "Create a Clip from an audio file path.
   Returns the opened Clip ready for playback."
  ^Clip [^String path]
  (let [stream (load-audio-stream path)
        clip (AudioSystem/getClip)]
    (.open clip stream)
    clip))

(defn play-clip!
  "Start playing the clip from current position."
  [^Clip clip]
  (.start clip))

(defn stop-clip!
  "Stop the clip and reset position to beginning."
  [^Clip clip]
  (.stop clip)
  (.setFramePosition clip 0))

(defn pause-clip!
  "Pause the clip at current position."
  [^Clip clip]
  (.stop clip))

(defn resume-clip!
  "Resume the clip from current position."
  [^Clip clip]
  (.start clip))

(defn set-clip-volume!
  "Set clip volume (0.0 to 1.0, can exceed 1.0 for boost).
   Uses decibels internally via MASTER_GAIN control."
  [^Clip clip ^double volume]
  (when (.isControlSupported clip FloatControl$Type/MASTER_GAIN)
    (let [ctrl (.getControl clip FloatControl$Type/MASTER_GAIN)
          min-db (.getMinimum ctrl)
          max-db (.getMaximum ctrl)
          ;; Map 0.0-1.0 to dB range (logarithmic)
          ;; At volume=0, use minimum dB (effectively silent)
          ;; At volume=1, use 0 dB (unity gain)
          ;; Allow >1.0 for boost up to max-db
          db (if (<= volume 0.0)
               min-db
               (let [log-vol (* 20.0 (Math/log10 volume))]
                 (max min-db (min max-db log-vol))))]
      (.setValue ^FloatControl ctrl (float db)))))

(defn set-clip-loop!
  "Set clip looping. true = loop continuously, false = play once."
  [^Clip clip looping?]
  (if looping?
    (.loop clip Clip/LOOP_CONTINUOUSLY)
    (.loop clip 0)))

(defn clip-playing?
  "Check if the clip is currently playing."
  [^Clip clip]
  (.isRunning clip))

(defn clip-position
  "Get current playback position in seconds."
  ^double [^Clip clip]
  (/ (.getMicrosecondPosition clip) 1000000.0))

(defn clip-duration
  "Get total duration in seconds."
  ^double [^Clip clip]
  (/ (.getMicrosecondLength clip) 1000000.0))

(defn seek-clip!
  "Seek to a position in seconds."
  [^Clip clip ^double seconds]
  (let [micros (long (* seconds 1000000.0))]
    (.setMicrosecondPosition clip micros)))

(defn close-clip!
  "Close the clip and release resources."
  [^Clip clip]
  (.stop clip)
  (.close clip))

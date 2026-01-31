(ns lib.audio.static
  "Clip-based static audio source.
   Loads entire audio into memory - best for short sounds (SFX)."
  (:require [lib.audio.protocol :as proto]
            [lib.audio.internal :as internal])
  (:import [javax.sound.sampled Clip]))

;; ============================================================
;; StaticSource record
;; ============================================================

(defrecord StaticSource [clip volume looping? paused?]
  proto/AudioSource

  (play* [this]
    (let [^Clip c clip]
      (internal/stop-clip! c)
      (when @looping?
        (internal/set-clip-loop! c true))
      (internal/play-clip! c)
      (reset! paused? false))
    this)

  (stop* [this]
    (internal/stop-clip! clip)
    (reset! paused? false)
    this)

  (pause* [this]
    (when (internal/clip-playing? clip)
      (internal/pause-clip! clip)
      (reset! paused? true))
    this)

  (resume* [this]
    (when @paused?
      (when @looping?
        (internal/set-clip-loop! clip true))
      (internal/resume-clip! clip)
      (reset! paused? false))
    this)

  (seek* [this seconds]
    (internal/seek-clip! clip seconds)
    this)

  (tell* [this]
    (let [pos (internal/clip-position clip)]
      (if @looping?
        (let [dur (internal/clip-duration clip)]
          (if (pos? dur)
            (mod pos dur)
            pos))
        pos)))

  (duration* [this]
    (internal/clip-duration clip))

  (set-volume* [this vol]
    (internal/set-clip-volume! clip vol)
    (reset! volume vol)
    this)

  (set-loop* [this loop?]
    (internal/set-clip-loop! clip loop?)
    (reset! looping? loop?)
    this)

  (playing?* [this]
    (internal/clip-playing? clip))

  (paused?* [this]
    @paused?)

  (looping?* [this]
    @looping?)

  (seeking?* [this]
    false)

  (get-volume* [this]
    @volume)

  (close* [this]
    (internal/close-clip! clip)))

;; ============================================================
;; Constructor
;; ============================================================

(defn create-static-source
  "Create a StaticSource from an audio file path."
  [^String path]
  (let [clip (internal/create-clip path)]
    (->StaticSource clip
                    (atom 1.0)
                    (atom false)
                    (atom false))))

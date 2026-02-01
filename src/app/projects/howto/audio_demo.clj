(ns app.projects.howto.audio-demo
  "Audio Demo - Love2D-style audio playback.

   Demonstrates:
   - Loading and playing audio files (MP3, OGG, WAV)
   - Play, pause, resume, stop controls
   - Volume adjustment
   - Looping toggle

   Controls:
   - SPACE: Play/Pause toggle
   - S: Stop (resets to beginning)
   - L: Toggle looping
   - UP/DOWN: Volume up/down
   - LEFT/RIGHT: Seek backward/forward"
  (:require [lib.audio.core :as audio]
            [lib.graphics.shapes :as shapes]
            [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def audio-file "resources/sounds/from-pixabay-dot-com.mp3")

;; ============================================================
;; State
;; ============================================================

(defonce music (atom nil))
(defonce volume (atom 1.0))

;; ============================================================
;; Drawing
;; ============================================================

(def text-color 0xFFCCCCCC)  ;; Light gray for dark background
(def playing-color 0xFF4CAF50)
(def paused-color 0xFFFF9800)
(def stopped-color 0xFF666666)

(defn format-time [seconds]
  (when seconds
    (let [mins (int (/ seconds 60))
          secs (int (mod seconds 60))]
      (format "%d:%02d" mins secs))))

(defn draw-status [^Canvas canvas w h]
  (let [music-source @music
        is-playing (and music-source (audio/playing? music-source))
        is-paused (and music-source (audio/paused? music-source))
        status-text (cond
                      is-playing "PLAYING"
                      is-paused "PAUSED"
                      :else "STOPPED")
        status-color (cond
                       is-playing playing-color
                       is-paused paused-color
                       :else stopped-color)
        current-pos (when music-source (audio/tell music-source))
        total-dur (when music-source (audio/duration music-source))
        time-text (str (format-time current-pos) " / " (format-time total-dur))
        vol-text (str "Volume: " (int (* @volume 100)) "%")
        loop-text (str "Loop: " (if (and music-source (audio/looping? music-source)) "ON" "OFF"))
        cx (/ w 2)
        cy (/ h 2)]
    ;; Title
    (text/text canvas "Audio Demo" (- cx 80) 60 {:size 32 :color text-color})
    ;; Status indicator
    (shapes/circle canvas cx (- cy 60) 30 {:color status-color})
    (text/text canvas status-text (- cx 45) (- cy 50) {:size 20 :color 0xFFFFFFFF})
    ;; Time
    (text/text canvas time-text (- cx 50) (+ cy 20) {:size 24 :color text-color})
    ;; Progress bar
    (let [bar-w 300
          bar-h 8
          bar-x (- cx (/ bar-w 2))
          bar-y (+ cy 50)
          progress (if (and current-pos total-dur (pos? total-dur))
                     (/ current-pos total-dur)
                     0)]
      (shapes/rect canvas bar-x bar-y bar-w bar-h {:color 0xFF444444 :radius 4})
      (shapes/rect canvas bar-x bar-y (* bar-w progress) bar-h {:color status-color :radius 4}))
    ;; Volume and loop status
    (text/text canvas vol-text (- cx 50) (+ cy 100) {:size 18 :color text-color})
    (text/text canvas loop-text (- cx 30) (+ cy 130) {:size 18 :color text-color})
    ;; Controls help
    (let [help-y (+ cy 180)]
      (text/text canvas "SPACE: Play/Pause   S: Stop   L: Loop" (- cx 160) help-y {:size 14 :color 0xFF888888})
      (text/text canvas "UP/DOWN: Volume   LEFT/RIGHT: Seek" (- cx 145) (+ help-y 20) {:size 14 :color 0xFF888888}))))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  "Called once when example starts."
  (println "Audio Demo loaded!")
  (println "Loading audio file:" audio-file)
  (try
    (reset! music (audio/new-source audio-file))
    (println "Audio loaded successfully. Duration:" (format-time (audio/duration @music)))
    (catch Exception e
      (println "Failed to load audio:" (.getMessage e)))))

(defn tick [_dt]
  "Called every frame with delta time."
  nil)

;; ============================================================
;; Keyboard handler
;; ============================================================

(defn on-key-pressed
  "Handle key press events.
   Called by the shell when a key is pressed."
  [key _modifiers]
  (when-let [music-source @music]
    (cond
      ;; SPACE - Play/Pause toggle
      (= key 0x20)
      (if (audio/playing? music-source)
        (audio/pause! music-source)
        (if (audio/paused? music-source)
          (audio/resume! music-source)
          (audio/play! music-source)))

      ;; S - Stop
      (= key 0x73)
      (audio/stop! music-source)

      ;; L - Toggle loop
      (= key 0x6C)
      (audio/set-looping! music-source (not (audio/looping? music-source)))

      ;; UP - Volume up
      (= key 0x40000052)
      (do
        (swap! volume #(min 2.0 (+ % 0.1)))
        (audio/set-volume! music-source @volume))

      ;; DOWN - Volume down
      (= key 0x40000051)
      (do
        (swap! volume #(max 0.0 (- % 0.1)))
        (audio/set-volume! music-source @volume))

      ;; RIGHT - Seek forward 5s
      (= key 0x4000004F)
      (let [pos (audio/tell music-source)
            dur (audio/duration music-source)]
        (audio/seek! music-source (min dur (+ pos 5))))

      ;; LEFT - Seek backward 5s
      (= key 0x40000050)
      (let [pos (audio/tell music-source)]
        (audio/seek! music-source (max 0 (- pos 5)))))))

(defn draw [^Canvas canvas width height]
  "Called every frame for rendering."
  (draw-status canvas width height))

(defn cleanup []
  "Called when switching away from this example.
   Note: close! is optional - audio cleanup happens automatically on window close."
  (when @music
    (audio/stop! @music))  ;; Just stop playback, close is automatic
  (reset! music nil)
  (println "Audio Demo cleanup"))

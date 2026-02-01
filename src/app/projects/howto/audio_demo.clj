(ns app.projects.howto.audio-demo
  "Audio Demo - Love2D-style audio playback with streaming.

   Demonstrates:
   - Loading audio with {:type :stream} for low-memory streaming
   - Play, pause, resume, stop controls
   - Volume adjustment (immediate response with SourceDataLine)
   - Looping toggle
   - Seek (via stream rewind)

   Controls:
   - SPACE: Play/Pause toggle
   - S: Stop (resets to beginning)
   - L: Toggle looping
   - UP/DOWN: Volume up/down
   - LEFT/RIGHT: Seek backward/forward"
  (:require [lib.color.open-color :as oc]
            [lib.audio.core :as audio]
            [lib.graphics.shapes :as shapes]
            [lib.graphics.path :as path]
            [lib.text.core :as text]
            [lib.gesture.api :as gesture])
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

;; Window dimensions (updated each frame for gesture handlers)
(defonce window-width (atom 800))
(defonce window-height (atom 600))

;; ============================================================
;; Drawing
;; ============================================================

(def text-color oc/gray-4)
(def playing-color oc/green-7)
(def paused-color oc/yellow-8)
(def stopped-color [0.4 0.4 0.4 1.0])

(defn format-time [seconds]
  (when seconds
    (let [mins (int (/ seconds 60))
          secs (int (mod seconds 60))]
      (format "%d:%02d" mins secs))))

(defn- draw-play-icon
  "Draw a play triangle icon centered at (cx, cy) with given size."
  [^Canvas canvas cx cy size color]
  (let [half (/ size 2)
        ;; Play triangle pointing right
        play-path (path/polygon [[(- cx half) (- cy half)]
                                 [(+ cx half) cy]
                                 [(- cx half) (+ cy half)]])]
    (shapes/path canvas play-path {:color color})))

(defn- draw-pause-icon
  "Draw pause bars icon centered at (cx, cy) with given size."
  [^Canvas canvas cx cy size color]
  (let [bar-w (/ size 4)
        bar-h size
        gap (/ size 5)
        left-x (- cx gap (/ bar-w 2))
        right-x (+ cx gap (- (/ bar-w 2)))
        top-y (- cy (/ bar-h 2))]
    (shapes/rounded-rect canvas left-x top-y bar-w bar-h 2 {:color color})
    (shapes/rounded-rect canvas right-x top-y bar-w bar-h 2 {:color color})))

(defn- draw-stop-icon
  "Draw a stop square icon centered at (cx, cy) with given size."
  [^Canvas canvas cx cy size color]
  (let [half (/ size 2)]
    (shapes/rounded-rect canvas (- cx half) (- cy half) size size 3 {:color color})))

;; ============================================================
;; Seek Bar Helpers
;; ============================================================

(defn- get-seek-bar-bounds
  "Return seek bar bounds as [x y w h] given window dimensions."
  [w h]
  (let [bar-w 300
        bar-h 8
        cx (/ w 2)
        cy (/ h 2)
        bar-x (- cx (/ bar-w 2))
        bar-y (+ cy 50)]
    [bar-x bar-y bar-w bar-h]))

(defn- seek-from-x
  "Seek audio to position based on x coordinate within seek bar."
  [x w h]
  (when-let [music-source @music]
    (let [[bar-x _ bar-w _] (get-seek-bar-bounds w h)
          ratio (/ (- x bar-x) bar-w)
          ratio (max 0.0 (min 1.0 ratio))
          duration (audio/duration music-source)]
      (audio/seek! music-source (* ratio duration)))))

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
        cy (/ h 2)
        icon-size 40
        icon-y (- cy 90)]
    ;; Title
    (text/text canvas "Audio Demo" cx 60 {:size 32 :color text-color :align :center})
    ;; Play/Pause/Stop icon
    (cond
      is-playing (draw-play-icon canvas cx icon-y icon-size status-color)
      is-paused (draw-pause-icon canvas cx icon-y icon-size status-color)
      :else (draw-stop-icon canvas cx icon-y icon-size status-color))
    ;; Status text below icon
    (text/text canvas status-text cx (- cy 35) {:size 20 :color status-color :align :center})
    ;; Time
    (text/text canvas time-text cx (+ cy 20) {:size 24 :color text-color :align :center})
    ;; Progress bar (using shared bounds for gesture hit-testing)
    (let [[bar-x bar-y bar-w bar-h] (get-seek-bar-bounds w h)
          progress (if (and current-pos total-dur (pos? total-dur))
                     (min 1.0 (/ current-pos total-dur))
                     0)]
      (shapes/rounded-rect canvas bar-x bar-y bar-w bar-h 4 {:color [0.267 0.267 0.267 1.0]})
      (shapes/rounded-rect canvas bar-x bar-y (* bar-w progress) bar-h 4 {:color status-color}))
    ;; Volume and loop status
    (text/text canvas vol-text cx (+ cy 100) {:size 18 :color text-color :align :center})
    (text/text canvas loop-text cx (+ cy 130) {:size 18 :color text-color :align :center})
    ;; Controls help
    (let [help-y (+ cy 180)]
      (text/text canvas "SPACE: Play/Pause   S: Stop   L: Loop" cx help-y {:size 14 :color [0.533 0.533 0.533 1.0] :align :center})
      (text/text canvas "UP/DOWN: Volume   LEFT/RIGHT: Seek" cx (+ help-y 20) {:size 14 :color [0.533 0.533 0.533 1.0] :align :center}))))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  "Called once when example starts."
  (println "Audio Demo loaded!")
  (println "Loading audio file:" audio-file "(streaming mode)")
  (try
    (reset! music (audio/from-file audio-file {:type :stream}))
    (println "Audio loaded successfully. Duration:" (format-time (audio/duration @music)))
    (catch Exception e
      (println "Failed to load audio:" (.getMessage e))))
  ;; Register seek bar for click/drag
  (gesture/register-target!
    {:id :audio-seek-bar
     :layer :content
     :z-index 10
     :bounds-fn (fn [_ctx]
                  (get-seek-bar-bounds @window-width @window-height))
     :gesture-recognizers [:drag]
     :handlers {:on-pointer-down (fn [event]
                                   (let [x (get-in event [:pointer :x])]
                                     (seek-from-x x @window-width @window-height)))
                :on-drag (fn [event]
                           (let [x (get-in event [:pointer :x])]
                             (seek-from-x x @window-width @window-height)))}}))

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
  ;; Update window dimensions for gesture handlers
  (reset! window-width width)
  (reset! window-height height)
  (draw-status canvas width height))

(defn cleanup []
  "Called when switching away from this example.
   Note: close! is optional - audio cleanup happens automatically on window close."
  (gesture/unregister-target! :audio-seek-bar)
  (when @music
    (audio/stop! @music))  ;; Just stop playback, close is automatic
  (reset! music nil)
  (println "Audio Demo cleanup"))

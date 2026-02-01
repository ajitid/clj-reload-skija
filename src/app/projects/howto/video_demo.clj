(ns app.projects.howto.video-demo
  "Video Demo - GPU-accelerated video playback with Skia effects.

   Demonstrates:
   - Loading video with (video/from-file)
   - Play, pause, stop controls
   - Seek via progress bar
   - Skia effects on video (rounded corners)

   Controls:
   - SPACE: Play/Pause toggle
   - S: Stop (reset to beginning)
   - LEFT/RIGHT: Seek backward/forward 5s

   Note: Requires a video file. Place 'sample.mp4' in resources/videos/
   or change the video-file path below."
  (:require [lib.color.open-color :as oc]
            [lib.video.core :as video]
            [lib.window.layer :as layer]
            [lib.graphics.shapes :as shapes]
            [lib.graphics.clip :as clip]
            [lib.text.core :as text]
            [lib.gesture.api :as gesture])
  (:import [io.github.humbleui.skija Canvas]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Configuration
;; ============================================================

;; Change this path to your video file
(def video-file "resources/videos/sample.mp4")

;; ============================================================
;; State
;; ============================================================

(defonce video-source (atom nil))

;; Window dimensions (updated each frame for gesture handlers)
(defonce window-width (atom 800))
(defonce window-height (atom 600))

;; ============================================================
;; Drawing Helpers
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

;; ============================================================
;; Seek Bar
;; ============================================================

(defn- get-seek-bar-bounds
  "Return seek bar bounds as [x y w h] given video position."
  [video-x video-y video-w]
  (let [bar-w video-w
        bar-h 8
        bar-x video-x
        bar-y (+ video-y 20)]  ; Below video
    [bar-x bar-y bar-w bar-h]))

(defn- seek-from-x
  "Seek video to position based on x coordinate within seek bar."
  [x video-x video-w]
  (when-let [source @video-source]
    (let [ratio (/ (- x video-x) video-w)
          ratio (max 0.0 (min 1.0 ratio))
          dur (video/duration source)]
      (video/seek! source (* ratio dur)))))

;; ============================================================
;; Video Drawing
;; ============================================================

(defn draw-video-frame
  "Draw the current video frame with rounded corners."
  [^Canvas canvas source x y w h corner-radius]
  (let [ctx (layer/context)]
    ;; Ensure first frame is decoded for preview
    (video/ensure-first-frame! source)
    (when-let [frame (video/current-frame source ctx)]
      ;; Calculate scaling to fit video in bounds while maintaining aspect ratio
      (let [video-w (video/width source)
            video-h (video/height source)
            scale-x (/ w video-w)
            scale-y (/ h video-h)
            scale (min scale-x scale-y)
            scaled-w (* video-w scale)
            scaled-h (* video-h scale)
            offset-x (+ x (/ (- w scaled-w) 2))
            offset-y (+ y (/ (- h scaled-h) 2))]
        ;; Draw with rounded corners using clip
        (clip/with-clip [canvas [offset-x offset-y scaled-w scaled-h corner-radius]]
          (let [src-rect (Rect/makeXYWH 0 0 video-w video-h)
                dst-rect (Rect/makeXYWH offset-x offset-y scaled-w scaled-h)]
            (.drawImageRect canvas frame src-rect dst-rect)))))))

(defn draw-controls
  "Draw playback controls and seek bar."
  [^Canvas canvas w h video-x video-y video-w video-h]
  (let [source @video-source
        is-playing (and source (video/playing? source))
        is-paused (and source (video/paused? source))
        status-color (cond
                       is-playing playing-color
                       is-paused paused-color
                       :else stopped-color)
        current-pos (when source (video/tell source))
        total-dur (when source (video/duration source))
        time-text (str (format-time current-pos) " / " (format-time total-dur))
        ;; Seek bar below video
        [bar-x bar-y bar-w bar-h] (get-seek-bar-bounds video-x (+ video-y video-h) video-w)
        progress (if (and current-pos total-dur (pos? total-dur))
                   (min 1.0 (/ current-pos total-dur))
                   0)]
    ;; Time display
    (text/text canvas time-text (+ video-x (/ video-w 2)) (+ bar-y 30)
               {:size 18 :color text-color :align :center})
    ;; Seek bar background
    (shapes/rounded-rect canvas bar-x bar-y bar-w bar-h 4
                         {:color [0.267 0.267 0.267 1.0]})
    ;; Seek bar progress
    (when (pos? progress)
      (shapes/rounded-rect canvas bar-x bar-y (* bar-w progress) bar-h 4
                           {:color status-color}))
    ;; Controls help
    (let [help-y (+ bar-y 60)]
      (text/text canvas "SPACE: Play/Pause   S: Stop   LEFT/RIGHT: Seek"
                 (+ video-x (/ video-w 2)) help-y
                 {:size 14 :color [0.533 0.533 0.533 1.0] :align :center}))))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  "Called once when example starts."
  (println "Video Demo loaded!")
  (println "Loading video file:" video-file)
  (try
    (reset! video-source (video/from-file video-file {:hw-accel? true}))
    (let [source @video-source]
      (println "Video loaded successfully.")
      (println (format "  Resolution: %dx%d" (video/width source) (video/height source)))
      (println (format "  Duration: %s" (format-time (video/duration source))))
      (println (format "  FPS: %.2f" (double (video/fps source)))))
    (catch Exception e
      (println "Failed to load video:" (.getMessage e))
      (println "Make sure the video file exists at:" video-file))))

(defn tick [dt]
  "Called every frame with delta time."
  ;; Advance video playback
  (when-let [source @video-source]
    (video/advance-frame! source dt)))

(defn on-key-pressed
  "Handle key press events."
  [key _modifiers]
  (when-let [source @video-source]
    (cond
      ;; SPACE - Play/Pause toggle
      (= key 0x20)
      (if (video/playing? source)
        (video/pause! source)
        (video/play! source))

      ;; S - Stop
      (= key 0x73)
      (video/stop! source)

      ;; RIGHT - Seek forward 5s
      (= key 0x4000004F)
      (let [pos (video/tell source)
            dur (video/duration source)]
        (video/seek! source (min dur (+ pos 5))))

      ;; LEFT - Seek backward 5s
      (= key 0x40000050)
      (let [pos (video/tell source)]
        (video/seek! source (max 0 (- pos 5)))))))

(defn draw [^Canvas canvas w h]
  "Called every frame for rendering."
  ;; Update window dimensions
  (reset! window-width w)
  (reset! window-height h)
  ;; Title
  (text/text canvas "Video Demo" (/ w 2) 40 {:size 28 :color text-color :align :center})
  ;; Video area (centered, with padding)
  (when @video-source
    (let [padding 60
          max-video-w (- w (* 2 padding))
          max-video-h (- h 200)  ; Leave room for controls
          video-x padding
          video-y 80
          corner-radius 16]
      ;; Draw video frame
      (draw-video-frame canvas @video-source video-x video-y max-video-w max-video-h corner-radius)
      ;; Draw controls
      (draw-controls canvas w h video-x video-y max-video-w max-video-h)))
  ;; Show message if no video loaded
  (when-not @video-source
    (text/text canvas "No video loaded" (/ w 2) (/ h 2)
               {:size 20 :color [0.6 0.6 0.6 1.0] :align :center})
    (text/text canvas (str "Expected: " video-file) (/ w 2) (+ (/ h 2) 30)
               {:size 14 :color [0.5 0.5 0.5 1.0] :align :center})))

(defn cleanup []
  "Called when switching away from this example."
  (when @video-source
    (video/close! @video-source))
  (reset! video-source nil)
  (println "Video Demo cleanup"))

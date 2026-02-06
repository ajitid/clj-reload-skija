(ns app.projects.howto.video-demo
  "Video Demo - GPU-accelerated video playback with Skia effects and audio sync.

   Demonstrates:
   - Loading video with (video/file->video)
   - Play, pause, stop controls
   - Seek via progress bar
   - Skia effects on video (rounded corners)
   - Audio playback with A/V sync
   - Volume control

   Controls:
   - SPACE: Play/Pause toggle
   - S: Stop (reset to beginning)
   - LEFT/RIGHT: Seek backward/forward 5s
   - UP/DOWN: Volume up/down
   - M: Mute/unmute toggle

   Note: Requires a video file. Place 'sample.mp4' in resources/videos/
   or change the video-file path below."
  (:require [lib.color.open-color :as oc]
            [lib.video.core :as video]
            [lib.window.layer :as layer]
            [lib.window.layer-metal :as layer-metal]
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

(def window-config {:backend :auto})

;; ============================================================
;; State
;; ============================================================

(defonce video-source (atom nil))

;; Window dimensions (updated each frame for gesture handlers)
(defonce window-width (atom 800))
(defonce window-height (atom 600))

;; Volume state (for mute toggle)
(defonce last-volume (atom 1.0))
(defonce muted? (atom false))

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
;; Layout Helpers
;; ============================================================

(defn- get-video-layout
  "Calculate video layout from window dimensions.
   Returns {:video-x :video-y :video-w :video-h}."
  [w h]
  (let [padding 60
        video-x padding
        video-y 80
        video-w (- w (* 2 padding))
        video-h (- h 200)]
    {:video-x video-x
     :video-y video-y
     :video-w video-w
     :video-h video-h}))

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

(defn- get-seek-bar-bounds-from-window
  "Return seek bar bounds as [x y w h] from window dimensions.
   Used for gesture hit-testing."
  [w h]
  (let [{:keys [video-x video-y video-w video-h]} (get-video-layout w h)]
    (get-seek-bar-bounds video-x (+ video-y video-h) video-w)))

(defn- seek-from-x
  "Seek video to position based on x coordinate within seek bar."
  [x w h]
  (when-let [source @video-source]
    (let [{:keys [video-x video-w]} (get-video-layout w h)
          ratio (/ (- x video-x) video-w)
          ratio (max 0.0 (min 1.0 ratio))
          dur (video/duration source)]
      (video/seek! source (* ratio dur)))))

;; ============================================================
;; Video Drawing
;; ============================================================

(defn draw-video-frame
  "Draw the current video frame with rounded corners.
   Uses draw-frame! API for backend-agnostic rendering (Metal + OpenGL)."
  [^Canvas canvas source x y w h corner-radius]
  ;; Get context from the active backend (Metal or OpenGL)
  (let [ctx (or (layer-metal/context) (layer/context))
        video-w (video/width source)
        video-h (video/height source)
        ;; Calculate scaling to fit video in bounds while maintaining aspect ratio
        scale-x (/ w video-w)
        scale-y (/ h video-h)
        scale (min scale-x scale-y)
        scaled-w (* video-w scale)
        scaled-h (* video-h scale)
        offset-x (+ x (/ (- w scaled-w) 2))
        offset-y (+ y (/ (- h scaled-h) 2))]
    ;; Use draw-frame! which handles both Metal and OpenGL paths
    ;; Note: Rounded corners clip only works for OpenGL path.
    ;; For Metal, the blit bypasses Skia so clip has no effect.
    (clip/with-clip [canvas [offset-x offset-y scaled-w scaled-h corner-radius]]
      (video/draw-frame! source canvas ctx offset-x offset-y scaled-w scaled-h))))

(defn draw-controls
  "Draw playback controls and seek bar."
  [^Canvas canvas w h video-x video-y video-w video-h]
  (let [source @video-source
        is-playing (and source (video/playing? source))
        is-paused (and source (video/paused? source))
        has-audio (and source (video/has-audio? source))
        status-color (cond
                       is-playing playing-color
                       is-paused paused-color
                       :else stopped-color)
        current-pos (when source (video/tell source))
        total-dur (when source (video/duration source))
        volume (when source (video/get-volume source))
        time-text (str (format-time current-pos) " / " (format-time total-dur))
        ;; Seek bar below video
        [bar-x bar-y bar-w bar-h] (get-seek-bar-bounds video-x (+ video-y video-h) video-w)
        progress (if (and current-pos total-dur (pos? total-dur))
                   (min 1.0 (/ current-pos total-dur))
                   0)]
    ;; Time display
    (text/text canvas time-text (+ video-x (/ video-w 2)) (+ bar-y 30)
               {:size 18 :color text-color :align :center})
    ;; Volume display (if audio available)
    (when has-audio
      (let [vol-text (if @muted?
                       "MUTED"
                       (format "Vol: %d%%" (int (* (or volume 1.0) 100))))]
        (text/text canvas vol-text (+ video-x video-w -10) (+ bar-y 30)
                   {:size 14 :color (if @muted? oc/red-7 text-color) :align :right})))
    ;; Audio sync indicator
    (when has-audio
      (text/text canvas "[Audio Sync]" (+ video-x 10) (+ bar-y 30)
                 {:size 14 :color oc/cyan-7 :align :left}))
    ;; Seek bar background
    (shapes/rounded-rect canvas bar-x bar-y bar-w bar-h 4
                         {:color [0.267 0.267 0.267 1.0]})
    ;; Seek bar progress
    (when (pos? progress)
      (shapes/rounded-rect canvas bar-x bar-y (* bar-w progress) bar-h 4
                           {:color status-color}))
    ;; Controls help
    (let [help-y (+ bar-y 60)
          help-text (if has-audio
                      "SPACE: Play/Pause   S: Stop   LEFT/RIGHT: Seek   UP/DOWN: Volume   M: Mute"
                      "SPACE: Play/Pause   S: Stop   LEFT/RIGHT: Seek")]
      (text/text canvas help-text
                 (+ video-x (/ video-w 2)) help-y
                 {:size 14 :color [0.533 0.533 0.533 1.0] :align :center}))))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  "Called once when example starts."
  (println "Video Demo loaded!")
  (println "Loading video file:" video-file)
  ;; Log system hwaccel capabilities
  (let [sys-info (video/system-info)]
    (println (format "System: %s %s, GPU: %s"
                     (name (:platform sys-info))
                     (name (:arch sys-info))
                     (name (:gpu-vendor sys-info))))
    (println (format "  Available decoders: %s"
                     (->> (:available sys-info)
                          (filter val)
                          (map key)
                          (map name)
                          (clojure.string/join ", ")))))
  ;; Reset audio state
  (reset! muted? false)
  (reset! last-volume 1.0)
  (try
    (reset! video-source (video/file->video video-file {:hw-accel? true}))
    (let [source @video-source
          hwaccel (video/hwaccel-type source)
          info (video/decoder-info source)
          has-audio (video/has-audio? source)]
      (println "Video loaded successfully.")
      (println (format "  Resolution: %dx%d" (video/width source) (video/height source)))
      (println (format "  Duration: %s" (format-time (video/duration source))))
      (println (format "  FPS: %.2f" (double (video/fps source))))
      (println (format "  Decoder: %s" (if hwaccel (name hwaccel) "software")))
      (println (format "  Zero-copy: %s" (if (:zero-copy? info) "yes (GPU-resident frames)" "no")))
      (println (format "  Audio: %s" (if has-audio "yes (A/V sync enabled)" "no")))
      (when (:fallback? info)
        (println "  (Fell back from hardware to software)")))
    (catch Exception e
      (println "Failed to load video:" (.getMessage e))
      (println "Make sure the video file exists at:" video-file)))
  ;; Register seek bar for click/drag
  (gesture/register-target!
   {:id :video-seek-bar
    :layer :content
    :z-index 10
    :bounds-fn (fn [_ctx]
                 (get-seek-bar-bounds-from-window @window-width @window-height))
    :gesture-recognizers [:drag]
    :handlers {:on-pointer-down (fn [event]
                                  (let [x (get-in event [:pointer :x])]
                                    (seek-from-x x @window-width @window-height)))
               :on-drag (fn [event]
                          (let [x (get-in event [:pointer :x])]
                            (seek-from-x x @window-width @window-height)))}}))

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
        (video/seek! source (max 0 (- pos 5))))

      ;; UP - Volume up
      (= key 0x40000052)
      (when (video/has-audio? source)
        (let [vol (or (video/get-volume source) 1.0)
              new-vol (min 1.0 (+ vol 0.1))]
          (video/set-volume! source new-vol)
          (reset! muted? false)))

      ;; DOWN - Volume down
      (= key 0x40000051)
      (when (video/has-audio? source)
        (let [vol (or (video/get-volume source) 1.0)
              new-vol (max 0.0 (- vol 0.1))]
          (video/set-volume! source new-vol)))

      ;; M - Mute toggle
      (= key 0x6D)
      (when (video/has-audio? source)
        (if @muted?
          ;; Unmute - restore last volume
          (do
            (video/set-volume! source @last-volume)
            (reset! muted? false))
          ;; Mute - save current volume and set to 0
          (do
            (reset! last-volume (or (video/get-volume source) 1.0))
            (video/set-volume! source 0.0)
            (reset! muted? true)))))))

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
  (gesture/unregister-target! :video-seek-bar)
  (when @video-source
    (video/close! @video-source))
  (reset! video-source nil)
  (reset! muted? false)
  (reset! last-volume 1.0)
  (println "Video Demo cleanup"))

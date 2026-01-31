(ns app.projects.howto.text-on-path
  "Text on Path - Demonstrates rendering text along curved paths.

   Shows how to:
   - Draw text along a circle path
   - Draw text along a wave path
   - Draw text along an arc path
   - Animate text offset along paths

   Note: Path creation uses lib.graphics.path, text rendering uses lib.text.path."
  (:require [app.state.system :as sys]
            [lib.text.core :as text]
            [lib.text.path :as text-path]
            [lib.graphics.path :as path]
            [lib.graphics.shapes :as shapes])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def font-size 24)
(def animation-speed 50)  ;; pixels per second

;; ============================================================
;; Drawing: Circle Path
;; ============================================================

(defn draw-circle-example [^Canvas canvas cx cy time]
  "Draw text around a circle with animated offset."
  (let [radius 80
        p (path/circle cx cy radius)
        path-len (path/length p)
        ;; Animated offset loops around the circle
        offset (mod (* time animation-speed) path-len)]
    ;; Draw the path outline for reference
    (shapes/circle canvas cx cy radius
                   {:color [0.2 0.2 0.2 1.0] :mode :stroke :stroke-width 1})
    ;; Draw text along the path
    (text-path/text-on-path canvas "Hello Circle! " p
                            {:size font-size
                             :offset offset
                             :color [0.29 0.56 0.85 1.0]})
    ;; Label
    (text/text canvas "Circle Path" cx (+ cy radius 40)
               {:size 14 :align :center :color [0.4 0.4 0.4 1.0]})))

;; ============================================================
;; Drawing: Wave Path
;; ============================================================

(defn draw-wave-example [^Canvas canvas x y width time]
  "Draw text along a sinusoidal wave."
  (let [amplitude 30
        frequency 2
        p (path/wave x y width amplitude frequency)
        path-len (path/length p)
        offset (mod (* time animation-speed) path-len)]
    ;; Draw the wave path for reference
    (shapes/path canvas p {:color [0.2 0.2 0.2 1.0] :mode :stroke :stroke-width 1})
    ;; Draw text along the wave
    (text-path/text-on-path canvas "Wavy Text ~ " p
                            {:size 20
                             :offset offset
                             :color [0.61 0.35 0.71 1.0]})
    ;; Label
    (text/text canvas "Wave Path" (+ x (/ width 2)) (+ y amplitude 50)
               {:size 14 :align :center :color [0.4 0.4 0.4 1.0]})))

;; ============================================================
;; Drawing: Arc Path
;; ============================================================

(defn draw-arc-example [^Canvas canvas cx cy]
  "Draw text along an arc (static, no animation)."
  (let [radius 100
        start-angle -150  ;; Start from upper-left
        sweep-angle 120   ;; Sweep 120 degrees
        p (path/arc cx cy radius start-angle sweep-angle)]
    ;; Draw the arc path for reference
    (shapes/path canvas p {:color [0.2 0.2 0.2 1.0] :mode :stroke :stroke-width 1})
    ;; Draw text along the arc
    (text-path/text-on-path canvas "Arc Text!" p
                            {:size font-size
                             :color [0.18 0.8 0.44 1.0]})
    ;; Label
    (text/text canvas "Arc Path" cx (+ cy radius 30)
               {:size 14 :align :center :color [0.4 0.4 0.4 1.0]})))

;; ============================================================
;; Drawing: Line Path (diagonal)
;; ============================================================

(defn draw-line-example [^Canvas canvas x1 y1 x2 y2 time]
  "Draw text along a straight diagonal line with animated offset."
  (let [p (path/line x1 y1 x2 y2)
        path-len (path/length p)
        ;; Ping-pong animation
        cycle-pos (mod (* time 0.5) 2.0)
        t (if (> cycle-pos 1.0) (- 2.0 cycle-pos) cycle-pos)
        offset (* t (- path-len 150))]
    ;; Draw the line path for reference
    (shapes/line canvas x1 y1 x2 y2
                 {:color [0.2 0.2 0.2 1.0] :stroke-width 1})
    ;; Draw text along the line
    (text-path/text-on-path canvas "Diagonal!" p
                            {:size 20
                             :offset (max 0 offset)
                             :color [0.91 0.3 0.24 1.0]})
    ;; Label
    (text/text canvas "Line Path" (/ (+ x1 x2) 2) (+ (/ (+ y1 y2) 2) 40)
               {:size 14 :align :center :color [0.4 0.4 0.4 1.0]})))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  "Called once when example starts."
  (println "Text on Path example loaded"))

(defn tick [_dt]
  "Called every frame with delta time."
  nil)

(defn draw [^Canvas canvas width height]
  "Called every frame for rendering."
  (let [time @sys/game-time
        col1-x (/ width 4)
        col2-x (* 3 (/ width 4))
        row1-y (/ height 3)
        row2-y (* 2 (/ height 3))]
    ;; Title
    (text/text canvas "Text on Path Examples"
               (/ width 2) 40
               {:size 28 :weight :medium :align :center :color [1.0 1.0 1.0 1.0]})
    (text/text canvas "Using RSXform for GPU-optimized rendering"
               (/ width 2) 70
               {:size 14 :align :center :color [0.53 0.53 0.53 1.0]})
    ;; 2x2 grid of examples
    (draw-circle-example canvas col1-x row1-y time)
    (draw-wave-example canvas (- col2-x 150) row1-y 300 time)
    (draw-arc-example canvas col1-x row2-y)
    (draw-line-example canvas (- col2-x 100) (- row2-y 60)
                       (+ col2-x 100) (+ row2-y 60) time)))

(defn cleanup []
  "Called when switching away from this example."
  (println "Text on Path cleanup"))

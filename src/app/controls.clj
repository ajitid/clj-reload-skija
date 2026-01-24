(ns app.controls
  "Control panel UI - sliders and drawing.
   Drawing logic for the control panel."
  (:require [app.state.sources :as src]
            [app.util :refer [cfg]])
  (:import [io.github.humbleui.skija Canvas Paint PaintMode Font Typeface Path]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Slider geometry
;; ============================================================

(defn calc-panel-x
  "Calculate panel x position (right-aligned)"
  [window-width]
  (- window-width (cfg 'app.config/panel-width) (cfg 'app.config/panel-right-offset)))

(defn slider-x-bounds
  "Get bounds for X slider: [x y w h]"
  [window-width]
  (let [px (calc-panel-x window-width)
        py (cfg 'app.config/panel-y)
        pad (cfg 'app.config/panel-padding)
        sw (cfg 'app.config/slider-width)
        sh (cfg 'app.config/slider-height)
        fps-offset 25
        graph-h (cfg 'app.config/fps-graph-height)]
    [(+ px pad) (+ py pad fps-offset graph-h 30) sw sh]))

(defn slider-y-bounds
  "Get bounds for Y slider: [x y w h]"
  [window-width]
  (let [px (calc-panel-x window-width)
        py (cfg 'app.config/panel-y)
        pad (cfg 'app.config/panel-padding)
        sw (cfg 'app.config/slider-width)
        sh (cfg 'app.config/slider-height)
        fps-offset 25
        graph-h (cfg 'app.config/fps-graph-height)]
    [(+ px pad) (+ py pad fps-offset graph-h 30 sh 30) sw sh]))

(defn slider-value-from-x
  "Convert mouse x position to slider value (min-max)"
  [mouse-x [sx _ sw _]]
  (let [min-val (cfg 'app.config/min-circles)
        max-val (cfg 'app.config/max-circles)
        ratio (/ (- mouse-x sx) sw)
        ratio (max 0.0 (min 1.0 ratio))]
    (int (Math/round (double (+ min-val (* ratio (- max-val min-val))))))))

;; ============================================================
;; Drawing
;; ============================================================

(defn draw-slider
  "Draw a slider with label and value above it.
   Layout:  Label: <value>
            [====slider====]"
  [^Canvas canvas label value [sx sy sw sh]]
  (let [min-val (cfg 'app.config/min-circles)
        max-val (cfg 'app.config/max-circles)
        ratio (/ (- value min-val) (- max-val min-val))
        fill-w (* sw ratio)
        font-size (or (cfg 'app.config/font-size) 18)]
    ;; Draw label and value ABOVE the slider
    (with-open [typeface (Typeface/makeDefault)
                font (Font. typeface (float font-size))
                text-paint (doto (Paint.)
                             (.setColor (unchecked-int (cfg 'app.config/panel-text-color))))]
      (.drawString canvas (str label " " value) (float sx) (float (- sy 6)) font text-paint))
    ;; Draw track
    (with-open [track-paint (doto (Paint.)
                              (.setColor (unchecked-int (cfg 'app.config/slider-track-color))))]
      (.drawRect canvas (Rect/makeXYWH (float sx) (float sy) (float sw) (float sh)) track-paint))
    ;; Draw fill
    (with-open [fill-paint (doto (Paint.)
                             (.setColor (unchecked-int (cfg 'app.config/slider-fill-color))))]
      (.drawRect canvas (Rect/makeXYWH (float sx) (float sy) (float fill-w) (float sh)) fill-paint))))

;; Pre-allocated resources for FPS graph (zero per-frame allocations)
;; Using def (not defonce) so colors update on hot-reload
(def fps-graph-bg-paint (doto (Paint.) (.setColor (unchecked-int (cfg 'app.config/fps-graph-bg-color)))))
(def fps-graph-line-paint (doto (Paint.) (.setColor (unchecked-int 0x44FFFFFF))))
(def fps-graph-stroke-paint
  (doto (Paint.)
    (.setMode PaintMode/STROKE)
    (.setStrokeWidth (float 1.5))
    (.setAntiAlias true)
    (.setColor (unchecked-int (cfg 'app.config/fps-graph-color)))))
;; Reusable Path object - call .reset before each use
(def fps-graph-path (Path.))

(defn draw-fps-graph
  "Draw FPS history as a line graph (ring buffer). Zero allocations."
  [^Canvas canvas x y w h]
  (let [^floats history src/fps-history
        current-idx @src/fps-history-idx
        n (alength history)
        target-fps (double (cfg 'app.config/fps-target))
        max-fps (* target-fps 1.5)
        inv-max-fps (/ 1.0 max-fps)  ;; Multiply instead of divide in loop
        x (double x)
        y (double y)
        w (double w)
        h (double h)
        bottom (+ y h)
        step (/ w (dec n))
        ^Path path fps-graph-path]
    ;; Draw background
    (.drawRect canvas (Rect/makeXYWH (float x) (float y) (float w) (float h)) fps-graph-bg-paint)
    ;; Draw target line (60 FPS reference)
    (let [target-y (- bottom (* h target-fps inv-max-fps))]
      (.drawLine canvas (float x) (float target-y) (float (+ x w)) (float target-y) fps-graph-line-paint))
    ;; Build path (reuse object, just reset)
    (.reset path)
    (loop [i (int 0)]
      (when (< i n)
        (let [ring-idx (mod (+ current-idx 1 i) n)
              fps (double (aget history ring-idx))
              clamped (if (< fps 0.0) 0.0 (if (> fps max-fps) max-fps fps))
              px (float (+ x (* i step)))
              py (float (- bottom (* h clamped inv-max-fps)))]
          (if (zero? i)
            (.moveTo path px py)
            (.lineTo path px py))
          (recur (inc i)))))
    (.drawPath canvas path fps-graph-stroke-paint)))

(defn draw-panel
  "Draw control panel with sliders at top-right."
  [^Canvas canvas window-width]
  (let [px (calc-panel-x window-width)
        py (cfg 'app.config/panel-y)
        pw (cfg 'app.config/panel-width)
        ph (cfg 'app.config/panel-height)
        pad (cfg 'app.config/panel-padding)
        font-size (or (cfg 'app.config/font-size) 18)]
    ;; Draw panel background
    (with-open [bg-paint (doto (Paint.)
                           (.setColor (unchecked-int (cfg 'app.config/panel-bg-color))))]
      (.drawRect canvas (Rect/makeXYWH (float px) (float py) (float pw) (float ph)) bg-paint))
    ;; Draw FPS text at top
    (with-open [typeface (Typeface/makeDefault)
                font (Font. typeface (float font-size))
                fps-paint (doto (Paint.)
                            (.setColor (unchecked-int (cfg 'app.config/panel-text-color))))]
      (.drawString canvas
                   (format "FPS: %.0f" (double @src/fps))
                   (float (+ px pad))
                   (float (+ py pad 14))
                   font fps-paint))
    ;; Draw FPS graph below FPS text
    (let [graph-y (+ py pad 20)
          graph-h (cfg 'app.config/fps-graph-height)
          graph-w (- pw (* 2 pad))]
      (draw-fps-graph canvas (+ px pad) graph-y graph-w graph-h))
    ;; Draw sliders
    (draw-slider canvas "X:" @src/circles-x (slider-x-bounds window-width))
    (draw-slider canvas "Y:" @src/circles-y (slider-y-bounds window-width))))

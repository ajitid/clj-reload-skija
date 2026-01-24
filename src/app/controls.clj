(ns app.controls
  "Control panel UI - sliders and drawing.
   Drawing logic for the control panel."
  (:require [app.state.sources :as src]
            [app.util :refer [cfg]])
  (:import [io.github.humbleui.skija Canvas Paint PaintMode Font Typeface]
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
        fps-offset 25]  ;; Space for FPS display
    [(+ px pad) (+ py pad fps-offset 22) sw sh]))

(defn slider-y-bounds
  "Get bounds for Y slider: [x y w h]"
  [window-width]
  (let [px (calc-panel-x window-width)
        py (cfg 'app.config/panel-y)
        pad (cfg 'app.config/panel-padding)
        sw (cfg 'app.config/slider-width)
        sh (cfg 'app.config/slider-height)
        fps-offset 25]  ;; Space for FPS display
    [(+ px pad) (+ py pad fps-offset 22 sh 30) sw sh]))

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
    ;; Draw FPS at top
    (with-open [typeface (Typeface/makeDefault)
                font (Font. typeface (float font-size))
                fps-paint (doto (Paint.)
                            (.setColor (unchecked-int (cfg 'app.config/panel-text-color))))]
      (.drawString canvas
                   (format "FPS: %.0f" (double @src/fps))
                   (float (+ px pad))
                   (float (+ py pad 14))
                   font fps-paint))
    ;; Draw sliders
    (draw-slider canvas "X:" @src/circles-x (slider-x-bounds window-width))
    (draw-slider canvas "Y:" @src/circles-y (slider-y-bounds window-width))))

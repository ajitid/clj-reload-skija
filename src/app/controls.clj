(ns app.controls
  "Control panel UI - sliders and mouse handling.
   Drawing and update logic for the control panel."
  (:require [app.state :as state])
  (:import [io.github.humbleui.jwm EventMouseButton EventMouseMove MouseButton]
           [io.github.humbleui.skija Canvas Paint PaintMode Font Typeface]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Helpers
;; ============================================================

(defn cfg
  "Get config value with runtime var lookup (survives hot-reload)."
  [var-sym]
  (some-> (resolve var-sym) deref))

(defn trigger-grid-recalc!
  "Trigger grid recalculation (calls core/recalculate-grid! via resolve to avoid circular dep)."
  []
  (when-let [recalc (resolve 'app.core/recalculate-grid!)]
    (recalc @state/window-width @state/window-height)))

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

(defn point-in-rect?
  "Check if point (px, py) is inside rect [x y w h]"
  [px py [x y w h]]
  (and (>= px x) (<= px (+ x w))
       (>= py y) (<= py (+ y h))))

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
                   (format "FPS: %.0f" (double @state/fps))
                   (float (+ px pad))
                   (float (+ py pad 14))
                   font fps-paint))
    ;; Draw sliders
    (draw-slider canvas "X:" @state/circles-x (slider-x-bounds window-width))
    (draw-slider canvas "Y:" @state/circles-y (slider-y-bounds window-width))))

;; ============================================================
;; Mouse event handling
;; ============================================================

(defn handle-mouse-press
  "Handle mouse button press - start dragging if on slider."
  [^EventMouseButton event]
  (when (= (.getButton event) MouseButton/PRIMARY)
    ;; Convert physical pixels to logical pixels
    (let [scale @state/scale
          ww @state/window-width
          mx (/ (.getX event) scale)
          my (/ (.getY event) scale)]
      (cond
        (point-in-rect? mx my (slider-x-bounds ww))
        (do
          (reset! state/dragging-slider :x)
          (reset! state/circles-x (slider-value-from-x mx (slider-x-bounds ww)))
          (trigger-grid-recalc!))

        (point-in-rect? mx my (slider-y-bounds ww))
        (do
          (reset! state/dragging-slider :y)
          (reset! state/circles-y (slider-value-from-x mx (slider-y-bounds ww)))
          (trigger-grid-recalc!))))))

(defn handle-mouse-release
  "Handle mouse button release - stop dragging."
  [^EventMouseButton event]
  (reset! state/dragging-slider nil))

(defn handle-mouse-move
  "Handle mouse move - update slider if dragging."
  [^EventMouseMove event]
  (when-let [slider @state/dragging-slider]
    ;; Convert physical pixels to logical pixels
    (let [scale @state/scale
          ww @state/window-width
          mx (/ (.getX event) scale)]
      (case slider
        :x (reset! state/circles-x (slider-value-from-x mx (slider-x-bounds ww)))
        :y (reset! state/circles-y (slider-value-from-x mx (slider-y-bounds ww))))
      (trigger-grid-recalc!))))

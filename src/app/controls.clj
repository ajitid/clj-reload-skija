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

;; ============================================================
;; Slider geometry
;; ============================================================

(defn slider-x-bounds
  "Get bounds for X slider: [x y w h]"
  []
  (let [px (cfg 'app.config/panel-x)
        py (cfg 'app.config/panel-y)
        sw (cfg 'app.config/slider-width)
        sh (cfg 'app.config/slider-height)]
    [(+ px 90) (+ py 30) sw sh]))

(defn slider-y-bounds
  "Get bounds for Y slider: [x y w h]"
  []
  (let [px (cfg 'app.config/panel-x)
        py (cfg 'app.config/panel-y)
        sw (cfg 'app.config/slider-width)
        sh (cfg 'app.config/slider-height)]
    [(+ px 90) (+ py 75) sw sh]))

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
  "Draw a slider with label and value."
  [^Canvas canvas label value [sx sy sw sh]]
  (let [min-val (cfg 'app.config/min-circles)
        max-val (cfg 'app.config/max-circles)
        ratio (/ (- value min-val) (- max-val min-val))
        fill-w (* sw ratio)
        font-size (or (cfg 'app.config/font-size) 18)]
    ;; Draw track
    (with-open [track-paint (doto (Paint.)
                              (.setColor (unchecked-int (cfg 'app.config/slider-track-color))))]
      (.drawRect canvas (Rect/makeXYWH (float sx) (float sy) (float sw) (float sh)) track-paint))
    ;; Draw fill
    (with-open [fill-paint (doto (Paint.)
                             (.setColor (unchecked-int (cfg 'app.config/slider-fill-color))))]
      (.drawRect canvas (Rect/makeXYWH (float sx) (float sy) (float fill-w) (float sh)) fill-paint))
    ;; Draw label and value
    (with-open [typeface (Typeface/makeDefault)
                font (Font. typeface (float font-size))
                text-paint (doto (Paint.)
                             (.setColor (unchecked-int (cfg 'app.config/panel-text-color))))]
      (.drawString canvas label (float (- sx 70)) (float (+ sy sh -2)) font text-paint)
      (.drawString canvas (str value) (float (+ sx sw 12)) (float (+ sy sh -2)) font text-paint))))

(defn draw-panel
  "Draw control panel with sliders."
  [^Canvas canvas]
  (let [px (cfg 'app.config/panel-x)
        py (cfg 'app.config/panel-y)
        pw (cfg 'app.config/panel-width)
        ph (cfg 'app.config/panel-height)]
    ;; Draw panel background
    (with-open [bg-paint (doto (Paint.)
                           (.setColor (unchecked-int (cfg 'app.config/panel-bg-color))))]
      (.drawRect canvas (Rect/makeXYWH (float px) (float py) (float pw) (float ph)) bg-paint))
    ;; Draw sliders
    (draw-slider canvas "X:" @state/circles-x (slider-x-bounds))
    (draw-slider canvas "Y:" @state/circles-y (slider-y-bounds))))

;; ============================================================
;; Mouse event handling
;; ============================================================

(defn handle-mouse-press
  "Handle mouse button press - start dragging if on slider."
  [^EventMouseButton event]
  (when (= (.getButton event) MouseButton/PRIMARY)
    ;; Convert physical pixels to logical pixels
    (let [scale @state/scale
          mx (/ (.getX event) scale)
          my (/ (.getY event) scale)]
      (cond
        (point-in-rect? mx my (slider-x-bounds))
        (do
          (reset! state/dragging-slider :x)
          (reset! state/circles-x (slider-value-from-x mx (slider-x-bounds))))

        (point-in-rect? mx my (slider-y-bounds))
        (do
          (reset! state/dragging-slider :y)
          (reset! state/circles-y (slider-value-from-x mx (slider-y-bounds))))))))

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
          mx (/ (.getX event) scale)]
      (case slider
        :x (reset! state/circles-x (slider-value-from-x mx (slider-x-bounds)))
        :y (reset! state/circles-y (slider-value-from-x mx (slider-y-bounds)))))))

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
  (some-> (requiring-resolve var-sym) deref))

(defn trigger-grid-recalc!
  "Trigger grid recalculation (calls core/recalculate-grid! via requiring-resolve)."
  []
  (when-let [recalc (requiring-resolve 'app.core/recalculate-grid!)]
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

(defn point-in-demo-circle?
  "Check if point (px, py) is inside the demo circle."
  [px py]
  (let [cx @state/demo-circle-x
        cy @state/demo-circle-y
        radius (or (cfg 'app.config/demo-circle-radius) 25)
        dx (- px cx)
        dy (- py cy)]
    (<= (+ (* dx dx) (* dy dy)) (* radius radius))))

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
  "Handle mouse button press - start dragging if on slider or demo circle."
  [^EventMouseButton event]
  (when (= (.getButton event) MouseButton/PRIMARY)
    ;; Convert physical pixels to logical pixels
    (let [scale @state/scale
          ww @state/window-width
          mx (/ (.getX event) scale)
          my (/ (.getY event) scale)]
      (cond
        ;; Check sliders first (higher z-order)
        (point-in-rect? mx my (slider-x-bounds ww))
        (do
          (reset! state/dragging-slider :x)
          (reset! state/circles-x (slider-value-from-x mx (slider-x-bounds ww)))
          (trigger-grid-recalc!))

        (point-in-rect? mx my (slider-y-bounds ww))
        (do
          (reset! state/dragging-slider :y)
          (reset! state/circles-y (slider-value-from-x mx (slider-y-bounds ww)))
          (trigger-grid-recalc!))

        ;; Check demo circle
        (point-in-demo-circle? mx my)
        (do
          (reset! state/demo-dragging? true)
          ;; Stop any running springs
          (reset! state/demo-spring-x nil)
          (reset! state/demo-spring-y nil)
          ;; Track mouse for velocity calculation
          (reset! state/demo-last-mouse-x mx)
          (reset! state/demo-last-mouse-y my)
          (reset! state/demo-last-mouse-time @state/game-time))))))

(defn handle-mouse-release
  "Handle mouse button release - stop dragging, create springs for demo."
  [^EventMouseButton event]
  ;; Handle slider release
  (reset! state/dragging-slider nil)

  ;; Handle demo circle release - create springs back to anchor
  (when @state/demo-dragging?
    (reset! state/demo-dragging? false)
    ;; Calculate throw velocity from last mouse movement
    (let [now @state/game-time
          dt (- now @state/demo-last-mouse-time)
          ;; Velocity in units per millisecond (only if we have a recent sample)
          vx (if (and (pos? dt) (< dt 100))  ;; only use if < 100ms ago
               (/ (- @state/demo-circle-x @state/demo-last-mouse-x) dt)
               0.0)
          vy (if (and (pos? dt) (< dt 100))
               (/ (- @state/demo-circle-y @state/demo-last-mouse-y) dt)
               0.0)
          ;; Get spring config from app.config
          stiffness (or (cfg 'app.config/demo-spring-stiffness) 180)
          damping (or (cfg 'app.config/demo-spring-damping) 12)
          mass (or (cfg 'app.config/demo-spring-mass) 1.0)]
      ;; Create X spring
      (when-let [spring-fn (requiring-resolve 'lib.spring.core/spring)]
        (reset! state/demo-spring-x
                (spring-fn {:from @state/demo-circle-x
                            :to @state/demo-anchor-x
                            :velocity vx
                            :stiffness stiffness
                            :damping damping
                            :mass mass}))
        ;; Create Y spring
        (reset! state/demo-spring-y
                (spring-fn {:from @state/demo-circle-y
                            :to @state/demo-anchor-y
                            :velocity vy
                            :stiffness stiffness
                            :damping damping
                            :mass mass}))))))

(defn handle-mouse-move
  "Handle mouse move - update slider or demo circle if dragging."
  [^EventMouseMove event]
  ;; Convert physical pixels to logical pixels
  (let [scale @state/scale
        ww @state/window-width
        mx (/ (.getX event) scale)
        my (/ (.getY event) scale)]

    ;; Handle slider dragging
    (when-let [slider @state/dragging-slider]
      (case slider
        :x (reset! state/circles-x (slider-value-from-x mx (slider-x-bounds ww)))
        :y (reset! state/circles-y (slider-value-from-x mx (slider-y-bounds ww))))
      (trigger-grid-recalc!))

    ;; Handle demo circle dragging
    (when @state/demo-dragging?
      ;; Store previous position for velocity calculation
      (reset! state/demo-last-mouse-x @state/demo-circle-x)
      (reset! state/demo-last-mouse-y @state/demo-circle-y)
      (reset! state/demo-last-mouse-time @state/game-time)
      ;; Move circle to mouse position
      (reset! state/demo-circle-x mx)
      (reset! state/demo-circle-y my))))

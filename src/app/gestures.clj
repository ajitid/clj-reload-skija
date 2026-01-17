(ns app.gestures
  "App-specific gesture target registrations.
   Registers sliders and demo circle with the gesture system."
  (:require [app.state :as state]))

;; Note: We use requiring-resolve for cross-namespace calls to survive hot-reload
;; Actual registration happens in init, handlers are looked up at runtime

(defn cfg
  "Get config value with runtime var lookup."
  [var-sym]
  (some-> (requiring-resolve var-sym) deref))

;; -----------------------------------------------------------------------------
;; Bounds Functions (return [x y w h] given context)
;; -----------------------------------------------------------------------------

(defn slider-x-bounds-fn
  "Bounds function for X slider."
  [ctx]
  (when-let [bounds-fn (requiring-resolve 'app.controls/slider-x-bounds)]
    (bounds-fn (:window-width ctx))))

(defn slider-y-bounds-fn
  "Bounds function for Y slider."
  [ctx]
  (when-let [bounds-fn (requiring-resolve 'app.controls/slider-y-bounds)]
    (bounds-fn (:window-width ctx))))

(defn demo-circle-bounds-fn
  "Bounds function for demo circle (as rectangle containing circle)."
  [_ctx]
  (let [cx @state/demo-circle-x
        cy @state/demo-circle-y
        r (or (cfg 'app.config/demo-circle-radius) 25)]
    [(- cx r) (- cy r) (* 2 r) (* 2 r)]))

;; -----------------------------------------------------------------------------
;; Gesture Handlers
;; -----------------------------------------------------------------------------

(defn on-slider-x-drag-start
  "Handler for X slider drag start."
  [event]
  (let [mx (get-in event [:pointer :x])
        ww @state/window-width]
    (reset! state/dragging-slider :x)
    (when-let [val-fn (requiring-resolve 'app.controls/slider-value-from-x)]
      (when-let [bounds-fn (requiring-resolve 'app.controls/slider-x-bounds)]
        (reset! state/circles-x (val-fn mx (bounds-fn ww)))))
    (when-let [recalc (requiring-resolve 'app.controls/trigger-grid-recalc!)]
      (recalc))))

(defn on-slider-x-drag
  "Handler for X slider drag."
  [event]
  (let [mx (get-in event [:pointer :x])
        ww @state/window-width]
    (when-let [val-fn (requiring-resolve 'app.controls/slider-value-from-x)]
      (when-let [bounds-fn (requiring-resolve 'app.controls/slider-x-bounds)]
        (reset! state/circles-x (val-fn mx (bounds-fn ww)))))
    (when-let [recalc (requiring-resolve 'app.controls/trigger-grid-recalc!)]
      (recalc))))

(defn on-slider-x-drag-end
  "Handler for X slider drag end."
  [_event]
  (reset! state/dragging-slider nil))

(defn on-slider-y-drag-start
  "Handler for Y slider drag start."
  [event]
  (let [mx (get-in event [:pointer :x])
        ww @state/window-width]
    (reset! state/dragging-slider :y)
    (when-let [val-fn (requiring-resolve 'app.controls/slider-value-from-x)]
      (when-let [bounds-fn (requiring-resolve 'app.controls/slider-y-bounds)]
        (reset! state/circles-y (val-fn mx (bounds-fn ww)))))
    (when-let [recalc (requiring-resolve 'app.controls/trigger-grid-recalc!)]
      (recalc))))

(defn on-slider-y-drag
  "Handler for Y slider drag."
  [event]
  (let [mx (get-in event [:pointer :x])
        ww @state/window-width]
    (when-let [val-fn (requiring-resolve 'app.controls/slider-value-from-x)]
      (when-let [bounds-fn (requiring-resolve 'app.controls/slider-y-bounds)]
        (reset! state/circles-y (val-fn mx (bounds-fn ww)))))
    (when-let [recalc (requiring-resolve 'app.controls/trigger-grid-recalc!)]
      (recalc))))

(defn on-slider-y-drag-end
  "Handler for Y slider drag end."
  [_event]
  (reset! state/dragging-slider nil))

(defn on-demo-circle-drag-start
  "Handler for demo circle drag start."
  [event]
  (let [mx (get-in event [:pointer :x])]
    (reset! state/demo-dragging? true)
    ;; Stop any running decay
    (reset! state/demo-decay-x nil)
    ;; Store click offset (so ball doesn't jump)
    (reset! state/demo-drag-offset-x (- @state/demo-circle-x mx))
    ;; Initialize position history for velocity tracking
    (reset! state/demo-position-history
            [{:x @state/demo-circle-x :t @state/game-time}])
    ;; Reset velocity
    (reset! state/demo-velocity-x 0.0)))

(defn on-demo-circle-drag
  "Handler for demo circle drag."
  [event]
  (let [mx (get-in event [:pointer :x])]
    ;; Move circle with offset (no jump!) - velocity calculated in tick
    (reset! state/demo-circle-x (+ mx @state/demo-drag-offset-x))))

(defn on-demo-circle-drag-end
  "Handler for demo circle drag end."
  [_event]
  (reset! state/demo-dragging? false)
  ;; Create decay animation with current velocity
  (when-let [decay-fn (requiring-resolve 'lib.anim.decay/decay)]
    (reset! state/demo-decay-x
            (decay-fn {:from @state/demo-circle-x
                       :velocity @state/demo-velocity-x
                       :rate :normal}))))

;; -----------------------------------------------------------------------------
;; Registration
;; -----------------------------------------------------------------------------

(defn register-gestures!
  "Register all app gesture targets. Called from init."
  []
  (when-let [register! (requiring-resolve 'lib.gesture.api/register-target!)]
    ;; Clear existing targets first (for hot-reload)
    (when-let [clear! (requiring-resolve 'lib.gesture.api/clear-targets!)]
      (clear!))

    ;; X Slider - only active when panel visible
    (register!
     {:id :slider-x
      :layer :overlay
      :z-index 10
      :bounds-fn (fn [ctx]
                   (when @state/panel-visible?
                     (slider-x-bounds-fn ctx)))
      :gesture-recognizers [:drag]
      :handlers {:on-drag-start on-slider-x-drag-start
                 :on-drag on-slider-x-drag
                 :on-drag-end on-slider-x-drag-end}})

    ;; Y Slider - only active when panel visible
    (register!
     {:id :slider-y
      :layer :overlay
      :z-index 10
      :bounds-fn (fn [ctx]
                   (when @state/panel-visible?
                     (slider-y-bounds-fn ctx)))
      :gesture-recognizers [:drag]
      :handlers {:on-drag-start on-slider-y-drag-start
                 :on-drag on-slider-y-drag
                 :on-drag-end on-slider-y-drag-end}})

    ;; Demo Circle
    (register!
     {:id :demo-circle
      :layer :content
      :z-index 10
      :bounds-fn demo-circle-bounds-fn
      :gesture-recognizers [:drag]
      :handlers {:on-drag-start on-demo-circle-drag-start
                 :on-drag on-demo-circle-drag
                 :on-drag-end on-demo-circle-drag-end}})))

(ns app.gestures
  "App-specific gesture target registrations.
   Registers sliders and demo circle with the gesture system."
  (:require [app.state.sources :as src]
            [app.state.animations :as anim]
            [app.state.system :as sys]
            [app.util :refer [cfg]]))

;; Note: We use requiring-resolve for cross-namespace calls to survive hot-reload

;; -----------------------------------------------------------------------------
;; Slider (generic)
;; -----------------------------------------------------------------------------

(defn make-slider-bounds-fn
  "Create bounds function for a slider given its bounds-fn symbol."
  [bounds-sym]
  (fn [ctx]
    (when @src/panel-visible?
      (when-let [bounds-fn (requiring-resolve bounds-sym)]
        (bounds-fn (:window-width ctx))))))

(defn- update-slider!
  "Update slider value from pointer x position.
   value-source is a Flex source (callable with new value)."
  [value-source bounds-sym mx]
  (let [ww @src/window-width]
    (when-let [val-fn (requiring-resolve 'app.controls/slider-value-from-x)]
      (when-let [bounds-fn (requiring-resolve bounds-sym)]
        (value-source (val-fn mx (bounds-fn ww)))))))

(defn make-slider-handlers
  "Create drag/tap handlers for a slider.
   Returns map of :on-drag-start :on-drag :on-drag-end :on-tap"
  [slider-key value-source bounds-sym]
  (let [update! (fn [event]
                  (update-slider! value-source bounds-sym (get-in event [:pointer :x])))]
    {:on-drag-start (fn [event]
                      (src/dragging-slider slider-key)
                      (update! event))
     :on-drag       update!
     :on-drag-end   (fn [_] (src/dragging-slider nil))
     :on-tap        update!}))

;; -----------------------------------------------------------------------------
;; Demo Circle
;; -----------------------------------------------------------------------------

(defn demo-circle-bounds-fn
  "Bounds function for demo circle (as rectangle containing circle)."
  [_ctx]
  (let [cx @anim/demo-circle-x
        cy @anim/demo-circle-y
        r (or (cfg 'app.config/demo-circle-radius) 25)]
    [(- cx r) (- cy r) (* 2 r) (* 2 r)]))

(def demo-circle-handlers
  {:on-drag-start
   (fn [event]
     (let [mx (get-in event [:pointer :x])]
       (src/demo-dragging? true)
       ;; Cancel any running decay animation
       (when-let [cancel! (requiring-resolve 'lib.anim.registry/cancel!)]
         (cancel! :demo-circle-x))
       (reset! anim/demo-drag-offset-x (- @anim/demo-circle-x mx))
       (reset! anim/demo-position-history
               [{:x @anim/demo-circle-x :t @sys/game-time}])
       (reset! anim/demo-velocity-x 0.0)))

   :on-drag
   (fn [event]
     (let [mx (get-in event [:pointer :x])]
       (reset! anim/demo-circle-x (+ mx @anim/demo-drag-offset-x))))

   :on-drag-end
   (fn [_]
     (src/demo-dragging? false)
     ;; Use animation registry for decay
     (when-let [decay-fn (requiring-resolve 'lib.anim.decay/decay)]
       (when-let [animate! (requiring-resolve 'lib.anim.registry/animate!)]
         (animate! :demo-circle-x
                   (decay-fn {:from @anim/demo-circle-x
                              :velocity @anim/demo-velocity-x
                              :rate :normal})
                   {:target anim/demo-circle-x}))))})

;; -----------------------------------------------------------------------------
;; Registration
;; -----------------------------------------------------------------------------

(defn register-gestures!
  "Register all app gesture targets. Called from init."
  []
  (when-let [register! (requiring-resolve 'lib.gesture.api/register-target!)]
    (when-let [clear! (requiring-resolve 'lib.gesture.api/clear-targets!)]
      (clear!))

    ;; Sliders
    (doseq [[id value-atom bounds-sym]
            [[:slider-x src/circles-x 'app.controls/slider-x-bounds]
             [:slider-y src/circles-y 'app.controls/slider-y-bounds]]]
      (register!
       {:id id
        :layer :overlay
        :z-index 10
        :bounds-fn (make-slider-bounds-fn bounds-sym)
        :gesture-recognizers [:drag :tap]
        :handlers (make-slider-handlers id value-atom bounds-sym)}))

    ;; Demo Circle
    (register!
     {:id :demo-circle
      :layer :content
      :z-index 10
      :bounds-fn demo-circle-bounds-fn
      :gesture-recognizers [:drag]
      :handlers demo-circle-handlers})))

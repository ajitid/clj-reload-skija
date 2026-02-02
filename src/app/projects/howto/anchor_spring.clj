(ns app.projects.howto.anchor-spring
  "Anchor spring demo - drag a ball, release to spring back to center.

   Demonstrates:
   - 2D spring physics animation (lib.anim.spring-2d)
   - Animation registry (lib.anim.registry)
   - Velocity tracking from drag for natural momentum
   - Vec2 usage for position/velocity state
   - Gesture handling (drag recognizer)"
  (:require [lib.color.core :as color]
            [lib.color.open-color :as oc]
            [app.state.system :as sys]
            [lib.flex.core :as flex]
            [lib.graphics.shapes :as shapes]
            [lib.anim.spring-2d :as spring-2d]
            [lib.text.core :as text]
            [fastmath.vector :as v])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def circle-radius 25)
(def circle-color oc/blue-6)
(def anchor-color (color/with-alpha color/white 0.27))
(def line-color (color/with-alpha color/white 0.2))

;; ============================================================
;; State (persists across hot-reloads)
;; ============================================================

;; Ball position (Vec2)
(defonce circle-pos (atom (v/vec2 400.0 300.0)))

;; Anchor position (window center, Vec2)
(defonce anchor-pos (atom (v/vec2 400.0 300.0)))

;; Drag state
(flex/defsource dragging? false)
(defonce drag-offset (atom (v/vec2 0.0 0.0)))

;; Velocity tracking (Vec2)
(defonce velocity (atom (v/vec2 0.0 0.0)))
(defonce position-history (atom []))

;; Spring ref for debug display (stored on drag-end, queryable after registry removes it)
(defonce spring-ref (atom nil))

;; ============================================================
;; Gesture handlers
;; ============================================================

(defn circle-bounds-fn [_ctx]
  (let [[cx cy] @circle-pos
        r circle-radius]
    [(- cx r) (- cy r) (* 2 r) (* 2 r)]))

(def circle-handlers
  {:on-drag-start
   (fn [event]
     (let [mx (get-in event [:pointer :x])
           my (get-in event [:pointer :y])
           pos @circle-pos
           [px py] pos]
       (dragging? true)
       ;; Cancel any running spring animation
       (when-let [cancel! (requiring-resolve 'lib.anim.registry/cancel!)]
         (cancel! :anchor-spring))
       ;; Clear spring ref for debug display
       (reset! spring-ref nil)
       ;; Record offset so ball doesn't jump to cursor
       (reset! drag-offset (v/vec2 (- px mx) (- py my)))
       ;; Initialize position history for velocity tracking
       (reset! position-history
               [{:pos pos :t @sys/game-time}])
       (reset! velocity (v/vec2 0.0 0.0))))

   :on-drag
   (fn [event]
     (let [mx (get-in event [:pointer :x])
           my (get-in event [:pointer :y])
           [ox oy] @drag-offset]
       (reset! circle-pos (v/vec2 (+ mx ox) (+ my oy)))))

   :on-drag-end
   (fn [_]
     (dragging? false)
     ;; Launch 2D spring animation back to anchor with tracked velocity
     (when-let [animate! (requiring-resolve 'lib.anim.registry/animate!)]
       (let [pos @circle-pos
             anchor @anchor-pos
             vel @velocity
             s (spring-2d/spring-2d {:from pos
                                      :to anchor
                                      :velocity vel
                                      :stiffness 180 :damping 12})]
         ;; Store spring ref for debug display
         (reset! spring-ref s)
         (animate! :anchor-spring s {:target circle-pos}))))})

(defn register-gestures! []
  (when-let [register! (requiring-resolve 'lib.gesture.api/register-target!)]
    (when-let [clear! (requiring-resolve 'lib.gesture.api/clear-targets!)]
      (clear!))
    (register!
     {:id :anchor-ball
      :layer :content
      :z-index 10
      :bounds-fn circle-bounds-fn
      :gesture-recognizers [:drag]
      :handlers circle-handlers})))

;; ============================================================
;; Drawing
;; ============================================================

(defn draw-anchor [^Canvas canvas]
  (let [[ax ay] @anchor-pos]
    (shapes/circle canvas ax ay circle-radius
                   {:color anchor-color
                    :mode :stroke
                    :stroke-width 2.0})))

(defn draw-connection-line [^Canvas canvas]
  (let [pos @circle-pos
        anchor @anchor-pos
        [cx cy] pos
        [ax ay] anchor
        diff (v/sub pos anchor)
        dist (v/mag diff)]
    ;; Only draw line when ball is away from anchor
    (when (> dist 2.0)
      (shapes/line canvas ax ay cx cy
                   {:color line-color
                    :stroke-width 1.5}))))

(defn draw-ball [^Canvas canvas]
  (let [[cx cy] @circle-pos]
    (shapes/circle canvas cx cy circle-radius
                   {:color circle-color})))

(defn draw-debug [^Canvas canvas width height]
  (let [s @spring-ref
        font-size 13
        line-h 18
        pad 12
        base-y (- height pad)]
    (if s
      (let [state (spring-2d/spring-2d-now s)
            anchor @anchor-pos
            ;; state has :value as Vec2, :velocity as Vec2
            pos (:value state)
            vel (:velocity state)
            diff (v/sub pos anchor)
            dist (v/mag diff)
            speed (v/mag vel)
            line (format "at-rest=%-5s  speed=%7.1f  dist=%6.1f  phase=%s"
                         (str (:at-rest? state))
                         (double speed)
                         (double dist)
                         (name (:phase state)))]
        (text/text canvas line pad base-y
                   {:size font-size :color (color/with-alpha color/white 0.6) :features "tnum"}))
      ;; No spring active
      (text/text canvas "No spring active (drag and release ball)"
                 pad base-y
                 {:size font-size :color (color/with-alpha color/white 0.33)}))))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  "Called once when example starts."
  (println "Anchor spring demo loaded!")
  ;; Register gesture targets
  (register-gestures!))

(defn tick [dt]
  "Called every frame with delta time."
  ;; Track velocity during drag using position history
  (when @dragging?
    (let [history @position-history
          current-pos @circle-pos
          current-t @sys/game-time
          new-history (-> history
                          (conj {:pos current-pos :t current-t})
                          (->> (take-last 3))
                          vec)]
      (reset! position-history new-history)
      (when (>= (count new-history) 2)
        (let [oldest (first new-history)
              newest (last new-history)
              dt-hist (- (:t newest) (:t oldest))]
          (when (pos? dt-hist)
            (let [diff (v/sub (:pos newest) (:pos oldest))]
              (reset! velocity (v/div diff dt-hist)))))))))

(defn draw [^Canvas canvas width height]
  "Called every frame for rendering."
  ;; Update anchor to window center
  (reset! anchor-pos (v/vec2 (/ width 2.0) (/ height 2.0)))
  ;; Draw
  (draw-anchor canvas)
  (draw-connection-line canvas)
  (draw-ball canvas)
  ;; Debug: show perceptual vs actual rest state
  (draw-debug canvas width height))

(defn cleanup []
  "Called when switching away from this example."
  (println "Anchor spring demo cleanup")
  ;; Cancel any running spring animation
  (when-let [cancel! (requiring-resolve 'lib.anim.registry/cancel!)]
    (cancel! :anchor-spring)))

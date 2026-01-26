(ns app.projects.howto.anchor-spring
  "Anchor spring demo - drag a ball, release to spring back to center.

   Demonstrates:
   - Spring physics animation (lib.anim.spring)
   - Animation registry (lib.anim.registry)
   - Velocity tracking from drag for natural momentum
   - Gesture handling (drag recognizer)"
  (:require [app.state.system :as sys]
            [lib.flex.core :as flex]
            [lib.graphics.shapes :as shapes])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def circle-radius 25)
(def circle-color 0xFF4A90D9)
(def anchor-color 0x44FFFFFF)
(def line-color 0x33FFFFFF)

;; ============================================================
;; State (persists across hot-reloads)
;; ============================================================

;; Ball position
(defonce circle-x (atom 400.0))
(defonce circle-y (atom 300.0))

;; Anchor position (window center)
(defonce anchor-x (atom 400.0))
(defonce anchor-y (atom 300.0))

;; Drag state
(flex/defsource dragging? false)
(defonce drag-offset-x (atom 0.0))
(defonce drag-offset-y (atom 0.0))

;; Velocity tracking
(defonce velocity-x (atom 0.0))
(defonce velocity-y (atom 0.0))
(defonce position-history (atom []))

;; ============================================================
;; Gesture handlers
;; ============================================================

(defn circle-bounds-fn [_ctx]
  (let [cx @circle-x
        cy @circle-y
        r circle-radius]
    [(- cx r) (- cy r) (* 2 r) (* 2 r)]))

(def circle-handlers
  {:on-drag-start
   (fn [event]
     (let [mx (get-in event [:pointer :x])
           my (get-in event [:pointer :y])]
       (dragging? true)
       ;; Cancel any running spring animations
       (when-let [cancel! (requiring-resolve 'lib.anim.registry/cancel!)]
         (cancel! :anchor-spring-x)
         (cancel! :anchor-spring-y))
       ;; Record offset so ball doesn't jump to cursor
       (reset! drag-offset-x (- @circle-x mx))
       (reset! drag-offset-y (- @circle-y my))
       ;; Initialize position history for velocity tracking
       (reset! position-history
               [{:x @circle-x :y @circle-y :t @sys/game-time}])
       (reset! velocity-x 0.0)
       (reset! velocity-y 0.0)))

   :on-drag
   (fn [event]
     (let [mx (get-in event [:pointer :x])
           my (get-in event [:pointer :y])]
       (reset! circle-x (+ mx @drag-offset-x))
       (reset! circle-y (+ my @drag-offset-y))))

   :on-drag-end
   (fn [_]
     (dragging? false)
     ;; Launch spring animations back to anchor with tracked velocity
     (when-let [spring-fn (requiring-resolve 'lib.anim.spring/spring)]
       (when-let [animate! (requiring-resolve 'lib.anim.registry/animate!)]
         (let [cx @circle-x
               cy @circle-y
               ax @anchor-x
               ay @anchor-y
               vx @velocity-x
               vy @velocity-y]
           (animate! :anchor-spring-x
                     (spring-fn {:from cx :to ax
                                 :velocity vx
                                 :stiffness 180 :damping 12})
                     {:target circle-x})
           (animate! :anchor-spring-y
                     (spring-fn {:from cy :to ay
                                 :velocity vy
                                 :stiffness 180 :damping 12})
                     {:target circle-y})))))})

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
  (let [ax @anchor-x
        ay @anchor-y]
    (shapes/circle canvas ax ay circle-radius
                   {:color anchor-color
                    :mode :stroke
                    :stroke-width 2.0})))

(defn draw-connection-line [^Canvas canvas]
  (let [cx @circle-x
        cy @circle-y
        ax @anchor-x
        ay @anchor-y
        dx (- cx ax)
        dy (- cy ay)
        dist (Math/sqrt (+ (* dx dx) (* dy dy)))]
    ;; Only draw line when ball is away from anchor
    (when (> dist 2.0)
      (shapes/line canvas ax ay cx cy
                   {:color line-color
                    :stroke-width 1.5}))))

(defn draw-ball [^Canvas canvas]
  (let [cx @circle-x
        cy @circle-y]
    (shapes/circle canvas cx cy circle-radius
                   {:color circle-color})))

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
          current-x @circle-x
          current-y @circle-y
          current-t @sys/game-time
          new-history (-> history
                          (conj {:x current-x :y current-y :t current-t})
                          (->> (take-last 3))
                          vec)]
      (reset! position-history new-history)
      (when (>= (count new-history) 2)
        (let [oldest (first new-history)
              newest (last new-history)
              dt-hist (- (:t newest) (:t oldest))]
          (when (pos? dt-hist)
            (reset! velocity-x (/ (- (:x newest) (:x oldest)) dt-hist))
            (reset! velocity-y (/ (- (:y newest) (:y oldest)) dt-hist))))))))

(defn draw [^Canvas canvas width height]
  "Called every frame for rendering."
  ;; Update anchor to window center
  (reset! anchor-x (/ width 2.0))
  (reset! anchor-y (/ height 2.0))
  ;; Draw
  (draw-anchor canvas)
  (draw-connection-line canvas)
  (draw-ball canvas))

(defn cleanup []
  "Called when switching away from this example."
  (println "Anchor spring demo cleanup")
  ;; Cancel any running spring animations
  (when-let [cancel! (requiring-resolve 'lib.anim.registry/cancel!)]
    (cancel! :anchor-spring-x)
    (cancel! :anchor-spring-y)))

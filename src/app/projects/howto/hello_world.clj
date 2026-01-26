(ns app.projects.howto.hello-world
  "Hello World - Minimal template with animation and interaction.

   Demonstrates:
   - Sine wave animation using game-time
   - Draggable circle with gesture handling
   - Basic Skija drawing

   Use this as a starting point for new examples."
  (:require [app.state.system :as sys]
            [lib.graphics.shapes :as shapes])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def circle-count 3)
(def circle-radius 20)
(def orbit-radius 150)
(def animation-speed 0.3)  ;; cycles per second

;; Trail
(def trail-max-length 30)
(def trail-base-radius 40)  ;; base size for trail circles
(def trail-color 0xFFFF69B4)

;; Colors
(def bg-circle-color 0xFF4A90D9)
(def draggable-color 0xFFFF69B4)
(def draggable-stroke-color 0xFFFFFFFF)

;; ============================================================
;; State (persists across hot-reloads)
;; ============================================================

;; Draggable circle position
(defonce circle-x (atom 400.0))
(defonce circle-y (atom 300.0))

;; Interaction state
(defonce pressed? (atom false))  ;; true immediately on pointer down
(defonce dragging? (atom false)) ;; true when actually moving
(defonce drag-offset-x (atom 0.0))
(defonce drag-offset-y (atom 0.0))

;; Trail - stores past positions as PersistentQueue of [x y] pairs
(defonce trail (atom clojure.lang.PersistentQueue/EMPTY))

;; ============================================================
;; Gesture Handlers
;; ============================================================

(def draggable-radius 25)

(defn circle-bounds-fn [_ctx]
  (let [cx @circle-x
        cy @circle-y
        r draggable-radius]
    [(- cx r) (- cy r) (* 2 r) (* 2 r)]))

(def circle-handlers
  {:on-pointer-down
   (fn [event]
     ;; Immediate visual feedback
     (reset! pressed? true)
     ;; Store offset for drag
     (let [mx (get-in event [:pointer :x])
           my (get-in event [:pointer :y])]
       (reset! drag-offset-x (- @circle-x mx))
       (reset! drag-offset-y (- @circle-y my))))

   :on-pointer-up
   (fn [_]
     (reset! pressed? false)
     (reset! dragging? false))

   :on-drag-start
   (fn [_]
     (reset! dragging? true))

   :on-drag
   (fn [event]
     (let [mx (get-in event [:pointer :x])
           my (get-in event [:pointer :y])]
       (reset! circle-x (+ mx @drag-offset-x))
       (reset! circle-y (+ my @drag-offset-y))))

   :on-drag-end
   (fn [_]
     (reset! dragging? false))})

(defn register-gestures! []
  (when-let [register! (requiring-resolve 'lib.gesture.api/register-target!)]
    (when-let [clear! (requiring-resolve 'lib.gesture.api/clear-targets!)]
      (clear!))

    (register!
      {:id :draggable-circle
       :layer :content
       :z-index 10
       :bounds-fn circle-bounds-fn
       :gesture-recognizers [:drag]
       :handlers circle-handlers})))

;; ============================================================
;; Trail
;; ============================================================

(defn update-trail! []
  "Add current position to trail, remove oldest if over limit."
  (let [x @circle-x
        y @circle-y]
    (swap! trail (fn [q]
                   (let [q' (conj q [x y])]
                     (if (> (count q') trail-max-length)
                       (pop q')
                       q'))))))

(defn draw-trail [^Canvas canvas]
  "Draw fading trail of past positions."
  (let [positions (vec @trail)
        len (count positions)]
    (dotimes [i len]
      (let [[x y] (nth positions i)
            ;; Fade alpha from 0 to 255 based on position in trail
            progress (/ (double i) (max 1 (dec len)))
            alpha (int (* 180 progress))
            ;; Shrink radius for older positions
            radius (* trail-base-radius 0.5 progress)
            color (bit-or (bit-and trail-color 0x00FFFFFF)
                          (bit-shift-left alpha 24))]
        (when (> radius 2)
          (shapes/circle canvas x y radius {:color color}))))))

;; ============================================================
;; Drawing
;; ============================================================

(defn draw-orbiting-circles
  "Draw circles orbiting around the draggable center using sine waves."
  [^Canvas canvas cx cy time]
  (let [phase-offset (/ (* 2 Math/PI) circle-count)]
    (doseq [i (range circle-count)]
      (let [;; Base angle for this circle
            base-angle (* i phase-offset)
            ;; Animate with time - each circle has offset phase
            angle (+ base-angle (* time animation-speed 2 Math/PI))
            ;; Sine wave modulates the radius
            wave-offset (* 30 (Math/sin (* time 3 animation-speed)))
            effective-radius (+ orbit-radius wave-offset)
            ;; Calculate position
            x (+ cx (* effective-radius (Math/cos angle)))
            y (+ cy (* effective-radius (Math/sin angle)))
            ;; Pulse size with sine wave
            size-wave (+ 1.0 (* 0.3 (Math/sin (+ (* time 4) (* i 0.5)))))
            radius (* circle-radius size-wave)
            ;; Vary alpha based on position
            alpha (int (+ 180 (* 75 (Math/sin (+ angle (* time 2))))))]
        (shapes/circle canvas x y radius
                       {:color (bit-or (bit-and bg-circle-color 0x00FFFFFF)
                                       (bit-shift-left alpha 24))})))))

(defn draw-draggable-circle
  "Draw the central draggable circle."
  [^Canvas canvas]
  (let [x @circle-x
        y @circle-y
        ;; Pulse when pressed (immediate feedback)
        scale (if @pressed? 1.2 1.0)
        r (* draggable-radius scale)]
    ;; Outer stroke
    (shapes/circle canvas x y (+ r 2)
                   {:color draggable-stroke-color
                    :mode :stroke
                    :stroke-width 3.0})
    ;; Fill
    (shapes/circle canvas x y r
                   {:color draggable-color})))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  "Called once when example starts."
  (println "Hello World loaded! Drag the pink circle.")
  (register-gestures!))

(defn tick [_dt]
  "Called every frame with delta time."
  (update-trail!))

(defn draw [^Canvas canvas width height]
  "Called every frame for rendering."
  ;; Center the draggable circle on first draw if not moved
  (when (and (= @circle-x 400.0) (= @circle-y 300.0))
    (reset! circle-x (/ width 2))
    (reset! circle-y (/ height 2)))

  ;; Get current time for animation
  (let [time @sys/game-time]
    ;; Draw trail first (behind everything)
    (draw-trail canvas)
    ;; Draw orbiting circles around the draggable center
    (draw-orbiting-circles canvas @circle-x @circle-y time)
    ;; Draw the draggable circle
    (draw-draggable-circle canvas)))

(defn cleanup []
  "Called when switching away from this example."
  (reset! trail clojure.lang.PersistentQueue/EMPTY)
  (println "Hello World cleanup"))

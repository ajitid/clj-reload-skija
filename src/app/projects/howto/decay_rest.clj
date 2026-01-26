(ns app.projects.howto.decay-rest
  "Decay rest comparison — perceptual vs physics rest.

   Two balls launched with the same velocity:
   - Orange stops at perceptual rest (library behavior: clamps time to perceptual-dur)
   - Blue stops at actual physics rest (raw exponential decay until velocity < 0.5)

   Demonstrates why perceptual rest can freeze a ball prematurely."
  (:require [lib.graphics.shapes :as shapes]
            [lib.text.core :as text]
            [lib.anim.decay :as decay]
            [lib.time :as time])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def ball-radius 20)
(def orange-color 0xFFE8943A)
(def blue-color 0xFF4A90D9)
(def button-color 0xFF3A3A4A)
(def button-text-color 0xFFFFFFFF)
(def label-color 0x99FFFFFF)
(def decay-rate 0.998)
(def velocity-threshold 0.5)

;; Button geometry
(def button-x 30)
(def button-y 30)
(def button-w 120)
(def button-h 44)
(def button-r 10)

;; ============================================================
;; State (persists across hot-reloads)
;; ============================================================

(defonce orange-x (atom 100.0))
(defonce blue-x (atom 100.0))
(defonce start-x (atom 100.0))
(defonce initial-velocity (atom 0.0))

;; Orange ball: uses library decay
(defonce decay-ref (atom nil))
(defonce orange-stopped? (atom true))
(defonce orange-vel (atom 0.0))

;; Blue ball: manual physics
(defonce launch-time (atom 0.0))
(defonce blue-stopped? (atom true))
(defonce blue-vel (atom 0.0))

;; Track if we've ever launched
(defonce launched? (atom false))

;; ============================================================
;; Launch
;; ============================================================

(defn launch! []
  (let [v0 (+ 800.0 (rand-int 2200))  ;; 800–3000 px/s
        sx @start-x
        now (time/now)]
    (reset! initial-velocity v0)
    (reset! launched? true)

    ;; Reset orange ball — library decay
    (reset! orange-x sx)
    (reset! orange-stopped? false)
    (reset! orange-vel v0)
    (reset! decay-ref (decay/decay {:from sx :velocity v0 :rate decay-rate}))

    ;; Reset blue ball — manual physics
    (reset! blue-x sx)
    (reset! blue-stopped? false)
    (reset! blue-vel v0)
    (reset! launch-time now)))

;; ============================================================
;; Gesture Handlers
;; ============================================================

(defn button-bounds-fn [_ctx]
  [button-x button-y button-w button-h])

(def button-handlers
  {:on-tap (fn [_event] (launch!))})

(defn register-gestures! []
  (when-let [register! (requiring-resolve 'lib.gesture.api/register-target!)]
    (when-let [clear! (requiring-resolve 'lib.gesture.api/clear-targets!)]
      (clear!))
    (register!
     {:id :decay-launch-button
      :layer :content
      :z-index 10
      :bounds-fn button-bounds-fn
      :gesture-recognizers [:tap]
      :handlers button-handlers})))

;; ============================================================
;; Drawing
;; ============================================================

(defn draw-button [^Canvas canvas]
  (shapes/rounded-rect canvas button-x button-y button-w button-h button-r
                       {:color button-color})
  (text/text canvas "Launch"
             (+ button-x (/ button-w 2.0))
             (+ button-y 28.0)
             {:size 16 :weight :medium :color button-text-color :align :center}))

(defn draw-velocity-label [^Canvas canvas]
  (when @launched?
    (text/text canvas (format "v\u2080 = %.0f px/s" (double @initial-velocity))
               (+ button-x button-w 20) (+ button-y 28.0)
               {:size 14 :color label-color :features "tnum"})))

(defn draw-start-line [^Canvas canvas y-top y-bottom]
  (let [sx @start-x]
    (shapes/line canvas sx y-top sx y-bottom
                 {:color 0x33FFFFFF :stroke-width 1.0 :dash [6 4]})))

(defn draw-ball-row [^Canvas canvas x y color label stopped?]
  ;; Ball
  (shapes/circle canvas x y ball-radius {:color color})
  ;; Label below ball row
  (text/text canvas label @start-x (+ y ball-radius 18)
             {:size 12 :color label-color})
  ;; Stop marker
  (when stopped?
    (shapes/line canvas x (- y ball-radius 4) x (+ y ball-radius 4)
                 {:color 0x66FFFFFF :stroke-width 2.0})))

(defn draw-debug [^Canvas canvas height]
  (let [pad 12
        font-size 13
        line-h 18
        base-y (- height pad)]
    (text/text canvas
               (format "Orange: vel=%8.1f  at-rest=%-5s  (perceptual)"
                       (double @orange-vel) (str @orange-stopped?))
               pad (- base-y line-h)
               {:size font-size :color 0x99FFFFFF :features "tnum"})
    (text/text canvas
               (format "Blue:   vel=%8.1f  at-rest=%-5s  (physics)"
                       (double @blue-vel) (str @blue-stopped?))
               pad base-y
               {:size font-size :color 0x99FFFFFF :features "tnum"})))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Decay rest comparison loaded! Click Launch to compare.")
  (register-gestures!))

(defn tick [_dt]
  ;; Orange ball — library decay
  (when (and @decay-ref (not @orange-stopped?))
    (let [state (decay/decay-now @decay-ref)
          v (:value state)
          vel (:velocity state)]
      (reset! orange-x v)
      (reset! orange-vel vel)
      (when (:at-rest? state)
        (reset! orange-stopped? true))))

  ;; Blue ball — manual exponential decay (no perceptual-dur clamping)
  (when (and @launched? (not @blue-stopped?))
    (let [elapsed (- (time/now) @launch-time)
          v0 (double @initial-velocity)
          k (* (- 1.0 decay-rate) 1000.0)
          e (Math/exp (- (* k elapsed)))
          pos (+ (double @start-x) (* (/ v0 k) (- 1.0 e)))
          vel (* v0 e)]
      (reset! blue-x pos)
      (reset! blue-vel vel)
      (when (<= (Math/abs vel) velocity-threshold)
        (reset! blue-stopped? true)
        (reset! blue-vel 0.0)))))

(defn draw [^Canvas canvas width height]
  ;; Update start-x to a reasonable left margin
  (reset! start-x 100.0)

  (let [y-orange (- (/ height 2.0) 60.0)
        y-blue   (+ (/ height 2.0) 60.0)]

    ;; Start line
    (draw-start-line canvas (- y-orange 40) (+ y-blue 40))

    ;; Button and velocity label
    (draw-button canvas)
    (draw-velocity-label canvas)

    ;; Orange ball (perceptual rest)
    (draw-ball-row canvas @orange-x y-orange orange-color
                   "Perceptual rest" @orange-stopped?)

    ;; Blue ball (physics rest)
    (draw-ball-row canvas @blue-x y-blue blue-color
                   "Physics rest" @blue-stopped?)

    ;; Debug text
    (draw-debug canvas height)))

(defn cleanup []
  (println "Decay rest comparison cleanup"))

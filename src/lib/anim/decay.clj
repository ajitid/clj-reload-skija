(ns lib.anim.decay
  "Decay animation using Apple's UIScrollView deceleration formula.

   Decay animates momentum without a target - velocity decreases exponentially
   until the animation comes to rest.

   Formula (from Apple UIScrollView):
     v(t) = v₀ × d^(1000t)
     x(t) = x₀ + v₀/(1000 × ln(d)) × (d^(1000t) - 1)

   Where:
     d = deceleration rate per millisecond (0.998 normal, 0.99 fast)
     t = time in seconds

   Usage:
     (def d (decay {:from 400 :velocity 1000}))
     (decay-now d)  ;; => {:value 580.0 :velocity 135.0 :at-rest? false}

   Sources:
     - Apple UIScrollView.decelerationRate documentation
     - Ilya Lobanov: Deceleration mechanics of UIScrollView"
  (:require [lib.time :as time]))

;; ============================================================
;; Default Values
;; ============================================================

;; Decay rate presets (Apple UIScrollView.DecelerationRate)
(def rate
  {:normal 0.998   ;; UIScrollView.DecelerationRate.normal
   :fast   0.99})  ;; UIScrollView.DecelerationRate.fast

(def defaults
  {:from 0.0
   :velocity 0.0                 ;; initial velocity (units/s)
   :rate (:normal rate)          ;; default to Apple normal
   :velocity-threshold 0.5})     ;; stop when velocity below this

;; ============================================================
;; Perceptual Duration
;; ============================================================

(defn decay-perceptual-duration
  "Time to reach 99% of projected distance. Only depends on rate.

   Formula: ln(0.01) / (1000 × ln(rate))

   This gives consistent durations:
     :normal (0.998) → ~2.3 seconds
     :fast   (0.99)  → ~0.46 seconds"
  [r]
  (let [log-rate (Math/log r)]
    (if (zero? log-rate)
      0.0
      (/ (Math/log 0.01) (* 1000 log-rate)))))

;; ============================================================
;; Core Algorithm (Apple's UIScrollView formula)
;; ============================================================

(defn- calculate-decay-state
  "Calculate decay position and velocity at time t using Apple's closed-form solution.
   Returns {:value :velocity :at-rest?}"
  [{:keys [from velocity rate start-time velocity-threshold]} t]
  (let [;; Elapsed time since animation start (in seconds)
        elapsed (max 0 (- t start-time))

        ;; Apple's formula: v(t) = v₀ × d^(1000t)
        ;; where d is applied per millisecond, so we multiply elapsed by 1000
        decay-factor (Math/pow rate (* elapsed 1000))
        current-velocity (* velocity decay-factor)

        ;; Position: x(t) = x₀ + v₀/(1000 × ln(d)) × (d^(1000t) - 1)
        ;; Note: ln(d) is negative for d < 1, so this works out correctly
        log-rate (Math/log rate)
        position-delta (if (zero? log-rate)
                         0.0
                         (* (/ velocity (* 1000 log-rate))
                            (- decay-factor 1)))
        current-position (+ from position-delta)

        ;; Check if at rest
        at-rest? (<= (Math/abs current-velocity) velocity-threshold)]

    {:value current-position
     :velocity (if at-rest? 0.0 current-velocity)
     :at-rest? at-rest?}))

;; ============================================================
;; Public API
;; ============================================================

(defn decay
  "Create a decay animation with the given config, merged with defaults.
   :start-time defaults to (time/now) if not provided.
   :perceptual-duration is pre-calculated for performance.

   Options:
     :from     - starting position (default 0.0)
     :velocity - initial velocity in units/second (default 0.0)
     :rate     - deceleration rate, keyword or number (default :normal)
                 Keywords: :normal (0.998), :fast (0.99)
                 Or raw number for custom rate
     :velocity-threshold - stop when velocity below this (default 0.5)

   Example:
     (decay {:from 400 :velocity 1000})
     (decay {:from 400 :velocity 1000 :rate :fast})
     (decay {:from 400 :velocity 1000 :rate 0.995})  ;; custom rate"
  [config]
  (let [;; Resolve rate if keyword, otherwise use as-is
        resolved-rate (if-let [r (:rate config)]
                        (if (keyword? r) (get rate r r) r)
                        (:rate defaults))
        perceptual-dur (decay-perceptual-duration resolved-rate)]
    (merge defaults
           config
           {:rate resolved-rate
            :start-time (or (:start-time config) (time/now))
            :perceptual-duration perceptual-dur})))

(defn decay-at
  "Get decay state at a specific time. Pure function.
   Returns {:value :velocity :at-rest? :at-perceptual-rest?}"
  [decay t]
  (let [state (calculate-decay-state decay t)
        elapsed (- t (:start-time decay))]
    (assoc state :at-perceptual-rest?
           (>= elapsed (:perceptual-duration decay)))))

(defn decay-now
  "Get decay state at current time. Uses configured time source.
   Returns {:value :velocity :at-rest?}"
  [decay]
  (decay-at decay (time/now)))

(defn decay-update
  "Update decay config mid-animation (rate, velocity-threshold).
   Preserves current position and velocity.
   Recalculates perceptual-duration in case rate changed.
   Returns a new decay with updated config.

   Example:
     (decay-update d {:rate 0.99})  ;; switch to faster stopping"
  [decay changes]
  (let [t (time/now)
        {:keys [value velocity]} (decay-at decay t)
        ;; Resolve new rate if provided
        new-rate (if-let [r (:rate changes)]
                   (if (keyword? r) (get rate r r) r)
                   (:rate decay))
        perceptual-dur (decay-perceptual-duration new-rate)]
    (merge decay
           changes
           {:from value
            :velocity velocity
            :start-time t
            :rate new-rate
            :perceptual-duration perceptual-dur})))

(ns lib.anim.decay
  "Decay animation using exponential deceleration (iOS-style).

   Decay animates momentum without a target - velocity decreases exponentially
   until the animation comes to rest.

   Formula (exp-based, equivalent to Apple UIScrollView):
     k = (1 - d) × 1000           ;; decay constant per second
     v(t) = v₀ × e^(-k×t)
     x(t) = x₀ + v₀/k × (1 - e^(-k×t))

   Where:
     d = deceleration rate (0.998 normal, 0.99 fast)
     t = time in seconds

   Usage:
     (def d (decay {:from 400 :velocity 1000}))
     (decay-now d)  ;; => {:value 580.0 :velocity 135.0 :at-rest? false}

   With delay (for stagger effects):
     (decay {:from 400 :velocity 1000 :delay 0.5})  ;; waits 0.5s before starting

   Sources:
     - Apple UIScrollView.decelerationRate documentation
     - pmndrs/react-spring decay implementation"
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
   :delay 0.0})                  ;; delay before decay starts (seconds)

;; Rest detection thresholds (units/s)
;; Based on human perception - motion below these values is imperceptible.
;; Works for logical pixels on HiDPI displays.
;; See: https://github.com/software-mansion/react-native-reanimated (VELOCITY_EPS)
(def ^:private velocity-threshold 0.5)
(def ^:private perceptual-velocity-threshold 1.0)

;; ============================================================
;; Perceptual Duration
;; ============================================================

(defn decay-perceptual-duration
  "Time until velocity drops below perceptual threshold (imperceptible motion).

   Uses a fixed velocity threshold (1 unit/s) based on human perception research.
   This is the industry standard approach used by:
     - React Native Reanimated (VELOCITY_EPS = 1)
     - Popmotion/Framer Motion (restSpeed)

   Formula: ln(|v₀| / threshold) / k  where k = (1-rate) × 1000

   Unlike the old 99%-of-distance approach, this correctly depends on
   initial velocity - faster flicks take longer to become imperceptible.

   Example durations (rate=0.998, threshold=1):
     v=100  → ~2.30s
     v=1000 → ~3.45s
     v=5000 → ~4.26s"
  [{:keys [velocity rate] :or {rate (:normal rate) velocity 0}}]
  (let [k (* (- 1 rate) 1000)
        v0 (Math/abs velocity)]
    (if (or (zero? k) (<= v0 perceptual-velocity-threshold))
      0.0
      (/ (Math/log (/ v0 perceptual-velocity-threshold)) k))))

;; ============================================================
;; Core Algorithm (exp-based, faster than pow)
;; ============================================================

(defn- calculate-decay-state
  "Calculate decay position and velocity at time t using exp-based formula.
   Returns {:value :velocity :at-rest? :in-delay?}"
  [{:keys [from velocity rate start-time delay] :or {delay 0.0}} t]
  (let [;; Elapsed time since animation start (in seconds)
        raw-elapsed (max 0 (- t start-time))

        ;; Check if still in delay period
        in-delay? (< raw-elapsed delay)]

    ;; If in delay, return initial state
    (if in-delay?
      {:value from
       :velocity 0.0
       :at-rest? false
       :in-delay? true}

      ;; Otherwise calculate decay physics
      (let [;; Subtract delay from elapsed time
            elapsed (- raw-elapsed delay)

            ;; Decay constant: k = (1-d) × 1000
            k (* (- 1 rate) 1000)

            ;; e^(-k×t) - the decay factor
            e (Math/exp (- (* k elapsed)))

            ;; Velocity: v(t) = v₀ × e^(-k×t)
            current-velocity (* velocity e)

            ;; Position: x(t) = x₀ + v₀/k × (1 - e^(-k×t))
            position-delta (if (zero? k)
                             0.0
                             (* (/ velocity k) (- 1 e)))
            current-position (+ from position-delta)

            ;; Check if at rest
            at-rest? (<= (Math/abs current-velocity) velocity-threshold)]

        {:value current-position
         :velocity (if at-rest? 0.0 current-velocity)
         :at-rest? at-rest?
         :in-delay? false}))))

;; ============================================================
;; Public API
;; ============================================================

(defn decay
  "Create a decay animation with the given config, merged with defaults.
   :start-time defaults to (time/now) if not provided.

   Options:
     :from     - starting position (default 0.0)
     :velocity - initial velocity in units/second (default 0.0)
     :rate     - deceleration rate, keyword or number (default :normal)
                 Keywords: :normal (0.998), :fast (0.99)
                 Or raw number for custom rate
     :delay    - seconds to wait before starting (default 0)

   Example:
     (decay {:from 400 :velocity 1000})
     (decay {:from 400 :velocity 1000 :rate :fast})
     (decay {:from 400 :velocity 1000 :delay 0.5})  ;; with delay"
  [config]
  (let [;; Resolve rate if keyword, otherwise use as-is
        resolved-rate (if-let [r (:rate config)]
                        (if (keyword? r) (get rate r r) r)
                        (:rate defaults))]
    (merge defaults
           config
           {:rate resolved-rate
            :start-time (or (:start-time config) (time/now))})))

(defn decay-at
  "Get decay state at a specific time. Pure function.
   Returns {:value :velocity :at-rest? :at-perceptual-rest? :in-delay?}"
  [decay t]
  (let [state (calculate-decay-state decay t)
        delay (or (:delay decay) 0.0)
        raw-elapsed (- t (:start-time decay))
        ;; Active elapsed (after delay)
        elapsed (- raw-elapsed delay)
        ;; Calculate perceptual duration on-demand
        perceptual-dur (decay-perceptual-duration decay)]
    (assoc state :at-perceptual-rest? (>= elapsed perceptual-dur))))

(defn decay-now
  "Get decay state at current time. Uses configured time source.
   Returns {:value :velocity :at-rest?}"
  [decay]
  (decay-at decay (time/now)))

(defn decay-update
  "Update decay config mid-animation (rate).
   Preserves current position, velocity, and remaining delay.
   Returns a new decay with updated config.

   Note: To clear delay, explicitly set :delay 0 in changes.

   Example:
     (decay-update d {:rate 0.99})  ;; switch to faster stopping"
  [decay changes]
  (let [t (time/now)
        {:keys [value velocity]} (decay-at decay t)
        ;; Resolve new rate if provided
        new-rate (if-let [r (:rate changes)]
                   (if (keyword? r) (get rate r r) r)
                   (:rate decay))]
    (merge decay
           changes
           {:from value
            :velocity velocity
            :start-time t
            :rate new-rate})))

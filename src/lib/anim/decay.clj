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

   With options:
     (decay {:from 400 :velocity 1000
             :delay 0.5           ;; wait before starting
             :loop 3              ;; repeat 3 times (true = infinite)
             :loop-delay 0.2      ;; pause between loops
             :alternate true      ;; reverse direction each loop
             :reversed false})    ;; start with negative velocity

   Mid-animation updates:
     (decay-update d {:rate :fast})   ;; change deceleration rate
     (decay-restart d)                ;; restart from beginning
     (decay-reverse d)                ;; reverse direction

   Sources:
     - Apple UIScrollView.decelerationRate documentation
     - pmndrs/react-spring decay implementation"
  (:require [lib.time :as time]
            [lib.anim.util :as util]))

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
   :delay 0.0                    ;; delay before decay starts (seconds)
   :loop-delay 0.0               ;; pause between loop iterations
   :loop false                   ;; false = no loop, true = infinite, number = count
   :alternate false              ;; reverse direction each loop
   :reversed false})             ;; start with negative velocity

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
;; Core Physics (single iteration)
;; ============================================================

(defn- calculate-single-decay
  "Calculate decay position and velocity for a single iteration at elapsed time.
   Returns [position velocity actual-at-rest?]"
  [from velocity rate elapsed]
  (let [;; Decay constant: k = (1-d) × 1000
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

        ;; Check if at rest (physics threshold)
        actual-at-rest? (<= (Math/abs current-velocity) velocity-threshold)]

    [current-position
     (if actual-at-rest? 0.0 current-velocity)
     actual-at-rest?]))

;; ============================================================
;; Core Algorithm (with loop support)
;; ============================================================

(defn- calculate-decay-state
  "Calculate decay state at time t with loop/direction support.
   Returns {:value :velocity :actual-at-rest? :at-rest? :in-delay? :iteration :direction :phase :done?}"
  [{:keys [from velocity rate start-time delay loop-delay loop alternate reversed]
    :or {delay 0.0 loop-delay 0.0 loop false alternate false reversed false}} t]
  (let [;; Elapsed time since animation start (in seconds)
        ;; NOTE: removed (max 0 ...) for consistency with other animations
        raw-elapsed (- t start-time)

        ;; Check if still in delay period
        in-delay? (< raw-elapsed delay)]

    ;; If in delay, return initial state
    (if in-delay?
      {:value from
       :velocity 0.0
       :actual-at-rest? false
       :at-rest? false
       :in-delay? true
       :iteration 0
       :direction (if reversed :backward :forward)
       :phase :delay
       :done? false}

      ;; Otherwise calculate with loop support
      (let [;; Perceptual duration for one iteration (based on initial velocity)
            perceptual-dur (decay-perceptual-duration {:velocity velocity :rate rate})

            ;; Active time after initial delay
            active-elapsed (- raw-elapsed delay)

            ;; Single iteration duration + loop delay
            iteration-with-delay (+ perceptual-dur loop-delay)

            ;; Calculate max iterations
            max-iterations (cond
                             (true? loop) ##Inf
                             (number? loop) loop
                             :else 1)

            ;; Which iteration are we in?
            ;; (zero? perceptual-dur) check needed because decay duration depends on velocity
            iteration (if (zero? perceptual-dur)
                        0
                        (util/calc-iteration active-elapsed iteration-with-delay max-iterations))

            ;; Time within current iteration
            iteration-start (* iteration iteration-with-delay)
            time-in-iteration (- active-elapsed iteration-start)

            ;; Are we in the loop-delay portion?
            in-loop-delay? (and (> time-in-iteration perceptual-dur)
                                (< iteration (dec max-iterations)))

            ;; Preserve input velocity sign, apply modifiers for reversed/alternate
            ;; 1. 'reversed' flips the velocity direction
            ;; 2. For alternating loops, flip on odd iterations
            base-velocity (if reversed (- velocity) velocity)
            iter-velocity (if (and alternate (odd? iteration))
                            (- base-velocity)
                            base-velocity)

            ;; Direction is metadata derived from actual velocity sign
            direction (if (neg? iter-velocity) :backward :forward)

            ;; Elapsed time within this iteration (clamped to perceptual duration)
            iteration-elapsed (min time-in-iteration perceptual-dur)

            ;; Calculate final position for this iteration (for loop continuity)
            final-position-delta (if (zero? (* (- 1 rate) 1000))
                                   0.0
                                   (/ velocity (* (- 1 rate) 1000)))
            iteration-final-pos (+ from (* iteration final-position-delta)
                                   (if (and alternate (odd? iteration))
                                     (- final-position-delta)
                                     0))

            ;; Calculate decay physics for this iteration
            [value vel actual-at-rest?] (calculate-single-decay
                                          iteration-final-pos iter-velocity
                                          rate iteration-elapsed)

            ;; At perceptual rest (one period elapsed)
            at-rest? (>= iteration-elapsed perceptual-dur)

            ;; Phase and done?
            phase (cond
                    (>= iteration max-iterations) :done
                    (and (not (true? loop))
                         (>= active-elapsed (+ (* max-iterations perceptual-dur)
                                               (* (dec max-iterations) loop-delay)))) :done
                    in-loop-delay? :loop-delay
                    :else :active)
            done? (= phase :done)]

        {:value (if (or done? in-loop-delay?)
                  (+ iteration-final-pos final-position-delta)
                  value)
         :velocity (if (or done? in-loop-delay?) 0.0 vel)
         :actual-at-rest? (or done? in-loop-delay? actual-at-rest?)
         :at-rest? (or done? at-rest?)
         :in-delay? false
         :iteration iteration
         :direction direction
         :phase phase
         :done? done?}))))

;; ============================================================
;; Public API
;; ============================================================

(defn decay
  "Create a decay animation with the given config, merged with defaults.
   :start-time defaults to (time/now) if not provided.

   Options:
     :from      - starting position (default 0.0)
     :velocity  - initial velocity in units/second (default 0.0)
     :rate      - deceleration rate, keyword or number (default :normal)
                  Keywords: :normal (0.998), :fast (0.99)
                  Or raw number for custom rate
     :delay     - seconds to wait before starting (default 0)
     :loop      - false (no loop), true (infinite), or number of iterations
     :loop-delay - pause between loop iterations (default 0)
     :alternate - reverse direction each loop (default false)
     :reversed  - start with negative velocity (default false)

   Example:
     (decay {:from 400 :velocity 1000})
     (decay {:from 400 :velocity 1000 :rate :fast})
     (decay {:from 400 :velocity 1000 :delay 0.5})
     (decay {:from 400 :velocity 1000 :loop 3 :alternate true})"
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
   Returns {:value :velocity :actual-at-rest? :at-rest? :in-delay? :iteration :direction :phase :done?}"
  [decay t]
  (calculate-decay-state decay t))

(defn decay-now
  "Get decay state at current time. Uses configured time source.
   Returns {:value :velocity :actual-at-rest? :at-rest? :in-delay? :iteration :direction :phase :done?}"
  [decay]
  (decay-at decay (time/now)))

(defn decay-restart
  "Restart decay from (time/now), keeping all other config.
   Returns a new decay."
  [decay]
  (assoc decay :start-time (time/now)))

(defn decay-update
  "Update decay config mid-animation.
   Starts from current value/velocity with new settings.
   Returns a new decay.

   Example:
     (decay-update d {:rate :fast})  ;; switch to faster stopping"
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

(defn decay-reverse
  "Reverse the decay direction, starting from current state.
   Negates velocity, toggles :reversed.
   Returns a new decay."
  [decay]
  (let [{:keys [value velocity]} (decay-now decay)
        {:keys [reversed]} decay]
    (assoc decay
           :from value
           :velocity (- velocity)
           :reversed (not reversed)
           :start-time (time/now))))

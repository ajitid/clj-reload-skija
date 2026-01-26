(ns lib.anim.spring
  "Spring physics library using Apple's CASpringAnimation closed-form solution.

   Springs are immutable data. Query position at any time with spring-at.

   Usage:
     (def s (spring {:from 0 :to 100}))
     (spring-now s)  ;; => {:value 67.3 :velocity 0.42 :at-rest? false}

   With options:
     (spring {:from 0 :to 100
              :delay 0.5           ;; wait before starting
              :loop 3              ;; repeat 3 times (true = infinite)
              :loop-delay 0.2      ;; pause between loops
              :alternate true      ;; reverse direction each loop
              :reversed false})    ;; start playing backwards

   Mid-animation updates:
     (spring-update s {:damping 20})      ;; change physics params
     (spring-update s {:to 200})          ;; change target (clears delay)
     (spring-restart s)                   ;; restart from beginning
     (spring-reverse s)                   ;; reverse direction"
  (:require [lib.time :as time]
            [lib.anim.util :as util]))

;; ============================================================
;; Default Values
;; ============================================================

(def defaults
  {:from 0.0
   :to 1.0
   :stiffness 180.0              ;; Apple-like bouncy feel
   :damping 12.0                 ;; Settles in ~0.5s
   :mass 1.0
   :velocity 0.0                 ;; initial velocity (units/s)
   :delay 0.0                    ;; delay before spring starts (seconds)
   :loop-delay 0.0               ;; pause between loop iterations
   :loop false                   ;; false = no loop, true = infinite, number = count
   :alternate false              ;; reverse direction each loop
   :reversed false})             ;; start backwards

;; Rest detection thresholds (units/s and units)
;; Based on human perception - motion below these values is imperceptible.
;; Works for logical pixels on HiDPI displays.
(def ^:private velocity-threshold 1.0)
(def ^:private displacement-threshold 0.001)

;; ============================================================
;; Perceptual Parameters (duration + bounce)
;; ============================================================
;; Source: https://www.kvin.me/posts/effortless-ui-spring-animations
;; Extended with variable mass support

(defn perceptual->physics
  "Convert perceptual spring params to physics params.

   Arguments:
     duration - perceptual duration in seconds (when key motion completes)
     bounce   - bounciness from -1 to 1 (-1=overdamped, 0=critical, 1=very bouncy)
     mass     - optional, defaults to 1.0 (independent third knob)

   Returns: {:mass :stiffness :damping}

   Mass is not involved in the duration/bounce equations (which assume mass=1).
   Changing mass gives different physical behavior but stiffness and damping
   stay fixed. See: https://www.kvin.me/posts/effortless-ui-spring-animations

   Example:
     (perceptual->physics 0.5 0.3)      ;; 0.5s bouncy spring
     (perceptual->physics 0.3 0 2.0)    ;; 0.3s critical, mass=2"
  ([duration bounce] (perceptual->physics duration bounce 1.0))
  ([duration bounce mass]
   (let [two-pi (* 2 Math/PI)
         four-pi (* 4 Math/PI)]
     {:mass mass
      :stiffness (Math/pow (/ two-pi duration) 2)
      :damping (if (>= bounce 0)
                 (/ (* (- 1 bounce) four-pi) duration)
                 (/ four-pi (* duration (+ 1 bounce))))})))

(defn spring-perceptual-duration
  "Calculate perceptual duration from physics params.
   This is how long the 'key motion' takes (useful for Timeline).

   Formula: 2π ÷ √(stiffness / mass)"
  [{:keys [stiffness mass] :or {mass 1.0}}]
  (/ (* 2 Math/PI) (Math/sqrt (/ stiffness mass))))

;; ============================================================
;; Core Physics (single iteration)
;; ============================================================

(defn- calculate-single-spring
  "Calculate spring position and velocity for a single iteration at elapsed time.
   Returns [position velocity actual-at-rest?]"
  [from to initial-velocity omega0 zeta elapsed]
  (let [;; Initial displacement (x0) and velocity (v0)
        x0 (- to from)
        v0 (- initial-velocity)  ;; wobble uses negative velocity in equations

        ;; Calculate position, velocity, and at-rest? based on damping type
        ;; Each branch returns [oscillation vel actual-at-rest?]
        [oscillation vel actual-at-rest?]
        (cond
          ;; Underdamped (zeta < 1): oscillates with exponential decay
          ;; Uses envelope-based rest check: bounds peak velocity for all future
          ;; oscillations. The envelope e^(-zeta*omega0*t) is monotonically
          ;; decreasing — once true, stays true forever (no flickering).
          (< zeta 1)
          (let [omega1 (* omega0 (Math/sqrt (- 1.0 (* zeta zeta))))
                envelope (Math/exp (- (* zeta omega0 elapsed)))
                sin-val (Math/sin (* omega1 elapsed))
                cos-val (Math/cos (* omega1 elapsed))
                ;; Oscillation coefficients (used in position, velocity, AND rest check)
                A (/ (+ v0 (* zeta omega0 x0)) omega1)
                B x0
                ;; Position
                oscillation (- to (* envelope (+ (* A sin-val) (* B cos-val))))
                ;; Velocity (derivative of position)
                vel (- (* zeta omega0 envelope (+ (* A sin-val) (* B cos-val)))
                       (* envelope (- (* (+ v0 (* zeta omega0 x0)) cos-val)
                                      (* omega1 x0 sin-val))))
                ;; Envelope-based rest: peak velocity bounded by omega1 * C * envelope
                ;; where C = sqrt(A^2 + B^2) is the amplitude of the oscillatory part.
                ;; Monotonically decreasing — once true, stays true.
                at-rest? (<= (* omega1 (Math/sqrt (+ (* A A) (* B B))) envelope)
                             velocity-threshold)]
            [oscillation vel at-rest?])

          ;; Critically damped (zeta = 1): fastest approach without overshoot
          ;; No oscillation, so instantaneous check is already monotonic.
          (= zeta 1.0)
          (let [envelope (Math/exp (- (* omega0 elapsed)))
                oscillation (- to (* envelope (+ x0 (* (+ v0 (* omega0 x0)) elapsed))))
                vel (* envelope
                       (+ (* v0 (- (* elapsed omega0) 1))
                          (* elapsed x0 omega0 omega0)))
                at-rest? (and (<= (Math/abs vel) velocity-threshold)
                              (<= (Math/abs (- to oscillation)) displacement-threshold))]
            [oscillation vel at-rest?])

          ;; Overdamped (zeta > 1): slow exponential approach
          ;; No oscillation, so instantaneous check is already monotonic.
          :else
          (let [omega2 (* omega0 (Math/sqrt (- (* zeta zeta) 1.0)))
                envelope (Math/exp (- (* zeta omega0 elapsed)))
                sinh-val (Math/sinh (* omega2 elapsed))
                cosh-val (Math/cosh (* omega2 elapsed))
                oscillation (- to
                               (* (/ envelope omega2)
                                  (+ (* (+ v0 (* zeta omega0 x0)) sinh-val)
                                     (* omega2 x0 cosh-val))))
                vel (- (* (/ (* envelope zeta omega0) omega2)
                          (+ (* (+ v0 (* zeta omega0 x0)) sinh-val)
                             (* x0 omega2 cosh-val)))
                       (* (/ envelope omega2)
                          (+ (* omega2 (+ v0 (* zeta omega0 x0)) cosh-val)
                             (* omega2 omega2 x0 sinh-val))))
                at-rest? (and (<= (Math/abs vel) velocity-threshold)
                              (<= (Math/abs (- to oscillation)) displacement-threshold))]
            [oscillation vel at-rest?]))]

    [(if actual-at-rest? to oscillation)
     (if actual-at-rest? 0.0 vel)
     actual-at-rest?]))

;; ============================================================
;; Core Algorithm (with loop support)
;; ============================================================

(defn- calculate-spring-state
  "Calculate spring state at time t with loop/direction support.
   Returns {:value :velocity :actual-at-rest? :at-rest? :in-delay? :iteration :direction :phase :done?}"
  [{:keys [from to velocity start-time omega0 zeta delay loop-delay loop alternate reversed]
    :or {delay 0.0 loop-delay 0.0 loop false alternate false reversed false}} t]
  (let [;; Elapsed time since animation start
        raw-elapsed (- t start-time)

        ;; Check if still in initial delay period
        in-delay? (< raw-elapsed delay)]

    ;; If in initial delay, return initial state
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
      (let [;; Perceptual duration for one iteration
            perceptual-dur (/ (* 2 Math/PI) omega0)

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
            iteration (util/calc-iteration active-elapsed iteration-with-delay max-iterations)

            ;; Time within current iteration
            iteration-start (* iteration iteration-with-delay)
            time-in-iteration (- active-elapsed iteration-start)

            ;; Are we in the loop-delay portion?
            in-loop-delay? (and (> time-in-iteration perceptual-dur)
                                (< iteration (dec max-iterations)))

            ;; Is this the last (or only) iteration?
            ;; Non-looping (max-iterations=1): always true
            ;; Finite loop last iteration: true
            ;; Infinite loop (max-iterations=##Inf): always false (correct — no "last" iteration)
            last-iteration? (>= iteration (dec max-iterations))

            ;; Direction (forward or backward based on alternate + reversed)
            base-forward? (if alternate
                            (even? iteration)
                            true)
            direction (if reversed
                        (if base-forward? :backward :forward)
                        (if base-forward? :forward :backward))

            ;; Effective from/to based on direction
            [eff-from eff-to] (if (= direction :backward)
                                [to from]
                                [from to])

            ;; Elapsed time within this iteration
            ;; Last/only iteration: unclamped so spring runs until physics rest
            ;; Earlier iterations: clamped to perceptual duration
            iteration-elapsed (if last-iteration?
                                time-in-iteration
                                (min time-in-iteration perceptual-dur))

            ;; Calculate spring physics for this iteration
            [value vel actual-at-rest?] (calculate-single-spring
                                          eff-from eff-to velocity
                                          omega0 zeta iteration-elapsed)

            ;; At rest?
            ;; Last/only iteration: physics-based (velocity + displacement thresholds)
            ;; Earlier iterations: perceptual (one period elapsed)
            at-rest? (if last-iteration?
                       actual-at-rest?
                       (>= iteration-elapsed perceptual-dur))

            ;; Phase and done?
            phase (cond
                    (>= iteration max-iterations) :done
                    (and last-iteration?
                         (not (true? loop))
                         actual-at-rest?) :done
                    in-loop-delay? :loop-delay
                    :else :active)
            done? (= phase :done)]

        {:value (if done?
                  (if (= direction :backward) eff-from eff-to)
                  (if in-loop-delay? eff-to value))
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

(defn spring
  "Create a spring with the given config, merged with defaults.
   :start-time defaults to (now) if not provided.
   Pre-computes :omega0 and :zeta for per-frame performance.

   Options:
     :from      - start value (default 0)
     :to        - target value (default 1)
     :stiffness - spring stiffness (default 180)
     :damping   - damping coefficient (default 12)
     :mass      - mass (default 1)
     :velocity  - initial velocity (default 0)
     :delay     - seconds to wait before starting (default 0)
     :loop      - false (no loop), true (infinite), or number of iterations
     :loop-delay - pause between loop iterations (default 0)
     :alternate - reverse direction each loop (default false)
     :reversed  - start playing backwards (default false)

   Example:
     (spring {:from 0 :to 100})
     (spring {:from 0 :to 100 :stiffness 300 :damping 20})
     (spring {:from 0 :to 100 :delay 0.5})
     (spring {:from 0 :to 100 :loop 3 :alternate true})"
  [config]
  (let [merged (merge defaults config)
        {:keys [stiffness damping mass]} merged
        omega0 (Math/sqrt (/ stiffness mass))
        zeta (/ damping (* 2 omega0 mass))]
    (assoc merged
           :start-time (or (:start-time config) (time/now))
           :omega0 omega0
           :zeta zeta)))

(defn spring-at
  "Get spring state at a specific time. Pure function.
   Returns {:value :velocity :actual-at-rest? :at-rest? :in-delay? :iteration :direction :phase :done?}"
  [spring t]
  (calculate-spring-state spring t))

(defn spring-now
  "Get spring state at current time. Uses configured time source.
   Returns {:value :velocity :actual-at-rest? :at-rest? :in-delay? :iteration :direction :phase :done?}"
  [spring]
  (spring-at spring (time/now)))

(defn spring-restart
  "Restart spring from (time/now), keeping all other config.
   Returns a new spring."
  [spring]
  (assoc spring :start-time (time/now)))

(defn spring-update
  "Update spring config mid-animation.
   Starts from current value/velocity with new settings.
   Recalculates omega0 and zeta if physics params changed.
   If :to is provided in changes, clears delay (starts immediately).
   Returns a new spring.

   Example:
     (spring-update s {:damping 20})
     (spring-update s {:stiffness 300 :mass 0.5})
     (spring-update s {:to 200})  ;; changes target, clears delay"
  [spring changes]
  (let [t (time/now)
        {:keys [value velocity]} (spring-at spring t)
        ;; If :to is being changed, clear delay (old retarget behavior)
        clear-delay? (contains? changes :to)
        updated (merge spring
                       changes
                       {:from value
                        :velocity velocity
                        :start-time t}
                       (when clear-delay? {:delay 0.0}))
        ;; Recalculate omega0 and zeta in case stiffness, damping, or mass changed
        {:keys [stiffness damping mass]} updated
        omega0 (Math/sqrt (/ stiffness mass))
        zeta (/ damping (* 2 omega0 mass))]
    (assoc updated :omega0 omega0 :zeta zeta)))

(defn spring-reverse
  "Reverse the spring direction, starting from current value.
   Swaps :from and :to, toggles :reversed.
   Returns a new spring."
  [spring]
  (let [{:keys [value velocity]} (spring-now spring)
        {:keys [from to reversed]} spring]
    (spring (assoc spring
                   :from value
                   :to from
                   :velocity velocity
                   :reversed (not reversed)
                   :start-time (time/now)))))

(defn spring-perceptual
  "Create spring using perceptual duration and bounce.
   More intuitive than physics params for designers.

   Options:
     :from      - start value (default 0)
     :to        - target value (default 1)
     :duration  - perceptual duration in seconds (required)
     :bounce    - bounciness -1 to 1 (default 0 = critical damping)
     :mass      - mass (default 1.0)
     :velocity  - initial velocity (default 0)
     :delay     - seconds to wait before starting (default 0)
     :loop      - false (no loop), true (infinite), or number of iterations
     :loop-delay - pause between loop iterations (default 0)
     :alternate - reverse direction each loop (default false)
     :reversed  - start playing backwards (default false)

   Example:
     (spring-perceptual {:from 0 :to 100 :duration 0.5 :bounce 0.3})
     (spring-perceptual {:from 0 :to 100 :duration 0.5 :loop 3 :alternate true})"
  [{:keys [duration bounce mass] :or {bounce 0 mass 1.0} :as opts}]
  (let [physics (perceptual->physics duration bounce mass)]
    (spring (merge (dissoc opts :duration :bounce) physics))))

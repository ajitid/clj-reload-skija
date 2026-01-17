(ns lib.anim.spring
  "Spring physics library using Apple's CASpringAnimation closed-form solution.

   Springs are immutable data. Query position at any time with spring-at.

   Usage:
     (def s (spring {:from 0 :to 100}))
     (spring-now s)  ;; => {:value 67.3 :velocity 0.42 :at-rest? false}

   Mid-animation updates:
     (spring-retarget s 200)           ;; change target
     (spring-update s {:damping 20})   ;; change physics params"
  (:require [lib.time :as time]))

;; ============================================================
;; Default Values
;; ============================================================

(def defaults
  {:from 0.0
   :to 1.0
   :stiffness 180.0              ;; Apple-like bouncy feel
   :damping 12.0                 ;; Settles in ~0.5s
   :mass 1.0
   :velocity 0.0})               ;; initial velocity (units/s)

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
     mass     - optional, defaults to 1.0

   Returns: {:mass :stiffness :damping}

   Example:
     (perceptual->physics 0.5 0.3)      ;; 0.5s bouncy spring
     (perceptual->physics 0.3 0 2.0)    ;; 0.3s critical, mass=2"
  ([duration bounce] (perceptual->physics duration bounce 1.0))
  ([duration bounce mass]
   (let [two-pi (* 2 Math/PI)
         four-pi (* 4 Math/PI)
         sqrt-mass (Math/sqrt mass)]
     {:mass mass
      :stiffness (* mass (Math/pow (/ two-pi duration) 2))
      :damping (if (>= bounce 0)
                 (/ (* sqrt-mass (- 1 bounce) four-pi) duration)
                 (/ (* sqrt-mass four-pi) (* duration (+ 1 bounce))))})))

(defn spring-perceptual-duration
  "Calculate perceptual duration from physics params.
   This is how long the 'key motion' takes (useful for Timeline).

   Formula: 2π ÷ √(stiffness / mass)"
  [{:keys [stiffness mass] :or {mass 1.0}}]
  (/ (* 2 Math/PI) (Math/sqrt (/ stiffness mass))))

;; ============================================================
;; Core Algorithm (from wobble)
;; ============================================================

(defn- calculate-spring-state
  "Calculate spring position and velocity at time t using closed-form solution.
   Returns {:value :velocity :at-rest?}"
  [{:keys [from to stiffness damping mass velocity start-time]} t]
  (let [;; Initial displacement (x0) and velocity (v0)
        x0 (- to from)
        v0 (- velocity)  ;; wobble uses negative velocity in equations

        ;; Damping ratio: determines oscillation type
        ;; zeta < 1: underdamped (oscillates)
        ;; zeta = 1: critically damped (fastest non-oscillating)
        ;; zeta > 1: overdamped (slow approach)
        zeta (/ damping (* 2 (Math/sqrt (* stiffness mass))))

        ;; Angular frequency (rad/s)
        omega0 (Math/sqrt (/ stiffness mass))

        ;; Elapsed time since animation start
        elapsed (- t start-time)

        ;; Calculate position and velocity based on damping type
        [oscillation vel]
        (cond
          ;; Underdamped (zeta < 1): oscillates with exponential decay
          (< zeta 1)
          (let [omega1 (* omega0 (Math/sqrt (- 1.0 (* zeta zeta))))
                envelope (Math/exp (- (* zeta omega0 elapsed)))
                sin-val (Math/sin (* omega1 elapsed))
                cos-val (Math/cos (* omega1 elapsed))]
            [(- to
                (* envelope
                   (+ (* (/ (+ v0 (* zeta omega0 x0)) omega1) sin-val)
                      (* x0 cos-val))))
             (- (* zeta omega0 envelope
                   (+ (* (/ (+ v0 (* zeta omega0 x0)) omega1) sin-val)
                      (* x0 cos-val)))
                (* envelope
                   (- (* (/ (+ v0 (* zeta omega0 x0)) 1) cos-val)
                      (* omega1 x0 sin-val))))])

          ;; Critically damped (zeta = 1): fastest approach without overshoot
          (= zeta 1.0)
          (let [envelope (Math/exp (- (* omega0 elapsed)))]
            [(- to (* envelope (+ x0 (* (+ v0 (* omega0 x0)) elapsed))))
             (* envelope
                (+ (* v0 (- (* elapsed omega0) 1))
                   (* elapsed x0 omega0 omega0)))])

          ;; Overdamped (zeta > 1): slow exponential approach
          :else
          (let [omega2 (* omega0 (Math/sqrt (- (* zeta zeta) 1.0)))
                envelope (Math/exp (- (* zeta omega0 elapsed)))
                sinh-val (Math/sinh (* omega2 elapsed))
                cosh-val (Math/cosh (* omega2 elapsed))]
            [(- to
                (* (/ envelope omega2)
                   (+ (* (+ v0 (* zeta omega0 x0)) sinh-val)
                      (* omega2 x0 cosh-val))))
             (- (* (/ (* envelope zeta omega0) omega2)
                   (+ (* (+ v0 (* zeta omega0 x0)) sinh-val)
                      (* x0 omega2 cosh-val)))
                (* (/ envelope omega2)
                   (+ (* omega2 (+ v0 (* zeta omega0 x0)) cosh-val)
                      (* omega2 omega2 x0 sinh-val))))]))

        ;; Check if spring is at rest
        at-rest? (and (<= (Math/abs vel) velocity-threshold)
                      (<= (Math/abs (- to oscillation)) displacement-threshold))]

    {:value (if at-rest? to oscillation)
     :velocity (if at-rest? 0.0 vel)
     :at-rest? at-rest?}))

;; ============================================================
;; Public API
;; ============================================================

(defn spring
  "Create a spring with the given config, merged with defaults.
   :start-time defaults to (now) if not provided.
   :perceptual-duration is pre-calculated for performance.

   Example:
     (spring {:from 0 :to 100})
     (spring {:from 0 :to 100 :stiffness 300 :damping 20})"
  [config]
  (let [merged (merge defaults config)
        perceptual-dur (spring-perceptual-duration merged)]
    (assoc merged
           :start-time (or (:start-time config) (time/now))
           :perceptual-duration perceptual-dur)))

(defn spring-at
  "Get spring state at a specific time. Pure function.
   Returns {:value :velocity :at-rest? :at-perceptual-rest?}"
  [spring t]
  (let [state (calculate-spring-state spring t)
        elapsed (- t (:start-time spring))]
    (assoc state :at-perceptual-rest?
           (>= elapsed (:perceptual-duration spring)))))

(defn spring-now
  "Get spring state at current time. Uses configured time source.
   Returns {:value :velocity :at-rest?}"
  [spring]
  (spring-at spring (time/now)))

(defn spring-retarget
  "Change target mid-animation, preserving current velocity.
   Returns a new spring starting from current position/velocity."
  [spring new-to]
  (let [t (time/now)
        {:keys [value velocity]} (spring-at spring t)]
    (merge spring
           {:from value
            :to new-to
            :velocity velocity
            :start-time t})))

(defn spring-update
  "Update spring config mid-animation (stiffness, damping, mass, to, etc).
   Preserves current position and velocity.
   Recalculates perceptual-duration in case stiffness or mass changed.
   Returns a new spring with updated config.

   Example:
     (spring-update s {:damping 20})
     (spring-update s {:stiffness 300 :mass 0.5})"
  [spring changes]
  (let [t (time/now)
        {:keys [value velocity]} (spring-at spring t)
        updated (merge spring changes
                       {:from value
                        :velocity velocity
                        :start-time t})
        ;; Recalculate in case stiffness or mass changed
        perceptual-dur (spring-perceptual-duration updated)]
    (assoc updated :perceptual-duration perceptual-dur)))

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

   Example:
     (spring-perceptual {:from 0 :to 100 :duration 0.5 :bounce 0.3})"
  [{:keys [duration bounce mass] :or {bounce 0 mass 1.0} :as opts}]
  (let [physics (perceptual->physics duration bounce mass)]
    (spring (merge (dissoc opts :duration :bounce) physics))))

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
   :velocity 0.0                 ;; initial velocity (units/s)
   :velocity-threshold 1.0
   :displacement-threshold 0.001})

;; ============================================================
;; Core Algorithm (from wobble)
;; ============================================================

(defn- calculate-spring-state
  "Calculate spring position and velocity at time t using closed-form solution.
   Returns {:value :velocity :at-rest?}"
  [{:keys [from to stiffness damping mass velocity start-time
           velocity-threshold displacement-threshold]} t]
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

   Example:
     (spring {:from 0 :to 100})
     (spring {:from 0 :to 100 :stiffness 300 :damping 20})"
  [config]
  (merge defaults
         config
         {:start-time (or (:start-time config) (time/now))}))

(defn spring-at
  "Get spring state at a specific time. Pure function.
   Returns {:value :velocity :at-rest?}"
  [spring t]
  (calculate-spring-state spring t))

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
   Returns a new spring with updated config.

   Example:
     (spring-update s {:damping 20})
     (spring-update s {:stiffness 300 :mass 0.5})"
  [spring changes]
  (let [t (time/now)
        {:keys [value velocity]} (spring-at spring t)]
    (merge spring
           changes
           {:from value
            :velocity velocity
            :start-time t})))

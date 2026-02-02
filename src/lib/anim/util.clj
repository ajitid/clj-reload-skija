(ns lib.anim.util
  "Shared utilities for animation library."
  (:require [fastmath.vector :as v]))

;; ============================================================
;; Loop Iteration Helpers
;; ============================================================

(defn calc-iteration
  "Calculate which iteration we're in for looping animations.
   Returns clamped iteration number (0-indexed, as long).

   Arguments:
     active-elapsed       - time elapsed after initial delay
     iteration-with-delay - single iteration duration + loop-delay
     max-iterations       - maximum iterations (may be ##Inf)"
  [active-elapsed iteration-with-delay max-iterations]
  (let [raw (if (<= active-elapsed 0)
              0
              (Math/floor (/ active-elapsed iteration-with-delay)))]
    (long (min raw (dec max-iterations)))))

;; ============================================================
;; 2D State Combining
;; ============================================================

(defn combine-2d-states
  "Combine two 1D animation states into a 2D state.

   Vector fields: :value, :velocity become Vec2
   Boolean AND fields: :actual-at-rest?, :at-rest?, :done?
   Pass-through from x: :in-delay?, :iteration, :direction, :phase"
  [state-x state-y]
  {:value (v/vec2 (:value state-x) (:value state-y))
   :velocity (v/vec2 (:velocity state-x) (:velocity state-y))
   :actual-at-rest? (and (:actual-at-rest? state-x) (:actual-at-rest? state-y))
   :at-rest? (and (:at-rest? state-x) (:at-rest? state-y))
   :in-delay? (:in-delay? state-x)
   :iteration (:iteration state-x)
   :direction (:direction state-x)
   :phase (:phase state-x)
   :done? (and (:done? state-x) (:done? state-y))})

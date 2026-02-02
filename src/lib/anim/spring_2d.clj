(ns lib.anim.spring-2d
  "2D spring physics - wraps 1D spring for X/Y coordinates.

   Usage:
     (def s (spring-2d {:from (v/vec2 0 0) :to (v/vec2 100 200)}))
     (spring-2d-now s)  ;; => {:value Vec2[67.3 134.6] :velocity Vec2[0.42 0.84] :at-rest? false ...}

   With options:
     (spring-2d {:from (v/vec2 0 0) :to (v/vec2 100 200)
                 :delay 0.5
                 :loop 3
                 :alternate true})

   Mid-animation updates:
     (spring-2d-update s {:damping 20})
     (spring-2d-update s {:to (v/vec2 200 400)})  ;; change target
     (spring-2d-restart s)
     (spring-2d-reverse s)"
  (:require [lib.anim.spring :as spring]
            [lib.anim.util :as util]
            [fastmath.vector :as v]))

;; ============================================================
;; Public API
;; ============================================================

(defn spring-2d
  "Create a 2D spring with the given config.
   :from, :to, and :velocity should be Vec2.

   Options:
     :from      - start value Vec2 (default (v/vec2 0 0))
     :to        - target value Vec2 (default (v/vec2 1 1))
     :velocity  - initial velocity Vec2 (default (v/vec2 0 0))
     :stiffness - spring stiffness (default 180)
     :damping   - damping coefficient (default 12)
     :mass      - mass (default 1)
     :delay     - seconds to wait before starting (default 0)
     :loop      - false (no loop), true (infinite), or number of iterations
     :loop-delay - pause between loop iterations (default 0)
     :alternate - reverse direction each loop (default false)
     :reversed  - start playing backwards (default false)

   Example:
     (spring-2d {:from (v/vec2 0 0) :to (v/vec2 100 200)})
     (spring-2d {:from (v/vec2 0 0) :to (v/vec2 100 200) :loop 3 :alternate true})"
  [{:keys [from to velocity stiffness damping mass delay loop loop-delay alternate reversed start-time]
    :or {from (v/vec2 0.0 0.0)
         to (v/vec2 1.0 1.0)
         velocity (v/vec2 0.0 0.0)}}]
  (let [[fx fy] from
        [tx ty] to
        [vx vy] velocity
        base-config (cond-> {}
                      stiffness (assoc :stiffness stiffness)
                      damping (assoc :damping damping)
                      mass (assoc :mass mass)
                      delay (assoc :delay delay)
                      loop (assoc :loop loop)
                      loop-delay (assoc :loop-delay loop-delay)
                      alternate (assoc :alternate alternate)
                      reversed (assoc :reversed reversed)
                      start-time (assoc :start-time start-time))]
    {:spring-x (spring/spring (merge base-config {:from fx :to tx :velocity vx}))
     :spring-y (spring/spring (merge base-config {:from fy :to ty :velocity vy}))}))

(defn spring-2d-at
  "Get 2D spring state at a specific time. Pure function.
   Returns {:value Vec2 :velocity Vec2 :actual-at-rest? :at-rest? :in-delay? :iteration :direction :phase :done?}"
  [{:keys [spring-x spring-y]} t]
  (util/combine-2d-states (spring/spring-at spring-x t)
                          (spring/spring-at spring-y t)))

(defn spring-2d-now
  "Get 2D spring state at current time.
   Returns {:value Vec2 :velocity Vec2 :actual-at-rest? :at-rest? :in-delay? :iteration :direction :phase :done?}"
  [{:keys [spring-x spring-y]}]
  (util/combine-2d-states (spring/spring-now spring-x)
                          (spring/spring-now spring-y)))

(defn spring-2d-restart
  "Restart 2D spring from now, keeping all other config.
   Returns a new 2D spring."
  [{:keys [spring-x spring-y]}]
  {:spring-x (spring/spring-restart spring-x)
   :spring-y (spring/spring-restart spring-y)})

(defn spring-2d-update
  "Update 2D spring config mid-animation.
   Preserves current position and velocity.
   If :to is provided (as Vec2), changes target and clears delay.

   Example:
     (spring-2d-update s {:damping 20})
     (spring-2d-update s {:stiffness 300 :mass 0.5})
     (spring-2d-update s {:to (v/vec2 200 400)})  ;; change target"
  [{:keys [spring-x spring-y]} changes]
  (let [;; Handle :to as Vec2
        [changes-x changes-y] (if-let [[tx ty] (:to changes)]
                                [(assoc (dissoc changes :to) :to tx)
                                 (assoc (dissoc changes :to) :to ty)]
                                [changes changes])]
    {:spring-x (spring/spring-update spring-x changes-x)
     :spring-y (spring/spring-update spring-y changes-y)}))

(defn spring-2d-reverse
  "Reverse the 2D spring direction, starting from current value.
   Returns a new 2D spring."
  [{:keys [spring-x spring-y]}]
  {:spring-x (spring/spring-reverse spring-x)
   :spring-y (spring/spring-reverse spring-y)})

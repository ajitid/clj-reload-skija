(ns lib.anim.spring-2d
  "2D spring physics - wraps 1D spring for X/Y coordinates.

   Usage:
     (def s (spring-2d {:from [0 0] :to [100 200]}))
     (spring-2d-now s)  ;; => {:value [67.3 134.6] :velocity [0.42 0.84] :at-rest? false}

   Mid-animation updates:
     (spring-2d-retarget s [200 400])
     (spring-2d-update s {:damping 20})"
  (:require [lib.anim.spring :as spring]))

;; ============================================================
;; Public API
;; ============================================================

(defn spring-2d
  "Create a 2D spring with the given config.
   :from, :to, and :velocity should be [x y] vectors.

   Example:
     (spring-2d {:from [0 0] :to [100 200]})
     (spring-2d {:from [0 0] :to [100 200] :velocity [10 20]})"
  [{:keys [from to velocity stiffness damping mass start-time]
    :or {from [0.0 0.0]
         to [1.0 1.0]
         velocity [0.0 0.0]}}]
  (let [[fx fy] from
        [tx ty] to
        [vx vy] velocity
        base-config (cond-> {}
                      stiffness (assoc :stiffness stiffness)
                      damping (assoc :damping damping)
                      mass (assoc :mass mass)
                      start-time (assoc :start-time start-time))]
    {:spring-x (spring/spring (merge base-config {:from fx :to tx :velocity vx}))
     :spring-y (spring/spring (merge base-config {:from fy :to ty :velocity vy}))}))

(defn spring-2d-at
  "Get 2D spring state at a specific time. Pure function.
   Returns {:value [x y] :velocity [vx vy] :at-rest?}"
  [{:keys [spring-x spring-y]} t]
  (let [state-x (spring/spring-at spring-x t)
        state-y (spring/spring-at spring-y t)]
    {:value [(:value state-x) (:value state-y)]
     :velocity [(:velocity state-x) (:velocity state-y)]
     :at-rest? (and (:at-rest? state-x) (:at-rest? state-y))}))

(defn spring-2d-now
  "Get 2D spring state at current time.
   Returns {:value [x y] :velocity [vx vy] :at-rest?}"
  [{:keys [spring-x spring-y]}]
  (let [state-x (spring/spring-now spring-x)
        state-y (spring/spring-now spring-y)]
    {:value [(:value state-x) (:value state-y)]
     :velocity [(:velocity state-x) (:velocity state-y)]
     :at-rest? (and (:at-rest? state-x) (:at-rest? state-y))}))

(defn spring-2d-retarget
  "Change target mid-animation, preserving current velocity.
   Returns a new 2D spring starting from current position/velocity."
  [{:keys [spring-x spring-y]} [tx ty]]
  {:spring-x (spring/spring-retarget spring-x tx)
   :spring-y (spring/spring-retarget spring-y ty)})

(defn spring-2d-update
  "Update 2D spring config mid-animation (stiffness, damping, mass, etc).
   Preserves current position and velocity.

   Example:
     (spring-2d-update s {:damping 20})
     (spring-2d-update s {:stiffness 300 :mass 0.5})"
  [{:keys [spring-x spring-y]} changes]
  {:spring-x (spring/spring-update spring-x changes)
   :spring-y (spring/spring-update spring-y changes)})

(ns lib.anim.decay-2d
  "2D decay animation - wraps 1D decay for X/Y coordinates.

   Usage:
     (def d (decay-2d {:from [400 300] :velocity [1000 -500]}))
     (decay-2d-now d)  ;; => {:value [580.0 200.0] :velocity [135.0 -67.5] :at-rest? false}"
  (:require [lib.anim.decay :as decay]))

;; ============================================================
;; Public API
;; ============================================================

(defn decay-2d
  "Create a 2D decay animation with the given config.
   :from and :velocity should be [x y] vectors.

   Example:
     (decay-2d {:from [400 300] :velocity [1000 -500]})
     (decay-2d {:from [400 300] :velocity [1000 -500] :rate :fast})"
  [{:keys [from velocity rate velocity-threshold start-time]
    :or {from [0.0 0.0]
         velocity [0.0 0.0]}}]
  (let [[fx fy] from
        [vx vy] velocity
        base-config (cond-> {}
                      rate (assoc :rate rate)
                      velocity-threshold (assoc :velocity-threshold velocity-threshold)
                      start-time (assoc :start-time start-time))]
    {:decay-x (decay/decay (merge base-config {:from fx :velocity vx}))
     :decay-y (decay/decay (merge base-config {:from fy :velocity vy}))}))

(defn decay-2d-at
  "Get 2D decay state at a specific time. Pure function.
   Returns {:value [x y] :velocity [vx vy] :at-rest?}"
  [{:keys [decay-x decay-y]} t]
  (let [state-x (decay/decay-at decay-x t)
        state-y (decay/decay-at decay-y t)]
    {:value [(:value state-x) (:value state-y)]
     :velocity [(:velocity state-x) (:velocity state-y)]
     :at-rest? (and (:at-rest? state-x) (:at-rest? state-y))}))

(defn decay-2d-now
  "Get 2D decay state at current time.
   Returns {:value [x y] :velocity [vx vy] :at-rest?}"
  [{:keys [decay-x decay-y]}]
  (let [state-x (decay/decay-now decay-x)
        state-y (decay/decay-now decay-y)]
    {:value [(:value state-x) (:value state-y)]
     :velocity [(:velocity state-x) (:velocity state-y)]
     :at-rest? (and (:at-rest? state-x) (:at-rest? state-y))}))

(defn decay-2d-update
  "Update 2D decay config mid-animation (rate, velocity-threshold).
   Preserves current position and velocity.

   Example:
     (decay-2d-update d {:rate 0.99})"
  [{:keys [decay-x decay-y]} changes]
  {:decay-x (decay/decay-update decay-x changes)
   :decay-y (decay/decay-update decay-y changes)})

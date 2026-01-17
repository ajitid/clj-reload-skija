(ns lib.anim.decay-2d
  "2D decay animation - wraps 1D decay for X/Y coordinates.

   Usage:
     (def d (decay-2d {:from [400 300] :velocity [1000 -500]}))
     (decay-2d-now d)  ;; => {:value [580.0 200.0] :velocity [135.0 -67.5] :at-rest? false ...}

   With options:
     (decay-2d {:from [400 300] :velocity [1000 -500]
                :delay 0.5
                :loop 3
                :alternate true})

   Mid-animation updates:
     (decay-2d-update d {:rate :fast})
     (decay-2d-restart d)
     (decay-2d-reverse d)"
  (:require [lib.anim.decay :as decay]))

;; ============================================================
;; Public API
;; ============================================================

(defn decay-2d
  "Create a 2D decay animation with the given config.
   :from and :velocity should be [x y] vectors.

   Options:
     :from      - starting position [x y] (default [0 0])
     :velocity  - initial velocity [vx vy] (default [0 0])
     :rate      - deceleration rate, keyword or number (default :normal)
     :delay     - seconds to wait before starting (default 0)
     :loop      - false (no loop), true (infinite), or number of iterations
     :loop-delay - pause between loop iterations (default 0)
     :alternate - reverse direction each loop (default false)
     :reversed  - start with negative velocity (default false)

   Example:
     (decay-2d {:from [400 300] :velocity [1000 -500]})
     (decay-2d {:from [400 300] :velocity [1000 -500] :rate :fast})
     (decay-2d {:from [400 300] :velocity [1000 -500] :loop 3 :alternate true})"
  [{:keys [from velocity rate delay loop loop-delay alternate reversed start-time]
    :or {from [0.0 0.0]
         velocity [0.0 0.0]}}]
  (let [[fx fy] from
        [vx vy] velocity
        base-config (cond-> {}
                      rate (assoc :rate rate)
                      delay (assoc :delay delay)
                      loop (assoc :loop loop)
                      loop-delay (assoc :loop-delay loop-delay)
                      alternate (assoc :alternate alternate)
                      reversed (assoc :reversed reversed)
                      start-time (assoc :start-time start-time))]
    {:decay-x (decay/decay (merge base-config {:from fx :velocity vx}))
     :decay-y (decay/decay (merge base-config {:from fy :velocity vy}))}))

(defn decay-2d-at
  "Get 2D decay state at a specific time. Pure function.
   Returns {:value [x y] :velocity [vx vy] :actual-at-rest? :at-rest? :in-delay? :iteration :direction :phase :done?}"
  [{:keys [decay-x decay-y]} t]
  (let [state-x (decay/decay-at decay-x t)
        state-y (decay/decay-at decay-y t)]
    {:value [(:value state-x) (:value state-y)]
     :velocity [(:velocity state-x) (:velocity state-y)]
     :actual-at-rest? (and (:actual-at-rest? state-x) (:actual-at-rest? state-y))
     :at-rest? (and (:at-rest? state-x) (:at-rest? state-y))
     :in-delay? (:in-delay? state-x)
     :iteration (:iteration state-x)
     :direction (:direction state-x)
     :phase (:phase state-x)
     :done? (and (:done? state-x) (:done? state-y))}))

(defn decay-2d-now
  "Get 2D decay state at current time.
   Returns {:value [x y] :velocity [vx vy] :actual-at-rest? :at-rest? :in-delay? :iteration :direction :phase :done?}"
  [{:keys [decay-x decay-y]}]
  (let [state-x (decay/decay-now decay-x)
        state-y (decay/decay-now decay-y)]
    {:value [(:value state-x) (:value state-y)]
     :velocity [(:velocity state-x) (:velocity state-y)]
     :actual-at-rest? (and (:actual-at-rest? state-x) (:actual-at-rest? state-y))
     :at-rest? (and (:at-rest? state-x) (:at-rest? state-y))
     :in-delay? (:in-delay? state-x)
     :iteration (:iteration state-x)
     :direction (:direction state-x)
     :phase (:phase state-x)
     :done? (and (:done? state-x) (:done? state-y))}))

(defn decay-2d-restart
  "Restart 2D decay from now, keeping all other config.
   Returns a new 2D decay."
  [{:keys [decay-x decay-y]}]
  {:decay-x (decay/decay-restart decay-x)
   :decay-y (decay/decay-restart decay-y)})

(defn decay-2d-update
  "Update 2D decay config mid-animation.
   Preserves current position and velocity.

   Example:
     (decay-2d-update d {:rate :fast})"
  [{:keys [decay-x decay-y]} changes]
  {:decay-x (decay/decay-update decay-x changes)
   :decay-y (decay/decay-update decay-y changes)})

(defn decay-2d-reverse
  "Reverse the 2D decay direction, starting from current state.
   Returns a new 2D decay."
  [{:keys [decay-x decay-y]}]
  {:decay-x (decay/decay-reverse decay-x)
   :decay-y (decay/decay-reverse decay-y)})

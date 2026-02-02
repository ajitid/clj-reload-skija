(ns lib.anim.decay-2d
  "2D decay animation - wraps 1D decay for X/Y coordinates.

   Usage:
     (def d (decay-2d {:from (v/vec2 400 300) :velocity (v/vec2 1000 -500)}))
     (decay-2d-now d)  ;; => {:value Vec2[580.0 200.0] :velocity Vec2[135.0 -67.5] :at-rest? false ...}

   With options:
     (decay-2d {:from (v/vec2 400 300) :velocity (v/vec2 1000 -500)
                :delay 0.5
                :loop 3
                :alternate true})

   Mid-animation updates:
     (decay-2d-update d {:rate :fast})
     (decay-2d-restart d)
     (decay-2d-reverse d)"
  (:require [lib.anim.decay :as decay]
            [lib.anim.util :as util]
            [fastmath.vector :as v]))

;; ============================================================
;; Public API
;; ============================================================

(defn decay-2d
  "Create a 2D decay animation with the given config.
   :from and :velocity should be Vec2.

   Options:
     :from      - starting position Vec2 (default (v/vec2 0 0))
     :velocity  - initial velocity Vec2 (default (v/vec2 0 0))
     :rate      - deceleration rate, keyword or number (default :normal)
     :delay     - seconds to wait before starting (default 0)
     :loop      - false (no loop), true (infinite), or number of iterations
     :loop-delay - pause between loop iterations (default 0)
     :alternate - reverse direction each loop (default false)
     :reversed  - start with negative velocity (default false)

   Example:
     (decay-2d {:from (v/vec2 400 300) :velocity (v/vec2 1000 -500)})
     (decay-2d {:from (v/vec2 400 300) :velocity (v/vec2 1000 -500) :rate :fast})
     (decay-2d {:from (v/vec2 400 300) :velocity (v/vec2 1000 -500) :loop 3 :alternate true})"
  [{:keys [from velocity rate delay loop loop-delay alternate reversed start-time]
    :or {from (v/vec2 0.0 0.0)
         velocity (v/vec2 0.0 0.0)}}]
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
   Returns {:value Vec2 :velocity Vec2 :actual-at-rest? :at-rest? :in-delay? :iteration :direction :phase :done?}"
  [{:keys [decay-x decay-y]} t]
  (util/combine-2d-states (decay/decay-at decay-x t)
                          (decay/decay-at decay-y t)))

(defn decay-2d-now
  "Get 2D decay state at current time.
   Returns {:value Vec2 :velocity Vec2 :actual-at-rest? :at-rest? :in-delay? :iteration :direction :phase :done?}"
  [{:keys [decay-x decay-y]}]
  (util/combine-2d-states (decay/decay-now decay-x)
                          (decay/decay-now decay-y)))

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

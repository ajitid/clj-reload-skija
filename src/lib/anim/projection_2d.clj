(ns lib.anim.projection-2d
  "2D projection - where a 2D flick would land.

   Usage:
     (projection-2d (v/vec2 100 200) (v/vec2 500 -300) :normal)  ;; => Vec2[~349.7 ~50.3]

   Sources:
     - WWDC 2018: Designing Fluid Interfaces
       https://developer.apple.com/videos/play/wwdc2018/803/
     - Ilya Lobanov: How UIScrollView works
       https://medium.com/@esskeetit/how-uiscrollview-works-e418adc47060"
  (:require [lib.anim.projection :as proj]
            [fastmath.vector :as v]))

;; ============================================================
;; Public API
;; ============================================================

(defn projection-2d
  "Calculate where a 2D flick would land.

   Arguments:
     position - Vec2 starting position
     velocity - Vec2 velocity (units/second)
     r        - deceleration rate (keyword like :normal/:fast, or number)

   Example:
     (projection-2d (v/vec2 100 200) (v/vec2 500 -300) :normal)  ;; => Vec2[~349.7 ~50.3]"
  [position velocity r]
  (let [[px py] position
        [vx vy] velocity]
    (v/vec2 (proj/projection px vx r)
            (proj/projection py vy r))))

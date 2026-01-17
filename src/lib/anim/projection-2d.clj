(ns lib.anim.projection-2d
  "2D projection - where a 2D flick would land.

   Usage:
     (projection-2d [100 200] [500 -300] :normal)  ;; => [~349.7 ~50.3]

   Sources:
     - WWDC 2018: Designing Fluid Interfaces
       https://developer.apple.com/videos/play/wwdc2018/803/
     - Ilya Lobanov: How UIScrollView works
       https://medium.com/@esskeetit/how-uiscrollview-works-e418adc47060"
  (:require [lib.anim.projection :as proj]))

;; ============================================================
;; Public API
;; ============================================================

(defn projection-2d
  "Calculate where a 2D flick would land.

   Arguments:
     position - [x y] starting position
     velocity - [vx vy] velocity (units/second)
     r        - deceleration rate (keyword like :normal/:fast, or number)

   Example:
     (projection-2d [100 200] [500 -300] :normal)  ;; => [~349.7 ~50.3]"
  [[px py] [vx vy] r]
  [(proj/projection px vx r)
   (proj/projection py vy r)])

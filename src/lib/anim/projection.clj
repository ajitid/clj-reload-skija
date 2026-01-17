(ns lib.anim.projection
  "Projection utilities for gesture intent detection.

   Calculates where a flick gesture would land based on initial velocity
   and deceleration rate. Used to determine user intent before starting
   an animation (e.g., which corner to snap to in PIP).

   Formula (exp-based, equivalent to Apple UIScrollView):
     k = (1 - rate) × 1000
     final = from + velocity / k

   Usage:
     (projection 100 500 :normal)      ;; => ~349.7 (where it would land)

   Sources:
     - WWDC 2018: Designing Fluid Interfaces
       https://developer.apple.com/videos/play/wwdc2018/803/
     - Ilya Lobanov: How UIScrollView works
       https://medium.com/@esskeetit/how-uiscrollview-works-e418adc47060
     - pmndrs/react-spring decay implementation"
  (:require [lib.anim.decay :as decay]))

;; ============================================================
;; Core Functions
;; ============================================================

(defn projection
  "Calculate where a flick would land (final resting position).

   Arguments:
     from     - starting position
     velocity - initial velocity (units/second)
     r        - deceleration rate (keyword like :normal/:fast, or number)

   Example:
     (projection 100 500 :normal)   ;; => ~349.7 (where it would land)
     (projection 100 -500 :normal)  ;; => ~-149.7 (flicking backwards)

   Sources:
     - WWDC 2018: Designing Fluid Interfaces
       https://developer.apple.com/videos/play/wwdc2018/803/"
  [from velocity r]
  (let [resolved-rate (if (keyword? r) (get decay/rate r r) r)
        ;; Decay constant: k = (1-rate) × 1000
        k (* (- 1 resolved-rate) 1000)]
    (if (zero? k)
      from
      ;; As t→∞, e^(-k×t)→0, so: final = from + v/k × (1 - 0) = from + v/k
      (+ from (/ velocity k)))))


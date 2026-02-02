(ns lib.anim.pip-flick
  "PIP-flick animation for snapping to anchor points.

   Requires: lib.anim.projection (for projection-2d)

   Implements the Picture-in-Picture flick gesture behavior from iOS:
   1. User flicks a draggable view with some velocity
   2. Project where it would land using decay formula
   3. Find nearest anchor point (e.g., screen corners)
   4. Spring animate to that anchor, preserving gesture velocity

   This creates the natural 'throw to corner' feel from FaceTime/iMessage PIP.

   Usage:
     (def result (pip-flick {:position (v/vec2 100 200)
                             :velocity (v/vec2 500 -300)
                             :anchors [(v/vec2 0 0) (v/vec2 400 0) (v/vec2 0 600) (v/vec2 400 600)]}))
     ;; => {:target Vec2[400 0] :spring-config {...}}

   Sources:
     - WWDC 2018: Designing Fluid Interfaces
       https://developer.apple.com/videos/play/wwdc2018/803/
     - Ilya Lobanov: How UIScrollView works
       https://medium.com/@esskeetit/how-uiscrollview-works-e418adc47060"
  (:require [fastmath.vector :as v]))

;; ============================================================
;; Distance Calculations
;; ============================================================

(defn- nearest-anchor
  "Find the anchor point (Vec2) nearest to the given position (Vec2).
   Returns the anchor point itself."
  [position anchors]
  (when (seq anchors)
    (reduce (fn [best anchor]
              (if (< (v/dist-sq position anchor)
                     (v/dist-sq position best))
                anchor
                best))
            (first anchors)
            (rest anchors))))

;; ============================================================
;; Default Values
;; ============================================================

(def defaults
  {:rate 0.998          ;; Apple normal deceleration
   :spring-stiffness 200.0
   :spring-damping 20.0})

;; ============================================================
;; Public API
;; ============================================================

(defn pip-flick
  "Calculate PIP-flick target and spring configuration.

   Given current position, velocity, and anchor points, determines:
   1. Where the flick would naturally land (projection)
   2. Which anchor is nearest to that projected position
   3. Spring configuration to animate there

   Arguments (map):
     :position - Vec2 current position
     :velocity - Vec2 current velocity (units/second)
     :anchors  - vector of Vec2 anchor points (e.g., corners)
     :rate     - (optional) decay rate for projection, default 0.998

   Returns:
     {:target Vec2           ;; chosen anchor point
      :projected Vec2        ;; where flick would have landed
      :velocity Vec2         ;; original velocity (for spring initial velocity)
      :spring-x {...}         ;; spring config for X axis
      :spring-y {...}}        ;; spring config for Y axis

   Example:
     (pip-flick {:position (v/vec2 100 200)
                 :velocity (v/vec2 800 -400)
                 :anchors [(v/vec2 50 50) (v/vec2 350 50) (v/vec2 50 550) (v/vec2 350 550)]})

   Sources:
     - WWDC 2018: Designing Fluid Interfaces
       https://developer.apple.com/videos/play/wwdc2018/803/"
  [{:keys [position velocity anchors rate]
    :or {rate (:rate defaults)}}]
  (let [[px py] position
        [vx vy] velocity

        ;; Project where the flick would land
        projection-2d (requiring-resolve 'lib.anim.projection-2d/projection-2d)
        projected (if projection-2d
                    (projection-2d position velocity rate)
                    position)

        ;; Find nearest anchor to projected position
        target (nearest-anchor projected anchors)
        [tx ty] target]

    {:target target
     :projected projected
     :velocity velocity
     ;; Spring configs for each axis
     :spring-x {:from px :to tx :velocity vx
                :stiffness (:spring-stiffness defaults)
                :damping (:spring-damping defaults)}
     :spring-y {:from py :to ty :velocity vy
                :stiffness (:spring-stiffness defaults)
                :damping (:spring-damping defaults)}}))

(defn corner-anchors
  "Generate corner anchor points for a rectangular area.
   Useful for PIP that snaps to screen corners.

   Arguments:
     width   - area width
     height  - area height
     padding - inset from edges (optional, default 20)

   Returns vector of 4 corner Vec2 points:
     [top-left top-right bottom-left bottom-right]

   Example:
     (corner-anchors 400 600)      ;; => [Vec2[20 20] Vec2[380 20] Vec2[20 580] Vec2[380 580]]
     (corner-anchors 400 600 50)   ;; => [Vec2[50 50] Vec2[350 50] Vec2[50 550] Vec2[350 550]]"
  ([width height] (corner-anchors width height 20))
  ([width height padding]
   [(v/vec2 (double padding) (double padding))                          ;; top-left
    (v/vec2 (double (- width padding)) (double padding))                ;; top-right
    (v/vec2 (double padding) (double (- height padding)))               ;; bottom-left
    (v/vec2 (double (- width padding)) (double (- height padding)))]))  ;; bottom-right

(defn edge-anchors
  "Generate edge center anchor points for a rectangular area.
   Useful for PIP that snaps to edge centers (like iOS notification banners).

   Arguments:
     width   - area width
     height  - area height
     padding - inset from edges (optional, default 20)

   Returns vector of 4 edge center Vec2 points:
     [center-top center-bottom left-center right-center]"
  ([width height] (edge-anchors width height 20))
  ([width height padding]
   (let [cx (/ width 2.0)
         cy (/ height 2.0)]
     [(v/vec2 cx (double padding))                      ;; top center
      (v/vec2 cx (double (- height padding)))           ;; bottom center
      (v/vec2 (double padding) cy)                      ;; left center
      (v/vec2 (double (- width padding)) cy)])))        ;; right center

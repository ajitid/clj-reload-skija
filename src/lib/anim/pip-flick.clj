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
     (def result (pip-flick {:position [100 200]
                             :velocity [500 -300]
                             :anchors [[0 0] [400 0] [0 600] [400 600]]}))
     ;; => {:target [400 0] :spring-config {...}}

   Sources:
     - WWDC 2018: Designing Fluid Interfaces
       https://developer.apple.com/videos/play/wwdc2018/803/
     - Ilya Lobanov: How UIScrollView works
       https://medium.com/@esskeetit/how-uiscrollview-works-e418adc47060")

;; ============================================================
;; Distance Calculations
;; ============================================================

(defn- distance-squared
  "Calculate squared distance between two 2D points.
   Using squared distance avoids sqrt for faster comparisons."
  [[x1 y1] [x2 y2]]
  (let [dx (- x2 x1)
        dy (- y2 y1)]
    (+ (* dx dx) (* dy dy))))

(defn- nearest-anchor
  "Find the anchor point nearest to the given position.
   Returns the anchor point itself."
  [position anchors]
  (when (seq anchors)
    (reduce (fn [best anchor]
              (if (< (distance-squared position anchor)
                     (distance-squared position best))
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
     :position - [x y] current position
     :velocity - [vx vy] current velocity (units/second)
     :anchors  - vector of [x y] anchor points (e.g., corners)
     :rate     - (optional) decay rate for projection, default 0.998

   Returns:
     {:target [x y]           ;; chosen anchor point
      :projected [x y]        ;; where flick would have landed
      :velocity [vx vy]       ;; original velocity (for spring initial velocity)
      :spring-x {...}         ;; spring config for X axis
      :spring-y {...}}        ;; spring config for Y axis

   Example:
     (pip-flick {:position [100 200]
                 :velocity [800 -400]
                 :anchors [[50 50] [350 50] [50 550] [350 550]]})

   Sources:
     - WWDC 2018: Designing Fluid Interfaces
       https://developer.apple.com/videos/play/wwdc2018/803/"
  [{:keys [position velocity anchors rate]
    :or {rate (:rate defaults)}}]
  (let [[px py] position
        [vx vy] velocity

        ;; Project where the flick would land
        projection-2d (requiring-resolve 'lib.anim.projection/projection-2d)
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

   Returns vector of 4 corner points:
     [[left top] [right top] [left bottom] [right bottom]]

   Example:
     (corner-anchors 400 600)      ;; => [[20 20] [380 20] [20 580] [380 580]]
     (corner-anchors 400 600 50)   ;; => [[50 50] [350 50] [50 550] [350 550]]"
  ([width height] (corner-anchors width height 20))
  ([width height padding]
   [[(double padding) (double padding)]                          ;; top-left
    [(double (- width padding)) (double padding)]                ;; top-right
    [(double padding) (double (- height padding))]               ;; bottom-left
    [(double (- width padding)) (double (- height padding))]]))  ;; bottom-right

(defn edge-anchors
  "Generate edge center anchor points for a rectangular area.
   Useful for PIP that snaps to edge centers (like iOS notification banners).

   Arguments:
     width   - area width
     height  - area height
     padding - inset from edges (optional, default 20)

   Returns vector of 4 edge center points:
     [[center-top] [center-bottom] [left-center] [right-center]]"
  ([width height] (edge-anchors width height 20))
  ([width height padding]
   (let [cx (/ width 2.0)
         cy (/ height 2.0)]
     [[cx (double padding)]                      ;; top center
      [cx (double (- height padding))]           ;; bottom center
      [(double padding) cy]                      ;; left center
      [(double (- width padding)) cy]])))        ;; right center

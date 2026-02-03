(ns lib.math
  "Supplementary math utilities for creative coding.
   Complements fastmath.core / fastmath.vector / fastmath.random.
   Follows fastmath naming conventions.

   NOTE: Not hot-reloadable (lib.* namespaces require restart).

   ## What fastmath already provides (use directly)

   | Function              | Namespace          |
   |-----------------------|--------------------|
   | norm, cnorm           | fastmath.core      |
   | constrain (clamp)     | fastmath.core      |
   | wrap                  | fastmath.core      |
   | lerp, smoothstep      | fastmath.core      |
   | radians, degrees      | fastmath.core      |
   | frac, sgn, sq         | fastmath.core      |
   | dist (scalar)         | fastmath.core      |
   | econstrain (2D clamp) | fastmath.vector    |
   | dist, dist-sq         | fastmath.vector    |
   | noise, random         | fastmath.random    |
   | Vec2/Vec3/Vec4        | fastmath.vector    |

   ## What this namespace adds

   Element-wise 2D operations that fastmath.vector doesn't provide:
   - `ewrap`  - element-wise wrap for Vec2
   - `enorm`  - element-wise norm/map-range for Vec2

   VVVV-style spread generators:
   - `linear-spread` - distribute n values evenly along a 1D line
   - `circle-spread` - distribute n points on a circle/ellipse
   - `rect-spread`   - distribute n×m points on a 2D grid"
  (:require [fastmath.core :as fm]
            [fastmath.vector :as v]))

(defn ewrap
  "Element-wise wrap for Vec2.
   Like fastmath.vector/econstrain but wraps around instead of clamping.

   Example:
     (ewrap (v/vec2 370 -10) (v/vec2 0 0) (v/vec2 360 360))  ;=> Vec2[10.0 350.0]"
  [point mn mx]
  (let [[px py] point
        [mn-x mn-y] mn
        [mx-x mx-y] mx]
    (v/vec2 (fm/wrap mn-x mx-x px)
            (fm/wrap mn-y mx-y py))))

(defn enorm
  "Element-wise norm (map-range) for Vec2.
   Maps each component from [start1..end1] to [start2..end2].

   Example:
     (enorm (v/vec2 50 75) (v/vec2 0 0) (v/vec2 100 100) (v/vec2 0 0) (v/vec2 1 1))  ;=> Vec2[0.5 0.75]"
  [point s1 e1 s2 e2]
  (let [[px py] point
        [s1x s1y] s1
        [e1x e1y] e1
        [s2x s2y] s2
        [e2x e2y] e2]
    (v/vec2 (fm/norm px s1x e1x s2x e2x)
            (fm/norm py s1y e1y s2y e2y))))

(defn linear-spread
  "VVVV LinearSpread — distributes `n` values evenly along a 1D line.

   Example:
     (linear-spread 5 100)                  ;=> [-40.0 -20.0 0.0 20.0 40.0]
     (linear-spread 5 100 {:center 200})    ;=> [160.0 180.0 200.0 220.0 240.0]
     (linear-spread 5 100 {:phase 0.1})     ;=> [-30.0 -10.0 10.0 30.0 50.0]"
  ([n width] (linear-spread n width {}))
  ([n width {:keys [phase center] :or {phase 0.0 center 0.0}}]
   (let [w (double width)
         c (double center)
         p (double phase)]
     (vec (for [i (range n)]
            (+ c (* (+ (- (/ (+ (double i) 0.5) n) 0.5)
                       (fm/wrap 0.0 1.0 p))
                    w)))))))

(defn circle-spread
  "VVVV CircularSpread — distributes `n` points on a circle or ellipse.

   Example:
     (circle-spread 4 100)                          ;=> 4 points at radius 100
     (circle-spread 8 100 {:phase 0.125})           ;=> rotated 45°
     (circle-spread 6 100 {:width 0.5})             ;=> half-circle arc
     (circle-spread 4 100 {:height 50})             ;=> ellipse, rx=100 ry=50"
  ([n radius] (circle-spread n radius {}))
  ([n radius {:keys [phase center width factor height]
              :or   {phase 0.0 center [0 0] width 1.0 factor 1.0}}]
   (let [r  (double radius)
         h  (double (or height radius))
         p  (double phase)
         w  (double width)
         f  (double factor)
         [cx cy] center
         cx (double cx)
         cy (double cy)]
     (vec (for [i (range n)]
            (let [angle (* fm/TWO_PI (+ p (* (/ (double i) n) f w)))]
              (v/vec2 (+ cx (* (Math/cos angle) r))
                      (+ cy (* (Math/sin angle) h)))))))))

(defn rect-spread
  "VVVV RectSpread — distributes `nx * ny` points on a 2D grid.
   Composes two linear spreads, row-major order.

   Example:
     (rect-spread 3 3 100 100)                      ;=> 9 points in 3×3 grid
     (rect-spread 4 3 200 150 {:phase-x 0.25})      ;=> shifted 25% on X"
  ([nx ny width-x width-y] (rect-spread nx ny width-x width-y {}))
  ([nx ny width-x width-y {:keys [phase-x phase-y center]
                            :or   {phase-x 0.0 phase-y 0.0 center [0 0]}}]
   (let [[cx cy] center
         xs (linear-spread nx width-x {:phase phase-x :center cx})
         ys (linear-spread ny width-y {:phase phase-y :center cy})]
     (vec (for [y ys x xs]
            (v/vec2 x y))))))

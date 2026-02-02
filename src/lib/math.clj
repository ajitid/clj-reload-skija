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
   - `enorm`  - element-wise norm/map-range for Vec2"
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

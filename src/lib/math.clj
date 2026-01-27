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
   - `ewrap`  - element-wise wrap for 2D points
   - `enorm`  - element-wise norm/map-range for 2D points"
  (:require [fastmath.core :as fm]))

(defn ewrap
  "Element-wise wrap for 2D points.
   Like fastmath.vector/econstrain but wraps around instead of clamping.

   Example:
     (ewrap [370 -10] [0 0] [360 360])  ;=> [10.0 350.0]"
  [[x y] [mnx mny] [mxx mxy]]
  [(fm/wrap mnx mxx x) (fm/wrap mny mxy y)])

(defn enorm
  "Element-wise norm (map-range) for 2D points.
   Maps each component from [start1..end1] to [start2..end2].

   Example:
     (enorm [50 75] [0 0] [100 100] [0 0] [1 1])  ;=> [0.5 0.75]"
  [[x y] [s1x s1y] [e1x e1y] [s2x s2y] [e2x e2y]]
  [(fm/norm x s1x e1x s2x e2x) (fm/norm y s1y e1y s2y e2y)])

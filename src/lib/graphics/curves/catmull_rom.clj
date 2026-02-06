(ns lib.graphics.curves.catmull-rom
  "Catmull-Rom spline (C1 centripetal)."
  (:require [fastmath.vector :as v]
            [lib.graphics.curves.common :as common])
  (:import [io.github.humbleui.skija PathBuilder]))

(defn catmull-rom
  "Create a C1-continuous Catmull-Rom spline through the given points.

   Uses centripetal parameterization (alpha=0.5) by default, which avoids
   cusps and self-intersections. Endpoints are extended by reflection.

   Args:
     points - vector of [x y] coordinate pairs (min 2)
     opts   - optional map:
              :alpha - parameterization exponent (default 0.5)
                       0.0 = uniform, 0.5 = centripetal, 1.0 = chordal

   Returns: Skija Path"
  ([points]
   (catmull-rom points {}))
  ([points {:keys [alpha] :or {alpha 0.5}}]
   (let [n (count points)]
     (cond
       (< n 2) (.build (PathBuilder.))
       (= n 2) (let [pb (PathBuilder.)
                      [x1 y1] (first points)
                      [x2 y2] (second points)]
                 (.moveTo pb (float x1) (float y1))
                 (.lineTo pb (float x2) (float y2))
                 (.build pb))
       :else
       (let [;; Extend endpoints by reflection
             p0 (first points)
             p1 (second points)
             pn-2 (nth points (- n 2))
             pn-1 (last points)
             ;; Reflect: ghost = 2*endpoint - next
             ghost-start [(- (* 2.0 (double (first p0))) (double (first p1)))
                          (- (* 2.0 (double (second p0))) (double (second p1)))]
             ghost-end [(- (* 2.0 (double (first pn-1))) (double (first pn-2)))
                        (- (* 2.0 (double (second pn-1))) (double (second pn-2)))]
             ;; Extended point array: [ghost, p0, p1, ..., pn-1, ghost]
             ext (vec (concat [ghost-start] points [ghost-end]))

             ;; Knot parameterization: t[i+1] = t[i] + v/dist(p[i], p[i+1])^alpha
             knot-t (fn ^double [p1 p2]
                      (let [d (v/dist p1 p2)]
                        (Math/pow (max d common/EPSILON) (double alpha))))

             ;; For each segment (p[i] to p[i+1]), use surrounding 4 points
             segments
             (mapv
              (fn [i]
                ;; ext indices: i, i+1, i+2, i+3 correspond to
                ;; the 4 surrounding points for segment from ext[i+1] to ext[i+2]
                (let [q0 (nth ext i)
                      q1 (nth ext (inc i))
                      q2 (nth ext (+ i 2))
                      q3 (nth ext (+ i 3))
                      ;; Knot intervals
                      t01 (knot-t q0 q1)
                      t12 (knot-t q1 q2)
                      t23 (knot-t q2 q3)
                      ;; Barry-Goldman tangents
                      [q0x q0y] (mapv double q0)
                      [q1x q1y] (mapv double q1)
                      [q2x q2y] (mapv double q2)
                      [q3x q3y] (mapv double q3)
                      dx12 (- q2x q1x)
                      dy12 (- q2y q1y)
                      t01+t12 (+ t01 t12)
                      t12+t23 (+ t12 t23)
                      m1x (+ dx12 (* t12 (- (/ (- q1x q0x) t01) (/ (- q2x q0x) t01+t12))))
                      m1y (+ dy12 (* t12 (- (/ (- q1y q0y) t01) (/ (- q2y q0y) t01+t12))))
                      m2x (+ dx12 (* t12 (- (/ (- q3x q2x) t23) (/ (- q3x q1x) t12+t23))))
                      m2y (+ dy12 (* t12 (- (/ (- q3y q2y) t23) (/ (- q3y q1y) t12+t23))))
                      ;; Convert to cubic Bezier control points
                      cp1x (+ q1x (/ m1x 3.0))
                      cp1y (+ q1y (/ m1y 3.0))
                      cp2x (- q2x (/ m2x 3.0))
                      cp2y (- q2y (/ m2y 3.0))]
                  [[q1x q1y] [cp1x cp1y] [cp2x cp2y] [q2x q2y]]))
              (range (dec n)))]
         (common/build-path segments))))))

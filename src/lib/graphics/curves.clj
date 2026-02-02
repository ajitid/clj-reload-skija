(ns lib.graphics.curves
  "Curve interpolation algorithms — Natural Cubic Spline, Hobby, Catmull-Rom.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern).

   ## Quick Start

   ```clojure
   (require '[lib.graphics.curves :as curves])

   ;; All take [[x y] ...] or [Vec2 ...] (min 2 points), return Skija Path
   (curves/natural-cubic-spline points)         ;; C2 continuous
   (curves/hobby-curve points)                   ;; G1, METAFONT-style
   (curves/hobby-curve points {:omega 0.0})      ;; with endpoint curl
   (curves/catmull-rom points)                   ;; C1 centripetal
   (curves/catmull-rom points {:alpha 0.5})      ;; with options
   ```"
  (:require [fastmath.vector :as v])
  (:import [io.github.humbleui.skija PathBuilder]))

;; ============================================================
;; Internal Helpers
;; ============================================================

(def ^:private EPSILON 1.0e-6)

(defn- build-path
  "Build a Skija Path from a sequence of cubic Bezier segments.
   Each segment is [p0 cp1 cp2 p1] where p0/p1 are endpoints
   and cp1/cp2 are control points."
  [segments]
  (let [pb (PathBuilder.)]
    (when (seq segments)
      (let [[[sx sy] _ _ _] (first segments)]
        (.moveTo pb (float sx) (float sy)))
      (doseq [[_ [c1x c1y] [c2x c2y] [ex ey]] segments]
        (.cubicTo pb (float c1x) (float c1y)
                  (float c2x) (float c2y)
                  (float ex) (float ey))))
    (.build pb)))

;; ============================================================
;; Natural Cubic Spline (C2 continuous)
;; ============================================================

(defn- solve-tridiagonal
  "Solve tridiagonal system using Thomas algorithm (O(n)).
   a = sub-diagonal, b = diagonal, c = super-diagonal, d = rhs.
   All are double-arrays of length n. Modifies c and d in place.
   Returns solution as double-array."
  [^doubles a ^doubles b ^doubles c ^doubles d]
  (let [n (alength d)
        ;; Forward sweep
        _ (loop [i 1]
            (when (< i n)
              (let [m (/ (aget a i) (aget b (dec i)))]
                (aset b i (- (aget b i) (* m (aget c (dec i)))))
                (aset d i (- (aget d i) (* m (aget d (dec i)))))
                (recur (inc i)))))
        ;; Back substitution
        x (double-array n)]
    (aset x (dec n) (/ (aget d (dec n)) (aget b (dec n))))
    (loop [i (- n 2)]
      (when (>= i 0)
        (aset x i (/ (- (aget d i) (* (aget c i) (aget x (inc i))))
                      (aget b i)))
        (recur (dec i))))
    x))

(defn natural-cubic-spline
  "Create a C2-continuous natural cubic spline through the given points.

   Uses the Thomas algorithm (O(n)) to solve the tridiagonal system
   for control points. Natural boundary conditions: S''=0 at endpoints.

   Args:
     points - vector of [x y] coordinate pairs (min 2)

   Returns: Skija Path"
  [points]
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
      (let [;; n points => n-1 segments, need n-1 pairs of control points
            m (dec n)
            ;; Extract x and y coordinates
            xs (double-array (mapv #(double (first %)) points))
            ys (double-array (mapv #(double (second %)) points))

            ;; Build tridiagonal system for P1 control points (x coords)
            ;; Natural spline: 2*P1[0] + P1[1] = K[0] + 2*K[1]
            ;; P1[i-1] + 4*P1[i] + P1[i+1] = 4*K[i] + 2*K[i+1]
            ;; 2*P1[m-2] + 7*P1[m-1] = 8*K[m-1] + K[m]
            ax (double-array m)
            bx (double-array m)
            cx (double-array m)
            dx (double-array m)

            ay (double-array m)
            by (double-array m)
            cy (double-array m)
            dy (double-array m)]

        ;; First row
        (aset ax 0 0.0)
        (aset bx 0 2.0)
        (aset cx 0 1.0)
        (aset dx 0 (+ (aget xs 0) (* 2.0 (aget xs 1))))
        (aset ay 0 0.0)
        (aset by 0 2.0)
        (aset cy 0 1.0)
        (aset dy 0 (+ (aget ys 0) (* 2.0 (aget ys 1))))

        ;; Middle rows
        (dotimes [i (- m 2)]
          (let [j (inc i)]
            (aset ax j 1.0)
            (aset bx j 4.0)
            (aset cx j 1.0)
            (aset dx j (+ (* 4.0 (aget xs j)) (* 2.0 (aget xs (inc j)))))
            (aset ay j 1.0)
            (aset by j 4.0)
            (aset cy j 1.0)
            (aset dy j (+ (* 4.0 (aget ys j)) (* 2.0 (aget ys (inc j)))))))

        ;; Last row
        (aset ax (dec m) 2.0)
        (aset bx (dec m) 7.0)
        (aset cx (dec m) 0.0)
        (aset dx (dec m) (+ (* 8.0 (aget xs (dec m))) (aget xs m)))
        (aset ay (dec m) 2.0)
        (aset by (dec m) 7.0)
        (aset cy (dec m) 0.0)
        (aset dy (dec m) (+ (* 8.0 (aget ys (dec m))) (aget ys m)))

        ;; Solve for P1 control points
        (let [p1x (solve-tridiagonal ax bx cx dx)
              p1y (solve-tridiagonal ay by cy dy)
              ;; Derive P2 from P1: P2[i] = 2*K[i+1] - P1[i+1]
              ;; Last: P2[m-1] = (K[m] + P1[m-1]) / 2
              segments (mapv
                        (fn [i]
                          (let [p2x (if (< i (dec m))
                                      (- (* 2.0 (aget xs (inc i))) (aget p1x (inc i)))
                                      (/ (+ (aget xs m) (aget p1x (dec m))) 2.0))
                                p2y (if (< i (dec m))
                                      (- (* 2.0 (aget ys (inc i))) (aget p1y (inc i)))
                                      (/ (+ (aget ys m) (aget p1y (dec m))) 2.0))]
                            [(nth points i)
                             [(aget p1x i) (aget p1y i)]
                             [p2x p2y]
                             (nth points (inc i))]))
                        (range m))]
          (build-path segments))))))

;; ============================================================
;; Hobby Curve (G1 / METAFONT-style)
;; ============================================================
;; Based on Jake Low's implementation (ISC License):
;; https://www.jakelow.com/blog/hobby-curves/hobby.js
;; Which follows Jackowski's paper (TUGboat vol. 34, 2013).

(defn- hobby-rho
  "Velocity function (Jackowski formula 28).
   Computes handle length relative to chord length."
  ^double [^double alpha ^double beta]
  (/ 2.0
     (+ 1.0
        (* (/ 2.0 3.0) (Math/cos beta))
        (* (/ 1.0 3.0) (Math/cos alpha)))))

(defn- angle-between
  "Signed angle from 2D vector a to vector b (radians).
   Equivalent to atan2(a×b, a·b)."
  ^double [[^double ax ^double ay] [^double bx ^double by]]
  (Math/atan2 (- (* ax by) (* ay bx))
              (+ (* ax bx) (* ay by))))

(defn hobby-curve
  "Create a G1-continuous curve using Hobby's algorithm (METAFONT).

   Produces aesthetically pleasing curves with rounded shapes.
   Based on Jackowski's formulation of the Hobby/Knuth algorithm.

   Args:
     points - vector of [x y] coordinate pairs (min 2)
     opts   - optional map:
              :omega - endpoint curl, 0 to 1 (default 0.0)

   Returns: Skija Path"
  ([points]
   (hobby-curve points {}))
  ([points {:keys [omega] :or {omega 0.0}}]
   (let [num-pts (count points)]
     (cond
       (< num-pts 2) (.build (PathBuilder.))
       (= num-pts 2) (let [pb (PathBuilder.)
                            [x1 y1] (first points)
                            [x2 y2] (second points)]
                       (.moveTo pb (float x1) (float y1))
                       (.lineTo pb (float x2) (float y2))
                       (.build pb))
       :else
       ;; n is defined so points are P[0]..P[n] (n+1 points total)
       (let [n (dec num-pts)
             ;; Chord vectors and lengths
             chords (mapv (fn [i]
                            (let [[x1 y1] (nth points i)
                                  [x2 y2] (nth points (inc i))]
                              [(- (double x2) (double x1))
                               (- (double y2) (double y1))]))
                          (range n))
             d (double-array (mapv (fn [[cx cy]]
                                     (Math/sqrt (+ (* (double cx) (double cx))
                                                   (* (double cy) (double cy)))))
                                   chords))

             ;; gamma[i] = signed turning angle at P[i]
             ;; gamma[0] is unused, gamma[n] = 0
             gamma (double-array (inc n))
             _ (do
                 (dotimes [i (dec n)]
                   (let [i+1 (inc i)]
                     (aset gamma i+1 (angle-between (nth chords i)
                                                     (nth chords i+1)))))
                 (aset gamma n 0.0))

             ;; Set up tridiagonal system (Jackowski formula 38)
             ;; System size: n+1 equations (indices 0 to n)
             sz (inc n)
             a-arr (double-array sz)
             b-arr (double-array sz)
             c-arr (double-array sz)
             d-arr (double-array sz)]

         ;; Row 0: endpoint condition
         (aset b-arr 0 (+ 2.0 omega))
         (aset c-arr 0 (+ (* 2.0 omega) 1.0))
         (aset d-arr 0 (* -1.0 (+ (* 2.0 omega) 1.0) (aget gamma 1)))

         ;; Interior rows 1..n-1
         (dotimes [j (dec n)]
           (let [i (inc j)
                 d-prev (aget d (dec i))
                 d-next (aget d i)]
             (aset a-arr i (/ 1.0 d-prev))
             (aset b-arr i (/ (+ (* 2.0 d-prev) (* 2.0 d-next))
                               (* d-prev d-next)))
             (aset c-arr i (/ 1.0 d-next))
             (aset d-arr i (/ (* -1.0
                                 (+ (* 2.0 (aget gamma i) d-next)
                                    (* (aget gamma (min (inc i) n)) d-prev)))
                               (* d-prev d-next)))))

         ;; Row n: endpoint condition
         (aset a-arr n (+ (* 2.0 omega) 1.0))
         (aset b-arr n (+ 2.0 omega))
         (aset d-arr n 0.0)

         ;; Solve with Thomas algorithm for alpha angles
         (let [alpha (solve-tridiagonal a-arr b-arr c-arr d-arr)

               ;; Compute beta from alpha and gamma:
               ;; beta[i] = -gamma[i+1] - alpha[i+1]  for i = 0..n-2
               ;; beta[n-1] = -alpha[n]
               beta (double-array n)
               _ (do
                   (dotimes [i (dec n)]
                     (aset beta i (- (- (aget gamma (inc i)))
                                     (aget alpha (inc i)))))
                   (aset beta (dec n) (- (aget alpha n))))

               ;; Compute control points using rho velocity function
               segments
               (mapv
                (fn [i]
                  (let [[cx cy] (nth chords i)
                        di (aget d i)
                        ;; Handle magnitudes (Jackowski formula 22)
                        a-len (/ (* (hobby-rho (aget alpha i) (aget beta i)) di) 3.0)
                        b-len (/ (* (hobby-rho (aget beta i) (aget alpha i)) di) 3.0)
                        ;; Rotate chord vector by alpha[i], normalize, scale
                        ;; rotate(v, angle) = [vx*cos - vy*sin, vx*sin + vy*cos]
                        alpha-i (aget alpha i)
                        cos-a (Math/cos alpha-i)
                        sin-a (Math/sin alpha-i)
                        ;; Rotated + normalized chord direction for c0
                        rx0 (- (* (double cx) cos-a) (* (double cy) sin-a))
                        ry0 (+ (* (double cx) sin-a) (* (double cy) cos-a))
                        inv-len0 (/ 1.0 (max (Math/sqrt (+ (* rx0 rx0) (* ry0 ry0))) EPSILON))
                        ;; c0[i] = point[i] + a_len * normalize(rotate(chord, alpha))
                        [px py] (nth points i)
                        c0x (+ (double px) (* a-len rx0 inv-len0))
                        c0y (+ (double py) (* a-len ry0 inv-len0))
                        ;; Rotate chord by -beta[i] for c1
                        beta-i (aget beta i)
                        cos-b (Math/cos (- beta-i))
                        sin-b (Math/sin (- beta-i))
                        rx1 (- (* (double cx) cos-b) (* (double cy) sin-b))
                        ry1 (+ (* (double cx) sin-b) (* (double cy) cos-b))
                        inv-len1 (/ 1.0 (max (Math/sqrt (+ (* rx1 rx1) (* ry1 ry1))) EPSILON))
                        ;; c1[i] = point[i+1] - b_len * normalize(rotate(chord, -beta))
                        [qx qy] (nth points (inc i))
                        c1x (- (double qx) (* b-len rx1 inv-len1))
                        c1y (- (double qy) (* b-len ry1 inv-len1))]
                    [(nth points i) [c0x c0y] [c1x c1y] (nth points (inc i))]))
                (range n))]
           (build-path segments)))))))


;; ============================================================
;; Catmull-Rom (C1 centripetal)
;; ============================================================

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
                        (Math/pow (max d EPSILON) (double alpha))))

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
                      ;; m1 = (q2 - q1) + t12 * ((q1 - q0)/t01 - (q2 - q0)/(t01 + t12))
                      ;; m2 = (q2 - q1) + t12 * ((q3 - q2)/t23 - (q3 - q1)/(t12 + t23))
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
                      ;; cp1 = q1 + m1/3, cp2 = q2 - m2/3
                      cp1x (+ q1x (/ m1x 3.0))
                      cp1y (+ q1y (/ m1y 3.0))
                      cp2x (- q2x (/ m2x 3.0))
                      cp2y (- q2y (/ m2y 3.0))]
                  [[q1x q1y] [cp1x cp1y] [cp2x cp2y] [q2x q2y]]))
              (range (dec n)))]
         (build-path segments))))))

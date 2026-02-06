(ns lib.graphics.curves
  "Curve interpolation algorithms — Natural Cubic Spline, Hobby, Catmull-Rom.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern).

   ## Quick Start

   ```clojure
   (require '[lib.graphics.curves :as curves])

   ;; All take [[x y] ...] or [Vec2 ...] (min 2 points), return Skija Path
   (curves/natural-cubic-spline points)         ;; C2 continuous
   (curves/hobby-curve points)                   ;; G1, Hobby's algorithm
   (curves/hobby-curve points {:curl 0.0})       ;; with endpoint curl
   (curves/hobby-curve points {:closed true})    ;; closed loop
   (curves/hobby-curve points {:tensions (fn [i in-deg out-deg] [1.5 1.5])})
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
   and cp1/cp2 are control points. When closed? is true, closes the path."
  ([segments]
   (build-path segments false))
  ([segments closed?]
   (let [pb (PathBuilder.)]
     (when (seq segments)
       (let [[[sx sy] _ _ _] (first segments)]
         (.moveTo pb (float sx) (float sy)))
       (doseq [[_ [c1x c1y] [c2x c2y] [ex ey]] segments]
         (.cubicTo pb (float c1x) (float c1y)
                   (float c2x) (float c2y)
                   (float ex) (float ey)))
       (when closed?
         (.closePath pb)))
     (.build pb))))

;; ============================================================
;; Natural Cubic Spline (C2 continuous)
;; ============================================================

(defn- solve-tridiagonal
  "Solve tridiagonal system using Thomas algorithm (O(n)).
   a = sub-diagonal, b = diagonal, c = super-diagonal, d = rhs.
   All are double-arrays of length n. Non-destructive (allocates work arrays).
   Returns solution as double-array."
  [^doubles a ^doubles b ^doubles c ^doubles d]
  (let [n (alength d)
        ;; Work copies so callers' arrays are not mutated
        bb (java.util.Arrays/copyOf b n)
        dd (java.util.Arrays/copyOf d n)
        ;; Forward sweep
        _ (loop [i 1]
            (when (< i n)
              (let [m (/ (aget a i) (aget bb (dec i)))]
                (aset bb i (- (aget bb i) (* m (aget c (dec i)))))
                (aset dd i (- (aget dd i) (* m (aget dd (dec i)))))
                (recur (inc i)))))
        ;; Back substitution
        x (double-array n)]
    (aset x (dec n) (/ (aget dd (dec n)) (aget bb (dec n))))
    (loop [i (- n 2)]
      (when (>= i 0)
        (aset x i (/ (- (aget dd i) (* (aget c i) (aget x (inc i))))
                      (aget bb i)))
        (recur (dec i))))
    x))

(defn- solve-sherman-morrison
  "Solve cyclic tridiagonal system via Sherman-Morrison.
   a, b, c, d are the standard tridiagonal arrays of length n.
   s = wrap element at bottom-left (a[0] wrapping), t = wrap element at top-right (c[n-1] wrapping).
   Returns solution as double-array."
  [^doubles a ^doubles b ^doubles c ^doubles d s t]
  (let [s (double s)
        t (double t)
        n (alength d)
        ;; Modified diagonal: b'[0] -= t, b'[n-1] -= s
        b' (java.util.Arrays/copyOf b n)
        _ (aset b' 0 (- (aget b' 0) t))
        _ (aset b' (dec n) (- (aget b' (dec n)) s))
        ;; Solve A' x1 = d
        x1 (solve-tridiagonal a b' c d)
        ;; Build correction rhs: u[0] = t, u[n-1] = s, rest 0
        u (double-array n)
        _ (do (aset u 0 1.0) (aset u (dec n) 1.0))
        ;; Solve A' x2 = u
        x2 (solve-tridiagonal a b' c u)
        ;; Sherman-Morrison combination:
        ;; x = x1 - ((t*x1[0] + s*x1[n-1]) / (1 + t*x2[0] + s*x2[n-1])) * x2
        factor (/ (+ (* t (aget x1 0)) (* s (aget x1 (dec n))))
                  (+ 1.0 (* t (aget x2 0)) (* s (aget x2 (dec n)))))
        x (double-array n)]
    (dotimes [i n]
      (aset x i (- (aget x1 i) (* factor (aget x2 i)))))
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
;; Hobby Curve (G1)
;; ============================================================
;; Based on Jake Low's implementation (ISC License):
;; https://www.jakelow.com/blog/hobby-curves/hobby.js
;; Which follows Jackowski's paper (TUGboat vol. 34, 2013).

(defn- hobby-rho
  "Velocity function (Knuth/Hobby formula).
   Computes handle length relative to chord length."
  ^double [^double alpha ^double beta]
  (let [sa (Math/sin alpha) sb (Math/sin beta)
        ca (Math/cos alpha) cb (Math/cos beta)
        s5 (Math/sqrt 5.0)
        num (+ 4.0 (* (Math/sqrt 8.0) (- sa (/ sb 16.0)) (- sb (/ sa 16.0)) (- ca cb)))
        den (+ 2.0 (* (- s5 1.0) ca) (* (- 3.0 s5) cb))]
    (/ num den)))

(defn- angle-between
  "Signed angle from 2D vector a to vector b (radians).
   Equivalent to atan2(a×b, a·b)."
  ^double [[^double ax ^double ay] [^double bx ^double by]]
  (Math/atan2 (- (* ax by) (* ay bx))
              (+ (* ax bx) (* ay by))))

;; Tension: post-solve handle scaling (openrndr convention).
;; Angles are always solved at tension=1. The :tensions callback returns
;; [t1 t2] per segment; handle lengths are multiplied by t.
;; Values > 1 = longer handles (looser), < 1 = shorter (tighter).
;; At default [1.0 1.0] no scaling is applied.
;;
;; References:
;; - openrndr/orx HobbyCurve.kt
;; - Hobby's original paper: "Smooth, Easy to Compute Interpolating
;;   Splines" (John D. Hobby, 1986)

(defn- hobby-segments
  "Compute cubic Bezier segments from solved alpha/beta angles, chords, and chord lengths.
   m = number of chords, gamma-arr = turning angles, tensions = optional callback.
   When closed? is true, segment endpoints wrap around."
  [points ^doubles alpha ^doubles beta ^doubles chord-lens chords
   m ^doubles gamma-arr tensions closed?]
  (mapv
   (fn [i]
     (let [[cx cy] (nth chords i)
           di (aget chord-lens i)
           ai (aget alpha i)
           bi (aget beta i)
           ;; Per-segment tension override
           [t1 t2] (if tensions
                     (tensions i
                               (Math/toDegrees (aget gamma-arr (long i)))
                               (Math/toDegrees (aget gamma-arr (mod (inc i) m))))
                     [1.0 1.0])
           ;; Handle magnitudes
           a-len (* (hobby-rho ai bi) (double t1) (/ di 3.0))
           b-len (* (hobby-rho bi ai) (double t2) (/ di 3.0))
           ;; Rotate chord by alpha for outgoing control point
           cos-a (Math/cos ai) sin-a (Math/sin ai)
           rx0 (- (* (double cx) cos-a) (* (double cy) sin-a))
           ry0 (+ (* (double cx) sin-a) (* (double cy) cos-a))
           inv-len0 (/ 1.0 (max (Math/sqrt (+ (* rx0 rx0) (* ry0 ry0))) EPSILON))
           [px py] (nth points i)
           c0x (+ (double px) (* a-len rx0 inv-len0))
           c0y (+ (double py) (* a-len ry0 inv-len0))
           ;; Rotate chord by -beta for incoming control point
           cos-b (Math/cos (- bi)) sin-b (Math/sin (- bi))
           rx1 (- (* (double cx) cos-b) (* (double cy) sin-b))
           ry1 (+ (* (double cx) sin-b) (* (double cy) cos-b))
           inv-len1 (/ 1.0 (max (Math/sqrt (+ (* rx1 rx1) (* ry1 ry1))) EPSILON))
           end-idx (if closed? (mod (inc i) (count points)) (inc i))
           [qx qy] (nth points end-idx)
           c1x (- (double qx) (* b-len rx1 inv-len1))
           c1y (- (double qy) (* b-len ry1 inv-len1))]
       [(nth points i) [c0x c0y] [c1x c1y] (nth points end-idx)]))
   (range m)))

(defn- hobby-open
  "Open Hobby curve solver. Returns segments."
  [points n chords ^doubles chord-lens ^doubles gamma-arr curl tensions]
  ;; System size: n+1 equations (indices 0 to n)
  (let [sz (inc n)
        a-arr (double-array sz)
        b-arr (double-array sz)
        c-arr (double-array sz)
        d-arr (double-array sz)]
    ;; Row 0: endpoint condition
    (aset b-arr 0 (+ 2.0 (double curl)))
    (aset c-arr 0 (+ (* 2.0 (double curl)) 1.0))
    (aset d-arr 0 (* -1.0 (+ (* 2.0 (double curl)) 1.0) (aget gamma-arr 1)))
    ;; Interior rows 1..n-1
    (dotimes [j (dec n)]
      (let [i (inc j)
            d-prev (aget chord-lens (dec i))
            d-next (aget chord-lens i)]
        (aset a-arr i (/ 1.0 d-prev))
        (aset b-arr i (/ (+ (* 2.0 d-prev) (* 2.0 d-next))
                          (* d-prev d-next)))
        (aset c-arr i (/ 1.0 d-next))
        (aset d-arr i (/ (* -1.0
                            (+ (* 2.0 (aget gamma-arr i) d-next)
                               (* (aget gamma-arr (min (inc i) n)) d-prev)))
                          (* d-prev d-next)))))
    ;; Row n: endpoint condition
    (aset a-arr n (+ (* 2.0 (double curl)) 1.0))
    (aset b-arr n (+ 2.0 (double curl)))
    (aset d-arr n 0.0)
    ;; Solve for alpha
    (let [alpha (solve-tridiagonal a-arr b-arr c-arr d-arr)
          beta (double-array n)]
      (dotimes [i (dec n)]
        (aset beta i (- (- (aget gamma-arr (inc i)))
                        (aget alpha (inc i)))))
      (aset beta (dec n) (- (aget alpha n)))
      (hobby-segments points alpha beta chord-lens chords n gamma-arr tensions false))))

(defn- hobby-closed
  "Closed Hobby curve solver. Returns segments."
  [points m chords ^doubles chord-lens ^doubles gamma-arr tensions]
  ;; All m rows are interior-style; system wraps with Sherman-Morrison
  (let [a-arr (double-array m)
        b-arr (double-array m)
        c-arr (double-array m)
        d-arr (double-array m)]
    (dotimes [i m]
      (let [d-prev (aget chord-lens (mod (dec (+ i m)) m))
            d-next (aget chord-lens i)
            g-curr (aget gamma-arr i)
            g-next (aget gamma-arr (mod (inc i) m))]
        (aset a-arr i (/ 1.0 d-prev))
        (aset b-arr i (/ (+ (* 2.0 d-prev) (* 2.0 d-next))
                          (* d-prev d-next)))
        (aset c-arr i (/ 1.0 d-next))
        (aset d-arr i (/ (* -1.0 (+ (* 2.0 g-curr d-next) (* g-next d-prev)))
                          (* d-prev d-next)))))
    ;; Extract wrap elements before zeroing them
    (let [s-val (aget a-arr 0)
          t-val (aget c-arr (dec m))]
      (aset a-arr 0 0.0)
      (aset c-arr (dec m) 0.0)
      (let [alpha (solve-sherman-morrison a-arr b-arr c-arr d-arr s-val t-val)
            beta (double-array m)]
        (dotimes [i m]
          (aset beta i (- (- (aget gamma-arr (mod (inc i) m)))
                          (aget alpha (mod (inc i) m)))))
        (hobby-segments points alpha beta chord-lens chords m gamma-arr tensions true)))))

(defn hobby-curve
  "Create a G1-continuous curve using Hobby's algorithm.

   Produces aesthetically pleasing curves with rounded shapes.

   Args:
     points - vector of [x y] coordinate pairs (min 2 for open, min 3 for closed)
     opts   - optional map:
              :curl     - endpoint curl, 0 to 1 (default 0.0, open only)
              :closed   - if true, curve closes back to first point (default false)
              :tensions - (fn [chord-idx in-angle-deg out-angle-deg] [t1 t2])
                          Per-segment handle scale factors (default nil = [1.0 1.0]).
                          Values > 1 = longer handles (looser), < 1 = shorter (tighter).

   Returns: Skija Path"
  ([points]
   (hobby-curve points {}))
  ([points {:keys [curl closed tensions]
            :or {curl 0.0 closed false}}]
   (let [num-pts (count points)]
     (cond
       (< num-pts 2) (.build (PathBuilder.))
       (= num-pts 2) (if closed
                       (.build (PathBuilder.))
                       (let [pb (PathBuilder.)
                             [x1 y1] (first points)
                             [x2 y2] (second points)]
                         (.moveTo pb (float x1) (float y1))
                         (.lineTo pb (float x2) (float y2))
                         (.build pb)))
       :else
       (if closed
         ;; Closed curve: m = num-pts chords, wrapping
         (let [m num-pts
               chords (mapv (fn [i]
                              (let [[x1 y1] (nth points i)
                                    [x2 y2] (nth points (mod (inc i) m))]
                                [(- (double x2) (double x1))
                                 (- (double y2) (double y1))]))
                            (range m))
               chord-lens (double-array (mapv (fn [[cx cy]]
                                                (Math/sqrt (+ (* (double cx) (double cx))
                                                              (* (double cy) (double cy)))))
                                              chords))
               gamma-arr (double-array m)
               _ (dotimes [i m]
                   (aset gamma-arr i (angle-between (nth chords (mod (dec (+ i m)) m))
                                                     (nth chords i))))]
           (build-path (hobby-closed points m chords chord-lens gamma-arr tensions) true))
         ;; Open curve: n = num-pts - 1 chords
         (let [n (dec num-pts)
               chords (mapv (fn [i]
                              (let [[x1 y1] (nth points i)
                                    [x2 y2] (nth points (inc i))]
                                [(- (double x2) (double x1))
                                 (- (double y2) (double y1))]))
                            (range n))
               chord-lens (double-array (mapv (fn [[cx cy]]
                                                (Math/sqrt (+ (* (double cx) (double cx))
                                                              (* (double cy) (double cy)))))
                                              chords))
               ;; gamma[i] = turning angle; gamma[0] unused, gamma[n] = 0
               gamma-arr (double-array (inc n))
               _ (do
                   (dotimes [i (dec n)]
                     (aset gamma-arr (inc i) (angle-between (nth chords i)
                                                             (nth chords (inc i)))))
                   (aset gamma-arr n 0.0))]
           (build-path (hobby-open points n chords chord-lens gamma-arr curl tensions))))))))


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

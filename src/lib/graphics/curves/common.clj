(ns lib.graphics.curves.common
  "Shared helpers for curve algorithms — epsilon, path builder, tridiagonal solvers."
  (:import [io.github.humbleui.skija PathBuilder]))

(def EPSILON 1.0e-6)

(defn build-path
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

(defn solve-tridiagonal
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

(defn solve-sherman-morrison
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

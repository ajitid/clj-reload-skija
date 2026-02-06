(ns lib.graphics.curves.spline
  "Natural Cubic Spline (C2 continuous)."
  (:require [lib.graphics.curves.common :as common])
  (:import [io.github.humbleui.skija PathBuilder]))

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
        (let [p1x (common/solve-tridiagonal ax bx cx dx)
              p1y (common/solve-tridiagonal ay by cy dy)
              ;; Derive P2 from P1
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
          (common/build-path segments))))))

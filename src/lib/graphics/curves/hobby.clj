(ns lib.graphics.curves.hobby
  "Hobby Curve (G1) — G1-continuous curve using Hobby's algorithm.

   Based on Jackowski's 2013 TUGboat paper 'Typographers, programmers and
   mathematicians, or the case of an æsthetically pleasing interpolation'.
   Implementation matches OPENRNDR (https://github.com/openrndr/orx).

   Uses the complex rho formula (with sqrt(5)) and supports per-segment
   asymmetric tension via optional callback (METAFONT-style feature)."
  (:require [lib.graphics.curves.common :as common])
  (:import [io.github.humbleui.skija PathBuilder]))

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
           inv-len0 (/ 1.0 (max (Math/sqrt (+ (* rx0 rx0) (* ry0 ry0))) common/EPSILON))
           [px py] (nth points i)
           c0x (+ (double px) (* a-len rx0 inv-len0))
           c0y (+ (double py) (* a-len ry0 inv-len0))
           ;; Rotate chord by -beta for incoming control point
           cos-b (Math/cos (- bi)) sin-b (Math/sin (- bi))
           rx1 (- (* (double cx) cos-b) (* (double cy) sin-b))
           ry1 (+ (* (double cx) sin-b) (* (double cy) cos-b))
           inv-len1 (/ 1.0 (max (Math/sqrt (+ (* rx1 rx1) (* ry1 ry1))) common/EPSILON))
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
    (let [alpha (common/solve-tridiagonal a-arr b-arr c-arr d-arr)
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
      (let [alpha (common/solve-sherman-morrison a-arr b-arr c-arr d-arr s-val t-val)
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
           (common/build-path (hobby-closed points m chords chord-lens gamma-arr tensions) true))
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
           (common/build-path (hobby-open points n chords chord-lens gamma-arr curl tensions))))))))

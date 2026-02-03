(ns lib.graphics.transform
  "Canvas transformation system - scoped transforms, matrix builders, perspective.

   NOTE: Not hot-reloadable (lib.* namespaces require restart).

   ## Quick Start

   ```clojure
   ;; Scoped transform (bare save/restore)
   (xf/with-transform [canvas]
     (.translate canvas 100 100)
     (.rotate canvas 45)
     (shapes/circle canvas 0 0 50 {:color [1 0 0 1]}))

   ;; Declarative transforms
   (xf/with-transform [canvas {:translate [100 100]
                                :rotate 45
                                :scale [2 1]}]
     (shapes/circle canvas 0 0 50 {:color [1 0 0 1]}))

   ;; Pre-built matrix
   (xf/with-transform [canvas (xf/rotate-matrix 45)]
     (shapes/circle canvas 0 0 50 {:color [1 0 0 1]}))

   ;; Perspective
   (xf/with-transform [canvas (xf/perspective-matrix 0.001 0)]
     (draw-3d-stuff...))
   ```"
  (:import [io.github.humbleui.skija Canvas Matrix33 Matrix44]))

;; ============================================================
;; Matrix33 Builders (2D affine transforms)
;; ============================================================

(defn translate-matrix
  "Create a 2D translation Matrix33."
  [dx dy]
  (Matrix33/makeTranslate (float dx) (float dy)))

(defn scale-matrix
  "Create a 2D scale Matrix33."
  [sx sy]
  (Matrix33/makeScale (float sx) (float sy)))

(defn rotate-matrix
  "Create a 2D rotation Matrix33 (degrees).
   Optional pivot point (px, py) for rotation center."
  ([deg]
   (Matrix33/makeRotate (float deg)))
  ([deg px py]
   (let [t1 (Matrix33/makeTranslate (float px) (float py))
         r  (Matrix33/makeRotate (float deg))
         t2 (Matrix33/makeTranslate (float (- (double px))) (float (- (double py))))]
     (.makeConcat (.makeConcat t1 r) t2))))

(defn skew-matrix
  "Create a 2D skew/shear Matrix33."
  [sx sy]
  (Matrix33. (float-array [1 sx 0
                           sy 1 0
                           0 0 1])))

(defn compose-matrix
  "Compose two or more Matrix33 transforms.
   Application order: left to right (first arg applied first)."
  ([^Matrix33 m1 ^Matrix33 m2]
   (.makeConcat m1 m2))
  ([m1 m2 & more]
   (reduce compose-matrix (compose-matrix m1 m2) more)))

;; ============================================================
;; FitBox — map src rect into dst rect with fit mode
;; ============================================================

(defn- apply-box-fit
  "Given a fit mode and input/output sizes, return adjusted {:src-size :dst-size}.
   Sizes are [w h] pairs. Ported from React Native Skia's Fitting.ts."
  [fit [iw ih] [ow oh]]
  (let [iw (double iw) ih (double ih)
        ow (double ow) oh (double oh)]
    (case fit
      :fill
      {:src-size [iw ih] :dst-size [ow oh]}

      :contain
      (if (> (/ ow oh) (/ iw ih))
        {:src-size [iw ih] :dst-size [(* oh (/ iw ih)) oh]}
        {:src-size [iw ih] :dst-size [ow (* ow (/ ih iw))]})

      :cover
      (if (< (/ ow oh) (/ iw ih))
        {:src-size [iw ih] :dst-size [(* oh (/ iw ih)) oh]}
        {:src-size [iw ih] :dst-size [ow (* ow (/ ih iw))]})

      :fit-width
      {:src-size [iw ih] :dst-size [ow (* ow (/ ih iw))]}

      :fit-height
      {:src-size [iw ih] :dst-size [(* oh (/ iw ih)) oh]}

      :none
      {:src-size [iw ih] :dst-size [iw ih]}

      :scale-down
      (if (or (> iw ow) (> ih oh))
        (recur :contain [iw ih] [ow oh])
        {:src-size [iw ih] :dst-size [iw ih]}))))

(defn- inscribe
  "Center a size [w h] within a rect {:x :y :w :h}. Returns {:x :y :w :h}."
  [[sw sh] {:keys [x y w h]}]
  (let [hw (* 0.5 (- (double w) (double sw)))
        hh (* 0.5 (- (double h) (double sh)))]
    {:x (+ (double x) hw) :y (+ (double y) hh) :w (double sw) :h (double sh)}))

(defn- rect2rect
  "Compute a Matrix33 that maps adjusted-src rect into adjusted-dst rect."
  [src dst]
  (let [sx (/ (:w dst) (:w src))
        sy (/ (:h dst) (:h src))
        tx (- (:x dst) (* (:x src) sx))
        ty (- (:y dst) (* (:y src) sy))]
    (compose-matrix
      (translate-matrix tx ty)
      (scale-matrix sx sy))))

(defn fitbox
  "Compute a Matrix33 that maps src rect into dst rect using the given fit mode.

   Rects are maps: {:x :y :w :h}

   Fit modes: :contain (default), :cover, :fill, :fit-width, :fit-height, :scale-down, :none

   Usage with with-transform:
     (with-transform [canvas (fitbox :contain {:x 0 :y 0 :w 200 :h 200}
                                               {:x 50 :y 50 :w 80 :h 80})]
       ;; draw as if canvas is 200x200
       ...)"
  ([src dst] (fitbox :contain src dst))
  ([fit src dst]
   (let [{:keys [src-size dst-size]}
         (apply-box-fit fit [(:w src) (:h src)] [(:w dst) (:h dst)])
         adj-src (inscribe src-size src)
         adj-dst (inscribe dst-size dst)]
     (rect2rect adj-src adj-dst))))

;; ============================================================
;; Matrix44 Builders (3D / perspective transforms)
;; ============================================================

(defn matrix44
  "Create a Matrix44 from 16 floats (row-major order).

   Example:
     (matrix44 1 0 0 0  0 1 0 0  0 0 1 0  0 0 0 1)  ;; identity"
  [m00 m01 m02 m03
   m10 m11 m12 m13
   m20 m21 m22 m23
   m30 m31 m32 m33]
  (Matrix44. (float-array [m00 m01 m02 m03
                           m10 m11 m12 m13
                           m20 m21 m22 m23
                           m30 m31 m32 m33])))

(defn translate-matrix-44
  "Create a 4x4 translation matrix."
  [dx dy dz]
  (matrix44 1 0 0 dx
            0 1 0 dy
            0 0 1 dz
            0 0 0 1))

(defn scale-matrix-44
  "Create a 4x4 scale matrix."
  [sx sy sz]
  (matrix44 sx 0  0  0
            0  sy 0  0
            0  0  sz 0
            0  0  0  1))

(defn rotate-x-matrix-44
  "Create a 4x4 rotation matrix around the X axis (degrees)."
  [deg]
  (let [r (Math/toRadians (double deg))
        c (Math/cos r)
        s (Math/sin r)]
    (matrix44 1 0    0     0
              0 c    (- s) 0
              0 s    c     0
              0 0    0     1)))

(defn rotate-y-matrix-44
  "Create a 4x4 rotation matrix around the Y axis (degrees)."
  [deg]
  (let [r (Math/toRadians (double deg))
        c (Math/cos r)
        s (Math/sin r)]
    (matrix44 c    0 s 0
              0    1 0 0
              (- s) 0 c 0
              0    0 0 1)))

(defn rotate-z-matrix-44
  "Create a 4x4 rotation matrix around the Z axis (degrees)."
  [deg]
  (let [r (Math/toRadians (double deg))
        c (Math/cos r)
        s (Math/sin r)]
    (matrix44 c (- s) 0 0
              s c     0 0
              0 0     1 0
              0 0     0 1)))

(defn skew-matrix-44
  "Create a 4x4 skew/shear matrix (2D shear in XY plane)."
  [sx sy]
  (matrix44 1  sx 0 0
            sy 1  0 0
            0  0  1 0
            0  0  0 1))

(defn perspective-matrix
  "Create a 4x4 perspective transform matrix.
   px, py control perspective distortion strength.
   Small values like 0.001 give subtle perspective.

   Example:
     (perspective-matrix 0.001 0)    ;; horizontal perspective
     (perspective-matrix 0 0.001)    ;; vertical perspective"
  [px py]
  (matrix44 1  0  0 0
            0  1  0 0
            0  0  1 0
            px py 0 1))

(defn compose-matrix44
  "Compose two or more Matrix44 transforms.
   Application order: left to right (first arg applied first)."
  ([^Matrix44 m1 ^Matrix44 m2]
   (let [^floats a (.getMat m1)
         ^floats b (.getMat m2)
         c (float-array 16)]
     (dotimes [i 4]
       (dotimes [j 4]
         (aset c (+ (* i 4) j)
               (float (+ (* (aget a (+ (* i 4) 0)) (aget b j))
                         (* (aget a (+ (* i 4) 1)) (aget b (+ 4 j)))
                         (* (aget a (+ (* i 4) 2)) (aget b (+ 8 j)))
                         (* (aget a (+ (* i 4) 3)) (aget b (+ 12 j))))))))
     (Matrix44. c)))
  ([m1 m2 & more]
   (reduce compose-matrix44 (compose-matrix44 m1 m2) more)))

;; ============================================================
;; Matrix Conversion
;; ============================================================

(defn ->matrix44
  "Convert a Matrix33 to a Matrix44.

   [a b c]    [a b 0 c]
   [d e f] -> [d e 0 f]
   [g h i]    [0 0 1 0]
              [g h 0 i]"
  [^Matrix33 m]
  (let [^floats mat (.getMat m)]
    (matrix44 (aget mat 0) (aget mat 1) 0 (aget mat 2)
              (aget mat 3) (aget mat 4) 0 (aget mat 5)
              0            0            1 0
              (aget mat 6) (aget mat 7) 0 (aget mat 8))))

(defn ->matrix33
  "Convert a Matrix44 to a Matrix33 (drops Z row and column).

   [a b _ c]    [a b c]
   [d e _ f] -> [d e f]
   [_ _ _ _]    [m n p]
   [m n _ p]"
  [^Matrix44 m]
  (let [^floats mat (.getMat m)]
    (Matrix33. (float-array [(aget mat 0)  (aget mat 1)  (aget mat 3)
                             (aget mat 4)  (aget mat 5)  (aget mat 7)
                             (aget mat 12) (aget mat 13) (aget mat 15)]))))

;; ============================================================
;; Point Transform (for hit testing, non-canvas use)
;; ============================================================

(defn transform-point
  "Transform a 2D point by a Matrix33. Returns [x' y'].
   Handles perspective division.

   Example:
     (transform-point (rotate-matrix 90) 100 0)  ;=> ~[0.0 100.0]"
  [^Matrix33 m x y]
  (let [^floats mat (.getMat m)
        x (double x) y (double y)
        w (+ (* (double (aget mat 6)) x) (* (double (aget mat 7)) y) (double (aget mat 8)))]
    [(/ (+ (* (double (aget mat 0)) x) (* (double (aget mat 1)) y) (double (aget mat 2))) w)
     (/ (+ (* (double (aget mat 3)) x) (* (double (aget mat 4)) y) (double (aget mat 5))) w)]))

(defn transform-point-44
  "Transform a 3D point by a Matrix44. Returns [x' y' z'].
   Handles perspective division.

   Example:
     (transform-point-44 (translate-matrix-44 10 20 30) 0 0 0)
     ;=> [10.0 20.0 30.0]"
  [^Matrix44 m x y z]
  (let [^floats mat (.getMat m)
        x (double x) y (double y) z (double z)
        x' (+ (* (double (aget mat 0)) x)  (* (double (aget mat 1)) y)  (* (double (aget mat 2)) z)  (double (aget mat 3)))
        y' (+ (* (double (aget mat 4)) x)  (* (double (aget mat 5)) y)  (* (double (aget mat 6)) z)  (double (aget mat 7)))
        z' (+ (* (double (aget mat 8)) x)  (* (double (aget mat 9)) y)  (* (double (aget mat 10)) z) (double (aget mat 11)))
        w  (+ (* (double (aget mat 12)) x) (* (double (aget mat 13)) y) (* (double (aget mat 14)) z) (double (aget mat 15)))]
    [(/ x' w) (/ y' w) (/ z' w)]))

;; ============================================================
;; Canvas Query
;; ============================================================

(defn local-to-device
  "Get the canvas's current local-to-device transform as a Matrix44."
  [^Canvas canvas]
  (.getLocalToDevice canvas))

;; ============================================================
;; Internal: Apply transforms (public for macro expansion)
;; ============================================================

(defn apply-transforms
  "Apply transforms to a canvas. Used by with-transform macro.
   Accepts a map of transforms, a Matrix33, or a Matrix44.

   Map keys (applied in order: translate, rotate, scale, skew):
     :translate [dx dy]
     :rotate    degrees
     :scale     [sx sy] or single number
     :skew      [sx sy]"
  [^Canvas canvas opts-or-matrix]
  (cond
    (map? opts-or-matrix)
    (let [{:keys [translate rotate scale skew]} opts-or-matrix]
      (when translate
        (let [[dx dy] translate]
          (.translate canvas (float dx) (float dy))))
      (when rotate
        (.rotate canvas (float rotate)))
      (when scale
        (let [[sx sy] (if (number? scale) [scale scale] scale)]
          (.scale canvas (float sx) (float sy))))
      (when skew
        (let [[sx sy] skew]
          (.skew canvas (float sx) (float sy)))))

    (instance? Matrix33 opts-or-matrix)
    (.concat canvas ^Matrix33 opts-or-matrix)

    (instance? Matrix44 opts-or-matrix)
    (.concat canvas ^Matrix44 opts-or-matrix)

    :else
    (throw (ex-info "Unknown transform type" {:transform opts-or-matrix}))))

;; ============================================================
;; Scoped Transform Macro
;; ============================================================

(defmacro with-transform
  "Execute body within a save/restore scope, optionally applying transforms.

   Three forms:

   ;; Bare scope (save/restore only)
   (with-transform [canvas]
     (.translate canvas 100 100)
     (draw-stuff...))

   ;; Declarative transforms (applied in order: translate, rotate, scale, skew)
   (with-transform [canvas {:translate [100 100]
                             :rotate 45
                             :scale [2 1]
                             :skew [0.3 0]}]
     (draw-stuff...))

   ;; Pre-built matrix (Matrix33 or Matrix44)
   (with-transform [canvas my-matrix]
     (draw-stuff...))

   ;; FitBox — map design coordinates into a screen rect
   (with-transform [canvas (fitbox :contain {:x 0 :y 0 :w 200 :h 200}
                                             {:x 50 :y 50 :w 80 :h 80})]
     ;; draw as if canvas is 200×200
     ...)"
  [[canvas & [opts-or-matrix]] & body]
  (if opts-or-matrix
    `(let [c# ~canvas]
       (.save c#)
       (try
         (apply-transforms c# ~opts-or-matrix)
         ~@body
         (finally
           (.restore c#))))
    `(let [c# ~canvas]
       (.save c#)
       (try
         ~@body
         (finally
           (.restore c#))))))

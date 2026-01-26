(ns lib.graphics.path
  "Path creation and operations - Love2D-style graphics API.

   NOTE: Not hot-reloadable (lib.* namespaces require restart).

   ## Quick Start

   ```clojure
   ;; Factories
   (path/rect 10 10 100 50)
   (path/circle 50 50 25)
   (path/from-svg \"M0,0 L100,100 Z\")

   ;; Builder (fluent)
   (-> (path/builder)
       (path/move-to 0 0)
       (path/line-to 100 100)
       (path/close)
       (path/build))

   ;; Boolean operations
   (path/union p1 p2)
   (path/xor outer-circle inner-circle)  ; donut/ring

   ;; Transforms (return NEW path)
   (path/offset my-path 100 50)
   (path/rotate my-path 45)

   ;; Trim/animate (via PathMeasure)
   (let [pm (path/measure my-path)
         len (path/length pm)]
     (path/segment pm 0 (* 0.5 len)))  ; first 50%

   ;; Fast hit testing (O(1) after setup)
   (def region (path/make-hit-region my-path))
   (path/region-contains? region x y)
   ```"
  (:import [io.github.humbleui.skija Path PathBuilder PathOp PathMeasure Matrix33 Region]
           [io.github.humbleui.types Rect RRect IRect]))

;; ============================================================
;; Factories
;; ============================================================

(defn rect
  "Create a rectangle path."
  [x y w h]
  (Path/makeRect (Rect/makeXYWH (float x) (float y) (float w) (float h))))

(defn rrect
  "Create a rounded rectangle path."
  [x y w h radius]
  (let [[rx ry] (if (vector? radius) radius [radius radius])
        r (RRect/makeXYWH (float x) (float y) (float w) (float h) (float rx) (float ry))]
    (Path/makeRRect r)))

(defn oval
  "Create an oval path."
  [x y w h]
  (Path/makeOval (Rect/makeXYWH (float x) (float y) (float w) (float h))))

(defn circle
  "Create a circle path."
  [cx cy r]
  (oval (- cx r) (- cy r) (* 2 r) (* 2 r)))

(defn polygon
  "Create a polygon path from points.

   Points can be:
     - Flat list: [x1 y1 x2 y2 ...]
     - Nested pairs: [[x1 y1] [x2 y2] ...]

   Args:
     points  - list of coordinates
     closed? - whether to close the path (default: true)"
  ([points]
   (polygon points true))
  ([points closed?]
   (let [flat (if (vector? (first points)) (flatten points) points)
         pairs (partition 2 flat)
         pb (PathBuilder.)]
     (when (seq pairs)
       (let [[x y] (first pairs)]
         (.moveTo pb (float x) (float y)))
       (doseq [[x y] (rest pairs)]
         (.lineTo pb (float x) (float y)))
       (when closed?
         (.closePath pb)))
     (.build pb))))

(defn line
  "Create a line segment path."
  [x1 y1 x2 y2]
  (let [pb (PathBuilder.)]
    (.moveTo pb (float x1) (float y1))
    (.lineTo pb (float x2) (float y2))
    (.build pb)))

(defn arc
  "Create an arc path.

   Args:
     cx, cy      - center coordinates
     radius      - arc radius
     start-angle - start angle in degrees (0 = right, 90 = bottom)
     sweep-angle - sweep angle in degrees (positive = clockwise)

   Returns: Path object"
  [cx cy radius start-angle sweep-angle]
  (let [oval (Rect/makeLTRB (float (- cx radius)) (float (- cy radius))
                            (float (+ cx radius)) (float (+ cy radius)))]
    (-> (PathBuilder.)
        (.addArc oval (float start-angle) (float sweep-angle))
        (.build))))

(defn wave
  "Create a sinusoidal wave path using cubic Bezier curves.

   Uses 4 Bezier segments per cycle via Hermite interpolation at
   quarter-wave points. Smooth C1 tangents, fewer path verbs than lineTo.

   Args:
     x, y      - start coordinates
     width     - total width of wave
     amplitude - wave amplitude (height from center)
     frequency - number of complete wave cycles

   Returns: Path object"
  [x y width amplitude frequency]
  (let [pb (PathBuilder.)
        ;; 4 quarter-wave segments per cycle
        n-quarters (max 1 (long (Math/round (* 4.0 (double frequency)))))
        qw (/ (double width) n-quarters)
        ;; Hermite tangent scale: amp * 2π*freq / n-quarters
        ts (/ (* (double amplitude) 2.0 Math/PI (double frequency)) n-quarters)]
    (.moveTo pb (float x) (float y))
    (dotimes [i n-quarters]
      (let [a0 (* 2.0 Math/PI (double frequency) (/ (double i) n-quarters))
            a1 (* 2.0 Math/PI (double frequency) (/ (double (inc i)) n-quarters))
            x0 (+ (double x) (* (double i) qw))
            x1 (+ (double x) (* (double (inc i)) qw))
            y0 (+ (double y) (* (double amplitude) (Math/sin a0)))
            y1 (+ (double y) (* (double amplitude) (Math/sin a1)))
            ty0 (* ts (Math/cos a0))
            ty1 (* ts (Math/cos a1))]
        (.cubicTo pb
                  (float (+ x0 (/ qw 3.0)))
                  (float (+ y0 (/ ty0 3.0)))
                  (float (- x1 (/ qw 3.0)))
                  (float (- y1 (/ ty1 3.0)))
                  (float x1)
                  (float y1))))
    (.build pb)))

(defn from-svg
  "Parse an SVG path string into a Path."
  [^String svg-string]
  (Path/makeFromSVGString svg-string))

;; ============================================================
;; Builder
;; ============================================================

(defn builder
  "Create a new PathBuilder for fluent path construction."
  []
  (PathBuilder.))

(defn move-to
  "Move to point without drawing."
  [^PathBuilder pb x y]
  (.moveTo pb (float x) (float y))
  pb)

(defn line-to
  "Draw a line to point."
  [^PathBuilder pb x y]
  (.lineTo pb (float x) (float y))
  pb)

(defn quad-to
  "Draw a quadratic bezier curve."
  [^PathBuilder pb cx cy x y]
  (.quadTo pb (float cx) (float cy) (float x) (float y))
  pb)

(defn cubic-to
  "Draw a cubic bezier curve."
  [^PathBuilder pb c1x c1y c2x c2y x y]
  (.cubicTo pb (float c1x) (float c1y) (float c2x) (float c2y) (float x) (float y))
  pb)

(defn arc-to
  "Draw an arc."
  [^PathBuilder pb [x y w h] start-angle sweep-angle force-move?]
  (.arcTo pb (Rect/makeXYWH (float x) (float y) (float w) (float h))
          (float start-angle) (float sweep-angle) (boolean force-move?))
  pb)

(defn tangent-arc-to
  "Draw an arc tangent to two lines: (current→p1) and (p1→p2).
   Rounds the corner at p1 with the given radius."
  [^PathBuilder pb x1 y1 x2 y2 radius]
  (.tangentArcTo pb (float x1) (float y1) (float x2) (float y2) (float radius))
  pb)

(defn close
  "Close the current contour."
  [^PathBuilder pb]
  (.closePath pb)
  pb)

(defn build
  "Build the PathBuilder into a Path."
  [^PathBuilder pb]
  (.build pb))

;; ============================================================
;; Boolean Operations
;; ============================================================

(defn union
  "Union of two paths."
  [^Path p1 ^Path p2]
  (Path/makeCombining p1 p2 PathOp/UNION))

(defn intersect
  "Intersection of two paths."
  [^Path p1 ^Path p2]
  (Path/makeCombining p1 p2 PathOp/INTERSECT))

(defn difference
  "Difference of two paths (p1 minus p2)."
  [^Path p1 ^Path p2]
  (Path/makeCombining p1 p2 PathOp/DIFFERENCE))

(defn xor
  "XOR of two paths (area in either but not both).
   Useful for rings, donuts, and toggle selections."
  [^Path p1 ^Path p2]
  (Path/makeCombining p1 p2 PathOp/XOR))

;; ============================================================
;; Path Interpolation
;; ============================================================

(defn interpolatable?
  "Check if two paths can be interpolated (same verb/point structure)."
  [^Path p1 ^Path p2]
  (.isInterpolatable p1 p2))

(defn interpolate
  "Interpolate between two compatible paths.
   Weight 0.0 = p1, 1.0 = p2. Paths must be interpolatable."
  [^Path p1 ^Path p2 weight]
  (.makeInterpolate p1 p2 (float weight)))

;; ============================================================
;; Queries
;; ============================================================

(defn contains-point?
  "Check if a point is inside the path."
  [^Path p x y]
  (.contains p (float x) (float y)))

(defn bounds
  "Get the bounding rectangle [x y w h]."
  [^Path p]
  (let [^Rect r (.getBounds p)]
    [(.getLeft r) (.getTop r) (.getWidth r) (.getHeight r)]))

(defn path-empty?
  "Check if path is empty."
  [^Path p]
  (.isEmpty p))

(defn convex?
  "Check if path is convex."
  [^Path p]
  (.isConvex p))

;; ============================================================
;; Transforms (return NEW path, no drawing needed)
;; ============================================================

(defn transform
  "Transform a path by a matrix. Returns new Path."
  [^Path p ^Matrix33 matrix]
  (.transform p matrix))

(defn offset
  "Translate a path. Returns new Path."
  [^Path p dx dy]
  (.transform p (Matrix33/makeTranslate (float dx) (float dy))))

(defn scale
  "Scale a path around origin. Returns new Path."
  ([^Path p s]
   (scale p s s))
  ([^Path p sx sy]
   (.transform p (Matrix33/makeScale (float sx) (float sy)))))

(defn rotate
  "Rotate a path around origin (degrees). Returns new Path."
  [^Path p degrees]
  (.transform p (Matrix33/makeRotate (float degrees))))

;; ============================================================
;; PathMeasure (length, trim, position along path)
;; ============================================================

(defn measure
  "Create a PathMeasure for querying path geometry.
   Use with `length`, `position-at`, `tangent-at`, `segment`."
  [^Path p]
  (PathMeasure. p))

(defn length
  "Get total length of path (or PathMeasure)."
  [p]
  (if (instance? PathMeasure p)
    (.getLength ^PathMeasure p)
    (.getLength (PathMeasure. ^Path p))))

(defn position-at
  "Get [x y] position at distance along path."
  [^PathMeasure pm distance]
  (when-let [pt (.getPosition pm (float distance))]
    [(.getX pt) (.getY pt)]))

(defn tangent-at
  "Get [dx dy] tangent vector at distance along path."
  [^PathMeasure pm distance]
  (when-let [pt (.getTangent pm (float distance))]
    [(.getX pt) (.getY pt)]))

(defn segment
  "Extract a segment of the path from start to end distance.
   Useful for animating path drawing (trim effect)."
  [^PathMeasure pm start-dist end-dist]
  (let [pb (PathBuilder.)]
    (when (.getSegment pm (float start-dist) (float end-dist) pb true)
      (.build pb))))

;; ============================================================
;; Region-based Hit Testing (O(1) after setup)
;; ============================================================

;; see https://claude.ai/share/cd060e0a-4f56-4c4c-aa63-ca09d28f42f9

(defn make-hit-region
  "Create a Region for fast repeated hit testing on same path.
   Much faster than `contains?` when testing many points.

   Note: Uses integer coordinates (pixel-level precision).

   Usage:
     (def region (path/make-hit-region my-path))
     (path/region-contains? region mouse-x mouse-y)  ; O(1)"
  [^Path p]
  (let [[x y w h] (bounds p)
        clip (doto (Region.)
               (.setRect (IRect/makeXYWH (int x) (int y)
                                         (int (Math/ceil w))
                                         (int (Math/ceil h)))))
        region (Region.)]
    (.setPath region p clip)
    region))

(defn region-contains?
  "Fast O(1) point-in-region test. Use with `make-hit-region`."
  [^Region region x y]
  (.contains region (int x) (int y)))

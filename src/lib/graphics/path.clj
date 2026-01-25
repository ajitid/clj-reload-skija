(ns lib.graphics.path
  "Path creation, operations, and queries - Love2D-style graphics API.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern).

   ## Quick Start

   ```clojure
   ;; Fluent builder pattern
   (-> (path)
       (move-to 0 0)
       (line-to 100 0)
       (line-to 100 100)
       (close)
       (build))

   ;; Path factories
   (path-rect 10 10 100 50)
   (path-circle 50 50 25)
   (path-from-svg \"M0,0 L100,100 Z\")

   ;; Boolean operations
   (path-union p1 p2)
   (path-difference p1 p2)
   ```"
  (:import [io.github.humbleui.skija Path PathBuilder PathOp PathFillMode PathDirection]
           [io.github.humbleui.types Rect RRect Point]
           [io.github.humbleui.skija Matrix33]))

;; ============================================================
;; Path Builder (Fluent API)
;; ============================================================

(defn path
  "Create a new PathBuilder for fluent path construction.

   Example:
     (-> (path)
         (move-to 0 0)
         (line-to 100 100)
         (close)
         (build))"
  []
  (PathBuilder.))

(defn move-to
  "Move to point without drawing. Starts a new contour.

   Args:
     pb   - PathBuilder
     x, y - destination point"
  [^PathBuilder pb x y]
  (.moveTo pb (float x) (float y))
  pb)

(defn line-to
  "Draw a line to point.

   Args:
     pb   - PathBuilder
     x, y - destination point"
  [^PathBuilder pb x y]
  (.lineTo pb (float x) (float y))
  pb)

(defn quad-to
  "Draw a quadratic bezier curve.

   Args:
     pb       - PathBuilder
     cx, cy   - control point
     x, y     - destination point"
  [^PathBuilder pb cx cy x y]
  (.quadTo pb (float cx) (float cy) (float x) (float y))
  pb)

(defn cubic-to
  "Draw a cubic bezier curve.

   Args:
     pb         - PathBuilder
     c1x, c1y   - first control point
     c2x, c2y   - second control point
     x, y       - destination point"
  [^PathBuilder pb c1x c1y c2x c2y x y]
  (.cubicTo pb (float c1x) (float c1y) (float c2x) (float c2y) (float x) (float y))
  pb)

(defn conic-to
  "Draw a conic/rational quadratic bezier curve.

   Args:
     pb       - PathBuilder
     cx, cy   - control point
     x, y     - destination point
     weight   - conic weight (1.0 = quadratic, <1 = elliptical, >1 = hyperbolic)"
  [^PathBuilder pb cx cy x y weight]
  (.conicTo pb (float cx) (float cy) (float x) (float y) (float weight))
  pb)

(defn arc-to
  "Draw an arc.

   Args:
     pb           - PathBuilder
     oval         - [x y w h] bounding box for the arc's oval
     start-angle  - starting angle in degrees (0 = 3 o'clock)
     sweep-angle  - sweep angle in degrees (positive = clockwise)
     force-move?  - if true, move to arc start instead of line"
  [^PathBuilder pb [x y w h] start-angle sweep-angle force-move?]
  (.arcTo pb (Rect/makeXYWH (float x) (float y) (float w) (float h))
          (float start-angle) (float sweep-angle) (boolean force-move?))
  pb)

(defn close
  "Close the current contour by drawing a line to the start.

   Args:
     pb - PathBuilder"
  [^PathBuilder pb]
  (.closePath pb)
  pb)

(defn build
  "Build the PathBuilder into a Path.

   Args:
     pb - PathBuilder

   Returns: Path instance"
  [^PathBuilder pb]
  (.build pb))

;; ============================================================
;; Path Builder Macro
;; ============================================================

(defmacro with-path
  "Build a path using a block. Automatically creates builder and builds.

   Example:
     (with-path [p]
       (move-to p 0 0)
       (line-to p 100 100)
       (close p))  ; Returns built Path"
  [[binding] & body]
  `(let [~binding (path)]
     ~@body
     (build ~binding)))

;; ============================================================
;; Path Factories
;; ============================================================

(defn path-rect
  "Create a rectangle path.

   Args:
     x, y - top-left position
     w, h - width and height
     dir  - path direction: :cw (clockwise) or :ccw (counter-clockwise) (default :cw)"
  ([x y w h]
   (path-rect x y w h :cw))
  ([x y w h dir]
   (let [direction (case dir
                     :cw PathDirection/CLOCKWISE
                     :ccw PathDirection/COUNTER_CLOCKWISE
                     PathDirection/CLOCKWISE)]
     (Path/makeRect (Rect/makeXYWH (float x) (float y) (float w) (float h)) direction))))

(defn path-rrect
  "Create a rounded rectangle path.

   Args:
     x, y   - top-left position
     w, h   - width and height
     radius - corner radius (single value or [rx ry])
     dir    - path direction: :cw or :ccw (default :cw)"
  ([x y w h radius]
   (path-rrect x y w h radius :cw))
  ([x y w h radius dir]
   (let [[rx ry] (if (vector? radius) radius [radius radius])
         rrect (RRect/makeXYWH (float x) (float y) (float w) (float h) (float rx) (float ry))
         direction (case dir
                     :cw PathDirection/CLOCKWISE
                     :ccw PathDirection/COUNTER_CLOCKWISE
                     PathDirection/CLOCKWISE)]
     (Path/makeRRect rrect direction))))

(defn path-oval
  "Create an oval path.

   Args:
     x, y - top-left of bounding box
     w, h - width and height of bounding box
     dir  - path direction: :cw or :ccw (default :cw)"
  ([x y w h]
   (path-oval x y w h :cw))
  ([x y w h dir]
   (let [direction (case dir
                     :cw PathDirection/CLOCKWISE
                     :ccw PathDirection/COUNTER_CLOCKWISE
                     PathDirection/CLOCKWISE)]
     (Path/makeOval (Rect/makeXYWH (float x) (float y) (float w) (float h)) direction))))

(defn path-circle
  "Create a circle path.

   Args:
     cx, cy - center position
     r      - radius
     dir    - path direction: :cw or :ccw (default :cw)"
  ([cx cy r]
   (path-circle cx cy r :cw))
  ([cx cy r dir]
   ;; Create oval from center and radius
   (path-oval (- cx r) (- cy r) (* 2 r) (* 2 r) dir)))

(defn path-polygon
  "Create a polygon path from points.

   Args:
     points  - sequence of [x y] points or flat [x1 y1 x2 y2 ...] coords
     closed? - if true, close the path (default true)"
  ([points]
   (path-polygon points true))
  ([points closed?]
   (let [;; Normalize to flat coordinate array
         flat (if (vector? (first points))
                (flatten points)
                points)
         coords (float-array flat)]
     (if closed?
       (Path/makeFromPolygon coords)
       ;; For open polygon, use builder
       (let [pb (PathBuilder.)
             pairs (partition 2 flat)]
         (when (seq pairs)
           (let [[x y] (first pairs)]
             (.moveTo pb (float x) (float y)))
           (doseq [[x y] (rest pairs)]
             (.lineTo pb (float x) (float y))))
         (.build pb))))))

(defn path-line
  "Create a line segment path.

   Args:
     x1, y1 - start point
     x2, y2 - end point"
  [x1 y1 x2 y2]
  (-> (path)
      (move-to x1 y1)
      (line-to x2 y2)
      (build)))

(defn path-from-svg
  "Parse an SVG path string into a Path.

   Args:
     svg-string - SVG path data (e.g., \"M0,0 L100,100 Z\")

   Examples:
     (path-from-svg \"M0,0 L100,0 L100,100 L0,100 Z\")
     (path-from-svg \"M10,10 C20,20 40,20 50,10\")"
  [^String svg-string]
  (Path/makeFromSVGString svg-string))

;; ============================================================
;; Path Operations (Boolean)
;; ============================================================

(defn path-union
  "Union of two paths (combine areas).

   Args:
     p1 - first path
     p2 - second path

   Returns: new Path containing union"
  [^Path p1 ^Path p2]
  (Path/makeCombining p1 p2 PathOp/UNION))

(defn path-intersect
  "Intersection of two paths (area where both overlap).

   Args:
     p1 - first path
     p2 - second path

   Returns: new Path containing intersection"
  [^Path p1 ^Path p2]
  (Path/makeCombining p1 p2 PathOp/INTERSECT))

(defn path-difference
  "Difference of two paths (p1 minus p2).

   Args:
     p1 - path to subtract from
     p2 - path to subtract

   Returns: new Path containing difference"
  [^Path p1 ^Path p2]
  (Path/makeCombining p1 p2 PathOp/DIFFERENCE))

(defn path-xor
  "Exclusive OR of two paths (area in either but not both).

   Args:
     p1 - first path
     p2 - second path

   Returns: new Path containing XOR"
  [^Path p1 ^Path p2]
  (Path/makeCombining p1 p2 PathOp/XOR))

(defn path-reverse-difference
  "Reverse difference of two paths (p2 minus p1).

   Args:
     p1 - first path
     p2 - second path

   Returns: new Path containing reverse difference"
  [^Path p1 ^Path p2]
  (Path/makeCombining p1 p2 PathOp/REVERSE_DIFFERENCE))

;; ============================================================
;; Path Transformations
;; ============================================================

(defn path-transform
  "Transform a path by a matrix.

   Args:
     p      - path to transform
     matrix - Matrix33 instance or [a b c d e f g h i] array

   Returns: new transformed Path"
  [^Path p matrix]
  (let [m (if (instance? Matrix33 matrix)
            matrix
            (Matrix33. (float-array matrix)))]
    (.transform p m)))

(defn path-offset
  "Offset/translate a path.

   Args:
     p     - path to offset
     dx    - horizontal offset
     dy    - vertical offset

   Returns: new offset Path"
  [^Path p dx dy]
  (let [m (Matrix33/makeTranslate (float dx) (float dy))]
    (.transform p m)))

(defn path-scale
  "Scale a path around origin.

   Args:
     p     - path to scale
     sx    - horizontal scale
     sy    - vertical scale (optional, defaults to sx)

   Returns: new scaled Path"
  ([^Path p s]
   (path-scale p s s))
  ([^Path p sx sy]
   (let [m (Matrix33/makeScale (float sx) (float sy))]
     (.transform p m))))

(defn path-rotate
  "Rotate a path around origin.

   Args:
     p       - path to rotate
     degrees - rotation in degrees

   Returns: new rotated Path"
  [^Path p degrees]
  (let [m (Matrix33/makeRotate (float degrees))]
    (.transform p m)))

;; ============================================================
;; Path Queries
;; ============================================================

(defn path-contains?
  "Check if a point is inside the path.

   Args:
     p    - path to test
     x, y - point coordinates

   Returns: true if point is inside path"
  [^Path p x y]
  (.contains p (float x) (float y)))

(defn path-bounds
  "Get the bounding rectangle of a path.

   Args:
     p - path

   Returns: [x y w h] bounding box"
  [^Path p]
  (let [^Rect r (.getBounds p)]
    [(.getLeft r) (.getTop r) (.getWidth r) (.getHeight r)]))

(defn path-convex?
  "Check if a path is convex.

   Args:
     p - path

   Returns: true if path is convex"
  [^Path p]
  (.isConvex p))

(defn path-empty?
  "Check if a path is empty (no geometry).

   Args:
     p - path

   Returns: true if path is empty"
  [^Path p]
  (.isEmpty p))

(defn path-finite?
  "Check if all path coordinates are finite (not NaN or infinite).

   Args:
     p - path

   Returns: true if all coordinates are finite"
  [^Path p]
  (.isFinite p))

(defn path-line?
  "Check if path represents a single line segment.

   Args:
     p - path

   Returns: true if path is a line"
  [^Path p]
  (.isLine p))

(defn path-oval?
  "Check if path represents an oval.

   Args:
     p - path

   Returns: true if path is an oval"
  [^Path p]
  (.isOval p))

(defn path-rect?
  "Check if path represents a rectangle.

   Args:
     p - path

   Returns: true if path is a rectangle"
  [^Path p]
  (.isRect p))

(defn path-rrect?
  "Check if path represents a rounded rectangle.

   Args:
     p - path

   Returns: true if path is a rounded rectangle"
  [^Path p]
  (.isRRect p))

;; ============================================================
;; Path Fill Mode
;; ============================================================

(defn path-set-fill-mode
  "Set the fill mode for a path.

   Args:
     p    - path
     mode - fill mode: :winding, :even-odd, :inverse-winding, :inverse-even-odd

   Returns: path (mutated)"
  [^Path p mode]
  (let [fill-mode (case mode
                    :winding PathFillMode/WINDING
                    :even-odd PathFillMode/EVEN_ODD
                    :inverse-winding PathFillMode/INVERSE_WINDING
                    :inverse-even-odd PathFillMode/INVERSE_EVEN_ODD
                    PathFillMode/WINDING)]
    (.setFillMode p fill-mode)
    p))

(defn path-fill-mode
  "Get the fill mode of a path.

   Args:
     p - path

   Returns: :winding, :even-odd, :inverse-winding, or :inverse-even-odd"
  [^Path p]
  (let [mode (.getFillMode p)]
    (condp = mode
      PathFillMode/WINDING :winding
      PathFillMode/EVEN_ODD :even-odd
      PathFillMode/INVERSE_WINDING :inverse-winding
      PathFillMode/INVERSE_EVEN_ODD :inverse-even-odd
      :winding)))

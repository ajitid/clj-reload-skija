(ns lib.graphics.clip
  "Clipping functions - Love2D-style graphics API.

   NOTE: Not hot-reloadable (lib.* namespaces require restart).

   ## Quick Start

   ```clojure
   ;; Clip INSIDE region (default)
   (clip/with-clip [canvas [10 10 100 100]]
     (draw-stuff...))

   ;; Clip OUTSIDE region (cutout/spotlight)
   (clip/with-clip-out [canvas [50 50 100 100]]
     (shapes/rect canvas 0 0 w h {:color 0x80000000}))

   ;; Rounded rectangle
   (clip/with-clip [canvas [10 10 100 100 5]]
     (draw-stuff...))

   ;; Path
   (clip/with-clip [canvas my-path]
     (draw-stuff...))
   ```"
  (:import [io.github.humbleui.skija Canvas ClipMode Path]
           [io.github.humbleui.types Rect RRect]))

;; ============================================================
;; Clipping Functions
;; ============================================================

(defn rect
  "Clip to a rectangle."
  ([^Canvas canvas x y w h]
   (.clipRect canvas (Rect/makeXYWH (float x) (float y) (float w) (float h)))
   canvas)
  ([^Canvas canvas x y w h mode]
   (let [clip-mode (if (= mode :difference) ClipMode/DIFFERENCE ClipMode/INTERSECT)]
     (.clipRect canvas (Rect/makeXYWH (float x) (float y) (float w) (float h)) clip-mode))
   canvas))

(defn rrect
  "Clip to a rounded rectangle."
  ([^Canvas canvas x y w h radius]
   (let [[rx ry] (if (vector? radius) radius [radius radius])
         r (RRect/makeXYWH (float x) (float y) (float w) (float h) (float rx) (float ry))]
     (.clipRRect canvas r))
   canvas)
  ([^Canvas canvas x y w h radius mode]
   (let [[rx ry] (if (vector? radius) radius [radius radius])
         r (RRect/makeXYWH (float x) (float y) (float w) (float h) (float rx) (float ry))
         clip-mode (if (= mode :difference) ClipMode/DIFFERENCE ClipMode/INTERSECT)]
     (.clipRRect canvas r clip-mode))
   canvas))

(defn path
  "Clip to a path."
  ([^Canvas canvas ^Path p]
   (.clipPath canvas p)
   canvas)
  ([^Canvas canvas ^Path p mode]
   (let [clip-mode (if (= mode :difference) ClipMode/DIFFERENCE ClipMode/INTERSECT)]
     (.clipPath canvas p clip-mode))
   canvas))

;; ============================================================
;; Internal: Apply clip shape (public for macro expansion)
;; ============================================================

(defn apply-clip
  "Apply a clip shape to the canvas. Used by with-clip macros.
   Shape: [x y w h], [x y w h radius], or Path instance."
  [canvas shape mode]
  (cond
    ;; Path instance
    (instance? Path shape)
    (path canvas shape mode)

    ;; [x y w h radius] = rounded rect
    (and (vector? shape) (= 5 (count shape)))
    (let [[x y w h r] shape]
      (rrect canvas x y w h r mode))

    ;; [x y w h] = rect
    (and (vector? shape) (= 4 (count shape)))
    (let [[x y w h] shape]
      (rect canvas x y w h mode))

    :else
    (throw (ex-info "Unknown clip shape" {:shape shape}))))

;; ============================================================
;; Clipping Macros
;; ============================================================

(defmacro with-clip
  "Clip INSIDE the shape. Everything outside is hidden.

   Shape:
     [x y w h]        - Rectangle
     [x y w h radius] - Rounded rectangle
     Path instance    - Path

   Example:
     (with-clip [canvas [10 10 100 100]]
       (shapes/circle canvas 50 50 40 {:color 0xFF0000FF}))"
  [[canvas shape] & body]
  `(let [c# ~canvas]
     (.save c#)
     (try
       (apply-clip c# ~shape :intersect)
       ~@body
       (finally
         (.restore c#)))))

(defmacro with-clip-out
  "Clip OUTSIDE the shape (cutout/spotlight effect).
   Everything inside the shape is hidden.

   Shape:
     [x y w h]        - Rectangle
     [x y w h radius] - Rounded rectangle
     Path instance    - Path

   Example:
     ;; Spotlight - darken everything except focus area
     (with-clip-out [canvas [50 50 100 100]]
       (shapes/rect canvas 0 0 w h {:color 0x80000000}))"
  [[canvas shape] & body]
  `(let [c# ~canvas]
     (.save c#)
     (try
       (apply-clip c# ~shape :difference)
       ~@body
       (finally
         (.restore c#)))))

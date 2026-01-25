(ns lib.graphics.clip
  "Clipping functions and macros - Love2D-style graphics API.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern).

   ## Quick Start

   ```clojure
   ;; Clip to rectangle
   (with-clip [canvas [10 10 100 100]]
     (draw-stuff...))

   ;; Clip to rounded rectangle
   (with-clip [canvas {:rect [10 10 100 100] :radius 5}]
     (draw-stuff...))

   ;; Clip to path
   (with-clip [canvas my-path]
     (draw-stuff...))

   ;; Explicit API
   (clip-rect canvas [10 10 100 100])
   (clip-path canvas my-path)
   ```"
  (:import [io.github.humbleui.skija Canvas ClipMode Path Region]
           [io.github.humbleui.types Rect RRect]))

;; ============================================================
;; Clip Mode Helper
;; ============================================================

(defn- resolve-clip-mode
  "Convert keyword to ClipMode enum."
  [mode]
  (case mode
    :intersect ClipMode/INTERSECT
    :difference ClipMode/DIFFERENCE
    nil ClipMode/INTERSECT
    mode))

;; ============================================================
;; Rectangle Clipping
;; ============================================================

(defn clip-rect
  "Clip to a rectangle.

   Args:
     canvas      - drawing canvas
     rect        - [x y w h] rectangle
     mode        - clip mode: :intersect (default) or :difference
     anti-alias? - whether to anti-alias edges (default true)

   Examples:
     (clip-rect canvas [10 10 100 100])
     (clip-rect canvas [10 10 100 100] :difference)
     (clip-rect canvas [10 10 100 100] :intersect false)"
  ([^Canvas canvas [x y w h]]
   (clip-rect canvas [x y w h] :intersect true))
  ([^Canvas canvas [x y w h] mode]
   (clip-rect canvas [x y w h] mode true))
  ([^Canvas canvas [x y w h] mode anti-alias?]
   (let [rect (Rect/makeXYWH (float x) (float y) (float w) (float h))]
     (.clipRect canvas rect (resolve-clip-mode mode) (boolean anti-alias?)))
   canvas))

;; ============================================================
;; Rounded Rectangle Clipping
;; ============================================================

(defn clip-rrect
  "Clip to a rounded rectangle.

   Args:
     canvas      - drawing canvas
     rect        - [x y w h] rectangle
     radius      - corner radius (single value or [rx ry])
     mode        - clip mode: :intersect (default) or :difference
     anti-alias? - whether to anti-alias edges (default true)

   Examples:
     (clip-rrect canvas [10 10 100 100] 5)
     (clip-rrect canvas [10 10 100 100] [5 3] :difference)"
  ([^Canvas canvas [x y w h] radius]
   (clip-rrect canvas [x y w h] radius :intersect true))
  ([^Canvas canvas [x y w h] radius mode]
   (clip-rrect canvas [x y w h] radius mode true))
  ([^Canvas canvas [x y w h] radius mode anti-alias?]
   (let [[rx ry] (if (vector? radius) radius [radius radius])
         rrect (RRect/makeXYWH (float x) (float y) (float w) (float h) (float rx) (float ry))]
     (.clipRRect canvas rrect (resolve-clip-mode mode) (boolean anti-alias?)))
   canvas))

;; ============================================================
;; Path Clipping
;; ============================================================

(defn clip-path
  "Clip to a path.

   Args:
     canvas      - drawing canvas
     path        - Path instance
     mode        - clip mode: :intersect (default) or :difference
     anti-alias? - whether to anti-alias edges (default true)

   Examples:
     (clip-path canvas my-path)
     (clip-path canvas my-path :difference)"
  ([^Canvas canvas ^Path path]
   (clip-path canvas path :intersect true))
  ([^Canvas canvas ^Path path mode]
   (clip-path canvas path mode true))
  ([^Canvas canvas ^Path path mode anti-alias?]
   (.clipPath canvas path (resolve-clip-mode mode) (boolean anti-alias?))
   canvas))

;; ============================================================
;; Region Clipping
;; ============================================================

(defn clip-region
  "Clip to a region.

   Args:
     canvas - drawing canvas
     region - Region instance
     mode   - clip mode: :intersect (default) or :difference

   Note: Regions are axis-aligned and don't support anti-aliasing."
  ([^Canvas canvas ^Region region]
   (clip-region canvas region :intersect))
  ([^Canvas canvas ^Region region mode]
   (.clipRegion canvas region (resolve-clip-mode mode))
   canvas))

;; ============================================================
;; Shape Resolution
;; ============================================================

(defn- resolve-clip-shape
  "Apply a clip shape to the canvas.

   Shape can be:
   - [x y w h] - Rectangle
   - {:rect [x y w h] :radius r} - Rounded rectangle
   - {:rect [x y w h] :radius r :mode :difference} - With clip mode
   - Path instance - Path clipping
   - {:path path :mode :difference} - Path with clip mode"
  [^Canvas canvas shape]
  (cond
    ;; Vector = rectangle [x y w h]
    (and (vector? shape) (number? (first shape)))
    (clip-rect canvas shape)

    ;; Map with :rect = rounded rectangle or rectangle with options
    (and (map? shape) (:rect shape))
    (let [{:keys [rect radius mode anti-alias?]
           :or {mode :intersect anti-alias? true}} shape]
      (if radius
        (clip-rrect canvas rect radius mode anti-alias?)
        (clip-rect canvas rect mode anti-alias?)))

    ;; Path instance
    (instance? Path shape)
    (clip-path canvas shape)

    ;; Map with :path
    (and (map? shape) (:path shape))
    (let [{:keys [path mode anti-alias?]
           :or {mode :intersect anti-alias? true}} shape]
      (clip-path canvas path mode anti-alias?))

    ;; Region instance
    (instance? Region shape)
    (clip-region canvas shape)

    :else
    (throw (ex-info "Unknown clip shape type"
                    {:shape shape :type (type shape)}))))

;; ============================================================
;; Clipping Macro
;; ============================================================

(defmacro with-clip
  "Execute body with clipping applied. Automatically saves and restores canvas state.

   Args:
     canvas - drawing canvas
     shape  - clip shape (see resolve-clip-shape for options)

   Shape can be:
     - [x y w h] - Rectangle
     - {:rect [x y w h] :radius r} - Rounded rectangle
     - {:rect [x y w h] :mode :difference} - Rectangle with clip mode
     - path-instance - Path
     - {:path path :mode :difference} - Path with clip mode

   Examples:
     ;; Clip to rectangle
     (with-clip [canvas [10 10 100 100]]
       (shapes/circle canvas 50 50 40 {:color 0xFF0000FF}))

     ;; Clip to rounded rectangle
     (with-clip [canvas {:rect [10 10 100 100] :radius 5}]
       (draw-content...))

     ;; Clip to path
     (with-clip [canvas my-star-path]
       (draw-gradient-fill...))

     ;; Inverse clip (draw outside region)
     (with-clip [canvas {:rect [50 50 100 100] :mode :difference}]
       (draw-stuff...))"
  [[canvas shape] & body]
  `(let [c# ~canvas]
     (.save c#)
     (try
       (resolve-clip-shape c# ~shape)
       ~@body
       (finally
         (.restore c#)))))

;; ============================================================
;; Utility Functions
;; ============================================================

(defn get-clip-bounds
  "Get the current clip bounds as [x y w h].

   Args:
     canvas - drawing canvas

   Returns: [x y w h] bounding rectangle of current clip"
  [^Canvas canvas]
  (let [^Rect r (.getLocalClipBounds canvas)]
    [(.getLeft r) (.getTop r) (.getWidth r) (.getHeight r)]))

(defn clip-empty?
  "Check if the current clip is empty (nothing will be drawn).

   Args:
     canvas - drawing canvas

   Returns: true if clip region is empty"
  [^Canvas canvas]
  (.isClipEmpty canvas))

(defn clip-rect?
  "Check if the current clip is a rectangle (for optimization hints).

   Args:
     canvas - drawing canvas

   Returns: true if clip is a simple rectangle"
  [^Canvas canvas]
  (.isClipRect canvas))

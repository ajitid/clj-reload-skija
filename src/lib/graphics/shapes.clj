(ns lib.graphics.shapes
  "Shape drawing functions - Love2D-style graphics API.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:require [lib.graphics.state :as gfx])
  (:import [io.github.humbleui.skija Canvas Paint PaintMode Path]
           [io.github.humbleui.types Rect RRect]))

;; ============================================================
;; Circle
;; ============================================================

(defn circle
  "Draw a circle at the given position.

   Args:
     canvas - drawing canvas
     x, y   - center position
     radius - circle radius
     opts   - optional map supporting all paint options (see state/make-paint)
              Common: :color, :mode, :stroke-width, :blur, :shadow, :gradient, etc.

   Examples:
     (circle canvas 100 100 25)
     (circle canvas 100 100 25 {:color [0.29 0.56 0.85 1.0]})
     (circle canvas 100 100 25 {:mode :stroke :stroke-width 2})
     (circle canvas 100 100 25 {:blur 5.0 :shadow {:dx 2 :dy 2 :blur 3}})"
  ([^Canvas canvas x y radius]
   (circle canvas x y radius {}))
  ([^Canvas canvas x y radius opts]
   (if-let [paint (:paint opts)]
     ;; Use provided paint
     (.drawCircle canvas (float x) (float y) (float radius) paint)
     ;; Create new paint with options
     (gfx/with-paint [paint opts]
       (.drawCircle canvas (float x) (float y) (float radius) paint)))))

;; ============================================================
;; Rectangle
;; ============================================================

(defn rectangle
  "Draw a rectangle at the given position.

   Args:
     canvas   - drawing canvas
     x, y     - top-left position
     w, h     - width and height
     opts     - optional map (all paint options supported, see circle)

   Examples:
     (rectangle canvas 10 10 100 50)
     (rectangle canvas 10 10 100 50 {:color [0.29 0.56 0.85 1.0] :mode :stroke})
     (rectangle canvas 10 10 100 50 {:gradient {:type :linear :x0 0 :y0 0 :x1 100 :y1 0
                                                :colors [[1 0 0 1] [0 0 1 1]]}})"
  ([^Canvas canvas x y w h]
   (rectangle canvas x y w h {}))
  ([^Canvas canvas x y w h opts]
   (if-let [paint (:paint opts)]
     (.drawRect canvas (Rect/makeXYWH (float x) (float y) (float w) (float h)) paint)
     (gfx/with-paint [paint opts]
       (.drawRect canvas (Rect/makeXYWH (float x) (float y) (float w) (float h)) paint)))))

;; ============================================================
;; Rounded Rectangle
;; ============================================================

(defn rounded-rect
  "Draw a rounded rectangle.

   Args:
     canvas   - drawing canvas
     x, y     - top-left position
     w, h     - width and height
     radius   - corner radius (single value or [rx ry])
     opts     - optional map (all paint options supported, see circle)

   Examples:
     (rounded-rect canvas 10 10 100 50 5)
     (rounded-rect canvas 10 10 100 50 [5 3] {:color [0.29 0.56 0.85 1.0]})
     (rounded-rect canvas 10 10 100 50 10 {:blur 3.0 :shadow {:dx 2 :dy 2 :blur 5}})"
  ([^Canvas canvas x y w h radius]
   (rounded-rect canvas x y w h radius {}))
  ([^Canvas canvas x y w h radius opts]
   (let [[rx ry] (if (vector? radius) radius [radius radius])
         rrect (RRect/makeXYWH (float x) (float y) (float w) (float h) (float rx) (float ry))]
     (if-let [paint (:paint opts)]
       (.drawRRect canvas rrect paint)
       (gfx/with-paint [paint opts]
         (.drawRRect canvas rrect paint))))))

;; ============================================================
;; Line
;; ============================================================

(defn line
  "Draw a line between two points.

   Args:
     canvas   - drawing canvas
     x1, y1   - start point
     x2, y2   - end point
     opts     - optional map (all paint options supported, see circle)
                Note: :mode is automatically set to :stroke

   Examples:
     (line canvas 0 0 100 100)
     (line canvas 0 0 100 100 {:color [0.29 0.56 0.85 1.0] :stroke-width 2})
     (line canvas 0 0 100 100 {:dash [10 5] :stroke-cap :round})"
  ([^Canvas canvas x1 y1 x2 y2]
   (line canvas x1 y1 x2 y2 {}))
  ([^Canvas canvas x1 y1 x2 y2 opts]
   (let [opts (assoc opts :mode :stroke)]
     (if-let [paint (:paint opts)]
       (.drawLine canvas (float x1) (float y1) (float x2) (float y2) paint)
       (gfx/with-paint [paint opts]
         (.drawLine canvas (float x1) (float y1) (float x2) (float y2) paint))))))

;; ============================================================
;; Path
;; ============================================================

(defn path
  "Draw a path on canvas.

   Args:
     canvas - drawing canvas
     p      - Path instance (from lib.graphics.path)
     opts   - optional map (all paint options supported, see circle)

   Examples:
     (path canvas my-path)
     (path canvas my-path {:color [0.29 0.56 0.85 1.0]})
     (path canvas my-path {:mode :stroke :stroke-width 2})
     (path canvas star-path {:gradient {:type :linear ...}})"
  ([^Canvas canvas ^Path p]
   (path canvas p {}))
  ([^Canvas canvas ^Path p opts]
   (if-let [paint (:paint opts)]
     (.drawPath canvas p paint)
     (gfx/with-paint [paint opts]
       (.drawPath canvas p paint)))))

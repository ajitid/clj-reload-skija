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

;; ============================================================
;; Oval
;; ============================================================

(defn oval
  "Draw an oval (ellipse) inscribed in the given bounding box.

   Args:
     canvas - drawing canvas
     x, y   - top-left of bounding box
     w, h   - width and height
     opts   - optional map (all paint options supported, see circle)

   Examples:
     (oval canvas 10 10 100 50)
     (oval canvas 10 10 100 50 {:color [0.29 0.56 0.85 1.0]})
     (oval canvas 10 10 100 50 {:mode :stroke :stroke-width 2})"
  ([^Canvas canvas x y w h]
   (oval canvas x y w h {}))
  ([^Canvas canvas x y w h opts]
   (let [r (Rect/makeXYWH (float x) (float y) (float w) (float h))]
     (if-let [paint (:paint opts)]
       (.drawOval canvas r paint)
       (gfx/with-paint [paint opts]
         (.drawOval canvas r paint))))))

;; ============================================================
;; Arc
;; ============================================================

(defn arc
  "Draw an arc (portion of an oval outline or wedge/pie shape).

   Args:
     canvas      - drawing canvas
     x, y        - top-left of bounding oval
     w, h        - width and height of bounding oval
     start-angle - start angle in degrees (0 = 3 o'clock, clockwise)
     sweep-angle - sweep angle in degrees
     opts        - optional map (all paint options supported, see circle)
                   :wedge true → pie/wedge shape (includeCenter=true)
                   Without :wedge, forces :mode :stroke (open arc)

   Examples:
     (arc canvas 10 10 100 100 0 90)
     (arc canvas 10 10 100 100 0 270 {:wedge true :color [0.29 0.56 0.85 1.0]})
     (arc canvas 10 10 100 100 45 180 {:stroke-width 3})"
  ([^Canvas canvas x y w h start-angle sweep-angle]
   (arc canvas x y w h start-angle sweep-angle {}))
  ([^Canvas canvas x y w h start-angle sweep-angle opts]
   (let [wedge (boolean (:wedge opts))
         opts (if wedge opts (assoc opts :mode :stroke))
         left (float x) top (float y)
         right (float (+ x w)) bottom (float (+ y h))]
     (if-let [paint (:paint opts)]
       (.drawArc canvas left top right bottom (float start-angle) (float sweep-angle) wedge paint)
       (gfx/with-paint [paint opts]
         (.drawArc canvas left top right bottom (float start-angle) (float sweep-angle) wedge paint))))))

;; ============================================================
;; Point
;; ============================================================

(defn point
  "Draw a single point.

   Args:
     canvas - drawing canvas
     x, y   - point position
     opts   - optional map (all paint options supported, see circle)
              Defaults: :mode :stroke, :stroke-cap :round, :stroke-width 4

   Examples:
     (point canvas 100 100)
     (point canvas 100 100 {:color [1 0 0 1] :stroke-width 8})
     (point canvas 100 100 {:stroke-cap :square :stroke-width 6})"
  ([^Canvas canvas x y]
   (point canvas x y {}))
  ([^Canvas canvas x y opts]
   (let [opts (merge {:stroke-cap :round :stroke-width 4} opts {:mode :stroke})]
     (if-let [paint (:paint opts)]
       (.drawPoint canvas (float x) (float y) paint)
       (gfx/with-paint [paint opts]
         (.drawPoint canvas (float x) (float y) paint))))))

;; ============================================================
;; Double Rounded Rectangle
;; ============================================================

(defn drrect
  "Draw a double rounded rectangle (ring/donut/cutout shape).

   Args:
     canvas  - drawing canvas
     ox, oy  - outer rect top-left
     ow, oh  - outer rect width/height
     oradius - outer corner radius (single number or [rx ry])
     ix, iy  - inner rect top-left
     iw, ih  - inner rect width/height
     iradius - inner corner radius (single number or [rx ry])
     opts    - optional map (all paint options supported, see circle)

   Examples:
     (drrect canvas 10 10 200 200 20 30 30 140 140 10)
     (drrect canvas 10 10 200 200 20 30 30 140 140 10 {:color [0.29 0.56 0.85 1.0]})"
  ([^Canvas canvas ox oy ow oh oradius ix iy iw ih iradius]
   (drrect canvas ox oy ow oh oradius ix iy iw ih iradius {}))
  ([^Canvas canvas ox oy ow oh oradius ix iy iw ih iradius opts]
   (let [[orx ory] (if (vector? oradius) oradius [oradius oradius])
         [irx iry] (if (vector? iradius) iradius [iradius iradius])
         outer (RRect/makeXYWH (float ox) (float oy) (float ow) (float oh) (float orx) (float ory))
         inner (RRect/makeXYWH (float ix) (float iy) (float iw) (float ih) (float irx) (float iry))]
     (if-let [paint (:paint opts)]
       (.drawDRRect canvas outer inner paint)
       (gfx/with-paint [paint opts]
         (.drawDRRect canvas outer inner paint))))))

;; ============================================================
;; Rect Shadow (Skia analytical fast path)
;; ============================================================

(defn rect-shadow
  "Draw an analytical rectangle shadow (Skia fast path — no GPU blur pass).

   Unlike :shadow paint option which uses generic image filters, this uses Skia's
   drawRectShadow which computes an exact Gaussian approximation analytically.
   Significantly faster for rect/rounded-rect shadows (UI cards, panels, buttons).

   Args:
     canvas - drawing canvas
     x, y   - rect top-left
     w, h   - rect width/height
     opts   - map with shadow options:
              :dx     - horizontal offset (default 0)
              :dy     - vertical offset (default 0)
              :blur   - blur radius (default 4)
              :spread - spread radius (optional, 0 if omitted)
              :color  - [r g b a] floats 0.0-1.0 (default [0 0 0 0.5])
              :clip   - clip shadow to outside rect bounds (default true)
                        false → shadow bleeds through under rect

   Examples:
     (rect-shadow canvas 10 10 200 100 {:blur 8 :dy 4})
     (rect-shadow canvas 10 10 200 100 {:blur 12 :color [0 0 0.2 0.6] :clip false})
     (rect-shadow canvas 10 10 200 100 {:blur 8 :spread 4 :dy 4})"
  [^Canvas canvas x y w h opts]
  (let [{:keys [dx dy blur spread color clip]
         :or {dx 0 dy 0 blur 4 color [0 0 0 0.5] clip true}} opts
        [r g b a] color
        argb (unchecked-int (bit-or (bit-shift-left (int (* (float a) 255)) 24)
                                    (bit-shift-left (int (* (float r) 255)) 16)
                                    (bit-shift-left (int (* (float g) 255)) 8)
                                    (int (* (float b) 255))))
        rect (Rect/makeXYWH (float x) (float y) (float w) (float h))]
    (if spread
      (if clip
        (.drawRectShadow canvas rect (float dx) (float dy) (float blur) (float spread) argb)
        (.drawRectShadowNoclip canvas rect (float dx) (float dy) (float blur) (float spread) argb))
      (if clip
        (.drawRectShadow canvas rect (float dx) (float dy) (float blur) argb)
        (.drawRectShadowNoclip canvas rect (float dx) (float dy) (float blur) (float 0) argb)))))

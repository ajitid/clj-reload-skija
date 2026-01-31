(ns app.ui.group
  "Collapsible group widget for organizing controls.

   A group displays a clickable header with expand/collapse arrow
   and optionally shows indented child controls.

   Colors use [r g b a] float vectors (0.0-1.0) for Skia Color4f."
  (:require [lib.color.core :as color]
            [lib.color.open-color :as oc]
            [lib.text.core :as text]
            [lib.graphics.path :as gpath]
            [lib.graphics.shapes :as shapes])
  (:import [io.github.humbleui.skija Canvas Paint Color4f]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Default styling - [r g b a] floats
;; ============================================================

(def default-header-height 26)
(def default-header-bg-color [0.267 0.267 0.267 1.0])
(def default-header-text-color color/white)
(def default-arrow-color oc/gray-4)
(def default-font-size 14)
(def default-indent 10)
(def default-vertical-spacing 6)
(def default-top-padding 6)
(def default-bottom-padding 6)

;; ============================================================
;; Geometry calculations
;; ============================================================

(defn header-bounds
  "Calculate header clickable area."
  [x y w header-height]
  [x y w header-height])

(defn total-height
  "Calculate group height (header + content if expanded).

   Parameters:
   - collapsed?: boolean
   - children: vector of {:height num}
   - opts: map with :vertical-spacing, :top-padding, :bottom-padding"
  [collapsed? children opts]
  (let [header-height (or (:header-height opts) default-header-height)
        vertical-spacing (or (:vertical-spacing opts) default-vertical-spacing)
        top-padding (or (:top-padding opts) default-top-padding)
        bottom-padding (or (:bottom-padding opts) default-bottom-padding)]
    (if collapsed?
      header-height
      (+ header-height
         top-padding
         (reduce + 0 (map :height children))
         (* vertical-spacing (max 0 (dec (count children))))
         bottom-padding))))

;; ============================================================
;; Drawing
;; ============================================================

(defn draw-arrow
  "Draw expand/collapse arrow indicator."
  [^Canvas canvas x y collapsed? arrow-color]
  (let [arrow-path (if collapsed?
                     ;; Right-pointing arrow
                     (gpath/polygon [x y
                                     (+ x 6) (+ y 4)
                                     x (+ y 8)])
                     ;; Down-pointing arrow
                     (gpath/polygon [x y
                                     (+ x 8) y
                                     (+ x 4) (+ y 6)]))]
    (shapes/path canvas arrow-path {:color arrow-color})))

(defn draw
  "Draw collapsible group with header and children.

   Parameters:
   - canvas: Skija Canvas
   - label: String label for the group
   - collapsed?: Boolean indicating collapsed state
   - bounds: [x y w h] bounding box
   - children: Vector of {:height num :draw-fn (fn [canvas x y w])}
   - opts: Map of options:
     - :header-height (default 26)
     - :header-bg-color [r g b a] floats (default [0.267 0.267 0.267 1.0])
     - :header-text-color [r g b a] floats (default color/white)
     - :arrow-color [r g b a] floats (default oc/gray-4)
     - :font-size (default 14)
     - :indent (default 10)
     - :vertical-spacing (default 6)
     - :top-padding (default 6)
     - :bottom-padding (default 6)

   Layout:
     +------------------+
     | > Group Label    |  (clickable header)
     +------------------+
     |   [child 1]      |  (indented when expanded)
     |   [child 2]      |
     +------------------+"
  [^Canvas canvas label collapsed? [gx gy gw gh] children opts]
  (let [header-height (or (:header-height opts) default-header-height)
        [hr hg hb ha] (or (:header-bg-color opts) default-header-bg-color)
        header-text-color (or (:header-text-color opts) default-header-text-color)
        arrow-color (or (:arrow-color opts) default-arrow-color)
        font-size (or (:font-size opts) default-font-size)
        indent (or (:indent opts) default-indent)
        vertical-spacing (or (:vertical-spacing opts) default-vertical-spacing)
        top-padding (or (:top-padding opts) default-top-padding)]
    ;; Draw header background
    (with-open [bg-paint (doto (Paint.)
                           (.setColor4f (Color4f. (float hr) (float hg) (float hb) (float ha))))]
      (.drawRect canvas (Rect/makeXYWH (float gx) (float gy) (float gw) (float header-height)) bg-paint))
    ;; Draw arrow
    (draw-arrow canvas (+ gx 8) (+ gy (/ (- header-height 8) 2)) collapsed? arrow-color)
    ;; Draw label (vertically centered better)
    (text/text canvas label
               (+ gx 22)
               (+ gy (/ header-height 2) 4)
               {:size font-size :color header-text-color})
    ;; Draw children if expanded
    (when-not collapsed?
      (loop [children children
             cy (+ gy header-height top-padding)]
        (when-let [child (first children)]
          (let [child-height (:height child)
                draw-fn (:draw-fn child)]
            ;; Call child draw function with indented x position
            (draw-fn canvas (+ gx indent) cy (- gw indent))
            ;; Recurse to next child
            (recur (rest children) (+ cy child-height vertical-spacing))))))))

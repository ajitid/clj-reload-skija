(ns lib.layout.render
  "Rendering utilities for layout trees with Skija."
  (:require [lib.layout.core :as layout])
  (:import [io.github.humbleui.skija Canvas Paint PaintMode Rect]))

(defn walk-layout
  "Walk a laid-out tree, calling render-fn for each node.
   render-fn receives (node bounds) where bounds is {:x :y :w :h}.

   Example:
     (walk-layout tree
       (fn [node {:keys [x y w h]}]
         (when-let [color (:color node)]
           (.drawRect canvas (Rect/makeXYWH x y w h) paint))))"
  [tree render-fn]
  (when tree
    (let [bounds (:bounds tree)]
      (render-fn tree bounds)
      (doseq [child (:children tree)]
        (walk-layout child render-fn)))))

(defn draw-debug-bounds
  "Draw debug rectangles showing layout bounds.
   Useful for visualizing the layout structure.

   Options:
     :stroke-color - color for outlines (default 0x80FF0000)
     :stroke-width - line width (default 1)
     :fill-color   - fill color (default nil, no fill)
     :show-labels  - show size labels (default false)"
  ([canvas tree] (draw-debug-bounds canvas tree {}))
  ([^Canvas canvas tree opts]
   (let [{:keys [stroke-color stroke-width fill-color]
          :or {stroke-color 0x80FF0000
               stroke-width 1}} opts
         stroke-paint (doto (Paint.)
                        (.setColor (unchecked-int stroke-color))
                        (.setMode PaintMode/STROKE)
                        (.setStrokeWidth stroke-width))
         fill-paint (when fill-color
                      (doto (Paint.)
                        (.setColor (unchecked-int fill-color))
                        (.setMode PaintMode/FILL)))]
     (walk-layout tree
       (fn [_node {:keys [x y w h]}]
         (let [rect (Rect/makeXYWH x y w h)]
           (when fill-paint
             (.drawRect canvas rect fill-paint))
           (.drawRect canvas rect stroke-paint)))))))

(defn make-renderer
  "Create a render function that draws a layout tree.
   Takes a draw-node function that handles individual node rendering.

   draw-node signature: (draw-node canvas node bounds)

   Returns: (fn [canvas tree parent-bounds])

   Example:
     (def render-ui
       (make-renderer
         (fn [canvas node {:keys [x y w h]}]
           (when-let [color (:fill node)]
             (.drawRect canvas (Rect/makeXYWH x y w h)
               (doto (Paint.) (.setColor color)))))))"
  [draw-node]
  (fn render
    ([canvas tree]
     (render canvas tree {:x 0 :y 0 :w 800 :h 600}))
    ([canvas tree parent-bounds]
     (let [laid-out (layout/layout tree parent-bounds)]
       (walk-layout laid-out
         (fn [node bounds]
           (draw-node canvas node bounds)))
       laid-out))))

(defn render-tree
  "Render a layout tree with a draw-node function.
   Convenience wrapper around make-renderer for one-off rendering.

   Arguments:
     canvas       - Skija Canvas
     tree         - layout tree (will be laid out if not already)
     parent-bounds - {:x :y :w :h} for root positioning
     draw-node    - (fn [canvas node bounds]) for each node

   Example:
     (render-tree canvas my-tree {:x 0 :y 0 :w 800 :h 600}
       (fn [canvas node {:keys [x y w h]}]
         (when-let [bg (:background node)]
           (.drawRect canvas (Rect/makeXYWH x y w h)
             (doto (Paint.) (.setColor bg))))))"
  [canvas tree parent-bounds draw-node]
  (let [laid-out (if (:bounds tree)
                   tree
                   (layout/layout tree parent-bounds))]
    (walk-layout laid-out
      (fn [node bounds]
        (draw-node canvas node bounds)))
    laid-out))

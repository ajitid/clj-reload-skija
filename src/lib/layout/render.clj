(ns lib.layout.render
  "Rendering utilities for layout trees with Skija."
  (:require [lib.layout.core :as layout]
            [lib.layout.scroll :as scroll])
  (:import [io.github.humbleui.skija Canvas Paint PaintMode]
           [io.github.humbleui.types Rect RRect]))

;; ============================================================
;; Helpers
;; ============================================================

(defn- normalize-overflow
  "Convert overflow spec to normalized map form."
  [overflow]
  (cond
    (nil? overflow) {:x :visible :y :visible}
    (keyword? overflow) {:x overflow :y overflow}
    (map? overflow) (merge {:x :visible :y :visible} overflow)))

;; ============================================================
;; Scrollbar Rendering
;; ============================================================

(defn- render-scrollbars
  "Render scrollbars for scrollable container.

  Args:
    canvas - Skija Canvas
    node - layout node (must have :id)
    bounds - container bounds {:x :y :w :h}
    scroll-offset - current scroll {:x :y}"
  [^Canvas canvas node bounds scroll-offset]
  (when-let [dims (scroll/get-dimensions (:id node))]
    (let [{:keys [viewport content]} dims
          {:keys [x y w h]} bounds

          ;; Use constants from scroll.clj (single source of truth)
          scrollbar-radius 3
          track-color 0x20FFFFFF
          thumb-color 0x60FFFFFF]

      ;; Vertical scrollbar (when content taller than viewport)
      (when (and (pos? (:h viewport)) (> (:h content) (:h viewport)))
        (let [track-height (- h (* 2 scroll/scrollbar-margin))
              thumb-ratio (/ (:h viewport) (:h content))
              thumb-height (max scroll/scrollbar-min-thumb (* track-height thumb-ratio))
              max-scroll (- (:h content) (:h viewport))
              scroll-progress (if (pos? max-scroll)
                               (/ (:y scroll-offset) max-scroll)
                               0)
              thumb-y (* scroll-progress (- track-height thumb-height))

              track-x (- (+ x w) scroll/scrollbar-width scroll/scrollbar-margin)
              track-y (+ y scroll/scrollbar-margin)]

          ;; Draw track
          (with-open [track-paint (doto (Paint.)
                                    (.setColor (unchecked-int track-color)))]
            (.drawRRect canvas
                        (RRect/makeXYWH track-x track-y scroll/scrollbar-width track-height scrollbar-radius)
                        track-paint))

          ;; Draw thumb
          (with-open [thumb-paint (doto (Paint.)
                                    (.setColor (unchecked-int thumb-color)))]
            (.drawRRect canvas
                        (RRect/makeXYWH track-x (+ track-y thumb-y) scroll/scrollbar-width thumb-height scrollbar-radius)
                        thumb-paint))))

      ;; Horizontal scrollbar (when content wider than viewport)
      (when (and (pos? (:w viewport)) (> (:w content) (:w viewport)))
        (let [track-width (- w (* 2 scroll/scrollbar-margin))
              thumb-ratio (/ (:w viewport) (:w content))
              thumb-width (max scroll/scrollbar-min-thumb (* track-width thumb-ratio))
              max-scroll (- (:w content) (:w viewport))
              scroll-progress (if (pos? max-scroll)
                               (/ (:x scroll-offset) max-scroll)
                               0)
              thumb-x (* scroll-progress (- track-width thumb-width))

              track-x (+ x scroll/scrollbar-margin)
              track-y (- (+ y h) scroll/scrollbar-width scroll/scrollbar-margin)]

          ;; Draw track
          (with-open [track-paint (doto (Paint.)
                                    (.setColor (unchecked-int track-color)))]
            (.drawRRect canvas
                        (RRect/makeXYWH track-x track-y track-width scroll/scrollbar-width scrollbar-radius)
                        track-paint))

          ;; Draw thumb
          (with-open [thumb-paint (doto (Paint.)
                                    (.setColor (unchecked-int thumb-color)))]
            (.drawRRect canvas
                        (RRect/makeXYWH (+ track-x thumb-x) track-y thumb-width scroll/scrollbar-width scrollbar-radius)
                        thumb-paint)))))))

;; ============================================================
;; Layout Tree Walking
;; ============================================================

(defn walk-layout
  "Walk a laid-out tree, calling render-fn for each node.
   render-fn receives (node bounds canvas) where bounds is {:x :y :w :h :z :overflow}.
   Children are sorted by :z index (lower z rendered first, higher z on top).

   Overflow handling:
   - :visible - children can render beyond parent bounds
   - :clip - children clipped to parent bounds
   - :scroll - children clipped + translated by scroll offset + scrollbars rendered

   Example:
     (walk-layout tree canvas
       (fn [node {:keys [x y w h]} canvas]
         (when-let [color (:color node)]
           (.drawRect canvas (Rect/makeXYWH x y w h) paint))))"
  ([tree render-fn]
   ;; Legacy arity without canvas - no clipping/scroll support
   (when tree
     (let [bounds (:bounds tree)]
       (render-fn tree bounds)
       (let [sorted-children (sort-by #(get-in % [:bounds :z] 0) (:children tree))]
         (doseq [child sorted-children]
           (walk-layout child render-fn))))))
  ([tree ^Canvas canvas render-fn]
   ;; With canvas - supports overflow clipping and scrolling
   (when tree
     (let [bounds (:bounds tree)
           overflow (normalize-overflow (get bounds :overflow))
           {:keys [x y]} overflow

           ;; Determine clipping/scrolling behavior per axis
           clip-x? (#{:clip :scroll} x)
           clip-y? (#{:clip :scroll} y)
           scroll-x? (= :scroll x)
           scroll-y? (= :scroll y)

           ;; Get scroll offset if scrollable (requires :id)
           scroll-offset (when (and (:id tree) (or scroll-x? scroll-y?))
                          (scroll/get-scroll (:id tree)))

           needs-clip? (or clip-x? clip-y?)
           needs-scroll? (and scroll-offset (or scroll-x? scroll-y?))]

       ;; Render this node first
       (render-fn tree bounds canvas)

       (let [sorted-children (sort-by #(get-in % [:bounds :z] 0) (:children tree))]
         (if needs-clip?
           ;; Save canvas state, apply clipping and scroll translation
           (let [save-count (.save canvas)
                 {:keys [x y w h]} bounds]

             ;; Clip to parent bounds
             (.clipRect canvas (Rect/makeXYWH x y w h))

             ;; Translate by scroll offset (negative = content moves opposite to scroll)
             (when needs-scroll?
               (let [dx (if scroll-x? (- (:x scroll-offset 0)) 0)
                     dy (if scroll-y? (- (:y scroll-offset 0)) 0)]
                 (.translate canvas (float dx) (float dy))))

             ;; Render children
             (doseq [child sorted-children]
               (walk-layout child canvas render-fn))

             ;; Restore canvas state (removes clip and translation)
             (.restoreToCount canvas save-count)

             ;; Render scrollbars AFTER restoring (so they're not clipped/translated)
             (when needs-scroll?
               (render-scrollbars canvas tree bounds scroll-offset)))

           ;; No clipping - children can overflow
           (doseq [child sorted-children]
             (walk-layout child canvas render-fn))))))))

;; ============================================================
;; Convenience Functions
;; ============================================================

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
    (walk-layout laid-out canvas
      (fn [node bounds _canvas]
        (draw-node canvas node bounds)))
    laid-out))

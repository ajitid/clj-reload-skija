Files Created

src/lib/layout/core.clj - Core layout algorithm

- parse-unit - parses px, %, and stretch ("1s") values
- layout - computes {:x :y :w :h} bounds for entire tree
- hstack, vstack, grid - convenience constructors
- box, spacer - element helpers

src/lib/layout/render.clj - Skija integration

- walk-layout - traverse tree with render callback
- draw-debug-bounds - visualize layout boxes
- render-tree - one-shot render with draw function

Usage Example

```clj
  (require '[lib.layout.core :as layout]
           '[lib.layout.render :as layout-render])

  ;; Define UI tree
  (def ui
    (layout/vstack {:padding 20 :gap 10}
      [(layout/hstack {:gap 10 :align :center}
         [(layout/box {:w 100 :h 40})
          (layout/spacer)
          (layout/box {:w 100 :h 40})])
       (layout/box {:w "100%" :h "1s"})  ;; fills remaining space
       (layout/grid {:cols 3 :gap 5}
         (repeat 9 (layout/box {:h 50})))]))

  ;; In draw function:
  (layout-render/render-tree canvas ui {:x 0 :y 0 :w width :h height}
    (fn [canvas node {:keys [x y w h]}]
      (when-let [color (:fill node)]
        (.drawRect canvas (Rect/makeXYWH x y w h) paint))))
```

Unit Types

- 100 → 100 pixels
- "50%" → 50% of parent
- "1s" / "2s" → stretch (proportional remaining space)

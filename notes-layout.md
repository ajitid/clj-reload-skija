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

---

https://news.ycombinator.com/item?id=16540529
https://github.com/lynaghk/subform-layout

Subform does not support intrinsic sizing

> is this flex layout or grid layout or flutter layout or swiftui layout or subform layout

⏺ This is Subform layout - exactly what you asked me to implement from the articles you shared!

Key distinguishing characteristics:

| Feature | This Implementation                          |
| ------- | -------------------------------------------- |
| Model   | before → size → after uniformly on both axes |
| Units   | px, %, stretch ("1s", "2s")                  |
| Modes   | stack-horizontal, stack-vertical, grid       |

Comparison:

- Flexbox: More complex (flex-grow/shrink/basis, justify-content, align-items, margin collapse). Our "1s"/"2s" is like flex-grow but simpler.
- CSS Grid: Similar fr units, but Subform unifies stacks and grids under one model.
- Flutter: Similar feel (Row, Column, Expanded, Spacer) but Flutter has intrinsic sizing.
- SwiftUI: Similar feel (HStack, VStack, Spacer) but SwiftUI has intrinsic sizing.
- Subform: ✓ Fewer concepts, uniform model, no intrinsic sizing, stretch units.

The main Subform philosophy we followed: "fewer concepts, applied uniformly" - same {:before :size :after} model everywhere instead of flexbox's many interacting properties.

> what do you mean by intrinsic sizing

⏺ Intrinsic sizing = elements can measure their own "natural" size based on content, and parents can query this before layout.

Examples:

┌─────────────────────────────────────────────────────────────┐
│ Flutter/SwiftUI (HAS intrinsic sizing): │
│ │
│ Text("Hello") → knows it needs ~45px width │
│ Image(file) → knows it's 200×150 │
│ Row([A, B]) → asks children, sums their widths │
│ │
│ Parent can "wrap" to fit children automatically │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Subform/Our system (NO intrinsic sizing): │
│ │
│ Box with text → you must specify size explicitly │
│ Row([A, B]) → doesn't ask children their preferred size │
│ │
│ Everything flows top-down: parent dictates space, │
│ children fill or use explicit sizes │
└─────────────────────────────────────────────────────────────┘

Trade-off:

|     | Intrinsic                 | No Intrinsic (Subform)         |
| --- | ------------------------- | ------------------------------ |
| Pro | "Wrap content" just works | Simpler algorithm, predictable |
| Con | Two-pass layout, complex  | Must specify sizes manually    |

Subform intentionally omits it for simplicity. If you wanted a text label to fit its content, you'd need to measure the text yourself and set the size explicitly.

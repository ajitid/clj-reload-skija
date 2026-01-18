# Layout System Improvements Plan

## Summary

Make the layout system more Subform-like by:

1. Rename `horizontal`/`vertical` to `x`/`y` throughout
2. Refactoring children-layout spacing to use `x`/`y` with `before`/`between`/`after`
3. Adding "hug" sizing (parent shrinks to fit children)
4. Adding "pop-out" mode (absolute positioning within parent)

## Files to Modify

- `/Users/as186073/Downloads/clj-reloadable-skija-on-window-main/src/lib/layout/core.clj`

---

## Conceptual Model (Subform-style)

```clojure
{:layout {...}           ;; How THIS element is sized/positioned
 :children-layout {...}  ;; How this element arranges its CHILDREN
 :children [...]}
```

| Property          | Contains            | Purpose                                            |
| ----------------- | ------------------- | -------------------------------------------------- |
| `layout`          | `:mode`, `:x`, `:y` | Element's own size, position, and positioning mode |
| `children-layout` | `:mode`, `:x`, `:y` | Container's arrangement of children                |

**Note:** Both have `:mode` but different meanings:

- `layout.mode` → `:pop-out` (how parent positions this element)
- `children-layout.mode` → `:stack-horizontal`, `:stack-vertical`, `:grid`

---

## Change 1: Rename `horizontal`/`vertical` to `x`/`y` in element layout

### Current API

```clojure
{:layout {:horizontal {:before 10 :size 100 :after 10}
          :vertical {:before 5 :size 50 :after 5}}}
```

### New API

```clojure
{:layout {:x {:before 10 :size 100 :after 10}
          :y {:before 5 :size 50 :after 5}}}
```

### Implementation Steps

1. Update `get-axis-props` to use `:x`/`:y` instead of `:horizontal`/`:vertical`
2. Update `axis-defaults` comments
3. Update all references in `layout-stack`, `layout-grid`, `layout`
4. Update convenience constructors (`box`, `spacer`)
5. Update docstrings

---

## Change 2: Refactor children-layout spacing

### Current API (to be replaced)

```clojure
{:children-layout {:mode :stack-horizontal
                   :gap 10
                   :padding 20
                   :padding-left 10
                   :padding-right 20
                   :padding-top 5
                   :padding-bottom 15}}
```

### New API

```clojure
{:children-layout {:mode :stack-horizontal
                   :x {:before 10 :between 20 :after 20}
                   :y {:before 5 :after 15}}}

;; Grid example (both axes have :between)
{:children-layout {:mode :grid
                   :x {:before 10 :between 40 :after 10}
                   :y {:before 10 :between 20 :after 10}
                   :cols 3}}
```

### Implementation Steps

1. Update `children-layout-defaults` - remove `gap`, `padding`, `padding-*`
2. Add new defaults for `x` and `y` with `before`, `between`, `after`
3. Update `get-padding` → `get-spacing` to extract from `x`/`y` structure
4. Update `layout-stack` to use new spacing structure
5. Update `layout-grid` to use new spacing structure
6. Update docstrings for `hstack`, `vstack`, `grid`

---

## Change 3: Add "hug" sizing

### API

```clojure
;; Parent shrinks to fit children's sizes
{:layout {:x {:size "hug"}
          :y {:size "hug"}}}
```

### Behavior

- Parent calculates total size of children (including their before/after + gaps)
- Only works with parent-directed children that have determinable sizes
- Children with stretch sizing resolve to their minimum (or 0 if no min)

### Implementation Steps

1. Add `"hug"` as a recognized size unit in `parse-unit`
2. In `layout` function, after laying out children, if size is "hug":
   - Calculate total bounds of children
   - Update parent's bounds to fit
3. Handle edge cases:
   - No children → size 0
   - Only pop-out children → ignore them for hug calculation

---

## Change 4: Add "pop-out" mode

### API

```clojure
;; Child positions itself, ignores parent's stack/grid
{:layout {:mode :pop-out
          :x {:before 10 :size 50}
          :y {:before 20 :size 30}}}
```

### Behavior

- Child is positioned relative to parent's content area
- `before` = offset from parent's left/top edge
- Ignored by parent's stack/grid layout (doesn't take up flow space)
- Other children flow as if pop-out child doesn't exist

### Implementation Steps

1. In `layout-stack` and `layout-grid`, filter out pop-out children before calculating positions
2. After positioning parent-directed children, position pop-out children:
   - Use their `before` values as offsets from parent content area
3. Pop-out children still get laid out recursively (their children follow normal rules)

---

## What's NOT Changing

- `min`/`max` stay as separate properties (not `"1s_200"` syntax)
- Unit types: px, %, stretch (`"1s"`) - same parsing

---

## Migration Summary

| Old                  | New                                                        |
| -------------------- | ---------------------------------------------------------- |
| `:horizontal {...}`  | `:x {...}`                                                 |
| `:vertical {...}`    | `:y {...}`                                                 |
| `:padding 20`        | `:x {:before 20 :after 20} :y {:before 20 :after 20}`      |
| `:padding-left 10`   | `:x {:before 10}`                                          |
| `:padding-right 20`  | `:x {:after 20}`                                           |
| `:padding-top 5`     | `:y {:before 5}`                                           |
| `:padding-bottom 15` | `:y {:after 15}`                                           |
| `:gap 10`            | `:x {:between 10}` (hstack) or `:y {:between 10}` (vstack) |
| `:gap-x 20`          | `:x {:between 20}`                                         |
| `:gap-y 10`          | `:y {:between 10}`                                         |
| N/A                  | `:size "hug"`                                              |
| N/A                  | `:mode :pop-out`                                           |

## Sources

Here are the sources I referenced during our research:

Subform (Primary)

- https://github.com/lynaghk/subform-layout - Embeddable layout engine code/docs
- https://talk.subformapp.com/t/how-to-get-started-with-subform/1112.html - Tutorial with hug, stretch, sizing details
- https://subformapp.com/learn/ - Official tutorials (now archived)
- https://ryanlucas.org/writing/dynamic-layout-at-design-time/ - Design philosophy

Comparison Research

- https://deepwiki.com/nicbarker/clay - Clay layout model
- https://www.hackingwithswift.com/quick-start/swiftui/how-to-customize-stack-layouts-with-alignment-and-spacing - SwiftUI alignment/spacing
- https://docs.flutter.dev/ui/layout - Flutter Row/Column
- https://themobilecoder.com/axis-alignment-columns-rows-flutter/ - mainAxisAlignment/crossAxisAlignment

Medium Articles (couldn't fetch due to 403)

- https://subformapp.com/articles/why-not-flexbox/ - Subform's rationale

The Subform Talk forum and GitHub repo were the most valuable for understanding the actual API design.

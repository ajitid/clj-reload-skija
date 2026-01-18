# Layout System - New Features

## 1. Z-Index

Control stacking order of elements. Higher `:z` values render on top.

```clojure
;; Pop-out with z-index
{:layout {:mode :pop-out
          :z 10
          :x {:offset 0 :size 100}
          :y {:offset 0 :size 50}}}

;; Regular elements can also have z-index
{:layout {:z 5
          :x {:size 100}}}
```

**Note:** Children are sorted by z-index before rendering. Default z is 0.

---

## 2. Overflow Handling

Control what happens when children exceed parent bounds.

```clojure
{:children-layout {:mode :stack-y
                   :overflow :clip}  ; clips children to parent bounds
 :children [...]}
```

**Options:**
- `:visible` (default) - children can extend beyond bounds
- `:clip` - children clipped to parent bounds
- `:hidden` - alias for :clip

**Usage with canvas:** Use `(walk-layout tree canvas render-fn)` for clipping support.

---

## 3. Aspect Ratio

Maintain width/height proportions. One axis derives from the other.

```clojure
;; Width specified, height derived (16:9 video)
{:layout {:x {:size 320}
          :aspect 16/9}}  ; height = 320 / (16/9) = 180

;; Height specified, width derived
{:layout {:y {:size 100}
          :aspect 2}}  ; width = 100 * 2 = 200

;; With stretch - width fills, height maintains ratio
{:layout {:x {:size "1s"}
          :aspect 4/3}}
```

**Note:** `:aspect` = width / height. If both axes have explicit sizes, aspect is ignored.

---

## 4. Anchor/Origin for Pop-Out

Position pop-out elements from different reference points using `:anchor` and `:offset`.

```clojure
;; Bottom-right corner (e.g., FAB button)
{:layout {:mode :pop-out
          :anchor :bottom-right
          :x {:offset 20 :size 56}   ; 20px from right edge
          :y {:offset 20 :size 56}}} ; 20px from bottom edge

;; Centered overlay
{:layout {:mode :pop-out
          :anchor :center
          :x {:size 300}
          :y {:size 200}}}

;; Top-right (e.g., close button)
{:layout {:mode :pop-out
          :anchor :top-right
          :x {:offset 10 :size 30}
          :y {:offset 10 :size 30}}}
```

**Anchor options:**
- `:top-left` (default) - offset moves right/down
- `:top-right` - offset moves left/down
- `:bottom-left` - offset moves right/up
- `:bottom-right` - offset moves left/up
- `:center` - offset from center point

**Note:** Negative `:offset` values push elements outside parent bounds.

---

## 5. Per-Pair Spacing (Workaround)

To override `:between` spacing for specific pairs of children, insert a zero-width spacer:

```clojure
(hstack {:x {:between 10}}
  [(box {:w 50})
   {:layout {:x {:size 0}}}  ; zero-width spacer = no gap here
   (box {:w 50})             ; these two items are adjacent (no gap)
   (box {:w 50})])           ; normal 10px gap before this one
```

This achieves "gap is 10px between all children, except between items 1 and 2 where it's 0px".

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

### Values

- `:visible` (default) - Children can render beyond parent bounds
- `:clip` - Children clipped to parent bounds (no scrolling)
- `:scroll` - Children clipped with scroll capability

### Syntax

```clojure
;; Shorthand - applies to both axes
:overflow :clip
:overflow :scroll

;; Map syntax - per-axis control
:overflow {:x :clip :y :scroll}
:overflow {:y :scroll}  ;; x defaults to :visible
```

### Basic Example

```clojure
{:children-layout {:mode :stack-y
                   :overflow :clip}  ; clips children to parent bounds
 :children [...]}
```

**Usage with canvas:** Use `(walk-layout tree canvas render-fn)` for clipping/scroll support.

---

## 3. Scrolling

Containers with `:overflow :scroll` enable scrollable content with mouse wheel support.

### Requirements

1. Explicit `:id` on the scrollable container (for state tracking)
2. Initialize scroll state with `(scroll/init! id)`
3. Set content dimensions with `(scroll/set-dimensions! id viewport content)`

### Example

```clojure
(require '[lib.layout.scroll :as scroll])

;; In your UI tree
{:id :my-list
 :layout {:y {:size 300}}
 :children-layout {:mode :stack-y
                   :overflow {:y :scroll}}
 :children [...]}

;; Initialize scroll state (e.g., in init function)
(scroll/init! :my-list)

;; Set dimensions after layout (e.g., after render-tree)
(scroll/set-dimensions! :my-list
  {:w 200 :h 300}   ;; viewport
  {:w 200 :h 1000}) ;; content (total scrollable height)
```

### Scroll APIs

The `lib.layout.scroll` namespace provides:

**Read APIs:**
```clojure
(scroll/get-scroll :my-list)           ;; => {:x 0 :y 150}
(scroll/get-dimensions :my-list)       ;; => {:scroll {...} :viewport {...} :content {...}}
(scroll/get-scrollable-size :my-list)  ;; => {:x 0 :y 700} (max scroll)
(scroll/get-scroll-progress :my-list :y) ;; => 0.5 (50% scrolled)
(scroll/scrollable? :my-list :y)       ;; => true
```

**Write APIs:**
```clojure
(scroll/set-scroll! :my-list {:x 0 :y 200})
(scroll/scroll-by! :my-list {:x 0 :y 50})
(scroll/scroll-to-top! :my-list)
(scroll/scroll-to-bottom! :my-list)
```

**Watchers:**
```clojure
;; Watch for scroll changes
(def watcher-id
  (scroll/watch! :my-list
    (fn [old-pos new-pos]
      (println "Scrolled from" old-pos "to" new-pos))))

;; Stop watching
(scroll/unwatch! :my-list watcher-id)
```

### Scrollbar Rendering

Scrollbars are rendered automatically when:
- Overflow is `:scroll` on an axis
- Content size exceeds viewport size on that axis

Scrollbars appear as semi-transparent rounded rectangles on the right (vertical) or bottom (horizontal) edge.

---

## 4. Mixins (Lifecycle Hooks)

Reusable lifecycle behaviors for layout nodes. Mixins are maps with `:did-mount` and `:will-unmount` functions.

```clojure
(require '[lib.layout.mixins :as mixins])

;; Scrollable mixin - manages scroll state lifecycle
{:id :sidebar
 :mixins [(mixins/scrollable :y)]
 :overflow {:y :scroll}
 :children [...]}

;; Persist scroll when container hidden
{:mixins [(mixins/scrollable :y {:persist true})]}

;; Watch scroll changes
{:mixins [(mixins/on-scroll-changed
            (fn [id old new]
              (println id "scrolled from" old "to" new)))]}
```

### Virtual Scrolling

For long lists (1000s of items), use the virtual scroll mixin to only render visible items:

```clojure
(require '[lib.layout.mixins :as mixins])

;; Create virtual scroll mixin
(def contact-scroller
  (mixins/virtual-scroll
    @all-contacts      ;; all items (data)
    50                 ;; item height (px)
    (fn [contact idx]  ;; render function
      {:fill 0xFF404040
       :label (:name contact)})
    {:buffer 5}))      ;; extra items above/below viewport

;; In your UI, compute visible children dynamically
(defn contacts-list [viewport-height]
  {:id :contacts
   :layout {:y {:size viewport-height}}
   :children-layout {:mode :stack-y :overflow {:y :scroll}}
   :children (mixins/compute-visible-children contact-scroller :contacts viewport-height)})
```

The virtual scroll mixin:
- Only renders items visible in the viewport (plus a buffer)
- Automatically sets scroll dimensions based on total item count
- Uses absolute positioning for visible items

---

## 5. Aspect Ratio

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

## 6. Anchor/Origin for Pop-Out

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

## 7. Per-Pair Spacing (Workaround)

To override `:between` spacing for specific pairs of children, insert a zero-width spacer:

```clojure
(hstack {:x {:between 10}}
  [(box {:w 50})
   {:layout {:x {:size 0}}}  ; zero-width spacer = no gap here
   (box {:w 50})             ; these two items are adjacent (no gap)
   (box {:w 50})])           ; normal 10px gap before this one
```

This achieves "gap is 10px between all children, except between items 1 and 2 where it's 0px".

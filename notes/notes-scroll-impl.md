# Plan: Comprehensive Scrolling Implementation

## Summary
1. Remove `:hidden` alias (keep `:clip`, `:visible`, add `:scroll`)
2. Add map syntax for per-axis overflow control
3. Implement scroll state management (Clay-style, layout system owns state)
4. Add mixin system for scroll lifecycle and behaviors
5. Implement scroll-aware hit testing
6. Add scroll rendering (translation + scrollbars)
7. Add input handling (mouse wheel + drag-to-scroll)
8. Add programmatic scroll APIs
9. Support virtual scrolling for long lists

---

## User-Facing API (Final)

### 1. Overflow Declaration
```clojure
;; Shorthand - applies to both axes
:overflow :clip
:overflow :scroll

;; Map syntax - per-axis control
:overflow {:x :clip :y :scroll}
:overflow {:y :scroll}  ;; x defaults to :visible
```

### 2. Scroll Mixin
```clojure
{:id :sidebar
 :overflow {:y :scroll}
 :mixins [(mixins/scrollable :y)]  ;; enables scrolling
 :children [...]}

;; Persist scroll when container hidden
{:mixins [(mixins/scrollable :y {:persist true})]}

;; Watch scroll changes
{:mixins [(mixins/on-scroll-changed
            (fn [id old new] ...))]}
```

### 3. Programmatic Scroll APIs
```clojure
;; Read/write scroll position
(scroll/get-scroll :sidebar)  ;; => {:x 0 :y 150}
(scroll/set-scroll! :sidebar {:x 0 :y 200})
(scroll/scroll-to-top! :sidebar)
(scroll/scroll-to-bottom! :sidebar)
(scroll/scroll-by! :sidebar {:x 0 :y 50})

;; Query dimensions
(scroll/get-dimensions :sidebar)  ;; => {:viewport {...} :content {...} :scroll {...}}
(scroll/get-scrollable-size :sidebar)  ;; => {:x 0 :y 1400} (max scroll)
(scroll/get-scroll-progress :sidebar :y)  ;; => 0.5 (50% scrolled)
(scroll/scrollable? :sidebar :y)  ;; => true
```

### 4. Virtual Scroll Mixin
```clojure
{:id :contacts
 :overflow {:y :scroll}
 :mixins [(mixins/scrollable :y)
          (mixins/virtual-scroll
            @items       ;; all items
            80           ;; item height
            render-fn)]} ;; (fn [item idx] {...})
```

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│  User Code (app.ui)                                 │
│  - Declares :overflow and :mixins                   │
└──────────────┬──────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────┐
│  Layout System (lib.layout.core)                    │
│  - Processes mixins (did-mount/will-unmount)        │
│  - Normalizes overflow to map                       │
│  - Stores content dimensions                        │
└──────────────┬──────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────┐
│  Scroll Module (lib.layout.scroll) - OWNS STATE     │
│  - defonce scroll-states atom                       │
│  - Provides init!/destroy!/set-scroll! APIs         │
└──────────────┬──────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────┐
│  Rendering (lib.layout.render)                      │
│  - Applies scroll offset via canvas.translate       │
│  - Renders scrollbars when :scroll active           │
└──────────────┬──────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────┐
│  Gesture System (lib.gesture.*)                     │
│  - Scroll-aware hit testing (transforms coords)     │
│  - Mouse wheel + drag scroll handlers               │
└─────────────────────────────────────────────────────┘
```

---

## Part 1: Remove `:hidden` Alias

### File: `src/lib/layout/render.clj`

**Line 31 - Change:**
```clojure
;; Before:
clip? (#{:clip :hidden} overflow)

;; After:
clip? (= :clip overflow)  ;; temporary, will be updated in Part 3
```

### File: `notes-layout-new-new-stuff-added.md`

Remove any references to `:hidden`, document only `:visible`, `:clip`, `:scroll`.

---

## Part 2: Create Scroll Module (lib.layout.scroll)

### File: `src/lib/layout/scroll.clj` (NEW)

```clojure
(ns lib.layout.scroll
  "Scroll state management and APIs.

  State is owned by this module (defonce atoms) and survives hot-reload.
  User code interacts via public APIs only.")

;; ============================================================
;; State (internal, survives hot-reload)
;; ============================================================

(defonce ^:private scroll-states (atom {}))
;; {:sidebar {:scroll {:x 0 :y 150}
;;            :viewport {:w 200 :h 600}
;;            :content {:w 200 :h 2000}}}

(defonce ^:private watchers (atom {}))
;; {:sidebar {watcher-uuid-1 (fn [old new] ...)
;;            watcher-uuid-2 (fn [old new] ...)}}

;; ============================================================
;; Lifecycle (called by mixins)
;; ============================================================

(defn init!
  "Initialize scroll state for a container. Called on mount."
  [id]
  (when-not (contains? @scroll-states id)
    (swap! scroll-states assoc id
      {:scroll {:x 0 :y 0}
       :viewport {:w 0 :h 0}
       :content {:w 0 :h 0}})))

(defn destroy!
  "Remove scroll state for a container. Called on unmount."
  [id]
  (swap! scroll-states dissoc id)
  (swap! watchers dissoc id))

(defn set-dimensions!
  "Update viewport and content dimensions. Called after layout."
  [id viewport-bounds content-size]
  (swap! scroll-states update id
    (fn [state]
      (assoc state
        :viewport viewport-bounds
        :content content-size))))

;; ============================================================
;; Read APIs
;; ============================================================

(defn get-scroll
  "Get current scroll offset for container."
  [id]
  (get-in @scroll-states [id :scroll] {:x 0 :y 0}))

(defn get-dimensions
  "Get viewport, content, and scroll for container."
  [id]
  (get @scroll-states id))

(defn get-scrollable-size
  "Calculate max scrollable distance on each axis."
  [id]
  (when-let [{:keys [viewport content]} (get @scroll-states id)]
    {:x (max 0 (- (:w content) (:w viewport)))
     :y (max 0 (- (:h content) (:h viewport)))}))

(defn scrollable?
  "Check if container is scrollable on given axis."
  [id axis]
  (let [scrollable-size (get-scrollable-size id)]
    (> (get scrollable-size axis 0) 0)))

(defn get-scroll-progress
  "Get scroll progress on axis (0.0 to 1.0)."
  [id axis]
  (let [scroll (get-scroll id)
        max-scroll (get (get-scrollable-size id) axis 0)]
    (if (> max-scroll 0)
      (/ (get scroll axis 0) max-scroll)
      0.0)))

;; ============================================================
;; Write APIs
;; ============================================================

(defn- clamp-scroll
  "Clamp scroll offset to valid range."
  [scroll max-scroll]
  {:x (max 0 (min (:x scroll) (:x max-scroll)))
   :y (max 0 (min (:y scroll) (:y max-scroll)))})

(defn- notify-watchers!
  "Notify all watchers for a container of scroll change."
  [id old-pos new-pos]
  (when (not= old-pos new-pos)
    (doseq [[_watcher-id callback] (get @watchers id)]
      (try
        (callback old-pos new-pos)
        (catch Exception e
          (println "Scroll watcher error:" e))))))

(defn set-scroll!
  "Set scroll position (clamped to valid range)."
  [id pos]
  (when-let [state (get @scroll-states id)]
    (let [old-pos (:scroll state)
          max-scroll (or (get-scrollable-size id) {:x 0 :y 0})
          new-pos (clamp-scroll pos max-scroll)]
      (swap! scroll-states assoc-in [id :scroll] new-pos)
      (notify-watchers! id old-pos new-pos))))

(defn scroll-by!
  "Scroll by delta amount."
  [id delta]
  (let [current (get-scroll id)]
    (set-scroll! id {:x (+ (:x current) (:x delta 0))
                     :y (+ (:y current) (:y delta 0))})))

(defn scroll-to-top! [id]
  (set-scroll! id {:x 0 :y 0}))

(defn scroll-to-bottom! [id]
  (let [{:keys [x y]} (get-scrollable-size id)]
    (set-scroll! id {:x 0 :y y})))

(defn scroll-to-right! [id]
  (let [{:keys [x y]} (get-scrollable-size id)]
    (set-scroll! id {:x x :y 0})))

;; ============================================================
;; Watchers
;; ============================================================

(defn watch!
  "Register a callback for scroll changes.
  Callback signature: (fn [old-pos new-pos] ...)
  Returns: watcher-id for removal"
  [id callback]
  (let [watcher-id (random-uuid)]
    (swap! watchers update id (fnil assoc {}) watcher-id callback)
    watcher-id))

(defn unwatch!
  "Remove a specific watcher."
  [id watcher-id]
  (swap! watchers update id dissoc watcher-id))

(defn unwatch-all!
  "Remove all watchers for a container."
  [id]
  (swap! watchers dissoc id))
```

---

## Part 3: Add Mixin System

### File: `src/lib/layout/mixins.clj` (NEW)

```clojure
(ns lib.layout.mixins
  "Reusable lifecycle mixins for layout nodes."
  (:require [lib.layout.scroll :as scroll]))

;; ============================================================
;; Scrollable Mixin
;; ============================================================

(defn scrollable
  "Mixin for scrollable containers.

  Args:
    axes - :x, :y, or both
    opts - optional {:persist true} to keep scroll when hidden

  Examples:
    (mixins/scrollable :y)
    (mixins/scrollable :x :y)
    (mixins/scrollable :y {:persist true})"
  [& args]
  (let [[axes opts] (if (map? (last args))
                      [(butlast args) (last args)]
                      [args {}])
        persist? (:persist opts false)]
    {:did-mount
     (fn [node]
       (scroll/init! (:id node)))

     :will-unmount
     (fn [node]
       (when-not persist?
         (scroll/destroy! (:id node))))}))

;; ============================================================
;; Scroll Change Watcher Mixin
;; ============================================================

(defn on-scroll-changed
  "Mixin that calls a function whenever scroll position changes.

  Args:
    callback - (fn [id old-pos new-pos] ...) called on scroll change

  Example:
    (mixins/on-scroll-changed
      (fn [id old new]
        (println id \"scrolled from\" old \"to\" new)))"
  [callback]
  (let [watcher-id-atom (atom nil)]
    {:did-mount
     (fn [node]
       (let [id (:id node)
             watcher-id (scroll/watch! id
                          (fn [old-pos new-pos]
                            (callback id old-pos new-pos)))]
         (reset! watcher-id-atom watcher-id)))

     :will-unmount
     (fn [node]
       (when-let [watcher-id @watcher-id-atom]
         (scroll/unwatch! (:id node) watcher-id)))}))

;; ============================================================
;; Virtual Scroll Mixin
;; ============================================================

(defn virtual-scroll
  "Mixin for virtualizing long lists (only render visible items).

  Args:
    items - vector of all items
    item-height - fixed height per item (px)
    render-item - (fn [item index] ...) returns tree node
    opts - optional {:buffer 3} items to render beyond viewport

  Example:
    (mixins/virtual-scroll
      @all-items
      50
      (fn [item i] {:text (:name item)})
      {:buffer 5})"
  [items item-height render-item & [opts]]
  (let [buffer (:buffer opts 3)]
    {:compute-children
     (fn [node]
       (let [id (:id node)
             scroll-y (:y (scroll/get-scroll id) 0)
             viewport-h (get-in (scroll/get-dimensions id) [:viewport :h] 0)

             ;; Calculate visible range
             start-idx (max 0 (- (int (/ scroll-y item-height)) buffer))
             visible-count (int (/ viewport-h item-height))
             end-idx (min (count items) (+ start-idx visible-count (* 2 buffer)))

             visible-items (subvec items start-idx end-idx)]

         ;; Set virtual content height for scrollbar
         (scroll/set-dimensions! id
           {:w 0 :h viewport-h}  ;; viewport
           {:w 0 :h (* (count items) item-height)})  ;; total content

         ;; Render visible items with absolute positioning
         (for [[i item] (map-indexed vector visible-items)]
           (let [actual-idx (+ start-idx i)
                 y-offset (* actual-idx item-height)]
             (merge (render-item item actual-idx)
                    {:layout {:y {:absolute y-offset}
                              :x {:size "1s"}
                              :h {:size item-height}}})))))}))
```

---

## Part 4: Update Layout System

### File: `src/lib/layout/core.clj`

**Add normalize-overflow helper (near top with other helpers):**
```clojure
(defn- normalize-overflow
  "Convert overflow spec to normalized map form.

  Examples:
    nil          => {:x :visible :y :visible}
    :clip        => {:x :clip :y :clip}
    {:y :scroll} => {:x :visible :y :scroll}"
  [overflow]
  (cond
    (nil? overflow) {:x :visible :y :visible}
    (keyword? overflow) {:x overflow :y overflow}
    (map? overflow) (merge {:x :visible :y :visible} overflow)))
```

**Update children-layout-defaults (line ~113):**
```clojure
(def ^:private children-layout-defaults
  {:mode :stack-x
   :x {}
   :y {}
   :overflow {:x :visible :y :visible}})  ;; Changed to map
```

**Update layout function where overflow is extracted (line ~613):**
```clojure
;; Before:
overflow (get children-layout :overflow :visible)

;; After:
overflow (normalize-overflow (get children-layout :overflow))
```

**Update bounds map (line ~618):**
```clojure
bounds {:x (+ (:x parent-bounds) before-x)
        :y (+ (:y parent-bounds) before-y)
        :w final-w
        :h final-h
        :z z-index
        :overflow overflow}  ;; Now a map {:x :visible :y :scroll}
```

**Add mixin processing (new function):**
```clojure
(defn- process-mixins
  "Apply mixin lifecycle hooks for a node.

  Args:
    node - layout node with :mixins
    phase - :did-mount, :will-unmount, or :compute-children

  For :compute-children, returns computed children or nil."
  [node phase]
  (let [mixins (:mixins node)]
    (case phase
      :did-mount
      (doseq [mixin mixins]
        (when-let [hook (:did-mount mixin)]
          (hook node)))

      :will-unmount
      (doseq [mixin mixins]
        (when-let [hook (:will-unmount mixin)]
          (hook node)))

      :compute-children
      ;; Compute children from mixins (for virtual scroll)
      (some (fn [mixin]
              (when-let [compute-fn (:compute-children mixin)]
                (compute-fn node)))
            mixins))))

;; Call this in appropriate places during layout
```

**Update layout function to call mixins (after calculating bounds):**
```clojure
;; After bounds calculated, before returning:
(when (and (:id tree) (:mixins tree))
  ;; First time seeing this node? Call did-mount
  (process-mixins tree :did-mount)

  ;; Check if mixins want to compute children
  (when-let [computed-children (process-mixins tree :compute-children)]
    (def final-children computed-children)))
```

---

## Part 5: Update Rendering System

### File: `src/lib/layout/render.clj`

**Update walk-layout function (lines 26-44):**

```clojure
(defn walk-layout
  "Walk a laid-out tree, calling render-fn for each node.
   Supports overflow clipping and scrolling with canvas translation.

   render-fn receives (node bounds canvas) where bounds is {:x :y :w :h :z :overflow}.
   Children are sorted by :z index (lower z rendered first, higher z on top).

   Overflow handling:
   - :visible - children can render beyond parent bounds
   - :clip - children clipped to parent bounds
   - :scroll - children clipped + translated by scroll offset"
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

           ;; Get scroll offset if scrollable
           scroll-offset (when (or scroll-x? scroll-y?)
                          (when-let [id (:id tree)]
                            (scroll/get-scroll id)))

           needs-clip? (or clip-x? clip-y?)
           needs-scroll? (and scroll-offset (or scroll-x? scroll-y?))]

       ;; Render this node
       (render-fn tree bounds canvas)

       (let [sorted-children (sort-by #(get-in % [:bounds :z] 0) (:children tree))]
         (if needs-clip?
           ;; Save canvas state, apply clipping and scroll translation
           (let [save-count (.save canvas)
                 {:keys [x y w h]} bounds]

             ;; Clip to parent bounds
             (.clipRect canvas (Rect/makeXYWH x y w h))

             ;; Translate by scroll offset (negative = content moves opposite)
             (when needs-scroll?
               (let [dx (if scroll-x? (- (:x scroll-offset 0)) 0)
                     dy (if scroll-y? (- (:y scroll-offset 0)) 0)]
                 (.translate canvas (float dx) (float dy))))

             ;; Render children
             (doseq [child sorted-children]
               (walk-layout child canvas render-fn))

             ;; Render scrollbars after children
             (when needs-scroll?
               (render-scrollbars canvas tree bounds scroll-offset))

             ;; Restore canvas state
             (.restoreToCount canvas save-count))

           ;; No clipping - children can overflow
           (doseq [child sorted-children]
             (walk-layout child canvas render-fn))))))))

;; Add helper for normalize-overflow (or import from core)
(defn- normalize-overflow [overflow]
  (cond
    (nil? overflow) {:x :visible :y :visible}
    (keyword? overflow) {:x overflow :y overflow}
    (map? overflow) (merge {:x :visible :y :visible} overflow)))
```

**Add scrollbar rendering (new function):**

```clojure
(defn- render-scrollbars
  "Render scrollbars for scrollable container.

  Args:
    canvas - Skija Canvas
    node - layout node
    bounds - container bounds {:x :y :w :h}
    scroll-offset - current scroll {:x :y}"
  [^Canvas canvas node bounds scroll-offset]
  (when-let [dims (scroll/get-dimensions (:id node))]
    (let [{:keys [viewport content]} dims
          {:keys [x y w h]} bounds

          scrollbar-width 8
          scrollbar-color 0xFF888888
          thumb-color 0xFFCCCCCC]

      ;; Vertical scrollbar
      (when (> (:h content) (:h viewport))
        (let [track-height h
              thumb-height (* track-height (/ (:h viewport) (:h content)))
              max-scroll (- (:h content) (:h viewport))
              thumb-y (if (> max-scroll 0)
                       (* (- track-height thumb-height) (/ (:y scroll-offset) max-scroll))
                       0)

              track-rect (Rect/makeXYWH (- (+ x w) scrollbar-width) y scrollbar-width track-height)
              thumb-rect (Rect/makeXYWH (- (+ x w) scrollbar-width) (+ y thumb-y) scrollbar-width thumb-height)

              track-paint (doto (Paint.) (.setColor (unchecked-int scrollbar-color)))
              thumb-paint (doto (Paint.) (.setColor (unchecked-int thumb-color)))]

          (.drawRect canvas track-rect track-paint)
          (.drawRect canvas thumb-rect thumb-paint)))

      ;; Horizontal scrollbar
      (when (> (:w content) (:w viewport))
        (let [track-width w
              thumb-width (* track-width (/ (:w viewport) (:w content)))
              max-scroll (- (:w content) (:w viewport))
              thumb-x (if (> max-scroll 0)
                       (* (- track-width thumb-width) (/ (:x scroll-offset) max-scroll))
                       0)

              track-rect (Rect/makeXYWH x (- (+ y h) scrollbar-width) track-width scrollbar-width)
              thumb-rect (Rect/makeXYWH (+ x thumb-x) (- (+ y h) scrollbar-width) thumb-width scrollbar-width)

              track-paint (doto (Paint.) (.setColor (unchecked-int scrollbar-color)))
              thumb-paint (doto (Paint.) (.setColor (unchecked-int thumb-color)))]

          (.drawRect canvas track-rect track-paint)
          (.drawRect canvas thumb-rect thumb-paint))))))
```

---

## Part 6: Add Scroll-Aware Hit Testing

### File: `src/lib/gesture/hit_test.clj`

**Update hit-test functions to account for scroll:**

```clojure
(defn hit-test-with-scroll
  "Hit test accounting for scroll offsets in parent containers.

  Args:
    screen-x, screen-y - mouse position in screen space
    tree - layout tree with :bounds
    scroll-offset-acc - accumulated scroll offset from parents

  Returns: sequence of nodes under pointer, sorted by z-index"
  [screen-x screen-y tree scroll-offset-acc]
  (when tree
    (let [;; Get this node's scroll offset if scrollable
          node-scroll (if (and (:id tree)
                              (get-in tree [:bounds :overflow]))
                       (scroll/get-scroll (:id tree))
                       {:x 0 :y 0})

          ;; Accumulate scroll offsets from parent chain
          total-offset {:x (+ (:x scroll-offset-acc) (:x node-scroll))
                        :y (+ (:y scroll-offset-acc) (:y node-scroll))}

          ;; Transform mouse coords from screen space to layout space
          layout-x (+ screen-x (:x total-offset))
          layout-y (+ screen-y (:y total-offset))

          ;; Test against this node's bounds (in layout space)
          bounds (:bounds tree)
          hit? (and bounds
                   (>= layout-x (:x bounds))
                   (< layout-x (+ (:x bounds) (:w bounds)))
                   (>= layout-y (:y bounds))
                   (< layout-y (+ (:y bounds) (:h bounds))))]

      (if hit?
        ;; Node hit - check children with accumulated offset
        (concat [tree]
                (mapcat #(hit-test-with-scroll screen-x screen-y % total-offset)
                        (:children tree)))
        ;; Node not hit
        []))))

;; Update existing hit-test to use scroll-aware version
(defn hit-test
  "Find all targets under pointer, accounting for scroll offsets.

  Args:
    px, py - pointer position in screen coordinates
    ctx - context map (may contain :tree for layout-based hit testing)
    targets - registered gesture targets
    blocked-layers - layers to ignore

  Returns: sequence of hit targets sorted by layer and z-index"
  [px py ctx targets blocked-layers]
  ;; Existing target-based hit testing...
  ;; Plus new tree-based hit testing if :tree in ctx:
  (when-let [tree (:tree ctx)]
    (hit-test-with-scroll px py tree {:x 0 :y 0})))
```

---

## Part 7: Add Input Handling

### File: `src/app/core.clj`

**Add EventMouseWheel handler in create-event-listener:**

```clojure
;; In the cond statement that handles events, add:

EventMouseWheel
(when-let [handle-wheel (requiring-resolve 'lib.gesture.api/handle-mouse-wheel)]
  (let [dx (.getDeltaX event)
        dy (.getDeltaY event)
        shift? (.isShiftPressed event)
        x (.getX event)
        y (.getY event)]
    (handle-wheel {:x x :y y :dx dx :dy dy :shift shift?}
                  {:scale @state/scale
                   :tree @state/current-tree})))
```

### File: `src/lib/gesture/api.clj`

**Add mouse wheel handler:**

```clojure
(defn handle-mouse-wheel
  "Handle mouse wheel scroll events.

  Finds scrollable container under cursor and updates scroll offset.
  Shift+wheel scrolls horizontally."
  [event ctx]
  (let [{:keys [x y dx dy shift]} event
        {:keys [tree]} ctx

        ;; Find scrollable container under cursor
        hits (hit-test/hit-test-with-scroll x y tree {:x 0 :y 0})
        scrollable-hit (first (filter #(get-in % [:bounds :overflow]) hits))]

    (when scrollable-hit
      (when-let [id (:id scrollable-hit)]
        (let [overflow (get-in scrollable-hit [:bounds :overflow])
              scroll-x? (= :scroll (:x overflow))
              scroll-y? (= :scroll (:y overflow))

              ;; Determine scroll delta (shift swaps axes)
              delta (if shift
                     {:x dy :y 0}  ;; horizontal scroll with shift
                     {:x (if scroll-x? dx 0)
                      :y (if scroll-y? dy 0)})]

          (scroll/scroll-by! id delta)
          (request-frame!))))))
```

---

## Part 8: Documentation Updates

### File: `notes-layout-new-new-stuff-added.md`

Update overflow documentation:

```markdown
## Overflow Handling

Control how content behaves when children exceed parent bounds.

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

### Scrolling

Containers with `:overflow :scroll` require:
1. Explicit `:id` for state tracking
2. `scrollable` mixin for lifecycle management

```clojure
{:id :my-list
 :overflow {:y :scroll}
 :mixins [(mixins/scrollable :y)]
 :children [...]}
```

### Scroll APIs

See `lib.layout.scroll` namespace for programmatic control:
- `get-scroll`, `set-scroll!`, `scroll-to-top!`, etc.
- `get-dimensions`, `get-scrollable-size`, `get-scroll-progress`
- Watcher API for scroll change callbacks

### Virtual Scrolling

For long lists (1000s of items), use virtual scroll mixin:

```clojure
{:id :contacts
 :overflow {:y :scroll}
 :mixins [(mixins/scrollable :y)
          (mixins/virtual-scroll @items 80 render-fn)]}
```
```

---

## Implementation Order

1. **Part 1** - Remove `:hidden` alias (simple search/replace)
2. **Part 2** - Create `lib.layout.scroll` module (core state management)
3. **Part 3** - Create `lib.layout.mixins` module (scroll mixin)
4. **Part 4** - Update `lib.layout.core` (normalize overflow, process mixins)
5. **Part 5** - Update `lib.layout.render` (scroll translation, scrollbars)
6. **Part 6** - Update hit testing (scroll-aware coordinate transformation)
7. **Part 7** - Add input handling (mouse wheel, drag scroll)
8. **Part 8** - Update documentation

---

## Testing Checklist

- [ ] Basic scroll with mouse wheel
- [ ] Horizontal scroll with shift+wheel
- [ ] Per-axis overflow control
- [ ] Scroll persistence across hide/show
- [ ] Hit testing inside scrolled containers
- [ ] Scrollbar rendering and sizing
- [ ] Programmatic scroll APIs
- [ ] Virtual scroll with 10,000+ items
- [ ] Nested scrollable containers
- [ ] Hot-reload preserves scroll state

---

## Files Summary

| File | Status | Changes |
|------|--------|---------|
| `src/lib/layout/scroll.clj` | NEW | Core scroll state and APIs |
| `src/lib/layout/mixins.clj` | NEW | Mixin definitions |
| `src/lib/layout/core.clj` | MODIFY | Normalize overflow, mixin processing |
| `src/lib/layout/render.clj` | MODIFY | Scroll translation, scrollbars |
| `src/lib/gesture/hit_test.clj` | MODIFY | Scroll-aware hit testing |
| `src/lib/gesture/api.clj` | MODIFY | Mouse wheel handler |
| `src/app/core.clj` | MODIFY | EventMouseWheel case |
| `notes-layout-new-new-stuff-added.md` | MODIFY | Documentation updates |

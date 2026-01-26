# Gesture Handling System - Bug Fixes

This document describes the bugs found in the initial gesture arena implementation and how they were fixed.

## Overview

The gesture system was inspired by Flutter's Gesture Arena pattern, adapted for Clojure/JWM/Skija. After initial implementation, the sliders and draggable demo circle stopped responding to mouse events. This document traces through the bugs and their solutions.

---

## Bug 1: Hit-test crashed on nil bounds

### Location
`src/lib/gesture/hit_test.clj`

### Problem
When the control panel is hidden, the slider bounds functions return `nil`. The hit-test code passed this nil to `point-in-rect?`, which crashed when trying to destructure `[x y w h]` from nil.

```clojure
;; Slider bounds returns nil when panel hidden
:bounds-fn (fn [ctx]
             (when @state/panel-visible?
               (slider-x-bounds-fn ctx)))  ;; Returns nil if panel hidden

;; Hit-test crashed here
(filter (fn [target]
          (when-let [bounds-fn (:bounds-fn target)]
            (let [bounds (bounds-fn ctx)]        ;; bounds = nil
              (point-in-rect? px py bounds)))))  ;; CRASH!
```

### Fix
Added a second `when-let` to skip targets with nil bounds:

```clojure
(filter (fn [target]
          (when-let [bounds-fn (:bounds-fn target)]
            (when-let [bounds (bounds-fn ctx)]     ;; Skip if nil
              (point-in-rect? px py bounds)))))
```

---

## Bug 2: Drag required 10px movement before starting

### Location
`src/lib/gesture/state.clj`

### Problem
The drag recognizer was configured with `min-distance: 10`, meaning the user had to move the pointer 10 pixels before the drag gesture was recognized. But the original slider and demo circle code responded **immediately** on mouse down.

```clojure
;; Original behavior (app/controls.clj):
;; - Mouse down on slider → immediately update value
;; - Mouse down on circle → immediately store offset, set dragging flag

;; Gesture system behavior:
;; - Mouse down → create recognizer in :possible state
;; - Mouse move 10px → transition to :began, call :on-drag-start
;; - Result: 10px of "dead zone" before drag starts
```

### Fix
Changed default `min-distance` to 0 for immediate drag:

```clojure
(def recognizer-configs
  {:drag {:min-distance 0}  ;; Was 10, now 0 for immediate response
   ...})
```

---

## Bug 3: Drag recognizer didn't declare victory immediately

### Location
`src/lib/gesture/recognizers.clj`

### Problem
Even with `min-distance: 0`, the recognizer started in `:possible` state and only transitioned to `:began` on the first move event. This meant:

1. Pointer down → recognizer in `:possible` state, `wants-to-win? = false`
2. Arena resolves (single recognizer wins by default)
3. `deliver-gesture!` called with state `:possible` → no handler matches
4. `:on-drag-start` never called until first move

### Fix
For immediate drag (min-distance=0), create the recognizer already in `:began` state with `wants-to-win? = true`:

```clojure
(defn create-recognizer [type target pos time]
  (let [config (get state/recognizer-configs type {})
        immediate-drag? (and (= type :drag)
                             (zero? (get config :min-distance 10)))]
    {:type        type
     :state       (if immediate-drag? :began :possible)  ;; Start in :began
     :wants-to-win? immediate-drag?                       ;; Declare victory
     ...}))
```

---

## Bug 4: Arena state became `:resolved`, blocking all moves

### Location
`src/lib/gesture/api.clj`

### Problem
After a winner was determined, `try-resolve-and-deliver!` changed the arena state to `:resolved`:

```clojure
(swap! state/arena assoc
       :winner new-winner
       :state :resolved  ;; <-- Problem!
       ...)
```

But `handle-pointer-move` only processed events when state was `:tracking`:

```clojure
(defn handle-pointer-move [px py ctx]
  (when (= state :tracking)  ;; State is :resolved, so this is false!
    ...))                     ;; All moves ignored!
```

### Fix
Keep state as `:tracking` until pointer up. The winner field indicates resolution, not the state:

```clojure
(swap! state/arena assoc
       :winner new-winner
       ;; Removed: :state :resolved
       ...)
```

---

## Bug 5: Double delivery and missing `:began` event

### Location
`src/lib/gesture/api.clj`

### Problem
Two issues in `handle-pointer-move`:

**5a. Double delivery**: After `try-resolve-and-deliver!` already delivered the gesture, the code delivered again:

```clojure
(try-resolve-and-deliver!)  ;; Delivers gesture
(when-let [w (:winner @state/arena)]
  (deliver-gesture! w (:state w)))  ;; Delivers AGAIN!
```

**5b. Missing `:began` delivery**: When the winner already existed and the recognizer transitioned from `:began` to `:changed`, the code only delivered `:changed`:

```clojure
(when (= (:state updated) :changed)
  (deliver-gesture! updated :changed))
;; What about the first :began → :changed transition?
;; :on-drag-start was never called!
```

### Fix
- Removed duplicate delivery after `try-resolve-and-deliver!`
- The initial `:began` event is now delivered by `try-resolve-and-deliver!` when the winner is first determined
- Subsequent moves deliver `:changed` events as `:on-drag`

```clojure
(defn handle-pointer-move [px py ctx]
  (if winner
    ;; Winner exists - just deliver :changed
    (let [updated (update-recognizer-move winner pos time)]
      (swap! state/arena assoc :winner updated)
      (when (= (:state updated) :changed)
        (deliver-gesture! updated :changed)))
    ;; No winner - try to resolve (this delivers :began if winner found)
    (do
      (swap! state/arena assoc :recognizers updated)
      (try-resolve-and-deliver!))))  ;; No duplicate delivery after
```

---

## Event Flow After Fixes

### Pointer Down
```
1. Hit test → find topmost target
2. Create drag recognizer with:
   - state: :began (immediate)
   - wants-to-win?: true
3. Arena resolves → this recognizer wins
4. deliver-gesture! with :began → calls :on-drag-start
```

### Pointer Move
```
1. Check arena state = :tracking ✓
2. Winner exists → update recognizer
3. State transitions :began → :changed
4. deliver-gesture! with :changed → calls :on-drag
```

### Pointer Up
```
1. Update winner with pointer-up
2. State transitions to :ended
3. deliver-gesture! with :ended → calls :on-drag-end
4. Reset arena to :idle
```

---

## Testing

Run the app and verify:

```clojure
(open)  ;; Open window
```

1. **Sliders**: Press Ctrl+` to show panel, click and drag X/Y sliders
2. **Demo circle**: Click and drag the circle along X axis
3. **Momentum**: Release while moving to see decay animation

All interactions should respond immediately on mouse down, not requiring any movement threshold.

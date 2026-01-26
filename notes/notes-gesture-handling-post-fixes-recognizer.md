# Gesture Recognizer - Post-Fix Alignment with iOS/Flutter

This document describes the refinements made to align our gesture recognizer behavior with iOS UIKit and Flutter's gesture arena pattern.

---

## Context

After the initial gesture handling fixes (see `notes-gesture-handling-fixes.md`), we identified that our "immediate drag" hack deviated from iOS/Flutter behavior. This document covers the alignment changes.

---

## Config Values: Aligned with iOS UIKit

### File: `src/lib/gesture/state.clj`

```clojure
(def recognizer-configs
  {:drag       {:min-distance 0}           ;; iOS: UIPanGestureRecognizer starts immediately
   :long-press {:min-duration 500          ;; iOS: minimumPressDuration = 0.5s
                :max-distance 10}          ;; iOS: allowableMovement = 10pt
   :tap        {:max-distance 10}})        ;; iOS: ~10pt tolerance, no duration limit
```

### iOS Reference Values

| Property | iOS Value | iOS Source |
|----------|-----------|------------|
| Long press duration | 500ms | `UILongPressGestureRecognizer.minimumPressDuration` |
| Long press movement | 10pt | `UILongPressGestureRecognizer.allowableMovement` |
| Pan/Drag threshold | 0 | `UIPanGestureRecognizer` has no min distance |
| Tap max duration | None | iOS doesn't enforce tap duration |

### Flutter Reference Values

| Property | Flutter Value | Flutter Source |
|----------|---------------|----------------|
| Touch slop | 18px | `kTouchSlop` (for touch input) |
| Precise pointer slop | 1-2px | `kPrecisePointerHitSlop` (for mouse) |
| Long press timeout | 500ms | `kLongPressTimeout` |
| Tap timeout | Not enforced | `kHoverTapTimeout` exists but not honored |

**Note:** We use iOS's 10pt for distance thresholds as a reasonable middle ground for desktop mouse input.

---

## Recognizer Creation: Removed Immediate-Drag Hack

### File: `src/lib/gesture/recognizers.clj`

### Problem with Previous Implementation

The original fix for sliders (Bug 3 in `notes-gesture-handling-fixes.md`) introduced a hack where drag recognizers with `min-distance=0` would:
1. Start in `:began` state on pointer DOWN
2. Pre-declare `wants-to-win? = true` immediately

```clojure
;; OLD (deviated from iOS/Flutter)
(let [immediate-drag? (and (= type :drag)
                           (zero? (get config :min-distance 10)))]
  {:state       (if immediate-drag? :began :possible)  ;; Pre-began on down
   :wants-to-win? immediate-drag?})                     ;; Pre-declared victory
```

**Issue:** This prevented tap from ever winning if both tap and drag were on the same target, because drag would win immediately on pointer down before the user even moved.

### How iOS/Flutter Actually Work

Even with no minimum distance threshold:
- **Pointer down** → Recognizer enters arena in "possible" state
- **First move** → Pan/drag transitions to "began" and declares victory
- **Release without moving** → Tap wins

The key insight: **Victory is declared based on user ACTION (move or release), not on pointer down.**

### New Implementation (iOS/Flutter Aligned)

```clojure
;; NEW (matches iOS/Flutter)
(defn create-recognizer
  "Create a new recognizer instance for a target.
   All recognizers start in :possible state (iOS/Flutter behavior).
   Victory is declared based on user action, not on pointer down."
  [type target pos time]
  (let [config (get state/recognizer-configs type {})]
    {:type        type
     :state       :possible        ;; All start possible
     :wants-to-win? false          ;; Victory declared on action
     ...}))
```

---

## Gesture Arena Flow (After Fix)

### Tap vs Drag Disambiguation

```
Pointer Down
    ├── Drag: state = :possible, wants-to-win? = false
    └── Tap:  state = :possible, wants-to-win? = false

    Arena: "No winner yet, keep tracking both"

SCENARIO A: User moves (even 1px with min-distance=0)
    ├── Drag: exceeds threshold → state = :began, wants-to-win? = true → WINS
    └── Tap:  still :possible → cancelled by arena

SCENARIO B: User releases without moving
    ├── Drag: no movement → state = :failed, can-win? = false
    └── Tap:  within thresholds → state = :ended, wants-to-win? = true → WINS
```

### Long Press Disambiguation

```
Pointer Down
    ├── Long-press: state = :possible
    └── Tap:        state = :possible

SCENARIO A: User holds 500ms without moving
    ├── Long-press: duration met → state = :began, wants-to-win? = true → WINS
    └── Tap:        cancelled by arena

SCENARIO B: User releases before 500ms
    ├── Long-press: duration not met → state = :failed
    └── Tap:        within thresholds → WINS

SCENARIO C: User moves more than 10pt while holding
    ├── Long-press: movement exceeded → state = :failed
    └── Drag:       (if present) movement threshold met → WINS
```

---

## Trade-offs

### Before (Immediate Drag Hack)
- Sliders responded on pointer DOWN
- Tap could never win against drag on same target

### After (iOS/Flutter Aligned)
- Sliders respond on first MOVE (typically imperceptible - 1px)
- Tap and drag properly disambiguate
- Matches iOS `UIPanGestureRecognizer` behavior exactly

---

## Summary of Changes

| File | Change |
|------|--------|
| `src/lib/gesture/state.clj` | Removed `:max-duration` from tap config (iOS doesn't enforce) |
| `src/lib/gesture/recognizers.clj` | Removed `immediate-drag?` hack, all recognizers start in `:possible` |

---

## References

- [UILongPressGestureRecognizer - Apple](https://developer.apple.com/documentation/uikit/uilongpressgesturerecognizer)
- [UIPanGestureRecognizer - Apple](https://developer.apple.com/documentation/uikit/uipangesturerecognizer)
- [Flutter kTouchSlop constant](https://api.flutter.dev/flutter/gestures/kTouchSlop-constant.html)
- [Flutter constants.dart](https://github.com/flutter/flutter/blob/master/packages/flutter/lib/src/gestures/constants.dart)

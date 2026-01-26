> there's certainly some issue with gesture handler. sometimes i have to do double click then hold the second click and drag to make the drag happen. other times when i run the app again it works fine --
> hold click and drag does the dragging. look into the code, ultrathink, then refer to flutter code+docs and then ultrathink to find the answer

## initial explanation

I've identified two bugs in the gesture handling system after comparing with Flutter's gesture arena behavior:

Bug 1: Arena resolves too eagerly (PRIMARY)

In arena.clj:38-39, the rule (= 1 (count active)) → wins causes a lone recognizer to "win" immediately on pointer-down, before it has actually declared victory.

In Flutter/iOS, a recognizer only wins when it explicitly declares (via movement threshold) or when sweep() forces resolution on pointer-up.

Bug 2: Missing :began delivery

In api.clj:155, when an existing winner moves, only :changed is delivered. The :began state (which triggers on-drag-start) is skipped because:

1. Winner was set in :possible state (from Bug 1)
2. First move transitions to :began, but code only checks for :changed
3. Result: on-drag-start is never called → demo-drag-offset-x never set → circle jumps/behaves erratically

Why it's intermittent

The double-click "works" because the first click's stale state sometimes accidentally aligns with subsequent interactions. Starting fresh sometimes has correct initial values that mask the issue.

The Fix

1. Remove the "single active = wins" rule from resolve-arena
2. Add proper :began delivery in handle-pointer-move

This aligns the behavior with Flutter's arena model where recognizers must explicitly declare victory through user action (movement exceeding threshold), not just by being alone in the arena.

Sources:

- https://api.flutter.dev/flutter/gestures/GestureArenaManager-class.html
- https://api.flutter.dev/flutter/gestures/GestureArenaManager/sweep.html
- https://medium.com/flutter-community/flutter-deep-dive-gestures-c16203b3434f

## Length explanation

Gesture Handler Bug Fix Plan

Problem

Intermittent issue: sometimes drag requires double-click to initiate. First click "absorbs" but doesn't work, second click + hold + drag works.

Root Cause Analysis

After deep analysis comparing with Flutter's gesture arena system, I found two bugs:

Bug 1: Arena resolves too eagerly for single recognizers (PRIMARY)

Location: lib/gesture/arena.clj:38-39

```
 ;; Only one left standing wins
 (= 1 (count active))
 (first active)
```

This rule causes a lone recognizer to "win" immediately on pointer-down, before it has declared victory (via wants-to-win?).

Flutter/iOS behavior: A recognizer wins only when:

1.  It explicitly declares victory (wants-to-win? = true)
2.  OR it's the last one after others have rejected/failed
3.  OR sweep() forces resolution on pointer-up

The current code violates this by granting victory to the only active recognizer regardless of whether it has declared.

Bug 2: Missing :began delivery for existing winners

Location: lib/gesture/api.clj:155-156

```
 (when (= (:state updated) :changed)
   (deliver-gesture! updated :changed))
```

When a winner already exists and moves happen, only :changed is delivered. The :began state transition is never delivered because:

1.  Winner was determined while in :possible state (Bug 1)
2.  First move transitions state to :began
3.  But we only deliver on :changed, skipping :began
4.  Result: on-drag-start is never called

Why Double-Click Seems to Fix It

Without on-drag-start:

- demo-drag-offset-x is never set (stale value 0.0 from initial state)
- demo-dragging? remains false
- The drag handler calculates position with wrong offset
- Circle may jump weirdly or not respond as expected
- Repeated interactions may accidentally bring state into alignment

Fix Implementation

Change 1: Fix arena resolution rule

File: src/lib/gesture/arena.clj

Remove the "single active = wins" rule. A recognizer should only win when:

- It has declared (wants-to-win? = true), OR
- It's the last one standing after others have explicitly failed (not just "only one exists")

```
(defn resolve-arena
  [recognizers]
  (let [active (active-recognizers recognizers)
        declared (declared-winners active)]
    (cond
      ;; Single declaration wins
      (= 1 (count declared))
      (first declared)

      ;; Multiple declarations: highest priority wins
      (> (count declared) 1)
      (apply max-key :priority declared)

      ;; REMOVED: (= 1 (count active)) rule
      ;; This was causing premature wins before declaration

      ;; Still undecided
      :else nil)))
```

Change 2: Deliver :began state properly

File: src/lib/gesture/api.clj

In handle-pointer-move, when winner exists, deliver :began on first transition:

```
(if winner
  (let [prev-state (:state winner)
        updated (recognizers/update-recognizer-move winner [px py] time)]
    (swap! state/arena assoc :winner updated)
    ;; Deliver on-drag-start when transitioning to :began
    (when (and (= prev-state :possible) (= (:state updated) :began))
      (deliver-gesture! updated :began))
    ;; Deliver on-drag for continued movement
    (when (= (:state updated) :changed)
      (deliver-gesture! updated :changed)))
  ...)
```

Files to Modify

1.  src/lib/gesture/arena.clj - Remove premature single-recognizer win rule
2.  src/lib/gesture/api.clj - Add :began state delivery for existing winners

Testing

After changes:

1.  Click and hold on demo circle - drag should start immediately
2.  Verify on-drag-start is called (circle shouldn't jump)
3.  Release and repeat - should work consistently
4.  Test sliders in control panel
5.  Hot-reload and verify gestures still work

References

- https://api.flutter.dev/flutter/gestures/GestureArenaManager-class.html
- https://api.flutter.dev/flutter/gestures/GestureArenaManager/sweep.html
- https://medium.com/flutter-community/flutter-deep-dive-gestures-c16203b3434f

## Summary

Change 1: src/lib/gesture/arena.clj:15-42

Removed the "single active = wins" rule. Now a recognizer only wins when it explicitly declares victory (wants-to-win? = true). Forced resolution happens via sweep-arena on pointer-up, which is the correct Flutter/iOS behavior.

Change 2: src/lib/gesture/api.clj:149-159

Added delivery of :began state when a winner transitions from :possible to :began. This ensures on-drag-start is called properly, setting up demo-drag-offset-x and demo-dragging? before any drag events.

To test:

1. Run the app with clj -A:dev:macos-arm64 and (open)
2. Click and hold on the demo circle - drag should start immediately without jumping
3. Release and repeat multiple times - should work consistently every time
4. Test the sliders in the control panel (Ctrl+`) as well
5. Hot-reload with (reload) and verify gestures still work

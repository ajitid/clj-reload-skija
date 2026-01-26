# Fix: Circle now carries momentum on release

## The Bug
When dragging a circle quickly and releasing, it should overshoot the target (carry momentum) and spring back. Instead, it moved directly to the target without any overshoot.

## Root Cause
Velocity was calculated **at the moment of mouse release** from position differences:

```clojure
;; In handle-mouse-release:
(let [dt (- now @state/demo-last-mouse-time)
      vx (if (< dt 0.1)
           (/ (- @state/demo-circle-x @state/demo-last-mouse-x) dt)
           0.0)]
  ...)
```

The problem: there's often a delay between the last `mousemove` event and `mouseup`. During this delay:
- Position doesn't change (no new `mousemove`)
- Time keeps passing (`dt` grows)
- Velocity = distance/time → approaches 0

If `dt > 0.1 seconds`, velocity was forced to 0.

## The Fix
Calculate velocity **during drag** (on each `mousemove`) and store it:

```clojure
;; In handle-mouse-move:
(when (pos? dt)
  (reset! state/demo-velocity-x (/ (- mx @state/demo-circle-x) dt))
  (reset! state/demo-velocity-y (/ (- my @state/demo-circle-y) dt)))

;; In handle-mouse-release:
(let [vx @state/demo-velocity-x  ;; just read the stored value
      vy @state/demo-velocity-y]
  ...)
```

Now the velocity is always fresh from the most recent mouse movement, so the spring correctly continues with the drag momentum.

## Files Changed
- `src/app/state.clj` - added `demo-velocity-x` and `demo-velocity-y` atoms
- `src/app/controls.clj` - calculate velocity during drag, use stored velocity on release

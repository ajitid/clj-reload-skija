# Conversation: what's the closest to Circle().contour.rectified() in skija?

**Date:** 2026-01-25T10:55:38.519Z
**Project:** /Users/as186073/Downloads/vvvv-clj/clj-reload-skija
**Messages:** 6

## Table of Contents

1. [what's the closest to Circle().contour.rectified() in skija?](#user-1)
2. [https://api.openrndr.org/](#user-2)

---

## <a id="user-1"></a>User #1

what's the closest to Circle().contour.rectified() in skija?

---

**Assistant:**

I'll help you find the Skija equivalent for getting a rectified contour from a circle. Let me first look at how Skija is being used in this codebase.

---

**Assistant:**

Based on the codebase, in **Skija** the closest equivalent to `Circle().contour.rectified()` is using **`PathMeasure`** to sample the path at uniform arc-length intervals:

```clojure
(require '[lib.graphics.path :as path])

;; Create a circle path
(def circle-path (path/circle cx cy radius))

;; Create a PathMeasure to work with arc-length parameterization
(def pm (path/measure circle-path))
(def total-length (path/length pm))

;; Sample at uniform intervals (rectified/arc-length parameterized)
(defn sample-rectified [pm num-samples]
  (for [i (range num-samples)]
    (let [t (/ i num-samples)
          distance (* t total-length)]
      (path/position-at pm distance))))

;; Get uniformly spaced points along the circle
(def points (sample-rectified pm 100))
```

**Key differences:**
- Skija doesn't have a direct `.rectified()` method
- Instead, use **`PathMeasure`** (lib.graphics.path.clj:229-260) which provides:
  - `length` - total arc length
  - `position-at` - get [x y] at specific distance
  - `tangent-at` - get tangent vector at distance
  - `segment` - extract path segment between distances

This gives you the same uniform arc-length sampling that a rectified contour provides in other APIs.

---

## <a id="user-2"></a>User #2

https://api.openrndr.org/

---

**Assistant:**

Let me fetch the OPENRNDR API documentation to better understand their `rectified()` method.

---

**Assistant:**

Perfect! Now I understand what you're looking for. **OPENRNDR's `contour.rectified()`** creates an arc-length parameterized version of a contour, where `t` values correspond to actual distance traveled along the path rather than parametric distance through bezier segments.

## What OPENRNDR's `rectified()` Does

In OPENRNDR, regular contours have uneven parameterization - `t=0.5` doesn't necessarily give you the midpoint by arc length. The `rectified()` method (from **orx-shapes** extension) solves this by creating a version where:
- `t` values are evenly spaced along the actual path length
- Animations move at constant speed
- Sampling gives uniform point distribution

## Skija Equivalent

In **Skija**, you achieve the same thing using **`PathMeasure`**, which is already in this codebase at lib.graphics.path.clj:229-260:

```clojure
(require '[lib.graphics.path :as path])

;; OPENRNDR approach:
;; val circle = Circle(cx, cy, radius).contour
;; val rectified = circle.rectified()
;; rectified.position(0.5)  // midpoint by arc length

;; Skija equivalent:
(def circle-path (path/circle cx cy radius))
(def pm (path/measure circle-path))
(def total-length (path/length pm))

;; Get position at t=0.5 (midpoint by arc length)
(path/position-at pm (* 0.5 total-length))

;; Get tangent/normal at any arc-length position
(path/tangent-at pm (* t total-length))

;; Sample uniformly along the path
(defn uniform-samples [pm num-points]
  (let [len (path/length pm)]
    (for [i (range num-points)]
      (path/position-at pm (* (/ i num-points) len)))))
```

The key difference is that OPENRNDR's `rectified()` returns a new contour that remembers its arc-length parameterization, while Skija's `PathMeasure` is an explicit measurement object you query with actual distance values.

Sources:
- [Modifying contours | OPENRNDR for pen plotters](https://x.funprogramming.org/modifyingContours/)
- [ShapeContour API](https://api.openrndr.org/openrndr-shape/org.openrndr.shape/-shape-contour/index.html)
- [GitHub Issue #135 - equidistantPositions](https://github.com/openrndr/openrndr/issues/135)

---


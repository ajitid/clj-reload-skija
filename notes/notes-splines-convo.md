# Conversation: what is the equivalent of hobby curve in openrndr https://deepwiki.com/search/hobby-curve_55b8dd03-4

**Date:** 2026-01-26T03:05:27.612Z
**Project:** /Users/as186073/Downloads/vvvv-clj/clj-reload-skija
**Messages:** 38

## Table of Contents

1. [what is the equivalent of hobby curve in openrndr https://deepwiki.com/search/hobby-curve_55b8dd03-4...](#user-1)
2. [Okay, create a plan to add a hobby curve and then also add an example in howto to display all the na...](#user-2)
3. [Also in our code base we have added `wave` Check if its implementation is right or if it can be impr...](#user-3)
4. [one more thing, there is https://skia-canvas.org/api/context#linedashmarker Can you check if we have...](#user-4)
5. [one more thing, there is https://skia-canvas.org/api/context#linedashmarker Can you check if we have...](#user-5)
6. [oh if this is the case  For large filled areas, https://bclehmann.github.io/2022/11/05/HatchingWithS...](#user-6)
7. [also pls add examples for all in howto We already have example for curve that we already discussed. ...](#user-7)

---

## <a id="user-1"></a>User #1

what is the equivalent of hobby curve in openrndr https://deepwiki.com/search/hobby-curve_55b8dd03-4a60-4cdb-8e2c-af926c48fd60?mode=fast in skia/skija? search web to find

---

**Assistant:**

Based on my research, here's the situation:

## Hobby Curves in OpenRNDR

OpenRNDR provides `hobbyCurve()` in the `orx-shapes` module that creates smooth `ShapeContour` objects through a list of points:

```kotlin
val smoothContour = hobbyCurve(points, closed = false)
```

## Skia/Skija: No Direct Equivalent

**Skia does not have a built-in Hobby curve implementation.** You have several alternatives:

### 1. Natural Cubic Spline (closest built-in)

Skia has a [SmoothBezierSplineInterpolation](https://fiddle.skia.org/c/@SmoothBezierSplineInterpolation) fiddle showing how to compute smooth cubic splines through points using a tridiagonal matrix solver. This gives C2 continuity (smoother mathematically, but potentially less aesthetically pleasing than Hobby).

### 2. Catmull-Rom Splines

A popular alternative that's easy to convert to Skia's `cubicTo()` calls. [Catmull-Rom splines](https://qroph.github.io/2018/07/30/smooth-paths-using-catmull-rom-splines.html) pass through all control points and can be converted to cubic Bézier segments.

### 3. Implement Hobby's Algorithm Yourself

[Jake Low's excellent article](https://www.jakelow.com/blog/hobby-curves) explains the algorithm in detail. The key insight: Hobby curves minimize curvature variance (aesthetic) rather than ensuring C2 continuity (mathematical smoothness). The algorithm:
- Solves a tridiagonal linear system (O(n) time)
- Outputs cubic Bézier control points
- Use those with `Path.cubicTo()` in Skija

### For Skija specifically

```clojure
;; Pseudocode for using computed control points
(let [path (Path.)]
  (.moveTo path x0 y0)
  ;; For each segment, after computing Hobby control points:
  (.cubicTo path cp1x cp1y cp2x cp2y endx endy)
  path)
```

### Key Difference

| Algorithm | Continuity | Aesthetic Quality |
|-----------|------------|-------------------|
| Natural cubic spline | C2 | Can have large curvature swings |
| Hobby | G1 | Minimizes curvature variance (smoother appearance) |
| Catmull-Rom | C1 | Good balance, easy to implement |

**Recommendation**: If you need Hobby curves specifically for their aesthetic quality, you'll need to implement the algorithm yourself (it's ~50-100 lines) and feed the resulting control points to Skija's `Path.cubicTo()`. Otherwise, Catmull-Rom is a good practical alternative.

Sources:
- [Hobby's algorithm for aesthetic Bézier splines – Jake Low](https://www.jakelow.com/blog/hobby-curves)
- [Skia Fiddle: SmoothBezierSplineInterpolation](https://fiddle.skia.org/c/@SmoothBezierSplineInterpolation)
- [Smooth Paths Using Catmull-Rom Splines](https://qroph.github.io/2018/07/30/smooth-paths-using-catmull-rom-splines.html)
- [OpenRNDR Curves and Shapes Guide](https://guide.openrndr.org/drawing/curvesAndShapes.html)

---

## <a id="user-2"></a>User #2

Okay, create a plan to add a hobby curve and then also add an example in howto to display all the natural cubic spline, hobby, and catmull rom. also check /Users/as186073/Downloads/Skija-master to see what's already available

---

**Assistant:**

I'll start by exploring the codebase and the Skija source in parallel to understand what's available and how things are structured.

---

**Assistant:**

Good, I have a comprehensive picture. Let me now read the key files to understand the exact code patterns before designing the plan.

---

**Assistant:**

Now I have a thorough understanding of the codebase. Let me launch a Plan agent to design the implementation.

---

**Assistant:**

The Plan agent produced a comprehensive design. Let me read a couple more files to validate the drag interaction pattern and finalize.

---

**Assistant:**

Now I have full context. Let me write the final plan.

---

**Assistant:**

The plan covers two new files:

1. **`src/lib/graphics/curves.clj`** — Three curve algorithms (Natural Cubic Spline, Hobby, Catmull-Rom) that each take `[[x y] ...]` points and return a Skija `Path`. Skija has no built-in spline interpolation, so all three are implemented from scratch using `cubicTo` segments.

2. **`src/app/projects/howto/curves.clj`** — A visual comparison example with 3 vertically-stacked panels showing the same 7 control points rendered through each algorithm, with labels and color coding. Launched via `(open :howto/curves)`.

---

**Assistant:**

Let me do a more thorough search of the Skija source for any curve utilities we may have missed.

---

**Assistant:**

Now let me compare what Skija offers vs. what's already wrapped in `lib/graphics/path.clj`.

---

**Assistant:**

Here's the gap analysis — Skija curve primitives **not yet wrapped** in `lib/graphics/path.clj`:

**Missing Builder methods (curve primitives):**
1. **`conicTo`** — Conic section (rational quadratic Bezier with weight). w<1 = ellipse, w=1 = parabola, w>1 = hyperbola. Unique to Skia.
2. **`tangentArcTo`** — Arc tangent to two lines (PostScript-style `arct`)
3. **`ellipticalArcTo`** — SVG-style elliptical arc (rx, ry, rotation, arc size, direction, endpoint)
4. **Relative variants**: `rQuadTo`, `rCubicTo`, `rConicTo`, `rEllipticalArcTo`

**Missing Path utilities:**
5. **`convertConicToQuads`** — Approximate a conic with quadratic Bezier curves
6. **`makeInterpolate` / `isInterpolatable`** — Morph between two compatible paths
7. **`computeTightBounds`** — Bounds including curve extrema (not just control points)

**Missing PathMeasure:**
8. **`getRSXform`** — Rotation+scale+translation at distance
9. **`getMatrix`** — Full transformation matrix at distance
10. **`isClosed`** / **`nextContour`** — Contour iteration

**Missing Path queries:**
11. **`isOval`**, **`isRRect`**, **`isRect`** — Shape detection
12. **Degeneracy tests** — `isLineDegenerate`, `isQuadDegenerate`, `isCubicDegenerate`

**PathEffect (separate system, not currently wrapped at all):**
13. **`makeCorner`** — Round sharp corners
14. **`makeDash`** — Dash patterns
15. **`makeDiscrete`** — Randomized path
16. **`makePath1D`** — Repeat a path along another path
17. **`makePath2D`** / **`makeLine2D`** — 2D grid effects
18. **`makeSum`** / **`makeCompose`** — Composite effects

---

**Assistant:**

Natural cubic spline is mainly useful for:
- **Data visualization**: Smooth line through data points (charts, graphs) where mathematical smoothness (C2) matters
- **Camera/motion paths**: When you need perfectly smooth acceleration (no curvature discontinuities)
- **Scientific computing**: Where C2 continuity is a mathematical requirement

**For creative/graphics work (which this project is), it's the least useful of the three.** It can overshoot badly with unevenly spaced points and doesn't optimize for aesthetic quality like Hobby does. Catmull-Rom and Hobby cover the practical needs:

- **Catmull-Rom**: Best for interactive/freehand curves, simple to understand, good default
- **Hobby**: Best for aesthetic/design curves, METAFONT heritage, produces the most visually pleasing shapes

That said, it's only ~30 extra lines of code and having it in the howto makes for a nice educational comparison showing *why* Hobby/Catmull-Rom look better.

---

**Assistant:**

Here's my honest assessment of each:

**`conicTo`** — Low practical value for this project. Conic sections are Skia's internal representation of circles/ellipses. You'd use it for exact conic arcs in CAD-style work. But you already have `circle`, `oval`, and `arc` factories that cover the common cases. Rarely needed directly.

**`tangentArcTo`** — Moderate value. Useful for drawing smooth rounded corners where two arbitrary lines meet (like the PostScript `arct` operator). For example, creating a path that goes straight, then smoothly rounds a corner at a given radius. But `PathEffect.makeCorner()` or pre-computed `rrect` handles most practical cases.

**`ellipticalArcTo`** — Moderate value. It's the SVG `A` arc command (endpoint parametrization with rotation). Useful for importing/constructing SVG-compatible paths, pie charts with rotated ellipses. Your existing `arc-to` covers the center parametrization. This adds endpoint style + rotation support.

**Relative variants** (`rQuadTo`, etc.) — Low value in Clojure. These mirror SVG lowercase path commands (`q`, `c`, etc.). In Clojure you typically compute absolute positions, so relative drawing is rarely needed.

**`convertConicToQuads`** — Very niche. Only useful when exporting to renderers that don't support conics.

**Bottom line**: For a creative prototyping toolkit, **path interpolation is the clear winner** — it enables shape morphing animations. The rest are edge cases you can add later if needed. Let me update the plan.

---

**Assistant:**

Now let me update the plan with the user's decisions.

---

**Assistant:**

The plan is finalized with the user's decisions:

- **All three algorithms** (Natural Cubic Spline, Hobby, Catmull-Rom)
- **Path interpolation** added to `path.clj` (morphing between compatible paths)
- **Skip** other Skija primitives (conicTo, tangentArcTo, ellipticalArcTo, etc.) — low practical value for this project

Three files touched: one modified (`path.clj`), two new (`curves.clj` library + `curves.clj` howto example).

---

**Assistant:**

Here's the comparison:

### `tangentArcTo` — Per-corner control while building

```clojure
;; Round a specific corner during path construction
;; Adds an arc tangent to lines: (current→p1) and (p1→p2)
(-> (path/builder)
    (path/move-to 50 200)
    (path/tangent-arc-to 200 50 350 200 30)  ;; round THIS corner with radius 30
    (path/line-to 350 200)                    ;; sharp corner here (no rounding)
    (path/build))
```

**Use case**: You're building a path step-by-step and want to round *specific* corners with *different* radii. Like constructing a custom shape where corner A has radius 10 and corner B has radius 30.

### `PathEffect.makeCorner` — Blanket rounding after the fact

```clojure
;; Round ALL sharp corners in an existing path
;; Applied during rendering, not during construction
(let [sharp-path (path/polygon [[50 200] [200 50] [350 200]])
      corner-effect (PathEffect/makeCorner (float 30))]
  ;; The effect rounds every corner uniformly
  (shapes/path canvas sharp-path {:path-effect corner-effect}))
```

**Use case**: You have an already-built path (or receive one from elsewhere) and want to soften all corners uniformly. Simpler API — one call rounds everything.

### Key Differences

| | `tangentArcTo` | `PathEffect.makeCorner` |
|---|---|---|
| **When** | During path construction | After path is built |
| **Granularity** | Per-corner, different radii | All corners, same radius |
| **Modifies path** | Yes, adds arc segments | No, applies at render time |
| **Complexity** | More control, more work | One line, fire-and-forget |

In practice, `makeCorner` covers 90% of use cases (round all corners uniformly). `tangentArcTo` is for surgical control over individual corners.

---

**Assistant:**

Updated the plan to include `tangent-arc-to` in the builder section. Ready for approval.

---

## <a id="user-3"></a>User #3

Also in our code base we have added `wave` Check if its implementation is right or if it can be improved. Also, do we have path effect to make corner already in our code base? Because I'm not totally sure about it. 

---

**Assistant:**

Let me check both things.

---

**Assistant:**

Let me also read the filters file to see the full PathEffect wrappers.

---

**Assistant:**

Here are my findings:

### PathEffect.makeCorner — Already in the codebase

`lib/graphics/filters.clj:167-176` already wraps it:

```clojure
(defn corner-path-effect [radius]
  (PathEffect/makeCorner (float radius)))
```

Along with `dash-path-effect` and `discrete-path-effect`. So the PathEffect system is already covered. That said, `tangentArcTo` serves a different purpose (per-corner control during path building vs. blanket rounding after), so it's still worth adding.

### Wave Implementation — Works but can be improved

Current implementation (`path.clj:117-137`):

```clojure
;; Uses 100 lineTo segments (straight lines approximating a sine)
segments 100
...
(.lineTo pb (float px) (float py))
```

**Issues:**

1. **Straight line segments, not curves.** It draws 100 tiny straight lines. This means:
   - **Discontinuous tangents** at every vertex — if you use this path for text-on-path, the text will have tiny angle jumps at each joint
   - **Visible faceting** when zoomed in
   - 100 path verbs is heavier than necessary

2. **Hardcoded segment count doesn't scale with frequency.** A 1-cycle wave gets 100 segments (overkill). A 20-cycle wave also gets 100 segments (only 5 per cycle — could look rough).

**Better approach — cubic Bezier approximation:**

A sine wave can be closely approximated with **4 cubic Bezier segments per cycle** using Hermite interpolation. At each quarter-wave point, we know the exact position and slope:

| Point | Position | Slope |
|-------|----------|-------|
| t=0 | y = 0 | y' = +Aω |
| t=T/4 | y = +A | y' = 0 |
| t=T/2 | y = 0 | y' = -Aω |
| t=3T/4 | y = -A | y' = 0 |

Convert Hermite → Bezier control points: `cp1 = P0 + tangent0/3`, `cp2 = P1 - tangent1/3`. This gives:
- Smooth tangents everywhere (C1 continuous) — better for text-on-path
- Only `4 × frequency` segments instead of 100
- Accurate at any zoom level

---

**Assistant:**

Plan updated with all three changes to `path.clj`:
1. **Wave improvement** — cubic Bezier (4 segments/cycle) replacing 100 lineTo segments
2. **tangent-arc-to** — per-corner arc rounding in the builder
3. **Path interpolation** — morphing between compatible paths

Plus the two new files (`curves.clj` library + howto example) remain unchanged.

---

## <a id="user-4"></a>User #4

one more thing, there is https://skia-canvas.org/api/context#linedashmarker Can you check if we have any equivalent of this in /Users/as186073/Downloads/Skija-master I just want to know and also about that if we have it, especially for dashes, is it possible to change the size of those dashes I guess. For other patterns that can be drawn on the line it's fine. Whatever we have we'll keep that I guess. If the other patterns also have flexibility then it's even better. 

---

## <a id="user-5"></a>User #5

one more thing, there is https://skia-canvas.org/api/context#linedashmarker Can you check if we have any equivalent of this in /Users/as186073/Downloads/Skija-master I just want to know and also about that if we have it, especially for dashes, is it possible to change the size of those dashes I guess. For other patterns that can be drawn on the line it's fine. Whatever we have we'll keep that I guess. If the other patterns also have flexibility then it's even better. also check for lineDashFit in that page and then search for same/something similar in skija-master. Then maybe if you're not able to find it, then probably like search the web because it may come up with a different name. So it's better to search the web for skia equivalent than search in skija-master codebase 

---

**Assistant:**

Here's the full picture:

## lineDashMarker + lineDashFit → Skija Equivalent

**skia-canvas** has a nice API, but it's a wrapper around native Skia features. Here's the mapping:

| skia-canvas | Skija equivalent | In our codebase? |
|---|---|---|
| `lineDashMarker = path` | `PathEffect.makePath1D(path, advance, phase, style)` | **No** — not wrapped |
| `lineDashFit = "move"` | `PathEffect1DStyle.TRANSLATE` | **No** — not wrapped |
| `lineDashFit = "turn"` | `PathEffect1DStyle.ROTATE` | **No** — not wrapped |
| `lineDashFit = "follow"` | `PathEffect1DStyle.MORPH` | **No** — not wrapped |
| `setLineDash([10, 5])` | `PathEffect.makeDash(intervals, phase)` | **Yes** — `dash-path-effect` in `filters.clj` |

### What Skija's `makePath1D` does

```java
PathEffect.makePath1D(
  Path path,       // The marker shape (e.g. arrow, dot, cross)
  float advance,   // Distance between each stamp
  float phase,     // Offset (for animation: update each frame)
  PathEffect1DStyle style  // TRANSLATE, ROTATE, or MORPH
)
```

The three styles match skia-canvas's `lineDashFit`:
- **TRANSLATE** = `"move"` — stamps without rotation
- **ROTATE** = `"turn"` — rotates to follow path direction
- **MORPH** = `"follow"` — bends the marker to match path curvature (most sophisticated)

### Dash sizing flexibility

For `makeDash`: yes, you fully control sizes via the `intervals` array. `[10 5]` = 10px dash, 5px gap. `[20 10 5 10]` = long dash, gap, dot, gap. `phase` offsets the start position (animatable).

### lineDashFit (auto-fit dashes to path length)

**This does NOT exist in native Skia.** `lineDashFit` is a skia-canvas convenience. To achieve the same in Skia/Skija, you manually:
1. Measure path length with `PathMeasure`
2. Calculate adjusted intervals so the pattern divides evenly
3. Create `DashPathEffect` with adjusted values

### What's missing in our codebase

The main gap is `makePath1D` — the ability to stamp a custom shape along a path. Our `filters.clj` already has `dash-path-effect`, `corner-path-effect`, and `discrete-path-effect`, but **not** `path-1d-effect`.

---

**Assistant:**

Yes, Skia has Path2D. Here's the full picture of what exists:

### Path1D vs Path2D — Different use cases

**`makePath1D(path, advance, phase, style)`** — Stamps a shape **along a 1D path** (like beads on a string)
- Use for: arrows along a curve, dots on a line, custom dash markers
- The `lineDashMarker` + `lineDashFit` equivalent

**`makePath2D(matrix, path)`** — Stamps a shape in a **2D grid/lattice** (fills an area)
- Use for: [hatching, cross-hatching](https://learn.microsoft.com/en-us/xamarin/xamarin-forms/user-interface/graphics/skiasharp/curves/effects), tiling patterns, fabric textures, geometric fills
- The matrix controls grid spacing + rotation (e.g., `Matrix33.makeScale(20, 20)` = every 20px in both directions; add rotation for diagonal hatch)

**`makeLine2D(width, matrix)`** — Special case of Path2D where the pattern is a straight line
- Use for: line-based hatching (horizontal, vertical, diagonal lines at given spacing)
- Simpler API when you just want parallel lines

### Visual comparison

```
Path1D: stamps along a path       Path2D: tiles across a 2D area
   ★---★---★---★                   ★  ★  ★  ★
      (curve)                      ★  ★  ★  ★
                                   ★  ★  ★  ★

Line2D: hatching lines
   ──────────────
   ──────────────
   ──────────────
```

### What our codebase has vs missing

| Effect | Skija method | In `filters.clj`? |
|---|---|---|
| Dashes | `makeDash` | **Yes** — `dash-path-effect` |
| Corner rounding | `makeCorner` | **Yes** — `corner-path-effect` |
| Jagged/sketchy | `makeDiscrete` | **Yes** — `discrete-path-effect` |
| Custom shape along path | `makePath1D` | **No** |
| 2D pattern fill | `makePath2D` | **No** |
| Line hatching | `makeLine2D` | **No** |
| Combine (side-by-side) | `makeSum` | **No** |
| Combine (cascade) | `makeCompose` | **No** |

### Performance note

For large filled areas, [Path2D hatching can be slow](https://bclehmann.github.io/2022/11/05/HatchingWithSKShader/) (~10 FPS). Shader-based tiling performs much better (~300 FPS) for real-time rendering. Path2D is fine for small areas or non-interactive use.

---

**Assistant:**

Great point. Let me design friendlier names. Let me check skia-canvas's terminology more carefully.

---

**Assistant:**

Let me also check how the existing path effects integrate with the paint system to ensure the new API stays consistent.

---

**Assistant:**

Now I have the full picture. Here's the proposed idiomatic API — much friendlier than Skia's `makePath1D`/`makePath2D` naming:

```clojure
;; Stamp shapes along a path (Skia: makePath1D / skia-canvas: lineDashMarker)
(stamp-path-effect arrow-path 20)                         ;; stamp every 20px
(stamp-path-effect arrow-path 20 {:fit :follow})          ;; bend to match curve
(stamp-path-effect dot-path 15 {:fit :turn :offset 5})    ;; rotate + animated offset

;; Tile a shape in a 2D grid (Skia: makePath2D)
(tile-path-effect star-path 30 30)                        ;; 30×30 grid
(tile-path-effect star-path 30 30 {:angle 45})            ;; diagonal tiling

;; Parallel line hatching (Skia: makeLine2D)
(hatch-path-effect 2 10)                                  ;; 2px lines, 10px apart
(hatch-path-effect 2 10 {:angle 45})                      ;; diagonal hatching

;; Combine effects
(sum-path-effects dash corner)        ;; both visible
(compose-path-effects outer inner)    ;; cascaded

;; Usage with shapes API (via :path-effect opt)
(shapes/path canvas my-path {:mode :stroke
                              :path-effect (stamp-path-effect arrow 20 {:fit :turn})})
```

The `:fit` keyword uses skia-canvas terms: `:move`, `:turn`, `:follow` instead of Skia's opaque `TRANSLATE`/`ROTATE`/`MORPH`.

Let me update the plan with all of this.

---

## <a id="user-6"></a>User #6

oh if this is the case  For large filled areas, https://bclehmann.github.io/2022/11/05/HatchingWithSKShader/ (~10 FPS). Shader-based tiling performs much better (~300 FPS) for real-time rendering then Then let's not add that 2D path effect and instead use shader based tiling for tiling grids or whatever, right?

---

**Assistant:**

That's a reasonable instinct, but let me add nuance:

The 10 FPS figure is specifically for **large filled areas** (like filling a full-screen shape with hatching). For small-to-medium decorative patterns, `Path2D` works fine. The performance concern is proportional to the fill area.

That said, for a real-time graphics toolkit like yours, if tiling is needed, the shader approach is indeed the right default. You already have shader infrastructure (`lib/graphics/shaders.clj` + `:sksl` support in `state.clj`).

Here's what I'd recommend for the plan scope:

- **Add**: `stamp-path-effect` (Path1D) — stamps shapes along a *stroke* (1D). Not area-filling, so no perf issue. This is the `lineDashMarker` equivalent.
- **Add**: `sum-path-effects` / `compose-path-effects` — combining effects is universally useful.
- **Skip**: `tile-path-effect` (Path2D) and `hatch-path-effect` (Line2D) — these fill *areas* and are the ones with perf issues. When tiling is needed, shader-based approach is better.

---

**Assistant:**

Got it — you want the full set of effects, but implement 2D tiling/hatching via shaders (better perf) instead of `Path2D`/`Line2D`. Let me check what shader infrastructure already exists.

---

**Assistant:**

Now I have the full picture. Here's what makes sense for the 2D case:

For **shader-based 2D tiling**, your codebase already has everything needed. The `lib/graphics/shaders.clj` infrastructure is complete with SkSL support. For simple patterns (grids, hatching, dots), an SkSL shader using `mod()` is the idiomatic approach:

```clojure
;; Example: diagonal hatch shader (already possible today with shaders.clj)
(shader
  "uniform float uSpacing;
   uniform float uWidth;
   uniform float uAngle;
   
   half4 main(float2 coord) {
     float c = cos(uAngle), s = sin(uAngle);
     float d = mod(coord.x * c + coord.y * s, uSpacing);
     float alpha = smoothstep(uWidth * 0.5 + 0.5, uWidth * 0.5 - 0.5, abs(d - uSpacing * 0.5));
     return half4(alpha);
   }"
  {:uSpacing 12.0 :uWidth 2.0 :uAngle 0.785})  ;; 45° hatching
```

For **complex shapes tiled** (arbitrary paths repeated), you'd render to an offscreen Surface → create Image → `Image.makeShader(REPEAT, REPEAT)`. That's a different infrastructure piece.

Given the scope is already substantial, I think the cleanest approach is:

1. **Add to `filters.clj`**: `stamp-path-effect` (Path1D), `sum-path-effects`, `compose-path-effects` — these are PathEffect-based and needed
2. **Skip Path2D/Line2D** wrapping entirely — shaders are better for this
3. **Optionally add `hatch-shader`/`tile-shader` convenience functions** to `shaders.clj` — since the SkSL infra already exists, this is just adding pre-built shader patterns (like the existing `noise-shader`, `gradient-shader`)

---

**Assistant:**

Yes, I'll organize the plan into three separate tasks that can be implemented independently. Let me finalize the plan now.

---

**Assistant:**

The plan is now organized into three independent tasks:

1. **Curve algorithms + howto example** — `curves.clj` library (3 algorithms) + visual comparison demo
2. **Path effects + path.clj** — wave improvement, tangent-arc-to, interpolation, stamp-path-effect, sum/compose
3. **2D pattern shaders** — hatch, grid, dot patterns via GPU-accelerated SkSL shaders (not Path2D)

---

## <a id="user-7"></a>User #7

also pls add examples for all in howto We already have example for curve that we already discussed. For 1D and effects, yeah, an example, another how-to. And for 2D and all, another how-to.

---

**Assistant:**

Updated. The plan now includes three howto examples:

1. **`howto/curves.clj`** — 3-panel curve algorithm comparison (already planned)
2. **`howto/path_effects.clj`** — stamp `:move`/`:turn`/`:follow` + composed/summed effects on a shared wavy path
3. **`howto/pattern_shaders.clj`** — hatch, grid, dot patterns applied to shapes, showing GPU-accelerated 2D tiling

---


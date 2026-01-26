# Plan: Curves, Path Effects & 2D Pattern Shaders

Three independent tasks, each with its own howto example.

---

## Task 1: Curve Interpolation Library + Howto Example

### Files

| File                                | Action                              |
| ----------------------------------- | ----------------------------------- |
| `src/lib/graphics/curves.clj`       | **Create** — 3 curve algorithms     |
| `src/app/projects/howto/curves.clj` | **Create** — visual comparison demo |

### `lib/graphics/curves.clj` — Public API

```clojure
(defn natural-cubic-spline [points])        ;; C2 continuous
(defn hobby-curve [points] [points opts])   ;; G1, opts: {:tension 1.0}
(defn catmull-rom [points] [points opts])   ;; C1, opts: {:alpha 0.5}
```

All take `[[x y] ...]` (min 2), return Skija `Path` via `path/builder` + `path/cubic-to`.

**Natural Cubic Spline (C2):** Tridiagonal system → Thomas algorithm (O(n)) → P1 control points → derive P2. Natural boundary (S''=0 at endpoints). Use `double-array` to avoid boxing.

**Hobby Curve (G1/METAFONT):**

1. Segment angles ψ, chord lengths d, turning angles δ
2. Tridiagonal system → θ angles, derive φ = -δ - θ
3. Hobby's ρ function: `ρ(θ,φ) = (2 + √2·(sinθ - sinφ/16)·(sinφ - sinθ/16)·(cosθ - cosφ)) / (3·(1 + ½(√5-1)·cosθ + ½(3-√5)·cosφ))`
4. Control points at distance ρ·d/tension along computed angles

**Catmull-Rom (C1, centripetal):** Extend endpoints by reflection → for each segment use 4 surrounding points → Barry-Goldman tangents with `t = dist^alpha` → convert to cubic Bezier: `cp1 = p1 + m1/3`, `cp2 = p2 - m2/3`. Epsilon floor (1e-6) for zero distances.

**Edge cases:** <2 points → empty path. 2 points → straight line. Coincident points → epsilon guard.

### `app/projects/howto/curves.clj` — Visual Demo

Three vertically-stacked panels, same 7 control points, each showing one algorithm:

```
┌──────────────────────────────┐
│   Curve Interpolation        │
├──────────────────────────────┤
│  Natural Cubic Spline  (C2)  │  blue 0xFF4A90D9
├──────────────────────────────┤
│  Hobby Curve  (G1/METAFONT)  │  green 0xFF2ECC71
├──────────────────────────────┤
│  Catmull-Rom (C1/centripetal)│  purple 0xFF9B59B6
└──────────────────────────────┘
```

Each panel: label + thin gray control polygon + colored curve stroke + red control point dots. No drag interaction.

### Verification

```bash
clj -A:dev:macos-arm64   # restart required (new lib/* file)
# In REPL:
(open :howto/curves)
```

---

## Task 2: Path Effects + path.clj Additions

### Files

| File                                      | Action                                                       |
| ----------------------------------------- | ------------------------------------------------------------ |
| `src/lib/graphics/path.clj`               | **Modify** — wave improvement, tangent-arc-to, interpolation |
| `src/lib/graphics/filters.clj`            | **Modify** — stamp, sum, compose path effects                |
| `src/app/projects/howto/path_effects.clj` | **Create** — visual demo of path effects                     |

### `path.clj` changes

**A. Improve `wave` factory** — Replace 100 `lineTo` segments with **4 cubic Bezier segments per cycle** using Hermite interpolation at quarter-wave points. Smooth tangents (C1), fewer verbs, zoom-accurate.

**B. Add `tangent-arc-to`** to Builder section:

```clojure
(defn tangent-arc-to
  "Draw an arc tangent to two lines: (current→p1) and (p1→p2).
   Rounds the corner at p1 with the given radius."
  [^PathBuilder pb x1 y1 x2 y2 radius]
  (.tangentArcTo pb (float x1) (float y1) (float x2) (float y2) (float radius))
  pb)
```

**C. Add path interpolation** (after Boolean Operations):

```clojure
(defn interpolatable? [^Path p1 ^Path p2]
  (.isInterpolatable p1 p2))

(defn interpolate [^Path p1 ^Path p2 weight]
  (.makeInterpolate p1 p2 (float weight)))
```

### `filters.clj` additions — Idiomatic path effects

**stamp-path-effect** (Skia's `makePath1D` / skia-canvas's `lineDashMarker`):

```clojure
(defn stamp-path-effect
  "Stamp a shape repeatedly along a stroked path.

   Args:
     marker  - Path shape to stamp (centered at origin)
     spacing - distance between stamps (pixels)
     opts    - optional map:
               :offset - phase offset for animation (default 0)
               :fit    - how marker follows path (default :turn)
                         :move   - translate only
                         :turn   - translate + rotate to follow direction
                         :follow - bend marker to match path curvature"
  ([marker spacing] ...)
  ([marker spacing {:keys [offset fit] :or {offset 0 fit :turn}}] ...))
```

**sum-path-effects** / **compose-path-effects**:

```clojure
(defn sum-path-effects
  "Combine two path effects (both visible simultaneously)."
  [effect1 effect2]
  (.makeSum effect1 effect2))

(defn compose-path-effects
  "Compose two path effects (apply outer after inner)."
  [outer inner]
  (.makeCompose outer inner))
```

Requires adding to imports: `PathEffect`, `PathEffect1DStyle`.

### `app/projects/howto/path_effects.clj` — Visual Demo

Shows stamp, dash, corner, discrete, and composed effects on the same base path (a wavy curve):

```
┌──────────────────────────────────┐
│   Path Effects                   │
├──────────────────────────────────┤
│  Stamp :move      ★  ★  ★  ★    │  arrows/dots translated along path
├──────────────────────────────────┤
│  Stamp :turn      ➤ ➤ ➤ ➤       │  arrows rotated to follow direction
├──────────────────────────────────┤
│  Stamp :follow    ～～～～       │  shapes bent to match curvature
├──────────────────────────────────┤
│  Composed: dash + corner         │  rounded dashed line
├──────────────────────────────────┤
│  Sum: dash + discrete            │  both effects visible together
└──────────────────────────────────┘
```

Each row: label + base path (thin gray) + effect applied (colored). Uses a shared wavy/S-curve path. Marker shapes built with `path/builder` (arrow, circle, diamond).

### Verification

```bash
clj -A:dev:macos-arm64   # restart required
# In REPL:
(open :howto/path-effects)
```

---

## Task 3: 2D Pattern Shaders (hatch, grid, dots)

### Files

| File                                         | Action                                      |
| -------------------------------------------- | ------------------------------------------- |
| `src/lib/graphics/shaders.clj`               | **Modify** — add pattern shader helpers     |
| `src/app/projects/howto/pattern_shaders.clj` | **Create** — visual demo of pattern shaders |

Add pre-built SkSL shader patterns (like existing `noise-shader`, `gradient-shader`):

```clojure
(defn hatch-shader
  "Create a parallel line hatching shader.
   Args: line-width, spacing, opts {:angle 0 :color 0xFFFFFFFF}"
  [line-width spacing & [opts]] ...)

(defn grid-shader
  "Create a grid pattern shader.
   Args: line-width, spacing-x, spacing-y, opts {:color 0xFFFFFFFF}"
  [line-width spacing-x spacing-y & [opts]] ...)

(defn dot-pattern-shader
  "Create a repeating dot pattern shader.
   Args: dot-radius, spacing-x, spacing-y, opts {:color 0xFFFFFFFF}"
  [dot-radius spacing-x spacing-y & [opts]] ...)
```

These use SkSL (`mod()`, `smoothstep()`) for GPU-accelerated rendering — much faster than Path2D for large areas (~300 FPS vs ~10 FPS).

**Why shaders over Path2D PathEffect**: Path2D hatching is slow for large filled areas. Shader-based tiling runs on GPU at full framerate. The existing `lib/graphics/shaders.clj` has full SkSL infrastructure already.

### `app/projects/howto/pattern_shaders.clj` — Visual Demo

Shows hatch, grid, and dot patterns applied to various shapes:

```
┌──────────────────────────────────┐
│   2D Pattern Shaders (GPU)       │
├─────────────────┬────────────────┤
│  Hatch (0°)     │  Hatch (45°)   │  parallel lines, horizontal vs diagonal
│  ═══════════    │  ╲╲╲╲╲╲╲╲╲    │
├─────────────────┬────────────────┤
│  Grid           │  Dot Pattern   │  grid lines vs repeating dots
│  ┼┼┼┼┼┼┼┼┼     │  ● ● ● ● ●   │
│  ┼┼┼┼┼┼┼┼┼     │  ● ● ● ● ●   │
├─────────────────┴────────────────┤
│  Cross-hatch (composed)          │  two angled hatches overlaid
│  ╳╳╳╳╳╳╳╳╳╳╳╳╳╳╳╳╳╳╳╳╳╳╳╳╳╳╳  │
└──────────────────────────────────┘
```

Each cell: a rounded rectangle filled with the pattern shader. Labels showing function call and params. Demonstrates that shaders are GPU-accelerated and maintain full framerate even on large areas.

### Verification

```bash
clj -A:dev:macos-arm64   # restart required
# In REPL:
(open :howto/pattern-shaders)
```

---

## Key Reference Files

- `src/lib/graphics/path.clj` — PathBuilder API
- `src/lib/graphics/shapes.clj` — Drawing API
- `src/lib/graphics/filters.clj` — Existing path effects (dash, corner, discrete)
- `src/lib/graphics/shaders.clj` — SkSL shader infrastructure
- `src/app/projects/howto/text_on_path.clj` — Howto example pattern
- `src/app/projects/howto/variable_font.clj` — Another howto pattern

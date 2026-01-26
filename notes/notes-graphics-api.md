# Graphics API Design Philosophy

## Idiomatic Clojure / Love2D Style

Following Skija's own philosophy and Love2D's design principles:

> **Hide implementation details. Feel like a Clojure library, not a Java wrapper.**

### Core Principles

1. **Pure Data** - Use maps, vectors, keywords, numbers
2. **No Type Leakage** - Hide Java classes (Paint, ImageFilter, Shader, etc.)
3. **Declarative** - Describe what you want, not how to build it
4. **Composable** - Effects combine with simple map merging
5. **Sensible Defaults** - Common cases work with minimal code

---

## API Overview

### Basic Shapes

```clojure
(require '[lib.graphics.shapes :as shapes])

;; Minimal - just position and size
(shapes/circle canvas 100 100 50)

;; With color
(shapes/circle canvas 100 100 50 {:color 0xFF4A90D9})

;; Stroked
(shapes/circle canvas 100 100 50 {:mode :stroke :stroke-width 3})

;; All shapes work the same way
(shapes/rectangle canvas 10 10 100 50 {:color 0xFF00FF00})
(shapes/rounded-rect canvas 10 10 100 50 10 {:color 0xFFFFFF00})
(shapes/line canvas 0 0 100 100 {:stroke-width 2})
```

### Text Rendering

```clojure
(require '[lib.graphics.text :as text])

;; Simple text
(text/text canvas "Hello" 10 20)

;; Colored with custom size
(text/text canvas "Hello" 10 20 {:size 24 :color 0xFF4A90D9})

;; With effects (see below)
(text/text canvas "GLOW" 10 50
  {:size 48
   :shadow {:dx 0 :dy 0 :blur 10 :color 0xFF00FFFF}})
```

### Batch Drawing (High Performance)

```clojure
(require '[lib.graphics.batch :as batch])

;; Multiple points - flexible input formats
(batch/points canvas [{:x 100 :y 100} {:x 200 :y 200}] 5 {:color 0xFF4A90D9})
(batch/points canvas [[100 100] [200 200]] 5)
(batch/points canvas (float-array [100 100 200 200]) 5) ; fastest

;; Multiple line segments
(batch/lines canvas (float-array [0 0 100 100  100 100 200 50]))
```

---

## Effects System - The Key Innovation

### ❌ Bad (Exposing Implementation)

```clojure
;; User has to know about ImageFilter, FilterTileMode, etc.
(shapes/circle canvas 100 100 50
  {:image-filter (ImageFilter/makeBlur 5.0 5.0 FilterTileMode/DECAL)})

(shapes/circle canvas 100 100 50
  {:image-filter (ImageFilter/makeDropShadow 2 2 3.0 3.0 0x80000000)})
```

### ✅ Good (Idiomatic Clojure)

```clojure
;; Pure data - no Java types!
(shapes/circle canvas 100 100 50 {:blur 5.0})

(shapes/circle canvas 100 100 50
  {:shadow {:dx 2 :dy 2 :blur 3 :color 0x80000000}})
```

### Available Effects

#### Blur Effects
```clojure
;; Simple blur
{:blur 5.0}

;; Blur with tile mode
{:blur [5.0 :clamp]}

;; Drop shadow
{:shadow {:dx 2 :dy 2 :blur 3 :color 0x80000000}}

;; Outer glow
{:glow {:size 10 :mode :outer}}
```

#### Color Effects
```clojure
;; Boolean flags
{:grayscale true}
{:sepia true}

;; Numeric adjustments
{:brightness 0.3}    ; -1.0 to 1.0
{:contrast 1.5}      ; 0.0 to 2.0

;; Alpha
{:alphaf 0.5}        ; 0.0 to 1.0
{:alpha 128}         ; 0 to 255
```

#### Gradients
```clojure
;; Linear gradient
{:gradient {:type :linear
            :x0 0 :y0 0
            :x1 100 :y1 0
            :colors [0xFFFF0000 0xFF0000FF]}}

;; Radial gradient with stops
{:gradient {:type :radial
            :cx 100 :cy 100
            :radius 50
            :colors [0xFFFFFFFF 0xFF4A90D9 0xFF000000]
            :stops [0.0 0.5 1.0]}}

;; Sweep gradient (color wheel)
{:gradient {:type :sweep
            :cx 100 :cy 100
            :colors [0xFFFF0000 0xFFFFFF00 0xFF00FF00]}}

;; Repeating gradients
{:gradient {:type :linear
            :x0 0 :y0 0 :x1 20 :y1 0
            :colors [0xFF000000 0xFFFFFFFF]
            :tile-mode :repeat}}
```

#### Line Styles
```clojure
;; Dashed lines
{:dash [10 5]}       ; 10px dash, 5px gap
{:dash [5 5 10 5]}   ; dot-dash pattern
```

#### Blend Modes
```clojure
{:blend-mode :multiply}
{:blend-mode :screen}
{:blend-mode :overlay}
{:blend-mode :darken}
{:blend-mode :lighten}
;; ... and 25+ more modes
```

### Combining Effects

```clojure
;; Multiple effects compose naturally!
(shapes/circle canvas 100 100 50
  {:gradient {:type :radial
              :cx 100 :cy 100 :radius 50
              :colors [0xFFFF00FF 0xFF00FFFF]}
   :shadow {:dx 5 :dy 5 :blur 10 :color 0x80000000}
   :blend-mode :screen
   :alphaf 0.8})
```

---

## Design Comparisons

### Love2D (Lua)
```lua
love.graphics.setColor(1, 0, 0)
love.graphics.circle("fill", 100, 100, 50)
```

### Our API (Clojure)
```clojure
(shapes/circle canvas 100 100 50 {:color 0xFFFF0000})
```

**Key Difference:** We pass `canvas` explicitly (no global state), but everything else follows Love2D's declarative style.

### Processing (Java)
```java
fill(255, 0, 0);
circle(100, 100, 50);
```

### Our API (Clojure)
```clojure
(shapes/circle canvas 100 100 50 {:color 0xFFFF0000})
```

**Advantage:** All parameters in one place (no global state mutations).

---

## Advanced Features

### Power User Escape Hatches

For advanced users who need direct Skija access:

```clojure
;; Use pre-created Java objects (escape hatch)
{:shader my-shader-instance}
{:color-filter my-filter-instance}
{:image-filter my-filter-instance}
{:paint my-paint-instance}
```

But **99% of users never need this** - they just use the idiomatic maps!

### Low-Level Filter Creation

For cases where the high-level API doesn't cover your need:

```clojure
(require '[lib.graphics.filters :as filters])
(require '[lib.graphics.gradients :as gradients])

;; Create filters manually
(def my-blur (filters/blur 5.0))
(def my-gradient (gradients/linear-gradient 0 0 100 0
                   [0xFFFF0000 0xFF0000FF]))

;; Then use them
(shapes/circle canvas 100 100 50 {:image-filter my-blur})
(shapes/circle canvas 100 100 50 {:shader my-gradient})
```

Still idiomatic Clojure - no raw Java!

---

## Summary

**Before this redesign:**
- Users had to learn Skija Java APIs
- Lots of `ImageFilter/makeBlur(...)` boilerplate
- Paint objects everywhere
- Hard to discover features

**After this redesign:**
- Pure Clojure data structures
- `:blur 5.0` - can't be simpler!
- Implementation hidden completely
- Self-documenting through examples

**The Goal:**
> Users should never have to look at Skija documentation.
> Everything should feel like native Clojure.

✨ **Mission Accomplished!**

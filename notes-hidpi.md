# Development Notes

## Physical vs Logical Pixels

### Coordinate Systems

JWM and Skija work in **physical pixels** by default. We convert everything to **logical pixels** for consistent UI across displays.

| System | Coordinate Type | Description |
|--------|-----------------|-------------|
| JWM mouse events | Physical pixels | Raw screen pixels from OS |
| Skija surface | Physical pixels | Actual rendering buffer |
| Our drawing code | Logical pixels | Abstract units, device-independent |

### Background

Modern displays have varying pixel densities:
- Standard displays: 72-96 DPI
- Retina/HiDPI: 192-220+ DPI (2x or higher)
- Windows/Linux: Often fractional scaling (1.25x, 1.5x, 1.75x)

Without proper handling, UI elements appear tiny on HiDPI displays.

### Definitions

| Type | Description | Example |
|------|-------------|---------|
| **Physical pixels** | Actual LED/LCD diodes on screen | A 2880×1800 Retina display has 2880×1800 physical pixels |
| **Logical pixels** | Abstract units for UI layout | Same display might be 1440×900 logical pixels at 2x scale |

### Scale Factor

The ratio between physical and logical pixels.

Retrieved via: `window.getScreen().getScale()`

| Display Type | Scale | Physical | Logical |
|--------------|-------|----------|---------|
| Standard 1080p | 1.0 | 1920×1080 | 1920×1080 |
| MacBook Retina | 2.0 | 2880×1800 | 1440×900 |
| Windows 150% | 1.5 | 2880×1620 | 1920×1080 |
| 4K at 200% | 2.0 | 3840×2160 | 1920×1080 |

### Conversion Formulas

```
Physical = Logical × Scale
Logical  = Physical ÷ Scale
```

---

## JWM Behavior

From the official JWM documentation:

> "All pixel sizes are also unscaled. They correspond to the physical screen pixels, not 'logical' pixels."

JWM provides **physical pixels** for:
- `surface.getWidth()` / `surface.getHeight()` → physical pixels
- `event.getX()` / `event.getY()` (mouse events) → physical pixels
- `window.setContentSize(w, h)` → physical pixels

JWM abstracts away platform differences:
- macOS natively works in logical pixels
- Windows/Linux natively work in physical pixels
- JWM normalizes everything to physical pixels

---

## Skija Behavior

Skija renders to a surface in **physical pixels**. The canvas draws at 1:1 with the surface unless you apply transforms.

To work in logical pixels, apply `canvas.scale(scaleFactor, scaleFactor)` before drawing.

---

## The Problem

If we draw a 100px button without scaling:
- On 1x display: 100 physical pixels → looks correct
- On 2x display: 100 physical pixels → appears half size (too small!)

---

## The Solution

Always work in **logical pixels** by:

1. **Drawing:** Scale the canvas by the scale factor before drawing
2. **Mouse input:** Convert physical mouse coordinates to logical

---

## Implementation

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        JWM / OS                              │
│  (provides physical pixels for everything)                   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    app.core (drawing)                        │
│                                                              │
│  1. Get scale: window.getScreen().getScale()                │
│  2. Compute logical size: physical ÷ scale                  │
│  3. Scale canvas: canvas.scale(scale, scale)                │
│  4. Draw using logical coordinates                          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    app.controls (mouse)                      │
│                                                              │
│  1. Receive physical coords from JWM                        │
│  2. Convert: logical = physical ÷ scale                     │
│  3. Hit-test against logical positions                      │
└─────────────────────────────────────────────────────────────┘
```

### Drawing Pipeline

```clojure
;; In event listener for EventFrameSkija:

;; 1. Get scale factor from window's screen
(let [scale (.getScale (.getScreen window))

      ;; 2. Get physical dimensions from surface
      physical-w (.getWidth surface)
      physical-h (.getHeight surface)

      ;; 3. Calculate logical dimensions
      logical-w (/ physical-w scale)
      logical-h (/ physical-h scale)]

  ;; 4. Scale canvas to work in logical pixels
  (.save canvas)
  (.scale canvas (float scale) (float scale))

  ;; 5. Draw using logical coordinates
  ;; A 100px circle will appear the same size on all displays
  (.drawCircle canvas 100 100 50 paint)

  (.restore canvas))
```

### Mouse Input Pipeline

```clojure
(defn handle-mouse-event [event]
  ;; JWM gives physical coordinates
  (let [physical-x (.getX event)
        physical-y (.getY event)

        ;; Convert to logical coordinates
        scale @state/scale
        logical-x (/ physical-x scale)
        logical-y (/ physical-y scale)]

    ;; Now logical-x/y match our drawing coordinate system
    (when (point-in-rect? logical-x logical-y button-bounds)
      (handle-click))))
```

---

## Examples

### 2x Retina Display

```
Scale = 2.0
Window: 800×600 logical = 1600×1200 physical

Drawing a panel at logical (20, 20):
  → Canvas scaled by 2.0
  → Renders at physical (40, 40)
  → Appears correct size on screen

User clicks at physical (40, 40):
  → Convert: 40 ÷ 2.0 = 20
  → Logical (20, 20)
  → Matches panel position ✓
```

### 1.5x Windows Display

```
Scale = 1.5
Window: 800×600 logical = 1200×900 physical

Drawing a 100px button at logical (100, 100):
  → Renders at physical (150, 150)
  → Appears same size as on 1x display

Mouse click at physical (150, 150):
  → Convert: 150 ÷ 1.5 = 100
  → Logical (100, 100)
  → Matches button position ✓
```

### 1x Standard Display

```
Scale = 1.0
Window: 800×600 logical = 800×600 physical

No conversion needed - physical equals logical.
Code still works correctly (dividing by 1.0 is no-op).
```

---

## Key Points

1. **JWM always gives physical pixels** - surface size, mouse coords, window size
2. **Scale canvas before drawing** - `canvas.scale(scale, scale)` converts logical → physical
3. **Divide mouse coords by scale** - converts physical → logical for hit-testing
4. **Config values are logical** - define UI in logical pixels, scaling handles the rest
5. **Store scale in atom** - accessible to both drawing and input handling code

---

## References

- [JWM Getting Started](https://github.com/HumbleUI/JWM/blob/main/docs/Getting%20Started.md)
- [Humble UI Layout](https://tonsky.me/blog/humble-layout/)
- [HumbleUI/JWM GitHub](https://github.com/HumbleUI/JWM)

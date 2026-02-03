# Plan: Expose Skia's Gradient Interpolation API in Skija

## Goal

Expose the `SkGradientShader::Interpolation` struct through the HumbleUI/Skija Java bindings and wire it up through the Clojure wrapper, enabling native Oklab/OKLCH/LAB/LCH gradient interpolation on the GPU.

## What This Gives Us

- **15 color spaces** for gradient interpolation: Destination, sRGBLinear, Lab, OKLab, OKLabGamutMap, LCH, OKLCH, OKLCHGamutMap, sRGB, HSL, HWB, DisplayP3, Rec2020, ProphotoRGB, A98RGB
- **4 hue methods** for polar color spaces (LCH, OKLCH, HSL, HWB): Shorter, Longer, Increasing, Decreasing
- Zero performance cost — Skia does it internally in its optimized gradient pipeline

## What This Does NOT Give Us

Clojure2d's non-linear interpolation curves (cubic spline, monotone, Shepard, easings). Those control the *rate* of transition between stops, not the *color space*. They would still require pre-sampling if needed.

---

## Files to Modify

### Skija (at `/Users/as186073/Downloads/vvvv-clj/Skija/`)

| File | Action | Description |
|------|--------|-------------|
| `shared/java/GradientStyle.java` | **Modify** | Add `_interpColorSpace` and `_interpHueMethod` fields |
| `platform/cc/Shader.cc` | **Modify** | Update 8 JNI functions to pass `Interpolation` struct to Skia |
| `shared/java/Shader.java` | **Modify** | Update native method signatures to accept new int params |

### Clojure project (at `/Users/as186073/Downloads/vvvv-clj/clj-reload-skija/`)

| File | Action | Description |
|------|--------|-------------|
| `src/lib/graphics/gradients.clj` | **Modify** | Accept `:interp-color-space` and `:hue-method` options |
| `src/lib/graphics/state.clj` | **Modify** | Pass new options from paint maps to gradient functions |
| `src/app/projects/howto/gradient_spaces.clj` | **Create** | Howto example comparing color spaces side by side |

---

## Step-by-Step Implementation

### Step 1: Modify `GradientStyle.java`

Add interpolation color space and hue method as int fields to `GradientStyle`. This avoids creating new Java classes while keeping backward compatibility.

**Current:**
```java
public class GradientStyle {
    public static final int _INTERPOLATE_PREMUL = 1;
    public static GradientStyle DEFAULT = new GradientStyle(FilterTileMode.CLAMP, true, null);
    public final FilterTileMode _tileMode;
    public final boolean _premul;
    public final Matrix33 _localMatrix;
}
```

**New:** Add two fields + update constructor. Keep the old `DEFAULT` working (colorSpace=0 means Destination, hueMethod=0 means Shorter — both defaults).

```java
public class GradientStyle {
    public static final int _INTERPOLATE_PREMUL = 1;
    public static GradientStyle DEFAULT = new GradientStyle(FilterTileMode.CLAMP, true, null, 0, 0);

    public final FilterTileMode _tileMode;
    public final boolean _premul;
    public final Matrix33 _localMatrix;
    public final int _interpColorSpace;  // maps to Interpolation::ColorSpace enum (0-15)
    public final int _interpHueMethod;   // maps to Interpolation::HueMethod enum (0-3)
}
```

The int values map directly to the C++ enum ordinals:
- ColorSpace: 0=Destination, 1=SRGBLinear, 2=Lab, 3=OKLab, 4=OKLabGamutMap, 5=LCH, 6=OKLCH, 7=OKLCHGamutMap, 8=SRGB, 9=HSL, 10=HWB, 11=DisplayP3, 12=Rec2020, 13=ProphotoRGB, 14=A98RGB
- HueMethod: 0=Shorter, 1=Longer, 2=Increasing, 3=Decreasing

### Step 2: Update `Shader.java` native method declarations

Change `int flags` to `int flags, int interpColorSpace, int interpHueMethod` on all 8 `CS` native methods (the Color4f variants). The int-color variants can stay as-is (they use the old Skia API that doesn't support Interpolation).

Update the 4 Java wrapper methods (`makeLinearGradientCS`, etc.) to pass the new fields from `GradientStyle`.

**The 4 CS native methods to update:**
- `_nMakeLinearGradientCS` — add `int interpColorSpace, int interpHueMethod`
- `_nMakeRadialGradientCS` — add `int interpColorSpace, int interpHueMethod`
- `_nMakeTwoPointConicalGradientCS` — add `int interpColorSpace, int interpHueMethod`
- `_nMakeSweepGradientCS` — add `int interpColorSpace, int interpHueMethod`

### Step 3: Update `Shader.cc` (C++ JNI implementation)

For each of the 4 `*CS` JNI functions, change the signature to accept `jint interpColorSpace, jint interpHueMethod`, construct the `SkGradientShader::Interpolation` struct, and call the Interpolation-accepting overload of `MakeLinear`/`MakeRadial`/`MakeTwoPointConical`/`MakeSweep`.

**Example for linear (current line 66-77):**
```cpp
extern "C" JNIEXPORT jlong JNICALL Java_io_github_humbleui_skija_Shader__1nMakeLinearGradientCS
  (JNIEnv* env, jclass jclass, jfloat x0, jfloat y0, jfloat x1, jfloat y1,
   jfloatArray colorsArray, jlong colorSpacePtr, jfloatArray posArray,
   jint tileModeInt, jint flags, jint interpColorSpace, jint interpHueMethod,
   jfloatArray matrixArray) {
    // ... existing setup code ...

    SkGradientShader::Interpolation interp;
    interp.fInPremul = (flags & 1)
        ? SkGradientShader::Interpolation::InPremul::kYes
        : SkGradientShader::Interpolation::InPremul::kNo;
    interp.fColorSpace = static_cast<SkGradientShader::Interpolation::ColorSpace>(interpColorSpace);
    interp.fHueMethod = static_cast<SkGradientShader::Interpolation::HueMethod>(interpHueMethod);

    SkShader* ptr = SkGradientShader::MakeLinear(
        pts, reinterpret_cast<SkColor4f*>(colors), colorSpace,
        pos, env->GetArrayLength(posArray), tileMode,
        interp, localMatrix.get()).release();
    // ... cleanup ...
}
```

Repeat for radial, two-point conical, and sweep.

### Step 4: Rebuild Skija

```bash
export JAVA_HOME=$(/usr/libexec/java_home)
bb scripts/build-skija.clj
```

This runs `python3 script/build.py` in the Skija dir, packages JARs, copies to `.jars/`.

### Step 5: Update `lib/graphics/gradients.clj`

Add `interp-color-space` and `hue-method` parameters to `make-gradient-style` and propagate to all 4 gradient functions.

**Update `make-gradient-style`:**
```clojure
(defn- make-gradient-style
  [tile-mode interp-color-space hue-method]
  (GradientStyle. (parse-tile-mode tile-mode) true nil
                  (int (or interp-color-space 0))
                  (int (or hue-method 0))))
```

**Add keyword->int mapping:**
```clojure
(defn- parse-interp-color-space [cs]
  (case cs
    nil            0
    :destination   0
    :srgb-linear   1
    :lab           2
    :oklab         3
    :lch           5
    :oklch         6
    :srgb          8
    :hsl           9
    :hwb           10
    :display-p3    11
    0))

(defn- parse-hue-method [hm]
  (case hm
    nil          0
    :shorter     0
    :longer      1
    :increasing  2
    :decreasing  3
    0))
```

**Update all 4 gradient functions** to accept optional `interp-color-space` and `hue-method` params. Example for `linear-gradient`:

```clojure
(defn linear-gradient
  ([x0 y0 x1 y1 colors]
   (linear-gradient x0 y0 x1 y1 colors nil :clamp nil nil))
  ([x0 y0 x1 y1 colors positions]
   (linear-gradient x0 y0 x1 y1 colors positions :clamp nil nil))
  ([x0 y0 x1 y1 colors positions tile-mode]
   (linear-gradient x0 y0 x1 y1 colors positions tile-mode nil nil))
  ([x0 y0 x1 y1 colors positions tile-mode interp-color-space hue-method]
   (let [colors-arr (colors->color4f-array colors)
         positions-arr (when positions (float-array positions))
         style (make-gradient-style tile-mode interp-color-space hue-method)]
     (Shader/makeLinearGradient ...))))
```

### Step 6: Update `lib/graphics/state.clj`

Pass the new `:interp` and `:hue-method` keys from the gradient options map.

```clojure
:linear ((resolve 'lib.graphics.gradients/linear-gradient)
         (:x0 effect-value) (:y0 effect-value)
         (:x1 effect-value) (:y1 effect-value)
         (:colors effect-value)
         (:stops effect-value)
         (:tile-mode effect-value :clamp)
         (:interp effect-value)       ;; e.g. :oklab
         (:hue-method effect-value))   ;; e.g. :shorter
```

---

## Usage After Implementation

```clojure
;; Oklab gradient (perceptually uniform, no muddy midtones)
(shapes/rectangle canvas 0 0 400 100
  {:gradient {:type :linear
              :x0 0 :y0 0 :x1 400 :y1 0
              :colors [[1 1 0 1] [0 0 1 1]]
              :interp :oklab}})

;; OKLCH with hue method (controls which direction around the hue wheel)
(shapes/circle canvas 200 200 100
  {:gradient {:type :radial
              :cx 200 :cy 200 :radius 100
              :colors [[1 0 0 1] [0 0 1 1]]
              :interp :oklch
              :hue-method :longer}})

;; sRGB (legacy behavior, explicit)
(shapes/rectangle canvas 0 0 400 100
  {:gradient {:type :linear
              :x0 0 :y0 0 :x1 400 :y1 0
              :colors [[1 0 0 1] [0 1 0 1]]
              :interp :srgb}})
```

### Step 7: Create howto example `gradient_spaces.clj`

Create `src/app/projects/howto/gradient_spaces.clj` following the pattern of `pattern_shaders.clj`.

**Layout:** Grid of gradient bars, each showing the same color pair interpolated in a different color space. This makes the visual difference immediately obvious.

**Structure:**
```clojure
(ns app.projects.howto.gradient-spaces
  "Gradient Color Space Interpolation — comparing sRGB, Oklab, OKLCH, LAB, LCH.

   Shows the same color pairs rendered with different interpolation color spaces.
   Notice how sRGB produces muddy/dark midtones while perceptual spaces stay vibrant."
  (:require [lib.color.core :as color]
            [lib.graphics.shapes :as shapes]
            [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas]))
```

**Content — 3 sections:**

1. **Color space comparison** (same pair, different spaces):
   - Row of horizontal gradient bars for yellow→blue:
     - sRGB (default/legacy) — shows muddy gray middle
     - Oklab — stays vibrant
     - OKLCH — vibrant with hue shift
     - LAB — perceptual
     - LCH — perceptual polar

2. **Hue method comparison** (OKLCH with different hue methods):
   - Red→Blue with :shorter vs :longer vs :increasing vs :decreasing
   - Shows how hue method controls which path around the color wheel

3. **Practical color pairs** (Oklab vs sRGB):
   - Cyan→Red, Green→Purple, Orange→Teal
   - Each shown in sRGB vs Oklab side-by-side

Each gradient bar is a `shapes/rectangle` with `:gradient` map including the new `:interp` key. Labels above each bar using `text/text`.

**Example interface:** Standard `init`, `tick`, `draw`, `cleanup` functions.

**Launch:** `(open :howto/gradient-spaces)`

---

## Verification

1. **Build Skija**: `export JAVA_HOME=$(/usr/libexec/java_home) && bb scripts/build-skija.clj` — should complete without errors
2. **Syntax check**: `bb scripts/check.clj src/lib/graphics/gradients.clj src/lib/graphics/state.clj src/app/projects/howto/gradient_spaces.clj`
3. **Visual test**: Launch the howto example:
   ```clojure
   clj -A:dev:macos-arm64
   (open :howto/gradient-spaces)
   ```
   - Yellow→Blue in sRGB should show muddy gray middle
   - Yellow→Blue in Oklab should stay vibrant throughout
   - Hue method section should show different paths around the hue wheel

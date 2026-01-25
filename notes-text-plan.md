# Implementation Plan: `lib/text/` Text API

## Overview

Create a dedicated `lib/text/` module with comprehensive text capabilities following Love2D patterns and Clojure idioms. Delete the old `lib/graphics/text.clj` (no backwards compatibility).

## Module Structure

```
src/lib/text/
├── core.clj       # Basic text drawing, font cache, alignment, variable fonts
├── measure.clj    # Text measurement: width, bounds, font metrics
├── paragraph.clj  # Multi-line: word wrap, rich text, hit testing
└── path.clj       # Text on path using RSXform
```

## Files to Modify/Create

### 1. `src/lib/text/core.clj` (NEW)

**Purpose:** Basic text drawing with font styles, alignment, and variable fonts

**Key Functions:**

```clojure
;; Font management
(defn default-typeface [])                    ; system default
(defn get-typeface [family weight slant])     ; cached by [family weight slant]
(defn get-font [typeface size])               ; cached by [typeface size]
(defn clear-font-cache! [])

;; Variable font support
(defn load-typeface [path])                   ; load from file
(defn variation-axes [typeface])              ; query available axes
;; => [{:tag "wght" :min 100 :default 400 :max 900} ...]

(defn vary [typeface variations])             ; create variant
;; (vary my-font {:wght 700 :wdth 85})

;; Main drawing function
(defn text [canvas text x y]
  [canvas text x y opts])
```

**Font Options:**

- `:size` - font size in points (default: 14)
- `:weight` - `:normal`, `:bold`, or 100-900 (default: `:normal`)
- `:slant` - `:upright`, `:italic`, `:oblique` (default: `:upright`)
- `:family` - font family name (default: system)
- `:align` - `:left`, `:center`, `:right` (default: `:left`)
- `:variations` - variable font axes `{:wght 700 :wdth 85}` (optional)

**Paint Options:** Same as shapes (`:color`, `:shadow`, `:blur`, `:gradient`, etc.)

**Skija Classes:**

- `io.github.humbleui.skija.FontMgr`
- `io.github.humbleui.skija.FontStyle` (with `FontWeight`, `FontSlant`)
- `io.github.humbleui.skija.Typeface`
- `io.github.humbleui.skija.Font`
- `io.github.humbleui.skija.FontVariation`
- `io.github.humbleui.skija.FontVariationAxis`

**Variable Font Example:**

```clojure
;; Load variable font
(def inter-v (load-typeface "fonts/Inter-V.ttf"))

;; Query axes - returns all available axes with ranges
(variation-axes inter-v)
;; => [{:tag "wght" :min 100 :default 400 :max 900}
;;     {:tag "slnt" :min -10 :default 0 :max 0}]

;; Create variant
(def inter-bold (vary inter-v {:wght 700}))

;; Or inline with text
(text canvas "Hello" 100 100 {:size 24 :variations {:wght 600 :wdth 85}})
```

**Animating Variable Axes (SMOOTH, not stepped):**

```clojure
;; Variable font axes are continuous floats - perfect for animation!
(def weight-spring (spring/spring {:from 200 :to 800}))

;; In draw function:
(let [{:keys [value]} (spring/spring-now weight-spring)]
  ;; 'value' smoothly interpolates: 200 → 345.7 → 567.2 → 734.1 → 800
  (text/text canvas "Smooth Animation!" x y
    {:size 48 :variations {:wght value}}))

;; Animate ANY axis the font supports:
;; - wght (weight): 100-900
;; - wdth (width): 50-200
;; - opsz (optical size): 8-144
;; - slnt (slant): -12 to 0
;; - Custom axes: GRAD, CASL, MONO, etc.
```

**Why it's smooth:** Variable fonts contain interpolation data between "master" designs. Skia rasterizes glyphs at the exact requested float value - no discrete steps.

---

### 2. `src/lib/text/measure.clj` (NEW)

**Purpose:** Text measurement utilities

**Key Functions:**

```clojure
(defn text-width [text]
  [text opts])
;; Returns: float

(defn text-bounds [text]
  [text opts])
;; Returns: {:width :height :left :top}

(defn font-metrics []
  [opts])
;; Returns: {:ascent :descent :height :leading :cap-height :x-height
;;           :underline-pos :underline-thickness :strikeout-pos :strikeout-thickness}

(defn line-height []
  [opts])
;; Returns: float (shorthand for (:height (font-metrics opts)))
```

**Skija Classes:**

- `io.github.humbleui.skija.Font` - `.measureText()`, `.measureTextWidth()`, `.getMetrics()`
- `io.github.humbleui.skija.FontMetrics` - all metric fields

---

### 3. `src/lib/text/paragraph.clj` (NEW)

**Purpose:** Multi-line text with word wrap and rich styling

**Key Functions:**

```clojure
;; Creation
(defn paragraph [text opts])
;; opts: :width (required), :size, :weight, :color, :align, :max-lines, :ellipsis, :line-height

(defn rich-text [opts spans])
;; spans: [{:text "Hello " :size 24} {:text "World" :weight :bold}]

;; Drawing
(defn draw [canvas paragraph x y])

;; Measurement
(defn height [paragraph])
(defn longest-line [paragraph])
(defn line-count [paragraph])

;; Hit Testing (for interactive text)
(defn index-at-point [paragraph x y])
;; Returns: {:index n :affinity :upstream|:downstream}

(defn position-at-index [paragraph index])
;; Returns: float (x coordinate)

(defn rects-for-range [paragraph start end])
;; Returns: [{:x :y :width :height :direction} ...]

(defn word-boundary [paragraph index])
;; Returns: {:start :end}
```

**Skija Classes:**

- `io.github.humbleui.skija.paragraph.Paragraph`
- `io.github.humbleui.skija.paragraph.ParagraphBuilder`
- `io.github.humbleui.skija.paragraph.ParagraphStyle`
- `io.github.humbleui.skija.paragraph.TextStyle`
- `io.github.humbleui.skija.paragraph.FontCollection`
- `io.github.humbleui.skija.paragraph.DecorationStyle` (for underline/strikethrough)

**FontCollection Setup:**

```clojure
(defonce ^:private font-collection
  (doto (FontCollection.)
    (.setDefaultFontManager (FontMgr/getDefault))))
```

---

### 4. `src/lib/text/path.clj` (NEW)

**Purpose:** Text rendered along a path using RSXform (GPU-optimized)

**Key Functions:**

```clojure
(defn text-on-path [canvas text path]
  [canvas text path opts])
;; opts: :size, :weight, :color, :offset (along path), :spacing

;; Path helpers
(defn circle-path [cx cy radius])
(defn arc-path [cx cy radius start-angle sweep-angle])
(defn wave-path [x y width amplitude frequency])
```

**Implementation Strategy (from React Native Skia):**

1. **Convert text to glyphs:**

   ```clojure
   (let [glyphs (.textToGlyphs font text)]
     ...)
   ```

2. **Get glyph widths:**

   ```clojure
   (let [widths (.getWidths font glyphs)]
     ...)
   ```

3. **Create ContourMeasure for path:**

   ```clojure
   (let [iter (ContourMeasureIter. path false 1.0)]
     (loop [contour (.next iter) ...]
       ...))
   ```

4. **Position each glyph along path:**

   ```clojure
   ;; For each glyph:
   ;; - Get position and tangent at distance
   ;; - Create RSXform with tangent (tx, ty) and adjusted position
   (let [[px py] (get-pos-tan contour distance)
         [tx ty] tangent
         adjusted-x (- px (* (/ width 2) tx))
         adjusted-y (- py (* (/ width 2) ty))]
     (RSXform/make tx ty adjusted-x adjusted-y))
   ```

5. **Create TextBlob with RSXforms:**

   ```clojure
   (TextBlob/makeFromRSXform glyphs rsxforms font)
   ```

6. **Draw single TextBlob:**
   ```clojure
   (.drawTextBlob canvas blob 0 0 paint)
   ```

**Skija Classes:**

- `io.github.humbleui.skija.Font` - `.textToGlyphs()`, `.getWidths()`
- `io.github.humbleui.skija.TextBlob` - `.makeFromRSXform()`
- `io.github.humbleui.skija.RSXform` - rotation + scale + translation
- `io.github.humbleui.skija.PathMeasure` or `ContourMeasureIter`

**What is RSXform:**
RSXform (Rotated Scale Xform) is a compact 2D transform encoding:

- `tx, ty` = tangent vector (cos/sin of rotation angle)
- `px, py` = adjusted position (glyph center on path)

```clojure
;; For each glyph at distance d along path:
(let [[px py] (get-position contour d)    ; point on path
      [tx ty] (get-tangent contour d)     ; tangent = rotation
      half-w (/ glyph-width 2)
      ;; Adjust position so glyph center sits on path
      adj-x (- px (* half-w tx))
      adj-y (- py (* half-w ty))]
  (RSXform/make tx ty adj-x adj-y))
```

**Why RSXform:**

- Single draw call for entire text (GPU-optimized)
- Each glyph gets proper rotation from path tangent
- Handled natively by Skia's rendering pipeline
- Much faster than N separate canvas transforms

---

### 5. `src/lib/graphics/text.clj` (DELETE)

**Action:** Delete this file entirely. No backwards compatibility.

Update any files that import `lib.graphics.text`:

- `src/app/shell/debug_panel.clj` → use `lib.text.core`
- `src/app/ui/slider.clj` → use `lib.text.core`
- Any other imports found via grep

---

## Implementation Order

1. **`lib/text/core.clj`** - Base module with font cache and basic text
   - Font cache (typeface + font)
   - Weight/slant support via FontStyle
   - Alignment support
   - Variable font support (load, query axes, vary)

2. **`lib/text/measure.clj`** - Measurement utilities
   - Depends on core.clj for fonts
   - Add `text-width`, `text-bounds`, `font-metrics`, `line-height`

3. **`lib/text/paragraph.clj`** - Multi-line text
   - Depends on core.clj for font utilities
   - Wrap Skija Paragraph API
   - Add rich-text builder
   - Hit testing for interactive text

4. **`lib/text/path.clj`** - Text on path
   - Depends on core.clj for fonts
   - RSXform-based glyph positioning
   - Path helpers (circle, arc, wave)

5. **Delete `lib/graphics/text.clj`** - No backwards compat
   - Remove file
   - Update all imports in codebase

---

## API Design Patterns

### Consistent Options Map

```clojure
;; Font options (shared across all functions)
{:size 24           ; points
 :weight :bold      ; or 100-900
 :slant :italic     ; :upright, :italic, :oblique
 :family "Roboto"   ; font family name
 :variations {:wght 600 :wdth 85}}  ; variable font axes

;; Text options
{:align :center     ; :left, :center, :right
 :underline true    ; or {:style :wavy :color 0xFF...}
 :strikethrough true
 :letter-spacing 2.0
 :line-height 1.5}  ; multiplier (paragraph only)

;; Paint options (same as shapes)
{:color 0xFFFFFFFF
 :shadow {:dx 2 :dy 2 :blur 3 :color 0x80000000}
 :blur 5.0
 :gradient {...}
 :alphaf 0.8}
```

### Weight/Slant Mapping

```clojure
(def weights
  {:thin 100 :extra-light 200 :light 300 :normal 400
   :medium 500 :semi-bold 600 :bold 700 :extra-bold 800 :black 900})

(def slants
  {:upright FontSlant/UPRIGHT
   :italic FontSlant/ITALIC
   :oblique FontSlant/OBLIQUE})
```

### Variable Font Helpers

```clojure
(defn- make-variations [variations-map]
  "Convert {:wght 700 :wdth 85} to FontVariation array"
  (into-array FontVariation
    (map (fn [[k v]] (FontVariation. (name k) (float v)))
         variations-map)))

(defn vary [typeface variations-map]
  "Create variant of typeface with given variations"
  (.makeClone typeface (make-variations variations-map)))
```

### Caching Strategy

```clojure
;; Typeface cache: [family weight slant] -> Typeface
(defonce ^:private typeface-cache (atom {}))

;; Varied typeface cache: [base-typeface variations-map] -> Typeface
(defonce ^:private varied-typeface-cache (atom {}))

;; Font cache: [typeface size] -> Font
(defonce ^:private font-cache (atom {}))

;; FontCollection: singleton for paragraph system
(defonce ^:private font-collection ...)
```

---

## Skija Imports

```clojure
;; core.clj
(:import [io.github.humbleui.skija
          Canvas Paint Font FontMgr FontStyle FontSlant FontWeight Typeface
          FontVariation FontVariationAxis])

;; measure.clj
(:import [io.github.humbleui.skija Font FontMetrics]
         [io.github.humbleui.types Rect])

;; paragraph.clj
(:import [io.github.humbleui.skija.paragraph
          Paragraph ParagraphBuilder ParagraphStyle TextStyle
          FontCollection Alignment Direction DecorationStyle
          DecorationLineStyle BaselineMode HeightMode])

;; path.clj
(:import [io.github.humbleui.skija
          Canvas Paint Font TextBlob RSXform PathMeasure])
```

---

## Verification

### Test Basic Text

```clojure
(require '[lib.text.core :as text])
(text/text canvas "Hello" 100 100)
(text/text canvas "Bold" 100 130 {:size 24 :weight :bold})
(text/text canvas "Centered" 200 160 {:size 18 :align :center})
```

### Test Variable Fonts

```clojure
(require '[lib.text.core :as text])

;; Load variable font
(def inter-v (text/load-typeface "path/to/Inter-V.ttf"))

;; Query axes
(text/variation-axes inter-v)
;; => [{:tag "wght" :min 100 :default 400 :max 900} ...]

;; Create variant
(def inter-bold (text/vary inter-v {:wght 700}))

;; Use inline
(text/text canvas "Variable!" 100 100 {:size 24 :variations {:wght 600}})
```

### Test Variable Font Animation (smooth interpolation)

```clojure
(require '[lib.text.core :as text]
         '[lib.anim.spring :as spring])

;; Create spring for weight animation
(defonce weight-anim (atom (spring/spring {:from 200 :to 800})))

;; In tick - spring updates automatically via time

;; In draw
(let [{:keys [value]} (spring/spring-now @weight-anim)]
  ;; 'value' is continuous float: 200.0, 345.7, 567.2, 734.1, 800.0
  (text/text canvas "Smooth!" 100 100 {:size 48 :variations {:wght value}}))

;; Verify: text weight should smoothly animate, no jumps
```

### Test Measurement

```clojure
(require '[lib.text.measure :as measure])
(measure/text-width "Hello" {:size 24})  ; => 65.0
(measure/font-metrics {:size 24})        ; => {:ascent -22 :descent 6 ...}
```

### Test Paragraph

```clojure
(require '[lib.text.paragraph :as para])
(def p (para/paragraph "Long text..." {:width 300 :align :center}))
(para/draw canvas p 50 50)
(para/height p)  ; => 84.0
```

### Test Rich Text

```clojure
(def rich (para/rich-text {:width 300}
            [{:text "Hello " :size 24}
             {:text "World" :size 24 :weight :bold :color 0xFF0000FF}]))
(para/draw canvas rich 50 150)
```

### Test Text on Path

```clojure
(require '[lib.text.path :as text-path])
(def circle (text-path/circle-path 200 200 80))
(text-path/text-on-path canvas "Around the circle!" circle {:size 18})
```

### Run Example

```bash
clj -A:dev:macos-arm64 -e "(open :playground/ball-spring)"
# Then in connected REPL, test text APIs
```

### Update Existing Imports

After implementation, update files that use `lib.graphics.text`:

```bash
# Find files to update
grep -r "lib.graphics.text" src/
# Update imports to use lib.text.core
```

---

## Notes

- **No hot-reload:** All `lib.*` namespaces require restart (per clj-reload pattern)
- **No backwards compat:** Delete `lib.graphics.text`, update all imports
- **FontCollection singleton:** Paragraph system needs shared FontCollection
- **Text-on-path uses RSXform:** GPU-optimized single draw call via `TextBlob.makeFromRSXform`
- **Variable fonts are continuous:** Axes accept any float value in range, enabling smooth animation
- **Animation caching strategy:** For animated variable fonts, consider LRU cache for recent typeface variants to avoid recreating every frame (Skia's glyph rasterization is fast, but caching helps)

---

## Sources

### Local References (explored during research)

| Source                  | Path                                                | Used For                                                                          |
| ----------------------- | --------------------------------------------------- | --------------------------------------------------------------------------------- |
| **Skija Java Bindings** | `/Users/as186073/Downloads/Skija-master/`           | Font, FontMetrics, TextBlob, Paragraph, TextStyle, Shaper APIs                    |
| **React Native Skia**   | `/Users/as186073/Downloads/react-native-skia-main/` | Text-on-path RSXform implementation (`packages/skia/cpp/api/recorder/Drawings.h`) |
| **Existing text API**   | `src/lib/graphics/text.clj`                         | Current implementation to migrate                                                 |
| **Paint system**        | `src/lib/graphics/state.clj`                        | Options map pattern, `with-paint` macro                                           |
| **Gesture API**         | `src/lib/gesture/api.clj`                           | Module organization patterns                                                      |
| **Anim modules**        | `src/lib/anim/*.clj`                                | Folder structure patterns                                                         |

### Web References

| Source                            | URL                                                             | Used For                                              |
| --------------------------------- | --------------------------------------------------------------- | ----------------------------------------------------- |
| **React Native Skia - Paragraph** | https://shopify.github.io/react-native-skia/docs/text/paragraph | ParagraphBuilder pattern, TextStyle props, layout API |
| **React Native Skia - Text**      | https://shopify.github.io/react-native-skia/docs/text/text      | Basic text rendering, font props                      |
| **React Native Skia - Text Path** | https://shopify.github.io/react-native-skia/docs/text/path      | Text-on-path component API                            |

### Key Skija Files Explored

```
Skija-master/shared/java/
├── Font.java                    # Font class, metrics, glyph methods
├── FontMgr.java                 # Font manager, typeface matching
├── FontMetrics.java             # Ascent, descent, height, etc.
├── FontStyle.java               # Weight, width, slant
├── FontVariation.java           # Variable font axis values
├── FontVariationAxis.java       # Axis metadata (min, max, default)
├── Typeface.java                # Typeface loading, variation support
├── TextBlob.java                # TextBlob creation, makeFromRSXform
├── TextBlobBuilder.java         # Builder for multi-run blobs
├── RSXform.java                 # Rotation + scale + translation
├── PathMeasure.java             # Path measurement for text-on-path
├── shaper/
│   ├── Shaper.java              # HarfBuzz text shaping
│   └── TextLine.java            # Single line shaped text
└── paragraph/
    ├── Paragraph.java           # Multi-line layout, hit testing
    ├── ParagraphBuilder.java    # Builder with pushStyle/addText
    ├── ParagraphStyle.java      # Alignment, max lines, ellipsis
    ├── TextStyle.java           # Color, font, decorations, spacing
    ├── FontCollection.java      # Font collection for paragraph
    └── DecorationStyle.java     # Underline, strikethrough styles
```

### React Native Skia Files Explored (text-on-path)

```
react-native-skia-main/packages/skia/
├── cpp/api/recorder/Drawings.h    # TextPathCmd implementation (lines 213-310)
├── src/renderer/components/text/TextPath.tsx  # React component
└── src/dom/types/Drawings.ts      # TypeScript types (TextPathProps)
```

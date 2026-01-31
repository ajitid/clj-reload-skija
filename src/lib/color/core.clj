(ns lib.color.core
  "Color utilities returning [r g b a] float arrays (0.0-1.0).

   All color functions return a 4-element vector [r g b a] with floats
   in the 0.0-1.0 range, suitable for use with Skija's Color4f.

   Provides OKLCH, HSL, and other perceptual color spaces."
  (:require [clojure2d.color :as c]))

;; ============================================================
;; Common Color Constants
;; ============================================================

(def white [1.0 1.0 1.0 1.0])
(def black [0.0 0.0 0.0 1.0])
(def transparent [0.0 0.0 0.0 0.0])
(def red [1.0 0.0 0.0 1.0])
(def green [0.0 1.0 0.0 1.0])
(def blue [0.0 0.0 1.0 1.0])

;; ============================================================
;; Internal Conversion Helpers (clojure2d interop)
;; ============================================================

(defn- color4f->c2d
  "Convert [r g b a] floats to clojure2d Vec4 (0-255 range)"
  [[r g b a]]
  (c/color (* r 255) (* g 255) (* b 255) (* (or a 1.0) 255)))

(defn- c2d->color4f
  "Convert clojure2d color to [r g b a] floats"
  [color]
  [(/ (c/red color) 255.0)
   (/ (c/green color) 255.0)
   (/ (c/blue color) 255.0)
   (/ (c/alpha color) 255.0)])

;; ============================================================
;; Conversion Helpers (public)
;; ============================================================

(defn hex->color4f
  "Convert hex int (0xAARRGGBB) to [r g b a] floats."
  [hex]
  (let [hex-int (unchecked-int hex)]
    [(/ (bit-and (bit-shift-right hex-int 16) 0xFF) 255.0)
     (/ (bit-and (bit-shift-right hex-int 8) 0xFF) 255.0)
     (/ (bit-and hex-int 0xFF) 255.0)
     (/ (bit-and (bit-shift-right hex-int 24) 0xFF) 255.0)]))

(defn color4f->hex
  "Convert [r g b a] floats to hex int (0xAARRGGBB)."
  [[r g b a]]
  (unchecked-int
   (bit-or (bit-shift-left (int (* (or a 1.0) 255)) 24)
           (bit-shift-left (int (* r 255)) 16)
           (bit-shift-left (int (* g 255)) 8)
           (int (* b 255)))))

;; ============================================================
;; Color Space Conversions - All return [r g b a] floats
;; ============================================================

(defn oklch
  "Create color from OKLCH values, returns [r g b a] floats.
   L: lightness 0-1, C: chroma 0-0.4+, H: hue 0-360"
  [l ch h]
  (let [color (c/from-Oklch [l ch h])]
    [(/ (c/red color) 255.0)
     (/ (c/green color) 255.0)
     (/ (c/blue color) 255.0)
     1.0]))

(defn oklch-alpha
  "Create color from OKLCH + alpha, returns [r g b a] floats.
   L: lightness 0-1, C: chroma 0-0.4+, H: hue 0-360, A: alpha 0-1"
  [l ch h a]
  (let [color (c/from-Oklch [l ch h])]
    [(/ (c/red color) 255.0)
     (/ (c/green color) 255.0)
     (/ (c/blue color) 255.0)
     (float a)]))

(defn hsl
  "Create color from HSL values, returns [r g b a] floats.
   H: hue 0-360, S: saturation 0-1, L: lightness 0-1"
  [h s l]
  (let [color (c/from-HSL [h s l])]
    [(/ (c/red color) 255.0)
     (/ (c/green color) 255.0)
     (/ (c/blue color) 255.0)
     1.0]))

(defn hsl-alpha
  "Create color from HSL + alpha, returns [r g b a] floats."
  [h s l a]
  (let [color (c/from-HSL [h s l])]
    [(/ (c/red color) 255.0)
     (/ (c/green color) 255.0)
     (/ (c/blue color) 255.0)
     (float a)]))

(defn rgba
  "Create color from RGBA values (all 0.0-1.0), returns [r g b a] floats."
  ([r g b] [r g b 1.0])
  ([r g b a] [r g b a]))

(defn rgb
  "Create color from RGB values (all 0.0-1.0), returns [r g b 1.0] floats."
  [r g b]
  [r g b 1.0])

(defn hex
  "Create color from hex string, returns [r g b a] floats.
   Supports formats: #RGB, #RGBA, #RRGGBB, #RRGGBBAA"
  [s]
  (let [color (c/color s)]
    [(/ (c/red color) 255.0)
     (/ (c/green color) 255.0)
     (/ (c/blue color) 255.0)
     (/ (c/alpha color) 255.0)]))

(defn named
  "Create color from keyword name, returns [r g b a] floats.
   e.g. (named :red), (named :cornflowerblue)"
  [kw]
  (let [color (c/color kw)]
    [(/ (c/red color) 255.0)
     (/ (c/green color) 255.0)
     (/ (c/blue color) 255.0)
     1.0]))

(defn gray
  "Create grayscale color, returns [r g b a] floats.
   v: value 0.0-1.0"
  ([v] [v v v 1.0])
  ([v a] [v v v a]))

;; ============================================================
;; Color Manipulation - All return [r g b a] floats
;; ============================================================

(defn with-alpha
  "Set the alpha of a color, returns [r g b a] floats."
  [[r g b _] a]
  [r g b a])

(defn mix
  "Mix two colors. Uses clojure2d's perceptual mixing."
  ([c1 c2] (mix c1 c2 0.5))
  ([c1 c2 t]
   (c2d->color4f (c/mix (color4f->c2d c1) (color4f->c2d c2) t))))

(defn lerp
  "Linear interpolation between two colors using clojure2d."
  ([c1 c2] (lerp c1 c2 0.5))
  ([c1 c2 t]
   (c2d->color4f (c/lerp (color4f->c2d c1) (color4f->c2d c2) t))))

(defn brighten
  "Brighten color in LAB colorspace (perceptually uniform)."
  [color amount]
  (let [[_ _ _ a] color
        result (c/brighten (color4f->c2d color) amount)]
    (assoc (c2d->color4f result) 3 a)))

(defn darken
  "Darken color in LAB colorspace (perceptually uniform)."
  [color amount]
  (let [[_ _ _ a] color
        result (c/darken (color4f->c2d color) amount)]
    (assoc (c2d->color4f result) 3 a)))

(defn saturate
  "Increase saturation in LCH colorspace."
  [color amount]
  (let [[_ _ _ a] color
        result (c/saturate (color4f->c2d color) amount)]
    (assoc (c2d->color4f result) 3 a)))

(defn desaturate
  "Decrease saturation in LCH colorspace."
  [color amount]
  (let [[_ _ _ a] color
        result (c/desaturate (color4f->c2d color) amount)]
    (assoc (c2d->color4f result) 3 a)))

(defn complementary
  "Get complementary color (opposite hue)."
  [color]
  (let [[_ _ _ a] color
        result (c/complementary (color4f->c2d color))]
    (assoc (c2d->color4f result) 3 a)))

(defn clamp-color
  "Clamp color values to valid 0-1 range."
  [[r g b a]]
  [(max 0.0 (min 1.0 (double r)))
   (max 0.0 (min 1.0 (double g)))
   (max 0.0 (min 1.0 (double b)))
   (max 0.0 (min 1.0 (double (or a 1.0))))])

;; ============================================================
;; Color Analysis
;; ============================================================

(defn luminance
  "Get perceptual luminance (0.0-1.0)."
  [color]
  (/ (c/luma (color4f->c2d color)) 255.0))

(defn contrast-ratio
  "WCAG contrast ratio between two colors (1.0-21.0)."
  [c1 c2]
  (c/contrast-ratio (color4f->c2d c1) (color4f->c2d c2)))

(defn hue
  "Extract hue (0-360 degrees)."
  [color]
  (c/hue (color4f->c2d color)))

(defn get-saturation
  "Extract saturation (0.0-1.0)."
  [color]
  (/ (c/saturation (color4f->c2d color)) 255.0))

;; ============================================================
;; Color Generation
;; ============================================================

(defn temperature
  "Create color from black body temperature in Kelvin (1000-40000K)."
  [kelvin]
  (c2d->color4f (c/temperature kelvin)))

(defn gradient
  "Sample a color from a multi-stop gradient.
   colors: vector of colors [[r g b a] ...]
   t: position along gradient (0.0-1.0)"
  [colors t]
  (let [c2d-colors (mapv color4f->c2d colors)
        grad-fn (c/gradient c2d-colors)]
    (c2d->color4f (grad-fn t))))

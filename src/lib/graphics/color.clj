(ns lib.graphics.color
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
;; Conversion Helpers
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

(defn mix-colors
  "Mix two colors, returns [r g b a] floats.
   t: interpolation factor 0-1 (0 = c1, 1 = c2)"
  ([[r1 g1 b1 a1] [r2 g2 b2 a2]]
   (mix-colors [r1 g1 b1 a1] [r2 g2 b2 a2] 0.5))
  ([[r1 g1 b1 a1] [r2 g2 b2 a2] t]
   [(+ r1 (* t (- r2 r1)))
    (+ g1 (* t (- g2 g1)))
    (+ b1 (* t (- b2 b1)))
    (+ a1 (* t (- a2 a1)))]))

(defn lerp-color
  "Linear interpolation between two colors, returns [r g b a] floats."
  [[r1 g1 b1 a1] [r2 g2 b2 a2] t]
  [(+ r1 (* t (- r2 r1)))
   (+ g1 (* t (- g2 g1)))
   (+ b1 (* t (- b2 b1)))
   (+ a1 (* t (- a2 a1)))])

(defn brighten
  "Brighten a color by factor (0-1), returns [r g b a] floats."
  [[r g b a] factor]
  [(min 1.0 (+ r (* factor (- 1.0 r))))
   (min 1.0 (+ g (* factor (- 1.0 g))))
   (min 1.0 (+ b (* factor (- 1.0 b))))
   a])

(defn darken
  "Darken a color by factor (0-1), returns [r g b a] floats."
  [[r g b a] factor]
  [(* r (- 1.0 factor))
   (* g (- 1.0 factor))
   (* b (- 1.0 factor))
   a])

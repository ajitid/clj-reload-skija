(ns lib.graphics.color
  "Color utilities wrapping clojure2d.color for Skija.
   Provides OKLCH and other perceptual color spaces with packed ARGB output."
  (:require [clojure2d.color :as c]))

;; Re-export commonly used functions
(def color c/color)
(def pack c/pack)
(def mix c/mix)
(def lerp c/lerp)
(def darken c/darken)
(def brighten c/brighten)
(def saturate c/saturate)
(def desaturate c/desaturate)

;; OKLCH helpers - returns packed ARGB int for Skija
(defn oklch
  "Create color from OKLCH values, returns packed ARGB int.
   L: lightness 0-1, C: chroma 0-0.4+, H: hue 0-360"
  [l c h]
  (c/pack (c/from-Oklch [l c h])))

(defn oklch-alpha
  "Create color from OKLCH + alpha, returns packed ARGB int.
   L: lightness 0-1, C: chroma 0-0.4+, H: hue 0-360, A: alpha 0-255"
  [l c h a]
  (c/pack (c/set-alpha (c/from-Oklch [l c h]) a)))

;; HSL helper - often more intuitive than raw RGB
(defn hsl
  "Create color from HSL values, returns packed ARGB int.
   H: hue 0-360, S: saturation 0-1, L: lightness 0-1"
  [h s l]
  (c/pack (c/from-HSL [h s l])))

(defn hsl-alpha
  "Create color from HSL + alpha, returns packed ARGB int."
  [h s l a]
  (c/pack (c/set-alpha (c/from-HSL [h s l]) a)))

;; Hex helper
(defn hex
  "Create color from hex string, returns packed ARGB int."
  [s]
  (c/pack (c/color s)))

;; Named color helper
(defn named
  "Create color from keyword name, returns packed ARGB int.
   e.g. (named :red), (named :cornflowerblue)"
  [kw]
  (c/pack (c/color kw)))

;; Grayscale helper
(defn gray
  "Create grayscale color, returns packed ARGB int.
   v: value 0-255"
  ([v] (c/pack (c/gray v)))
  ([v a] (c/pack (c/set-alpha (c/gray v) a))))

;; Color manipulation returning packed ARGB
(defn mix-pack
  "Mix two colors and return packed ARGB int."
  ([c1 c2] (c/pack (c/mix c1 c2)))
  ([c1 c2 t] (c/pack (c/mix c1 c2 t))))

(defn lerp-pack
  "Interpolate two colors and return packed ARGB int."
  [c1 c2 t]
  (c/pack (c/lerp c1 c2 t)))

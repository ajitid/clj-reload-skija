(ns lib.color.core
  "Color utilities returning [r g b a] float arrays (0.0-1.0).

   All color functions return a 4-element vector [r g b a] with floats
   in the 0.0-1.0 range, suitable for use with Skija's Color4f.

   Provides OKLCH, HSL, and other perceptual color spaces."
  (:require [fastmath.matrix :as mat]
            [fastmath.vector :as v]))

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
;; Internal Conversion Helpers (Oklab via fastmath matrices)
;; ============================================================

(defn- clamp01 ^double [^double v]
  (max 0.0 (min 1.0 v)))

(defn- srgb->linear ^double [^double v]
  (if (<= v 0.04045)
    (/ v 12.92)
    (Math/pow (/ (+ v 0.055) 1.055) 2.4)))

(defn- linear->srgb ^double [^double v]
  (if (<= v 0.0031308)
    (* v 12.92)
    (- (* 1.055 (Math/pow v (/ 1.0 2.4))) 0.055)))

;; Oklab conversion matrices (Björn Ottosson)
(def ^:private ^:const m1-linear->lms
  (mat/mat3x3 0.4122214708  0.5363325363  0.0514459929
              0.2119034982  0.6806995451  0.1073969566
              0.0883024619  0.2817188376  0.6299787005))

(def ^:private ^:const m2-lms->oklab
  (mat/mat3x3 0.2104542553  0.7936177850 -0.0040720468
              1.9779984951 -2.4285922050  0.4505937099
              0.0259040371  0.7827717662 -0.8086757660))

(def ^:private ^:const m2-inv-oklab->lms
  (mat/mat3x3 1.0000000000  0.3963377774  0.2158037573
              1.0000000000 -0.1055613458 -0.0638541728
              1.0000000000 -0.0894841775 -1.2914855480))

(def ^:private ^:const m1-inv-lms->linear
  (mat/mat3x3  4.0767416621 -3.3077115913  0.2309699292
              -1.2684380046  2.6097574011 -0.3413193965
              -0.0041960863 -0.7034186147  1.7076147010))

(defn- color4f->oklab
  "Convert [r g b a] to [L a b] via sRGB linearize -> Oklab matrix math."
  [[r g b _]]
  (let [linear (v/fmap (v/vec3 r g b) srgb->linear)
        lms    (v/fmap (mat/mulv m1-linear->lms linear) #(Math/cbrt %))]
    (mat/mulv m2-lms->oklab lms)))

(defn- oklab->color4f
  "Convert [L a b] to [r g b 1.0] via inverse Oklab matrices + gamma."
  [lab]
  (let [lms    (v/fmap (mat/mulv m2-inv-oklab->lms lab) #(* % % %))
        [lr lg lb] (mat/mulv m1-inv-lms->linear lms)]
    [(clamp01 (linear->srgb lr))
     (clamp01 (linear->srgb lg))
     (clamp01 (linear->srgb lb))
     1.0]))

(defn- color4f->oklch
  "Convert [r g b a] to [L C H]."
  [color]
  (let [[L a b] (color4f->oklab color)]
    [L
     (Math/sqrt (+ (* a a) (* b b)))
     (let [h (Math/toDegrees (Math/atan2 b a))]
       (if (neg? h) (+ h 360.0) h))]))

(defn- oklch->color4f
  "Convert [L C H] to [r g b 1.0]."
  [[L C H]]
  (let [h-rad (Math/toRadians (double H))]
    (oklab->color4f (v/vec3 L (* (double C) (Math/cos h-rad))
                              (* (double C) (Math/sin h-rad))))))

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
  (oklch->color4f [(double l) (double ch) (double h)]))

(defn oklch-alpha
  "Create color from OKLCH + alpha, returns [r g b a] floats.
   L: lightness 0-1, C: chroma 0-0.4+, H: hue 0-360, A: alpha 0-1"
  [l ch h a]
  (assoc (oklch->color4f [(double l) (double ch) (double h)]) 3 (double a)))

(defn- hsl->rgb
  "Convert HSL to RGB. H: 0-360, S: 0-1, L: 0-1 -> [r g b] 0-1"
  [h s l]
  (let [h (double h) s (double s) l (double l)
        c (* s (- 1.0 (Math/abs (- (* 2.0 l) 1.0))))
        h' (/ (mod h 360.0) 60.0)
        x (* c (- 1.0 (Math/abs (- (mod h' 2.0) 1.0))))
        [r1 g1 b1] (cond
                      (< h' 1.0) [c x 0.0]
                      (< h' 2.0) [x c 0.0]
                      (< h' 3.0) [0.0 c x]
                      (< h' 4.0) [0.0 x c]
                      (< h' 5.0) [x 0.0 c]
                      :else      [c 0.0 x])
        m (- l (* 0.5 c))]
    [(+ r1 m) (+ g1 m) (+ b1 m)]))

(defn hsl
  "Create color from HSL values, returns [r g b a] floats.
   H: hue 0-360, S: saturation 0-1, L: lightness 0-1"
  [h s l]
  (let [[r g b] (hsl->rgb h s l)]
    [r g b 1.0]))

(defn hsl-alpha
  "Create color from HSL + alpha, returns [r g b a] floats."
  [h s l a]
  (let [[r g b] (hsl->rgb h s l)]
    [r g b (double a)]))

(defn rgba
  "Create color from RGBA values (all 0.0-1.0), returns [r g b a] floats."
  ([r g b] [r g b 1.0])
  ([r g b a] [r g b a]))

(defn rgb
  "Create color from RGB values (all 0.0-1.0), returns [r g b 1.0] floats."
  [r g b]
  [r g b 1.0])

(defn- parse-hex-string
  "Parse hex color string. Supports #RGB, #RGBA, #RRGGBB, #RRGGBBAA."
  [s]
  (let [s (if (.startsWith ^String s "#") (subs s 1) s)
        n (count s)]
    (case n
      3 (let [r (Integer/parseInt (subs s 0 1) 16)
              g (Integer/parseInt (subs s 1 2) 16)
              b (Integer/parseInt (subs s 2 3) 16)]
          [(/ (* r 17) 255.0) (/ (* g 17) 255.0) (/ (* b 17) 255.0) 1.0])
      4 (let [r (Integer/parseInt (subs s 0 1) 16)
              g (Integer/parseInt (subs s 1 2) 16)
              b (Integer/parseInt (subs s 2 3) 16)
              a (Integer/parseInt (subs s 3 4) 16)]
          [(/ (* r 17) 255.0) (/ (* g 17) 255.0) (/ (* b 17) 255.0) (/ (* a 17) 255.0)])
      6 (let [r (Integer/parseInt (subs s 0 2) 16)
              g (Integer/parseInt (subs s 2 4) 16)
              b (Integer/parseInt (subs s 4 6) 16)]
          [(/ r 255.0) (/ g 255.0) (/ b 255.0) 1.0])
      8 (let [r (Integer/parseInt (subs s 0 2) 16)
              g (Integer/parseInt (subs s 2 4) 16)
              b (Integer/parseInt (subs s 4 6) 16)
              a (Integer/parseInt (subs s 6 8) 16)]
          [(/ r 255.0) (/ g 255.0) (/ b 255.0) (/ a 255.0)]))))

(defn hex
  "Create color from hex string, returns [r g b a] floats.
   Supports formats: #RGB, #RGBA, #RRGGBB, #RRGGBBAA"
  [s]
  (parse-hex-string s))

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
  "Mix two colors in Oklab perceptual space."
  ([c1 c2] (mix c1 c2 0.5))
  ([c1 c2 t]
   (let [lab1 (color4f->oklab c1)
         lab2 (color4f->oklab c2)]
     (oklab->color4f (v/interpolate lab1 lab2 t)))))

(defn lerp
  "Linear interpolation between two colors in sRGB."
  ([c1 c2] (lerp c1 c2 0.5))
  ([[r1 g1 b1 a1] [r2 g2 b2 a2] t]
   (let [t (double t) inv (- 1.0 t)]
     [(+ (* inv (double r1)) (* t (double r2)))
      (+ (* inv (double g1)) (* t (double g2)))
      (+ (* inv (double b1)) (* t (double b2)))
      (+ (* inv (double (or a1 1.0))) (* t (double (or a2 1.0))))])))

(defn brighten
  "Brighten color in Oklab colorspace (perceptually uniform)."
  [color amount]
  (let [[_ _ _ a] color
        [L la lb] (color4f->oklab color)]
    (assoc (oklab->color4f (v/vec3 (clamp01 (+ L (* (double amount) 0.18))) la lb))
           3 a)))

(defn darken
  "Darken color in Oklab colorspace (perceptually uniform)."
  [color amount]
  (brighten color (- (double amount))))

(defn saturate
  "Increase chroma in Oklch colorspace."
  [color amount]
  (let [[_ _ _ a] color
        [L C H] (color4f->oklch color)]
    (assoc (oklch->color4f [L (max 0.0 (+ C (* (double amount) 0.03))) H])
           3 a)))

(defn desaturate
  "Decrease chroma in Oklch colorspace."
  [color amount]
  (saturate color (- (double amount))))

(defn complementary
  "Get complementary color (opposite hue in Oklch)."
  [color]
  (let [[_ _ _ a] color
        [L C H] (color4f->oklch color)]
    (assoc (oklch->color4f [L C (mod (+ H 180.0) 360.0)])
           3 a)))

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
  "Get relative luminance (0.0-1.0) per WCAG definition."
  [[r g b _]]
  (+ (* 0.2126 (srgb->linear (double r)))
     (* 0.7152 (srgb->linear (double g)))
     (* 0.0722 (srgb->linear (double b)))))

(defn contrast-ratio
  "WCAG contrast ratio between two colors (1.0-21.0)."
  [c1 c2]
  (let [l1 (luminance c1)
        l2 (luminance c2)
        lighter (max l1 l2)
        darker (min l1 l2)]
    (/ (+ lighter 0.05) (+ darker 0.05))))

(defn hue
  "Extract hue (0-360 degrees) via Oklch."
  [color]
  (nth (color4f->oklch color) 2))

(defn get-saturation
  "Extract chroma from Oklch, normalized to roughly 0-1."
  [color]
  (/ (double (nth (color4f->oklch color) 1)) 0.4))

;; ============================================================
;; Color Generation
;; ============================================================

(defn temperature
  "Create color from black body temperature in Kelvin (1000-40000K).
   Uses Tanner Helland algorithm."
  [kelvin]
  (let [t (/ (double kelvin) 100.0)
        r (if (<= t 66.0)
            1.0
            (clamp01 (/ (* 329.698727446 (Math/pow (- t 60.0) -0.1332047592)) 255.0)))
        g (if (<= t 66.0)
            (clamp01 (/ (- (* 99.4708025861 (Math/log t)) 161.1195681661) 255.0))
            (clamp01 (/ (* 288.1221695283 (Math/pow (- t 60.0) -0.0755148492)) 255.0)))
        b (if (>= t 66.0)
            1.0
            (if (<= t 19.0)
              0.0
              (clamp01 (/ (- (* 138.5177312231 (Math/log (- t 10.0))) 305.0447927307) 255.0))))]
    [r g b 1.0]))

(defn gradient
  "Sample a color from a multi-stop gradient (Oklab interpolation).
   colors: vector of colors [[r g b a] ...]
   t: position along gradient (0.0-1.0)"
  [colors t]
  (let [t (max 0.0 (min 1.0 (double t)))
        n (count colors)]
    (cond
      (<= n 0) [0.0 0.0 0.0 1.0]
      (= n 1) (first colors)
      :else
      (let [seg (* t (dec n))
            i (min (int seg) (- n 2))
            local-t (- seg i)]
        (mix (nth colors i) (nth colors (inc i)) local-t)))))

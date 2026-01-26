(ns lib.graphics.filters
  "Common filters and effects - blur, shadows, gradients, etc.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:import [io.github.humbleui.skija ImageFilter MaskFilter ColorFilter ColorMatrix Shader
            FilterTileMode FilterBlurMode PathEffect PathEffect1DStyle Path]))

;; ============================================================
;; Image Filters (blur, shadows, etc.)
;; ============================================================

(defn blur
  "Create a blur image filter.

   Args:
     sigma    - blur radius (single value or [sigmaX sigmaY])
     mode     - tile mode: :clamp, :repeat, :mirror, :decal (default :decal)

   Examples:
     (blur 5.0)              ; 5px blur in both directions
     (blur [5.0 10.0])       ; 5px horizontal, 10px vertical
     (blur 5.0 :clamp)       ; 5px blur with clamped edges"
  ([sigma]
   (blur sigma :decal))
  ([sigma mode]
   (let [[sx sy] (if (vector? sigma) sigma [sigma sigma])
         tile-mode (case mode
                     :clamp FilterTileMode/CLAMP
                     :repeat FilterTileMode/REPEAT
                     :mirror FilterTileMode/MIRROR
                     :decal FilterTileMode/DECAL
                     FilterTileMode/DECAL)]
     (ImageFilter/makeBlur (float sx) (float sy) tile-mode))))

(defn drop-shadow
  "Create a drop shadow image filter.

   Args:
     dx, dy   - shadow offset
     sigma    - blur radius (single value or [sigmaX sigmaY])
     color    - shadow color (32-bit ARGB)

   Example:
     (drop-shadow 2 2 3.0 0x80000000)  ; 2px offset, 3px blur, semi-transparent black"
  ([dx dy sigma color]
   (let [[sx sy] (if (vector? sigma) sigma [sigma sigma])]
     (ImageFilter/makeDropShadow (float dx) (float dy) (float sx) (float sy) (unchecked-int color)))))

(defn drop-shadow-only
  "Create a drop shadow image filter (shadow only, no original content).

   Args:
     dx, dy   - shadow offset
     sigma    - blur radius (single value or [sigmaX sigmaY])
     color    - shadow color (32-bit ARGB)

   Example:
     (drop-shadow-only 2 2 3.0 0xFF000000)  ; Just the shadow"
  ([dx dy sigma color]
   (let [[sx sy] (if (vector? sigma) sigma [sigma sigma])]
     (ImageFilter/makeDropShadowOnly (float dx) (float dy) (float sx) (float sy) (unchecked-int color)))))

;; ============================================================
;; Mask Filters (blur for strokes/fills)
;; ============================================================

(defn mask-blur
  "Create a blur mask filter (for blurring the alpha channel).

   Args:
     mode     - blur mode: :normal, :solid, :outer, :inner
     sigma    - blur radius

   Examples:
     (mask-blur :normal 5.0)  ; Standard blur
     (mask-blur :outer 3.0)   ; Outer glow effect
     (mask-blur :inner 3.0)   ; Inner glow effect"
  [mode sigma]
  (let [blur-mode (case mode
                    :normal FilterBlurMode/NORMAL
                    :solid FilterBlurMode/SOLID
                    :outer FilterBlurMode/OUTER
                    :inner FilterBlurMode/INNER
                    FilterBlurMode/NORMAL)]
    (MaskFilter/makeBlur blur-mode (float sigma))))

;; ============================================================
;; Color Filters
;; ============================================================

(defn color-matrix-filter
  "Create a color matrix filter for color transformations.

   Args:
     matrix - 20-element color matrix array [r g b a offset ...]

   Example:
     ;; Grayscale:
     (color-matrix-filter [0.33 0.33 0.33 0 0
                           0.33 0.33 0.33 0 0
                           0.33 0.33 0.33 0 0
                           0    0    0    1 0])"
  [matrix]
  (ColorFilter/makeMatrix (ColorMatrix. (float-array matrix))))

(defn brightness-filter
  "Create a brightness adjustment filter.

   Args:
     brightness - -1.0 (black) to 1.0 (white), 0 = no change"
  [brightness]
  (let [b (float brightness)]
    (color-matrix-filter [1 0 0 0 b
                          0 1 0 0 b
                          0 0 1 0 b
                          0 0 0 1 0])))

(defn contrast-filter
  "Create a contrast adjustment filter.

   Args:
     contrast - 0.0 (gray) to 2.0 (high contrast), 1.0 = no change"
  [contrast]
  (let [c (float contrast)
        offset (* 0.5 (- 1.0 c))]
    (color-matrix-filter [c 0 0 0 offset
                          0 c 0 0 offset
                          0 0 c 0 offset
                          0 0 0 1 0])))

(defn grayscale-filter
  "Create a grayscale filter (desaturates to black & white)."
  []
  (color-matrix-filter [0.33 0.33 0.33 0 0
                        0.33 0.33 0.33 0 0
                        0.33 0.33 0.33 0 0
                        0    0    0    1 0]))

(defn sepia-filter
  "Create a sepia tone filter (warm vintage look)."
  []
  (color-matrix-filter [0.393 0.769 0.189 0 0
                        0.349 0.686 0.168 0 0
                        0.272 0.534 0.131 0 0
                        0     0     0     1 0]))

;; ============================================================
;; Path Effects
;; ============================================================

(defn dash-path-effect
  "Create a dashed line path effect.

   Args:
     intervals - array of on/off lengths [on off on off ...]
     phase     - offset into intervals array (default 0)

   Examples:
     (dash-path-effect [10 5])        ; 10px dash, 5px gap
     (dash-path-effect [5 5 10 5])   ; dot-dash pattern
     (dash-path-effect [10 5] 2.5)   ; dashes offset by 2.5px"
  ([intervals]
   (dash-path-effect intervals 0))
  ([intervals phase]
   (PathEffect/makeDash (float-array intervals) (float phase))))

(defn corner-path-effect
  "Create a corner rounding path effect.

   Args:
     radius - corner radius in pixels

   Example:
     (corner-path-effect 10)  ; Round all corners with 10px radius"
  [radius]
  (PathEffect/makeCorner (float radius)))

(defn discrete-path-effect
  "Create a discrete/jagged path effect.

   Args:
     seg-length - length of each segment
     deviation  - maximum perpendicular deviation
     seed       - random seed (optional, defaults to 0)

   Example:
     (discrete-path-effect 10 5)    ; Jagged/sketchy line effect
     (discrete-path-effect 10 5 42) ; With specific seed"
  ([seg-length deviation]
   (discrete-path-effect seg-length deviation 0))
  ([seg-length deviation seed]
   (PathEffect/makeDiscrete (float seg-length) (float deviation) (int seed))))

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
  ([^Path marker spacing]
   (stamp-path-effect marker spacing {}))
  ([^Path marker spacing {:keys [offset fit] :or {offset 0 fit :turn}}]
   (let [style (case fit
                 :move PathEffect1DStyle/TRANSLATE
                 :turn PathEffect1DStyle/ROTATE
                 :follow PathEffect1DStyle/MORPH
                 PathEffect1DStyle/ROTATE)]
     (PathEffect/makePath1D marker (float spacing) (float offset) style))))

(defn sum-path-effects
  "Combine two path effects (both visible simultaneously)."
  [^PathEffect effect1 ^PathEffect effect2]
  (.makeSum effect1 effect2))

(defn compose-path-effects
  "Compose two path effects (apply outer after inner)."
  [^PathEffect outer ^PathEffect inner]
  (.makeCompose outer inner))

;; ============================================================
;; Mask Filters (Advanced)
;; ============================================================

(defn mask-shader
  "Create a mask filter from a shader.

   The shader's alpha channel controls masking:
   - White (alpha=1) = fully visible
   - Black (alpha=0) = fully masked

   Args:
     shader - Shader instance

   Example:
     (mask-shader my-gradient-shader)"
  [^Shader shader]
  (MaskFilter/makeShader shader))

(defn mask-table
  "Create a mask filter using a lookup table.

   The 256-element table maps input alpha values (0-255) to output alpha values.

   Args:
     table - byte array or sequence of 256 values (0-255)

   Example:
     ;; Posterize alpha to 4 levels
     (mask-table (mapcat #(repeat 64 (* % 85)) (range 4)))"
  [table]
  (let [byte-arr (if (bytes? table)
                   table
                   (byte-array (map unchecked-byte table)))]
    (MaskFilter/makeTable byte-arr)))

(defn mask-gamma
  "Create a gamma correction mask filter.

   Applies gamma curve to alpha channel. Values < 1 brighten, > 1 darken.

   Args:
     gamma - gamma exponent (typically 0.5-2.0)

   Example:
     (mask-gamma 2.2)  ; sRGB gamma correction"
  [gamma]
  (MaskFilter/makeGamma (float gamma)))

(defn mask-clip
  "Create a clipping mask filter.

   Maps alpha values outside [min, max] to 0, inside range stays unchanged.

   Args:
     min-val - minimum alpha (0-255)
     max-val - maximum alpha (0-255)

   Example:
     (mask-clip 128 255)  ; Only show alpha >= 50%"
  [min-val max-val]
  (MaskFilter/makeClip (int min-val) (int max-val)))

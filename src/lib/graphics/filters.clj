(ns lib.graphics.filters
  "Common filters and effects - blur, shadows, gradients, etc.

   All functions that take colors accept [r g b a] float vectors (0.0-1.0).

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:import [io.github.humbleui.skija ImageFilter MaskFilter ColorFilter ColorMatrix Shader
            FilterTileMode FilterBlurMode PathEffect PathEffect1DStyle Path Color4f ColorSpace
            BlendMode Matrix33 SamplingMode ColorChannel Image InversionMode]
           [io.github.humbleui.types Rect]))

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
     color    - shadow color as [r g b a] floats (0.0-1.0)

   Example:
     (drop-shadow 2 2 3.0 [0 0 0 0.5])  ; 2px offset, 3px blur, semi-transparent black"
  ([dx dy sigma color]
   (let [[sx sy] (if (vector? sigma) sigma [sigma sigma])
         [r g b a] color]
     (ImageFilter/makeDropShadow (float dx) (float dy) (float sx) (float sy)
                                 (Color4f. (float r) (float g) (float b) (float (or a 1.0)))
                                 (ColorSpace/getSRGB) nil))))

(defn drop-shadow-only
  "Create a drop shadow image filter (shadow only, no original content).

   Args:
     dx, dy   - shadow offset
     sigma    - blur radius (single value or [sigmaX sigmaY])
     color    - shadow color as [r g b a] floats (0.0-1.0)

   Example:
     (drop-shadow-only 2 2 3.0 [0 0 0 1])  ; Just the shadow"
  ([dx dy sigma color]
   (let [[sx sy] (if (vector? sigma) sigma [sigma sigma])
         [r g b a] color]
     (ImageFilter/makeDropShadowOnly (float dx) (float dy) (float sx) (float sy)
                                     (Color4f. (float r) (float g) (float b) (float (or a 1.0)))
                                     (ColorSpace/getSRGB) nil))))

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

;; ============================================================
;; Internal Helpers
;; ============================================================

(defn- color->argb-int
  "Convert [r g b a] float color (0.0-1.0) to 32-bit ARGB int."
  [[r g b a]]
  (let [ai (int (* 255 (float (or a 1.0))))
        ri (int (* 255 (float r)))
        gi (int (* 255 (float g)))
        bi (int (* 255 (float b)))]
    (unchecked-int (bit-or (bit-shift-left ai 24)
                           (bit-shift-left ri 16)
                           (bit-shift-left gi 8)
                           bi))))

(defn- resolve-blend-mode [mode]
  (if (instance? BlendMode mode)
    mode
    (case mode
      :clear BlendMode/CLEAR
      :src BlendMode/SRC
      :dst BlendMode/DST
      :src-over BlendMode/SRC_OVER
      :dst-over BlendMode/DST_OVER
      :src-in BlendMode/SRC_IN
      :dst-in BlendMode/DST_IN
      :src-out BlendMode/SRC_OUT
      :dst-out BlendMode/DST_OUT
      :src-atop BlendMode/SRC_ATOP
      :dst-atop BlendMode/DST_ATOP
      :xor BlendMode/XOR
      :plus BlendMode/PLUS
      :modulate BlendMode/MODULATE
      :screen BlendMode/SCREEN
      :overlay BlendMode/OVERLAY
      :darken BlendMode/DARKEN
      :lighten BlendMode/LIGHTEN
      :color-dodge BlendMode/COLOR_DODGE
      :color-burn BlendMode/COLOR_BURN
      :hard-light BlendMode/HARD_LIGHT
      :soft-light BlendMode/SOFT_LIGHT
      :difference BlendMode/DIFFERENCE
      :exclusion BlendMode/EXCLUSION
      :multiply BlendMode/MULTIPLY
      :hue BlendMode/HUE
      :saturation BlendMode/SATURATION
      :color BlendMode/COLOR
      :luminosity BlendMode/LUMINOSITY)))

(defn- resolve-color-channel [ch]
  (case ch
    :r ColorChannel/R
    :g ColorChannel/G
    :b ColorChannel/B
    :a ColorChannel/A))

(defn- resolve-tile-mode [mode]
  (case mode
    :clamp FilterTileMode/CLAMP
    :repeat FilterTileMode/REPEAT
    :mirror FilterTileMode/MIRROR
    :decal FilterTileMode/DECAL
    FilterTileMode/DECAL))

(defn- resolve-sampling-mode [sampling]
  (case sampling
    :default SamplingMode/DEFAULT
    :linear SamplingMode/LINEAR
    :mitchell SamplingMode/MITCHELL
    :catmull-rom SamplingMode/CATMULL_ROM
    SamplingMode/DEFAULT))

(defn- make-rect
  "Create Rect from [x y w h]."
  [[x y w h]]
  (Rect/makeXYWH (float x) (float y) (float w) (float h)))

;; ============================================================
;; ImageFilter Composition
;; ============================================================

(defn compose
  "Chain two image filters (outer applied to inner's output).
   If either is nil, uses the source graphics."
  [outer inner]
  (ImageFilter/makeCompose outer inner))

(defn merge-filters
  "Combine multiple image filters with src-over blending.
   All filters are applied to the same source and merged."
  [& filters]
  (ImageFilter/makeMerge (into-array ImageFilter filters)))

(defn blend-filter
  "Composite two image filters with a blend mode.
   mode: blend mode keyword (:multiply, :screen, :overlay, etc.)
   bg/fg: ImageFilters (nil = source graphics)."
  [mode bg fg]
  (ImageFilter/makeBlend (resolve-blend-mode mode) bg fg))

;; ============================================================
;; ImageFilter Geometry
;; ============================================================

(defn offset
  "Translate filtered content by (dx, dy).
   Optional input filter (nil = source graphics)."
  ([dx dy]
   (ImageFilter/makeOffset (float dx) (float dy) nil))
  ([dx dy input]
   (ImageFilter/makeOffset (float dx) (float dy) input)))

(defn matrix-transform
  "Transform by Matrix33 with sampling mode.
   sampling: :default, :linear, :mitchell, :catmull-rom"
  [matrix sampling input]
  (ImageFilter/makeMatrixTransform matrix (resolve-sampling-mode sampling) input))

(defn crop
  "Crop image filter to rect [x y w h].
   Optional tile mode for outside-rect behavior."
  ([rect input]
   (ImageFilter/makeCrop (make-rect rect) input))
  ([rect mode input]
   (ImageFilter/makeCrop (make-rect rect) (resolve-tile-mode mode) input)))

(defn tile
  "Repeat a region as a tile pattern.
   src-rect, dst-rect: [x y w h]."
  [src-rect dst-rect input]
  (ImageFilter/makeTile (make-rect src-rect) (make-rect dst-rect) input))

;; ============================================================
;; Morphology
;; ============================================================

(defn dilate
  "Morphological expand (thicken shapes).
   Single radius or separate rx, ry."
  ([radius]
   (dilate radius radius))
  ([rx ry]
   (ImageFilter/makeDilate (float rx) (float ry) nil)))

(defn erode
  "Morphological shrink (thin shapes).
   Single radius or separate rx, ry."
  ([radius]
   (erode radius radius))
  ([rx ry]
   (ImageFilter/makeErode (float rx) (float ry) nil)))

;; ============================================================
;; Advanced Filters
;; ============================================================

(defn displacement-map
  "Displacement map using color channels of a displacement image.
   x-chan, y-chan: :r, :g, :b, :a — which channel drives displacement
   scale: displacement magnitude
   displacement-input: filter providing displacement colors
   color-input: filter providing colors to displace (nil = source)"
  [x-chan y-chan scale displacement-input color-input]
  (ImageFilter/makeDisplacementMap
    (resolve-color-channel x-chan)
    (resolve-color-channel y-chan)
    (float scale)
    displacement-input
    color-input))

(defn matrix-convolution
  "Apply a convolution kernel to the image.
   kernel: flat vector of floats (width*height elements)
   opts: {:width :height :gain :bias :offset :tile-mode :convolve-alpha :input}
     gain multiplies each kernel output, bias is added after.
     offset: [kx ky] kernel center (default: center of kernel)."
  [kernel {:keys [width height gain bias offset tile-mode convolve-alpha input]
           :or {gain 1.0 bias 0.0 tile-mode :decal convolve-alpha true input nil}}]
  (let [w (int width)
        h (int height)
        [kx ky] (or offset [(quot w 2) (quot h 2)])]
    (ImageFilter/makeMatrixConvolution
      w h
      (float-array kernel)
      (float gain)
      (float bias)
      (int kx) (int ky)
      (resolve-tile-mode tile-mode)
      (boolean convolve-alpha)
      input)))

(defn magnifier
  "Lens magnification effect.
   src-rect: [x y w h] region to magnify
   zoom: magnification factor
   inset: border inset for smooth transition"
  ([src-rect zoom inset]
   (magnifier src-rect zoom inset nil))
  ([src-rect zoom inset input]
   (ImageFilter/makeMagnifier
     (make-rect src-rect)
     (float zoom)
     (float inset)
     SamplingMode/LINEAR
     input)))

;; ============================================================
;; Convolution Kernel Presets
;; ============================================================

(def sharpen-kernel [0 -1 0, -1 5 -1, 0 -1 0])
(def emboss-kernel [-2 -1 0, -1 1 1, 0 1 2])
(def edge-detect-kernel [-1 -1 -1, -1 8 -1, -1 -1 -1])
(def blur-3x3-kernel (vec (repeat 9 (/ 1.0 9))))

;; ============================================================
;; Lighting
;; ============================================================

(defn light
  "Create a lighting effect (diffuse or specular).
   opts map:
     :source    :distant | :point | :spot
     :type      :diffuse | :specular
     ;; For :distant
     :azimuth   degrees  :elevation  degrees
     ;; For :point
     :x :y :z   light position
     ;; For :spot
     :from [x y z]  :to [x y z]  :falloff N  :cutoff-angle degrees
     ;; Common
     :color         [r g b a] (default [1 1 1 1])
     :surface-scale float (default 1.0)
     :kd            float (diffuse coefficient, default 1.0)
     :ks            float (specular coefficient, default 1.0)
     :shininess     float (specular only, default 8.0)
     :input         optional ImageFilter"
  [opts]
  (let [{:keys [source type azimuth elevation x y z from to falloff cutoff-angle
                color surface-scale kd ks shininess input]
         :or {source :distant type :diffuse color [1 1 1 1]
              surface-scale 1.0 kd 1.0 ks 1.0 shininess 8.0
              azimuth 135 elevation 45
              falloff 1.0 cutoff-angle 30}} opts
        light-color (color->argb-int color)
        ss (float surface-scale)]
    (case [source type]
      [:distant :diffuse]
      (let [az (Math/toRadians (double azimuth))
            el (Math/toRadians (double elevation))
            dx (float (* (Math/cos el) (Math/cos az)))
            dy (float (* (Math/cos el) (Math/sin az)))
            dz (float (Math/sin el))]
        (ImageFilter/makeDistantLitDiffuse dx dy dz light-color ss (float kd) input))

      [:distant :specular]
      (let [az (Math/toRadians (double azimuth))
            el (Math/toRadians (double elevation))
            dx (float (* (Math/cos el) (Math/cos az)))
            dy (float (* (Math/cos el) (Math/sin az)))
            dz (float (Math/sin el))]
        (ImageFilter/makeDistantLitSpecular dx dy dz light-color ss (float ks) (float shininess) input))

      [:point :diffuse]
      (ImageFilter/makePointLitDiffuse (float x) (float y) (float z)
                                       light-color ss (float kd) input)

      [:point :specular]
      (ImageFilter/makePointLitSpecular (float x) (float y) (float z)
                                        light-color ss (float ks) (float shininess) input)

      [:spot :diffuse]
      (let [[fx fy fz] from
            [tx ty tz] to]
        (ImageFilter/makeSpotLitDiffuse
          (float fx) (float fy) (float fz) (float tx) (float ty) (float tz)
          (float falloff) (float cutoff-angle) light-color ss (float kd) input))

      [:spot :specular]
      (let [[fx fy fz] from
            [tx ty tz] to]
        (ImageFilter/makeSpotLitSpecular
          (float fx) (float fy) (float fz) (float tx) (float ty) (float tz)
          (float falloff) (float cutoff-angle) light-color ss (float ks) (float shininess) input)))))

;; ============================================================
;; Source Filters
;; ============================================================

(defn image-source
  "Use an Image as filter input."
  ([^Image image]
   (ImageFilter/makeImage image))
  ([^Image image src dst sampling]
   (ImageFilter/makeImage image (make-rect src) (make-rect dst) (resolve-sampling-mode sampling))))

(defn shader-source
  "Use a Shader as filter input."
  [^Shader shader]
  (ImageFilter/makeShader shader))

(defn empty-filter
  "Transparent black filter (composition placeholder)."
  []
  (ImageFilter/makeEmpty))

;; ============================================================
;; Arithmetic Blend
;; ============================================================

(defn arithmetic
  "Arithmetic blend of two filters.
   result = k1*bg*fg + k2*fg + k3*bg + k4
   bg, fg: ImageFilters (nil = source).
   enforce-premul: clamp to premultiplied range (default true)."
  ([k1 k2 k3 k4 bg fg]
   (arithmetic k1 k2 k3 k4 bg fg true))
  ([k1 k2 k3 k4 bg fg enforce-premul]
   (ImageFilter/makeArithmetic (float k1) (float k2) (float k3) (float k4)
                                (boolean enforce-premul) bg fg)))

;; ============================================================
;; ColorFilter Composition & Blending
;; ============================================================

(defn compose-colors
  "Chain two color filters (outer applied to inner's output)."
  [outer inner]
  (ColorFilter/makeComposed outer inner))

(defn blend-color
  "Blend a solid color with a blend mode.
   color: [r g b a] floats (0.0-1.0)
   mode: blend mode keyword"
  [color mode]
  (ColorFilter/makeBlend (color->argb-int color) (resolve-blend-mode mode)))

(defn lerp-colors
  "Interpolate between two color filters.
   t: 0.0 = filter-a, 1.0 = filter-b."
  [filter-a filter-b t]
  (ColorFilter/makeLerp filter-a filter-b (float t)))

;; ============================================================
;; Advanced ColorFilters
;; ============================================================

(defn hsla-matrix
  "Color matrix transform in HSLA color space.
   matrix: 20-element color matrix."
  [matrix]
  (ColorFilter/makeHSLAMatrix (ColorMatrix. (float-array matrix))))

(defn high-contrast
  "Accessibility high-contrast filter.
   opts: {:grayscale bool :inversion :no|:brightness|:lightness :contrast float}"
  [opts]
  (let [{:keys [grayscale inversion contrast]
         :or {grayscale false inversion :no contrast 0.0}} opts
        inv-mode (case inversion
                   :no InversionMode/NO
                   :brightness InversionMode/BRIGHTNESS
                   :lightness InversionMode/LIGHTNESS
                   InversionMode/NO)]
    (ColorFilter/makeHighContrast (boolean grayscale) inv-mode (float contrast))))

(defn lighting-color
  "Per-channel multiply + add color filter.
   multiply: [r g b] — multiplied per channel (with alpha=1)
   add: [r g b] — added per channel after multiply (with alpha=0)"
  [multiply add]
  (let [mul-int (if (vector? multiply)
                  (color->argb-int (conj (vec multiply) 1.0))
                  (unchecked-int multiply))
        add-int (if (vector? add)
                  (color->argb-int (conj (vec add) 0.0))
                  (unchecked-int add))]
    (ColorFilter/makeLighting mul-int add-int)))

(defn luma-filter
  "Extract luminance to alpha. Useful for masking by brightness."
  []
  (ColorFilter/getLuma))

;; ============================================================
;; Composite Helpers
;; ============================================================

(defn inner-shadow
  "Create an inner shadow image filter.
   The shadow appears inside the shape's edges.

   Algorithm:
   1. Offset + blur the source to create a shifted blurred copy
   2. Invert its alpha (opaque center -> transparent, transparent edges -> opaque)
   3. Tint with shadow color
   4. Composite inside original shape using SRC_ATOP blend

   Args:
     dx, dy  - shadow offset direction
     sigma   - blur radius
     color   - shadow color as [r g b a] floats"
  [dx dy sigma color]
  (let [;; Offset source in shadow direction and blur
        shifted (ImageFilter/makeOffset (float dx) (float dy) nil)
        ;; makeBlur has no 4-arg overload; use 5-arg with type-hinted crop
        ^Rect no-crop nil
        blurred (ImageFilter/makeBlur (float sigma) (float sigma)
                                      FilterTileMode/DECAL shifted no-crop)
        ;; Invert alpha: where source was opaque -> transparent, edges -> opaque
        ;; Matrix zeroes RGB, inverts alpha: a' = 1 - a
        invert-matrix (ColorMatrix. (float-array [0 0 0 0 0
                                                  0 0 0 0 0
                                                  0 0 0 0 0
                                                  0 0 0 -1 1]))
        inverted (ImageFilter/makeColorFilter
                   (ColorFilter/makeMatrix invert-matrix) blurred)
        ;; Tint with shadow color (SRC_IN: replace color, keep alpha)
        shadow-int (color->argb-int color)
        tinted (ImageFilter/makeColorFilter
                 (ColorFilter/makeBlend shadow-int BlendMode/SRC_IN)
                 inverted)
        ;; SRC_ATOP: clips shadow to source shape and composites over it
        ;; result = shadow*source.a + source*(1-shadow.a)
        result (ImageFilter/makeBlend BlendMode/SRC_ATOP nil tinted)]
    result))

(defn box-shadow
  "CSS-style box shadow.
   opts: {:dx :dy :blur :spread :color :inset}
   When :inset false (default): outer shadow (drop-shadow with optional spread).
   When :inset true: inner shadow."
  [{:keys [dx dy blur spread color inset]
    :or {dx 0 dy 0 blur 0 spread 0 color [0 0 0 0.5] inset false}}]
  (if inset
    (inner-shadow dx dy blur color)
    (if (pos? spread)
      ;; Outer shadow with spread: dilate source, shadow the dilated version
      (let [dilated (ImageFilter/makeDilate (float spread) (float spread) nil)
            [sx sy] (if (vector? blur) blur [blur blur])
            [r g b a] color
            shadow (ImageFilter/makeDropShadowOnly
                     (float dx) (float dy) (float sx) (float sy)
                     (Color4f. (float r) (float g) (float b) (float (or a 1.0)))
                     (ColorSpace/getSRGB) dilated)]
        ;; Merge: shadow behind, then source on top
        (ImageFilter/makeMerge (into-array ImageFilter [shadow nil])))
      ;; Simple outer shadow without spread
      (drop-shadow dx dy blur color))))

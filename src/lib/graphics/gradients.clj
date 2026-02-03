(ns lib.graphics.gradients
  "Gradient and shader utilities.

   All gradient functions accept colors as [r g b a] float vectors (0.0-1.0).

   Interpolation color spaces (optional):
     :destination (default), :srgb-linear, :lab, :oklab, :lch, :oklch,
     :srgb, :hsl, :hwb, :display-p3, :rec2020, :prophoto-rgb, :a98-rgb

   Hue methods for polar spaces (LCH, OKLCH, HSL, HWB):
     :shorter (default), :longer, :increasing, :decreasing

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:import [io.github.humbleui.skija Shader GradientStyle FilterTileMode Color4f ColorSpace]))

;; ============================================================
;; Internal Helpers
;; ============================================================

(defn- vec->color4f
  "Convert [r g b a] floats to Color4f."
  [[r g b a]]
  (Color4f. (float r) (float g) (float b) (float (or a 1.0))))

(defn- colors->color4f-array
  "Convert sequence of [r g b a] colors to Color4f array."
  [colors]
  (into-array Color4f (map vec->color4f colors)))

(defn- parse-tile-mode
  "Convert keyword to FilterTileMode."
  [mode]
  (case mode
    :clamp FilterTileMode/CLAMP
    :repeat FilterTileMode/REPEAT
    :mirror FilterTileMode/MIRROR
    :decal FilterTileMode/DECAL
    FilterTileMode/CLAMP))

(defn- parse-interp-color-space
  "Convert keyword to Interpolation::ColorSpace ordinal."
  [cs]
  (case cs
    nil            0
    :destination   0
    :srgb-linear   1
    :lab           2
    :oklab         3
    :oklab-gamut   4
    :lch           5
    :oklch         6
    :oklch-gamut   7
    :srgb          8
    :hsl           9
    :hwb           10
    :display-p3    11
    :rec2020       12
    :prophoto-rgb  13
    :a98-rgb       14
    0))

(defn- parse-hue-method
  "Convert keyword to Interpolation::HueMethod ordinal."
  [hm]
  (case hm
    nil          0
    :shorter     0
    :longer      1
    :increasing  2
    :decreasing  3
    0))

(defn- make-gradient-style
  "Create a GradientStyle from tile mode and interpolation options."
  [tile-mode interp-color-space hue-method]
  (GradientStyle. (parse-tile-mode tile-mode) true nil
                  (int (parse-interp-color-space interp-color-space))
                  (int (parse-hue-method hue-method))))

;; ============================================================
;; Linear Gradients
;; ============================================================

(defn linear-gradient
  "Create a linear gradient shader.

   Args:
     x0, y0       - start point
     x1, y1       - end point
     colors       - sequence of [r g b a] float colors
     positions    - optional array of positions (0.0-1.0), nil for evenly spaced
     tile-mode    - :clamp, :repeat, :mirror, or :decal (default :clamp)
     interp-color-space - interpolation color space keyword (default nil = :destination)
     hue-method   - hue interpolation method keyword (default nil = :shorter)

   Examples:
     ;; Simple two-color gradient
     (linear-gradient 0 0 100 0 [[0 0 0 1] [1 1 1 1]])

     ;; Oklab interpolation (perceptually uniform)
     (linear-gradient 0 0 100 0 [[1 1 0 1] [0 0 1 1]] nil :clamp :oklab)

     ;; OKLCH with longer hue path
     (linear-gradient 0 0 100 0 [[1 0 0 1] [0 0 1 1]] nil :clamp :oklch :longer)"
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
     (Shader/makeLinearGradient (float x0) (float y0) (float x1) (float y1)
                                colors-arr (ColorSpace/getSRGB) positions-arr style))))

;; ============================================================
;; Radial Gradients
;; ============================================================

(defn radial-gradient
  "Create a radial gradient shader.

   Args:
     cx, cy       - center point
     radius       - gradient radius
     colors       - sequence of [r g b a] float colors
     positions    - optional array of positions (0.0-1.0), nil for evenly spaced
     tile-mode    - :clamp, :repeat, :mirror, or :decal (default :clamp)
     interp-color-space - interpolation color space keyword (default nil = :destination)
     hue-method   - hue interpolation method keyword (default nil = :shorter)

   Examples:
     ;; Simple radial gradient
     (radial-gradient 50 50 50 [[1 0 0 1] [0 0 1 1]])

     ;; Oklab radial
     (radial-gradient 50 50 50 [[1 0 0 1] [0 0 1 1]] nil :clamp :oklab)"
  ([cx cy radius colors]
   (radial-gradient cx cy radius colors nil :clamp nil nil))
  ([cx cy radius colors positions]
   (radial-gradient cx cy radius colors positions :clamp nil nil))
  ([cx cy radius colors positions tile-mode]
   (radial-gradient cx cy radius colors positions tile-mode nil nil))
  ([cx cy radius colors positions tile-mode interp-color-space hue-method]
   (let [colors-arr (colors->color4f-array colors)
         positions-arr (when positions (float-array positions))
         style (make-gradient-style tile-mode interp-color-space hue-method)]
     (Shader/makeRadialGradient (float cx) (float cy) (float radius)
                                colors-arr (ColorSpace/getSRGB) positions-arr style))))

;; ============================================================
;; Two-Point Conical Gradients
;; ============================================================

(defn conical-gradient
  "Create a two-point conical gradient shader.

   Args:
     x0, y0, r0   - start point and radius
     x1, y1, r1   - end point and radius
     colors       - sequence of [r g b a] float colors
     positions    - optional array of positions (0.0-1.0), nil for evenly spaced
     tile-mode    - :clamp, :repeat, :mirror, or :decal (default :clamp)
     interp-color-space - interpolation color space keyword (default nil = :destination)
     hue-method   - hue interpolation method keyword (default nil = :shorter)

   Example:
     (conical-gradient 0 0 10 100 100 50
                       [[1 0 0 1] [0 0 1 1]])"
  ([x0 y0 r0 x1 y1 r1 colors]
   (conical-gradient x0 y0 r0 x1 y1 r1 colors nil :clamp nil nil))
  ([x0 y0 r0 x1 y1 r1 colors positions]
   (conical-gradient x0 y0 r0 x1 y1 r1 colors positions :clamp nil nil))
  ([x0 y0 r0 x1 y1 r1 colors positions tile-mode]
   (conical-gradient x0 y0 r0 x1 y1 r1 colors positions tile-mode nil nil))
  ([x0 y0 r0 x1 y1 r1 colors positions tile-mode interp-color-space hue-method]
   (let [colors-arr (colors->color4f-array colors)
         positions-arr (when positions (float-array positions))
         style (make-gradient-style tile-mode interp-color-space hue-method)]
     (Shader/makeTwoPointConicalGradient (float x0) (float y0) (float r0)
                                         (float x1) (float y1) (float r1)
                                         colors-arr (ColorSpace/getSRGB) positions-arr style))))

;; ============================================================
;; Sweep Gradients (Angular)
;; ============================================================

(defn sweep-gradient
  "Create a sweep/angular gradient shader (like a color wheel).

   Args:
     cx, cy       - center point
     colors       - sequence of [r g b a] float colors
     positions    - optional array of positions (0.0-1.0), nil for evenly spaced
     start-angle  - start angle in degrees (default 0)
     end-angle    - end angle in degrees (default 360)
     tile-mode    - :clamp, :repeat, :mirror, or :decal (default :clamp)
     interp-color-space - interpolation color space keyword (default nil = :destination)
     hue-method   - hue interpolation method keyword (default nil = :shorter)

   Examples:
     ;; Full sweep gradient
     (sweep-gradient 50 50 [[1 0 0 1] [0 1 0 1] [0 0 1 1]])

     ;; Partial sweep (90 degrees)
     (sweep-gradient 50 50 [[1 0 0 1] [0 0 1 1]] nil 0 90)"
  ([cx cy colors]
   (sweep-gradient cx cy colors nil 0 360 :clamp nil nil))
  ([cx cy colors positions]
   (sweep-gradient cx cy colors positions 0 360 :clamp nil nil))
  ([cx cy colors positions start-angle end-angle]
   (sweep-gradient cx cy colors positions start-angle end-angle :clamp nil nil))
  ([cx cy colors positions start-angle end-angle tile-mode]
   (sweep-gradient cx cy colors positions start-angle end-angle tile-mode nil nil))
  ([cx cy colors positions start-angle end-angle tile-mode interp-color-space hue-method]
   (let [colors-arr (colors->color4f-array colors)
         positions-arr (when positions (float-array positions))
         style (make-gradient-style tile-mode interp-color-space hue-method)]
     (Shader/makeSweepGradient (float cx) (float cy)
                               (float start-angle) (float end-angle)
                               colors-arr (ColorSpace/getSRGB) positions-arr style))))

;; ============================================================
;; Solid Color Shader
;; ============================================================

(defn solid-color
  "Create a solid color shader.

   Args:
     color - [r g b a] float color

   Example:
     (solid-color [0.29 0.56 0.85 1.0])"
  [color]
  (Shader/makeColor (vec->color4f color) (ColorSpace/getSRGB)))

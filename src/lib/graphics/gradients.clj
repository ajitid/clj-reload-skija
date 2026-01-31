(ns lib.graphics.gradients
  "Gradient and shader utilities.

   All gradient functions accept colors as [r g b a] float vectors (0.0-1.0).

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:import [io.github.humbleui.skija Shader GradientStyle FilterTileMode]))

;; ============================================================
;; Internal Helpers
;; ============================================================

(defn- color4f->int
  "Convert [r g b a] floats to packed ARGB int for Skija."
  [[r g b a]]
  (let [a (or a 1.0)]
    (unchecked-int
     (bit-or (bit-shift-left (int (* a 255)) 24)
             (bit-shift-left (int (* r 255)) 16)
             (bit-shift-left (int (* g 255)) 8)
             (int (* b 255))))))

(defn- colors->int-array
  "Convert sequence of [r g b a] colors to int array."
  [colors]
  (int-array (map color4f->int colors)))

(defn- parse-tile-mode
  "Convert keyword to FilterTileMode."
  [mode]
  (case mode
    :clamp FilterTileMode/CLAMP
    :repeat FilterTileMode/REPEAT
    :mirror FilterTileMode/MIRROR
    :decal FilterTileMode/DECAL
    FilterTileMode/CLAMP))

(defn- make-gradient-style
  "Create a GradientStyle from tile mode keyword."
  [tile-mode]
  (GradientStyle. (parse-tile-mode tile-mode) true nil))

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

   Examples:
     ;; Simple two-color gradient
     (linear-gradient 0 0 100 0 [[0 0 0 1] [1 1 1 1]])

     ;; Three-color gradient with custom stops
     (linear-gradient 0 0 100 100
                      [[1 0 0 1] [0 1 0 1] [0 0 1 1]]
                      [0.0 0.5 1.0])

     ;; Repeating gradient
     (linear-gradient 0 0 100 0
                      [[0 0 0 1] [1 1 1 1]]
                      nil
                      :repeat)"
  ([x0 y0 x1 y1 colors]
   (linear-gradient x0 y0 x1 y1 colors nil :clamp))
  ([x0 y0 x1 y1 colors positions]
   (linear-gradient x0 y0 x1 y1 colors positions :clamp))
  ([x0 y0 x1 y1 colors positions tile-mode]
   (let [colors-arr (colors->int-array colors)
         positions-arr (when positions (float-array positions))
         style (make-gradient-style tile-mode)]
     (Shader/makeLinearGradient (float x0) (float y0) (float x1) (float y1)
                                colors-arr positions-arr style))))

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

   Examples:
     ;; Simple radial gradient
     (radial-gradient 50 50 50 [[1 0 0 1] [0 0 1 1]])

     ;; Multi-stop gradient
     (radial-gradient 100 100 100
                      [[1 1 1 1] [0.5 0.5 0.5 1] [0 0 0 1]]
                      [0.0 0.7 1.0])"
  ([cx cy radius colors]
   (radial-gradient cx cy radius colors nil :clamp))
  ([cx cy radius colors positions]
   (radial-gradient cx cy radius colors positions :clamp))
  ([cx cy radius colors positions tile-mode]
   (let [colors-arr (colors->int-array colors)
         positions-arr (when positions (float-array positions))
         style (make-gradient-style tile-mode)]
     (Shader/makeRadialGradient (float cx) (float cy) (float radius)
                                colors-arr positions-arr style))))

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

   Example:
     (conical-gradient 0 0 10 100 100 50
                       [[1 0 0 1] [0 0 1 1]])"
  ([x0 y0 r0 x1 y1 r1 colors]
   (conical-gradient x0 y0 r0 x1 y1 r1 colors nil :clamp))
  ([x0 y0 r0 x1 y1 r1 colors positions]
   (conical-gradient x0 y0 r0 x1 y1 r1 colors positions :clamp))
  ([x0 y0 r0 x1 y1 r1 colors positions tile-mode]
   (let [colors-arr (colors->int-array colors)
         positions-arr (when positions (float-array positions))
         style (make-gradient-style tile-mode)]
     (Shader/makeTwoPointConicalGradient (float x0) (float y0) (float r0)
                                         (float x1) (float y1) (float r1)
                                         colors-arr positions-arr style))))

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

   Examples:
     ;; Full sweep gradient
     (sweep-gradient 50 50 [[1 0 0 1] [0 1 0 1] [0 0 1 1]])

     ;; Partial sweep (90 degrees)
     (sweep-gradient 50 50 [[1 0 0 1] [0 0 1 1]] nil 0 90)"
  ([cx cy colors]
   (sweep-gradient cx cy colors nil 0 360 :clamp))
  ([cx cy colors positions]
   (sweep-gradient cx cy colors positions 0 360 :clamp))
  ([cx cy colors positions start-angle end-angle]
   (sweep-gradient cx cy colors positions start-angle end-angle :clamp))
  ([cx cy colors positions start-angle end-angle tile-mode]
   (let [colors-arr (colors->int-array colors)
         positions-arr (when positions (float-array positions))
         style (make-gradient-style tile-mode)]
     (if (and (= start-angle 0) (= end-angle 360))
       ;; Full sweep
       (Shader/makeSweepGradient (float cx) (float cy) colors-arr positions-arr)
       ;; Partial sweep
       (Shader/makeSweepGradient (float cx) (float cy)
                                 (float start-angle) (float end-angle)
                                 colors-arr positions-arr style)))))

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
  (Shader/makeColor (color4f->int color)))

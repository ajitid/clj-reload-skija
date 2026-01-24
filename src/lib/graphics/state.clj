(ns lib.graphics.state
  "Paint management utilities - Love2D-style graphics state.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:import [io.github.humbleui.skija Paint PaintMode PaintStrokeCap PaintStrokeJoin
            BlendMode ImageFilter MaskFilter ColorFilter Shader PathEffect Blender
            Color4f ColorSpace]))

;; ============================================================
;; Paint Configuration
;; ============================================================

(defn set-color
  "Set paint color (32-bit ARGB integer)."
  [^Paint paint color]
  (.setColor paint (unchecked-int color))
  paint)

(defn set-mode
  "Set paint mode (:fill or :stroke)."
  [^Paint paint mode]
  (.setMode paint (case mode
                    :fill PaintMode/FILL
                    :stroke PaintMode/STROKE
                    mode))
  paint)

(defn set-stroke-width
  "Set stroke width for stroke mode."
  [^Paint paint width]
  (.setStrokeWidth paint (float width))
  paint)

(defn set-stroke-cap
  "Set stroke cap (:butt, :round, or :square)."
  [^Paint paint cap]
  (.setStrokeCap paint (case cap
                         :butt PaintStrokeCap/BUTT
                         :round PaintStrokeCap/ROUND
                         :square PaintStrokeCap/SQUARE
                         cap))
  paint)

(defn set-antialias
  "Set antialiasing on/off."
  [^Paint paint enabled?]
  (.setAntiAlias paint (boolean enabled?))
  paint)

(defn set-dither
  "Set dithering on/off for smooth color transitions."
  [^Paint paint enabled?]
  (.setDither paint (boolean enabled?))
  paint)

(defn set-alpha
  "Set alpha (0-255), leaving RGB unchanged."
  [^Paint paint alpha]
  (.setAlpha paint (int alpha))
  paint)

(defn set-alphaf
  "Set alpha (0.0-1.0), leaving RGB unchanged."
  [^Paint paint alpha]
  (.setAlphaf paint (float alpha))
  paint)

(defn set-stroke-join
  "Set stroke join (:miter, :round, or :bevel)."
  [^Paint paint join]
  (.setStrokeJoin paint (case join
                          :miter PaintStrokeJoin/MITER
                          :round PaintStrokeJoin/ROUND
                          :bevel PaintStrokeJoin/BEVEL
                          join))
  paint)

(defn set-stroke-miter
  "Set miter limit for sharp corners."
  [^Paint paint miter]
  (.setStrokeMiter paint (float miter))
  paint)

(defn set-argb
  "Set color from separate A, R, G, B components (0-255 each).

   Example:
     (set-argb paint 255 100 150 200)  ; fully opaque blue-ish"
  [^Paint paint a r g b]
  (.setARGB paint (int a) (int r) (int g) (int b))
  paint)

(defn set-color4f
  "Set color using floating point RGBA (0.0-1.0 each).
   Supports HDR/wide-gamut colors with optional ColorSpace.

   Examples:
     (set-color4f paint 1.0 0.5 0.2 1.0)           ; opaque orange
     (set-color4f paint 1.0 0.5 0.2 1.0 color-space) ; with color space"
  ([^Paint paint r g b a]
   (.setColor4f paint (Color4f. (float r) (float g) (float b) (float a)))
   paint)
  ([^Paint paint r g b a ^ColorSpace color-space]
   (.setColor4f paint (Color4f. (float r) (float g) (float b) (float a)) color-space)
   paint))

(defn reset-paint
  "Reset paint to default values. Useful for reusing paint objects."
  [^Paint paint]
  (.reset paint)
  paint)

(defn clone-paint
  "Create a shallow copy of paint. Shares PathEffect, Shader, MaskFilter,
   ColorFilter, and ImageFilter with the original."
  [^Paint paint]
  (.makeClone paint))

;; ============================================================
;; Advanced Effects
;; ============================================================

(defn set-blend-mode
  "Set blend mode for compositing.

   Common modes:
   - :src-over (default) - source over destination
   - :src - replace destination
   - :dst - keep destination
   - :clear - clear to transparent
   - :multiply - multiply colors
   - :screen - screen blend
   - :overlay - overlay blend
   - :darken, :lighten, etc.

   See BlendMode enum for all ~30 modes."
  [^Paint paint mode]
  (when mode
    (.setBlendMode paint (case mode
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
                           :luminosity BlendMode/LUMINOSITY
                           mode)))
  paint)

(defn set-shader
  "Set shader for gradients/patterns (ImageFilter instance or nil)."
  [^Paint paint shader]
  (.setShader paint shader)
  paint)

(defn set-color-filter
  "Set color filter for color transformations (ColorFilter instance or nil)."
  [^Paint paint filter]
  (.setColorFilter paint filter)
  paint)

(defn set-image-filter
  "Set image filter for effects like blur, shadows (ImageFilter instance or nil)."
  [^Paint paint filter]
  (.setImageFilter paint filter)
  paint)

(defn set-mask-filter
  "Set mask filter for blur effects (MaskFilter instance or nil)."
  [^Paint paint filter]
  (.setMaskFilter paint filter)
  paint)

(defn set-path-effect
  "Set path effect for dashed lines, etc (PathEffect instance or nil)."
  [^Paint paint effect]
  (.setPathEffect paint effect)
  paint)

(defn set-blender
  "Set blender for custom blend functions (Blender instance or nil)."
  [^Paint paint blender]
  (.setBlender paint blender)
  paint)

;; ============================================================
;; Paint Creation
;; ============================================================

(defn- apply-effect
  "Internal: Apply a high-level effect to paint.
   Converts declarative effect maps to Skija objects."
  [^Paint paint effect-key effect-value]
  (case effect-key
    ;; Blur effects
    :blur
    (let [sigma (if (number? effect-value) effect-value (first effect-value))
          mode (if (number? effect-value) :decal (second effect-value))]
      (require 'lib.graphics.filters)
      (set-image-filter paint ((resolve 'lib.graphics.filters/blur) sigma mode)))

    :shadow
    (let [{:keys [dx dy blur color] :or {dx 0 dy 0 blur 0 color 0x80000000}} effect-value]
      (require 'lib.graphics.filters)
      (set-image-filter paint ((resolve 'lib.graphics.filters/drop-shadow) dx dy blur color)))

    :glow
    (let [{:keys [size mode] :or {size 10 mode :outer}} effect-value]
      (require 'lib.graphics.filters)
      (set-mask-filter paint ((resolve 'lib.graphics.filters/mask-blur) mode size)))

    ;; Color effects
    :grayscale
    (when effect-value
      (require 'lib.graphics.filters)
      (set-color-filter paint ((resolve 'lib.graphics.filters/grayscale-filter))))

    :sepia
    (when effect-value
      (require 'lib.graphics.filters)
      (set-color-filter paint ((resolve 'lib.graphics.filters/sepia-filter))))

    :brightness
    (require 'lib.graphics.filters)
    (set-color-filter paint ((resolve 'lib.graphics.filters/brightness-filter) effect-value))

    :contrast
    (require 'lib.graphics.filters)
    (set-color-filter paint ((resolve 'lib.graphics.filters/contrast-filter) effect-value))

    ;; Gradients
    :gradient
    (let [{:keys [type]} effect-value]
      (require 'lib.graphics.gradients)
      (set-shader paint
        (case type
          :linear ((resolve 'lib.graphics.gradients/linear-gradient)
                   (:x0 effect-value) (:y0 effect-value)
                   (:x1 effect-value) (:y1 effect-value)
                   (:colors effect-value)
                   (:stops effect-value)
                   (:tile-mode effect-value :clamp))
          :radial ((resolve 'lib.graphics.gradients/radial-gradient)
                   (:cx effect-value) (:cy effect-value)
                   (:radius effect-value)
                   (:colors effect-value)
                   (:stops effect-value)
                   (:tile-mode effect-value :clamp))
          :sweep ((resolve 'lib.graphics.gradients/sweep-gradient)
                  (:cx effect-value) (:cy effect-value)
                  (:colors effect-value)
                  (:stops effect-value)
                  (:start effect-value 0)
                  (:end effect-value 360)
                  (:tile-mode effect-value :clamp)))))

    ;; Line styles
    :dash
    (let [intervals (if (vector? effect-value) effect-value [effect-value effect-value])]
      (require 'lib.graphics.filters)
      (set-path-effect paint ((resolve 'lib.graphics.filters/dash-path-effect) intervals)))

    ;; Low-level (for advanced users)
    :shader (set-shader paint effect-value)
    :color-filter (set-color-filter paint effect-value)
    :image-filter (set-image-filter paint effect-value)
    :mask-filter (set-mask-filter paint effect-value)
    :path-effect (set-path-effect paint effect-value)
    :blender (set-blender paint effect-value)

    ;; Unknown - ignore
    nil)
  paint)

(defn make-paint
  "Create a Paint with options map.

   Basic Options:
     :color         - 32-bit ARGB color (default 0xFFFFFFFF)
     :mode          - :fill or :stroke (default :fill)
     :antialias     - boolean (default true)
     :dither        - boolean (default false)
     :alpha         - 0-255 (overrides color alpha)
     :alphaf        - 0.0-1.0 (overrides color alpha)

   Stroke Options (when :mode is :stroke):
     :stroke-width  - width for stroke mode (default 1.0)
     :stroke-cap    - :butt, :round, or :square (default :butt)
     :stroke-join   - :miter, :round, or :bevel (default :miter)
     :stroke-miter  - miter limit (default 4.0)

   High-Level Effects (idiomatic):
     :blur          - number or [sigma mode], e.g. 5.0 or [5.0 :clamp]
     :shadow        - {:dx :dy :blur :color}, e.g. {:dx 2 :dy 2 :blur 3 :color 0x80000000}
     :glow          - {:size :mode}, e.g. {:size 10 :mode :outer}
     :grayscale     - true/false
     :sepia         - true/false
     :brightness    - -1.0 to 1.0
     :contrast      - 0.0 to 2.0
     :gradient      - {:type :linear/:radial/:sweep ...}, see gradients.clj
     :dash          - [on off] or number, e.g. [10 5] or 10
     :blend-mode    - :multiply, :screen, :overlay, etc.

   Low-Level (for advanced users):
     :shader        - Shader instance
     :color-filter  - ColorFilter instance
     :image-filter  - ImageFilter instance
     :mask-filter   - MaskFilter instance
     :path-effect   - PathEffect instance
     :blender       - Blender instance

   Examples:
     ;; Simple filled circle
     (make-paint {:color 0xFF4A90D9})

     ;; Stroked with rounded corners
     (make-paint {:mode :stroke :stroke-width 2 :stroke-join :round})

     ;; With blur effect
     (make-paint {:color 0xFF4A90D9 :blur 5.0})

     ;; With drop shadow
     (make-paint {:color 0xFF4A90D9 :shadow {:dx 2 :dy 2 :blur 3}})

     ;; Dashed line
     (make-paint {:mode :stroke :dash [10 5]})

     ;; Gradient fill
     (make-paint {:gradient {:type :linear :x0 0 :y0 0 :x1 100 :y1 0
                             :colors [0xFFFF0000 0xFF0000FF]}})"
  ([] (make-paint {}))
  ([opts]
   (let [paint (Paint.)
         ;; Separate basic props from effects
         basic-keys #{:color :mode :stroke-width :stroke-cap :stroke-join :stroke-miter
                      :antialias :dither :alpha :alphaf :blend-mode}
         {:keys [color mode stroke-width stroke-cap stroke-join stroke-miter
                 antialias dither alpha alphaf blend-mode]
          :or {color 0xFFFFFFFF
               mode :fill
               stroke-width 1.0
               stroke-cap :butt
               stroke-join :miter
               stroke-miter 4.0
               antialias true
               dither false}} opts]

     ;; Basic properties
     (set-color paint color)
     (set-mode paint mode)
     (set-antialias paint antialias)
     (set-dither paint dither)

     ;; Alpha overrides (if specified)
     (when alpha (set-alpha paint alpha))
     (when alphaf (set-alphaf paint alphaf))

     ;; Stroke properties
     (when (= mode :stroke)
       (set-stroke-width paint stroke-width)
       (set-stroke-cap paint stroke-cap)
       (set-stroke-join paint stroke-join)
       (set-stroke-miter paint stroke-miter))

     ;; Blend mode
     (when blend-mode (set-blend-mode paint blend-mode))

     ;; Apply high-level effects (everything that's not a basic property)
     (doseq [[k v] (apply dissoc opts basic-keys)]
       (apply-effect paint k v))

     paint)))

;; ============================================================
;; Paint Macro
;; ============================================================

(defmacro with-paint
  "Create a Paint with options, execute body, then close Paint.

   Example:
     (with-paint [p {:color 0xFF4A90D9 :mode :fill}]
       (.drawCircle canvas x y r p))"
  [[binding opts] & body]
  `(with-open [~binding (make-paint ~opts)]
     ~@body))

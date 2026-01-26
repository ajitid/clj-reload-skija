(ns lib.graphics.shaders
  "Runtime shader support using SkSL (Skia Shading Language).

   Allows custom GPU shaders for advanced visual effects. SkSL is similar
   to GLSL but with some Skia-specific features.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern).

   ## Quick Start

   ```clojure
   ;; Simple shader (no uniforms)
   (def my-shader
     (shader \"half4 main(float2 coord) {
                return half4(coord.x / 800, coord.y / 600, 0.5, 1.0);
              }\"))

   ;; Use it
   (shapes/circle canvas 100 100 50 {:shader my-shader})
   ```

   ## With Uniforms

   ```clojure
   ;; Create reusable effect (compiled once)
   (def gradient-effect
     (effect
       \"uniform float2 iResolution;
        uniform float iTime;

        half4 main(float2 coord) {
          float2 uv = coord / iResolution;
          return half4(uv.x, uv.y, sin(iTime) * 0.5 + 0.5, 1.0);
        }\"))

   ;; Create shader with current uniform values
   (def my-shader
     (make-shader gradient-effect
       {:iResolution [800.0 600.0]
        :iTime 1.5}))
   ```

   ## Custom Blenders

   ```clojure
   ;; Simple custom blend (one-liner)
   (def my-blend
     (blender \"half4 main(half4 src, half4 dst) { return src * dst; }\"))

   ;; With uniforms
   (def mix-blend
     (blender
       \"uniform float ratio;
        half4 main(half4 src, half4 dst) { return mix(src, dst, ratio); }\"
       {:ratio 0.5}))

   ;; Arithmetic blender (efficient built-in formula)
   (def multiply (arithmetic-blender 1 0 0 0))  ; k1*src*dst + k2*src + k3*dst + k4

   ;; Use with paint
   (draw/rect {:x 0 :y 0 :w 100 :h 100 :fill :red :blender my-blend})
   ```

   ## SkSL Entry Points

   | Effect Type   | Entry Point                      |
   |---------------|----------------------------------|
   | Shader        | `half4 main(float2 fragCoord)`   |
   | Color Filter  | `half4 main(half4 color)`        |
   | Blender       | `half4 main(half4 src, half4 dst)`|"
  (:import [io.github.humbleui.skija RuntimeEffect Data Shader ColorFilter Blender]
           [java.nio ByteBuffer ByteOrder]))

;; ============================================================
;; Effect Compilation
;; ============================================================

(defn effect
  "Compile SkSL source code into a reusable RuntimeEffect.

   Args:
     sksl   - SkSL source code string
     type   - effect type: :shader (default), :color-filter, or :blender

   Returns a RuntimeEffect that can be used to create multiple shader
   instances with different uniform values.

   Throws RuntimeException with Skia's exact error message if compilation fails.

   Examples:
     ;; Simple shader effect
     (effect \"half4 main(float2 c) { return half4(1,0,0,1); }\")

     ;; Color filter effect
     (effect :color-filter
       \"half4 main(half4 color) { return color.bgra; }\")

     ;; Blender effect
     (effect :blender
       \"half4 main(half4 src, half4 dst) { return src * dst; }\")"
  ([sksl]
   (RuntimeEffect/makeForShader sksl))
  ([type sksl]
   (case type
     :shader (RuntimeEffect/makeForShader sksl)
     :color-filter (RuntimeEffect/makeForColorFilter sksl)
     :blender (RuntimeEffect/makeForBlender sksl))))

;; ============================================================
;; Uniform Encoding
;; ============================================================

(defn- get-uniform-size
  "Get the byte size for a uniform based on its type."
  [uniform-info]
  ;; RuntimeEffectUniform provides type info
  ;; For now, we use count * 4 bytes (float size)
  ;; This handles float, float2, float3, float4, etc.
  (* (.getCount uniform-info) 4))

(defn- value-byte-size
  "Get the byte size needed to encode a Clojure value as floats."
  [value]
  (cond
    (number? value) 4
    (vector? value) (* (count value) 4)
    (sequential? value) (* (count (vec value)) 4)
    :else 4))

(defn- encode-value
  "Encode a Clojure value into the ByteBuffer at current position."
  [^ByteBuffer buffer value]
  (cond
    ;; Single number
    (number? value)
    (.putFloat buffer (float value))

    ;; Vector of numbers [x y] or [x y z] or [x y z w]
    (vector? value)
    (doseq [v value]
      (.putFloat buffer (float v)))

    ;; Sequence (lazy seqs, lists)
    (sequential? value)
    (doseq [v value]
      (.putFloat buffer (float v)))

    :else
    (throw (ex-info "Unsupported uniform value type"
                    {:value value :type (type value)}))))

(defn- encode-uniforms
  "Encode uniform values into a Data object based on effect's uniform metadata.

   The effect knows the layout (names, offsets, types) of its uniforms.
   We match by name and encode at the correct offset."
  [^RuntimeEffect effect uniforms-map]
  (if (empty? uniforms-map)
    nil
    (let [uniform-infos (.getUniforms effect)
          ;; Calculate total buffer size from uniform layout + actual values.
          ;; getCount returns array length (1 for non-array), not component count,
          ;; so we use the actual value size when available for correct sizing
          ;; of vector types (float2, float3, float4).
          total-size (reduce (fn [size info]
                               (let [name (.getName info)
                                     value (or (get uniforms-map (keyword name))
                                               (get uniforms-map name))
                                     byte-size (if value
                                                 (value-byte-size value)
                                                 (get-uniform-size info))]
                                 (max size (+ (.getOffset info) byte-size))))
                             0
                             uniform-infos)
          buffer (doto (ByteBuffer/allocate (max 4 total-size))
                   (.order ByteOrder/LITTLE_ENDIAN))]
      ;; Encode each uniform at its correct offset
      (doseq [info uniform-infos]
        (let [uniform-name (.getName info)
              ;; Try both keyword and string keys
              value (or (get uniforms-map (keyword uniform-name))
                        (get uniforms-map uniform-name))]
          (when value
            (.position buffer (.getOffset info))
            (encode-value buffer value))))
      (Data/makeFromBytes (.array buffer)))))

;; ============================================================
;; Shader Creation
;; ============================================================

(defn make-shader
  "Create a Shader from a RuntimeEffect with uniform values.

   Args:
     effect   - RuntimeEffect from (effect ...) or SkSL string
     uniforms - map of uniform name -> value (optional)
     children - vector of child shaders for sampling (optional)

   Uniform values can be:
     - Numbers: {:time 1.5}
     - Vectors: {:resolution [800.0 600.0]}
     - Nested vectors for matrices: {:transform [[1 0] [0 1]]}

   Examples:
     ;; No uniforms
     (make-shader my-effect)

     ;; With uniforms
     (make-shader my-effect {:iTime 1.5 :iResolution [800 600]})

     ;; With child shader for sampling
     (make-shader blur-effect {:radius 5.0} [source-shader])"
  ([effect]
   (make-shader effect {}))
  ([effect uniforms]
   (make-shader effect uniforms nil))
  ([effect uniforms children]
   (let [eff (if (string? effect) (RuntimeEffect/makeForShader effect) effect)
         data (encode-uniforms eff uniforms)
         children-arr (when (seq children)
                        (into-array Shader children))]
     (.makeShader eff data children-arr nil))))

(defn make-color-filter
  "Create a ColorFilter from a RuntimeEffect.

   Args:
     effect   - RuntimeEffect (must be :color-filter type)
     uniforms - map of uniform name -> value (optional)
     children - vector of child color filters (optional)

   Example:
     (def invert-effect
       (effect :color-filter
         \"half4 main(half4 c) { return half4(1-c.r, 1-c.g, 1-c.b, c.a); }\"))

     (def invert-filter (make-color-filter invert-effect))"
  ([effect]
   (make-color-filter effect {}))
  ([effect uniforms]
   (make-color-filter effect uniforms nil))
  ([effect uniforms children]
   (let [data (encode-uniforms effect uniforms)
         children-arr (when (seq children)
                        (into-array ColorFilter children))]
     (.makeColorFilter effect data children-arr))))

(defn make-blender
  "Create a Blender from a RuntimeEffect.

   Args:
     effect   - RuntimeEffect (must be :blender type)
     uniforms - map of uniform name -> value (optional)
     children - vector of child blenders (optional)

   Example:
     (def mix-effect
       (effect :blender
         \"uniform float ratio;
          half4 main(half4 src, half4 dst) { return mix(src, dst, ratio); }\"))

     (def half-blend (make-blender mix-effect {:ratio 0.5}))"
  ([effect]
   (make-blender effect {}))
  ([effect uniforms]
   (make-blender effect uniforms nil))
  ([effect uniforms children]
   (let [data (encode-uniforms effect uniforms)
         children-arr (when (seq children)
                        (into-array Blender children))]
     (.makeBlender effect data children-arr))))

;; ============================================================
;; Convenience: One-liner shader from string
;; ============================================================

(defn shader
  "Create a shader directly from SkSL source code.

   This is a convenience function that compiles and creates in one step.
   For shaders used multiple times with different uniforms, prefer
   (effect ...) + (make-shader ...) to compile only once.

   Args:
     sksl     - SkSL source code string
     uniforms - map of uniform name -> value (optional)

   Examples:
     ;; Simple gradient
     (shader \"half4 main(float2 c) {
                return half4(c.x/800, c.y/600, 0.5, 1);
              }\")

     ;; With uniforms
     (shader
       \"uniform float2 res;
        half4 main(float2 c) { return half4(c/res, 0.5, 1); }\"
       {:res [800 600]})"
  ([sksl]
   (make-shader (effect sksl)))
  ([sksl uniforms]
   (make-shader (effect sksl) uniforms)))

(defn color-filter
  "Create a color filter directly from SkSL source code.

   Example:
     (color-filter
       \"half4 main(half4 c) { return c.bgra; }\")"
  ([sksl]
   (make-color-filter (effect :color-filter sksl)))
  ([sksl uniforms]
   (make-color-filter (effect :color-filter sksl) uniforms)))

(defn blender
  "Create a blender directly from SkSL source code.

   This is a convenience function that compiles and creates in one step.
   For blenders used multiple times with different uniforms, prefer
   (effect :blender ...) + (make-blender ...) to compile only once.

   Args:
     sksl     - SkSL source code string
     uniforms - map of uniform name -> value (optional)

   Examples:
     ;; Custom 50% blend
     (blender
       \"half4 main(half4 src, half4 dst) { return mix(src, dst, 0.5); }\")

     ;; Parameterized blend ratio
     (blender
       \"uniform float ratio;
        half4 main(half4 src, half4 dst) { return mix(src, dst, ratio); }\"
       {:ratio 0.3})"
  ([sksl]
   (make-blender (effect :blender sksl)))
  ([sksl uniforms]
   (make-blender (effect :blender sksl) uniforms)))

(defn arithmetic-blender
  "Create an arithmetic blender using Skia's built-in formula:
   result = k1 * src * dst + k2 * src + k3 * dst + k4

   This is more efficient than an equivalent SkSL blender.

   Args:
     k1, k2, k3, k4  - coefficients for the formula
     enforce-premul? - if true, clamps RGB to calculated alpha (default true)

   Examples:
     ;; Multiply: src * dst
     (arithmetic-blender 1 0 0 0)

     ;; Screen: src + dst - src*dst
     (arithmetic-blender -1 1 1 0)

     ;; Average: (src + dst) / 2
     (arithmetic-blender 0 0.5 0.5 0)"
  ([k1 k2 k3 k4]
   (arithmetic-blender k1 k2 k3 k4 true))
  ([k1 k2 k3 k4 enforce-premul?]
   (Blender/makeArithmetic (float k1) (float k2) (float k3) (float k4) enforce-premul?)))

;; ============================================================
;; Common Shader Patterns (Ready to Use)
;; ============================================================

(defn noise-shader
  "Create a simple noise shader.

   Args:
     scale - noise scale (default 1.0)
     seed  - random seed (default 0.0)"
  ([] (noise-shader 1.0 0.0))
  ([scale] (noise-shader scale 0.0))
  ([scale seed]
   (shader
     "uniform float uScale;
      uniform float uSeed;

      float hash(float2 p) {
        return fract(sin(dot(p + uSeed, float2(127.1, 311.7))) * 43758.5453);
      }

      half4 main(float2 coord) {
        float n = hash(floor(coord * uScale));
        return half4(n, n, n, 1.0);
      }"
     {:uScale scale :uSeed seed})))

(defn gradient-shader
  "Create a UV gradient shader (useful for debugging/visualization).

   Maps x -> red, y -> green."
  [width height]
  (shader
    "uniform float2 iResolution;

     half4 main(float2 coord) {
       float2 uv = coord / iResolution;
       return half4(uv.x, uv.y, 0.5, 1.0);
     }"
    {:iResolution [width height]}))

(defn animated-shader
  "Create an animated wave shader.

   Args:
     width, height - dimensions for UV calculation
     time          - animation time in seconds
     speed         - wave speed (default 1.0)
     frequency     - wave frequency (default 10.0)"
  ([width height time]
   (animated-shader width height time 1.0 10.0))
  ([width height time speed frequency]
   (shader
     "uniform float2 iResolution;
      uniform float iTime;
      uniform float uSpeed;
      uniform float uFrequency;

      half4 main(float2 coord) {
        float2 uv = coord / iResolution;
        float wave = sin(uv.x * uFrequency + iTime * uSpeed) * 0.5 + 0.5;
        return half4(uv.x, wave, uv.y, 1.0);
      }"
     {:iResolution [width height]
      :iTime time
      :uSpeed speed
      :uFrequency frequency})))

;; ============================================================
;; 2D Pattern Shaders (GPU-accelerated)
;; ============================================================

(defn- argb->premul-float4
  "Convert 0xAARRGGBB color integer to premultiplied [r g b a] for SkSL uniforms."
  [color]
  (let [c (unchecked-int color)
        a (/ (double (bit-and (unsigned-bit-shift-right c 24) 0xFF)) 255.0)
        r (* (/ (double (bit-and (unsigned-bit-shift-right c 16) 0xFF)) 255.0) a)
        g (* (/ (double (bit-and (unsigned-bit-shift-right c 8) 0xFF)) 255.0) a)
        b (* (/ (double (bit-and c 0xFF)) 255.0) a)]
    [r g b a]))

;; Pre-compiled effects (SkSL compiled once at load time)

(def ^:private hatch-effect
  (effect
    "uniform float uLineWidth;
     uniform float uSpacing;
     uniform float uAngle;
     uniform float4 uColor;

     half4 main(float2 coord) {
       float cs = cos(uAngle);
       float sn = sin(uAngle);
       float p = coord.x * sn - coord.y * cs;
       float d = mod(p, uSpacing);
       float dist = min(d, uSpacing - d);
       float hw = uLineWidth * 0.5;
       float a = smoothstep(hw + 0.5, hw - 0.5, dist);
       return half4(uColor * a);
     }"))

(def ^:private grid-effect
  (effect
    "uniform float uLineWidth;
     uniform float uSpacingX;
     uniform float uSpacingY;
     uniform float4 uColor;

     half4 main(float2 coord) {
       float dx = mod(coord.x, uSpacingX);
       float dy = mod(coord.y, uSpacingY);
       float distX = min(dx, uSpacingX - dx);
       float distY = min(dy, uSpacingY - dy);
       float dist = min(distX, distY);
       float hw = uLineWidth * 0.5;
       float a = smoothstep(hw + 0.5, hw - 0.5, dist);
       return half4(uColor * a);
     }"))

(def ^:private dot-effect
  (effect
    "uniform float uRadius;
     uniform float uSpacingX;
     uniform float uSpacingY;
     uniform float4 uColor;

     half4 main(float2 coord) {
       float2 cell = float2(mod(coord.x, uSpacingX), mod(coord.y, uSpacingY));
       float2 center = float2(uSpacingX * 0.5, uSpacingY * 0.5);
       float dist = length(cell - center);
       float a = smoothstep(uRadius + 0.5, uRadius - 0.5, dist);
       return half4(uColor * a);
     }"))

(def ^:private cross-hatch-effect
  (effect
    "uniform float uLineWidth;
     uniform float uSpacing;
     uniform float uAngle1;
     uniform float uAngle2;
     uniform float4 uColor;

     half4 main(float2 coord) {
       float hw = uLineWidth * 0.5;
       float p1 = coord.x * sin(uAngle1) - coord.y * cos(uAngle1);
       float d1 = mod(p1, uSpacing);
       float a1 = smoothstep(hw + 0.5, hw - 0.5, min(d1, uSpacing - d1));
       float p2 = coord.x * sin(uAngle2) - coord.y * cos(uAngle2);
       float d2 = mod(p2, uSpacing);
       float a2 = smoothstep(hw + 0.5, hw - 0.5, min(d2, uSpacing - d2));
       float a = max(a1, a2);
       return half4(uColor * a);
     }"))

(defn hatch-shader
  "Create a parallel line hatching shader.

   Args:
     line-width - width of each line in pixels
     spacing    - distance between line centers in pixels
     opts       - optional map:
                  :angle  - line rotation in radians (default 0, horizontal)
                  :color  - 0xAARRGGBB integer (default 0xFFFFFFFF)

   Examples:
     (hatch-shader 2 10)                            ;; horizontal white lines
     (hatch-shader 2 10 {:angle (/ Math/PI 4)})     ;; 45° diagonal
     (hatch-shader 3 15 {:color 0xFF4A90D9})"
  ([line-width spacing]
   (hatch-shader line-width spacing nil))
  ([line-width spacing opts]
   (let [{:keys [angle color] :or {angle 0.0 color 0xFFFFFFFF}} opts]
     (make-shader hatch-effect
       {:uLineWidth (double line-width)
        :uSpacing   (double spacing)
        :uAngle     (double angle)
        :uColor     (argb->premul-float4 color)}))))

(defn grid-shader
  "Create a grid pattern shader.

   Args:
     line-width - width of grid lines in pixels
     spacing-x  - horizontal cell spacing in pixels
     spacing-y  - vertical cell spacing in pixels
     opts       - optional map:
                  :color - 0xAARRGGBB integer (default 0xFFFFFFFF)

   Examples:
     (grid-shader 1 20 20)                        ;; white square grid
     (grid-shader 2 30 15 {:color 0xFF9B59B6})"
  ([line-width spacing-x spacing-y]
   (grid-shader line-width spacing-x spacing-y nil))
  ([line-width spacing-x spacing-y opts]
   (let [{:keys [color] :or {color 0xFFFFFFFF}} opts]
     (make-shader grid-effect
       {:uLineWidth (double line-width)
        :uSpacingX  (double spacing-x)
        :uSpacingY  (double spacing-y)
        :uColor     (argb->premul-float4 color)}))))

(defn dot-pattern-shader
  "Create a repeating dot pattern shader.

   Args:
     dot-radius - radius of each dot in pixels
     spacing-x  - horizontal spacing between dot centers in pixels
     spacing-y  - vertical spacing between dot centers in pixels
     opts       - optional map:
                  :color - 0xAARRGGBB integer (default 0xFFFFFFFF)

   Examples:
     (dot-pattern-shader 3 15 15)                        ;; white dots
     (dot-pattern-shader 5 20 20 {:color 0xFFE67E22})"
  ([dot-radius spacing-x spacing-y]
   (dot-pattern-shader dot-radius spacing-x spacing-y nil))
  ([dot-radius spacing-x spacing-y opts]
   (let [{:keys [color] :or {color 0xFFFFFFFF}} opts]
     (make-shader dot-effect
       {:uRadius   (double dot-radius)
        :uSpacingX (double spacing-x)
        :uSpacingY (double spacing-y)
        :uColor    (argb->premul-float4 color)}))))

(defn cross-hatch-shader
  "Create a cross-hatching shader (two overlaid line sets).

   Args:
     line-width - width of each line in pixels
     spacing    - distance between line centers in pixels
     opts       - optional map:
                  :angle1 - first line set angle in radians (default π/4)
                  :angle2 - second line set angle in radians (default -π/4)
                  :color  - 0xAARRGGBB integer (default 0xFFFFFFFF)

   Examples:
     (cross-hatch-shader 2 12)                                ;; default 45°/-45°
     (cross-hatch-shader 1.5 10 {:color 0xFFE74C3C})"
  ([line-width spacing]
   (cross-hatch-shader line-width spacing nil))
  ([line-width spacing opts]
   (let [pi4 (/ Math/PI 4.0)
         {:keys [angle1 angle2 color]
          :or {angle1 pi4 angle2 (- pi4) color 0xFFFFFFFF}} opts]
     (make-shader cross-hatch-effect
       {:uLineWidth (double line-width)
        :uSpacing   (double spacing)
        :uAngle1    (double angle1)
        :uAngle2    (double angle2)
        :uColor     (argb->premul-float4 color)}))))

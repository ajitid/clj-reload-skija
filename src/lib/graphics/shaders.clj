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

   ## SkSL Entry Points

   | Effect Type   | Entry Point                      |
   |---------------|----------------------------------|
   | Shader        | `half4 main(float2 fragCoord)`   |
   | Color Filter  | `half4 main(half4 color)`        |
   | Blender       | `half4 main(half4 src, half4 dst)`|"
  (:import [io.github.humbleui.skija RuntimeEffect Data Shader ColorFilter]
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
          ;; Calculate total buffer size from uniform layout
          total-size (reduce (fn [size info]
                               (max size (+ (.getOffset info)
                                            (get-uniform-size info))))
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

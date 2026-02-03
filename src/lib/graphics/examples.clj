(ns lib.graphics.examples
  "Usage examples for the graphics library.

   DESIGN PHILOSOPHY (Following Love2D and Skija's principles):

   1. IDIOMATIC CLOJURE - Use maps, keywords, and simple data structures
   2. HIDE IMPLEMENTATION - No raw Java objects in user code
   3. DECLARATIVE - Describe what you want, not how to build it
   4. COMPOSABLE - Effects combine naturally with simple map merging

   Compare:

   BAD (Exposing Skija internals):
   (shapes/circle canvas 100 100 50
     {:image-filter (ImageFilter/makeBlur 5.0 5.0 FilterTileMode/DECAL)})

   GOOD (Idiomatic Clojure):
   (shapes/circle canvas 100 100 50 {:blur 5.0})

   This file demonstrates the GOOD way!
   NOT MEANT TO BE EXECUTED - just documentation/reference."
  (:require [lib.graphics.state :as gfx]
            [lib.graphics.shapes :as shapes]
            [lib.graphics.image :as image]
            [lib.text.core :as text]
            [lib.text.measure :as text-measure]
            [lib.graphics.batch :as batch]
            [lib.graphics.filters :as filters]
            [lib.graphics.gradients :as gradients]
            [lib.graphics.shaders :as shaders]
            [lib.graphics.shadows :as shadows]
            [lib.graphics.path :as path]))

;; ============================================================
;; Basic Shapes
;; ============================================================

(comment
  ;; Simple filled circle
  (shapes/circle canvas 100 100 50 {:color [0.29 0.56 0.85 1.0]})

  ;; Stroked circle with custom width
  (shapes/circle canvas 100 100 50
                {:mode :stroke
                 :stroke-width 3
                 :color [1.0 0.0 0.0 1.0]})

  ;; Rectangle
  (shapes/rectangle canvas 10 10 100 50 {:color [0.0 1.0 0.0 1.0]})

  ;; Rounded rectangle
  (shapes/rounded-rect canvas 10 10 100 50 10 {:color [1.0 1.0 0.0 1.0]})

  ;; Line with rounded caps
  (shapes/line canvas 0 0 100 100
              {:stroke-width 5
               :stroke-cap :round
               :color [0.0 0.0 0.0 1.0]}))

;; ============================================================
;; Text Rendering
;; ============================================================

(comment
  ;; Simple text
  (text/text canvas "Hello World" 10 50)

  ;; Colored text with custom size
  (text/text canvas "Hello" 10 50
                {:size 24
                 :color [1.0 0.0 0.0 1.0]})

  ;; Measure text width
  (text-measure/text-width "Hello" {:size 24}))

;; ============================================================
;; Batch Drawing (High Performance)
;; ============================================================

(comment
  ;; Draw many points efficiently
  (batch/points canvas [{:x 100 :y 100} {:x 200 :y 200}] 5
               {:color [0.29 0.56 0.85 1.0]})

  ;; Draw line segments
  (batch/lines canvas (float-array [0 0 100 100
                                    100 100 200 50])
              {:stroke-width 2 :color [1.0 0.0 0.0 1.0]}))

;; ============================================================
;; Advanced Paint Effects (Idiomatic Clojure/Love2D Style)
;; ============================================================

(comment
  ;; Blur effect
  (shapes/circle canvas 100 100 50
                {:color [0.29 0.56 0.85 1.0]
                 :blur 5.0})

  ;; Drop shadow (simple map)
  (shapes/circle canvas 100 100 50
                {:color [0.29 0.56 0.85 1.0]
                 :shadow {:dx 3 :dy 3 :blur 5}})

  ;; Outer glow
  (shapes/circle canvas 100 100 50
                {:color [0.29 0.56 0.85 1.0]
                 :glow {:size 10 :mode :outer}})

  ;; Dashed line (simple vector)
  (shapes/line canvas 0 0 100 100
              {:mode :stroke
               :stroke-width 3
               :dash [10 5]})

  ;; Multiply blend mode
  (shapes/circle canvas 100 100 50
                {:color [0.29 0.56 0.85 1.0]
                 :blend-mode :multiply})

  ;; Semi-transparent with alpha
  (shapes/circle canvas 100 100 50
                {:color [0.29 0.56 0.85 0.5]}))

;; ============================================================
;; Color Filters (Idiomatic - Just Use Keywords/Numbers)
;; ============================================================

(comment
  ;; Grayscale
  (shapes/circle canvas 100 100 50
                {:color [0.29 0.56 0.85 1.0]
                 :grayscale true})

  ;; Sepia tone
  (shapes/circle canvas 100 100 50
                {:color [0.29 0.56 0.85 1.0]
                 :sepia true})

  ;; Brightness adjustment
  (shapes/circle canvas 100 100 50
                {:color [0.29 0.56 0.85 1.0]
                 :brightness 0.3})

  ;; Contrast adjustment
  (shapes/circle canvas 100 100 50
                {:color [0.29 0.56 0.85 1.0]
                 :contrast 1.5}))

;; ============================================================
;; Gradients (Idiomatic - Just Use Maps)
;; ============================================================

(comment
  ;; Linear gradient - simple map
  (shapes/circle canvas 100 100 50
                {:gradient {:type :linear
                            :x0 50 :y0 50
                            :x1 150 :y1 150
                            :colors [[1 0 0 1] [0 0 1 1]]}})

  ;; Radial gradient with custom stops
  (shapes/circle canvas 100 100 50
                {:gradient {:type :radial
                            :cx 100 :cy 100
                            :radius 50
                            :colors [[1 1 1 1] [0.29 0.56 0.85 1] [0 0 0 1]]
                            :stops [0.0 0.5 1.0]}})

  ;; Sweep gradient (color wheel)
  (shapes/circle canvas 100 100 50
                {:gradient {:type :sweep
                            :cx 100 :cy 100
                            :colors [[1 0 0 1] [1 1 0 1] [0 1 0 1]
                                     [0 1 1 1] [0 0 1 1] [1 0 1 1]
                                     [1 0 0 1]]}})

  ;; Repeating gradient
  (shapes/rectangle canvas 0 0 200 200
                   {:gradient {:type :linear
                               :x0 0 :y0 0
                               :x1 20 :y1 0
                               :colors [[0 0 0 1] [1 1 1 1]]
                               :tile-mode :repeat}}))

;; ============================================================
;; Complex Examples (Show How Clean the API Is!)
;; ============================================================

(comment
  ;; Glowing text with drop shadow - just use :shadow!
  (text/text canvas "GLOW" 50 100
                {:size 48
                 :color [0 1 1 1]
                 :shadow {:dx 0 :dy 0 :blur 10 :color [0 1 1 1]}})

  ;; Neon effect - just use :glow!
  (shapes/circle canvas 100 100 50
                {:color [0 1 1 1]
                 :glow {:size 15 :mode :outer}
                 :blend-mode :screen})

  ;; Gradient stroke with rounded joins - just use :gradient map!
  (shapes/rectangle canvas 50 50 100 100
                   {:mode :stroke
                    :stroke-width 10
                    :stroke-join :round
                    :gradient {:type :linear
                               :x0 0 :y0 0
                               :x1 200 :y1 200
                               :colors [[1 0 0 1] [0 1 0 1] [0 0 1 1]]}})

  ;; Glass morphism - just use :blur number!
  (shapes/rounded-rect canvas 50 50 200 100 20
                      {:color [1 1 1 0.25]
                       :blend-mode :screen
                       :blur 10.0})

  ;; Multiple effects combined
  (shapes/circle canvas 100 100 50
                {:gradient {:type :radial
                            :cx 100 :cy 100
                            :radius 50
                            :colors [[1 0 1 1] [0 1 1 1]]}
                 :shadow {:dx 5 :dy 5 :blur 10 :color [0 0 0 0.5]}
                 :blend-mode :screen}))

;; ============================================================
;; Custom SkSL Shaders (GPU Programmable Effects)
;; ============================================================

(comment
  ;; Simple inline shader - UV gradient
  (shapes/circle canvas 100 100 50
                {:sksl "half4 main(float2 coord) {
                          return half4(coord.x / 800, coord.y / 600, 0.5, 1.0);
                        }"})

  ;; Shader with uniforms - animated wave
  (shapes/rectangle canvas 0 0 800 600
                   {:sksl {:source "uniform float2 iResolution;
                                    uniform float iTime;

                                    half4 main(float2 coord) {
                                      float2 uv = coord / iResolution;
                                      float wave = sin(uv.x * 10.0 + iTime) * 0.5 + 0.5;
                                      return half4(uv.x, wave, uv.y, 1.0);
                                    }"
                           :uniforms {:iResolution [800 600]
                                      :iTime @game-time}}})

  ;; Pre-compiled effect for better performance
  ;; (compile once, reuse with different uniforms)
  (def plasma-effect
    (shaders/effect
      "uniform float2 iResolution;
       uniform float iTime;

       half4 main(float2 coord) {
         float2 uv = coord / iResolution - 0.5;
         float t = iTime * 0.5;

         float v = sin(uv.x * 10.0 + t);
         v += sin((uv.y * 10.0 + t) * 0.5);
         v += sin((uv.x * 10.0 + uv.y * 10.0 + t) * 0.5);

         float3 col = float3(sin(v), sin(v + 2.0), sin(v + 4.0)) * 0.5 + 0.5;
         return half4(col, 1.0);
       }"))

  ;; Use the pre-compiled effect
  (shapes/rectangle canvas 0 0 800 600
                   {:shader (shaders/make-shader plasma-effect
                              {:iResolution [800 600]
                               :iTime @game-time})})

  ;; Color filter shader (works on existing colors)
  (def invert-filter
    (shaders/color-filter
      "half4 main(half4 color) {
         return half4(1.0 - color.rgb, color.a);
       }"))

  ;; Built-in shader helpers
  (shapes/rectangle canvas 0 0 800 600
                   {:shader (shaders/noise-shader 0.1)})

  (shapes/rectangle canvas 0 0 800 600
                   {:shader (shaders/gradient-shader 800 600)})

  (shapes/rectangle canvas 0 0 800 600
                   {:shader (shaders/animated-shader 800 600 @game-time)}))

;; ============================================================
;; Inner Shadow & Box Shadow (CSS-Style)
;; ============================================================

(comment
  ;; Inner shadow - shadow inside the shape's edges
  (shapes/rounded-rect canvas 10 10 200 100 10
                       {:color [0.2 0.2 0.3 1.0]
                        :inner-shadow {:dx 2 :dy 2 :blur 5 :color [0 0 0 0.6]}})

  ;; CSS-style box shadow with spread
  (shapes/circle canvas 100 100 50
                {:color [0.29 0.56 0.85 1.0]
                 :box-shadow {:dx 4 :dy 4 :blur 10 :spread 2 :color [0 0 0 0.4]}})

  ;; Inset box shadow (same as inner shadow)
  (shapes/rounded-rect canvas 10 10 200 100 10
                       {:color [0.8 0.8 0.8 1.0]
                        :box-shadow {:dx 2 :dy 2 :blur 6 :spread 1
                                     :color [0 0 0 0.5] :inset true}}))

;; ============================================================
;; Morphology (Dilate / Erode)
;; ============================================================

(comment
  ;; Dilate - thicken shapes
  (shapes/circle canvas 100 100 50
                {:color [1 0 0 1] :dilate 3})

  ;; Erode - thin shapes
  (shapes/circle canvas 100 100 50
                {:color [0 0 1 1] :erode 2})

  ;; Asymmetric morphology
  (shapes/rectangle canvas 10 10 100 50
                   {:color [0 1 0 1] :dilate [5 2]}))

;; ============================================================
;; Emboss & High Contrast
;; ============================================================

(comment
  ;; Emboss with default settings (azimuth 135, elevation 45)
  (shapes/rectangle canvas 10 10 100 50
                   {:color [0.5 0.5 0.5 1] :emboss true})

  ;; Emboss with custom light direction
  (shapes/rectangle canvas 10 10 100 50
                   {:color [0.5 0.5 0.5 1]
                    :emboss {:azimuth 225 :elevation 30}})

  ;; High contrast (accessibility)
  (shapes/circle canvas 100 100 50
                {:color [0.29 0.56 0.85 1.0]
                 :high-contrast {:grayscale true :contrast 0.5}}))

;; ============================================================
;; Filter Composition (Low-Level Power)
;; ============================================================

(comment
  ;; Compose two filters: blur then offset
  (shapes/circle canvas 100 100 50
                {:color [1 0 0 1]
                 :image-filter (filters/compose (filters/offset 5 5)
                                                (filters/blur 3.0))})

  ;; Merge multiple filters (all visible)
  (shapes/circle canvas 100 100 50
                {:color [0 1 0 1]
                 :image-filter (filters/merge-filters
                                 (filters/drop-shadow 3 3 5 [0 0 0 0.5])
                                 nil)})

  ;; Blend two filter results with multiply mode
  (shapes/circle canvas 100 100 50
                {:color [1 1 1 1]
                 :image-filter (filters/blend-filter :multiply
                                 (filters/blur 2.0)
                                 nil)}))

;; ============================================================
;; Convolution Kernels
;; ============================================================

(comment
  ;; Sharpen
  (shapes/rectangle canvas 10 10 200 200
                   {:color [0.5 0.5 0.5 1]
                    :image-filter (filters/matrix-convolution
                                    filters/sharpen-kernel
                                    {:width 3 :height 3})})

  ;; Edge detection
  (shapes/rectangle canvas 10 10 200 200
                   {:color [0.5 0.5 0.5 1]
                    :image-filter (filters/matrix-convolution
                                    filters/edge-detect-kernel
                                    {:width 3 :height 3})})

  ;; Emboss via convolution kernel
  (shapes/rectangle canvas 10 10 200 200
                   {:color [0.5 0.5 0.5 1]
                    :image-filter (filters/matrix-convolution
                                    filters/emboss-kernel
                                    {:width 3 :height 3 :gain 1.0 :bias 0.5})}))

;; ============================================================
;; Lighting Effects
;; ============================================================

(comment
  ;; Distant diffuse light (sunlight)
  (shapes/rectangle canvas 10 10 200 100
                   {:color [0.5 0.5 0.5 1]
                    :image-filter (filters/light
                                    {:source :distant :type :diffuse
                                     :azimuth 135 :elevation 45
                                     :color [1 1 1 1] :kd 1.0})})

  ;; Point specular light (nearby spotlight)
  (shapes/rectangle canvas 10 10 200 100
                   {:color [0.5 0.5 0.5 1]
                    :image-filter (filters/light
                                    {:source :point :type :specular
                                     :x 100 :y 50 :z 100
                                     :color [1 1 0.9 1]
                                     :ks 0.8 :shininess 12.0})})

  ;; Spot light (cone of light)
  (shapes/circle canvas 150 150 80
                {:color [0.4 0.4 0.4 1]
                 :image-filter (filters/light
                                 {:source :spot :type :diffuse
                                  :from [150 0 200] :to [150 150 0]
                                  :falloff 2.0 :cutoff-angle 45
                                  :color [1 1 1 1]})}))

;; ============================================================
;; Material Design Shadows (Canvas-Level)
;; ============================================================

(comment
  ;; Material shadow on a rounded rectangle path
  (let [p (path/rrect 20 20 200 100 12)]
    ;; Draw shadow first (behind the shape)
    (shadows/draw-shadow canvas p {:z-height 8
                                   :spot [0 0 0 0.4]})
    ;; Then draw the shape on top
    (shapes/path canvas p {:color [1 1 1 1]}))

  ;; High elevation shadow
  (let [p (path/circle 150 150 60)]
    (shadows/draw-shadow canvas p {:z-height 24
                                   :light-pos [200 0 800]
                                   :ambient [0 0 0 0.15]
                                   :spot [0 0 0 0.35]})
    (shapes/path canvas p {:color [0.95 0.95 0.97 1]}))

  ;; Transparent occluder (shadow visible through shape)
  (let [p (path/rect 50 50 160 80)]
    (shadows/draw-shadow canvas p {:z-height 6
                                   :flags #{:transparent}
                                   :spot [0 0 0.2 0.3]})))

;; ============================================================
;; Sprite Atlas & Image Drawing (Love2D Style)
;; ============================================================
;; NOTE: Skija doesn't expose Skia's native drawAtlas, so we use
;; drawImageRect under the hood. The API follows Love2D conventions.

(comment
  (require '[lib.graphics.image :as image])

  ;; Load a sprite sheet
  (def sheet (image/file->image "assets/sprites.png"))

  ;; Load from bytes (e.g., downloaded content)
  (def img (image/bytes->image byte-array))

  ;; Define quads (sprite regions within the sheet)
  (def player-idle (image/quad 0 0 32 32))
  (def player-walk (image/quad 32 0 32 32))
  (def player-jump (image/quad 64 0 32 32))

  ;; Create a grid of quads from a regular sprite sheet
  ;; (4 columns, 2 rows of 32x32 sprites)
  (def all-sprites (image/quad-grid 32 32 4 2))

  ;; Simple sprite drawing
  (image/draw canvas sheet player-idle 100 100)

  ;; Draw with rotation (radians), centered on sprite
  (image/draw canvas sheet player-idle 100 100
              {:rotation (/ Math/PI 4)
               :origin [16 16]})

  ;; Draw with scale (uniform or [sx sy])
  (image/draw canvas sheet player-idle 100 100
              {:scale 2.0})
  (image/draw canvas sheet player-idle 100 100
              {:scale [2.0 1.5]})

  ;; Draw flipped (useful for character facing direction)
  (image/draw canvas sheet player-walk 100 100
              {:flip-x true
               :origin [16 16]})

  ;; Semi-transparent sprite
  (image/draw canvas sheet player-idle 100 100
              {:alpha 0.5})

  ;; Combined transforms
  (image/draw canvas sheet player-jump 100 100
              {:rotation 0.3
               :scale 1.5
               :origin [16 16]
               :alpha 0.8})

  ;; Draw entire image (no quad)
  (image/draw-image canvas logo 10 10)
  (image/draw-image canvas logo 10 10 {:scale 0.5})

  ;; Animation helper - get current frame based on time
  (def walk-cycle (image/quad-grid 32 32 8))  ; 8-frame animation
  (let [frame (image/animation-frame walk-cycle @game-time 12)] ; 12 FPS
    (image/draw canvas sheet frame player-x player-y))

  ;; Batch drawing - draw many sprites efficiently
  (image/draw-batch canvas sheet
                    [[player-idle 100 100]
                     [player-walk 200 100 {:flip-x true}]
                     [player-jump 300 100 {:rotation 0.5}]])

  ;; RSXform helpers (for advanced/future use)
  ;; RSXform encodes rotation+scale as a 2x3 matrix
  (def xform (image/rsxform-from-radians
               1.0      ; scale
               0.5      ; rotation in radians
               100 100  ; position
               16 16))  ; anchor point
  )

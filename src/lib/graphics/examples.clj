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
            [lib.graphics.atlas :as atlas]
            [lib.text.core :as text]
            [lib.text.measure :as text-measure]
            [lib.graphics.batch :as batch]
            [lib.graphics.filters :as filters]
            [lib.graphics.gradients :as gradients]
            [lib.graphics.shaders :as shaders]))

;; ============================================================
;; Basic Shapes
;; ============================================================

(comment
  ;; Simple filled circle
  (shapes/circle canvas 100 100 50 {:color 0xFF4A90D9})

  ;; Stroked circle with custom width
  (shapes/circle canvas 100 100 50
                {:mode :stroke
                 :stroke-width 3
                 :color 0xFFFF0000})

  ;; Rectangle
  (shapes/rectangle canvas 10 10 100 50 {:color 0xFF00FF00})

  ;; Rounded rectangle
  (shapes/rounded-rect canvas 10 10 100 50 10 {:color 0xFFFFFF00})

  ;; Line with rounded caps
  (shapes/line canvas 0 0 100 100
              {:stroke-width 5
               :stroke-cap :round
               :color 0xFF000000}))

;; ============================================================
;; Text Rendering
;; ============================================================

(comment
  ;; Simple text
  (text/text canvas "Hello World" 10 50)

  ;; Colored text with custom size
  (text/text canvas "Hello" 10 50
                {:size 24
                 :color 0xFFFF0000})

  ;; Measure text width
  (text-measure/text-width "Hello" {:size 24}))

;; ============================================================
;; Batch Drawing (High Performance)
;; ============================================================

(comment
  ;; Draw many points efficiently
  (batch/points canvas [{:x 100 :y 100} {:x 200 :y 200}] 5
               {:color 0xFF4A90D9})

  ;; Draw line segments
  (batch/lines canvas (float-array [0 0 100 100
                                    100 100 200 50])
              {:stroke-width 2 :color 0xFFFF0000}))

;; ============================================================
;; Advanced Paint Effects (Idiomatic Clojure/Love2D Style)
;; ============================================================

(comment
  ;; Blur effect
  (shapes/circle canvas 100 100 50
                {:color 0xFF4A90D9
                 :blur 5.0})

  ;; Drop shadow (simple map)
  (shapes/circle canvas 100 100 50
                {:color 0xFF4A90D9
                 :shadow {:dx 3 :dy 3 :blur 5}})

  ;; Outer glow
  (shapes/circle canvas 100 100 50
                {:color 0xFF4A90D9
                 :glow {:size 10 :mode :outer}})

  ;; Dashed line (simple vector)
  (shapes/line canvas 0 0 100 100
              {:mode :stroke
               :stroke-width 3
               :dash [10 5]})

  ;; Multiply blend mode
  (shapes/circle canvas 100 100 50
                {:color 0xFF4A90D9
                 :blend-mode :multiply})

  ;; Semi-transparent with alpha
  (shapes/circle canvas 100 100 50
                {:color 0xFF4A90D9
                 :alphaf 0.5}))

;; ============================================================
;; Color Filters (Idiomatic - Just Use Keywords/Numbers)
;; ============================================================

(comment
  ;; Grayscale
  (shapes/circle canvas 100 100 50
                {:color 0xFF4A90D9
                 :grayscale true})

  ;; Sepia tone
  (shapes/circle canvas 100 100 50
                {:color 0xFF4A90D9
                 :sepia true})

  ;; Brightness adjustment
  (shapes/circle canvas 100 100 50
                {:color 0xFF4A90D9
                 :brightness 0.3})

  ;; Contrast adjustment
  (shapes/circle canvas 100 100 50
                {:color 0xFF4A90D9
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
                            :colors [0xFFFF0000 0xFF0000FF]}})

  ;; Radial gradient with custom stops
  (shapes/circle canvas 100 100 50
                {:gradient {:type :radial
                            :cx 100 :cy 100
                            :radius 50
                            :colors [0xFFFFFFFF 0xFF4A90D9 0xFF000000]
                            :stops [0.0 0.5 1.0]}})

  ;; Sweep gradient (color wheel)
  (shapes/circle canvas 100 100 50
                {:gradient {:type :sweep
                            :cx 100 :cy 100
                            :colors [0xFFFF0000 0xFFFFFF00 0xFF00FF00
                                     0xFF00FFFF 0xFF0000FF 0xFFFF00FF
                                     0xFFFF0000]}})

  ;; Repeating gradient
  (shapes/rectangle canvas 0 0 200 200
                   {:gradient {:type :linear
                               :x0 0 :y0 0
                               :x1 20 :y1 0
                               :colors [0xFF000000 0xFFFFFFFF]
                               :tile-mode :repeat}}))

;; ============================================================
;; Complex Examples (Show How Clean the API Is!)
;; ============================================================

(comment
  ;; Glowing text with drop shadow - just use :shadow!
  (text/text canvas "GLOW" 50 100
                {:size 48
                 :color 0xFF00FFFF
                 :shadow {:dx 0 :dy 0 :blur 10 :color 0xFF00FFFF}})

  ;; Neon effect - just use :glow!
  (shapes/circle canvas 100 100 50
                {:color 0xFF00FFFF
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
                               :colors [0xFFFF0000 0xFF00FF00 0xFF0000FF]}})

  ;; Glass morphism - just use :blur number!
  (shapes/rounded-rect canvas 50 50 200 100 20
                      {:color 0x40FFFFFF
                       :blend-mode :screen
                       :blur 10.0})

  ;; Multiple effects combined
  (shapes/circle canvas 100 100 50
                {:gradient {:type :radial
                            :cx 100 :cy 100
                            :radius 50
                            :colors [0xFFFF00FF 0xFF00FFFF]}
                 :shadow {:dx 5 :dy 5 :blur 10 :color 0x80000000}
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
;; Sprite Atlas & Image Drawing (Love2D Style)
;; ============================================================
;; NOTE: Skija doesn't expose Skia's native drawAtlas, so we use
;; drawImageRect under the hood. The API follows Love2D conventions.

(comment
  (require '[lib.graphics.atlas :as atlas])

  ;; Load a sprite sheet
  (def sheet (atlas/load-image "assets/sprites.png"))

  ;; Define quads (sprite regions within the sheet)
  (def player-idle (atlas/quad 0 0 32 32))
  (def player-walk (atlas/quad 32 0 32 32))
  (def player-jump (atlas/quad 64 0 32 32))

  ;; Create a grid of quads from a regular sprite sheet
  ;; (4 columns, 2 rows of 32x32 sprites)
  (def all-sprites (atlas/quad-grid 32 32 4 2))

  ;; Simple sprite drawing
  (atlas/draw canvas sheet player-idle 100 100)

  ;; Draw with rotation (radians), centered on sprite
  (atlas/draw canvas sheet player-idle 100 100
              {:rotation (/ Math/PI 4)
               :origin [16 16]})

  ;; Draw with scale (uniform or [sx sy])
  (atlas/draw canvas sheet player-idle 100 100
              {:scale 2.0})
  (atlas/draw canvas sheet player-idle 100 100
              {:scale [2.0 1.5]})

  ;; Draw flipped (useful for character facing direction)
  (atlas/draw canvas sheet player-walk 100 100
              {:flip-x true
               :origin [16 16]})

  ;; Semi-transparent sprite
  (atlas/draw canvas sheet player-idle 100 100
              {:alpha 0.5})

  ;; Combined transforms
  (atlas/draw canvas sheet player-jump 100 100
              {:rotation 0.3
               :scale 1.5
               :origin [16 16]
               :alpha 0.8})

  ;; Draw entire image (no quad)
  (atlas/draw-image canvas logo 10 10)
  (atlas/draw-image canvas logo 10 10 {:scale 0.5})

  ;; Animation helper - get current frame based on time
  (def walk-cycle (atlas/quad-grid 32 32 8))  ; 8-frame animation
  (let [frame (atlas/animation-frame walk-cycle @game-time 12)] ; 12 FPS
    (atlas/draw canvas sheet frame player-x player-y))

  ;; Batch drawing - draw many sprites efficiently
  (atlas/draw-batch canvas sheet
                    [[player-idle 100 100]
                     [player-walk 200 100 {:flip-x true}]
                     [player-jump 300 100 {:rotation 0.5}]])

  ;; RSXform helpers (for advanced/future use)
  ;; RSXform encodes rotation+scale as a 2x3 matrix
  (def xform (atlas/rsxform-from-radians
               1.0      ; scale
               0.5      ; rotation in radians
               100 100  ; position
               16 16))  ; anchor point

  ;; Batch with RSXform transforms
  (atlas/draw-batch-rsxform canvas sheet
                            [sprite-quad sprite-quad sprite-quad]
                            [(atlas/rsxform-from-radians 1.0 0.0 100 100 16 16)
                             (atlas/rsxform-from-radians 1.5 0.5 200 100 16 16)
                             (atlas/rsxform-from-radians 2.0 1.0 300 100 16 16)]))

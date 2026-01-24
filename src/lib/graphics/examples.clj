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
            [lib.graphics.text :as gfx-text]
            [lib.graphics.batch :as batch]
            [lib.graphics.filters :as filters]
            [lib.graphics.gradients :as gradients]))

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
  (gfx-text/text canvas "Hello World" 10 50)

  ;; Colored text with custom size
  (gfx-text/text canvas "Hello" 10 50
                {:size 24
                 :color 0xFFFF0000})

  ;; Measure text width
  (gfx-text/measure-text "Hello" {:size 24}))

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
  (gfx-text/text canvas "GLOW" 50 100
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

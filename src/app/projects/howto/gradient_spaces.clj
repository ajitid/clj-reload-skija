(ns app.projects.howto.gradient-spaces
  "Gradient Color Space Interpolation — comparing sRGB, Oklab, OKLCH, LAB, LCH.

   Shows the same color pairs rendered with different interpolation color spaces.
   Notice how sRGB produces muddy/dark midtones while perceptual spaces stay vibrant."
  (:require [lib.color.core :as color]
            [lib.graphics.shapes :as shapes]
            [lib.graphics.gradients :as grad]
            [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def ^:private label-color [0.85 0.85 0.85 1.0])
(def ^:private sub-label-color [0.53 0.53 0.53 1.0])
(def ^:private section-title-color [0.7 0.7 0.8 1.0])

(def ^:private color-space-rows
  [{:label "sRGB (default)" :interp :srgb}
   {:label "Oklab"          :interp :oklab}
   {:label "OKLCH"          :interp :oklch}
   {:label "LAB"            :interp :lab}
   {:label "LCH"            :interp :lch}])

(def ^:private hue-method-rows
  [{:label "Shorter (default)" :hue :shorter}
   {:label "Longer"            :hue :longer}
   {:label "Increasing"        :hue :increasing}
   {:label "Decreasing"        :hue :decreasing}])

(def ^:private comparison-pairs
  [{:label "Cyan \u2192 Red"     :c1 [0 1 1 1]   :c2 [1 0 0 1]}
   {:label "Green \u2192 Purple" :c1 [0 0.8 0 1]  :c2 [0.6 0 0.8 1]}
   {:label "Orange \u2192 Teal"  :c1 [1 0.5 0 1]  :c2 [0 0.7 0.7 1]}])

;; ============================================================
;; Drawing helpers
;; ============================================================

(defn- draw-gradient-bar
  "Draw a horizontal gradient bar with label."
  [^Canvas canvas x y w h label interp-cs hue-method colors]
  ;; Gradient bar
  (let [shader (grad/linear-gradient x y (+ x w) y colors nil :clamp interp-cs hue-method)]
    (shapes/rounded-rect canvas x y w h 6 {:shader shader}))
  ;; Label
  (text/text canvas label
             (+ x 8) (+ y h 16)
             {:size 11 :color sub-label-color}))

(defn- draw-section-title
  "Draw a section title."
  [^Canvas canvas title x y]
  (text/text canvas title x y
             {:size 16 :weight :medium :color section-title-color}))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  nil)

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas width height]
  ;; Background
  ;; Title
  (text/text canvas "Gradient Color Space Interpolation"
             (/ width 2.0) 36
             {:size 24 :weight :medium :align :center :color color/white})
  (text/text canvas "Same color pairs, different interpolation — notice the midtones"
             (/ width 2.0) 58
             {:size 12 :align :center :color sub-label-color})

  (let [pad 20
        gap 4
        bar-h 28
        label-h 18
        row-h (+ bar-h label-h gap)
        col-gap 24
        usable-w (- width (* 2 pad))

        ;; Section 1: Color space comparison (Yellow -> Blue)
        s1-y 80
        pair-colors [[1 1 0 1] [0 0 1 1]]]

    ;; --- Section 1: Color Space Comparison ---
    (draw-section-title canvas "Yellow \u2192 Blue across color spaces" pad s1-y)

    (doseq [[idx {:keys [label interp]}] (map-indexed vector color-space-rows)]
      (let [y (+ s1-y 24 (* idx row-h))]
        (draw-gradient-bar canvas pad y usable-w bar-h
                           label interp nil pair-colors)))

    ;; --- Section 2: Hue Methods (OKLCH Red -> Blue) ---
    (let [s2-y (+ s1-y 24 (* (count color-space-rows) row-h) 20)
          hue-colors [[1 0 0 1] [0 0 1 1]]]

      (draw-section-title canvas "Red \u2192 Blue — OKLCH hue methods" pad s2-y)

      (doseq [[idx {:keys [label hue]}] (map-indexed vector hue-method-rows)]
        (let [y (+ s2-y 24 (* idx row-h))]
          (draw-gradient-bar canvas pad y usable-w bar-h
                             label :oklch hue hue-colors)))

      ;; --- Section 3: Practical pairs (sRGB vs Oklab side by side) ---
      (let [s3-y (+ s2-y 24 (* (count hue-method-rows) row-h) 20)
            half-w (/ (- usable-w col-gap) 2.0)]

        (draw-section-title canvas "sRGB vs Oklab — practical pairs" pad s3-y)

        (doseq [[idx {:keys [label c1 c2]}] (map-indexed vector comparison-pairs)]
          (let [y (+ s3-y 24 (* idx row-h))]
            ;; sRGB (left)
            (draw-gradient-bar canvas pad y half-w bar-h
                               (str label " (sRGB)") :srgb nil [c1 c2])
            ;; Oklab (right)
            (draw-gradient-bar canvas (+ pad half-w col-gap) y half-w bar-h
                               (str label " (Oklab)") :oklab nil [c1 c2])))))))

(defn cleanup []
  nil)

(ns app.projects.howto.gradient-types
  "All four gradient types — linear, radial, conical, sweep.

   Each type shown in sRGB and Oklab side by side to highlight
   interpolation differences."
  (:require [lib.graphics.shapes :as shapes]
            [lib.graphics.gradients :as grad]
            [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def ^:private label-color [0.85 0.85 0.85 1.0])
(def ^:private sub-label-color [0.53 0.53 0.53 1.0])
(def ^:private section-title-color [0.7 0.7 0.8 1.0])

(def ^:private rainbow
  [[1.0 0.2 0.2 1.0]
   [1.0 0.6 0.1 1.0]
   [1.0 1.0 0.2 1.0]
   [0.2 0.9 0.3 1.0]
   [0.2 0.6 1.0 1.0]
   [0.6 0.2 0.9 1.0]])

(def ^:private warm-cool
  [[1.0 0.3 0.0 1.0]
   [1.0 0.85 0.0 1.0]
   [0.0 0.7 0.9 1.0]
   [0.1 0.2 0.6 1.0]])

(def ^:private sunset
  [[0.95 0.2 0.3 1.0]
   [0.95 0.55 0.15 1.0]
   [0.95 0.85 0.3 1.0]])

(def ^:private spotlight
  [[0.1 0.4 0.9 1.0]
   [0.9 0.2 0.6 1.0]
   [1.0 0.85 0.1 1.0]])

;; ============================================================
;; Drawing helpers
;; ============================================================

(defn- draw-section-title
  [^Canvas canvas title x y]
  (text/text canvas title x y
             {:size 13 :weight :medium :color section-title-color}))

(defn- draw-label
  [^Canvas canvas label x y]
  (text/text canvas label x y
             {:size 10 :color sub-label-color}))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init [] nil)
(defn tick [_dt] nil)

(defn draw [^Canvas canvas width height]
  ;; Title
  (text/text canvas "Gradient Types"
             (/ width 2.0) 30
             {:size 20 :weight :medium :align :center :color label-color})
  (text/text canvas "linear \u00b7 radial \u00b7 sweep \u00b7 conical  |  sRGB vs Oklab"
             (/ width 2.0) 48
             {:size 11 :align :center :color sub-label-color})

  (let [pad 16
        pair-gap 6        ;; gap between sRGB and Oklab within a pair
        usable-w (- width (* 2 pad))
        bar-h 22
        label-h 14
        bar-row-h (+ bar-h label-h 2)

        ;; Square size: fit 3 pairs across with gaps between sections
        col-gap 20
        ;; Each pair = sq + pair-gap + sq = 2*sq + pair-gap
        ;; 3 pairs + 2 col-gaps = usable-w
        ;; 3*(2*sq + pair-gap) + 2*col-gap = usable-w
        sq (Math/floor (/ (- usable-w (* 3 pair-gap) (* 2 col-gap)) 6.0))
        sq (min sq 180)]

    ;; ── Section 1: Linear Gradients (full-width bars) ──
    (let [s1-y 60]
      (draw-section-title canvas "Linear Gradient" pad s1-y)
      (let [y0 (+ s1-y 18)]
        ;; Rainbow sRGB
        (let [shader (grad/linear-gradient pad y0 (+ pad usable-w) y0 rainbow nil :clamp :srgb nil)]
          (shapes/rounded-rect canvas pad y0 usable-w bar-h 5 {:shader shader}))
        (draw-label canvas "Rainbow (sRGB)" pad (+ y0 bar-h 12))

        ;; Rainbow Oklab
        (let [y1 (+ y0 bar-row-h)
              shader (grad/linear-gradient pad y1 (+ pad usable-w) y1 rainbow nil :clamp :oklab nil)]
          (shapes/rounded-rect canvas pad y1 usable-w bar-h 5 {:shader shader}))
        (draw-label canvas "Rainbow (Oklab)" pad (+ y0 bar-row-h bar-h 12))

        ;; Warm->Cool OKLCH longer
        (let [y2 (+ y0 (* 2 bar-row-h))
              shader (grad/linear-gradient pad y2 (+ pad usable-w) y2 warm-cool nil :clamp :oklch :longer)]
          (shapes/rounded-rect canvas pad y2 usable-w bar-h 5 {:shader shader}))
        (draw-label canvas "Warm\u2192Cool (OKLCH longer hue)" pad (+ y0 (* 2 bar-row-h) bar-h 12))

        ;; ── Section 2: Radial / Sweep / Conical in a row ──
        ;; Each pair: sRGB on top, Oklab below, stacked vertically
        (let [row2-y (+ y0 (* 3 bar-row-h) 10)
              cell-w (+ (* sq 2) pair-gap)
              draw-pair (fn [title x y make-shader-fn]
                          (draw-section-title canvas title x y)
                          (let [top (+ y 16)]
                            ;; sRGB square
                            (let [shader (make-shader-fn x top sq :srgb)]
                              (shapes/rounded-rect canvas x top sq sq 5 {:shader shader}))
                            ;; Oklab square - next to it
                            (let [x2 (+ x sq pair-gap)
                                  shader (make-shader-fn x2 top sq :oklab)]
                              (shapes/rounded-rect canvas x2 top sq sq 5 {:shader shader}))
                            ;; Labels below
                            (draw-label canvas "sRGB" x (+ top sq 12))
                            (draw-label canvas "Oklab" (+ x sq pair-gap) (+ top sq 12))))]

          ;; Radial
          (draw-pair "Radial" pad row2-y
                     (fn [x y sz cs]
                       (let [half (/ sz 2.0)]
                         (grad/radial-gradient (+ x half) (+ y half) half
                                               sunset nil :clamp cs nil))))

          ;; Sweep
          (draw-pair "Sweep (Angular)" (+ pad cell-w col-gap) row2-y
                     (fn [x y sz cs]
                       (let [half (/ sz 2.0)]
                         (grad/sweep-gradient (+ x half) (+ y half)
                                              rainbow nil 0 360 :clamp cs nil))))

          ;; Conical — small circle offset from larger circle, classic cone shape
          (draw-pair "Two-Point Conical" (+ pad (* 2 (+ cell-w col-gap))) row2-y
                     (fn [x y sz cs]
                       (let [half (/ sz 2.0)]
                         (grad/conical-gradient
                           (+ x (* sz 0.3)) (+ y (* sz 0.35)) (* sz 0.08)
                           (+ x (* sz 0.55)) (+ y (* sz 0.5)) (* half 0.85)
                           spotlight nil :clamp cs nil)))))))))

(defn cleanup [] nil)

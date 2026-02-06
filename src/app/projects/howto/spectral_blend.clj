(ns app.projects.howto.spectral-blend
  "Spectral (pigment) color mixing vs standard RGB mixing.

   Blue + yellow = green with spectral mixing, not gray.
   Uses Kubelka-Munk theory ported from spectral.js.
   All rendering is GPU-only (SkSL shaders)."
  (:require [lib.graphics.shapes :as shapes]
            [lib.graphics.gradients :as grad]
            [lib.color.spectral :as spectral]
            [lib.text.core :as text]
            [app.state.system :as sys])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def ^:private label-color [0.85 0.85 0.85 1.0])
(def ^:private sub-label-color [0.53 0.53 0.53 1.0])
(def ^:private section-color [0.7 0.7 0.8 1.0])

(def ^:private pairs
  [[[0.0 0.0 1.0 1.0] [1.0 1.0 0.0 1.0] "Blue -> Yellow"]
   [[1.0 0.0 0.0 1.0] [0.0 0.0 1.0 1.0] "Red -> Blue"]
   [[1.0 0.0 0.0 1.0] [0.0 1.0 0.0 1.0] "Red -> Green"]
   [[0.0 1.0 1.0 1.0] [1.0 0.0 0.0 1.0] "Cyan -> Red"]])

;; ============================================================
;; Example Interface
;; ============================================================

(defn init [] nil)
(defn tick [_dt] nil)

(defn draw [^Canvas canvas width height]
  (let [pad 20
        usable-w (- width (* 2 pad))
        bar-h 28
        bar-gap 4
        pair-h (+ (* 2 bar-h) bar-gap 40)
        cx (/ width 2.0)]

    ;; Title
    (text/text canvas "Spectral Color Mixing"
               cx 32
               {:size 20 :weight :medium :align :center :color label-color})
    (text/text canvas "Kubelka-Munk pigment theory  |  blue + yellow = green"
               cx 50
               {:size 11 :align :center :color sub-label-color})

    ;; Gradient comparison rows — all GPU
    (let [start-y 70]
      (doseq [[idx [c1 c2 label]] (map-indexed vector pairs)]
        (let [y0 (+ start-y (* idx pair-h))]
          ;; Section label
          (text/text canvas label pad (+ y0 1)
                     {:size 11 :weight :medium :color section-color})

          ;; RGB lerp bar (single rect with linear gradient shader)
          (let [bar-y (+ y0 12)]
            (shapes/rounded-rect canvas pad bar-y usable-w bar-h 4
                                 {:shader (grad/linear-gradient
                                            pad bar-y (+ pad usable-w) bar-y
                                            [c1 c2] nil :clamp :srgb nil)})
            (text/text canvas "RGB lerp" (+ pad 4) (+ bar-y bar-h 11)
                       {:size 9 :color sub-label-color}))

          ;; Spectral bar (single rect with spectral gradient shader — fully GPU)
          (let [bar-y (+ y0 12 bar-h bar-gap)]
            (shapes/rounded-rect canvas pad bar-y usable-w bar-h 4
                                 {:shader (spectral/gradient-shader c1 c2 usable-w)})
            (text/text canvas "Spectral (KM)" (+ pad 4) (+ bar-y bar-h 11)
                       {:size 9 :color sub-label-color})))))

    ;; Animated blender circles — GPU blender
    (let [circle-y (+ 70 (* (count pairs) pair-h) 40)
          t-val (* 0.5 (+ 1.0 (Math/sin (* @sys/game-time 0.4))))
          r 70
          overlap 40
          left-cx  (- cx overlap)
          right-cx (+ cx overlap)]

      (text/text canvas "GPU Blender (animated)" cx (- circle-y r 12)
                 {:size 11 :weight :medium :align :center :color section-color})

      ;; Blue circle (dst)
      (shapes/circle canvas left-cx circle-y r
                     {:color [0.0 0.0 1.0 1.0]})

      ;; Yellow circle with spectral blender — overlap shows KM mixing
      (shapes/circle canvas right-cx circle-y r
                     {:color [1.0 1.0 0.0 1.0]
                      :blender (spectral/blender t-val)})

      (text/text canvas (format "t = %.2f" t-val) cx (+ circle-y r 16)
                 {:size 10 :align :center :color sub-label-color})
      (text/text canvas "blue (dst)" left-cx (+ circle-y r 16)
                 {:size 9 :align :center :color sub-label-color})
      (text/text canvas "yellow (src)" right-cx (+ circle-y r 16)
                 {:size 9 :align :center :color sub-label-color}))))

(defn cleanup [] nil)

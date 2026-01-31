(ns app.projects.howto.pattern-shaders
  "2D Pattern Shaders — GPU-accelerated repeating patterns using SkSL.

   Shows hatch, grid, dot, and cross-hatch patterns applied to rounded
   rectangles. All patterns run entirely on the GPU via SkSL shaders."
  (:require [lib.graphics.shapes :as shapes]
            [lib.graphics.shaders :as shaders]
            [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def ^:private label-color [0.8 0.8 0.8 1.0])
(def ^:private sub-label-color [0.53 0.53 0.53 1.0])
(def ^:private cell-bg [0.1 0.1 0.18 1.0])
(def ^:private corner-radius 12)

(def ^:private cells
  [{:label "Hatch (0°)"
    :sub   "(hatch-shader 2 10)"
    :color [0.29 0.56 0.85 1.0]}
   {:label "Hatch (45°)"
    :sub   "(hatch-shader 2 10 {:angle π/4})"
    :color [0.18 0.80 0.44 1.0]}
   {:label "Grid"
    :sub   "(grid-shader 1 20 20)"
    :color [0.61 0.35 0.71 1.0]}
   {:label "Dot Pattern"
    :sub   "(dot-pattern-shader 3 15 15)"
    :color [0.90 0.49 0.13 1.0]}])

(def ^:private crosshatch-label "Cross-hatch (composed)")
(def ^:private crosshatch-sub "(cross-hatch-shader 1.5 12)")
(def ^:private crosshatch-color [0.91 0.30 0.24 1.0])

;; ============================================================
;; Shader factories
;; ============================================================

(defn- make-shader-for-cell
  "Create the pattern shader for a given cell index."
  [idx color]
  (case idx
    0 (shaders/hatch-shader 2 10 {:color color})
    1 (shaders/hatch-shader 2 10 {:angle (/ Math/PI 4) :color color})
    2 (shaders/grid-shader 1 20 20 {:color color})
    3 (shaders/dot-pattern-shader 3 15 15 {:color color})))

;; ============================================================
;; Drawing
;; ============================================================

(defn- draw-cell
  "Draw a single pattern cell: background rect, pattern fill, labels."
  [^Canvas canvas {:keys [label sub color]} cx cy cw ch idx]
  ;; Background
  (shapes/rounded-rect canvas cx cy cw ch corner-radius {:color cell-bg})
  ;; Pattern fill (same rect, clipped by rounded-rect shape)
  (let [shader (make-shader-for-cell idx color)]
    (shapes/rounded-rect canvas cx cy cw ch corner-radius {:shader shader}))
  ;; Label
  (text/text canvas label
             (+ cx (/ cw 2.0)) (+ cy 24)
             {:size 15 :weight :medium :align :center :color label-color})
  ;; Sub-label (function call)
  (text/text canvas sub
             (+ cx (/ cw 2.0)) (+ cy 44)
             {:size 11 :align :center :color sub-label-color}))

(defn- draw-crosshatch-row
  "Draw the full-width cross-hatch row."
  [^Canvas canvas cx cy cw ch]
  ;; Background
  (shapes/rounded-rect canvas cx cy cw ch corner-radius {:color cell-bg})
  ;; Pattern fill
  (let [shader (shaders/cross-hatch-shader 1.5 12 {:color crosshatch-color})]
    (shapes/rounded-rect canvas cx cy cw ch corner-radius {:shader shader}))
  ;; Labels
  (text/text canvas crosshatch-label
             (+ cx (/ cw 2.0)) (+ cy 24)
             {:size 15 :weight :medium :align :center :color label-color})
  (text/text canvas crosshatch-sub
             (+ cx (/ cw 2.0)) (+ cy 44)
             {:size 11 :align :center :color sub-label-color}))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Pattern shaders howto loaded"))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas width height]
  ;; Title
  (text/text canvas "2D Pattern Shaders (GPU)"
             (/ width 2.0) 40
             {:size 28 :weight :medium :align :center :color [1.0 1.0 1.0 1.0]})
  (text/text canvas "GPU-accelerated repeating patterns using SkSL"
             (/ width 2.0) 68
             {:size 14 :align :center :color [0.53 0.53 0.53 1.0]})

  ;; Layout: 2 columns x 2 rows + 1 full-width row
  (let [pad 16
        gap 12
        top-y 90
        usable-w (- width (* 2 pad))
        usable-h (- height top-y pad)
        ;; 3 rows: top 2 are equal, bottom is shorter
        row-h (/ (- usable-h (* 2 gap)) 3.0)
        col-w (/ (- usable-w gap) 2.0)]

    ;; Row 0: Hatch (0°) | Hatch (45°)
    (draw-cell canvas (cells 0)
               pad top-y col-w row-h 0)
    (draw-cell canvas (cells 1)
               (+ pad col-w gap) top-y col-w row-h 1)

    ;; Row 1: Grid | Dot Pattern
    (let [r1-y (+ top-y row-h gap)]
      (draw-cell canvas (cells 2)
                 pad r1-y col-w row-h 2)
      (draw-cell canvas (cells 3)
                 (+ pad col-w gap) r1-y col-w row-h 3))

    ;; Row 2: Cross-hatch (full width)
    (let [r2-y (+ top-y (* 2 (+ row-h gap)))]
      (draw-crosshatch-row canvas pad r2-y usable-w row-h))))

(defn cleanup []
  (println "Pattern shaders howto cleanup"))

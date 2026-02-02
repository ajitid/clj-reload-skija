(ns app.projects.howto.stroke-fill
  "Stroke to Fill — convert stroked paths to filled outlines.

   Demonstrates path/stroke->fill for:
   - Hit testing on stroke boundaries
   - Boolean operations with stroked shapes
   - Visualizing stroke geometry"
  (:require [lib.color.core :as color]
            [lib.color.open-color :as oc]
            [lib.graphics.shapes :as shapes]
            [lib.graphics.path :as path]
            [lib.text.core :as text]
            [lib.time :as time])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def ^:private base-stroke-color oc/gray-5)
(def ^:private outline-fill-color [0.2 0.6 0.9 0.4])
(def ^:private outline-stroke-color oc/blue-4)
(def ^:private xor-fill-color [0.9 0.3 0.4 0.5])

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Stroke fill howto loaded"))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas width height]
  ;; Title
  (text/text canvas "stroke->fill"
             (/ width 2.0) 40
             {:size 28 :weight :medium :align :center :color color/white})
  (text/text canvas "Convert stroked paths to filled outlines for hit testing & boolean ops"
             (/ width 2.0) 68
             {:size 14 :align :center :color oc/gray-5})

  (let [t (time/now)
        ;; Animate stroke width
        base-width (+ 15 (* 10 (Math/sin (* t 0.8))))
        col-w (/ width 3.0)
        top-y 100]

    ;; --- Column 1: Basic line ---
    (let [cx (/ col-w 2.0)
          line-path (path/line 40 200 (- col-w 40) 320)
          outline (path/stroke->fill line-path {:stroke-width base-width
                                                 :stroke-cap :round})]
      ;; Label
      (text/text canvas "Line with :round cap"
                 cx (+ top-y 10)
                 {:size 14 :align :center :color oc/gray-4})
      (text/text canvas (format "width: %.1f" base-width)
                 cx (+ top-y 30)
                 {:size 12 :align :center :color oc/gray-6})
      ;; Draw filled outline
      (when outline
        (shapes/path canvas outline {:color outline-fill-color :mode :fill})
        (shapes/path canvas outline {:color outline-stroke-color :mode :stroke
                                     :stroke-width 1.5}))
      ;; Draw original path as thin stroke
      (shapes/path canvas line-path {:color base-stroke-color :mode :stroke
                                     :stroke-width 2 :stroke-cap :round}))

    ;; --- Column 2: Curved path ---
    (let [ox col-w
          cx (+ ox (/ col-w 2.0))
          curve-path (-> (path/builder)
                         (path/move-to (+ ox 40) 320)
                         (path/cubic-to (+ ox 60) 180
                                        (+ ox (- col-w 60)) 340
                                        (+ ox (- col-w 40)) 200)
                         (path/build))
          outline (path/stroke->fill curve-path {:stroke-width base-width
                                                  :stroke-cap :square
                                                  :stroke-join :round})]
      ;; Label
      (text/text canvas "Bezier with :square cap"
                 cx (+ top-y 10)
                 {:size 14 :align :center :color oc/gray-4})
      ;; Draw filled outline
      (when outline
        (shapes/path canvas outline {:color outline-fill-color :mode :fill})
        (shapes/path canvas outline {:color outline-stroke-color :mode :stroke
                                     :stroke-width 1.5}))
      ;; Draw original path
      (shapes/path canvas curve-path {:color base-stroke-color :mode :stroke
                                      :stroke-width 2}))

    ;; --- Column 3: XOR of two stroke outlines ---
    (let [ox (* 2 col-w)
          cx (+ ox (/ col-w 2.0))
          cy 260
          ;; Two overlapping lines
          line1 (path/line (+ ox 40) (- cy 40) (+ ox (- col-w 40)) (+ cy 40))
          line2 (path/line (+ ox 40) (+ cy 40) (+ ox (- col-w 40)) (- cy 40))
          outline1 (path/stroke->fill line1 {:stroke-width 30 :stroke-cap :round})
          outline2 (path/stroke->fill line2 {:stroke-width 30 :stroke-cap :round})]
      ;; Label
      (text/text canvas "XOR of two outlines"
                 cx (+ top-y 10)
                 {:size 14 :align :center :color oc/gray-4})
      ;; Draw XOR result
      (when (and outline1 outline2)
        (let [xor-path (path/xor outline1 outline2)]
          (shapes/path canvas xor-path {:color xor-fill-color :mode :fill})
          (shapes/path canvas xor-path {:color oc/red-4 :mode :stroke
                                        :stroke-width 1.5})))
      ;; Draw original lines
      (shapes/path canvas line1 {:color base-stroke-color :mode :stroke
                                 :stroke-width 2})
      (shapes/path canvas line2 {:color base-stroke-color :mode :stroke
                                 :stroke-width 2}))

    ;; --- Bottom row: Different caps/joins comparison ---
    (let [row-y 380
          cap-types [:butt :round :square]
          spacing (/ (- width 80) 3.0)]
      (text/text canvas "Cap styles comparison (butt, round, square)"
                 (/ width 2.0) (+ row-y 20)
                 {:size 14 :align :center :color oc/gray-4})
      (doseq [[i cap] (map-indexed vector cap-types)]
        (let [x (+ 40 (* i spacing) (/ spacing 2.0))
              line-path (path/line (- x 40) (+ row-y 80) (+ x 40) (+ row-y 80))
              outline (path/stroke->fill line-path {:stroke-width 30
                                                     :stroke-cap cap})]
          ;; Cap label
          (text/text canvas (name cap)
                     x (+ row-y 50)
                     {:size 12 :align :center :color oc/gray-5})
          ;; Draw outline
          (when outline
            (shapes/path canvas outline {:color outline-fill-color :mode :fill})
            (shapes/path canvas outline {:color outline-stroke-color :mode :stroke
                                         :stroke-width 1.5}))
          ;; Center dot showing actual line
          (shapes/circle canvas x (+ row-y 80) 3 {:color oc/red-5}))))))

(defn cleanup []
  (println "Stroke fill howto cleanup"))

(ns app.projects.howto.curves
  "Curve Interpolation — visual comparison of three algorithms.

   Shows Natural Cubic Spline (C2), Hobby Curve (G1), and Catmull-Rom (C1)
   side by side with the same control points."
  (:require [lib.graphics.curves :as curves]
            [lib.graphics.shapes :as shapes]
            [lib.graphics.path :as path]
            [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def ^:private point-radius 5)
(def ^:private curve-width 3.0)
(def ^:private polygon-width 1.0)
(def ^:private label-color [0.8 0.8 0.8 1.0])
(def ^:private polygon-color [0.33 0.33 0.33 1.0])
(def ^:private point-color [0.91 0.3 0.24 1.0])

(def ^:private panels
  [{:label "Natural Cubic Spline (C2)"
    :color [0.29 0.56 0.85 1.0]
    :curve-fn #(curves/natural-cubic-spline %)}
   {:label "Hobby Curve (G1 / METAFONT)"
    :color [0.18 0.8 0.44 1.0]
    :curve-fn #(curves/hobby-curve %)}
   {:label "Catmull-Rom (C1 / centripetal)"
    :color [0.61 0.35 0.71 1.0]
    :curve-fn #(curves/catmull-rom %)}])

;; ============================================================
;; Drawing
;; ============================================================

(defn- make-control-points
  "Generate 7 control points distributed within the given rect."
  [x y w h]
  (let [mx (+ x (/ w 2.0))
        pad-x (* w 0.08)
        pad-y (* h 0.15)]
    [[(+ x pad-x)          (+ y (/ h 2.0))]
     [(+ x (* w 0.2))      (+ y pad-y)]
     [(+ x (* w 0.35))     (- (+ y h) pad-y)]
     [mx                   (+ y pad-y)]
     [(+ x (* w 0.65))     (- (+ y h) pad-y)]
     [(+ x (* w 0.8))      (+ y pad-y)]
     [(- (+ x w) pad-x)    (+ y (/ h 2.0))]]))

(defn- draw-control-polygon
  "Draw thin gray lines connecting control points."
  [^Canvas canvas points]
  (let [poly-path (path/polygon points false)]
    (shapes/path canvas poly-path
                 {:color polygon-color :mode :stroke :stroke-width polygon-width})))

(defn- draw-control-points
  "Draw red dots at each control point."
  [^Canvas canvas points]
  (doseq [[x y] points]
    (shapes/circle canvas x y point-radius {:color point-color})))

(defn- draw-panel
  "Draw one curve panel with label, polygon, curve, and points."
  [^Canvas canvas {:keys [label color curve-fn]} panel-x panel-y panel-w panel-h]
  ;; Label
  (text/text canvas label
             (+ panel-x (/ panel-w 2.0)) (+ panel-y 22)
             {:size 16 :weight :medium :align :center :color label-color})
  ;; Curve area
  (let [curve-y (+ panel-y 36)
        curve-h (- panel-h 40)
        pts (make-control-points panel-x curve-y panel-w curve-h)
        curve-path (curve-fn pts)]
    ;; Control polygon
    (draw-control-polygon canvas pts)
    ;; Curve
    (shapes/path canvas curve-path
                 {:color color :mode :stroke :stroke-width curve-width
                  :stroke-cap :round :stroke-join :round})
    ;; Control points (on top)
    (draw-control-points canvas pts)))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Curves howto loaded"))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas width height]
  ;; Title
  (text/text canvas "Curve Interpolation"
             (/ width 2.0) 40
             {:size 28 :weight :medium :align :center :color [1.0 1.0 1.0 1.0]})
  (text/text canvas "Same 7 control points, three algorithms"
             (/ width 2.0) 68
             {:size 14 :align :center :color [0.53 0.53 0.53 1.0]})
  ;; Three stacked panels
  (let [top-y 90
        panel-h (/ (- height top-y 20) 3.0)
        pad 16
        panel-w (- width (* 2 pad))]
    (doseq [[i panel] (map-indexed vector panels)]
      (let [py (+ top-y (* i panel-h))]
        ;; Subtle separator line
        (when (pos? i)
          (shapes/line canvas pad py (- width pad) py
                       {:color [0.2 0.2 0.2 1.0] :stroke-width 1}))
        (draw-panel canvas panel pad py panel-w panel-h)))))

(defn cleanup []
  (println "Curves howto cleanup"))

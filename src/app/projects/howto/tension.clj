(ns app.projects.howto.tension
  "Hobby Curve Tension — visual comparison of default vs high tension.

   Two panels show the same 7 control points with:
   1. Hobby baseline (tension = 1.0 everywhere)
   2. Tension = 2.0 on the middle segment (longer handles, looser curve)"
  (:require [lib.color.core :as color]
            [lib.color.open-color :as oc]
            [lib.graphics.curves :as curves]
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
(def ^:private highlight-width 4.0)
(def ^:private label-color oc/gray-4)
(def ^:private polygon-color [0.33 0.33 0.33 1.0])
(def ^:private point-color oc/red-7)
(def ^:private highlight-color [1.0 0.6 0.2 0.35])

(def ^:private tension-segment
  "Which segment index gets tension applied (0-based, segment 3 = sharpest turn)."
  3)

(def ^:private tension-value 2.0)

(defn- tension-fn
  "Returns [t1 t2] handle scale factors for chord-idx.
   Applies tension-value on the tension-segment, 1.0 elsewhere."
  [chord-idx _in-deg _out-deg]
  (if (= chord-idx tension-segment)
    [tension-value tension-value]
    [1.0 1.0]))

(def ^:private panels
  [{:label "Hobby (tension = 1.0, baseline)"
    :color oc/blue-6
    :curve-fn (fn [pts] (curves/hobby-curve pts))
    :highlight? false}
   {:label "Tension = 2.0 at segment 3"
    :color [0.61 0.35 0.71 1.0]
    :curve-fn (fn [pts] (curves/hobby-curve pts {:tensions tension-fn}))
    :highlight? true}])

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

(defn- draw-highlight-segment
  "Draw a semitransparent orange line over the tension segment."
  [^Canvas canvas points]
  (let [[x1 y1] (nth points tension-segment)
        [x2 y2] (nth points (inc tension-segment))]
    (shapes/line canvas x1 y1 x2 y2
                 {:color highlight-color :stroke-width highlight-width
                  :stroke-cap :round})))

(defn- draw-panel
  "Draw one curve panel with label, polygon, curve, and points."
  [^Canvas canvas {:keys [label color curve-fn highlight?]} panel-x panel-y panel-w panel-h]
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
    ;; Highlight tension segment
    (when highlight?
      (draw-highlight-segment canvas pts))
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
  (println "Tension howto loaded"))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas width height]
  ;; Title
  (text/text canvas "Hobby Curve Tension"
             (/ width 2.0) 40
             {:size 28 :weight :medium :align :center :color color/white})
  (text/text canvas "Higher tension = longer handles = looser curve at that segment"
             (/ width 2.0) 68
             {:size 13 :align :center :color [0.53 0.53 0.53 1.0]})
  ;; Two stacked panels
  (let [top-y 90
        panel-h (/ (- height top-y 20) 2.0)
        pad 16
        panel-w (- width (* 2 pad))]
    (doseq [[i panel] (map-indexed vector panels)]
      (let [py (+ top-y (* i panel-h))]
        ;; Subtle separator line
        (when (pos? i)
          (shapes/line canvas pad py (- width pad) py
                       {:color oc/gray-8 :stroke-width 1}))
        (draw-panel canvas panel pad py panel-w panel-h)))))

(defn cleanup []
  (println "Tension howto cleanup"))

(ns app.projects.howto.path-effects
  "Path Effects — visual demo of stamp, compose, and sum effects.

   Shows five rows, each applying a different path effect to a shared
   wavy S-curve base path."
  (:require [lib.color.core :as color]
            [lib.color.open-color :as oc]
            [lib.graphics.shapes :as shapes]
            [lib.graphics.path :as path]
            [lib.graphics.filters :as filters]
            [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def ^:private base-color [0.33 0.33 0.33 1.0])
(def ^:private base-width 1.5)
(def ^:private effect-width 3.0)
(def ^:private label-color oc/gray-4)

(def ^:private rows
  [{:label "Stamp :move (translate only)"
    :color oc/blue-6}
   {:label "Stamp :turn (rotate to follow)"
    :color oc/green-5}
   {:label "Stamp :follow (bend to curvature)"
    :color oc/yellow-9}
   {:label "Composed: dash + corner"
    :color [0.61 0.35 0.71 1.0]}
   {:label "Sum: dash + discrete"
    :color oc/red-7}])

;; ============================================================
;; Marker shapes (built once, reused)
;; ============================================================

(defn- make-arrow-marker
  "Small rightward-pointing arrow centered at origin."
  []
  (-> (path/builder)
      (path/move-to -5 -4)
      (path/line-to 5 0)
      (path/line-to -5 4)
      (path/close)
      (path/build)))

(defn- make-diamond-marker
  "Small diamond centered at origin."
  []
  (-> (path/builder)
      (path/move-to 0 -5)
      (path/line-to 5 0)
      (path/line-to 0 5)
      (path/line-to -5 0)
      (path/close)
      (path/build)))

(defn- make-bar-marker
  "Horizontal bar centered at origin.
   Straight edges make curvature bending obvious with :follow."
  []
  (path/rect -50 -14 100 28))

;; ============================================================
;; Base path factory
;; ============================================================

(defn- make-s-curve
  "Create a wavy S-curve that spans the given rect."
  [x y w h]
  (let [mid-y (+ y (/ h 2.0))
        amp (* h 0.45)]
    (-> (path/builder)
        (path/move-to x mid-y)
        ;; First half: rise up then come back to center
        (path/cubic-to (+ x (* w 0.1))  (- mid-y amp)
                       (+ x (* w 0.4))  (- mid-y amp)
                       (+ x (* w 0.5))  mid-y)
        ;; Second half: dip down then come back to center
        (path/cubic-to (+ x (* w 0.6))  (+ mid-y amp)
                       (+ x (* w 0.9))  (+ mid-y amp)
                       (+ x w)          mid-y)
        (path/build))))

;; ============================================================
;; Effect factories
;; ============================================================

(defn- make-effect
  "Create the PathEffect for the given row index."
  [idx]
  (case idx
    ;; Stamp :move — arrow translated only (always points right)
    0 (filters/stamp-path-effect (make-arrow-marker) 20 {:fit :move})
    ;; Stamp :turn — diamond rotated to follow path direction
    1 (filters/stamp-path-effect (make-diamond-marker) 18 {:fit :turn})
    ;; Stamp :follow — bar bent to match path curvature
    2 (filters/stamp-path-effect (make-bar-marker) 110 {:fit :follow})
    ;; Composed: dash + corner (rounded dashes)
    3 (filters/compose-path-effects
       (filters/corner-path-effect 6)
       (filters/dash-path-effect [20 10]))
    ;; Sum: dash + discrete (both visible)
    4 (filters/sum-path-effects
       (filters/dash-path-effect [15 8])
       (filters/discrete-path-effect 8 3 42))))

;; ============================================================
;; Drawing
;; ============================================================

(defn- draw-row
  "Draw one effect row: label, base path (gray), effect path (colored)."
  [^Canvas canvas idx {:keys [label color]} row-x row-y row-w row-h]
  ;; Label
  (text/text canvas label
             (+ row-x (/ row-w 2.0)) (+ row-y 22)
             {:size 15 :weight :medium :align :center :color label-color})
  ;; Path area
  (let [path-y (+ row-y 32)
        path-h (- row-h 38)
        pad-x 30
        base-path (make-s-curve (+ row-x pad-x) path-y (- row-w (* 2 pad-x)) path-h)
        effect (make-effect idx)]
    ;; Base path (thin gray)
    (shapes/path canvas base-path
                 {:color base-color :mode :stroke :stroke-width base-width
                  :stroke-cap :round :stroke-join :round})
    ;; Effect path (colored)
    (shapes/path canvas base-path
                 {:color color :mode :stroke :stroke-width effect-width
                  :stroke-cap :round :stroke-join :round
                  :path-effect effect})))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Path effects howto loaded"))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas width height]
  ;; Title
  (text/text canvas "Path Effects"
             (/ width 2.0) 40
             {:size 28 :weight :medium :align :center :color color/white})
  (text/text canvas "stamp, compose, and sum effects on a shared S-curve"
             (/ width 2.0) 68
             {:size 14 :align :center :color [0.53 0.53 0.53 1.0]})
  ;; Five stacked rows
  (let [top-y 90
        row-h (/ (- height top-y 20) (count rows))
        pad 16
        row-w (- width (* 2 pad))]
    (doseq [[i row] (map-indexed vector rows)]
      (let [ry (+ top-y (* i row-h))]
        ;; Separator line
        (when (pos? i)
          (shapes/line canvas pad ry (- width pad) ry
                       {:color oc/gray-8 :stroke-width 1}))
        (draw-row canvas i row pad ry row-w row-h)))))

(defn cleanup []
  (println "Path effects howto cleanup"))

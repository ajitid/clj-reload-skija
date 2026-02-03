(ns app.projects.howto.rect-grid
  "Rect Grid — demonstrates lib.math/rect-spread.

   Draws a grid of points using rect-spread and prints the index at each point."
  (:require [lib.color.open-color :as oc]
            [lib.math :as lm]
            [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def ^:private nx 6)
(def ^:private ny 4)
(def ^:private label-color oc/gray-3)

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Rect Grid howto loaded"))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas width height]
  (let [grid-w (* width 0.7)
        grid-h (* height 0.7)
        cx     (/ width 2.0)
        cy     (/ height 2.0)
        points (lm/rect-spread nx ny grid-w grid-h {:center [cx cy]})]
    ;; Title
    (text/text canvas "rect-spread"
               cx 40
               {:size 28 :weight :medium :align :center :color oc/gray-3})
    (text/text canvas (str nx "×" ny " grid, " (count points) " points")
               cx 66
               {:size 14 :align :center :color oc/gray-5})
    ;; Draw index at each point
    (doseq [[i pt] (map-indexed vector points)]
      (let [[x y] pt]
        (text/text canvas (str i)
                   x y
                   {:size 16 :align :center :color label-color})))))

(defn cleanup []
  (println "Rect Grid howto cleanup"))

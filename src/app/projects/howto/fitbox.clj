(ns app.projects.howto.fitbox
  "FitBox — map design coordinates into a screen rect with 7 fit modes.

   Demonstrates:
   - lib.graphics.transform/fitbox with all fit modes
   - :contain, :cover, :fill, :fit-width, :fit-height, :scale-down, :none
   - Scoped canvas transforms via with-transform

   Launch: (open :howto/fitbox)"
  (:require [lib.graphics.shapes :as shapes]
            [lib.graphics.transform :as xf]
            [lib.text.core :as text]
            [lib.color.core :as color]
            [lib.color.open-color :as oc])
  (:import [io.github.humbleui.skija Canvas]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Grid Layout
;; ============================================================

(def cols 4)
(def card-w 200)
(def card-h 240)
(def card-pad 16)
(def grid-x0 30)
(def grid-y0 80)

(defn- card-pos
  "Return [x y] for card at index i."
  [i]
  (let [col (mod i cols)
        row (quot i cols)]
    [(+ grid-x0 (* col (+ card-w card-pad)))
     (+ grid-y0 (* row (+ card-h card-pad)))]))

(defn- draw-card-bg
  "Draw card background."
  [^Canvas canvas x y]
  (shapes/rounded-rect canvas x y card-w card-h 8
                       {:color [0.12 0.12 0.15 1.0]}))

(defn- draw-label
  "Draw card title label."
  [^Canvas canvas x y title]
  (text/text canvas title
             (+ x (/ card-w 2.0)) (+ y 20)
             {:size 13 :weight :medium :align :center :color oc/gray-3}))

;; ============================================================
;; Design-Space Content (drawn in src coordinates)
;; ============================================================

;; The "design" is drawn at 200×300 — intentionally non-square
;; to clearly show how each fit mode handles aspect ratio differences.
(def src-rect {:x 0 :y 0 :w 200 :h 300})

(defn- draw-design-content
  "Draw the design-space content (assumed 200×300 coordinate system).
   A colored background rect + circle + crosshairs so you can see
   cropping, letterboxing, and stretching clearly."
  [^Canvas canvas]
  (let [w (:w src-rect)
        h (:h src-rect)]
    ;; Background fill
    (shapes/rectangle canvas 0 0 w h {:color oc/indigo-8})
    ;; Crosshairs
    (shapes/line canvas 0 (/ h 2.0) w (/ h 2.0)
                 {:color [1 1 1 0.3] :stroke-width 1})
    (shapes/line canvas (/ w 2.0) 0 (/ w 2.0) h
                 {:color [1 1 1 0.3] :stroke-width 1})
    ;; Diagonal lines to show stretching
    (shapes/line canvas 0 0 w h
                 {:color [1 1 1 0.15] :stroke-width 1})
    (shapes/line canvas w 0 0 h
                 {:color [1 1 1 0.15] :stroke-width 1})
    ;; Central circle
    (shapes/circle canvas (/ w 2.0) (/ h 2.0) 60
                   {:color oc/cyan-5})
    ;; Corner markers
    (shapes/circle canvas 10 10 6 {:color oc/red-5})
    (shapes/circle canvas (- w 10) 10 6 {:color oc/green-5})
    (shapes/circle canvas 10 (- h 10) 6 {:color oc/yellow-5})
    (shapes/circle canvas (- w 10) (- h 10) 6 {:color oc/pink-5})
    ;; Border
    (shapes/rectangle canvas 0 0 w h
                       {:color [1 1 1 0.4] :mode :stroke :stroke-width 2})))

;; ============================================================
;; Individual Cards
;; ============================================================

(def dst-inset 16)
(def dst-h 180)

(defn- draw-fit-card
  "Draw a single fit-mode card at (x, y)."
  [^Canvas canvas x y fit-mode label]
  (draw-card-bg canvas x y)
  (draw-label canvas x y label)
  (let [dst {:x (+ x dst-inset)
             :y (+ y 36)
             :w (- card-w (* 2 dst-inset))
             :h dst-h}
        matrix (xf/fitbox fit-mode src-rect dst)]
    ;; Draw dst boundary
    (shapes/rectangle canvas (:x dst) (:y dst) (:w dst) (:h dst)
                       {:color [1 1 1 0.08]})
    (shapes/rectangle canvas (:x dst) (:y dst) (:w dst) (:h dst)
                       {:color [1 1 1 0.2] :mode :stroke :stroke-width 1})
    ;; Draw fitted content (clipped to dst)
    (.save canvas)
    (.clipRect canvas (Rect/makeXYWH (float (:x dst)) (float (:y dst))
                                     (float (:w dst)) (float (:h dst))))
    (xf/with-transform [canvas matrix]
      (draw-design-content canvas))
    (.restore canvas)))

;; ============================================================
;; Card Registry
;; ============================================================

(def ^:private cards
  [[:contain    ":contain"]
   [:cover      ":cover"]
   [:fill       ":fill"]
   [:fit-width  ":fit-width"]
   [:fit-height ":fit-height"]
   [:scale-down ":scale-down"]
   [:none       ":none"]])

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "FitBox howto loaded"))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas w h]
  ;; Title
  (text/text canvas "FitBox"
             (/ w 2.0) 35
             {:size 28 :weight :medium :align :center :color color/white})
  (text/text canvas "Map design coordinates (200\u00d7300) into screen rects with 7 fit modes"
             (/ w 2.0) 60
             {:size 14 :align :center :color oc/gray-5})

  ;; Draw grid of cards
  (doseq [[i [fit-mode label]] (map-indexed vector cards)]
    (let [[cx cy] (card-pos i)]
      (draw-fit-card canvas cx cy fit-mode label))))

(defn cleanup []
  (println "FitBox howto cleanup"))

(ns app.projects.howto.shadows
  "Shadow & effect showcase — every shadow type side by side.

   Demonstrates:
   - Drop shadow, inner shadow, box shadow
   - Outer/inner glow
   - Material Design shadow (ShadowUtils)
   - Emboss, morphology (dilate/erode)
   - Blur and backdrop blur (frosted glass)
   - Filter composition for multi-shadow effects

   Launch: (open :howto/shadows)"
  (:require [lib.graphics.shapes :as shapes]
            [lib.graphics.filters :as filters]
            [lib.graphics.shadows :as shadows]
            [lib.graphics.layers :as layers]
            [lib.graphics.path :as path]
            [lib.text.core :as text]
            [lib.color.core :as color]
            [lib.color.open-color :as oc])
  (:import [io.github.humbleui.skija Canvas]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Grid Layout
;; ============================================================

(def cols 7)
(def card-w 180)
(def card-h 200)
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
                       {:color [0.85 0.83 0.80 1.0]}))

(defn- draw-label
  "Draw card title label."
  [^Canvas canvas x y title]
  (text/text canvas title
             (+ x (/ card-w 2.0)) (+ y 20)
             {:size 12 :align :center :color oc/gray-8}))

;; ============================================================
;; Card Shape Helpers
;; ============================================================

(def ^:private shape-cx 90)
(def ^:private shape-cy 115)
(def ^:private shape-r 35)
(def ^:private shape-color oc/blue-6)
(def ^:private rect-color oc/violet-6)

(defn- draw-shape-circle
  "Draw the demo circle within a card at offset (x, y)."
  [^Canvas canvas x y opts]
  (shapes/circle canvas (+ x shape-cx) (+ y shape-cy) shape-r
                 (merge {:color shape-color} opts)))

(defn- draw-shape-rrect
  "Draw a demo rounded-rect within a card at offset (x, y)."
  [^Canvas canvas x y opts]
  (shapes/rounded-rect canvas (+ x 30) (+ y 80) 120 70 10
                       (merge {:color rect-color} opts)))

;; ============================================================
;; Individual Cards
;; ============================================================

(defn- card-drop-shadow [^Canvas canvas x y]
  (draw-label canvas x y "Drop Shadow")
  (draw-shape-circle canvas x y
    {:shadow {:dx 4 :dy 4 :blur 8 :color [0 0 0 0.5]}}))

(defn- card-drop-shadow-only [^Canvas canvas x y]
  (draw-label canvas x y "Shadow Only")
  (shapes/circle canvas (+ x shape-cx) (+ y shape-cy) shape-r
    {:color shape-color
     :image-filter (filters/drop-shadow-only 4 4 6 [0 0 0 0.7])}))

(defn- card-outer-glow [^Canvas canvas x y]
  (draw-label canvas x y "Outer Glow")
  (draw-shape-circle canvas x y
    {:glow {:size 15 :mode :outer}
     :color oc/cyan-5}))

(defn- card-inner-glow [^Canvas canvas x y]
  (draw-label canvas x y "Inner Glow")
  (draw-shape-circle canvas x y
    {:glow {:size 10 :mode :inner}
     :color oc/teal-5}))

(defn- card-blur [^Canvas canvas x y]
  (draw-label canvas x y "Blur")
  (draw-shape-circle canvas x y {:blur 5.0}))

(defn- card-backdrop-blur [^Canvas canvas x y]
  ;; Draw colorful shapes behind, then frosted glass panel on top
  (shapes/circle canvas (+ x 70) (+ y 100) 25 {:color oc/red-5})
  (shapes/circle canvas (+ x 110) (+ y 100) 25 {:color oc/green-5})
  (shapes/circle canvas (+ x 90) (+ y 130) 25 {:color oc/blue-5})
  (layers/with-layer [canvas {:backdrop (filters/blur 8)}]
    (shapes/rounded-rect canvas (+ x 40) (+ y 85) 100 60 8
                         {:color [1 1 1 0.15]}))
  ;; Label drawn last so it's not blurred
  (draw-label canvas x y "Backdrop Blur"))

(defn- card-inner-shadow [^Canvas canvas x y]
  (draw-label canvas x y "Inner Shadow")
  (draw-shape-rrect canvas x y
    {:inner-shadow {:dx 3 :dy 3 :blur 6 :color [0 0 0 0.6]}}))

(defn- card-box-shadow-outer [^Canvas canvas x y]
  (draw-label canvas x y "Box Shadow")
  (draw-shape-rrect canvas x y
    {:box-shadow {:dx 4 :dy 4 :blur 10 :spread 3 :color [0 0 0 0.4]}}))

(defn- card-box-shadow-inset [^Canvas canvas x y]
  (draw-label canvas x y "Box Shadow Inset")
  (draw-shape-rrect canvas x y
    {:box-shadow {:dx 2 :dy 2 :blur 6 :spread 1 :color [0 0 0 0.5] :inset true}}))

(defn- card-material-shadow [^Canvas canvas x y]
  (draw-label canvas x y "Material Shadow")
  (let [px (+ x 30) py (+ y 80)
        p (path/rrect px py 120 70 10)]
    (shadows/draw-shadow canvas p {:z-height 12
                                   :ambient [0 0 0 0.12]
                                   :spot [0 0 0 0.3]})
    (shapes/path canvas p {:color [0.95 0.95 0.97 1]})))

(defn- card-emboss-kernel [^Canvas canvas x y]
  (draw-label canvas x y "Emboss Kernel")
  ;; Convolution-based emboss gives a classic raised/chiseled look
  (shapes/rounded-rect canvas (+ x 30) (+ y 80) 120 70 10
    {:color oc/gray-5
     :image-filter (filters/matrix-convolution
                     filters/emboss-kernel
                     {:width 3 :height 3 :gain 1.0 :bias 0.5})}))

(defn- card-emboss-light [^Canvas canvas x y]
  ;; Lighting-based emboss via specular distant light
  (draw-shape-rrect canvas x y
    {:color oc/gray-5
     :emboss {:azimuth 135 :elevation 45}})
  ;; Label drawn after so lighting doesn't cover it
  (draw-label canvas x y "Emboss Light"))

(defn- card-dilate [^Canvas canvas x y]
  (draw-label canvas x y "Dilate")
  (draw-shape-circle canvas x y
    {:color oc/red-6 :dilate 3}))

(defn- card-erode [^Canvas canvas x y]
  (draw-label canvas x y "Erode")
  (draw-shape-circle canvas x y
    {:color oc/green-6 :erode 2}))

(defn- card-multi-shadow [^Canvas canvas x y]
  (draw-label canvas x y "Multi Shadow")
  ;; Compose a drop shadow + inner shadow via filter composition
  (let [outer (filters/drop-shadow 3 3 6 [0 0 0 0.5])
        inner (filters/inner-shadow 2 2 4 [0 0 0 0.4])
        combined (filters/compose inner outer)]
    (shapes/rounded-rect canvas (+ x 30) (+ y 80) 120 70 10
                         {:color oc/indigo-5 :image-filter combined})))

(defn- card-colored-shadow [^Canvas canvas x y]
  (draw-label canvas x y "Colored Shadow")
  (draw-shape-circle canvas x y
    {:color oc/pink-5
     :shadow {:dx 0 :dy 6 :blur 12 :color [0.86 0.26 0.56 0.5]}}))

(defn- card-long-shadow [^Canvas canvas x y]
  (draw-label canvas x y "Long Shadow")
  ;; Stack multiple offset shadows for depth, merge them all + source
  (let [steps 8
        shadow-filters (for [i (range 1 (inc steps))]
                         (filters/drop-shadow-only (* i 2) (* i 2) 1
                                                   [0 0 0 (/ 0.4 steps)]))
        ;; Merge all shadow layers + nil (original source) on top
        merged (apply filters/merge-filters (concat shadow-filters [nil]))]
    (shapes/circle canvas (+ x shape-cx) (+ y shape-cy) shape-r
                   {:color oc/orange-5
                    :image-filter merged})))

(defn- card-rect-shadow-clip [^Canvas canvas x y]
  (draw-label canvas x y "Rect Shadow")
  (let [rx (+ x 30) ry (+ y 75)]
    (shapes/rect-shadow canvas rx ry 120 70
                        {:dy 4 :blur 10 :color [0 0 0 0.5]})
    (shapes/rounded-rect canvas rx ry 120 70 10
                         {:color [0.95 0.95 0.97 1]})))

(defn- card-rect-shadow-noclip [^Canvas canvas x y]
  (draw-label canvas x y "Rect Shadow Noclip")
  (let [rx (+ x 30) ry (+ y 75)]
    (shapes/rect-shadow canvas rx ry 120 70
                        {:dy 4 :blur 14 :color [0 0 0 0.6] :clip false})
    (shapes/rounded-rect canvas rx ry 120 70 10
                         {:color [0.95 0.95 0.97 0.4]})
    (shapes/rounded-rect canvas rx ry 120 70 10
                         {:mode :stroke :stroke-width 1 :color [1 1 1 0.5]})))

;; ============================================================
;; Card Registry
;; ============================================================

(def ^:private cards
  [card-drop-shadow
   card-drop-shadow-only
   card-outer-glow
   card-inner-glow
   card-blur
   card-backdrop-blur
   card-inner-shadow
   card-box-shadow-outer
   card-box-shadow-inset
   card-material-shadow
   card-emboss-kernel
   card-emboss-light
   card-dilate
   card-erode
   card-multi-shadow
   card-colored-shadow
   card-long-shadow
   card-rect-shadow-clip
   card-rect-shadow-noclip])

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Shadows howto loaded"))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas w h]
  ;; Title
  (text/text canvas "Shadows & Effects"
             (/ w 2.0) 35
             {:size 28 :weight :medium :align :center :color color/white})
  (text/text canvas "Every shadow and effect type side by side"
             (/ w 2.0) 60
             {:size 14 :align :center :color oc/gray-5})

  ;; Draw grid of cards (each clipped to its bounds)
  (doseq [[i card-fn] (map-indexed vector cards)]
    (let [[x y] (card-pos i)]
      (draw-card-bg canvas x y)
      (.save canvas)
      (.clipRect canvas (Rect/makeXYWH (float x) (float y) (float card-w) (float card-h)))
      (card-fn canvas x y)
      (.restore canvas))))

(defn cleanup []
  (println "Shadows howto cleanup"))

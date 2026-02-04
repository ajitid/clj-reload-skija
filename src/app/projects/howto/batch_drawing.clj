(ns app.projects.howto.batch-drawing
  "Batch Drawing — demos all batch draw operations.

   Showcases points, lines, polygon, triangles, triangle-strip, and patch
   using lib.graphics.batch. Each section is animated via game-time."
  (:require [lib.graphics.batch :as batch]
            [lib.graphics.shapes :as shapes]
            [lib.color.core :as color]
            [lib.color.open-color :as oc]
            [lib.text.core :as text]
            [app.state.system :as sys])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Layout helpers
;; ============================================================

(def ^:private cols 3)
(def ^:private rows 2)
(def ^:private pad 20)
(def ^:private header-h 30)

(defn- cell-rect
  "Return [x y w h] for grid cell at col, row given canvas size."
  [col row cw ch]
  (let [cell-w (/ (- cw (* pad (inc cols))) cols)
        cell-h (/ (- ch (* pad (inc rows))) rows)
        x (+ pad (* col (+ cell-w pad)))
        y (+ pad (* row (+ cell-h pad)))]
    [x y cell-w cell-h]))

(defn- draw-section-label [^Canvas canvas label x y w]
  (text/text canvas label
             (+ x (/ w 2.0)) (+ y 18)
             {:size 13 :weight :medium :align :center :color oc/gray-4}))

;; ============================================================
;; Section 1: Points
;; ============================================================

(defn- draw-points-demo [^Canvas canvas x y w h t]
  (draw-section-label canvas "points" x y w)
  (let [cx (+ x (/ w 2.0))
        cy (+ y header-h (/ (- h header-h) 2.0))
        n 36
        pts (vec (for [i (range n)]
                   (let [angle (+ (* i (/ (* 2 Math/PI) n)) (* t 0.5))
                         r (+ 40 (* 20 (Math/sin (+ (* t 2) (* i 0.3)))))
                         px (+ cx (* r (Math/cos angle)))
                         py (+ cy (* r (Math/sin angle)))]
                     [px py])))]
    (batch/points canvas pts 4 {:color oc/cyan-5})))

;; ============================================================
;; Section 2: Lines
;; ============================================================

(defn- draw-lines-demo [^Canvas canvas x y w h t]
  (draw-section-label canvas "lines" x y w)
  (let [cx (+ x (/ w 2.0))
        cy (+ y header-h (/ (- h header-h) 2.0))
        n 12
        line-data (float-array
                    (mapcat (fn [i]
                              (let [angle (* i (/ (* 2 Math/PI) n))
                                    r-inner (+ 15 (* 5 (Math/sin (+ (* t 3) i))))
                                    r-outer (+ 50 (* 15 (Math/sin (+ (* t 1.5) (* i 0.7)))))]
                                [(+ cx (* r-inner (Math/cos angle)))
                                 (+ cy (* r-inner (Math/sin angle)))
                                 (+ cx (* r-outer (Math/cos angle)))
                                 (+ cy (* r-outer (Math/sin angle)))]))
                            (range n)))]
    (batch/lines canvas line-data {:color oc/yellow-5 :stroke-width 2 :stroke-cap :round})))

;; ============================================================
;; Section 3: Polygon
;; ============================================================

(defn- draw-polygon-demo [^Canvas canvas x y w h t]
  (draw-section-label canvas "polygon" x y w)
  (let [cx (+ x (/ w 2.0))
        cy (+ y header-h (/ (- h header-h) 2.0))
        spikes 5
        n (* spikes 2)
        pts (vec (for [i (range n)]
                   (let [angle (+ (* i (/ Math/PI spikes)) (* t 0.6) (/ Math/PI -2.0))
                         r (if (even? i)
                             (+ 55 (* 8 (Math/sin (* t 2))))
                             (+ 22 (* 5 (Math/sin (+ (* t 2.5) 1)))))]
                     [(+ cx (* r (Math/cos angle)))
                      (+ cy (* r (Math/sin angle)))])))]
    (batch/polygon canvas pts {:color oc/grape-5 :stroke-width 2.5 :stroke-cap :round :stroke-join :round})))

;; ============================================================
;; Section 4: Triangles
;; ============================================================

(defn- draw-triangles-demo [^Canvas canvas x y w h t]
  (draw-section-label canvas "triangles" x y w)
  (let [cx (+ x (/ w 2.0))
        cy (+ y header-h (/ (- h header-h) 2.0))
        ;; Classic RGB triangle, gently rotating
        spread (+ 55 (* 5 (Math/sin (* t 1.2))))
        rot (* t 0.4)
        p0 [(+ cx (* spread (Math/cos (+ rot (/ (* 2 Math/PI) 3) (/ Math/PI 2)))))
            (+ cy (* spread (Math/sin (+ rot (/ (* 2 Math/PI) 3) (/ Math/PI 2)))))]
        p1 [(+ cx (* spread (Math/cos (+ rot (* 2 (/ (* 2 Math/PI) 3)) (/ Math/PI 2)))))
            (+ cy (* spread (Math/sin (+ rot (* 2 (/ (* 2 Math/PI) 3)) (/ Math/PI 2)))))]
        p2 [(+ cx (* spread (Math/cos (+ rot (/ Math/PI 2)))))
            (+ cy (* spread (Math/sin (+ rot (/ Math/PI 2)))))]]
    (batch/triangles canvas
      [p0 p1 p2]
      [[1 0 0 1] [0 1 0 1] [0 0 1 1]])))

;; ============================================================
;; Section 5: Triangle Strip
;; ============================================================

(defn- draw-strip-demo [^Canvas canvas x y w h t]
  (draw-section-label canvas "triangle-strip" x y w)
  (let [area-y (+ y header-h 10)
        area-h (- h header-h 20)
        cy (+ area-y (/ area-h 2.0))
        n-pairs 8
        ribbon-w (* w 0.85)
        x0 (+ x (/ (- w ribbon-w) 2.0))
        step (/ ribbon-w (dec n-pairs))
        pts (vec (mapcat
                   (fn [i]
                     (let [px (+ x0 (* i step))
                           wave (* 15 (Math/sin (+ (* t 2.0) (* i 0.8))))
                           half-h (+ 18 (* 8 (Math/sin (+ (* t 1.3) (* i 0.5)))))]
                       [[px (- cy half-h wave)]
                        [px (+ cy half-h wave)]]))
                   (range n-pairs)))
        hue-step (/ 1.0 n-pairs)
        colors (vec (mapcat
                      (fn [i]
                        (let [hue (* 360.0 (mod (+ (* i hue-step) (* t 0.1)) 1.0))
                              c (color/hsl hue 0.75 0.6)]
                          [c c]))
                      (range n-pairs)))]
    (batch/triangle-strip canvas pts colors)))

;; ============================================================
;; Section 6: Patch
;; ============================================================

(defn- draw-patch-demo [^Canvas canvas x y w h t]
  (draw-section-label canvas "patch" x y w)
  (let [cx (+ x (/ w 2.0))
        cy (+ y header-h (/ (- h header-h) 2.0))
        s 50
        ;; Animate control-point bulge
        bx (* 25 (Math/sin (* t 0.9)))
        by (* 25 (Math/cos (* t 1.1)))
        ;; 12 control points: top(4), right(4), bottom(4), left(4)
        ;; but only 12 total - see Skia docs
        cubics [;; top edge: TL -> TR
                [(- cx s) (- cy s)]
                [(- cx (/ s 3)) (+ (- cy s) by)]
                [(+ cx (/ s 3)) (- (- cy s) by)]
                [(+ cx s) (- cy s)]
                ;; right edge: TR -> BR
                [(+ (+ cx s) bx) (- cy (/ s 3))]
                [(- (+ cx s) bx) (+ cy (/ s 3))]
                [(+ cx s) (+ cy s)]
                ;; bottom edge: BR -> BL
                [(+ cx (/ s 3)) (- (+ cy s) by)]
                [(- cx (/ s 3)) (+ (+ cy s) by)]
                [(- cx s) (+ cy s)]
                ;; left edge: BL -> TL
                [(- (- cx s) bx) (+ cy (/ s 3))]
                [(+ (- cx s) bx) (- cy (/ s 3))]]
        ;; Pulse corner colors
        a1 (+ 0.5 (* 0.5 (Math/sin (* t 1.5))))
        a2 (+ 0.5 (* 0.5 (Math/sin (+ (* t 1.5) 1.5))))
        a3 (+ 0.5 (* 0.5 (Math/sin (+ (* t 1.5) 3.0))))
        a4 (+ 0.5 (* 0.5 (Math/sin (+ (* t 1.5) 4.5))))
        colors [[1 0 0 a1] [0 1 0 a2] [0 0 1 a3] [1 1 0 a4]]]
    (batch/patch canvas cubics colors)))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Batch drawing howto loaded"))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas w h]
  (let [t @sys/game-time]
    ;; Row 0
    (let [[x y cw ch] (cell-rect 0 0 w h)]
      (draw-points-demo canvas x y cw ch t))
    (let [[x y cw ch] (cell-rect 1 0 w h)]
      (draw-lines-demo canvas x y cw ch t))
    (let [[x y cw ch] (cell-rect 2 0 w h)]
      (draw-polygon-demo canvas x y cw ch t))
    ;; Row 1
    (let [[x y cw ch] (cell-rect 0 1 w h)]
      (draw-triangles-demo canvas x y cw ch t))
    (let [[x y cw ch] (cell-rect 1 1 w h)]
      (draw-strip-demo canvas x y cw ch t))
    (let [[x y cw ch] (cell-rect 2 1 w h)]
      (draw-patch-demo canvas x y cw ch t))))

(defn cleanup []
  (println "Batch drawing howto cleanup"))

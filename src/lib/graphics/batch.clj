(ns lib.graphics.batch
  "Batch drawing functions - high-performance rendering.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:require [lib.graphics.state :as gfx])
  (:import [io.github.humbleui.skija Canvas Paint PaintMode PaintStrokeCap]))

;; ============================================================
;; Batch Points
;; ============================================================

(defn points
  "Draw multiple points (circles) using batched API for high performance.

   This is much faster than drawing individual circles when you have many points.
   Automatically accepts flexible input formats.

   Args:
     canvas  - drawing canvas
     points  - float array [x1 y1 x2 y2 ...], OR
               sequence of {:x :y} maps, OR
               sequence of [x y] vectors
     radius  - point radius
     opts    - optional map (all paint options supported, see shapes/circle)

   Examples:
     ;; Float array (fastest, zero allocations)
     (points canvas (float-array [100 100 200 200]) 5)

     ;; Maps (idiomatic)
     (points canvas [{:x 100 :y 100} {:x 200 :y 200}] 5 {:color [0.29 0.56 0.85 1.0]})

     ;; Vectors (simple)
     (points canvas [[100 100] [200 200]] 5)

     ;; With effects
     (points canvas points-data 3 {:gradient {:type :radial :cx 100 :cy 100 :radius 50
                                               :colors [[1 0 0 1] [0 0 1 1]]}})"
  ([^Canvas canvas points radius]
   (points canvas points radius {}))
  ([^Canvas canvas points radius opts]
   (let [;; Convert to float array if needed
         ^floats point-array
         (cond
           ;; Already a float array
           (instance? (Class/forName "[F") points)
           points

           ;; Sequence of maps {:x :y}
           (and (seq points) (map? (first points)))
           (float-array (mapcat (fn [p] [(:x p) (:y p)]) points))

           ;; Sequence of vectors [x y]
           (and (seq points) (vector? (first points)))
           (float-array (mapcat identity points))

           ;; Unknown format
           :else
           (throw (ex-info "Invalid points format" {:points points})))]

     (if-let [paint (:paint opts)]
       (.drawPoints canvas point-array paint)
       (gfx/with-paint [paint (assoc opts
                                     :mode :stroke
                                     :stroke-width (* 2 radius)
                                     :stroke-cap :round)]
         (.drawPoints canvas point-array paint))))))

;; ============================================================
;; Batch Lines
;; ============================================================

(defn lines
  "Draw multiple line segments using batched API for high performance.

   Args:
     canvas - drawing canvas
     lines  - float array of [x1 y1 x2 y2 x3 y3 x4 y4 ...]
              where each 4 floats define one line segment
     opts   - optional map (all paint options supported, see shapes/circle)
              Note: :mode is automatically set to :stroke

   Examples:
     ;; Draw 2 line segments: (0,0)-(100,100) and (100,100)-(200,50)
     (lines canvas (float-array [0 0 100 100
                                  100 100 200 50]))

     ;; With styling
     (lines canvas line-data {:color [0.29 0.56 0.85 1.0] :stroke-width 2 :stroke-cap :round})

     ;; With effects
     (lines canvas line-data {:gradient {:type :linear :x0 0 :y0 0 :x1 100 :y1 0
                                         :colors [[1 0 0 1] [0 0 1 1]]}})"
  ([^Canvas canvas lines]
   (lines canvas lines {}))
  ([^Canvas canvas ^floats lines opts]
   (if-let [paint (:paint opts)]
     (.drawLines canvas lines paint)
     (gfx/with-paint [paint (assoc opts :mode :stroke)]
       (.drawLines canvas lines paint)))))

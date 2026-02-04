(ns lib.graphics.batch
  "Batch drawing functions - high-performance rendering.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:require [lib.graphics.state :as gfx])
  (:import [io.github.humbleui.skija Canvas Paint PaintMode PaintStrokeCap BlendMode]
           [io.github.humbleui.types Point]))

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

   Note — Skia drawLines limitations:
     - :stroke-join is ignored — segments are drawn independently
     - :stroke-cap and :stroke-width are respected

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

;; ============================================================
;; Helpers (private)
;; ============================================================

(defn- ->float-array
  "Convert flexible position formats to flat float[] [x1 y1 x2 y2 ...].
   Accepts: float[], [[x y] ...], [{:x :y} ...]"
  ^floats [coords]
  (cond
    (instance? (Class/forName "[F") coords)
    coords

    (and (seq coords) (vector? (first coords)))
    (float-array (mapcat identity coords))

    (and (seq coords) (map? (first coords)))
    (float-array (mapcat (fn [p] [(:x p) (:y p)]) coords))

    :else
    (throw (ex-info "Invalid coordinate format" {:coords coords}))))

(defn- ->point-array
  "Convert flexible position formats to Point[].
   Accepts: Point[], float[] [x1 y1 ...], [[x y] ...], [{:x :y} ...]"
  [coords]
  (cond
    (and (.isArray (class coords))
         (= Point (.getComponentType (class coords))))
    coords

    (instance? (Class/forName "[F") coords)
    (Point/fromArray ^floats coords)

    (and (seq coords) (vector? (first coords)))
    (into-array Point (map (fn [[x y]] (Point. (float x) (float y))) coords))

    (and (seq coords) (map? (first coords)))
    (into-array Point (map (fn [p] (Point. (float (:x p)) (float (:y p)))) coords))

    :else
    (throw (ex-info "Invalid coordinate format for ->point-array" {:coords coords}))))

(defn- ->color-int-array
  "Convert [[r g b a] ...] float colors to int[] ARGB.
   nil → nil, int[] → pass through, [[r g b a] ...] → int[]"
  [colors]
  (cond
    (nil? colors) nil

    (instance? (Class/forName "[I") colors)
    colors

    :else
    (int-array (map (fn [[r g b a]]
                      (let [af (float (or a 1.0))]
                        (unchecked-int
                          (bit-or (bit-shift-left (int (* 255.0 af)) 24)
                                  (bit-shift-left (int (* 255.0 (float r))) 16)
                                  (bit-shift-left (int (* 255.0 (float g))) 8)
                                  (int (* 255.0 (float b)))))))
                    colors))))

(defn- ->blend-mode
  "Convert keyword to BlendMode enum. Passes through BlendMode instances."
  [mode]
  (if (instance? BlendMode mode)
    mode
    (case mode
      :clear BlendMode/CLEAR
      :src BlendMode/SRC
      :dst BlendMode/DST
      :src-over BlendMode/SRC_OVER
      :dst-over BlendMode/DST_OVER
      :src-in BlendMode/SRC_IN
      :dst-in BlendMode/DST_IN
      :src-out BlendMode/SRC_OUT
      :dst-out BlendMode/DST_OUT
      :src-atop BlendMode/SRC_ATOP
      :dst-atop BlendMode/DST_ATOP
      :xor BlendMode/XOR
      :plus BlendMode/PLUS
      :modulate BlendMode/MODULATE
      :screen BlendMode/SCREEN
      :overlay BlendMode/OVERLAY
      :darken BlendMode/DARKEN
      :lighten BlendMode/LIGHTEN
      :multiply BlendMode/MULTIPLY)))

;; ============================================================
;; Polygon
;; ============================================================

(defn polygon
  "Draw a polygon through all points (connected line segments).

   Uses Skia's drawPolygon for a single draw call. Mode is forced to :stroke.

   Args:
     canvas - drawing canvas
     coords - float array [x1 y1 x2 y2 ...], OR
              sequence of [x y] vectors, OR
              sequence of {:x :y} maps
     opts   - optional map (all paint options supported)
              Note: :mode is forced to :stroke

   Note — Skia drawPolygon limitations:
     - Draws an **open** polygon (does not connect last point back to first).
       To close, include the first point again at the end of coords.
     - :stroke-join and :stroke-miter are ignored — Skia draws each segment independently
     - :mode is forced to :stroke — fill is not supported by drawPolygon
     - :stroke-cap and :stroke-width are respected
     - For joins/fill/close, use lib.graphics.path/polygon + shapes/path instead

   Examples:
     (polygon canvas [[100 100] [200 50] [300 100] [200 200]])
     (polygon canvas (float-array [100 100 200 50 300 100]) {:color [1 0 0 1] :stroke-width 3})"
  ([^Canvas canvas coords]
   (polygon canvas coords {}))
  ([^Canvas canvas coords opts]
   (let [^floats float-arr (->float-array coords)]
     (if-let [paint (:paint opts)]
       (.drawPolygon canvas float-arr paint)
       (gfx/with-paint [paint (assoc opts :mode :stroke)]
         (.drawPolygon canvas float-arr paint))))))

;; ============================================================
;; Triangles
;; ============================================================

(defn triangles
  "Draw a triangle mesh with per-vertex colors.

   Every 3 positions form one triangle. Colors are interpolated across each triangle face.

   Args:
     canvas    - drawing canvas
     positions - Point[], float[] [x1 y1 ...], [[x y] ...], or [{:x :y} ...]
     colors    - per-vertex colors as [[r g b a] ...] or int[] ARGB, or nil
     opts      - optional map:
                 :tex-coords  - texture coordinates (same formats as positions)
                 :indices     - index buffer (sequence of shorts)
                 :blend-mode  - keyword (default :dst)
                 Plus all paint options

   Examples:
     ;; Classic RGB triangle
     (triangles canvas
       [[200 50] [50 300] [350 300]]
       [[1 0 0 1] [0 1 0 1] [0 0 1 1]])"
  ([^Canvas canvas positions colors]
   (triangles canvas positions colors {}))
  ([^Canvas canvas positions colors opts]
   (let [point-arr (->point-array positions)
         color-arr (->color-int-array colors)
         {:keys [tex-coords indices blend-mode]} opts
         paint-opts (dissoc opts :tex-coords :indices :blend-mode)]
     (if tex-coords
       (let [tex-arr (->point-array tex-coords)
             idx-arr (when indices (short-array (map short indices)))
             bm (->blend-mode (or blend-mode :dst))]
         (if-let [paint (:paint opts)]
           (.drawTriangles canvas point-arr color-arr tex-arr idx-arr bm paint)
           (gfx/with-paint [paint paint-opts]
             (.drawTriangles canvas point-arr color-arr tex-arr idx-arr bm paint))))
       (if-let [paint (:paint opts)]
         (.drawTriangles canvas point-arr color-arr paint)
         (gfx/with-paint [paint paint-opts]
           (.drawTriangles canvas point-arr color-arr paint)))))))

;; ============================================================
;; Triangle Strip
;; ============================================================

(defn triangle-strip
  "Draw a triangle strip with per-vertex colors.

   Each new vertex forms a triangle with the previous two vertices,
   creating a connected ribbon of triangles.

   Args: same as `triangles`

   Examples:
     ;; Ribbon
     (triangle-strip canvas
       [[50 50] [50 150] [150 50] [150 150] [250 50] [250 150]]
       [[1 0 0 1] [1 0 0 1] [0 1 0 1] [0 1 0 1] [0 0 1 1] [0 0 1 1]])"
  ([^Canvas canvas positions colors]
   (triangle-strip canvas positions colors {}))
  ([^Canvas canvas positions colors opts]
   (let [point-arr (->point-array positions)
         color-arr (->color-int-array colors)
         {:keys [tex-coords indices blend-mode]} opts
         paint-opts (dissoc opts :tex-coords :indices :blend-mode)]
     (if tex-coords
       (let [tex-arr (->point-array tex-coords)
             idx-arr (when indices (short-array (map short indices)))
             bm (->blend-mode (or blend-mode :dst))]
         (if-let [paint (:paint opts)]
           (.drawTriangleStrip canvas point-arr color-arr tex-arr idx-arr bm paint)
           (gfx/with-paint [paint paint-opts]
             (.drawTriangleStrip canvas point-arr color-arr tex-arr idx-arr bm paint))))
       (if-let [paint (:paint opts)]
         (.drawTriangleStrip canvas point-arr color-arr paint)
         (gfx/with-paint [paint paint-opts]
           (.drawTriangleStrip canvas point-arr color-arr paint)))))))

;; ============================================================
;; Triangle Fan
;; ============================================================

(defn triangle-fan
  "Draw a triangle fan with per-vertex colors.

   The first vertex is the shared center; each subsequent pair of vertices
   forms a triangle with the center, creating a fan shape.

   Args: same as `triangles`

   Examples:
     ;; Fan from center
     (triangle-fan canvas
       [[200 200] [300 100] [350 200] [300 300] [200 300] [100 200] [150 100]]
       (repeat 7 [1 1 1 1]))"
  ([^Canvas canvas positions colors]
   (triangle-fan canvas positions colors {}))
  ([^Canvas canvas positions colors opts]
   (let [point-arr (->point-array positions)
         color-arr (->color-int-array colors)
         {:keys [tex-coords indices blend-mode]} opts
         paint-opts (dissoc opts :tex-coords :indices :blend-mode)]
     (if tex-coords
       (let [tex-arr (->point-array tex-coords)
             idx-arr (when indices (short-array (map short indices)))
             bm (->blend-mode (or blend-mode :dst))]
         (if-let [paint (:paint opts)]
           (.drawTriangleFan canvas point-arr color-arr tex-arr idx-arr bm paint)
           (gfx/with-paint [paint paint-opts]
             (.drawTriangleFan canvas point-arr color-arr tex-arr idx-arr bm paint))))
       (if-let [paint (:paint opts)]
         (.drawTriangleFan canvas point-arr color-arr paint)
         (gfx/with-paint [paint paint-opts]
           (.drawTriangleFan canvas point-arr color-arr paint)))))))

;; ============================================================
;; Coons Patch
;; ============================================================

(defn patch
  "Draw a Coons patch with corner color interpolation.

   A Coons patch is defined by 12 cubic Bézier control points forming 4 curves
   (top, right, bottom, left) and 4 corner colors that are smoothly interpolated
   across the surface.

   Args:
     canvas - drawing canvas
     cubics - 12 control points as Point[], float[], [[x y] ...], or [{:x :y} ...]
              Order: top curve (4 pts), right (4), bottom (4 reversed), left (4 reversed)
     colors - 4 corner colors as [[r g b a] ...] or int[4]
              Order: top-left, top-right, bottom-right, bottom-left
     opts   - optional map:
              :tex-coords - 4 texture coordinate points
              :blend-mode - keyword (default :modulate)
              Plus all paint options

   Examples:
     (patch canvas
       [[0 0] [100 -30] [200 -30] [300 0]     ;; top
        [330 100] [330 200] [300 300]           ;; right
        [200 330] [100 330] [0 300]             ;; bottom
        [-30 200] [-30 100]]                    ;; left
       [[1 0 0 1] [0 1 0 1] [0 0 1 1] [1 1 0 1]])"
  ([^Canvas canvas cubics colors]
   (patch canvas cubics colors {}))
  ([^Canvas canvas cubics colors opts]
   (let [point-arr (->point-array cubics)
         color-arr (->color-int-array colors)
         {:keys [tex-coords blend-mode]} opts
         paint-opts (dissoc opts :tex-coords :blend-mode)]
     (if tex-coords
       (let [tex-arr (->point-array tex-coords)
             bm (->blend-mode (or blend-mode :modulate))]
         (if-let [paint (:paint opts)]
           (.drawPatch canvas point-arr color-arr tex-arr bm paint)
           (gfx/with-paint [paint paint-opts]
             (.drawPatch canvas point-arr color-arr tex-arr bm paint))))
       (if-let [paint (:paint opts)]
         (.drawPatch canvas point-arr color-arr paint)
         (gfx/with-paint [paint paint-opts]
           (.drawPatch canvas point-arr color-arr paint)))))))

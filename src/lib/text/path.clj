(ns lib.text.path
  "Text rendered along a path using RSXform (GPU-optimized).

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:require [lib.text.core :as core]
            [lib.graphics.state :as gfx])
  (:import [io.github.humbleui.skija
            Canvas Paint Font TextBlob RSXform Path PathMeasure]))

;; ============================================================
;; Path Helpers
;; ============================================================

(defn circle-path
  "Create a circular path.

   Args:
     cx, cy - center coordinates
     radius - circle radius

   Returns: Path object"
  [cx cy radius]
  (doto (Path.)
    (.addCircle (float cx) (float cy) (float radius))))

(defn arc-path
  "Create an arc path.

   Args:
     cx, cy      - center coordinates
     radius      - arc radius
     start-angle - start angle in degrees (0 = right, 90 = bottom)
     sweep-angle - sweep angle in degrees (positive = clockwise)

   Returns: Path object"
  [cx cy radius start-angle sweep-angle]
  (doto (Path.)
    (.addArc (- cx radius) (- cy radius)
             (+ cx radius) (+ cy radius)
             (float start-angle) (float sweep-angle))))

(defn wave-path
  "Create a sinusoidal wave path.

   Args:
     x, y      - start coordinates
     width     - total width of wave
     amplitude - wave amplitude (height)
     frequency - number of complete waves

   Returns: Path object"
  [x y width amplitude frequency]
  (let [path (Path.)
        segments 100
        step (/ width segments)]
    (.moveTo path (float x) (float y))
    (doseq [i (range 1 (inc segments))]
      (let [px (+ x (* i step))
            angle (* 2 Math/PI frequency (/ i segments))
            py (+ y (* amplitude (Math/sin angle)))]
        (.lineTo path (float px) (float py))))
    path))

(defn line-path
  "Create a straight line path.

   Args:
     x0, y0 - start coordinates
     x1, y1 - end coordinates

   Returns: Path object"
  [x0 y0 x1 y1]
  (doto (Path.)
    (.moveTo (float x0) (float y0))
    (.lineTo (float x1) (float y1))))

;; ============================================================
;; Text on Path (RSXform-based)
;; ============================================================

(defn- get-glyphs-and-widths
  "Get glyph IDs and widths for text."
  [^Font font ^String text]
  (let [glyphs (.textToGlyphs font text)
        widths (.getWidths font glyphs)]
    [glyphs widths]))

(defn- compute-rsxforms
  "Compute RSXform array for placing glyphs along a path.

   Returns: array of RSXform, or nil if text doesn't fit"
  [^PathMeasure measure glyphs widths offset spacing]
  (let [path-length (.getLength measure)
        n (alength glyphs)
        rsxforms (make-array RSXform n)
        pos-tan (float-array 4)]  ; [x, y, tangent-x, tangent-y]
    ;; Start at offset
    (loop [i 0
           distance (float offset)]
      (if (>= i n)
        rsxforms
        (let [glyph-width (aget widths i)
              half-width (/ glyph-width 2.0)
              center-dist (+ distance half-width)]
          ;; Check if glyph center is within path
          (if (> center-dist path-length)
            nil  ; Text doesn't fit
            (do
              ;; Get position and tangent at center distance
              (.getPosTan measure center-dist pos-tan)
              (let [px (aget pos-tan 0)
                    py (aget pos-tan 1)
                    tx (aget pos-tan 2)
                    ty (aget pos-tan 3)
                    ;; Adjust position so glyph center sits on path
                    adj-x (- px (* half-width tx))
                    adj-y (- py (* half-width ty))]
                (aset rsxforms i (RSXform/make tx ty adj-x adj-y)))
              (recur (inc i)
                     (+ distance glyph-width spacing)))))))))

(defn text-on-path
  "Draw text along a path using RSXform (GPU-optimized single draw call).

   Args:
     canvas - drawing canvas
     text   - string to draw
     path   - Path object defining the curve
     opts   - optional map:

   Path Options:
     :offset  - starting offset along path (default: 0)
     :spacing - additional spacing between glyphs (default: 0)

   Font Options (same as core/text):
     :size, :weight, :slant, :family, :typeface, :variations

   Paint Options (same as shapes):
     :color, :shadow, :blur, :gradient, etc.

   Examples:
     (text-on-path canvas \"Hello!\" (circle-path 200 200 80) {:size 24})
     (text-on-path canvas \"Wave\" (wave-path 50 200 400 50 2) {:size 18 :color 0xFF0000FF})"
  ([^Canvas canvas text path]
   (text-on-path canvas text path {}))
  ([^Canvas canvas text path opts]
   (let [{:keys [offset spacing]
          :or {offset 0 spacing 0}} opts
         font (#'core/resolve-font opts)
         [glyphs widths] (get-glyphs-and-widths font (str text))
         measure (PathMeasure. path false)]
     (when-let [rsxforms (compute-rsxforms measure glyphs widths offset spacing)]
       (let [blob (TextBlob/makeFromRSXform glyphs rsxforms font)
             paint-opts (dissoc opts :offset :spacing :size :weight :slant
                                :family :typeface :variations)]
         (if-let [paint (:paint opts)]
           (.drawTextBlob canvas blob 0 0 paint)
           (gfx/with-paint [paint paint-opts]
             (.drawTextBlob canvas blob 0 0 paint))))))))

;; ============================================================
;; Measurement
;; ============================================================

(defn text-fits-path?
  "Check if text will fit along a path.

   Args:
     text   - string to check
     path   - Path object
     opts   - font options

   Returns: boolean"
  [text path opts]
  (let [{:keys [offset spacing] :or {offset 0 spacing 0}} opts
        font (#'core/resolve-font opts)
        [glyphs widths] (get-glyphs-and-widths font (str text))
        measure (PathMeasure. path false)
        path-length (.getLength measure)
        text-length (+ (* (dec (alength glyphs)) spacing)
                       (reduce + 0.0 widths))]
    (<= (+ offset text-length) path-length)))

(defn path-length
  "Get the total length of a path.

   Returns: length in pixels (float)"
  [path]
  (.getLength (PathMeasure. path false)))

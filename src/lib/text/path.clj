(ns lib.text.path
  "Text rendered along a path using RSXform (GPU-optimized).

   Use lib.graphics.path for creating paths, this namespace focuses
   on rendering text along those paths.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern).

   Example:
     (require '[lib.graphics.path :as path]
              '[lib.text.path :as text-path])

     (text-path/text-on-path canvas \"Hello!\"
       (path/circle 200 200 80)
       {:size 24})"
  (:require [lib.text.core :as core]
            [lib.graphics.state :as gfx])
  (:import [io.github.humbleui.skija
            Canvas Paint Font TextBlob RSXform PathMeasure]
           [io.github.humbleui.types Point]))

;; ============================================================
;; Internal Helpers
;; ============================================================

(defn- rsxform
  "Create an RSXform (rotation+scale+translation matrix).
   Wraps constructor for consistency with Skija's make* pattern."
  [scos ssin tx ty]
  (RSXform. (float scos) (float ssin) (float tx) (float ty)))

(defn- get-glyphs-and-widths
  "Get glyph IDs and widths for text."
  [^Font font ^String text]
  (let [glyphs (.getStringGlyphs font text)
        widths (.getWidths font glyphs)]
    [glyphs widths]))

(defn- compute-rsxforms
  "Compute RSXform array for placing glyphs along a path.

   Returns: array of RSXform, or nil if text doesn't fit"
  [^PathMeasure measure glyphs widths offset spacing]
  (let [path-length (.getLength measure)
        n (alength glyphs)]
    ;; Start at offset
    (loop [i 0
           distance (float offset)
           rsxforms (transient [])]
      (if (>= i n)
        (into-array RSXform (persistent! rsxforms))
        (let [glyph-width (aget widths i)
              half-width (/ glyph-width 2.0)
              center-dist (+ distance half-width)]
          ;; Check if glyph center is within path
          (if (> center-dist path-length)
            nil  ; Text doesn't fit
            (let [^Point pos (.getPosition measure (float center-dist))
                  ^Point tan (.getTangent measure (float center-dist))
                  px (.getX pos)
                  py (.getY pos)
                  tx (.getX tan)
                  ty (.getY tan)
                  ;; Adjust position so glyph center sits on path
                  adj-x (- px (* half-width tx))
                  adj-y (- py (* half-width ty))]
              (recur (inc i)
                     (+ distance glyph-width spacing)
                     (conj! rsxforms (rsxform tx ty adj-x adj-y))))))))))

;; ============================================================
;; Public API
;; ============================================================

(defn text-on-path
  "Draw text along a path using RSXform (GPU-optimized single draw call).

   Args:
     canvas - drawing canvas
     text   - string to draw
     path   - Path object (from lib.graphics.path)
     opts   - optional map:

   Path Options:
     :offset  - starting offset along path (default: 0)
     :spacing - additional spacing between glyphs (default: 0)

   Font Options (same as lib.text.core/text):
     :size, :weight, :slant, :family, :typeface, :variations

   Paint Options (same as lib.graphics.shapes):
     :color, :shadow, :blur, :gradient, etc.

   Examples:
     (require '[lib.graphics.path :as path])

     (text-on-path canvas \"Hello!\" (path/circle 200 200 80) {:size 24})
     (text-on-path canvas \"Wave\" (path/wave 50 200 400 50 2) {:size 18 :color 0xFF0000FF})
     (text-on-path canvas \"Arc\" (path/arc 200 200 100 -90 180) {:size 20})"
  ([canvas text path]
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

(defn text-fits-path?
  "Check if text will fit along a path.

   Args:
     text - string to check
     path - Path object (from lib.graphics.path)
     opts - font options (:size, :weight, :family, etc.)

   Returns: boolean

   Example:
     (when (text-fits-path? \"Hello World\" my-path {:size 24})
       (text-on-path canvas \"Hello World\" my-path {:size 24}))"
  [text path opts]
  (let [{:keys [offset spacing] :or {offset 0 spacing 0}} opts
        font (#'core/resolve-font opts)
        [glyphs widths] (get-glyphs-and-widths font (str text))
        measure (PathMeasure. path false)
        path-length (.getLength measure)
        text-length (+ (* (dec (alength glyphs)) spacing)
                       (reduce + 0.0 widths))]
    (<= (+ offset text-length) path-length)))

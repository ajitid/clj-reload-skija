(ns lib.text.measure
  "Text measurement utilities.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:require [lib.text.core :as core])
  (:import [io.github.humbleui.skija Font FontMetrics]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Internal Helpers
;; ============================================================

(defn- resolve-font
  "Resolve font from options map. Delegates to core."
  [opts]
  (#'core/resolve-font opts))

;; ============================================================
;; Text Width
;; ============================================================

(defn text-width
  "Measure text width.

   Args:
     text - string to measure
     opts - optional font options (see core/text)

   Returns: width in pixels (float)

   Examples:
     (text-width \"Hello\")
     (text-width \"Hello\" {:size 24})
     (text-width \"Bold\" {:size 24 :weight :bold})"
  ([text] (text-width text {}))
  ([text opts]
   (let [font (resolve-font opts)]
     (.measureTextWidth font (str text)))))

;; ============================================================
;; Text Bounds
;; ============================================================

(defn text-bounds
  "Measure text bounding box.

   Args:
     text - string to measure
     opts - optional font options (see core/text)

   Returns: {:width :height :left :top}
            left/top are offsets from the origin (can be negative)

   Examples:
     (text-bounds \"Hello\")
     (text-bounds \"Hello\" {:size 24})"
  ([text] (text-bounds text {}))
  ([text opts]
   (let [font (resolve-font opts)
         ^Rect rect (.measureText font (str text))]
     {:width (.getWidth rect)
      :height (.getHeight rect)
      :left (.getLeft rect)
      :top (.getTop rect)})))

;; ============================================================
;; Font Metrics
;; ============================================================

(defn font-metrics
  "Get font metrics.

   Args:
     opts - optional font options (see core/text)

   Returns: {:ascent :descent :height :leading :cap-height :x-height
             :underline-pos :underline-thickness :strikeout-pos :strikeout-thickness}

   Notes:
     - ascent is negative (distance above baseline)
     - descent is positive (distance below baseline)
     - height = descent - ascent + leading

   Examples:
     (font-metrics)
     (font-metrics {:size 24})
     (font-metrics {:size 24 :weight :bold})"
  ([] (font-metrics {}))
  ([opts]
   (let [font (resolve-font opts)
         ^FontMetrics m (.getMetrics font)]
     {:ascent (.getAscent m)
      :descent (.getDescent m)
      :height (.getHeight m)
      :leading (.getLeading m)
      :cap-height (.getCapHeight m)
      :x-height (.getXHeight m)
      :underline-pos (.getUnderlinePosition m)
      :underline-thickness (.getUnderlineThickness m)
      :strikeout-pos (.getStrikeoutPosition m)
      :strikeout-thickness (.getStrikeoutThickness m)})))

;; ============================================================
;; Line Height
;; ============================================================

(defn line-height
  "Get line height (spacing between baselines).

   Args:
     opts - optional font options (see core/text)

   Returns: line height in pixels (float)

   Examples:
     (line-height)
     (line-height {:size 24})"
  ([] (line-height {}))
  ([opts]
   (:height (font-metrics opts))))

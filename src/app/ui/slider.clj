(ns app.ui.slider
  "Generic slider widget for UI controls.

   A slider displays a label, value, and draggable track.
   Examples can use this to create parameter controls.

   Colors use [r g b a] float vectors (0.0-1.0) for Skia Color4f."
  (:require [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas Paint Color4f]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Default styling - [r g b a] floats
;; ============================================================

(def default-track-color [0.333 0.333 0.333 1.0])
(def default-fill-color [1.0 0.412 0.706 1.0])  ;; hot pink
(def default-label-color [0.667 0.667 0.667 1.0])
(def default-value-color [1.0 1.0 1.0 1.0])
(def default-height 16)
(def default-width 160)
(def default-font-size 14)

;; ============================================================
;; Value calculation
;; ============================================================

(defn value-from-x
  "Convert mouse x position to slider value.
   Returns integer value clamped to [min-val, max-val]."
  [mouse-x [sx _sy sw _sh] min-val max-val]
  (let [ratio (/ (- mouse-x sx) sw)
        ratio (max 0.0 (min 1.0 ratio))]
    (int (Math/round (double (+ min-val (* ratio (- max-val min-val))))))))

;; ============================================================
;; Drawing
;; ============================================================

(defn draw
  "Draw a slider with label and value above it.

   Parameters:
   - canvas: Skija Canvas
   - label: String label for the slider
   - value: Current value (number)
   - bounds: [x y w h] bounding box
   - opts: Map of options:
     - :min (default 1)
     - :max (default 100)
     - :track-color [r g b a] floats (default [0.333 0.333 0.333 1.0])
     - :fill-color [r g b a] floats (default [1.0 0.412 0.706 1.0])
     - :label-color [r g b a] floats (default [0.667 0.667 0.667 1.0])
     - :value-color [r g b a] floats (default [1.0 1.0 1.0 1.0])
     - :font-size (default 14)

   Layout:  Label: <value>
            [====slider====]"
  [^Canvas canvas label value [sx sy sw sh] opts]
  (let [min-val (or (:min opts) 1)
        max-val (or (:max opts) 100)
        [tr tg tb ta] (or (:track-color opts) default-track-color)
        [fr fg fb fa] (or (:fill-color opts) default-fill-color)
        label-color (or (:label-color opts) default-label-color)
        value-color (or (:value-color opts) default-value-color)
        font-size (or (:font-size opts) default-font-size)
        ratio (/ (- value min-val) (- max-val min-val))
        fill-w (* sw ratio)]
    ;; Draw label ABOVE the slider (dimmer color)
    (text/text canvas (str label ":") sx (- sy 4)
                   {:size font-size :color label-color})
    ;; Draw value next to label (brighter color)
    (let [label-width (* (count label) (/ font-size 1.5))]
      (text/text canvas (str value) (+ sx label-width 8) (- sy 4)
                     {:size font-size :color value-color}))
    ;; Draw track
    (with-open [track-paint (doto (Paint.)
                              (.setColor4f (Color4f. (float tr) (float tg) (float tb) (float ta))))]
      (.drawRect canvas (Rect/makeXYWH (float sx) (float sy) (float sw) (float sh)) track-paint))
    ;; Draw fill
    (with-open [fill-paint (doto (Paint.)
                             (.setColor4f (Color4f. (float fr) (float fg) (float fb) (float fa))))]
      (.drawRect canvas (Rect/makeXYWH (float sx) (float sy) (float fill-w) (float sh)) fill-paint))))

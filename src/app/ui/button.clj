(ns app.ui.button
  "Generic button widget for UI controls.

   A button displays a rectangular background (square corners)
   with a centered text label. Swaps color when pressed.

   Colors use [r g b a] float vectors (0.0-1.0) for Skia Color4f."
  (:require [lib.color.core :as color]
            [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas Paint Color4f]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Default styling - [r g b a] floats
;; ============================================================

(def default-color [0.333 0.333 0.333 1.0])
(def default-pressed-color [0.467 0.467 0.467 1.0])
(def default-text-color color/white)
(def default-font-size 16)

;; ============================================================
;; Hit testing
;; ============================================================

(defn hit-test?
  "Check if point (mx, my) is inside button bounds [bx by bw bh]."
  [[bx by bw bh] mx my]
  (and (>= mx bx) (<= mx (+ bx bw))
       (>= my by) (<= my (+ by bh))))

;; ============================================================
;; Drawing
;; ============================================================

(defn draw
  "Draw a button with centered text label.

   Parameters:
   - canvas: Skija Canvas
   - label: String label for the button
   - bounds: [x y w h] bounding box
   - opts: Map of options:
     - :color [r g b a] floats (default [0.333 0.333 0.333 1.0])
     - :pressed-color [r g b a] floats (default [0.467 0.467 0.467 1.0])
     - :text-color [r g b a] floats (default color/white)
     - :font-size (default 16)
     - :pressed? (default false)"
  [^Canvas canvas label [bx by bw bh] opts]
  (let [pressed? (or (:pressed? opts) false)
        [r g b a] (if pressed?
                    (or (:pressed-color opts) default-pressed-color)
                    (or (:color opts) default-color))
        text-color (or (:text-color opts) default-text-color)
        font-size (or (:font-size opts) default-font-size)]
    ;; Draw background rectangle (square corners)
    (with-open [bg-paint (doto (Paint.)
                           (.setColor4f (Color4f. (float r) (float g) (float b) (float a))))]
      (.drawRect canvas (Rect/makeXYWH (float bx) (float by) (float bw) (float bh)) bg-paint))
    ;; Draw centered text label
    (let [center-x (+ bx (/ bw 2))
          center-y (+ by (/ bh 2) (/ font-size 3))]
      (text/text canvas label center-x center-y
                 {:size font-size :color text-color :align :center}))))

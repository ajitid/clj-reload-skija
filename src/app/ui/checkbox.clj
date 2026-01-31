(ns app.ui.checkbox
  "Generic checkbox widget for UI controls.

   A checkbox displays a label with a checkable box.
   Examples can use this to create boolean controls.

   Colors use [r g b a] float vectors (0.0-1.0) for Skia Color4f."
  (:require [lib.color.core :as color]
            [lib.color.open-color :as oc]
            [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas Paint PaintMode Color4f]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Default styling - [r g b a] floats
;; ============================================================

(def default-box-size 14)
(def default-box-color [0.333 0.333 0.333 1.0])
(def default-check-color oc/green-4)  ;; green
(def default-text-color color/white)
(def default-font-size 14)
(def default-spacing 8)

;; ============================================================
;; Drawing
;; ============================================================

(defn draw
  "Draw checkbox with label.

   Parameters:
   - canvas: Skija Canvas
   - label: String label for the checkbox
   - checked?: Boolean indicating checked state
   - bounds: [x y w h] bounding box
   - opts: Map of options:
     - :box-size (default 14)
     - :box-color [r g b a] floats (default [0.333 0.333 0.333 1.0])
     - :check-color [r g b a] floats (default oc/green-4)
     - :text-color [r g b a] floats (default color/white)
     - :font-size (default 14)
     - :spacing (default 8)

   Layout: [checkmark] Label"
  [^Canvas canvas label checked? [x y w h] opts]
  (let [box-size (or (:box-size opts) default-box-size)
        [br bg bb ba] (or (:box-color opts) default-box-color)
        [cr cg cb ca] (or (:check-color opts) default-check-color)
        text-color (or (:text-color opts) default-text-color)
        font-size (or (:font-size opts) default-font-size)
        spacing (or (:spacing opts) default-spacing)
        box-y (+ y (/ (- h box-size) 2))]  ;; Center box vertically
    ;; Draw checkbox box
    (with-open [box-paint (doto (Paint.)
                            (.setColor4f (Color4f. (float br) (float bg) (float bb) (float ba))))]
      (.drawRect canvas (Rect/makeXYWH (float x) (float box-y) (float box-size) (float box-size)) box-paint))
    ;; Draw check mark if checked
    (when checked?
      (with-open [check-paint (doto (Paint.)
                                (.setMode PaintMode/STROKE)
                                (.setStrokeWidth (float 2.0))
                                (.setAntiAlias true)
                                (.setColor4f (Color4f. (float cr) (float cg) (float cb) (float ca))))]
        (let [check-x (+ x 3)
              check-y (+ box-y 3)
              check-size (- box-size 6)]
          ;; Draw checkmark as two lines
          (.drawLine canvas
                     (float check-x)
                     (float (+ check-y (/ check-size 2)))
                     (float (+ check-x (/ check-size 2)))
                     (float (+ check-y check-size))
                     check-paint)
          (.drawLine canvas
                     (float (+ check-x (/ check-size 2)))
                     (float (+ check-y check-size))
                     (float (+ check-x check-size))
                     (float check-y)
                     check-paint))))
    ;; Draw label to the right of the box (vertically centered better)
    (text/text canvas label
               (+ x box-size spacing)
               (+ y (/ h 2) 4)
               {:size font-size :color text-color})))

;; ============================================================
;; Hit testing
;; ============================================================

(defn hit-test?
  "Check if point is inside checkbox bounds."
  [[x y w h] mx my]
  (and (>= mx x)
       (<= mx (+ x w))
       (>= my y)
       (<= my (+ y h))))

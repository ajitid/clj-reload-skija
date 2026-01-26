(ns app.ui.checkbox
  "Generic checkbox widget for UI controls.

   A checkbox displays a label with a checkable box.
   Examples can use this to create boolean controls."
  (:require [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas Paint PaintMode]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Default styling
;; ============================================================

(def default-box-size 14)
(def default-box-color 0xFF555555)
(def default-check-color 0xFF4AE88C)
(def default-text-color 0xFFFFFFFF)
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
     - :box-size (default 16)
     - :box-color (default 0xFF555555)
     - :check-color (default 0xFF4AE88C)
     - :text-color (default 0xFFFFFFFF)
     - :font-size (default 18)
     - :spacing (default 8)

   Layout: [✓] Label"
  [^Canvas canvas label checked? [x y w h] opts]
  (let [box-size (or (:box-size opts) default-box-size)
        box-color (or (:box-color opts) default-box-color)
        check-color (or (:check-color opts) default-check-color)
        text-color (or (:text-color opts) default-text-color)
        font-size (or (:font-size opts) default-font-size)
        spacing (or (:spacing opts) default-spacing)
        box-y (+ y (/ (- h box-size) 2))]  ;; Center box vertically
    ;; Draw checkbox box
    (with-open [box-paint (doto (Paint.)
                            (.setColor (unchecked-int box-color)))]
      (.drawRect canvas (Rect/makeXYWH (float x) (float box-y) (float box-size) (float box-size)) box-paint))
    ;; Draw check mark if checked
    (when checked?
      (with-open [check-paint (doto (Paint.)
                                (.setMode PaintMode/STROKE)
                                (.setStrokeWidth (float 2.0))
                                (.setAntiAlias true)
                                (.setColor (unchecked-int check-color)))]
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

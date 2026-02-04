(ns app.shell.fps-display
  "FPS display component - top-left overlay.

   Shows FPS number and graph when @state/fps-display-visible? is true.
   Independent of the control panel."
  (:require [lib.color.core :as color]
            [lib.color.open-color :as oc]
            [app.shell.state :as state]
            [lib.graphics.batch :as batch]
            [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas Color4f Paint PaintMode]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Display configuration
;; ============================================================

(def display-x 10)
(def display-y 10)
(def display-width 200)
(def display-height 80)
(def display-padding 12)
(def display-bg-color (color/gray 0.153 0.87))
(def display-text-color color/white)
(def font-size 14)
(def text-graph-spacing 8)

;; FPS graph settings
(def fps-graph-height 40)
(def fps-graph-color oc/green-4)
(def fps-graph-bg-color (color/gray 0.11 0.87))
(def fps-target 60)

;; ============================================================
;; Pre-allocated resources (zero per-frame allocations)
;; ============================================================

(def fps-graph-bg-paint
  (let [[r g b a] fps-graph-bg-color]
    (doto (Paint.) (.setColor4f (Color4f. (float r) (float g) (float b) (float a))))))
(def fps-graph-line-paint
  (doto (Paint.) (.setColor4f (Color4f. 1.0 1.0 1.0 0.27))))
(def fps-graph-stroke-paint
  (let [[r g b a] fps-graph-color]
    (doto (Paint.)
      (.setMode PaintMode/STROKE)
      (.setStrokeWidth (float 1.5))
      (.setAntiAlias true)
      (.setColor4f (Color4f. (float r) (float g) (float b) (float a))))))
;; Line segments: (n-1) segments x 4 floats (x1,y1,x2,y2) each
(def fps-graph-lines (float-array (* (dec state/fps-history-size) 4)))

;; ============================================================
;; Drawing helpers
;; ============================================================

(defn draw-fps-graph
  "Draw FPS history as a line graph (ring buffer). Zero allocations."
  [^Canvas canvas x y w h]
  (let [^floats history state/fps-history
        current-idx (long @state/fps-history-idx)
        n (alength history)
        target-fps (double fps-target)
        max-fps (* target-fps 1.5)
        inv-max-fps (/ 1.0 max-fps)
        x (double x)
        y (double y)
        w (double w)
        h (double h)
        bottom (+ y h)
        step (/ w (dec n))
        ^floats lines fps-graph-lines]
    ;; Draw background
    (.drawRect canvas (Rect/makeXYWH (float x) (float y) (float w) (float h)) fps-graph-bg-paint)
    ;; Draw target line (60 FPS reference)
    (let [target-y (- bottom (* h target-fps inv-max-fps))]
      (.drawLine canvas (float x) (float target-y) (float (+ x w)) (float target-y) fps-graph-line-paint))
    ;; Build line segments: each segment is (prev-x, prev-y, curr-x, curr-y)
    (loop [i (long 0)
           prev-x x
           prev-y (let [fps (double (aget history (mod (inc current-idx) n)))]
                    (- bottom (* h (max 0.0 (min max-fps fps)) inv-max-fps)))]
      (when (< i (dec n))
        (let [ring-idx (mod (+ current-idx 2 i) n)
              fps (double (aget history ring-idx))
              clamped (max 0.0 (min max-fps fps))
              curr-x (+ x (* (inc i) step))
              curr-y (- bottom (* h clamped inv-max-fps))
              base (* i 4)]
          (aset lines base (float prev-x))
          (aset lines (+ base 1) (float prev-y))
          (aset lines (+ base 2) (float curr-x))
          (aset lines (+ base 3) (float curr-y))
          (recur (inc i) curr-x curr-y))))
    (batch/lines canvas lines {:paint fps-graph-stroke-paint})))

;; ============================================================
;; Main draw function
;; ============================================================

(defn draw
  "Draw FPS display at top-left when @state/fps-display-visible?"
  [^Canvas canvas]
  (when @state/fps-display-visible?
    (let [px display-x
          py display-y
          pw display-width
          ph display-height
          pad display-padding]
      ;; Draw panel background
      (let [[r g b a] display-bg-color]
        (with-open [bg-paint (doto (Paint.)
                               (.setColor4f (Color4f. (float r) (float g) (float b) (float a))))]
          (.drawRect canvas (Rect/makeXYWH (float px) (float py) (float pw) (float ph)) bg-paint)))
      ;; Draw FPS text at top
      (text/text canvas
                 (format "FPS: %.0f" (double @state/fps))
                 (+ px pad)
                 (+ py pad 12)
                 {:size font-size :color display-text-color})
      ;; Draw FPS graph below FPS text with proper spacing
      (let [graph-y (+ py pad 12 text-graph-spacing)
            graph-h fps-graph-height
            graph-w (- pw (* 2 pad))]
        (draw-fps-graph canvas (+ px pad) graph-y graph-w graph-h)))))

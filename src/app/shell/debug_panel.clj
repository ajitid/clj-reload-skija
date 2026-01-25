(ns app.shell.debug-panel
  "Debug panel UI - FPS display and graph.

   The debug panel is shell infrastructure that overlays on top of examples.
   Press Ctrl+` to toggle visibility."
  (:require [app.shell.state :as state]
            [lib.graphics.batch :as batch]
            [lib.graphics.text :as gfx-text])
  (:import [io.github.humbleui.skija Canvas Paint PaintMode]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Panel configuration
;; ============================================================

(def panel-right-offset 20)
(def panel-y 20)
(def panel-width 200)
(def panel-height 80)
(def panel-padding 15)
(def panel-bg-color 0xDD333333)
(def panel-text-color 0xFFFFFFFF)
(def font-size 18)

;; FPS graph settings
(def fps-graph-height 40)
(def fps-graph-color 0xFF4AE88C)
(def fps-graph-bg-color 0xFF1A1A1A)
(def fps-target 60)

;; ============================================================
;; Drawing helpers
;; ============================================================

(defn calc-panel-x
  "Calculate panel x position (right-aligned)"
  [window-width]
  (- window-width panel-width panel-right-offset))

;; Pre-allocated resources for FPS graph (zero per-frame allocations)
(def fps-graph-bg-paint (doto (Paint.) (.setColor (unchecked-int fps-graph-bg-color))))
(def fps-graph-line-paint (doto (Paint.) (.setColor (unchecked-int 0x44FFFFFF))))
(def fps-graph-stroke-paint
  (doto (Paint.)
    (.setMode PaintMode/STROKE)
    (.setStrokeWidth (float 1.5))
    (.setAntiAlias true)
    (.setColor (unchecked-int fps-graph-color))))
;; Line segments: (n-1) segments x 4 floats (x1,y1,x2,y2) each
(def fps-graph-lines (float-array (* (dec state/fps-history-size) 4)))

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

(defn draw-panel
  "Draw debug panel with FPS display at top-right."
  [^Canvas canvas window-width]
  (let [px (calc-panel-x window-width)
        py panel-y
        pw panel-width
        ph panel-height
        pad panel-padding]
    ;; Draw panel background
    (with-open [bg-paint (doto (Paint.)
                           (.setColor (unchecked-int panel-bg-color)))]
      (.drawRect canvas (Rect/makeXYWH (float px) (float py) (float pw) (float ph)) bg-paint))
    ;; Draw FPS text at top
    (gfx-text/text canvas
                   (format "FPS: %.0f" (double @state/fps))
                   (+ px pad)
                   (+ py pad 14)
                   {:size font-size :color panel-text-color})
    ;; Draw FPS graph below FPS text
    (let [graph-y (+ py pad 20)
          graph-h fps-graph-height
          graph-w (- pw (* 2 pad))]
      (draw-fps-graph canvas (+ px pad) graph-y graph-w graph-h))))

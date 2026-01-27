(ns app.projects.howto.text-measurement
  "Text Measurement - Font metrics, text bounds, hit testing, intrinsic widths.

   Demonstrates:
   - Font metrics visualization (ascent, descent, cap-height, x-height)
   - Text bounding box vs advance width
   - Interactive hit testing with character/word highlighting
   - Min/max intrinsic width comparison"
  (:require [app.state.system :as sys]
            [lib.text.core :as text]
            [lib.text.measure :as measure]
            [lib.text.paragraph :as para]
            [lib.graphics.shapes :as shapes])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def metrics-font-size 48)
(def metrics-text "Sphinx")
(def bounds-font-size 36)
(def bounds-text "Typography")
(def label-color 0xFF888888)

;; ============================================================
;; State (persists across hot-reloads)
;; ============================================================

(defonce click-index (atom nil))
(defonce click-word (atom nil))

;; ============================================================
;; Drawing: Font Metrics Section
;; ============================================================

(defn draw-metrics-section [^Canvas canvas x y]
  (text/text canvas "Font Metrics" x y
             {:size 20 :weight :medium :color 0xFFFFFFFF})
  (let [metrics (measure/font-metrics {:size metrics-font-size})
        baseline-y (+ y 80)
        text-x (+ x 120)
        line-end-x (+ text-x 280)
        label-x (+ line-end-x 10)]
    ;; Draw the text
    (text/text canvas metrics-text text-x baseline-y
               {:size metrics-font-size :color 0xFFFFFFFF})
    ;; Baseline (green)
    (shapes/line canvas text-x baseline-y line-end-x baseline-y
                 {:color 0xFF2ECC71 :stroke-width 1})
    (text/text canvas "baseline" label-x baseline-y {:size 11 :color 0xFF2ECC71})
    ;; Ascent (red) - ascent is negative, so add it to baseline
    (let [ascent-y (+ baseline-y (:ascent metrics))]
      (shapes/line canvas text-x ascent-y line-end-x ascent-y
                   {:color 0xFFE74C3C :stroke-width 1})
      (text/text canvas (format "ascent (%.1f)" (:ascent metrics))
                 label-x ascent-y {:size 11 :color 0xFFE74C3C}))
    ;; Descent (blue)
    (let [descent-y (+ baseline-y (:descent metrics))]
      (shapes/line canvas text-x descent-y line-end-x descent-y
                   {:color 0xFF3498DB :stroke-width 1})
      (text/text canvas (format "descent (%.1f)" (:descent metrics))
                 label-x descent-y {:size 11 :color 0xFF3498DB}))
    ;; Cap height (orange) - label on LEFT to avoid overlapping ascent
    (let [cap-y (- baseline-y (:cap-height metrics))]
      (shapes/line canvas text-x cap-y line-end-x cap-y
                   {:color 0xFFF39C12 :stroke-width 1})
      (text/text canvas (format "cap-height (%.1f)" (:cap-height metrics))
                 (- text-x 10) (+ cap-y 3) {:size 11 :color 0xFFF39C12 :align :right}))
    ;; X-height (purple)
    (let [xh-y (- baseline-y (:x-height metrics))]
      (shapes/line canvas text-x xh-y line-end-x xh-y
                   {:color 0xFF9B59B6 :stroke-width 1})
      (text/text canvas (format "x-height (%.1f)" (:x-height metrics))
                 label-x xh-y {:size 11 :color 0xFF9B59B6}))))

;; ============================================================
;; Drawing: Text Bounds Section
;; ============================================================

(defn draw-bounds-section [^Canvas canvas x y]
  (text/text canvas "Text Bounds" x y
             {:size 20 :weight :medium :color 0xFFFFFFFF})
  (let [bounds (measure/text-bounds bounds-text {:size bounds-font-size})
        advance (measure/text-width bounds-text {:size bounds-font-size})
        baseline-y (+ y 60)
        text-x (+ x 20)]
    ;; Draw text
    (text/text canvas bounds-text text-x baseline-y
               {:size bounds-font-size :color 0xFFFFFFFF})
    ;; Tight bounding box (dashed-style with dotted color)
    (shapes/rectangle canvas
                      (+ text-x (:left bounds))
                      (+ baseline-y (:top bounds))
                      (:width bounds)
                      (:height bounds)
                      {:color 0xAAE74C3C :mode :stroke :stroke-width 1.5})
    ;; Advance width line (solid green)
    (shapes/line canvas text-x (+ baseline-y 10) (+ text-x advance) (+ baseline-y 10)
                 {:color 0xFF2ECC71 :stroke-width 2})
    ;; Labels
    (text/text canvas (format "bounds: %.1f x %.1f" (:width bounds) (:height bounds))
               (+ text-x 10) (+ baseline-y 35)
               {:size 12 :color 0xAAE74C3C})
    (text/text canvas (format "advance width: %.1f" advance)
               (+ text-x 10) (+ baseline-y 50)
               {:size 12 :color 0xFF2ECC71})))

;; ============================================================
;; Drawing: Hit Testing Section
;; ============================================================

(def hit-test-text
  "Click anywhere in this paragraph to see character index and word boundary highlighting. The paragraph wraps across multiple lines.")

(def hit-para-x 40)
(def hit-para-y 0) ;; relative, set in draw
(def hit-para-width 500)

(defn draw-hit-test-section [^Canvas canvas x y]
  (text/text canvas "Hit Testing (click in text)" x y
             {:size 20 :weight :medium :color 0xFFFFFFFF})
  (let [py (+ y 30)
        p (para/paragraph hit-test-text
            {:width hit-para-width :size 16 :color 0xFFFFFFFF})
        h (para/height p)]
    ;; Box outline
    (shapes/rectangle canvas x py hit-para-width h
                      {:color 0x33FFFFFF :mode :stroke :stroke-width 1})
    ;; Draw word highlight if clicked
    (when-let [{:keys [start end]} @click-word]
      (let [rects (para/rects-for-range p start end)]
        (doseq [{:keys [rx ry rw rh] :as r} rects]
          (shapes/rectangle canvas
                            (+ x (:x r)) (+ py (:y r))
                            (:width r) (:height r)
                            {:color 0x444A90D9}))))
    ;; Draw character highlight if clicked
    (when-let [idx @click-index]
      (let [rects (para/rects-for-range p idx (inc idx))]
        (doseq [r rects]
          (shapes/rectangle canvas
                            (+ x (:x r)) (+ py (:y r))
                            (:width r) (:height r)
                            {:color 0x88E74C3C}))))
    ;; Draw paragraph
    (para/draw canvas p x py)
    ;; Info label
    (let [info (if @click-index
                 (str "Index: " @click-index
                      "  Word: [" (:start @click-word) "-" (:end @click-word) "]")
                 "Click in the text above")]
      (text/text canvas info x (+ py h 20)
                 {:size 13 :color label-color}))
    ;; Store para origin for hit testing
    py))

;; ============================================================
;; Drawing: Intrinsic Widths Section
;; ============================================================

(def intrinsic-text "Hello beautiful world")

(defn draw-intrinsic-section [^Canvas canvas x y]
  (text/text canvas "Intrinsic Widths" x y
             {:size 20 :weight :medium :color 0xFFFFFFFF})
  (let [;; Max intrinsic (single line)
        p-max (para/paragraph intrinsic-text {:width Float/MAX_VALUE :size 18 :color 0xFFFFFFFF})
        max-w (para/max-intrinsic-width p-max)
        ;; Min intrinsic (maximum wrapping)
        p-min (para/paragraph intrinsic-text {:width 1 :size 18 :color 0xFFFFFFFF})
        min-w (para/min-intrinsic-width p-min)
        ;; Re-layout at actual widths
        p-at-max (para/paragraph intrinsic-text {:width max-w :size 18 :color 0xFFFFFFFF})
        p-at-min (para/paragraph intrinsic-text {:width min-w :size 18 :color 0xFFFFFFFF})
        y-start (+ y 30)
        gap 20]
    ;; Max intrinsic
    (let [h (para/height p-at-max)]
      (shapes/rectangle canvas x y-start max-w h
                        {:color 0x442ECC71 :mode :stroke :stroke-width 1.5})
      (para/draw canvas p-at-max x y-start)
      (text/text canvas (format "max-intrinsic-width: %.1f px" max-w)
                 x (+ y-start h 16) {:size 12 :color 0xFF2ECC71}))
    ;; Min intrinsic
    (let [y2 (+ y-start 60)
          h (para/height p-at-min)]
      (shapes/rectangle canvas x y2 min-w h
                        {:color 0x44E74C3C :mode :stroke :stroke-width 1.5})
      (para/draw canvas p-at-min x y2)
      (text/text canvas (format "min-intrinsic-width: %.1f px" min-w)
                 x (+ y2 h 16) {:size 12 :color 0xFFE74C3C}))))

;; ============================================================
;; Gesture Handlers
;; ============================================================

(def hit-test-origin-y (atom 0))

(def hit-test-handlers
  {:on-tap
   (fn [event]
     (let [mx (get-in event [:pointer :x])
           my (get-in event [:pointer :y])
           py @hit-test-origin-y
           local-x (- mx hit-para-x)
           local-y (- my py)
           p (para/paragraph hit-test-text
               {:width hit-para-width :size 16 :color 0xFFFFFFFF})
           {:keys [index]} (para/index-at-point p local-x local-y)]
       (reset! click-index index)
       (reset! click-word (para/word-boundary p index))))})

(defn register-gestures! []
  (when-let [register! (requiring-resolve 'lib.gesture.api/register-target!)]
    (register!
      {:id :hit-test-paragraph
       :layer :content
       :z-index 10
       :bounds-fn (fn [_ctx]
                    (let [py @hit-test-origin-y
                          p (para/paragraph hit-test-text
                              {:width hit-para-width :size 16 :color 0xFFFFFFFF})]
                      [hit-para-x py hit-para-width (para/height p)]))
       :gesture-recognizers [:tap]
       :handlers hit-test-handlers})))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Text Measurement loaded")
  (register-gestures!))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas width height]
  (let [margin 40
        x margin]
    ;; Title
    (text/text canvas "Text Measurement"
               (/ width 2) 35
               {:size 28 :weight :medium :align :center :color 0xFFFFFFFF})
    ;; Sections
    (draw-metrics-section canvas x 70)
    (draw-bounds-section canvas x 220)
    (let [py (draw-hit-test-section canvas x 370)]
      (reset! hit-test-origin-y py))
    (draw-intrinsic-section canvas x 500)))

(defn cleanup []
  (reset! click-index nil)
  (reset! click-word nil)
  (when-let [unregister! (requiring-resolve 'lib.gesture.api/unregister-target!)]
    (unregister! :hit-test-paragraph))
  (println "Text Measurement cleanup"))

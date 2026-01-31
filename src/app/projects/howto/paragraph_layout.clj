(ns app.projects.howto.paragraph-layout
  "Paragraph Layout - Line metrics, strut style, font features, and families.

   Demonstrates:
   - Per-line metrics with visual overlays (baseline, ascent/descent)
   - StrutStyle for consistent line heights with mixed font sizes
   - OpenType font features (tabular numbers)
   - Multiple font families (fallback chain)
   - Truncation detection with exceeded-max-lines?"
  (:require [lib.text.core :as text]
            [lib.text.paragraph :as para]
            [lib.graphics.shapes :as shapes])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def label-color [0.53 0.53 0.53 1.0])
(def box-color [1.0 1.0 1.0 0.2])

;; ============================================================
;; Drawing: Line Metrics Section
;; ============================================================

(def line-metrics-text
  "Line metrics provide per-line layout data including baseline, ascent, descent, and width. This is useful for custom text rendering overlays.")

(defn draw-line-metrics-section [^Canvas canvas x y]
  (text/text canvas "Line Metrics" x y
             {:size 20 :weight :medium :color [1.0 1.0 1.0 1.0]})
  (let [w 450
        py (+ y 30)
        p (para/paragraph line-metrics-text {:width w :size 16 :color [1.0 1.0 1.0 1.0]})
        metrics (para/line-metrics p)
        h (para/height p)]
    ;; Box outline
    (shapes/rectangle canvas x py w h
                      {:color box-color :mode :stroke :stroke-width 1})
    ;; Draw per-line overlays
    (doseq [m metrics]
      (let [line-y (+ py (:baseline m))
            line-top (- line-y (:ascent m))
            line-h (+ (:ascent m) (:descent m))]
        ;; Ascent/descent shaded rect
        (shapes/rectangle canvas (+ x (:left m)) (+ py (- (:baseline m) (:ascent m)))
                          (:width m) line-h
                          {:color [0.29 0.56 0.85 0.07]})
        ;; Baseline line
        (shapes/line canvas x line-y (+ x w) line-y
                     {:color [0.18 0.8 0.44 0.53] :stroke-width 0.5})
        ;; Line number label
        (text/text canvas (str (:line-number m))
                   (- x 15) line-y
                   {:size 11 :align :center :color label-color})))
    ;; Draw paragraph on top
    (para/draw canvas p x py)
    (+ py h 20)))

;; ============================================================
;; Drawing: Strut Style Section
;; ============================================================

(defn draw-strut-section [^Canvas canvas x y]
  (text/text canvas "Strut Style" x y
             {:size 20 :weight :medium :color [1.0 1.0 1.0 1.0]})
  (let [w 180
        py (+ y 30)
        spans [{:text "Small " :size 12 :color [0.8 0.8 0.8 1.0]}
               {:text "BIG " :size 28 :color [0.29 0.56 0.85 1.0] :weight :bold}
               {:text "small again " :size 12 :color [0.8 0.8 0.8 1.0]}
               {:text "MED " :size 20 :color [0.18 0.8 0.44 1.0]}
               {:text "tiny" :size 12 :color [0.8 0.8 0.8 1.0]}]
        ;; Without strut
        p-no-strut (para/rich-text {:width w} spans)
        h-no (para/height p-no-strut)
        ;; With strut
        p-strut (para/rich-text {:width w
                                 :strut {:font-size 20 :height 1.5
                                         :enabled true :force-height true
                                         :override-height true}}
                  spans)
        h-strut (para/height p-strut)
        max-h (max h-no h-strut)]
    ;; Left: no strut
    (shapes/rectangle canvas x py w max-h
                      {:color box-color :mode :stroke :stroke-width 1})
    (para/draw canvas p-no-strut x py)
    (text/text canvas "No strut (uneven)" (+ x (/ w 2)) (+ py max-h 18)
               {:size 12 :align :center :color label-color})
    ;; Right: with strut
    (let [rx (+ x w 30)]
      (shapes/rectangle canvas rx py w max-h
                        {:color box-color :mode :stroke :stroke-width 1})
      (para/draw canvas p-strut rx py)
      (text/text canvas "Strut: force-height (uniform)" (+ rx (/ w 2)) (+ py max-h 18)
                 {:size 12 :align :center :color label-color}))
    (+ py max-h 40)))

;; ============================================================
;; Drawing: Font Features & Families Section
;; ============================================================

(defn draw-features-section [^Canvas canvas x y]
  (text/text canvas "Font Features & Families" x y
             {:size 20 :weight :medium :color [1.0 1.0 1.0 1.0]})
  (let [py (+ y 30)
        w 500
        font-family "Inter Variable"
        gap 28          ;; space before each sub-section label
        label-gap 16    ;; space between a label and its content
        ;; Multiple number sets for clear proportional vs tabular comparison
        num-text "0123456789\n1111111111\n$1,234.00  $9,876.50\n09:45:30   12:00:00"
        ;; Proportional numbers (default)
        p-prop (para/paragraph num-text {:width w :size 24 :color [1.0 1.0 1.0 1.0]
                                         :family font-family})
        h-prop (para/height p-prop)
        prop-y (+ py label-gap)
        ;; Tabular numbers (tnum feature)
        tnum-label-y (+ prop-y h-prop gap)
        p-tnum (para/paragraph num-text {:width w :size 24 :color [1.0 1.0 1.0 1.0]
                                         :family font-family :features "tnum"})
        h-tnum (para/height p-tnum)
        tnum-y (+ tnum-label-y label-gap)
        ;; Emoji fallback
        emoji-text "Hello World 🧑‍🦰👋"
        fam-label-y (+ tnum-y h-tnum gap)
        fam-y (+ fam-label-y label-gap)
        p-fallback (para/paragraph emoji-text
                     {:width w :size 22 :color [1.0 1.0 1.0 1.0]
                      :family ["Helvetica" "Apple Color Emoji"]})
        h-fallback (para/height p-fallback)
        fam-attr-y (+ fam-y h-fallback 4)
        ;; Truncation detection
        trunc-label-y (+ fam-attr-y gap)
        trunc-y (+ trunc-label-y label-gap)
        trunc-w 300
        trunc-text "This paragraph has been truncated because it exceeds the maximum number of lines allowed by the layout configuration."
        p-trunc (para/paragraph trunc-text
                  {:width trunc-w :size 14 :color [1.0 1.0 1.0 1.0] :max-lines 2 :ellipsis "..."})
        h-trunc (para/height p-trunc)
        truncated? (para/exceeded-max-lines? p-trunc)]
    ;; Proportional label + paragraph
    (text/text canvas "Proportional (default):" x py
               {:size 13 :color label-color})
    (para/draw canvas p-prop x prop-y)
    ;; Tabular label + paragraph
    (text/text canvas "Tabular (features \"tnum\"):" x tnum-label-y
               {:size 13 :color label-color})
    (para/draw canvas p-tnum x tnum-y)
    ;; Emoji fallback
    (text/text canvas "Font Family (Emoji Fallback):" x fam-label-y
               {:size 13 :color label-color})
    (para/draw canvas p-fallback x fam-y)
    (text/text canvas "family: [\"Helvetica\" \"Apple Color Emoji\"]"
               x fam-attr-y {:size 11 :color label-color})
    ;; Truncation detection
    (text/text canvas "Truncation (max-lines: 2):" x trunc-label-y
               {:size 13 :color label-color})
    (shapes/rectangle canvas x trunc-y trunc-w h-trunc
                      {:color box-color :mode :stroke :stroke-width 1})
    (para/draw canvas p-trunc x trunc-y)
    (when truncated?
      (text/text canvas "⬆ exceeded-max-lines? → true"
                 x (+ trunc-y h-trunc 16) {:size 12 :color [0.91 0.3 0.24 1.0]}))
    (+ trunc-y h-trunc 40)))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Paragraph Layout loaded"))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas width height]
  (let [margin 40
        x margin]
    ;; Title
    (text/text canvas "Paragraph Layout"
               (/ width 2) 35
               {:size 28 :weight :medium :align :center :color [1.0 1.0 1.0 1.0]})
    ;; Sections
    (let [y1 (draw-line-metrics-section canvas x 70)
          y2 (draw-strut-section canvas x (+ y1 10))]
      (draw-features-section canvas x (+ y2 10)))))

(defn cleanup []
  (println "Paragraph Layout cleanup"))

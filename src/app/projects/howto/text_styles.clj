(ns app.projects.howto.text-styles
  "Text Styles - Rich text styling, decorations, spacing, and backgrounds.

   Demonstrates:
   - Rich text with mixed font sizes, weights, and colors
   - Underline styles: solid, double, dotted, dashed, wavy
   - Strikethrough styles
   - Letter spacing, word spacing, and line height
   - Background-colored text spans"
  (:require [lib.text.core :as text]
            [lib.text.paragraph :as para]
            [lib.graphics.shapes :as shapes])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def section-width 700)
(def label-color [0.53 0.53 0.53 1.0])

;; ============================================================
;; Drawing: Rich Text Spans
;; ============================================================

(defn draw-rich-text-section [^Canvas canvas x y]
  (text/text canvas "Rich Text Spans" x y
             {:size 20 :weight :medium :color [1.0 1.0 1.0 1.0]})
  (let [p (para/rich-text {:width section-width}
            [{:text "Small " :size 14 :color [0.8 0.8 0.8 1.0]}
             {:text "Medium " :size 20 :color [0.29 0.56 0.85 1.0]}
             {:text "Large " :size 32 :color [0.18 0.8 0.44 1.0] :weight :bold}
             {:text "and " :size 16 :color [0.8 0.8 0.8 1.0]}
             {:text "italic " :size 20 :slant :italic :color [0.61 0.35 0.71 1.0]}
             {:text "text mix nicely in one paragraph, wrapping across lines as needed." :size 16 :color [0.8 0.8 0.8 1.0]}])
        h (para/height p)]
    (para/draw canvas p x (+ y 30))
    (+ y 30 h 10)))

;; ============================================================
;; Drawing: Underline Styles
;; ============================================================

(defn draw-underline-section [^Canvas canvas x y]
  (text/text canvas "Underline Styles" x y
             {:size 20 :weight :medium :color [1.0 1.0 1.0 1.0]})
  (let [styles [{:name "Solid"  :style :solid  :color [0.29 0.56 0.85 1.0]}
                {:name "Double" :style :double :color [0.18 0.8 0.44 1.0]}
                {:name "Dotted" :style :dotted :color [0.91 0.3 0.24 1.0]}
                {:name "Dashed" :style :dashed :color [0.61 0.35 0.71 1.0]}
                {:name "Wavy"   :style :wavy   :color [0.95 0.61 0.07 1.0]}]
        spans (mapv (fn [{:keys [name style color]}]
                      {:text (str name "  ")
                       :size 22
                       :color [1.0 1.0 1.0 1.0]
                       :underline {:style style :color color :thickness-multiplier 2.0}})
                    styles)
        p (para/rich-text {:width section-width} spans)]
    (para/draw canvas p x (+ y 30))
    (+ y 30 (para/height p) 10)))

;; ============================================================
;; Drawing: Strikethrough
;; ============================================================

(defn draw-strikethrough-section [^Canvas canvas x y]
  (text/text canvas "Strikethrough" x y
             {:size 20 :weight :medium :color [1.0 1.0 1.0 1.0]})
  (let [p (para/rich-text {:width section-width}
            [{:text "Plain strikethrough  " :size 20 :color [0.8 0.8 0.8 1.0] :strikethrough true}
             {:text "Styled double red" :size 20 :color [0.8 0.8 0.8 1.0]
              :strikethrough {:style :double :color [1.0 0.0 0.0 1.0] :thickness-multiplier 1.5}}])]
    (para/draw canvas p x (+ y 30))
    (+ y 30 (para/height p) 10)))

;; ============================================================
;; Drawing: Spacing
;; ============================================================

(defn draw-spacing-section [^Canvas canvas x y]
  (text/text canvas "Spacing" x y
             {:size 20 :weight :medium :color [1.0 1.0 1.0 1.0]})
  (let [w (/ section-width 3)
        y-start (+ y 30)
        ;; Letter spacing row
        ls-configs [{:label "letter-spacing: 0" :opts {:letter-spacing 0}}
                    {:label "letter-spacing: 5" :opts {:letter-spacing 5.0}}
                    {:label "letter-spacing: 10" :opts {:letter-spacing 10.0}}]
        letter-text "Spacing"
        y1 y-start
        ;; Draw letter spacing
        _ (doseq [[i {:keys [label opts]}] (map-indexed vector ls-configs)]
            (let [cx (+ x (* i (+ w 10)))
                  p (para/paragraph letter-text (merge {:width w :size 18 :color [1.0 1.0 1.0 1.0]} opts))]
              (para/draw canvas p cx y1)
              (text/text canvas label cx (+ y1 30) {:size 11 :color label-color})))
        ;; Word spacing row
        ws-text "Hello beautiful world today"
        y2 (+ y1 55)
        ws-configs [{:label "word-spacing: 0" :opts {:word-spacing 0}}
                    {:label "word-spacing: 20" :opts {:word-spacing 20.0}}]
        _ (doseq [[i {:keys [label opts]}] (map-indexed vector ws-configs)]
            (let [cx (+ x (* i (+ w 10)))
                  p (para/paragraph ws-text (merge {:width w :size 14 :color [1.0 1.0 1.0 1.0]} opts))]
              (para/draw canvas p cx y2)
              (text/text canvas label cx (+ y2 (para/height p) 14) {:size 11 :color label-color})))
        ;; Line height row
        lh-text "Line one and line two with enough text to wrap to multiple lines."
        y3 (+ y2 80)
        lh-configs [{:label "line-height: 1.0" :opts {:line-height 1.0}}
                    {:label "line-height: 1.5" :opts {:line-height 1.5}}
                    {:label "line-height: 2.0" :opts {:line-height 2.0}}]]
    (doseq [[i {:keys [label opts]}] (map-indexed vector lh-configs)]
      (let [cx (+ x (* i (+ w 10)))
            p (para/paragraph lh-text (merge {:width w :size 13 :color [1.0 1.0 1.0 1.0]} opts))
            h (para/height p)]
        (shapes/rectangle canvas cx y3 w h {:color [1.0 1.0 1.0 0.13] :mode :stroke :stroke-width 1})
        (para/draw canvas p cx y3)
        (text/text canvas label cx (+ y3 h 14) {:size 11 :color label-color})))
    (+ y3 160)))

;; ============================================================
;; Drawing: Background Color
;; ============================================================

(defn draw-background-section [^Canvas canvas x y]
  (text/text canvas "Background Color" x y
             {:size 20 :weight :medium :color [1.0 1.0 1.0 1.0]})
  (let [p (para/rich-text {:width section-width}
            [{:text "Normal text " :size 18 :color [1.0 1.0 1.0 1.0]}
             {:text " highlighted " :size 18 :color [0.0 0.0 0.0 1.0] :background-color [0.95 0.77 0.06 1.0]}
             {:text " more text " :size 18 :color [1.0 1.0 1.0 1.0]}
             {:text " green " :size 18 :color [0.0 0.0 0.0 1.0] :background-color [0.18 0.8 0.44 1.0]}
             {:text " and " :size 18 :color [1.0 1.0 1.0 1.0]}
             {:text " blue " :size 18 :color [1.0 1.0 1.0 1.0] :background-color [0.2 0.6 0.86 1.0]}
             {:text " highlights in one paragraph." :size 18 :color [1.0 1.0 1.0 1.0]}])]
    (para/draw canvas p x (+ y 30))
    (+ y 30 (para/height p) 10)))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Text Styles loaded"))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas width height]
  (let [margin 40
        x margin]
    ;; Title
    (text/text canvas "Text Styles"
               (/ width 2) 35
               {:size 28 :weight :medium :align :center :color [1.0 1.0 1.0 1.0]})
    ;; Sections
    (let [y1 (draw-rich-text-section canvas x 70)
          y2 (draw-underline-section canvas x (+ y1 20))
          y3 (draw-strikethrough-section canvas x (+ y2 20))
          y4 (draw-spacing-section canvas x (+ y3 20))]
      (draw-background-section canvas x (+ y4 20)))))

(defn cleanup []
  (println "Text Styles cleanup"))

(ns app.projects.howto.paragraph-basics
  "Paragraph Basics - Word wrap, alignment, truncation, and RTL text.

   Demonstrates:
   - Multi-line paragraph wrapping at different widths
   - Alignment modes: left, center, right, justify
   - Truncation with max-lines and ellipsis
   - RTL (right-to-left) text direction"
  (:require [lib.text.core :as text]
            [lib.text.paragraph :as para]
            [lib.graphics.shapes :as shapes])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def sample-text
  "The quick brown fox jumps over the lazy dog. Pack my box with five dozen liquor jugs.")

(def long-text
  "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris.")

(def section-gap 30)
(def box-color [1.0 1.0 1.0 0.2])
(def label-color [0.53 0.53 0.53 1.0])
(def text-color [1.0 1.0 1.0 1.0])

;; ============================================================
;; Drawing: Word Wrap Section
;; ============================================================

(defn draw-wrap-section [^Canvas canvas x y]
  (text/text canvas "Word Wrap" x y
             {:size 20 :weight :medium :color [1.0 1.0 1.0 1.0]})
  (let [widths [200 300 400]
        y-start (+ y 30)]
    (loop [widths-left widths
           cx x]
      (when (seq widths-left)
        (let [w (first widths-left)
              p (para/paragraph sample-text {:width w :size 14 :color text-color})
              h (para/height p)]
          ;; Box outline
          (shapes/rectangle canvas cx y-start w h
                            {:color box-color :mode :stroke :stroke-width 1})
          ;; Paragraph
          (para/draw canvas p cx y-start)
          ;; Width label
          (text/text canvas (str w "px") (+ cx (/ w 2)) (+ y-start h 18)
                     {:size 12 :align :center :color label-color})
          (recur (rest widths-left) (+ cx w 20)))))))

;; ============================================================
;; Drawing: Alignment Section
;; ============================================================

(defn draw-alignment-section [^Canvas canvas x y]
  (text/text canvas "Alignment" x y
             {:size 20 :weight :medium :color [1.0 1.0 1.0 1.0]})
  (let [aligns [:left :center :right :justify]
        w 180
        y-start (+ y 30)]
    (loop [aligns-left aligns
           cx x]
      (when (seq aligns-left)
        (let [a (first aligns-left)
              p (para/paragraph sample-text {:width w :size 13 :color text-color :align a})
              h (para/height p)]
          ;; Box outline
          (shapes/rectangle canvas cx y-start w h
                            {:color box-color :mode :stroke :stroke-width 1})
          ;; Paragraph
          (para/draw canvas p cx y-start)
          ;; Label
          (text/text canvas (name a) (+ cx (/ w 2)) (+ y-start h 18)
                     {:size 12 :align :center :color label-color})
          (recur (rest aligns-left) (+ cx w 20)))))))

;; ============================================================
;; Drawing: Truncation Section
;; ============================================================

(defn draw-truncation-section [^Canvas canvas x y]
  (text/text canvas "Truncation" x y
             {:size 20 :weight :medium :color [1.0 1.0 1.0 1.0]})
  (let [w 350
        y-start (+ y 30)
        configs [{:label "No truncation" :opts {:width w :size 14 :color text-color}}
                 {:label "max-lines: 2, ellipsis: \"...\""
                  :opts {:width w :size 14 :color text-color :max-lines 2 :ellipsis "..."}}
                 {:label "max-lines: 1, ellipsis: \"…\""
                  :opts {:width w :size 14 :color text-color :max-lines 1 :ellipsis "…"}}]]
    (loop [configs-left configs
           cy y-start]
      (when (seq configs-left)
        (let [{:keys [label opts]} (first configs-left)
              p (para/paragraph long-text opts)
              h (para/height p)
              lines (para/line-count p)]
          ;; Box outline
          (shapes/rectangle canvas x cy w h
                            {:color box-color :mode :stroke :stroke-width 1})
          ;; Paragraph
          (para/draw canvas p x cy)
          ;; Label with line count
          (text/text canvas (str label "  [" lines " lines]") x (+ cy h 16)
                     {:size 11 :color label-color})
          (recur (rest configs-left) (+ cy h 35)))))))

;; ============================================================
;; Drawing: RTL Section
;; ============================================================

(defn draw-rtl-section [^Canvas canvas x y]
  (text/text canvas "RTL Text" x y
             {:size 20 :weight :medium :color [1.0 1.0 1.0 1.0]})
  (let [w 350
        arabic-text "مرحبا بالعالم. هذا نص تجريبي للعرض من اليمين إلى اليسار."
        y-start (+ y 30)
        configs [{:label "direction: :rtl, align: :right"
                  :opts {:width w :size 16 :color text-color :direction :rtl :align :right}}
                 {:label "direction: :ltr (for comparison)"
                  :opts {:width w :size 16 :color text-color :direction :ltr :align :left}}]]
    (loop [configs-left configs
           cy y-start]
      (when (seq configs-left)
        (let [{:keys [label opts]} (first configs-left)
              p (para/paragraph arabic-text opts)
              h (para/height p)]
          ;; Box outline
          (shapes/rectangle canvas x cy w h
                            {:color box-color :mode :stroke :stroke-width 1})
          ;; Paragraph
          (para/draw canvas p x cy)
          ;; Label
          (text/text canvas label x (+ cy h 16)
                     {:size 11 :color label-color})
          (recur (rest configs-left) (+ cy h 35)))))))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Paragraph Basics loaded"))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas width height]
  (let [margin 40
        x margin]
    ;; Title
    (text/text canvas "Paragraph Basics"
               (/ width 2) 35
               {:size 28 :weight :medium :align :center :color [1.0 1.0 1.0 1.0]})
    ;; Sections
    (draw-wrap-section canvas x 70)
    (draw-alignment-section canvas x 220)
    (draw-truncation-section canvas x 370)
    (draw-rtl-section canvas x 660)))

(defn cleanup []
  (println "Paragraph Basics cleanup"))

(ns app.projects.howto.rich-text-inline
  "Rich Text Inline - Placeholders and in-place paragraph updates.

   Demonstrates:
   - Inline placeholder spans in rich text (for icons/widgets)
   - Placeholder alignment modes: baseline, middle, top, bottom
   - In-place paragraph updates: alignment, font size, foreground color
   - Animated in-place updates using game-time"
  (:require [app.state.system :as sys]
            [lib.text.core :as text]
            [lib.text.paragraph :as para]
            [lib.graphics.shapes :as shapes])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def label-color 0xFF888888)
(def box-color 0x33FFFFFF)
(def placeholder-colors [0xFF4A90D9 0xFF2ECC71 0xFFE74C3C 0xFF9B59B6])

;; ============================================================
;; State (persists across hot-reloads)
;; ============================================================

;; Paragraph for in-place updates (created once, mutated in tick)
(defonce update-para (atom nil))
(defonce update-para-width (atom 400))

;; ============================================================
;; Drawing: Inline Placeholders Section
;; ============================================================

(defn draw-placeholder-row [^Canvas canvas x y align-kw w]
  "Draw a rich-text row with a placeholder at the given alignment."
  (let [p (para/rich-text {:width w :size 18}
            [{:text "Text " :color 0xFFFFFFFF}
             {:placeholder true :width 24 :height 24 :align align-kw}
             {:text " continues here" :color 0xFFFFFFFF}])
        h (para/height p)
        rects (para/placeholder-rects p)]
    ;; Draw paragraph
    (para/draw canvas p x y)
    ;; Draw colored rounded rect at each placeholder position
    (doseq [[i r] (map-indexed vector rects)]
      (let [color (nth placeholder-colors (mod i (count placeholder-colors)))]
        (shapes/rounded-rect canvas
                             (+ x (:x r)) (+ y (:y r))
                             (:width r) (:height r)
                             4
                             {:color color})))
    ;; Alignment label
    (text/text canvas (str "align: " (name align-kw)) (+ x w 15) (+ y 6)
               {:size 12 :color label-color})
    (+ y h 8)))

(defn draw-placeholder-section [^Canvas canvas x y]
  (text/text canvas "Inline Placeholders" x y
             {:size 20 :weight :medium :color 0xFFFFFFFF})
  (let [w 350
        py (+ y 30)
        aligns [:baseline :middle :top :bottom]]
    (loop [aligns-left aligns
           cy py]
      (if (seq aligns-left)
        (recur (rest aligns-left)
               (draw-placeholder-row canvas x cy (first aligns-left) w))
        cy))))

;; ============================================================
;; Drawing: In-Place Updates Section
;; ============================================================

(def update-text "Hello beautiful World! This paragraph updates alignment and colors without rebuilding.")

(defn ensure-update-para! []
  "Create the paragraph once if not yet created."
  (when (nil? @update-para)
    (reset! update-para
      (para/paragraph update-text
        {:width @update-para-width :size 18 :color 0xFFCCCCCC}))))

(defn draw-update-section [^Canvas canvas x y]
  (text/text canvas "In-Place Updates (animated)" x y
             {:size 20 :weight :medium :color 0xFFFFFFFF})
  (let [py (+ y 30)
        w @update-para-width
        time @sys/game-time
        ;; Cycle alignment every 2 seconds
        align-idx (mod (int (/ time 2)) 3)
        align-kw ([:left :center :right] align-idx)
        p @update-para]
    (when p
      ;; Apply in-place updates
      (para/update-alignment! p align-kw)
      ;; Animate foreground color of first 5 chars
      (let [;; Hue shift via time
            r (int (+ 128 (* 127 (Math/sin (* time 2)))))
            g (int (+ 128 (* 127 (Math/sin (+ (* time 2) 2)))))
            b (int (+ 128 (* 127 (Math/sin (+ (* time 2) 4)))))
            color (unchecked-int (bit-or 0xFF000000
                                         (bit-shift-left (bit-and r 0xFF) 16)
                                         (bit-shift-left (bit-and g 0xFF) 8)
                                         (bit-and b 0xFF)))]
        (para/update-foreground! p 0 5 color))
      ;; Re-layout and draw
      (para/layout! p w)
      (let [h (para/height p)]
        (shapes/rectangle canvas x py w h
                          {:color box-color :mode :stroke :stroke-width 1})
        (para/draw canvas p x py)
        ;; Label showing current alignment
        (text/text canvas (str "alignment: " (name align-kw))
                   x (+ py h 18)
                   {:size 12 :color label-color})))))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Rich Text Inline loaded")
  (ensure-update-para!))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas width height]
  (let [margin 40
        x margin]
    ;; Title
    (text/text canvas "Rich Text Inline"
               (/ width 2) 35
               {:size 28 :weight :medium :align :center :color 0xFFFFFFFF})
    ;; Sections
    (let [y1 (draw-placeholder-section canvas x 70)]
      (draw-update-section canvas x (+ y1 20)))))

(defn cleanup []
  (reset! update-para nil)
  (println "Rich Text Inline cleanup"))

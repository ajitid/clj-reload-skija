(ns app.projects.howto.atlas-demo
  "Atlas/sprite demo - shows sprite sheet rendering with transforms.

   Uses Kenney's Pixel Platformer assets (CC0 license).
   https://kenney.nl/assets/pixel-platformer

   Demonstrates:
   - Loading sprite sheets
   - Defining quads (sprite regions)
   - Drawing with rotation, scale, origin
   - Animation using quad sequences
   - Batch drawing multiple sprites"
  (:require [lib.graphics.image :as image]
            [lib.graphics.shapes :as shapes]
            [lib.time :as time])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; State
;; ============================================================

(defonce characters-sheet (atom nil))
(defonce tiles-sheet (atom nil))
(defonce character-quads (atom nil))

;; Animation state
(defonce player-x (atom 100.0))
(defonce player-facing-right (atom true))

;; ============================================================
;; Sprite Sheet Layout (from Kenney's metadata)
;; Characters: 24x24 tiles, 9 columns, 3 rows, 1px spacing
;; ============================================================

(defn quad-grid-with-spacing
  "Create quads for a grid with spacing between tiles."
  [tile-w tile-h cols rows spacing]
  (vec (for [row (range rows)
             col (range cols)]
         (image/quad (* col (+ tile-w spacing))
                     (* row (+ tile-h spacing))
                     tile-w
                     tile-h))))

;; ============================================================
;; Lifecycle
;; ============================================================

(defn init []
  (println "[atlas-demo] Loading Kenney Pixel Platformer assets...")
  (try
    ;; Load sprite sheets
    (reset! characters-sheet
            (image/file->image "resources/sprites/Tilemap/tilemap-characters.png"))
    (reset! tiles-sheet
            (image/file->image "resources/sprites/Tilemap/tilemap.png"))
    ;; Create quads for characters (24x24, 9 cols, 3 rows, 1px spacing)
    (reset! character-quads (quad-grid-with-spacing 24 24 9 3 1))
    (println "[atlas-demo] Loaded" (count @character-quads) "character sprites")
    (catch Exception e
      (println "[atlas-demo] Error loading sprites:" (.getMessage e))
      (println "[atlas-demo] Make sure you've downloaded the Kenney assets to resources/sprites/"))))

(defn cleanup []
  (println "[atlas-demo] Cleaning up...")
  (when @characters-sheet
    (.close @characters-sheet)
    (reset! characters-sheet nil))
  (when @tiles-sheet
    (.close @tiles-sheet)
    (reset! tiles-sheet nil))
  (reset! character-quads nil))

;; ============================================================
;; Update
;; ============================================================

(defn tick [dt]
  ;; Animate player position
  (let [t (time/now)
        new-x (+ 200 (* 150 (Math/sin (* t 0.5))))]
    ;; Update facing direction based on movement
    (when (> new-x @player-x)
      (reset! player-facing-right true))
    (when (< new-x @player-x)
      (reset! player-facing-right false))
    (reset! player-x new-x)))

;; ============================================================
;; Render
;; ============================================================

(defn draw [^Canvas canvas w h]
  (let [sheet @characters-sheet
        tiles @tiles-sheet
        quads @character-quads
        t (time/now)]

    ;; Background
    (shapes/rectangle canvas 0 0 w h {:color [0.176 0.176 0.267 1.0]})

    (when (and sheet quads (seq quads))
      (let [scale 3.0  ; Scale up the pixel art
            tile-size (* 24 scale)]

        ;; Section 1: All character sprites in a grid
        (doseq [i (range (min 27 (count quads)))]
          (let [col (mod i 9)
                row (quot i 9)
                x (+ 30 (* col (+ tile-size 10)))
                y (+ 40 (* row (+ tile-size 10)))]
            (image/draw canvas sheet (nth quads i) x y
                        {:scale scale
                         :filter :nearest})))  ; Pixel-perfect scaling

        ;; Section 2: Animated character (walking cycle)
        ;; Characters 0, 1 seem to be idle/walk frames for player 1
        (let [y-offset 320
              ;; Cycle between first two frames for simple walk animation
              frame-idx (mod (int (* t 8)) 2)
              walk-frame (nth quads frame-idx)]
          ;; Draw animated player
          (image/draw canvas sheet walk-frame @player-x y-offset
                      {:scale scale
                       :flip-x @player-facing-right
                       :origin [12 12]  ; Center of 24x24 sprite
                       :filter :nearest}))

        ;; Section 3: Rotation demo
        (let [y-offset 450
              sprite (nth quads 0)]
          (doseq [i (range 8)]
            (let [angle (* i (/ Math/PI 4))]
              (image/draw canvas sheet sprite
                          (+ 80 (* i 90)) y-offset
                          {:scale scale
                           :rotation angle
                           :origin [12 12]
                           :filter :nearest}))))

        ;; Section 4: Scale demo
        (let [y-offset 560
              sprite (nth quads 9)]  ; Different character
          (doseq [[i s] (map-indexed vector [1.0 2.0 3.0 4.0 5.0])]
            (image/draw canvas sheet sprite
                        (+ 60 (* i 100)) y-offset
                        {:scale s
                         :origin [12 12]
                         :filter :nearest})))

        ;; Section 5: Alpha/transparency demo
        (let [y-offset 620
              sprite (nth quads 18)]  ; Another character
          (doseq [i (range 5)]
            (let [alpha (/ (inc i) 5.0)]
              (image/draw canvas sheet sprite
                          (+ 60 (* i 90)) y-offset
                          {:scale scale
                           :alpha alpha
                           :filter :nearest}))))

        ;; Section 6: Batch drawing demo - crowd of characters
        ;; Note: filter is now a shared option for the entire batch
        (let [y-offset 740]
          (image/draw-batch canvas sheet
                            (for [i (range 12)]
                              [(nth quads (mod (* i 3) (count quads)))
                               (+ 40 (* i 60))
                               y-offset
                               {:scale (+ 2.0 (* 0.5 (Math/sin (+ t (* i 0.5)))))
                                :rotation (* 0.1 (Math/sin (+ (* t 2) i)))
                                :origin [12 12]}])
                            {:filter :nearest}))))))

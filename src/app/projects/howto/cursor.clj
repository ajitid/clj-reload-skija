(ns app.projects.howto.cursor
  "Cursor demo — hover over zones to change the mouse cursor.

   Demonstrates:
   - Setting system cursors by keyword via lib.window.cursor
   - Hiding and showing the cursor
   - Polling mouse position each frame for hover detection"
  (:require [lib.graphics.shapes :as shapes]
            [lib.text.core :as text]
            [lib.window.cursor :as cursor]
            [lib.window.core :as window])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Configuration
;; ============================================================

(def zone-w 140)
(def zone-h 80)
(def gap 16)
(def corner-r 10)
(def columns 4)

(def zones
  "Each zone: [keyword label color]"
  [[:default     "Default"      0xFF4A90D9]
   [:pointer     "Pointer"      0xFFFF69B4]
   [:text        "Text"         0xFF50C878]
   [:crosshair   "Crosshair"    0xFFFF8C00]
   [:move        "Move"         0xFF9B59B6]
   [:wait        "Wait"         0xFFE74C3C]
   [:progress    "Progress"     0xFF1ABC9C]
   [:not-allowed "Not Allowed"  0xFF95A5A6]
   [:ew-resize   "↔ EW Resize"  0xFFF39C12]
   [:ns-resize   "↕ NS Resize"  0xFF2ECC71]
   [:nwse-resize "╲ NWSE"       0xFFE67E22]
   [:nesw-resize "╱ NESW"       0xFF3498DB]])

;; ============================================================
;; State
;; ============================================================

(defonce hovered-zone (atom nil))

;; ============================================================
;; Hit testing
;; ============================================================

(defn- zone-rect
  "Compute [x y w h] for zone at index."
  [index origin-x origin-y]
  (let [col (mod index columns)
        row (quot index columns)
        x (+ origin-x (* col (+ zone-w gap)))
        y (+ origin-y (* row (+ zone-h gap)))]
    [x y zone-w zone-h]))

(defn- point-in-rect? [px py [x y w h]]
  (and (>= px x) (< px (+ x w))
       (>= py y) (< py (+ y h))))

(defn- find-hovered-zone
  "Return cursor keyword if mouse is over a zone, nil otherwise."
  [mx my origin-x origin-y]
  (first
    (keep-indexed
      (fn [i [cursor-kw _label _color]]
        (when (point-in-rect? mx my (zone-rect i origin-x origin-y))
          cursor-kw))
      zones)))

(defn- update-cursor!
  "Poll mouse position, detect hover zone, and set cursor."
  [origin-x origin-y]
  (let [[mx my] (window/get-mouse-position)
        hit (find-hovered-zone mx my origin-x origin-y)]
    (when (not= hit @hovered-zone)
      (reset! hovered-zone hit)
      (if hit
        (cursor/set-cursor! hit)
        (cursor/reset-cursor!)))))

;; ============================================================
;; Drawing
;; ============================================================

(defn- draw-zones [^Canvas canvas origin-x origin-y]
  (doseq [[i [cursor-kw label color]] (map-indexed vector zones)]
    (let [[x y w h] (zone-rect i origin-x origin-y)
          hovered? (= cursor-kw @hovered-zone)
          fill-color (if hovered?
                       color
                       (bit-and color (unchecked-int 0x99FFFFFF)))]
      ;; Background
      (shapes/rounded-rect canvas x y w h corner-r {:color fill-color})
      ;; Border when hovered
      (when hovered?
        (shapes/rounded-rect canvas x y w h corner-r
                             {:color 0xFFFFFFFF :mode :stroke :stroke-width 2.0}))
      ;; Label
      (text/text canvas label
                 (+ x (/ w 2)) (+ y (/ h 2) 5)
                 {:size 15 :weight :medium :align :center
                  :color (if hovered? 0xFFFFFFFF 0xDDFFFFFF)}))))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Cursor demo loaded! Hover over zones to change cursor."))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas width height]
  (let [total-w (+ (* columns zone-w) (* (dec columns) gap))
        rows (int (Math/ceil (/ (count zones) (double columns))))
        total-h (+ (* rows zone-h) (* (dec rows) gap))
        origin-x (/ (- width total-w) 2.0)
        origin-y (/ (- height total-h) 2.0)]

    ;; Title
    (text/text canvas "Cursor Demo" (/ width 2) (- origin-y 40)
               {:size 28 :weight :medium :align :center :color 0xFFFFFFFF})

    ;; Subtitle
    (text/text canvas "Hover over a zone to change the mouse cursor"
               (/ width 2) (- origin-y 12)
               {:size 14 :align :center :color 0xAAFFFFFF})

    ;; Poll mouse and update cursor
    (update-cursor! origin-x origin-y)

    ;; Draw zones
    (draw-zones canvas origin-x origin-y)))

(defn cleanup []
  (cursor/reset-cursor!)
  (reset! hovered-zone nil)
  (println "Cursor demo cleanup"))

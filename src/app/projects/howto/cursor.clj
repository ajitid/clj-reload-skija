(ns app.projects.howto.cursor
  "Cursor demo — hover over zones to change the mouse cursor.

   Demonstrates:
   - Setting system cursors by keyword via lib.window.cursor
   - Hiding and showing the cursor
   - Polling mouse position each frame for hover detection"
  (:require [lib.color.core :as color]
            [lib.color.open-color :as oc]
            [lib.graphics.shapes :as shapes]
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
  [[:default     "Default"      oc/blue-6]
   [:pointer     "Pointer"      [1.0 0.41 0.71 1.0]]
   [:text        "Text"         oc/green-5]
   [:crosshair   "Crosshair"    oc/orange-5]
   [:move        "Move"         [0.61 0.35 0.71 1.0]]
   [:wait        "Wait"         oc/red-7]
   [:progress    "Progress"     oc/teal-6]
   [:not-allowed "Not Allowed"  [0.58 0.65 0.65 1.0]]
   [:ew-resize   "↔ EW Resize"  oc/yellow-7]
   [:ns-resize   "↕ NS Resize"  oc/green-5]
   [:nwse-resize "╲ NWSE"       oc/yellow-9]
   [:nesw-resize "╱ NESW"       oc/blue-5]])

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
  (doseq [[i [cursor-kw label [r g b a]]] (map-indexed vector zones)]
    (let [[x y w h] (zone-rect i origin-x origin-y)
          hovered? (= cursor-kw @hovered-zone)
          fill-color (if hovered?
                       [r g b a]
                       [r g b 0.6])]
      ;; Background
      (shapes/rounded-rect canvas x y w h corner-r {:color fill-color})
      ;; Border when hovered
      (when hovered?
        (shapes/rounded-rect canvas x y w h corner-r
                             {:color color/white :mode :stroke :stroke-width 2.0}))
      ;; Label
      (text/text canvas label
                 (+ x (/ w 2)) (+ y (/ h 2) 5)
                 {:size 15 :weight :medium :align :center
                  :color (if hovered? color/white (color/with-alpha color/white 0.87))}))))

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
               {:size 28 :weight :medium :align :center :color color/white})

    ;; Subtitle
    (text/text canvas "Hover over a zone to change the mouse cursor"
               (/ width 2) (- origin-y 12)
               {:size 14 :align :center :color (color/with-alpha color/white 0.67)})

    ;; Poll mouse and update cursor
    (update-cursor! origin-x origin-y)

    ;; Draw zones
    (draw-zones canvas origin-x origin-y)))

(defn cleanup []
  (cursor/reset-cursor!)
  (reset! hovered-zone nil)
  (println "Cursor demo cleanup"))

(ns app.projects.howto.borderless
  "Borderless window — no title bar or window chrome.

   Demonstrates:
   - Removing window decorations via :bordered? false
   - Content renders edge-to-edge

   Launch: (open :howto/borderless)"
  (:require [lib.graphics.shapes :as shapes]
            [lib.text.core :as text]
            [lib.color.core :as color]
            [lib.color.open-color :as oc])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Window config — borderless
;; ============================================================

(def window-config
  {:bordered?      false
   :width          500
   :height         350
   :resizable?     true
   :always-on-top? false})

;; ============================================================
;; Example interface
;; ============================================================

(defn init []
  (println "Borderless window howto loaded"))

(defn tick [_dt]
  nil)

(defn draw [^Canvas canvas w h]
  ;; Full-bleed colored band across the top — shows content reaches the very edge
  (shapes/rectangle canvas 0 0 w 80
                     {:color oc/indigo-8})
  ;; Title inside the band
  (text/text canvas "Borderless Window"
             (/ w 2.0) 45
             {:size 24 :weight :medium :align :center :color color/white})
  ;; Thin accent line at band bottom
  (shapes/line canvas 0 80 w 80
               {:color oc/cyan-5 :stroke-width 2})
  ;; Description below the band
  (text/text canvas "No title bar \u2014 content fills to the window edge"
             (/ w 2.0) 110
             {:size 14 :align :center :color oc/gray-5})
  (text/text canvas ":bordered? false"
             (/ w 2.0) 132
             {:size 13 :align :center :color oc/gray-6})
  ;; Corner markers to highlight that we own every pixel
  (let [m 12 r 6]
    (shapes/circle canvas m m r {:color oc/red-5})
    (shapes/circle canvas (- w m) m r {:color oc/green-5})
    (shapes/circle canvas m (- h m) r {:color oc/yellow-5})
    (shapes/circle canvas (- w m) (- h m) r {:color oc/pink-5})))

(defn cleanup []
  (println "Borderless window howto cleanup"))

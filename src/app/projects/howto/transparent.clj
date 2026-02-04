(ns app.projects.howto.transparent
  "Transparent window — floating circle on a see-through background.

   Demonstrates:
   - Per-pixel window transparency via :transparent? config
   - Desktop visible through areas not drawn by the example
   - Borderless window (required by SDL3 for transparency)

   Launch: (open :howto/transparent)"
  (:require [lib.graphics.shapes :as shapes]
            [lib.color.open-color :as oc])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Window config — transparent + always-on-top
;; ============================================================

(def window-config
  {:transparent?   true
   :width          400
   :height         400
   :always-on-top? true
   :resizable?     true})

;; ============================================================
;; State
;; ============================================================

(defonce t (atom 0.0))

;; ============================================================
;; Example interface
;; ============================================================

(defn init []
  (println "Transparent window howto loaded"))

(defn tick [dt]
  (swap! t + dt))

(defn draw [^Canvas canvas w h]
  (let [cx (/ w 2.0)
        cy (/ h 2.0)
        ;; Gentle breathing animation
        pulse (+ 80.0 (* 10.0 (Math/sin (* @t 2.0))))]
    ;; Main circle — solid color, fully opaque
    (shapes/circle canvas cx cy pulse
                   {:color oc/cyan-5})
    ;; Outer ring
    (shapes/circle canvas cx cy (+ pulse 20.0)
                   {:color [0.3 0.8 1.0 0.4]
                    :mode :stroke
                    :stroke-width 3})
    ;; Small orbiting dot
    (let [angle (* @t 1.5)
          orbit-r (+ pulse 40.0)
          ox (+ cx (* orbit-r (Math/cos angle)))
          oy (+ cy (* orbit-r (Math/sin angle)))]
      (shapes/circle canvas ox oy 8
                     {:color oc/pink-5}))))

(defn cleanup []
  (println "Transparent window howto cleanup"))

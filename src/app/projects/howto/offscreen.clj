(ns app.projects.howto.offscreen
  "Off-screen rendering demo - animated ball with GPU snapshot capture.

   Demonstrates:
   - Off-screen rendering via Surface/makeRenderTarget
   - GPU-resident image snapshot (no CPU readback)
   - Semi-transparent overlay of captured frame
   - Button widget with tap gesture"
  (:require [app.ui.button :as button]
            [lib.flex.core :as flex]
            [lib.graphics.shapes :as shapes]
            [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas Surface ImageInfo Paint]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Configuration
;; ============================================================

(def panel-width 200)
(def panel-bg-color 0xFF333333)
(def ball-radius 30)
(def ball-color 0xFF4A90D9)
(def button-w 150)
(def button-h 40)
(def button-margin 25)
(def overlay-alpha 128)

;; ============================================================
;; State (persists across hot-reloads)
;; ============================================================

(flex/defsource window-width 800)
(flex/defsource window-height 600)
(flex/defsource button-pressed? false)

(defonce ball-y (atom 250.0))
(defonce ball-spring-anim (atom nil))
(defonce captured-image (atom nil))

;; ============================================================
;; Ball scene drawing (reused for live + off-screen)
;; ============================================================

(defn draw-ball-scene [^Canvas canvas area-w area-h]
  (let [cx (/ area-w 2.0)
        cy (double @ball-y)]
    ;; Background
    (shapes/rectangle canvas 0 0 area-w area-h {:color 0xFF1A1A2E})
    ;; Ball
    (shapes/circle canvas cx cy ball-radius {:color ball-color})
    ;; Label
    (text/text canvas "Live" 12 24 {:size 14 :color 0x88FFFFFF})))

;; ============================================================
;; Off-screen capture
;; ============================================================

(defn capture-drawing-area! []
  (let [area-w (- @window-width panel-width)
        area-h @window-height]
    (when-let [ctx-fn (requiring-resolve 'lib.window.layer/context)]
      (when-let [ctx (ctx-fn)]
        (let [info (ImageInfo/makeN32Premul (int area-w) (int area-h))
              surface (Surface/makeRenderTarget ctx true info)]
          (when surface
            (let [offscreen-canvas (.getCanvas surface)]
              (draw-ball-scene offscreen-canvas area-w area-h)
              ;; Close old image before replacing
              (when-let [old @captured-image]
                (.close old))
              (reset! captured-image (.makeImageSnapshot surface))
              (.close surface))))))))

;; ============================================================
;; Gesture setup
;; ============================================================

(defn button-bounds-fn [_ctx]
  (let [w @window-width
        h @window-height
        panel-x (- w panel-width)
        bx (+ panel-x button-margin)
        by (- (/ h 2) (/ button-h 2))]
    [bx by button-w button-h]))

(def button-handlers
  {:on-tap (fn [_event]
             (capture-drawing-area!))
   :on-drag-start (fn [_event]
                    (button-pressed? true))
   :on-drag-end (fn [_event]
                  (button-pressed? false))})

(defn register-gestures! []
  (when-let [register! (requiring-resolve 'lib.gesture.api/register-target!)]
    (when-let [clear! (requiring-resolve 'lib.gesture.api/clear-targets!)]
      (clear!))
    (register!
     {:id :capture-button
      :layer :overlay
      :z-index 10
      :bounds-fn button-bounds-fn
      :gesture-recognizers [:tap :drag]
      :handlers button-handlers})))

;; ============================================================
;; Animation setup
;; ============================================================

(defn start-ball-animation! []
  (when-let [animate! (requiring-resolve 'lib.anim.registry/animate!)]
    (when-let [spring-fn (requiring-resolve 'lib.anim.spring/spring)]
      (let [anim (spring-fn {:from 100 :to 400
                             :stiffness 40 :damping 6
                             :loop true :alternate true})]
        (animate! :offscreen-ball-y anim {:target ball-y})
        (reset! ball-spring-anim :offscreen-ball-y)))))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Offscreen rendering demo loaded!")
  (reset! ball-y 250.0)
  (start-ball-animation!)
  (register-gestures!))

(defn tick [_dt]
  ;; Animation registry ticks automatically
  )

(defn draw [^Canvas canvas width height]
  (window-width width)
  (window-height height)

  (let [area-w (- width panel-width)]
    ;; 1. Clip to drawing area and draw live ball scene
    (let [save-count (.save canvas)]
      (.clipRect canvas (Rect/makeXYWH 0 0 (float area-w) (float height)))
      (draw-ball-scene canvas area-w height)

      ;; 2. Draw captured overlay if present
      (when-let [img @captured-image]
        (with-open [p (doto (Paint.) (.setAlpha overlay-alpha))]
          (.drawImage canvas img 0 0 p))
        ;; Label for overlay
        (text/text canvas "Captured (overlay)" 12 (- height 12)
                   {:size 14 :color 0xAAFFFFFF}))
      (.restoreToCount canvas save-count))

    ;; 3. Draw control panel
    (let [panel-x (float area-w)]
      ;; Panel background
      (shapes/rectangle canvas panel-x 0 panel-width height {:color panel-bg-color})

      ;; Title
      (text/text canvas "Controls" (+ area-w (/ panel-width 2)) 40
                 {:size 18 :color 0xFFFFFFFF :align :center})

      ;; Capture button
      (let [bx (+ area-w button-margin)
            by (- (/ height 2) (/ button-h 2))]
        (button/draw canvas "Capture" [bx by button-w button-h]
                     {:color 0xFF4A90D9
                      :pressed-color 0xFF3A7AB9
                      :pressed? @button-pressed?}))

      ;; Status text
      (text/text canvas
                 (if @captured-image "Overlay active" "No capture yet")
                 (+ area-w (/ panel-width 2))
                 (+ (/ height 2) 50)
                 {:size 13 :color 0x88FFFFFF :align :center}))))

(defn cleanup []
  (println "Offscreen demo cleanup")
  ;; Cancel animation
  (when-let [cancel! (requiring-resolve 'lib.anim.registry/cancel!)]
    (when @ball-spring-anim
      (cancel! @ball-spring-anim)
      (reset! ball-spring-anim nil)))
  ;; Close captured image
  (when-let [img @captured-image]
    (.close img)
    (reset! captured-image nil)))

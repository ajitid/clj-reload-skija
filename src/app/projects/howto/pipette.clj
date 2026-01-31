(ns app.projects.howto.pipette
  "Pipette / color picker demo - sample colors from a gradient image.

   Demonstrates:
   - Programmatic gradient image generation (no external files)
   - Pixel sampling via Bitmap (CPU readback)
   - Colored circles with hex labels at sample positions"
  (:require [lib.color.core :as color]
            [lib.color.open-color :as oc]
            [lib.graphics.image :as image]
            [lib.graphics.shapes :as shapes]
            [lib.graphics.gradients :as grad]
            [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas Surface ImageInfo Paint Bitmap]))

;; ============================================================
;; Configuration
;; ============================================================

(def image-w 600)
(def image-h 400)
(def sample-count 6)
(def sample-radius 18)
(def outline-width 3)

;; ============================================================
;; State (persists across hot-reloads)
;; ============================================================

(defonce gradient-image (atom nil))
(defonce sampled-points (atom []))

;; ============================================================
;; Color utilities
;; ============================================================

(defn color->hex
  "Convert ARGB int to hex string like #RRGGBB."
  [color]
  (let [r (bit-and (bit-shift-right color 16) 0xFF)
        g (bit-and (bit-shift-right color 8) 0xFF)
        b (bit-and color 0xFF)]
    (format "#%02X%02X%02X" r g b)))

(defn color-int->color4f
  "Convert packed ARGB int to [r g b a] floats."
  [color]
  [(/ (bit-and (bit-shift-right color 16) 0xFF) 255.0)
   (/ (bit-and (bit-shift-right color 8) 0xFF) 255.0)
   (/ (bit-and color 0xFF) 255.0)
   (/ (bit-and (bit-shift-right color 24) 0xFF) 255.0)])

;; ============================================================
;; Gradient image generation
;; ============================================================

(defn generate-gradient-image!
  "Create a colorful gradient image programmatically."
  []
  ;; Close old image if present
  (when-let [old @gradient-image]
    (.close old))

  (let [info (ImageInfo/makeN32Premul image-w image-h)
        surface (Surface/makeRaster info)
        canvas (.getCanvas surface)]

    ;; Layer 1: horizontal rainbow gradient
    (with-open [shader (grad/linear-gradient 0 0 image-w 0
                         [[1.0 0.0 0.0 1.0]
                          oc/orange-5
                          [1.0 1.0 0.0 1.0]
                          [0.0 1.0 0.0 1.0]
                          oc/blue-6
                          [0.533 0.0 1.0 1.0]
                          [1.0 0.0 0.533 1.0]])
                paint (doto (Paint.) (.setShader shader))]
      (.drawRect canvas (io.github.humbleui.types.Rect/makeXYWH 0 0 (float image-w) (float image-h)) paint))

    ;; Layer 2: vertical white→transparent→black overlay
    (with-open [shader (grad/linear-gradient 0 0 0 image-h
                         [(color/with-alpha color/white 0.533)
                          (color/with-alpha color/black 0.0)
                          (color/with-alpha color/black 0.533)]
                         [0.0 0.5 1.0])
                paint (doto (Paint.) (.setShader shader))]
      (.drawRect canvas (io.github.humbleui.types.Rect/makeXYWH 0 0 (float image-w) (float image-h)) paint))

    ;; Layer 3: radial gradient spots for variety
    (doseq [[cx cy r colors] [[150 120 100
                                [(color/with-alpha color/white 0.4) (color/with-alpha color/white 0.0)]]
                               [450 280 120
                                [[0.0 1.0 1.0 0.4] [0.0 1.0 1.0 0.0]]]
                               [300 200 80
                                [[1.0 0.0 1.0 0.267] [1.0 0.0 1.0 0.0]]]]]
      (with-open [shader (grad/radial-gradient cx cy r colors)
                  paint (doto (Paint.) (.setShader shader))]
        (.drawRect canvas (io.github.humbleui.types.Rect/makeXYWH
                           (float (- cx r)) (float (- cy r))
                           (float (* 2 r)) (float (* 2 r)))
                   paint)))

    (let [img (.makeImageSnapshot surface)]
      (.close surface)
      (reset! gradient-image img))))

;; ============================================================
;; Pixel sampling
;; ============================================================

(defn sample-pixels!
  "Sample random pixels from the gradient image using Bitmap."
  []
  (when-let [img @gradient-image]
    (let [bmp (doto (Bitmap.)
                (.allocPixels (ImageInfo/makeN32Premul image-w image-h)))]
      (.readPixels img bmp)
      (let [margin 40
            points (vec (for [_ (range sample-count)]
                          (let [x (+ margin (rand-int (- image-w (* 2 margin))))
                                y (+ margin (rand-int (- image-h (* 2 margin))))
                                color (.getColor bmp x y)]
                            {:x x :y y :color color})))]
        (.close bmp)
        (reset! sampled-points points)))))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Pipette color picker demo loaded!")
  (generate-gradient-image!)
  (sample-pixels!))

(defn tick [_dt]
  ;; Static demo, no per-frame updates
  )

(defn draw [^Canvas canvas width height]
  ;; Center image on screen
  (let [offset-x (/ (- width image-w) 2.0)
        offset-y (/ (- height image-h) 2.0)]

    ;; Title
    (text/text canvas "Pipette - Color Sampling" (/ width 2) 40
               {:size 28 :weight :medium :align :center :color color/white})

    ;; Draw gradient image
    (when-let [img @gradient-image]
      (image/draw-image canvas img offset-x offset-y))

    ;; Draw sample circles
    (doseq [{:keys [x y color]} @sampled-points]
      (let [screen-x (+ offset-x x)
            screen-y (+ offset-y y)
            color4f (color-int->color4f color)]
        ;; White outline circle
        (shapes/circle canvas screen-x screen-y (+ sample-radius outline-width)
                       {:color color/white})
        ;; Filled circle in sampled color
        (shapes/circle canvas screen-x screen-y sample-radius
                       {:color color4f})
        ;; Hex label below
        (text/text canvas (color->hex color)
                   screen-x (+ screen-y sample-radius 20)
                   {:size 12 :color color/white :align :center})))))

(defn cleanup []
  (println "Pipette demo cleanup")
  (when-let [img @gradient-image]
    (.close img)
    (reset! gradient-image nil))
  (reset! sampled-points []))

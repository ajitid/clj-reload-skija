(ns app.projects.howto.pipette-photo
  "Interactive pipette on downloaded photos.

   Demonstrates:
   - Downloading images at runtime (picsum.photos)
   - Pixel-level color sampling via Bitmap
   - Click-to-pick interaction with gesture system
   - Button widget for controls"
  (:require [app.shell.state :as state]
            [lib.color.core :as color]
            [lib.color.open-color :as oc]
            [app.ui.button :as button]
            [lib.flex.core :as flex]
            [lib.graphics.image :as image]
            [lib.graphics.shapes :as shapes]
            [lib.text.core :as text])
  (:import [io.github.humbleui.skija Canvas ImageInfo Bitmap]
           [io.github.humbleui.types Rect]
           [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpResponse$BodyHandlers]))

;; ============================================================
;; Configuration
;; ============================================================

(def window-config {:width 1100 :height 830})

(def photo-url "https://picsum.photos/800/600")
(def photo-w 800)
(def photo-h 600)
(def sample-radius 14)
(def outline-width 3)
(def palette-size 32)
(def palette-gap 24)
(def top-bar-height 50)

;; Button layout
(def btn-w 120)
(def btn-h 34)
(def btn-gap 12)

;; ============================================================
;; State (persists across hot-reloads)
;; ============================================================

(flex/defsource window-width 800)
(flex/defsource window-height 600)

(defonce photo-image (atom nil))
(defonce photo-bitmap (atom nil))
(defonce sampled-points (atom []))
(defonce loading? (atom false))

;; ============================================================
;; Color utilities
;; ============================================================

(defn color->hex [color]
  (let [r (bit-and (bit-shift-right color 16) 0xFF)
        g (bit-and (bit-shift-right color 8) 0xFF)
        b (bit-and color 0xFF)]
    (format "#%02X%02X%02X" r g b)))

;; ============================================================
;; Image download
;; ============================================================

(defn- make-http-client []
  (-> (HttpClient/newBuilder)
      (.followRedirects HttpClient$Redirect/ALWAYS)
      (.build)))

(defn- fetch-image-bytes [url]
  (let [client (make-http-client)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI. url))
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofByteArray))]
    (.body response)))

(defn- rebuild-bitmap! [img]
  ;; Close old bitmap
  (when-let [old @photo-bitmap]
    (.close old))
  (let [w (.getWidth img)
        h (.getHeight img)
        bmp (doto (Bitmap.)
              (.allocPixels (ImageInfo/makeN32Premul w h)))]
    (.readPixels img bmp)
    (reset! photo-bitmap bmp)))

(defn download-photo! []
  (when-not @loading?
    (reset! loading? true)
    (future
      (try
        (let [bytes (fetch-image-bytes photo-url)
              img (image/bytes->image bytes)]
          ;; Close old image
          (when-let [old @photo-image]
            (.close old))
          (reset! photo-image img)
          (rebuild-bitmap! img)
          (reset! sampled-points []))
        (catch Exception e
          (println "Failed to download photo:" (.getMessage e)))
        (finally
          (reset! loading? false))))))

;; ============================================================
;; Pixel sampling
;; ============================================================

(defn sample-at!
  "Sample the color at image-local coordinates (ix, iy)."
  [ix iy]
  (when-let [bmp @photo-bitmap]
    (when-let [img @photo-image]
      (let [w (.getWidth img)
            h (.getHeight img)]
        (when (and (>= ix 0) (< ix w) (>= iy 0) (< iy h))
          (let [color (.getColor bmp (int ix) (int iy))]
            (swap! sampled-points conj {:x ix :y iy :color color})))))))

;; ============================================================
;; Layout helpers
;; ============================================================

(defn image-offset
  "Get the top-left offset for centering the photo."
  [win-w win-h]
  (when-let [img @photo-image]
    (let [iw (.getWidth img)
          ih (.getHeight img)
          ox (/ (- win-w iw) 2.0)
          oy (+ top-bar-height (/ (- (- win-h top-bar-height 90) ih) 2.0))]
      [ox oy iw ih])))

(defn new-photo-btn-bounds [_ctx]
  (let [w @window-width]
    [(- w btn-w btn-w btn-gap btn-gap 10) 8 btn-w btn-h]))

(defn clear-btn-bounds [_ctx]
  (let [w @window-width]
    [(- w btn-w 10) 8 btn-w btn-h]))

(defn image-area-bounds [_ctx]
  (let [w @window-width
        h @window-height]
    (if-let [[ox oy iw ih] (image-offset w h)]
      [ox oy iw ih]
      (color/with-alpha color/black 0.0))))

;; ============================================================
;; Gesture setup
;; ============================================================

(def image-tap-handlers
  {:on-tap (fn [event]
             (let [mx (get-in event [:pointer :x])
                   my (get-in event [:pointer :y])
                   w @window-width
                   h @window-height]
               (when-let [[ox oy _iw _ih] (image-offset w h)]
                 (let [ix (- mx ox)
                       iy (- my oy)]
                   (sample-at! ix iy)))))})

(def new-photo-handlers
  {:on-tap (fn [_] (download-photo!))})

(def clear-handlers
  {:on-tap (fn [_] (reset! sampled-points []))})

(defn register-gestures! []
  (when-let [register! (requiring-resolve 'lib.gesture.api/register-target!)]
    (when-let [clear! (requiring-resolve 'lib.gesture.api/clear-targets!)]
      (clear!))
    ;; Image area — click to sample
    (register!
     {:id :pipette-image
      :layer :content
      :z-index 1
      :bounds-fn image-area-bounds
      :gesture-recognizers [:tap]
      :handlers image-tap-handlers})
    ;; New Photo button
    (register!
     {:id :pipette-new-photo
      :layer :overlay
      :window (state/panel-gesture-window)
      :z-index 10
      :bounds-fn new-photo-btn-bounds
      :gesture-recognizers [:tap]
      :handlers new-photo-handlers})
    ;; Clear button
    (register!
     {:id :pipette-clear
      :layer :overlay
      :window (state/panel-gesture-window)
      :z-index 10
      :bounds-fn clear-btn-bounds
      :gesture-recognizers [:tap]
      :handlers clear-handlers})))

;; ============================================================
;; Drawing
;; ============================================================

(defn draw-palette [^Canvas canvas width height]
  (let [points @sampled-points
        n (count points)]
    (when (pos? n)
      (let [total-w (+ (* n palette-size) (* (dec n) palette-gap))
            start-x (/ (- width total-w) 2.0)
            py (- height 80)]
        (doseq [i (range n)]
          (let [{:keys [color]} (nth points i)
                color4f (color/hex->color4f color)
                px (+ start-x (* i (+ palette-size palette-gap)))]
            ;; Swatch square
            (shapes/rectangle canvas px py palette-size palette-size {:color color4f})
            (shapes/rectangle canvas px py palette-size palette-size
                              {:color color/white :mode :stroke :stroke-width 1.5})
            ;; Hex label
            (text/text canvas (color->hex color)
                       (+ px (/ palette-size 2)) (+ py palette-size 16)
                       {:size 10 :color [0.667 1 1 1] :align :center})))))))

(defn draw [^Canvas canvas width height]
  (window-width width)
  (window-height height)

  ;; Title
  (text/text canvas "Pipette Photo" (/ width 2) 40
             {:size 28 :weight :medium :align :center :color color/white})

  ;; Buttons
  (let [[bx by bw bh] (new-photo-btn-bounds nil)]
    (button/draw canvas "New Photo" [bx by bw bh]
                 {:color oc/blue-6 :pressed-color oc/blue-7}))
  (let [[bx by bw bh] (clear-btn-bounds nil)]
    (button/draw canvas "Clear" [bx by bw bh]
                 {:color [0.4 0.4 0.4 1.0] :pressed-color [0.533 0.533 0.533 1.0]}))

  ;; Photo or loading state
  (if @loading?
    (text/text canvas "Loading photo..." (/ width 2) (/ height 2)
               {:size 18 :color [0.533 1 1 1] :align :center})
    (if-let [img @photo-image]
      (when-let [[ox oy iw ih] (image-offset width height)]
        ;; Draw image
        (image/draw-image canvas img ox oy)

        ;; Draw border
        (shapes/rectangle canvas ox oy iw ih
                          {:color [0.267 1 1 1] :mode :stroke :stroke-width 1})

        ;; Draw sample circles on image
        (doseq [{:keys [x y color]} @sampled-points]
          (let [sx (+ ox x)
                sy (+ oy y)
                color4f (color/hex->color4f color)]
            ;; White outline
            (shapes/circle canvas sx sy (+ sample-radius outline-width)
                           {:color color/white})
            ;; Filled with sampled color
            (shapes/circle canvas sx sy sample-radius
                           {:color color4f})
            ;; Hex label
            (text/text canvas (color->hex color)
                       sx (+ sy sample-radius 18)
                       {:size 11 :color color/white :align :center})))

        ;; Hint text
        (when (empty? @sampled-points)
          (text/text canvas "Click on the image to pick colors"
                     (+ ox (/ iw 2)) (+ oy ih 20)
                     {:size 14 :color [0.4 1 1 1] :align :center})))

      ;; No image yet
      (text/text canvas "Press 'New Photo' to download" (/ width 2) (/ height 2)
                 {:size 16 :color [0.4 1 1 1] :align :center})))

  ;; Palette strip at bottom
  (draw-palette canvas width height))

;; ============================================================
;; Example Interface
;; ============================================================

(defn init []
  (println "Pipette Photo demo loaded!")
  (register-gestures!)
  ;; Auto-download first photo if none loaded
  (when-not @photo-image
    (download-photo!)))

(defn tick [_dt])

(defn cleanup []
  (println "Pipette Photo cleanup")
  (when-let [bmp @photo-bitmap]
    (.close bmp)
    (reset! photo-bitmap nil))
  (when-let [img @photo-image]
    (.close img)
    (reset! photo-image nil))
  (reset! sampled-points []))

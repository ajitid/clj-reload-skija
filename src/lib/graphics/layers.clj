(ns lib.graphics.layers
  "Layer/precompose system for grouped drawing effects - Love2D-style graphics API.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern).

   ## What are Layers?

   Layers (via saveLayer) create an offscreen buffer where you can:
   - Draw multiple shapes as a group
   - Apply effects (blur, blend modes, alpha) to the entire group on restore
   - Create frosted glass effects with backdrop filters

   ## Quick Start

   ```clojure
   ;; Simple alpha layer
   (with-layer [canvas {:alpha 128}]
     (draw-many-things...))  ; All drawn at 50% opacity

   ;; Layer with blur on restore
   (with-layer [canvas {:paint {:blur 5.0}}]
     (draw-content...))

   ;; Frosted glass effect
   (with-layer [canvas {:backdrop (blur 10.0)}]
     (fill-panel...))
   ```"
  (:require [lib.graphics.state :as gfx])
  (:import [io.github.humbleui.skija Canvas SaveLayerRec ImageFilter Paint]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Explicit Layer API
;; ============================================================

(defn save-layer
  "Save canvas state and create a layer with optional paint.

   The paint is applied when the layer is restored, allowing effects
   like blur or blend modes to affect the entire layer contents.

   Args:
     canvas - drawing canvas
     bounds - optional [x y w h] bounds for the layer (nil = full canvas)
     paint  - optional Paint instance to apply on restore

   Returns: save count (for nested saves)

   Example:
     (save-layer canvas nil my-blur-paint)
     (draw-stuff...)
     (restore canvas)"
  ([^Canvas canvas]
   (.saveLayer canvas ^Rect nil ^Paint nil))
  ([^Canvas canvas bounds]
   (let [rect (when bounds
                (let [[x y w h] bounds]
                  (Rect/makeXYWH (float x) (float y) (float w) (float h))))]
     (.saveLayer canvas rect ^Paint nil)))
  ([^Canvas canvas bounds ^Paint paint]
   (let [rect (when bounds
                (let [[x y w h] bounds]
                  (Rect/makeXYWH (float x) (float y) (float w) (float h))))]
     (.saveLayer canvas rect paint))))

(defn save-layer-alpha
  "Save canvas state and create a layer with alpha transparency.

   Everything drawn in this layer will have the specified alpha applied
   when the layer is restored.

   Args:
     canvas - drawing canvas
     bounds - optional [x y w h] bounds for the layer (nil = full canvas)
     alpha  - alpha value 0-255 (0 = transparent, 255 = opaque)

   Returns: save count

   Example:
     (save-layer-alpha canvas nil 128)  ; 50% opacity layer
     (draw-stuff...)
     (restore canvas)"
  ([^Canvas canvas alpha]
   (.saveLayerAlpha canvas ^Rect nil (int alpha)))
  ([^Canvas canvas bounds alpha]
   (let [rect (when bounds
                (let [[x y w h] bounds]
                  (Rect/makeXYWH (float x) (float y) (float w) (float h))))]
     (.saveLayerAlpha canvas rect (int alpha)))))

(defn save-layer-rec
  "Save canvas state with full SaveLayerRec control.

   This is the most flexible layer API, supporting backdrop filters
   for effects like frosted glass.

   Args:
     canvas - drawing canvas
     opts   - options map:
              :bounds   - [x y w h] layer bounds (nil = full canvas)
              :paint    - Paint instance or paint options map
              :backdrop - ImageFilter for backdrop effects (frosted glass)
              :init-with-previous? - start with existing canvas content (default false)

   Returns: save count

   Example:
     ;; Frosted glass effect
     (save-layer-rec canvas {:backdrop (blur 10.0)})
     (draw-panel-background...)
     (restore canvas)"
  [^Canvas canvas opts]
  (let [{:keys [bounds paint backdrop init-with-previous?]} opts
        rect (when bounds
               (let [[x y w h] bounds]
                 (Rect/makeXYWH (float x) (float y) (float w) (float h))))
        paint-obj (cond
                    (nil? paint) nil
                    (instance? Paint paint) paint
                    (map? paint) (gfx/make-paint paint)
                    :else (throw (ex-info "Invalid paint option" {:paint paint})))
        rec (cond-> (SaveLayerRec.)
              rect (.setBounds rect)
              paint-obj (.setPaint paint-obj)
              backdrop (.setBackdrop ^ImageFilter backdrop)
              init-with-previous? (.setInitWithPrevious true))]
    (.saveLayer canvas rec)))

(defn restore
  "Restore canvas to previous save state, applying layer effects.

   Args:
     canvas - drawing canvas"
  [^Canvas canvas]
  (.restore canvas)
  canvas)

(defn restore-to-count
  "Restore canvas to a specific save count.

   Useful for restoring multiple nested saves at once.

   Args:
     canvas     - drawing canvas
     save-count - save count to restore to"
  [^Canvas canvas save-count]
  (.restoreToCount canvas (int save-count))
  canvas)

(defn get-save-count
  "Get current save count.

   Args:
     canvas - drawing canvas

   Returns: current save count"
  [^Canvas canvas]
  (.getSaveCount canvas))

;; ============================================================
;; Layer Macro
;; ============================================================

(defmacro with-layer
  "Execute body within a layer. Automatically saves and restores.

   Args:
     canvas - drawing canvas
     opts   - layer options map:
              :bounds   - [x y w h] layer bounds (nil = full canvas)
              :paint    - Paint instance or paint options map
              :alpha    - shorthand for alpha-only layer (0-255)
              :backdrop - ImageFilter for backdrop effects
              :init-with-previous? - start with existing content

   Examples:
     ;; Alpha layer (50% opacity)
     (with-layer [canvas {:alpha 128}]
       (shapes/circle canvas 100 100 50 {:color 0xFFFF0000})
       (shapes/circle canvas 150 100 50 {:color 0xFF00FF00}))

     ;; Layer with blur effect
     (with-layer [canvas {:paint {:blur 5.0}}]
       (draw-content...))

     ;; Layer with blend mode
     (with-layer [canvas {:paint {:blend-mode :multiply}}]
       (draw-shapes...))

     ;; Frosted glass effect
     (require '[lib.graphics.filters :as filters])
     (with-layer [canvas {:backdrop (filters/blur 10.0)}]
       (shapes/rounded-rect canvas 10 10 200 100 10 {:color 0x80FFFFFF}))

     ;; Bounded layer (only affects region)
     (with-layer [canvas {:bounds [0 0 100 100] :alpha 200}]
       (draw-in-corner...))"
  [[canvas opts] & body]
  `(let [c# ~canvas
         opts# ~opts]
     (cond
       ;; Simple alpha shorthand
       (and (:alpha opts#) (not (:paint opts#)) (not (:backdrop opts#)))
       (save-layer-alpha c# (:bounds opts#) (:alpha opts#))

       ;; Full SaveLayerRec for complex options
       (or (:backdrop opts#) (:init-with-previous? opts#) (:paint opts#))
       (save-layer-rec c# opts#)

       ;; Basic save layer
       :else
       (save-layer c# (:bounds opts#)))
     (try
       ~@body
       (finally
         (restore c#)))))

;; ============================================================
;; Convenience Functions
;; ============================================================

(defn with-opacity
  "Execute a drawing function with reduced opacity.

   This is a convenience wrapper around with-layer for simple opacity cases.

   Args:
     canvas  - drawing canvas
     alpha   - opacity 0.0-1.0
     draw-fn - function that takes canvas and draws

   Example:
     (with-opacity canvas 0.5
       (fn [c] (shapes/circle c 100 100 50 {:color 0xFFFF0000})))"
  [^Canvas canvas alpha draw-fn]
  (let [alpha-int (int (* 255 (max 0.0 (min 1.0 alpha))))]
    (save-layer-alpha canvas nil alpha-int)
    (try
      (draw-fn canvas)
      (finally
        (restore canvas)))))

(defn with-blend-mode
  "Execute a drawing function with a specific blend mode.

   Args:
     canvas    - drawing canvas
     mode      - blend mode keyword (see state/set-blend-mode)
     draw-fn   - function that takes canvas and draws

   Example:
     (with-blend-mode canvas :multiply
       (fn [c] (draw-overlapping-shapes c)))"
  [^Canvas canvas mode draw-fn]
  (save-layer-rec canvas {:paint {:blend-mode mode}})
  (try
    (draw-fn canvas)
    (finally
      (restore canvas))))

;; ============================================================
;; Advanced: Masking via Layers
;; ============================================================

(defmacro with-mask
  "Apply a mask to content using blend modes.

   The mask-fn draws the mask shape (white = visible, black/transparent = hidden).
   The content-fn draws the content that will be masked.

   Args:
     canvas     - drawing canvas
     bounds     - [x y w h] bounds for the mask layer
     mask-fn    - function that draws the mask shape
     content-fn - function that draws the masked content

   Example:
     (with-mask canvas [0 0 200 200]
       ;; Mask: circle reveals content
       (fn [c] (shapes/circle c 100 100 50 {:color 0xFFFFFFFF}))
       ;; Content: gradient that gets masked to circle
       (fn [c] (shapes/rectangle c 0 0 200 200 {:gradient ...})))"
  [canvas bounds mask-fn content-fn]
  `(let [c# ~canvas]
     ;; Draw content first
     (save-layer c# ~bounds nil)
     (try
       (~content-fn c#)
       ;; Apply mask using DST_IN blend mode
       (save-layer-rec c# {:bounds ~bounds :paint {:blend-mode :dst-in}})
       (try
         (~mask-fn c#)
         (finally
           (restore c#)))
       (finally
         (restore c#)))))

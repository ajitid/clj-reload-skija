(ns lib.graphics.layers
  "Layer system for grouped drawing effects - Love2D-style graphics API.

   NOTE: Not hot-reloadable (lib.* namespaces require restart).

   ## What are Layers?

   Layers create an offscreen buffer where you can:
   - Draw multiple shapes as a group
   - Apply effects (blur, blend modes, alpha) to the entire group
   - Create frosted glass effects with backdrop filters

   ## Quick Start

   ```clojure
   ;; Alpha layer (50% opacity)
   (layers/with-layer [canvas {:alpha 128}]
     (draw-many-things...))

   ;; Blend mode
   (layers/with-layer [canvas {:blend-mode :multiply}]
     (draw-shapes...))

   ;; Frosted glass
   (layers/with-layer [canvas {:backdrop (filters/blur 10)}]
     (fill-panel...))

   ;; Combined
   (layers/with-layer [canvas {:alpha 128 :bounds [0 0 100 100]}]
     (draw-in-corner...))
   ```"
  (:require [lib.graphics.state :as gfx])
  (:import [io.github.humbleui.skija Canvas SaveLayerRec ImageFilter Paint]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Layer Macro
;; ============================================================

(defmacro with-layer
  "Execute body within a layer. Automatically saves and restores.

   Opts:
     :alpha      - opacity 0-255
     :bounds     - [x y w h] layer bounds (nil = full canvas)
     :backdrop   - ImageFilter for backdrop effects (frosted glass)
     :blend-mode - blend mode keyword

   Examples:
     (with-layer [canvas {:alpha 128}]
       (shapes/circle canvas 100 100 50 {:color [1 0 0 1]}))

     (with-layer [canvas {:backdrop (filters/blur 10)}]
       (shapes/rounded-rect canvas 10 10 200 100 10 {:color [1 1 1 0.5]}))"
  [[canvas opts] & body]
  `(let [c# ~canvas
         opts# ~opts
         alpha# (:alpha opts#)
         bounds# (:bounds opts#)
         backdrop# (:backdrop opts#)
         blend-mode# (:blend-mode opts#)
         rect# (when bounds#
                 (let [[x# y# w# h#] bounds#]
                   (Rect/makeXYWH (float x#) (float y#) (float w#) (float h#))))]
     (cond
       ;; Simple alpha-only case
       (and alpha# (not backdrop#) (not blend-mode#))
       (.saveLayerAlpha c# rect# (int alpha#))

       ;; Complex case with backdrop or blend mode
       (or backdrop# blend-mode#)
       (let [paint# (when blend-mode# (gfx/make-paint {:blend-mode blend-mode#}))
             rec# (SaveLayerRec. rect# paint# ^ImageFilter backdrop#)]
         (.saveLayer c# rec#))

       ;; Basic layer
       :else
       (.saveLayer c# rect# nil))
     (try
       ~@body
       (finally
         (.restore c#)))))

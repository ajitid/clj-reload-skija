(ns lib.window.layer
  "Skija surface management - port of JWM's LayerGLSkija pattern.
   Handles DirectContext, BackendRenderTarget, and Surface lifecycle."
  (:import [io.github.humbleui.skija DirectContext BackendRenderTarget
            Surface SurfaceOrigin SurfaceColorFormat]))

;; GL_RGBA8 format constant (0x8058 in OpenGL)
(def ^:private GL_RGBA8 0x8058)

(defonce ^:private state
  (atom {:context       nil
         :render-target nil
         :surface       nil
         :width         0
         :height        0}))

(defn- ensure-context!
  "Create DirectContext if it doesn't exist."
  []
  (when-not (:context @state)
    (swap! state assoc :context (DirectContext/makeGL))))

(defn- ensure-surface!
  "Create or recreate surface at the given physical dimensions."
  [width height]
  (let [{:keys [context render-target surface]} @state
        needs-recreate? (or (nil? surface)
                            (not= width (:width @state))
                            (not= height (:height @state)))]
    (when needs-recreate?
      ;; Cleanup old resources
      (when surface (.close surface))
      (when render-target (.close render-target))

      ;; Create new at current size
      ;; makeGL(width, height, sampleCnt, stencilBits, fbId, format)
      ;; stencil=8, samples=0, fbId=0 (default framebuffer), format=GL_RGBA8
      (let [rt (BackendRenderTarget/makeGL width height 0 8 0 GL_RGBA8)
            sf (Surface/wrapBackendRenderTarget
                 context rt SurfaceOrigin/BOTTOM_LEFT
                 SurfaceColorFormat/RGBA_8888 nil nil)]
        (swap! state assoc
               :render-target rt
               :surface sf
               :width width
               :height height)))))

(defn frame!
  "Prepare a frame for rendering at the given physical dimensions.
   Returns {:surface surface :canvas canvas :flush-fn fn}."
  [physical-width physical-height]
  (ensure-context!)
  (ensure-surface! physical-width physical-height)
  (let [{:keys [context surface]} @state]
    {:surface  surface
     :canvas   (.getCanvas surface)
     :flush-fn #(.flushAndSubmit surface)}))

(defn resize!
  "Called on window resize. Invalidates surface for recreation on next frame."
  []
  (let [{:keys [surface render-target]} @state]
    (when surface (.close surface))
    (when render-target (.close render-target))
    (swap! state assoc :surface nil :render-target nil)))

(defn cleanup!
  "Release all GPU resources."
  []
  (let [{:keys [surface render-target context]} @state]
    (when surface (.close surface))
    (when render-target (.close render-target))
    (when context (.close context))
    (reset! state {:context nil :render-target nil :surface nil
                   :width 0 :height 0})))

(defn context
  "Get the DirectContext (for flushing)."
  []
  (:context @state))

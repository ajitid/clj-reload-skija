(ns lib.window.layer
  "Skija surface management - port of JWM's LayerGLSkija pattern.
   Handles DirectContext, BackendRenderTarget, and Surface lifecycle.
   Per-window surface registry with shared DirectContext."
  (:import [io.github.humbleui.skija DirectContext BackendRenderTarget
            Surface SurfaceOrigin ColorType]))

;; GL_RGBA8 format constant (0x8058 in OpenGL)
(def ^:private GL_RGBA8 0x8058)

;; Shared DirectContext (one per GL context)
(defonce ^:private gl-ctx (atom nil))

;; Per-window surface state: {handle -> {:render-target :surface :width :height}}
(defonce ^:private surfaces (atom {}))

(defn- ensure-context!
  "Create DirectContext if it doesn't exist."
  []
  (when-not @gl-ctx
    (reset! gl-ctx (DirectContext/makeGL))))

(defn- ensure-surface!
  "Create or recreate surface at the given physical dimensions for a window."
  [handle width height]
  (let [entry (get @surfaces handle)
        needs-recreate? (or (nil? (:surface entry))
                            (not= width (:width entry))
                            (not= height (:height entry)))]
    (when needs-recreate?
      ;; Cleanup old resources
      (when-let [s (:surface entry)] (.close s))
      (when-let [rt (:render-target entry)] (.close rt))

      ;; Create new at current size
      ;; makeGL(width, height, sampleCnt, stencilBits, fbId, format)
      ;; stencil=8, samples=0, fbId=0 (default framebuffer), format=GL_RGBA8
      (let [rt (BackendRenderTarget/makeGL width height 0 8 0 GL_RGBA8)
            sf (Surface/wrapBackendRenderTarget
                 @gl-ctx rt SurfaceOrigin/BOTTOM_LEFT
                 ColorType/RGBA_8888 nil nil)]
        (swap! surfaces assoc handle
               {:render-target rt
                :surface sf
                :width width
                :height height})))))

(defn frame!
  "Prepare a frame for rendering at the given physical dimensions.
   Returns {:surface surface :canvas canvas :flush-fn fn}."
  [handle physical-width physical-height]
  (ensure-context!)
  (ensure-surface! handle physical-width physical-height)
  (let [{:keys [surface]} (get @surfaces handle)
        ctx @gl-ctx]
    {:surface  surface
     :canvas   (.getCanvas surface)
     :flush-fn #(.flushAndSubmit ctx surface)}))

(defn resize!
  "Called on window resize. Invalidates surface for recreation on next frame."
  [handle]
  (let [{:keys [surface render-target]} (get @surfaces handle)]
    (when surface (.close surface))
    (when render-target (.close render-target))
    (swap! surfaces update handle assoc :surface nil :render-target nil)))

(defn cleanup!
  "Release GPU resources for a window. Closes shared context when last window removed."
  [handle]
  (let [{:keys [surface render-target]} (get @surfaces handle)]
    (when surface (.close surface))
    (when render-target (.close render-target))
    (swap! surfaces dissoc handle))
  ;; Close shared context when no more windows
  (when (empty? @surfaces)
    (when-let [ctx @gl-ctx]
      (.close ctx)
      (reset! gl-ctx nil))))

(defn context
  "Get the DirectContext (for flushing)."
  []
  @gl-ctx)

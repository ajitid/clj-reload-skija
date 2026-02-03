(ns lib.window.layer-metal
  "Skija Metal surface management for macOS.
   Handles DirectContext, BackendRenderTarget, and Surface lifecycle
   using Metal backend via SDL3's Metal view.
   Shared device/queue/context, per-window metal-view/metal-layer."
  (:require [lib.window.metal :as metal])
  (:import [io.github.humbleui.skija DirectContext BackendRenderTarget
            Surface SurfaceOrigin ColorType]
           [org.lwjgl.sdl SDLMetal]))

;; ============================================================
;; State: shared globals + per-window registry
;; ============================================================

;; Shared GPU resources (one per application)
(defonce ^:private shared (atom {:device nil :queue nil :context nil}))

;; Per-window resources: {handle -> {:metal-view :metal-layer :width :height}}
(defonce ^:private windows (atom {}))

;; ============================================================
;; Initialization
;; ============================================================

(defn init!
  "Initialize Metal layer for the given SDL window.
   Must be called after window creation with SDL_WINDOW_METAL flag.
   Creates shared device/queue/context on first call.
   Returns true on success."
  [window-handle]
  (when (metal/available?)
    ;; Initialize shared resources on first call
    (when-not (:device @shared)
      (let [{:keys [device queue device-name]} (metal/create-metal-context)]
        (when (and device queue)
          (println "[layer-metal] Using device:" device-name)
          (let [skija-ctx (DirectContext/makeMetal device queue)]
            (when skija-ctx
              (reset! shared {:device device :queue queue :context skija-ctx}))))))
    ;; Initialize per-window resources
    (let [{:keys [device]} @shared]
      (when device
        (let [metal-view (SDLMetal/SDL_Metal_CreateView window-handle)]
          (when (and metal-view (pos? metal-view))
            (let [metal-layer (SDLMetal/SDL_Metal_GetLayer metal-view)]
              (when (and metal-layer (pos? metal-layer))
                ;; Configure the layer
                (metal/set-layer-device! metal-layer device)
                (metal/set-layer-pixel-format! metal-layer metal/MTLPixelFormatBGRA8Unorm)
                (metal/set-layer-framebuffer-only! metal-layer false)
                (swap! windows assoc window-handle
                       {:metal-view metal-view
                        :metal-layer metal-layer
                        :width 0
                        :height 0})
                true))))))))

;; ============================================================
;; Frame Rendering
;; ============================================================

(defn frame!
  "Prepare a frame for rendering at the given physical dimensions.
   Returns a map with:
     :surface    - Skia Surface for drawing
     :canvas     - Canvas from the surface
     :drawable   - CAMetalDrawable pointer (for capture)
     :texture    - MTLTexture pointer (for capture)
     :flush-fn   - Call after drawing to flush Skia commands
     :present-fn - Call to display the frame (returns command buffer for sync)

   Returns nil if no drawable is available.

   The caller should:
   1. Draw to the canvas
   2. Call flush-fn to flush Skia commands
   3. Optionally capture the texture (for screenshots/video)
   4. Call present-fn to display the frame"
  [handle physical-width physical-height]
  (let [{:keys [context]} @shared
        {:keys [metal-layer]} (get @windows handle)
        {:keys [queue]} @shared]
    (when (and context metal-layer (pos? metal-layer))
      ;; Update cached dimensions
      (swap! windows assoc-in [handle :width] physical-width)
      (swap! windows assoc-in [handle :height] physical-height)

      ;; Get next drawable from the layer
      (let [drawable (metal/get-next-drawable metal-layer)]
        (when (and drawable (pos? drawable))
          ;; Get the texture from the drawable
          (let [texture (metal/get-drawable-texture drawable)]
            (when (and texture (pos? texture))
              ;; Create BackendRenderTarget from Metal texture
              (let [render-target (BackendRenderTarget/makeMetal
                                    physical-width
                                    physical-height
                                    texture)
                    ;; Wrap in Skia Surface
                    surface (Surface/wrapBackendRenderTarget
                              context
                              render-target
                              SurfaceOrigin/TOP_LEFT
                              ColorType/BGRA_8888
                              nil   ; colorSpace
                              nil)] ; surfaceProps
                (when surface
                  {:surface    surface
                   :canvas     (.getCanvas surface)
                   :drawable   drawable
                   :texture    texture
                   :flush-fn   (fn []
                                 (.flushAndSubmit context surface)
                                 (.close surface)
                                 (.close render-target))
                   :present-fn (fn []
                                 (let [cmd-buffer (metal/create-command-buffer queue)]
                                   (when cmd-buffer
                                     (metal/present-drawable-with-command-buffer! cmd-buffer drawable)
                                     (metal/commit-command-buffer! cmd-buffer)
                                     cmd-buffer)))})))))))))

;; ============================================================
;; Resize Handling
;; ============================================================

(defn resize!
  "Called on window resize. The Metal layer auto-sizes from the view,
   so we just need to update our cached dimensions."
  [_handle]
  ;; Metal layer auto-sizes from SDL Metal view
  ;; No explicit action needed here
  nil)

;; ============================================================
;; Cleanup
;; ============================================================

(defn cleanup!
  "Release Metal/Skija resources for a window.
   Releases shared resources when last window is removed."
  [handle]
  (let [{:keys [metal-view]} (get @windows handle)]
    ;; Destroy SDL Metal view for this window
    (when (and metal-view (pos? metal-view))
      (SDLMetal/SDL_Metal_DestroyView metal-view))
    (swap! windows dissoc handle))
  ;; Release shared resources when no more windows
  (when (empty? @windows)
    (let [{:keys [context device queue]} @shared]
      (when context (.close context))
      (when device (metal/release! device))
      (when queue (metal/release! queue))
      (reset! shared {:device nil :queue nil :context nil}))))

;; ============================================================
;; Accessors
;; ============================================================

(defn context
  "Get the DirectContext (for external use like Image.adoptFrom)."
  []
  (:context @shared))

(defn device
  "Get the Metal device pointer."
  []
  (:device @shared))

(defn queue
  "Get the Metal command queue pointer."
  []
  (:queue @shared))

(defn metal-layer
  "Get the CAMetalLayer pointer for a window."
  [handle]
  (get-in @windows [handle :metal-layer]))

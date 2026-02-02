(ns lib.window.layer-metal
  "Skija Metal surface management for macOS.
   Handles DirectContext, BackendRenderTarget, and Surface lifecycle
   using Metal backend via SDL3's Metal view."
  (:require [lib.window.metal :as metal])
  (:import [io.github.humbleui.skija DirectContext BackendRenderTarget
            Surface SurfaceOrigin ColorType]
           [org.lwjgl.sdl SDLMetal]))

;; ============================================================
;; State
;; ============================================================

(defonce ^:private state
  (atom {:metal-view    nil     ; SDL_MetalView handle
         :metal-layer   nil     ; CAMetalLayer pointer
         :device        nil     ; MTLDevice pointer
         :queue         nil     ; MTLCommandQueue pointer
         :context       nil     ; Skija DirectContext
         :width         0
         :height        0}))

;; ============================================================
;; Initialization
;; ============================================================

(defn init!
  "Initialize Metal layer for the given SDL window.
   Must be called after window creation with SDL_WINDOW_METAL flag.
   Returns true on success."
  [window-handle]
  (when (metal/available?)
    ;; Create Metal device and queue
    (let [{:keys [device queue device-name]} (metal/create-metal-context)]
      (when (and device queue)
        (println "[layer-metal] Using device:" device-name)

        ;; Create SDL Metal view
        (let [metal-view (SDLMetal/SDL_Metal_CreateView window-handle)]
          (when (and metal-view (pos? metal-view))
            ;; Get the CAMetalLayer from the view
            (let [metal-layer (SDLMetal/SDL_Metal_GetLayer metal-view)]
              (when (and metal-layer (pos? metal-layer))
                ;; Configure the layer
                (metal/set-layer-device! metal-layer device)
                (metal/set-layer-pixel-format! metal-layer metal/MTLPixelFormatBGRA8Unorm)
                (metal/set-layer-framebuffer-only! metal-layer false) ; Allow reading for Skia

                ;; Create Skija DirectContext with Metal
                (let [skija-ctx (DirectContext/makeMetal device queue)]
                  (when skija-ctx
                    (swap! state assoc
                           :metal-view metal-view
                           :metal-layer metal-layer
                           :device device
                           :queue queue
                           :context skija-ctx)
                    true))))))))))

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
  [physical-width physical-height]
  (let [{:keys [context metal-layer queue]} @state]
    (when (and context metal-layer (pos? metal-layer))
      ;; Update cached dimensions
      (swap! state assoc :width physical-width :height physical-height)

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
                   :drawable   drawable   ; Exposed for capture
                   :texture    texture    ; Exposed for capture
                   :flush-fn   (fn []
                                 ;; Flush Skia to Metal
                                 (.flushAndSubmit context surface)
                                 ;; Close the surface and render target
                                 ;; (they're only valid for this frame)
                                 (.close surface)
                                 (.close render-target))
                   :present-fn (fn []
                                 ;; Create command buffer for presentation
                                 (let [cmd-buffer (metal/create-command-buffer queue)]
                                   (when cmd-buffer
                                     ;; Schedule drawable presentation
                                     (metal/present-drawable-with-command-buffer! cmd-buffer drawable)
                                     ;; Commit the command buffer
                                     (metal/commit-command-buffer! cmd-buffer)
                                     ;; Return command buffer for caller to wait on if needed
                                     cmd-buffer)))})))))))))

;; ============================================================
;; Resize Handling
;; ============================================================

(defn resize!
  "Called on window resize. The Metal layer auto-sizes from the view,
   so we just need to update our cached dimensions."
  []
  ;; Metal layer auto-sizes from SDL Metal view
  ;; No explicit action needed here
  nil)

;; ============================================================
;; Cleanup
;; ============================================================

(defn cleanup!
  "Release all Metal/Skija resources."
  []
  (let [{:keys [context metal-view device queue]} @state]
    ;; Close Skija context
    (when context
      (.close context))
    ;; Destroy SDL Metal view
    (when (and metal-view (pos? metal-view))
      (SDLMetal/SDL_Metal_DestroyView metal-view))
    ;; Note: device and queue are owned by Metal framework,
    ;; we should release them but they'll be cleaned up with the app
    (when device (metal/release! device))
    (when queue (metal/release! queue))
    (reset! state {:metal-view nil
                   :metal-layer nil
                   :device nil
                   :queue nil
                   :context nil
                   :width 0
                   :height 0})))

;; ============================================================
;; Accessors
;; ============================================================

(defn context
  "Get the DirectContext (for external use like Image.adoptFrom)."
  []
  (:context @state))

(defn device
  "Get the Metal device pointer."
  []
  (:device @state))

(defn queue
  "Get the Metal command queue pointer."
  []
  (:queue @state))

(defn metal-layer
  "Get the CAMetalLayer pointer."
  []
  (:metal-layer @state))

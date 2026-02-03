(ns lib.window.core
  "Public window API - JWM-like interface over SDL3.
   Provides create-window, set-event-handler!, request-frame!, run-loop!, close!
   Supports both OpenGL and Metal (macOS) backends.
   Supports multi-window via run-multi!."
  (:refer-clojure :exclude [run!])
  (:require [lib.window.internal :as sdl]
            [lib.window.layer :as layer]
            [lib.window.layer-metal :as layer-metal]
            [lib.window.events :as e])
  (:import [org.lwjgl.sdl SDL_Event SDLVideo]
           [org.lwjgl.opengl GL11]
           [lib.window.events EventClose EventResize EventFrameSkija EventExposed]))

;; Shared flag for capture module - set by lib.window.capture when active
;; This allows zero overhead when capture is not in use
(defonce capture-active? (atom false))

;; Window record holds SDL handles (long pointers) and state atoms
;; backend is :opengl or :metal
;; window-id is SDL's uint32 window identifier for event routing
(defrecord Window [^long handle ^long gl-context window-id event-handler
                   frame-requested? running?
                   width height scale backend])

(defn- detect-backend
  "Detect the best backend for the current platform.
   Prefers Metal on macOS, OpenGL elsewhere."
  []
  (let [os-name (System/getProperty "os.name")]
    (if (and os-name (.toLowerCase os-name) (.contains (.toLowerCase os-name) "mac"))
      :metal
      :opengl)))

(defn create-window
  "Create a window with the given options.
   Options:
     :title          - Window title (default \"Window\")
     :width          - Window width (default 800)
     :height         - Window height (default 600)
     :x              - Window x position (default: centered)
     :y              - Window y position (default: centered)
     :resizable?     - Allow resizing (default true)
     :high-dpi?      - Enable high DPI (default true)
     :always-on-top? - Keep window above others (default false)
     :display        - Display index (0-based) to open on (default: primary)
     :backend        - Graphics backend :opengl, :metal, or :auto (default)
                       :auto uses Metal on macOS, OpenGL elsewhere
     :shared-gl-context - For secondary OpenGL windows, share this GL context
                          instead of creating a new one
   Position precedence: explicit :x/:y > :display (centered) > default
   Returns a Window record."
  [{:keys [title width height x y resizable? high-dpi? always-on-top? display backend
           shared-gl-context]
    :or {title "Window" width 800 height 600 resizable? true high-dpi? true backend :auto}
    :as opts}]
  ;; Resolve :auto to actual backend
  (let [effective-backend (if (= backend :auto)
                            (detect-backend)
                            backend)
        opts-with-backend (assoc opts :backend effective-backend)]
    ;; Initialize SDL
    (sdl/init-sdl!)

    ;; Backend-specific setup
    (case effective-backend
      :metal
      (let [;; Create Metal window (no GL attributes needed)
            handle (sdl/create-window! opts-with-backend)
            scale (sdl/get-window-scale handle)
            [w h] (sdl/get-window-size handle)
            wid (sdl/get-window-id handle)]
        ;; Initialize Metal layer (shared device/queue created on first call)
        (when-not (layer-metal/init! handle)
          (throw (ex-info "Failed to initialize Metal layer" {:backend :metal})))
        (map->Window
         {:handle           handle
          :gl-context       0      ; No GL context for Metal
          :window-id        wid
          :event-handler    (atom nil)
          :frame-requested? (atom false)
          :running?         (atom false)
          :width            (atom w)
          :height           (atom h)
          :scale            (atom scale)
          :backend          :metal}))

      ;; Default: OpenGL
      (do
        (when-not shared-gl-context
          (sdl/set-gl-attributes!))
        (let [handle (sdl/create-window! (assoc opts-with-backend :backend :opengl))
              gl-ctx (if shared-gl-context
                       (do
                         (sdl/make-gl-current! handle shared-gl-context)
                         shared-gl-context)
                       (sdl/create-gl-context! handle))
              scale (sdl/get-window-scale handle)
              [w h] (sdl/get-window-size handle)
              wid (sdl/get-window-id handle)]
          (map->Window
           {:handle           handle
            :gl-context       gl-ctx
            :window-id        wid
            :event-handler    (atom nil)
            :frame-requested? (atom false)
            :running?         (atom false)
            :width            (atom w)
            :height           (atom h)
            :scale            (atom scale)
            :backend          :opengl}))))))

(defn set-event-handler!
  "Set the event handler function.
   Handler receives event records from lib.window.events."
  [^Window window handler-fn]
  (reset! (:event-handler window) handler-fn))

(defn request-frame!
  "Request a frame to be rendered.
   Handler will receive EventFrameSkija on next loop iteration."
  [^Window window]
  (reset! (:frame-requested? window) true))

(defn close!
  "Signal the window to close."
  [^Window window]
  (reset! (:running? window) false))

(defn- dispatch-event!
  "Dispatch an event to the handler."
  [window event]
  (when-let [handler @(:event-handler window)]
    (try
      (handler event)
      (catch Exception ex
        (println "Event handler error:" (.getMessage ex))))))

(defn- render-frame-opengl!
  "Render a frame using the OpenGL/Skija layer."
  [^Window window]
  (let [handle (:handle window)
        gl-ctx (:gl-context window)]
    ;; Make GL context current for this window (required for multi-window)
    (when (and gl-ctx (pos? gl-ctx))
      (sdl/make-gl-current! handle gl-ctx))
    (let [[pw ph] (sdl/get-window-size-in-pixels handle)
          {:keys [surface canvas flush-fn]} (layer/frame! handle pw ph)]
      ;; Update viewport to match new size
      (GL11/glViewport 0 0 pw ph)
      ;; Clear with white (or could be transparent)
      (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_STENCIL_BUFFER_BIT))
      ;; Dispatch frame event to handler
      ;; Handler returns truthy if it drew, falsy to skip swap (preserve previous frame)
      (when (dispatch-event! window (e/->EventFrameSkija surface canvas))
        ;; Flush Skija and swap buffers only if handler drew something
        (flush-fn)
        ;; Process frame capture (PBO async read) - only if capture is active
        (when @capture-active?
          (when-let [capture-fn (resolve 'lib.window.capture/process-frame!)]
            (capture-fn pw ph @(:scale window) :opengl)))
        (sdl/swap-buffers! handle)))))

(defn- render-frame-metal!
  "Render a frame using the Metal/Skija layer.
   Integrates with capture module for screenshots/video recording:
   1. Flush Skia to Metal
   2. Capture texture (while still valid, before present)
   3. Present drawable (texture becomes invalid after)
   4. Process previously captured frame"
  [^Window window]
  (let [handle (:handle window)
        [pw ph] (sdl/get-window-size-in-pixels handle)]
    ;; Get frame resources from Metal layer
    (when-let [{:keys [surface canvas texture flush-fn present-fn]} (layer-metal/frame! handle pw ph)]
      ;; Dispatch frame event to handler
      (when (dispatch-event! window (e/->EventFrameSkija surface canvas))
        ;; Flush Skija commands to Metal
        (flush-fn)

        ;; CAPTURE: Issue texture read BEFORE present (texture still valid)
        ;; This captures the current frame's texture into a buffer
        (when @capture-active?
          (when-let [capture-fn (resolve 'lib.window.capture/start-async-capture-metal!)]
            ;; Note: We pass nil for cmd-buffer since Skia's flushAndSubmit
            ;; already waits for GPU completion internally
            (capture-fn texture nil pw ph)))

        ;; Present the drawable (texture becomes invalid after this)
        (let [cmd-buffer (present-fn)]
          ;; PROCESS: Handle previously captured frame's pixels
          ;; This runs AFTER present so it doesn't block the current frame
          (when @capture-active?
            (when-let [process-fn (resolve 'lib.window.capture/process-frame!)]
              (process-fn pw ph @(:scale window) :metal))))))))

(defn- render-frame!
  "Render a frame using the appropriate backend.
   Handler should return truthy if it drew content, falsy to skip buffer swap
   (preserves previous frame - useful for hot-reload without flicker)."
  [^Window window]
  (case (:backend window)
    :metal (render-frame-metal! window)
    :opengl (render-frame-opengl! window)
    ;; Fallback to OpenGL
    (render-frame-opengl! window)))

(defn run!
  "Run the event loop. Blocks until window closes."
  [^Window window]
  (reset! (:running? window) true)
  (let [handle (:handle window)
        event (SDL_Event/malloc)
        ;; Track last rendered size to avoid redundant renders
        last-render-size (atom [0 0])
        ;; Set up live resize rendering callback
        _ (sdl/set-resize-render-fn!
           (fn []
             ;; Get current pixel size
             (let [[pw ph] (sdl/get-window-size-in-pixels handle)]
               ;; Only render if size actually changed
               (when (not= @last-render-size [pw ph])
                 (reset! last-render-size [pw ph])
                 ;; Update logical dimensions and dispatch resize event
                 (let [[w h] (sdl/get-window-size handle)
                       scale (sdl/get-window-scale handle)]
                   (reset! (:width window) w)
                   (reset! (:height window) h)
                   (reset! (:scale window) scale)
                   ;; Dispatch resize event so app state gets updated
                   (dispatch-event! window (e/->EventResize w h scale)))
                 ;; Render at new size
                 (render-frame! window)))))
        ;; Add event watcher for live resize
        watcher (sdl/add-event-watcher!)]
    (try
      (while @(:running? window)
        ;; Poll events
        (let [events (sdl/poll-events! event handle
                                       @(:width window)
                                       @(:height window))]
          (doseq [ev events]
            (cond
              ;; Close event - dispatch to handler (handler decides whether to close).
              ;; Handler can veto close by returning ::veto (a namespaced keyword sentinel).
              ;;
              ;; SDL can send both SDL_QUIT and WINDOW_CLOSE in the same poll batch.
              ;; The first dispatch calls do-sys-cleanup! which sets running? to false,
              ;; so the guard prevents dispatching a second close on an already-closed window.
              (instance? EventClose ev)
              (when @(:running? window)
                (dispatch-event! window ev))

              ;; Resize event - update dimensions and invalidate surface
              (instance? EventResize ev)
              (do
                (reset! (:width window) (:width ev))
                (reset! (:height window) (:height ev))
                (reset! (:scale window) (:scale ev))
                ;; Backend-specific resize handling
                (case (:backend window)
                  :metal (layer-metal/resize! handle)
                  :opengl (layer/resize! handle)
                  (layer/resize! handle))
                (dispatch-event! window ev))

              ;; Exposed event - handled by event watcher
              (instance? EventExposed ev)
              nil

              ;; Other events - just dispatch
              :else
              (dispatch-event! window ev))))

        ;; Render frame if requested and still running
        (when (and @(:running? window) @(:frame-requested? window))
          (reset! (:frame-requested? window) false)
          (render-frame! window)))

      (finally
        (sdl/remove-event-watcher! watcher)
        (sdl/set-resize-render-fn! nil)
        (.free event)
        ;; Cleanup capture resources (only if namespace was loaded)
        (when-let [cleanup-fn (resolve 'lib.window.capture/cleanup!)]
          (cleanup-fn))
        ;; Cleanup audio resources (only if namespace was loaded)
        (when-let [audio-cleanup-fn (resolve 'lib.audio.core/cleanup!)]
          (audio-cleanup-fn))
        ;; Backend-specific cleanup
        (case (:backend window)
          :metal (layer-metal/cleanup! handle)
          :opengl (layer/cleanup! handle)
          (layer/cleanup! handle))
        (sdl/cleanup! (:gl-context window) handle)))))

;; ============================================================
;; Multi-window event loop
;; ============================================================

(defn run-multi!
  "Run the event loop for multiple windows. Blocks until primary window closes.
   primary: the main Window record (closing it exits the loop).
   secondary-windows: seq of additional Window records."
  [primary secondary-windows]
  (let [all-windows (vec (cons primary secondary-windows))]
    ;; Set all windows as running
    (doseq [w all-windows]
      (reset! (:running? w) true))
    (let [primary-handle (:handle primary)
          ;; Build window-id -> Window lookup
          windows-by-wid (into {} (map (fn [w] [(:window-id w) w]) all-windows))
          ;; Build poll info from current window state
          poll-info-fn (fn []
                         (into {}
                           (map (fn [w]
                                  [(:window-id w)
                                   {:handle (:handle w)
                                    :width @(:width w)
                                    :height @(:height w)}])
                                all-windows)))
          event (SDL_Event/malloc)
          ;; Set up live resize rendering callback (renders primary window)
          last-render-size (atom [0 0])
          _ (sdl/set-resize-render-fn!
             (fn []
               (let [[pw ph] (sdl/get-window-size-in-pixels primary-handle)]
                 (when (not= @last-render-size [pw ph])
                   (reset! last-render-size [pw ph])
                   (let [[w h] (sdl/get-window-size primary-handle)
                         s (sdl/get-window-scale primary-handle)]
                     (reset! (:width primary) w)
                     (reset! (:height primary) h)
                     (reset! (:scale primary) s)
                     (dispatch-event! primary (e/->EventResize w h s)))
                   (render-frame! primary)))))
          watcher (sdl/add-event-watcher!)]
      (try
        (while @(:running? primary)
          ;; Poll events for all windows
          (let [tagged-events (sdl/poll-events-multi! event (poll-info-fn))]
            (doseq [{:keys [window-id] ev :event} tagged-events]
              (if (= window-id :all)
                ;; Global event (QUIT) - dispatch to primary
                (when @(:running? primary)
                  (dispatch-event! primary ev))
                ;; Window-specific event
                (when-let [w (get windows-by-wid window-id)]
                  (cond
                    ;; Close event
                    (instance? EventClose ev)
                    (if (= w primary)
                      (when @(:running? primary)
                        (dispatch-event! primary ev))
                      ;; Secondary window close - dispatch to its handler
                      (dispatch-event! w ev))

                    ;; Resize event - update dimensions and invalidate surface
                    (instance? EventResize ev)
                    (let [h (:handle w)]
                      (reset! (:width w) (:width ev))
                      (reset! (:height w) (:height ev))
                      (reset! (:scale w) (:scale ev))
                      (case (:backend w)
                        :metal (layer-metal/resize! h)
                        :opengl (layer/resize! h)
                        (layer/resize! h))
                      (dispatch-event! w ev))

                    ;; Exposed event - handled by event watcher
                    (instance? EventExposed ev)
                    nil

                    ;; Other events - just dispatch
                    :else
                    (dispatch-event! w ev))))))

          ;; Render frames for all windows that need it
          (doseq [w all-windows]
            (when (and @(:running? w) @(:frame-requested? w))
              (reset! (:frame-requested? w) false)
              (render-frame! w))))

        (finally
          (sdl/remove-event-watcher! watcher)
          (sdl/set-resize-render-fn! nil)
          (.free event)
          ;; Cleanup capture resources
          (when-let [cleanup-fn (resolve 'lib.window.capture/cleanup!)]
            (cleanup-fn))
          ;; Cleanup audio resources
          (when-let [audio-cleanup-fn (resolve 'lib.audio.core/cleanup!)]
            (audio-cleanup-fn))
          ;; Cleanup layer state for all windows
          (doseq [w all-windows]
            (case (:backend w)
              :metal (layer-metal/cleanup! (:handle w))
              :opengl (layer/cleanup! (:handle w))
              (layer/cleanup! (:handle w))))
          ;; Destroy secondary windows first
          (doseq [w secondary-windows]
            (sdl/destroy-window! (:handle w)))
          ;; Cleanup primary window and SDL
          (sdl/cleanup! (:gl-context primary) primary-handle))))))

;; ============================================================
;; Window property setters
;; ============================================================

(defn set-size!
  "Set the window size in logical pixels."
  [^Window window width height]
  (sdl/set-window-size! (:handle window) width height)
  (reset! (:width window) width)
  (reset! (:height window) height))

(defn set-position!
  "Set the window position in screen coordinates."
  [^Window window x y]
  (sdl/set-window-position! (:handle window) x y))

(defn set-resizable!
  "Enable or disable window resizing."
  [^Window window resizable?]
  (sdl/set-window-resizable! (:handle window) resizable?))

(defn set-bordered!
  "Enable or disable window border/decorations."
  [^Window window bordered?]
  (sdl/set-window-bordered! (:handle window) bordered?))

(defn set-always-on-top!
  "Enable or disable always-on-top."
  [^Window window always-on-top?]
  (sdl/set-window-always-on-top! (:handle window) always-on-top?))

(defn set-fullscreen!
  "Enable or disable fullscreen mode."
  [^Window window fullscreen?]
  (sdl/set-window-fullscreen! (:handle window) fullscreen?))

(defn set-minimum-size!
  "Set the minimum window size. Pass 0,0 to remove constraint."
  [^Window window width height]
  (sdl/set-window-minimum-size! (:handle window) width height))

(defn set-maximum-size!
  "Set the maximum window size. Pass 0,0 to remove constraint."
  [^Window window width height]
  (sdl/set-window-maximum-size! (:handle window) width height))

(defn set-opacity!
  "Set window opacity (0.0 = transparent, 1.0 = opaque)."
  [^Window window opacity]
  (sdl/set-window-opacity! (:handle window) opacity))

(defn raise!
  "Raise a window to the front and give it input focus."
  [^Window window]
  (sdl/raise-window! (:handle window)))

(defn show!
  "Show a hidden window."
  [^Window window]
  (sdl/show-window! (:handle window)))

(defn hide!
  "Hide a window without destroying it."
  [^Window window]
  (sdl/hide-window! (:handle window)))

;; ============================================================
;; Window property getters
;; ============================================================

(defn get-scale
  "Get the current display scale factor."
  [^Window window]
  @(:scale window))

(defn get-size
  "Get the current window size in logical pixels.
   Returns [width height]."
  [^Window window]
  [@(:width window) @(:height window)])

(defn get-backend
  "Get the graphics backend being used (:metal or :opengl)."
  [^Window window]
  (:backend window))

(defn get-direct-context
  "Get the Skija DirectContext for the current backend.
   Useful for creating textures, images, etc."
  [^Window window]
  (case (:backend window)
    :metal (layer-metal/context)
    :opengl (layer/context)
    (layer/context)))

(defn get-metal-device
  "Get the Metal device pointer (macOS only).
   Returns nil if not using Metal backend."
  [^Window window]
  (when (= :metal (:backend window))
    (layer-metal/device)))

(defn get-metal-queue
  "Get the Metal command queue pointer (macOS only).
   Returns nil if not using Metal backend."
  [^Window window]
  (when (= :metal (:backend window))
    (layer-metal/queue)))

;; Display/monitor functions (re-exported from internal)
(defn get-displays
  "Get all available display IDs.
   Returns a vector of display IDs."
  []
  (sdl/get-displays))

(defn get-primary-display
  "Get the primary display ID."
  []
  (sdl/get-primary-display))

(defn get-display-bounds
  "Get the bounds of a display.
   Returns {:x :y :w :h} or nil if failed."
  [display-id]
  (sdl/get-display-bounds display-id))

(defn get-display-name
  "Get the name of a display."
  [display-id]
  (sdl/get-display-name display-id))

(defn get-window-position
  "Get the window's current position in global coordinates.
   Returns [x y]."
  [^Window window]
  (sdl/get-window-position (:handle window)))

(defn get-global-mouse-position
  "Get the mouse position in global screen coordinates.
   Returns [x y]."
  []
  (sdl/get-global-mouse-position))

(defn get-mouse-position
  "Get the mouse position relative to the focused window.
   Returns [x y] in logical pixels."
  []
  (sdl/get-mouse-position))

;; Window title functions (re-exported from internal)
(defn set-window-title!
  "Set the window title."
  [^Window window title]
  (sdl/set-window-title! (:handle window) title))

;; Clipboard functions (re-exported from internal)
(defn set-clipboard-text!
  "Set UTF-8 text to system clipboard.
   Returns true on success, false on failure."
  [^String text]
  (sdl/set-clipboard-text! text))

(defn get-clipboard-text
  "Get UTF-8 text from system clipboard.
   Returns string or nil if no text available."
  []
  (sdl/get-clipboard-text))

(defn has-clipboard-text?
  "Check if clipboard contains text (non-empty).
   Returns boolean."
  []
  (sdl/has-clipboard-text?))

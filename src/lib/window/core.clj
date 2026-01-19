(ns lib.window.core
  "Public window API - JWM-like interface over SDL3.
   Provides create-window, set-event-handler!, request-frame!, run-loop!, close!"
  (:refer-clojure :exclude [run!])
  (:require [lib.window.internal :as sdl]
            [lib.window.layer :as layer]
            [lib.window.events :as e])
  (:import [org.lwjgl.sdl SDL_Event]
           [org.lwjgl.opengl GL11]
           [lib.window.events EventClose EventResize EventFrameSkija EventExposed]))

;; Shared flag for capture module - set by lib.window.capture when active
;; This allows zero overhead when capture is not in use
(defonce capture-active? (atom false))

;; Window record holds SDL handles (long pointers) and state atoms
(defrecord Window [^long handle ^long gl-context event-handler
                   frame-requested? running?
                   width height scale])

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
   Position precedence: explicit :x/:y > :display (centered) > default
   Returns a Window record."
  [{:keys [title width height x y resizable? high-dpi? always-on-top? display]
    :or {title "Window" width 800 height 600 resizable? true high-dpi? true}
    :as opts}]
  ;; Initialize SDL
  (sdl/init-sdl!)
  (sdl/set-gl-attributes!)

  ;; Create window and GL context
  (let [handle (sdl/create-window! opts)
        gl-ctx (sdl/create-gl-context! handle)
        scale (sdl/get-window-scale handle)
        [pw ph] (sdl/get-window-size-in-pixels handle)
        [w h] (sdl/get-window-size handle)]
    (map->Window
      {:handle           handle
       :gl-context       gl-ctx
       :event-handler    (atom nil)
       :frame-requested? (atom false)
       :running?         (atom false)
       :width            (atom w)
       :height           (atom h)
       :scale            (atom scale)})))

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

(defn- render-frame!
  "Render a frame using the Skija layer."
  [^Window window]
  (let [handle (:handle window)
        [pw ph] (sdl/get-window-size-in-pixels handle)
        {:keys [surface canvas flush-fn]} (layer/frame! pw ph)]
    ;; Update viewport to match new size
    (GL11/glViewport 0 0 pw ph)
    ;; Clear with white (or could be transparent)
    (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_STENCIL_BUFFER_BIT))
    ;; Dispatch frame event to handler
    ;; Note: Skija Canvas API uses top-left origin (Y=0 at top, Y increases down)
    ;; Skija internally handles transformation to OpenGL's BOTTOM_LEFT framebuffer
    ;; SkSL shaders receive fragCoord in BOTTOM_LEFT coordinates (Y=0 at bottom)
    (dispatch-event! window (e/->EventFrameSkija surface canvas))
    ;; Flush Skija and swap buffers
    (flush-fn)
    ;; Process frame capture (PBO async read) - only if capture is active
    ;; Zero overhead when not capturing (just an atom deref)
    (when @capture-active?
      (when-let [capture-fn (resolve 'lib.window.capture/process-frame!)]
        (capture-fn pw ph)))
    (sdl/swap-buffers! handle)))

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
              ;; Close event
              (instance? EventClose ev)
              (close! window)

              ;; Resize event - update dimensions and invalidate surface
              (instance? EventResize ev)
              (do
                (reset! (:width window) (:width ev))
                (reset! (:height window) (:height ev))
                (reset! (:scale window) (:scale ev))
                (layer/resize!)
                (dispatch-event! window ev))

              ;; Exposed event - handled by event watcher
              (instance? EventExposed ev)
              nil

              ;; Other events - just dispatch
              :else
              (dispatch-event! window ev))))

        ;; Render frame if requested
        (when @(:frame-requested? window)
          (reset! (:frame-requested? window) false)
          (render-frame! window)))

      (finally
        (sdl/remove-event-watcher! watcher)
        (sdl/set-resize-render-fn! nil)
        (.free event)
        ;; Cleanup capture resources (only if namespace was loaded)
        (when-let [cleanup-fn (resolve 'lib.window.capture/cleanup!)]
          (cleanup-fn))
        (layer/cleanup!)
        (sdl/cleanup! (:gl-context window) handle)))))

(defn get-scale
  "Get the current display scale factor."
  [^Window window]
  @(:scale window))

(defn get-size
  "Get the current window size in logical pixels.
   Returns [width height]."
  [^Window window]
  [@(:width window) @(:height window)])

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

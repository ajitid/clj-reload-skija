(ns lib.window.core
  "Public window API - JWM-like interface over SDL3.
   Provides create-window, set-event-handler!, request-frame!, run-loop!, close!"
  (:refer-clojure :exclude [run!])
  (:require [lib.window.internal :as sdl]
            [lib.window.layer :as layer]
            [lib.window.events :as e])
  (:import [org.lwjgl.sdl SDL_Event]
           [org.lwjgl.opengl GL11]
           [lib.window.events EventClose EventResize EventFrameSkija]))

;; Window record holds SDL handles (long pointers) and state atoms
(defrecord Window [^long handle ^long gl-context event-handler
                   frame-requested? running?
                   width height scale])

(defn create-window
  "Create a window with the given options.
   Returns a Window record."
  [{:keys [title width height resizable? high-dpi?]
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
    ;; Clear with white (or could be transparent)
    (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_STENCIL_BUFFER_BIT))
    ;; Dispatch frame event to handler
    (dispatch-event! window (e/->EventFrameSkija surface canvas))
    ;; Flush Skija and swap buffers
    (flush-fn)
    (sdl/swap-buffers! handle)))

(defn run!
  "Run the event loop. Blocks until window closes."
  [^Window window]
  (reset! (:running? window) true)
  (let [handle (:handle window)
        event (SDL_Event/malloc)]
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

              ;; Other events - just dispatch
              :else
              (dispatch-event! window ev))))

        ;; Render frame if requested
        (when @(:frame-requested? window)
          (reset! (:frame-requested? window) false)
          (render-frame! window)))

      (finally
        (.free event)
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

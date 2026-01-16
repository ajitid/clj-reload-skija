(ns app.core
  "Main application - Love2D style game loop with JWM/Skija.

   Game loop callbacks (hot-reloadable):
   - init - called once at startup
   - tick - called every frame with delta time (dt)
   - draw - called every frame for rendering

   The rendering reads from:
   - app.state (defonce) - sizes persist across reloads
   - app.config (def) - effects change on reload"
  (:require [app.state :as state]
            [app.config :as config]
            [app.controls :as controls])
  (:import [io.github.humbleui.jwm App Window EventWindowCloseRequest EventFrame EventMouseButton EventMouseMove ZOrder]
           [io.github.humbleui.jwm.skija EventFrameSkija LayerGLSkija]
           [io.github.humbleui.skija Canvas Paint PaintMode]
           [java.util.function Consumer]))

;; ============================================================
;; Helpers
;; ============================================================

(defn cfg
  "Get config value with runtime var lookup (survives hot-reload).
   Uses resolve as recommended by clj-reload."
  [var-sym]
  (some-> (resolve var-sym) deref))

(defn config-loaded?
  "Check if app.config namespace is loaded and ready."
  []
  (some? (resolve 'app.config/circle-color)))

;; ============================================================
;; Drawing helpers
;; ============================================================

(defn draw-circle
  "Draw a circle at the given position."
  [^Canvas canvas x y radius]
  (with-open [paint (doto (Paint.)
                      (.setColor (unchecked-int (cfg 'app.config/circle-color)))
                      (.setMode PaintMode/FILL)
                      (.setAntiAlias true))]
    (.drawCircle canvas (float x) (float y) (float radius) paint)))

(defn draw-circle-grid
  "Draw a grid of circles that fills the screen."
  [^Canvas canvas width height]
  (let [nx @state/circles-x
        ny @state/circles-y
        radius (cfg 'app.config/grid-circle-radius)
        ;; Calculate cell size based on window dimensions
        cell-w (/ width nx)
        cell-h (/ height ny)]
    (doseq [row (range ny)
            col (range nx)]
      (let [cx (+ (* col cell-w) (/ cell-w 2))
            cy (+ (* row cell-h) (/ cell-h 2))
            ;; Scale radius to fit in cell (use smaller of cell dimensions)
            fit-radius (min (/ cell-w 2.2) (/ cell-h 2.2) radius)]
        (draw-circle canvas cx cy fit-radius)))))

;; ============================================================
;; Love2D-style callbacks (hot-reloadable!)
;; ============================================================

(defn init
  "Called once when the game starts.
   Initialize your game state here."
  []
  (println "Game loaded!"))

(defn tick
  "Called every frame with delta time in seconds.
   Update your game state here."
  [dt]
  nil)

(defn draw
  "Called every frame for rendering.
   Draw your game here."
  [^Canvas canvas width height]
  ;; Clear background
  (.clear canvas (unchecked-int (or (cfg 'app.config/grid-bg-color) 0xFF222222)))

  ;; Only render when config is loaded
  (when (config-loaded?)
    ;; Draw the circle grid (auto-adjusts to window size)
    (draw-circle-grid canvas width height)

    ;; Draw control panel on top
    (controls/draw-panel canvas)))

;; ============================================================
;; Game loop infrastructure
;; ============================================================

(defn create-event-listener
  "Create an event listener for the window."
  [^Window window layer]
  (let [last-time (atom (System/nanoTime))]
    (reify Consumer
      (accept [_ event]
        (condp instance? event
          EventWindowCloseRequest
          (do
            (reset! state/running? false)
            (.close window)
            (App/terminate))

          EventMouseButton
          (let [^EventMouseButton me event]
            (if (.isPressed me)
              (controls/handle-mouse-press me)
              (controls/handle-mouse-release me)))

          EventMouseMove
          (controls/handle-mouse-move event)

          EventFrameSkija
          (let [^EventFrameSkija frame-event event
                surface (.getSurface frame-event)
                canvas (.getCanvas surface)
                w (.getWidth surface)
                h (.getHeight surface)
                ;; Calculate delta time
                now (System/nanoTime)
                dt (/ (- now @last-time) 1e9)]
            (reset! last-time now)
            ;; Love2D-style game loop with error isolation
            ;; Prevents render errors during hot-reload from crashing the app
            (try
              (#'tick dt)
              (#'draw canvas w h)
              (catch Exception e
                ;; Clear to error color so user knows something went wrong
                (.clear canvas (unchecked-int 0xFFFF6B6B))
                (println "Render error:" (.getMessage e))))
            (.requestFrame window))

          EventFrame
          (.requestFrame window)

          nil)))))

(defn start-app
  "Start the application - creates window and begins game loop."
  []
  (when-not @state/running?
    (reset! state/running? true)
    (App/start
     (fn []
       (let [window (App/makeWindow)
             layer (LayerGLSkija.)]
         (reset! state/window window)
         ;; Call init once at startup
         (#'init)
         (doto window
           (.setTitle "Skija Demo - Hot Reload with clj-reload")
           (.setLayer layer)
           (.setEventListener (create-event-listener window layer))
           (.setContentSize 800 600)
           (.setZOrder ZOrder/FLOATING)
           (.setVisible true))
         (.requestFrame window))))))

(defn -main
  "Entry point for running the application."
  [& args]
  (println "Starting Skija demo...")
  (println "Tip: Connect a REPL and use (user/reload) to hot-reload changes!")
  (start-app))

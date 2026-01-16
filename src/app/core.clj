(ns app.core
  "Main application - Love2D style game loop with JWM/Skija.

   Architecture (clj-reload pattern):
   - ALL callbacks use (resolve ...) for dynamic dispatch
   - This allows hot-reloading of ALL namespaces including app.core
   - Only defonce values in app.state persist across reloads

   Game loop callbacks (hot-reloadable):
   - init - called once at startup
   - tick - called every frame with delta time (dt)
   - draw - called every frame for rendering"
  (:require [app.state :as state])
  (:import [io.github.humbleui.jwm App Window EventWindowCloseRequest EventWindowResize EventFrame EventMouseButton EventMouseMove ZOrder]
           [io.github.humbleui.jwm.skija EventFrameSkija LayerGLSkija]
           [io.github.humbleui.skija Canvas Paint PaintMode PaintStrokeCap]
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

(defn recalculate-grid!
  "Recalculate and cache grid positions. Called on resize or grid settings change."
  [width height]
  (let [nx @state/circles-x
        ny @state/circles-y
        radius (or (cfg 'app.config/grid-circle-radius) 100)
        cell-w (/ width nx)
        cell-h (/ height ny)
        positions (for [row (range ny)
                        col (range nx)]
                    (let [cx (+ (* col cell-w) (/ cell-w 2))
                          cy (+ (* row cell-h) (/ cell-h 2))
                          fit-radius (min (/ cell-w 2.2) (/ cell-h 2.2) radius)]
                      {:cx cx :cy cy :radius fit-radius}))]
    (reset! state/grid-positions (vec positions))))

(defn draw-circle-grid
  "Draw a grid of circles using batched points API."
  [^Canvas canvas]
  (let [positions @state/grid-positions]
    (when (seq positions)
      (let [;; All circles same radius - use first one
            radius (:radius (first positions))
            ;; Build float array of x,y pairs
            points (float-array (mapcat (fn [{:keys [cx cy]}] [cx cy]) positions))]
        (with-open [paint (doto (Paint.)
                           (.setColor (unchecked-int (cfg 'app.config/circle-color)))
                           (.setMode PaintMode/STROKE)
                           (.setStrokeWidth (float (* 2 radius)))  ; diameter
                           (.setStrokeCap PaintStrokeCap/ROUND)
                           (.setAntiAlias true))]
          (.drawPoints canvas points paint))))))


;; ============================================================
;; Love2D-style callbacks (hot-reloadable!)
;; ============================================================

(defn init
  "Called once when the game starts.
   Initialize your game state here."
  []
  (println "Game loaded!")
  ;; Initial grid calculation
  (recalculate-grid! @state/window-width @state/window-height))

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
    ;; Draw the circle grid (uses cached positions)
    (draw-circle-grid canvas)

    ;; Draw control panel on top (at top-right)
    ((resolve 'app.controls/draw-panel) canvas width)))

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
              ((resolve 'app.controls/handle-mouse-press) me)
              ((resolve 'app.controls/handle-mouse-release) me)))

          EventMouseMove
          ((resolve 'app.controls/handle-mouse-move) event)

          EventWindowResize
          (let [^io.github.humbleui.jwm.EventWindowResize re event
                scale @state/scale
                w (/ (.getContentWidth re) scale)
                h (/ (.getContentHeight re) scale)]
            (reset! state/window-width w)
            (reset! state/window-height h)
            (when-let [recalc (resolve 'app.core/recalculate-grid!)]
              (recalc w h)))

          EventFrameSkija
          (let [^EventFrameSkija frame-event event
                surface (.getSurface frame-event)
                canvas (.getCanvas surface)
                ;; Get scale factor for HiDPI support
                scale (if-let [screen (.getScreen window)]
                        (.getScale screen)
                        1.0)
                _ (reset! state/scale scale)
                ;; Physical pixels from surface
                pw (.getWidth surface)
                ph (.getHeight surface)
                ;; Logical pixels (what we work with)
                w (/ pw scale)
                h (/ ph scale)
                _ (reset! state/window-width w)
                _ (reset! state/window-height h)
                ;; Calculate delta time
                now (System/nanoTime)
                dt (/ (- now @last-time) 1e9)]
            (reset! last-time now)
            ;; Update FPS (smoothed)
            (when (pos? dt)
              (let [current-fps (/ 1.0 dt)
                    smoothing 0.9]
                (reset! state/fps (+ (* smoothing @state/fps)
                                     (* (- 1.0 smoothing) current-fps)))))
            ;; Love2D-style game loop with error isolation
            ;; Prevents render errors during hot-reload from crashing the app
            (try
              ;; Scale canvas so we work in logical pixels
              (.save canvas)
              (.scale canvas (float scale) (float scale))
              ;; Dynamic dispatch via resolve - survives namespace reload
              (when-let [tick-fn (resolve 'app.core/tick)]
                (tick-fn dt))
              (when-let [draw-fn (resolve 'app.core/draw)]
                (draw-fn canvas w h))
              (.restore canvas)
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
         ;; Call init once at startup (via resolve for consistency)
         (when-let [init-fn (resolve 'app.core/init)]
           (init-fn))
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

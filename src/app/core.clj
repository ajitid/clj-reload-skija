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
            [app.config :as config])
  (:import [io.github.humbleui.jwm App Window EventWindowCloseRequest EventFrame ZOrder]
           [io.github.humbleui.jwm.skija EventFrameSkija LayerGLSkija]
           [io.github.humbleui.skija Canvas Paint PaintMode ImageFilter FilterTileMode]
           [io.github.humbleui.types Rect]
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

(defn draw-circle-with-shadow
  "Draw a circle with drop shadow at the given position."
  [^Canvas canvas x y]
  (let [radius @state/circle-radius
        shadow-filter (ImageFilter/makeDropShadow
                       (float (cfg 'app.config/shadow-dx))
                       (float (cfg 'app.config/shadow-dy))
                       (float (cfg 'app.config/shadow-sigma))
                       (float (cfg 'app.config/shadow-sigma))
                       (unchecked-int (cfg 'app.config/shadow-color)))
        paint (doto (Paint.)
                (.setColor (unchecked-int (cfg 'app.config/circle-color)))
                (.setMode PaintMode/FILL)
                (.setAntiAlias true)
                (.setImageFilter shadow-filter))]
    (.drawCircle canvas (float x) (float y) (float radius) paint)
    (.close paint)
    (.close shadow-filter)))

(defn draw-rect-with-blur
  "Draw a rectangle with blur effect at the given position."
  [^Canvas canvas x y]
  (let [width @state/rect-width
        height @state/rect-height
        blur-filter (ImageFilter/makeBlur
                     (float (cfg 'app.config/blur-sigma-x))
                     (float (cfg 'app.config/blur-sigma-y))
                     FilterTileMode/CLAMP)
        paint (doto (Paint.)
                (.setColor (unchecked-int (cfg 'app.config/rect-color)))
                (.setMode PaintMode/FILL)
                (.setAntiAlias true)
                (.setImageFilter blur-filter))]
    (.drawRect canvas (Rect/makeXYWH (float x) (float y) (float width) (float height)) paint)
    (.close paint)
    (.close blur-filter)))

;; ============================================================
;; Love2D-style callbacks (hot-reloadable!)
;; Renamed to avoid shadowing clojure.core/load and clojure.core/update
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
  ;; Example: you could animate things based on dt
  ;; (swap! state/circle-radius + (* 10 dt))
  nil)

(defn draw
  "Called every frame for rendering.
   Draw your game here."
  [^Canvas canvas width height]
  ;; Clear background to white
  (.clear canvas (unchecked-int 0xFFFFFFFF))

  ;; Only render when config is loaded
  (when (config-loaded?)
    ;; Draw the circle with drop shadow (left side)
    (draw-circle-with-shadow canvas 200 200)

    ;; Draw the rectangle with blur (right side)
    (draw-rect-with-blur canvas 350 150)))

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
            ;; Love2D-style game loop
            (#'tick dt)
            (#'draw canvas w h)
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
           (.setContentSize 600 400)
           (.setZOrder ZOrder/FLOATING)
           (.setVisible true))
         (.requestFrame window))))))

(defn -main
  "Entry point for running the application."
  [& args]
  (println "Starting Skija demo...")
  (println "Tip: Connect a REPL and use (user/reload) to hot-reload changes!")
  (start-app))

(ns app.core
  "Main application - JWM window with Skija rendering.

   The rendering reads from:
   - app.state (defonce) - sizes persist across reloads
   - app.config (def) - effects change on reload"
  (:require [app.state :as state]
            [app.config :as config])
  (:import [io.github.humbleui.jwm App Window EventWindowCloseRequest EventFrame]
           [io.github.humbleui.jwm.skija EventFrameSkija LayerGLSkija]
           [io.github.humbleui.skija Canvas Paint PaintMode ImageFilter FilterTileMode]
           [io.github.humbleui.types Rect]
           [java.util.function Consumer]))

(defn draw-circle-with-shadow
  "Draw a pink circle with drop shadow at the given position."
  [^Canvas canvas x y]
  (let [radius @state/circle-radius
        ;; Create drop shadow filter - reads from config (reloadable)
        shadow-filter (ImageFilter/makeDropShadow
                       (float config/shadow-dx)
                       (float config/shadow-dy)
                       (float config/shadow-sigma)
                       (float config/shadow-sigma)
                       (unchecked-int config/shadow-color))
        ;; Create paint for the circle
        paint (doto (Paint.)
                (.setColor (unchecked-int config/circle-color))
                (.setMode PaintMode/FILL)
                (.setAntiAlias true)
                (.setImageFilter shadow-filter))]
    (.drawCircle canvas (float x) (float y) (float radius) paint)
    (.close paint)
    (.close shadow-filter)))

(defn draw-rect-with-blur
  "Draw a green rectangle with blur effect at the given position."
  [^Canvas canvas x y]
  (let [width @state/rect-width
        height @state/rect-height
        ;; Create blur filter - reads from config (reloadable)
        blur-filter (ImageFilter/makeBlur
                     (float config/blur-sigma-x)
                     (float config/blur-sigma-y)
                     FilterTileMode/CLAMP)
        ;; Create paint for the rectangle
        paint (doto (Paint.)
                (.setColor (unchecked-int config/rect-color))
                (.setMode PaintMode/FILL)
                (.setAntiAlias true)
                (.setImageFilter blur-filter))]
    (.drawRect canvas (Rect/makeXYWH (float x) (float y) (float width) (float height)) paint)
    (.close paint)
    (.close blur-filter)))

(defn paint
  "Main paint function called on each frame."
  [^Canvas canvas width height]
  ;; Clear background to white
  (.clear canvas (unchecked-int 0xFFFFFFFF))

  ;; Draw the pink circle with drop shadow (left side)
  (draw-circle-with-shadow canvas 200 200)

  ;; Draw the green rectangle with blur (right side)
  (draw-rect-with-blur canvas 350 150))

(defn create-event-listener
  "Create an event listener for the window."
  [^Window window layer]
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
              h (.getHeight surface)]
          (paint canvas w h)
          (.requestFrame window))

        EventFrame
        (.requestFrame window)

        nil))))

(defn start-app
  "Start the application - creates window and begins rendering."
  []
  (when-not @state/running?
    (reset! state/running? true)
    (App/start
     (fn []
       (let [window (App/makeWindow)
             layer (LayerGLSkija.)]
         (reset! state/window window)
         (doto window
           (.setTitle "Skija Demo - Hot Reload with clj-reload")
           (.setLayer layer)
           (.setEventListener (create-event-listener window layer))
           (.setContentSize 600 400)
           (.setVisible true))
         (.requestFrame window))))))

(defn -main
  "Entry point for running the application."
  [& args]
  (println "Starting Skija demo...")
  (println "Tip: Connect a REPL and use (user/reload) to hot-reload changes!")
  (start-app))

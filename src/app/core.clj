(ns app.core
  "Main application entry point - SDL3/Skija game loop.

   Architecture (clj-reload pattern):
   - ALL callbacks use (resolve ...) for dynamic dispatch
   - Shell wraps examples and provides debug overlay
   - Examples provide init/tick/draw/cleanup callbacks

   The window infrastructure lives here while the shell manages
   example lifecycle and debug panel."
  (:require [app.shell.state :as shell-state]
            [app.state.system :as sys]
            [clojure.string :as str]
            [lib.error.core :as err]
            [lib.error.overlay :as err-overlay]
            [lib.flex.core :as flex]
            [lib.window.core :as window]
            [lib.window.events :as e]
            [lib.window.macos :as macos])
  (:import [io.github.humbleui.skija Canvas]
           [lib.window.events EventClose EventResize EventMouseButton EventMouseMove EventMouseWheel
            EventKey EventFrameSkija EventFingerDown EventFingerMove EventFingerUp]))

;; ============================================================
;; Window state (local to this module)
;; ============================================================

(defonce window-width (atom 800))
(defonce window-height (atom 600))
(defonce scale (atom 1.0))

;; ============================================================
;; Helpers
;; ============================================================

(defn- macos? []
  (str/includes? (str/lower-case (System/getProperty "os.name" "")) "mac"))

;; ============================================================
;; clj-reload hooks
;; ============================================================

(defn before-ns-unload []
  (reset! sys/reloading? true))

(defn after-ns-reload []
  (reset! sys/reloading? false))

;; ============================================================
;; Error clipboard helper
;; ============================================================

(defn copy-current-error-to-clipboard! []
  (when-let [err (or @sys/last-reload-error @sys/last-runtime-error)]
    (err/copy-to-clipboard! (err/format-error-for-clipboard err))))

;; ============================================================
;; Game loop infrastructure
;; ============================================================

(defn create-event-handler
  "Create an event handler for the window."
  [win]
  (let [last-time (atom (System/nanoTime))
        recording-active? (atom false)]
    (fn [event]
      (cond
        ;; Close event
        (instance? EventClose event)
        (do
          (when @recording-active?
            (when-let [stop-recording! (requiring-resolve 'lib.window.capture/stop-recording!)]
              (stop-recording!))
            (reset! recording-active? false))
          (reset! sys/running? false)
          (window/close! win))

        ;; Frame event
        (instance? EventFrameSkija event)
        (do
          (when (and (macos?) (not @sys/app-activated?))
            (macos/activate-app!)
            (reset! sys/app-activated? true))
          (window/request-frame! win)
          (when-not @sys/reloading?
            (let [{:keys [canvas]} event
                  s @scale
                  w @window-width
                  h @window-height
                  now (System/nanoTime)
                  raw-dt (/ (- now @last-time) 1e9)
                  dt (min raw-dt 0.033)]
              (reset! last-time now)
              ;; Update FPS
              (when (pos? raw-dt)
                (let [current-fps (/ 1.0 raw-dt)
                      smoothing 0.8
                      new-fps (+ (* smoothing @shell-state/fps)
                                 (* (- 1.0 smoothing) current-fps))]
                  (shell-state/fps new-fps)
                  (let [idx (swap! shell-state/fps-history-idx #(mod (inc %) shell-state/fps-history-size))]
                    (aset ^floats shell-state/fps-history idx (float new-fps)))))
              (try
                (.save canvas)
                (.scale canvas (float s) (float s))
                (if-let [reload-err @sys/last-reload-error]
                  (err-overlay/draw-error canvas reload-err)
                  (do
                    (reset! sys/last-runtime-error nil)
                    ;; Delegate to shell
                    (when-let [tick-fn (requiring-resolve 'app.shell.core/tick)]
                      (tick-fn dt))
                    (when-let [draw-fn (requiring-resolve 'app.shell.core/draw)]
                      (draw-fn canvas w h))))
                (catch Exception e
                  (reset! sys/last-runtime-error e)
                  (err-overlay/draw-error canvas (or @sys/last-reload-error e))
                  (println "Render error:" (.getMessage e)))
                (finally
                  (.restore canvas)))
              true)))

        ;; Skip other events during reload
        @sys/reloading?
        nil

        ;; Resize event
        (instance? EventResize event)
        (let [{:keys [width height scale]} event]
          (reset! window-width width)
          (reset! window-height height)
          (reset! app.core/scale scale))

        ;; Mouse button event
        (instance? EventMouseButton event)
        (do
          (when (and (= (:button event) :middle) (:pressed? event)
                     (or @sys/last-reload-error @sys/last-runtime-error))
            (copy-current-error-to-clipboard!))
          (when (= (:button event) :primary)
            (if (:pressed? event)
              (let [scrollbar-handler (requiring-resolve 'lib.gesture.api/handle-scrollbar-mouse-down)
                    tree-atom (requiring-resolve 'app.projects.playground.ball-spring/current-tree)
                    scrollbar-hit? (when (and scrollbar-handler tree-atom)
                                     (scrollbar-handler event {:tree @@tree-atom}))]
                (when scrollbar-hit?
                  (window/request-frame! win))
                (when-not scrollbar-hit?
                  (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-button)]
                    (handle-fn event {:scale @scale
                                      :window-width @window-width}))))
              (do
                (when-let [end-scrollbar (requiring-resolve 'lib.gesture.api/handle-scrollbar-mouse-up)]
                  (end-scrollbar))
                (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-button)]
                  (handle-fn event {:scale @scale
                                    :window-width @window-width}))))))

        ;; Mouse move event
        (instance? EventMouseMove event)
        (let [scrollbar-dragging? (when-let [f (requiring-resolve 'lib.gesture.api/scrollbar-dragging?)]
                                    (f))]
          (if scrollbar-dragging?
            (when-let [handle-scrollbar-move (requiring-resolve 'lib.gesture.api/handle-scrollbar-mouse-move)]
              (when (handle-scrollbar-move event)
                (window/request-frame! win)))
            (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-move)]
              (handle-fn event {:scale @scale
                                :window-width @window-width}))))

        ;; Mouse wheel event
        (instance? EventMouseWheel event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-wheel)]
          (let [tree-atom (requiring-resolve 'app.projects.playground.ball-spring/current-tree)]
            (when (handle-fn event {:scale @scale
                                    :tree (when tree-atom @@tree-atom)})
              (window/request-frame! win))))

        ;; Touch events
        (instance? EventFingerDown event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-finger-down)]
          (handle-fn event {:scale @scale
                            :window-width @window-width}))

        (instance? EventFingerMove event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-finger-move)]
          (handle-fn event {:scale @scale
                            :window-width @window-width}))

        (instance? EventFingerUp event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-finger-up)]
          (handle-fn event {:scale @scale
                            :window-width @window-width}))

        ;; Keyboard event
        (instance? EventKey event)
        (let [{:keys [key pressed? modifiers]} event]
          (when pressed?
            ;; Ctrl+E copies error
            (when (and (= key 0x65) (pos? (bit-and modifiers 0x00C0))
                       (or @sys/last-reload-error @sys/last-runtime-error))
              (copy-current-error-to-clipboard!))

            ;; Ctrl+S captures screenshot
            (when (and (= key 0x73) (pos? (bit-and modifiers 0x00C0)))
              (when-let [screenshot! (requiring-resolve 'lib.window.capture/screenshot!)]
                (let [timestamp (java.time.LocalDateTime/now)
                      formatter (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-mm-ss-SSS")
                      filename (str "screenshot_" (.format timestamp formatter) ".png")]
                  (screenshot! filename :png)
                  (println "[keybind] Screenshot captured:" filename))))

            ;; Ctrl+R toggles recording
            (when (and (= key 0x72) (pos? (bit-and modifiers 0x00C0)))
              (if @recording-active?
                (do
                  (when-let [stop-recording! (requiring-resolve 'lib.window.capture/stop-recording!)]
                    (stop-recording!))
                  (reset! recording-active? false)
                  (window/set-window-title! @sys/window @sys/window-title)
                  (println "[keybind] Recording stopped"))
                (do
                  (when-let [start-recording! (requiring-resolve 'lib.window.capture/start-recording!)]
                    (let [timestamp (java.time.LocalDateTime/now)
                          formatter (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-mm-ss")
                          filename (str "recording_" (.format timestamp formatter) ".mp4")]
                      (start-recording! filename {:fps 60})
                      (reset! recording-active? true)
                      (window/set-window-title! @sys/window
                                               (str @sys/window-title " [Recording]"))
                      (println "[keybind] Recording started:" filename))))))

            ;; Ctrl+` toggles panel
            (when (and (= key 0x60) (pos? (bit-and modifiers 0x00C0)))
              (shell-state/panel-visible? (not @shell-state/panel-visible?)))))

        :else nil))))

(defn- start-app-impl
  "Internal: Start the application."
  [example-key]
  (reset! sys/running? true)
  (let [title (str "Skija Demo - " (name example-key))
        win (window/create-window {:title title
                                   :width 800
                                   :height 600
                                   :resizable? true
                                   :high-dpi? true})]
    (reset! sys/window win)
    (reset! sys/app-activated? false)
    (reset! sys/window-title title)
    (reset! scale (window/get-scale win))
    (let [[w h] (window/get-size win)]
      (reset! window-width w)
      (reset! window-height h))
    ;; Switch to the example (loads ns, calls init)
    (when-let [switch-fn (requiring-resolve 'app.shell.core/switch-example!)]
      (switch-fn example-key))
    ;; Initialize shell
    (when-let [init-fn (requiring-resolve 'app.shell.core/init)]
      (init-fn))
    ;; Set up event loop
    (window/set-event-handler! win (create-event-handler win))
    (window/request-frame! win)
    (window/run! win)))

(defn start-app
  "Start the application with the given example key."
  [example-key]
  (when-not @sys/running?
    (if (macos?)
      (macos/run-on-main-thread-sync! #(start-app-impl example-key))
      (start-app-impl example-key))))

(defn -main
  "Entry point for running the application."
  [& args]
  (println "Starting Skija demo...")
  (println "Tip: Connect a REPL and use (user/reload) to hot-reload changes!")
  (let [example-key (if (first args)
                      (keyword (first args))
                      :playground/ball-spring)]
    (start-app example-key)))

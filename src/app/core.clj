(ns app.core
  "Main application entry point - SDL3/Skija game loop.

   Architecture (clj-reload pattern):
   - ALL callbacks use (resolve ...) for dynamic dispatch
   - Shell wraps examples and provides debug overlay
   - Examples provide init/tick/draw/cleanup callbacks

   Window configuration:
   - Default config lives in app.state.system/default-window-config
   - Examples override via (def window-config {:width 1100 ...})
   - Runtime changes via (patch-window! {:opacity 0.5})
   - Title is composed: base-title + \" - \" + example-name + \" [Recording]\""
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
            EventKey EventTextInput EventFrameSkija EventFingerDown EventFingerMove EventFingerUp]))

;; ============================================================
;; Window state (local to this module — tracks actual window)
;; ============================================================

(defonce window-width (atom 800))
(defonce window-height (atom 600))
(defonce scale (atom 1.0))

;; Panel window dimensions (tracked for gesture coordinate system)
(defonce panel-width (atom 260))
(defonce panel-height (atom 500))

;; Time-based throttle for panel window rendering (~30 FPS)
(defonce panel-last-render-time (atom 0))

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
;; Title composition
;; ============================================================

(defn- compose-display-title
  "Compose the displayed window title from config, example name, and recording state."
  []
  (let [base (:title @sys/window-config)
        example-key @shell-state/current-example
        example-name (when example-key (name example-key))]
    (cond-> base
      example-name (str " - " example-name)
      @sys/recording? (str " [Recording]"))))

(defn update-display-title!
  "Recompute and set the window title from current state."
  []
  (when-let [win @sys/window]
    (window/set-window-title! win (compose-display-title))))

;; ============================================================
;; Window configuration — runtime patching
;; ============================================================

(defn patch-window!
  "Patch the window configuration at runtime.
   Merges patch into current config and applies changes to the SDL window.

   Call with any subset of config keys:
     (patch-window! {:width 1100 :height 830})
     (patch-window! {:title \"My App\"})
     (patch-window! {:opacity 0.8 :always-on-top? true})"
  [patch]
  (when-let [win @sys/window]
    (let [old @sys/window-config
          new-config (swap! sys/window-config merge patch)]
      ;; Size
      (when (or (not= (:width old) (:width new-config))
                (not= (:height old) (:height new-config)))
        (window/set-size! win (:width new-config) (:height new-config))
        (reset! window-width (:width new-config))
        (reset! window-height (:height new-config)))
      ;; Title
      (when (not= (:title old) (:title new-config))
        (update-display-title!))
      ;; Position
      (when (and (:position new-config)
                 (not= (:position old) (:position new-config)))
        (let [[x y] (:position new-config)]
          (window/set-position! win x y)))
      ;; Resizable
      (when (not= (:resizable? old) (:resizable? new-config))
        (window/set-resizable! win (:resizable? new-config)))
      ;; Always on top
      (when (not= (:always-on-top? old) (:always-on-top? new-config))
        (window/set-always-on-top! win (:always-on-top? new-config)))
      ;; Bordered
      (when (not= (:bordered? old) (:bordered? new-config))
        (window/set-bordered! win (:bordered? new-config)))
      ;; Fullscreen
      (when (not= (:fullscreen? old) (:fullscreen? new-config))
        (window/set-fullscreen! win (:fullscreen? new-config)))
      ;; Opacity
      (when (not= (:opacity old) (:opacity new-config))
        (window/set-opacity! win (:opacity new-config)))
      ;; Min size
      (when (not= (:min-size old) (:min-size new-config))
        (if-let [[mw mh] (:min-size new-config)]
          (window/set-minimum-size! win mw mh)
          (window/set-minimum-size! win 0 0)))
      ;; Max size
      (when (not= (:max-size old) (:max-size new-config))
        (if-let [[mw mh] (:max-size new-config)]
          (window/set-maximum-size! win mw mh)
          (window/set-maximum-size! win 0 0)))
      new-config)))

;; ============================================================
;; Game loop infrastructure
;; ============================================================

(defn- call-example-cleanup!
  "Call the active example's cleanup function.
   Returns ::veto if the example wants to prevent window close."
  []
  (when-let [example-fn (requiring-resolve 'app.shell.core/example-fn)]
    (when-let [cleanup-fn (example-fn "cleanup")]
      (cleanup-fn))))

(defn- do-sys-cleanup!
  "System-level cleanup: stop recording, set running? false, close window."
  [win]
  (when @sys/recording?
    (when-let [stop-recording! (requiring-resolve 'lib.window.capture/stop-recording!)]
      (stop-recording!))
    (reset! sys/recording? false))
  (reset! sys/running? false)
  (window/close! win))

;; ============================================================
;; Panel window event handler
;; ============================================================

(defn create-panel-event-handler
  "Create an event handler for the control panel window."
  [panel-win main-win]
  (fn [event]
    (cond
      ;; Close event - hide the panel window instead of closing
      (instance? EventClose event)
      (do (shell-state/panel-visible? false)
          (window/hide! panel-win))

      ;; Frame event - draw the control panel
      (instance? EventFrameSkija event)
      (do
        ;; Only request next frame if panel is visible
        (when @shell-state/panel-visible?
          (window/request-frame! panel-win))
        (when-not @sys/reloading?
          (let [{:keys [canvas]} event
                s @scale
                w @panel-width
                h @panel-height]
            (try
              (.save canvas)
              (.scale canvas (float s) (float s))
              (when-let [draw-fn (requiring-resolve 'app.shell.control-panel/draw-standalone)]
                (draw-fn canvas w h))
              (catch Exception e
                (println "Panel render error:" (.getMessage e)))
              (finally
                (.restore canvas)))
            true)))

      ;; Resize event
      (instance? EventResize event)
      (let [{:keys [width height scale]} event]
        (reset! panel-width width)
        (reset! panel-height height)
        (reset! app.core/scale scale))

      ;; Mouse button event - route to gesture system with :window :panel
      (instance? EventMouseButton event)
      (when (= (:button event) :primary)
        (if (:pressed? event)
          ;; Mouse down: check text field first, then gestures
          (let [hit-field (when-let [ht (requiring-resolve 'app.ui.text-field/hit-test)]
                            (ht (:x event) (:y event)))]
            (if hit-field
              (when-let [md! (requiring-resolve 'app.ui.text-field/handle-mouse-down!)]
                (md! hit-field (:x event) (:y event) (:clicks event) (:handle panel-win)))
              (do
                (when-let [unfocus! (requiring-resolve 'app.ui.text-field/unfocus!)]
                  (unfocus!))
                (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-button)]
                  (handle-fn event {:scale @scale
                                    :window :panel})))))
          ;; Mouse up
          (do
            (when-let [dragging? (requiring-resolve 'app.ui.text-field/dragging-text?)]
              (when (dragging?)
                (when-let [mu! (requiring-resolve 'app.ui.text-field/handle-mouse-up!)]
                  (mu!))))
            (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-button)]
              (handle-fn event {:scale @scale
                                :window :panel})))))

      ;; Mouse move event
      (instance? EventMouseMove event)
      (let [text-dragging? (when-let [f (requiring-resolve 'app.ui.text-field/dragging-text?)]
                             (f))]
        (if text-dragging?
          (when-let [mm! (requiring-resolve 'app.ui.text-field/handle-mouse-move!)]
            (mm! (:x event) (:y event)))
          (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-move)]
            (handle-fn event {:scale @scale
                              :window :panel}))))

      ;; Text input event - only if text field is focused in THIS window
      (instance? EventTextInput event)
      (when-let [focused? (requiring-resolve 'app.ui.text-field/focused-in-window?)]
        (when (focused? (:handle panel-win))
          (when-let [handle-fn (requiring-resolve 'app.ui.text-field/handle-text-input!)]
            (handle-fn (:text event)))))

      ;; Keyboard event - route text field keys only if focused in THIS window
      (instance? EventKey event)
      (let [{:keys [key pressed? modifiers]} event
            text-field-focused? (when-let [f (requiring-resolve 'app.ui.text-field/focused-in-window?)]
                                  (f (:handle panel-win)))
            consumed? (when (and text-field-focused? pressed?)
                        (when-let [handle-fn (requiring-resolve 'app.ui.text-field/handle-key-event!)]
                          (handle-fn event)))]
        ;; Ctrl+` toggles panel show/hide (same as main window)
        (when (and (not consumed?) pressed?
                   (= key 0x60) (pos? (bit-and modifiers 0x00C0)))
          (let [visible? (not @shell-state/panel-visible?)]
            (shell-state/panel-visible? visible?)
            (when-not (:panel-inline? @sys/window-config)
              (if visible?
                (window/show! panel-win)
                (do (window/hide! panel-win)
                    (window/raise! main-win)))))))

      :else nil)))

;; ============================================================
;; Main window event handler
;; ============================================================

(defn create-event-handler
  "Create an event handler for the main window."
  [win]
  (let [last-time (atom (System/nanoTime))]
    (fn [event]
      (cond
        ;; Close event
        (instance? EventClose event)
        (when-not (= ::veto (call-example-cleanup!))
          (do-sys-cleanup! win))

        ;; Frame event
        (instance? EventFrameSkija event)
        (do
          (when (and (macos?) (not @sys/app-activated?))
            (macos/activate-app!)
            (reset! sys/app-activated? true))
          (window/request-frame! win)
          ;; Request panel frame at ~30 FPS (time-based, monitor-rate independent)
          (when (and (not (:panel-inline? @sys/window-config))
                     @shell-state/panel-visible?
                     (some? @sys/panel-window))
            (let [now-ms (/ (System/nanoTime) 1e6)
                  elapsed (- now-ms @panel-last-render-time)]
              (when (>= elapsed 33.0)
                (reset! panel-last-render-time now-ms)
                (window/request-frame! @sys/panel-window))))
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
              ;; Mouse down: check text field hit-test BEFORE unfocusing
              (let [hit-field (when-let [ht (requiring-resolve 'app.ui.text-field/hit-test)]
                                (ht (:x event) (:y event)))]
                (if hit-field
                  ;; Clicked on a text field — position cursor, start drag-select
                  (when-let [md! (requiring-resolve 'app.ui.text-field/handle-mouse-down!)]
                    (md! hit-field (:x event) (:y event) (:clicks event) (:handle win)))
                  ;; Clicked outside text fields — unfocus and continue with gestures
                  (let [_ (when-let [unfocus! (requiring-resolve 'app.ui.text-field/unfocus!)]
                            (unfocus!))
                        scrollbar-handler (requiring-resolve 'lib.gesture.api/handle-scrollbar-mouse-down)
                        tree @sys/current-tree
                        scrollbar-hit? (when (and scrollbar-handler tree)
                                         (scrollbar-handler event {:tree tree}))]
                    (when scrollbar-hit?
                      (window/request-frame! win))
                    (when-not scrollbar-hit?
                      (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-button)]
                        (handle-fn event {:scale @scale
                                          :window :main
                                          :window-width @window-width}))))))
              ;; Mouse up
              (do
                (when-let [dragging? (requiring-resolve 'app.ui.text-field/dragging-text?)]
                  (when (dragging?)
                    (when-let [mu! (requiring-resolve 'app.ui.text-field/handle-mouse-up!)]
                      (mu!))))
                (when-let [end-scrollbar (requiring-resolve 'lib.gesture.api/handle-scrollbar-mouse-up)]
                  (end-scrollbar))
                (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-button)]
                  (handle-fn event {:scale @scale
                                    :window :main
                                    :window-width @window-width}))))))

        ;; Mouse move event
        (instance? EventMouseMove event)
        (let [text-dragging? (when-let [f (requiring-resolve 'app.ui.text-field/dragging-text?)]
                               (f))]
          (if text-dragging?
            ;; Drag-to-select in text field
            (when-let [mm! (requiring-resolve 'app.ui.text-field/handle-mouse-move!)]
              (mm! (:x event) (:y event)))
            ;; Normal scrollbar / gesture flow
            (let [scrollbar-dragging? (when-let [f (requiring-resolve 'lib.gesture.api/scrollbar-dragging?)]
                                        (f))]
              (if scrollbar-dragging?
                (when-let [handle-scrollbar-move (requiring-resolve 'lib.gesture.api/handle-scrollbar-mouse-move)]
                  (when (handle-scrollbar-move event)
                    (window/request-frame! win)))
                (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-move)]
                  (handle-fn event {:scale @scale
                                    :window :main
                                    :window-width @window-width}))))))

        ;; Mouse wheel event
        (instance? EventMouseWheel event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-wheel)]
          (when (handle-fn event {:scale @scale
                                  :tree @sys/current-tree})
            (window/request-frame! win)))

        ;; Touch events
        (instance? EventFingerDown event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-finger-down)]
          (handle-fn event {:scale @scale
                            :window :main
                            :window-width @window-width}))

        (instance? EventFingerMove event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-finger-move)]
          (handle-fn event {:scale @scale
                            :window :main
                            :window-width @window-width}))

        (instance? EventFingerUp event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-finger-up)]
          (handle-fn event {:scale @scale
                            :window :main
                            :window-width @window-width}))

        ;; Text input event (from SDL_EVENT_TEXT_INPUT) - only if text field is focused in THIS window
        (instance? EventTextInput event)
        (when-let [focused? (requiring-resolve 'app.ui.text-field/focused-in-window?)]
          (when (focused? (:handle win))
            (when-let [handle-fn (requiring-resolve 'app.ui.text-field/handle-text-input!)]
              (handle-fn (:text event)))))

        ;; Keyboard event
        (instance? EventKey event)
        (let [{:keys [key pressed? modifiers]} event
              text-field-focused? (when-let [f (requiring-resolve 'app.ui.text-field/focused-in-window?)]
                                    (f (:handle win)))
              consumed? (when (and text-field-focused? pressed?)
                          (when-let [handle-fn (requiring-resolve 'app.ui.text-field/handle-key-event!)]
                            (handle-fn event)))
              ;; Delegate to example's on-key-pressed handler
              example-consumed? (when-not consumed?
                                  (when-let [handle-fn (requiring-resolve 'app.shell.core/handle-key-event!)]
                                    (handle-fn event)))]
          (when-not (or consumed? example-consumed?)
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
                (if @sys/recording?
                  (do
                    (when-let [stop-recording! (requiring-resolve 'lib.window.capture/stop-recording!)]
                      (stop-recording!))
                    (reset! sys/recording? false)
                    (update-display-title!)
                    (println "[keybind] Recording stopped"))
                  (do
                    (when-let [start-recording! (requiring-resolve 'lib.window.capture/start-recording!)]
                      (let [timestamp (java.time.LocalDateTime/now)
                            formatter (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-mm-ss")
                            filename (str "recording_" (.format timestamp formatter) ".mp4")]
                        (start-recording! filename {:fps 60})
                        (reset! sys/recording? true)
                        (update-display-title!)
                        (println "[keybind] Recording started:" filename))))))

              ;; Ctrl+` toggles panel window show/hide
              (when (and (= key 0x60) (pos? (bit-and modifiers 0x00C0)))
                (let [visible? (not @shell-state/panel-visible?)]
                  (shell-state/panel-visible? visible?)
                  (when-not (:panel-inline? @sys/window-config)
                    (when-let [panel-win @sys/panel-window]
                      (if visible?
                        (window/show! panel-win)
                        (do (window/hide! panel-win)
                            (window/raise! win))))))))))

        :else nil))))

;; ============================================================
;; Application startup
;; ============================================================

(defn- start-app-impl
  "Internal: Start the application.

   Startup sequence:
   1. Load example namespace
   2. Read example's window-config, merge with defaults
   3. Create main window with merged config
   4. Create panel window alongside main window
   5. Apply post-creation properties (bordered, fullscreen, etc.)
   6. Initialize shell (time source)
   7. Initialize example (calls init)
   8. Register shutdown hook for Ctrl+C handling
   9. Start event loop with run-multi!"
  [example-key]
  (reset! sys/running? true)

  ;; 1. Load example namespace
  (when-let [load-fn (requiring-resolve 'app.shell.core/load-example!)]
    (load-fn example-key))

  ;; 2. Read example config, merge with defaults
  (let [example-config (when-let [read-fn (requiring-resolve 'app.shell.core/read-example-config)]
                         (read-fn example-key))
        config (merge sys/default-window-config example-config)]
    (reset! sys/window-config config)

    ;; 3. Create main window with merged config
    (let [display-title (compose-display-title)
          win-opts (cond-> {:title        display-title
                            :width        (:width config)
                            :height       (:height config)
                            :resizable?   (:resizable? config)
                            :high-dpi?    true
                            :always-on-top? (:always-on-top? config)
                            :transparent? (:transparent? config)}
                     (:position config) (assoc :x (first (:position config))
                                               :y (second (:position config))))
          win (window/create-window win-opts)]
      (reset! sys/window win)
      (reset! sys/app-activated? false)
      (reset! scale (window/get-scale win))
      (let [[w h] (window/get-size win)]
        (reset! window-width w)
        (reset! window-height h))

      ;; 4. Create panel window positioned to the right of main window (unless inline)
      (let [panel-win (when-not (:panel-inline? @sys/window-config)
                        (let [[main-x main-y] (window/get-window-position win)
                              panel-x (+ main-x (:width config) 10)
                              panel-opts (cond-> {:title "Controls"
                                                  :width 260
                                                  :height 500
                                                  :x panel-x
                                                  :y main-y
                                                  :resizable? true
                                                  :high-dpi? true
                                                  :always-on-top? true
                                                  :vsync 0}
                                           ;; For OpenGL backend, share the GL context
                                           (= :opengl (window/get-backend win))
                                           (assoc :shared-gl-context (:gl-context win)))]
                          (window/create-window panel-opts)))]
        (reset! sys/panel-window panel-win)
        (when panel-win
          (let [[pw ph] (window/get-size panel-win)]
            (reset! panel-width pw)
            (reset! panel-height ph))
          ;; Hide panel if not visible at startup
          (when-not @shell-state/panel-visible?
            (window/hide! panel-win)))

        ;; 5. Apply post-creation properties to main window
        ;; Skip set-bordered! when transparent (already borderless from SDL flag)
        (when (and (not (:bordered? config))
                   (not (:transparent? config)))
          (window/set-bordered! win false))
        (when (:fullscreen? config)
          (window/set-fullscreen! win true))
        (when (not= 1.0 (:opacity config))
          (window/set-opacity! win (:opacity config)))
        (when-let [[mw mh] (:min-size config)]
          (window/set-minimum-size! win mw mh))
        (when-let [[mw mh] (:max-size config)]
          (window/set-maximum-size! win mw mh))

        ;; 6. Initialize shell (time source, etc.)
        (when-let [init-fn (requiring-resolve 'app.shell.core/init)]
          (init-fn))

        ;; 7. Initialize example
        (when-let [init-example-fn (requiring-resolve 'app.shell.core/init-example!)]
          (init-example-fn))

        ;; 8. Register shutdown hook for Ctrl+C / SIGTERM
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. (fn []
                                     (when @sys/running?
                                       (call-example-cleanup!)
                                       (do-sys-cleanup! win)))))

        ;; 9. Start event loop with both windows (or just main if inline)
        (window/set-event-handler! win (create-event-handler win))
        (when panel-win
          (window/set-event-handler! panel-win (create-panel-event-handler panel-win win)))
        (window/request-frame! win)
        (when (and panel-win @shell-state/panel-visible?)
          (window/request-frame! panel-win))
        (window/run-multi! win (if panel-win [panel-win] []))))))

(defn start-app
  "Start the application with the given example key."
  [example-key]
  (when-not @sys/running?
    (if (macos?)
      (macos/run-on-main-thread-sync! #(start-app-impl example-key))
      (start-app-impl example-key))))

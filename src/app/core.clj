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
  (:require [app.state :as state]
            [clojure.string :as str])
  (:import [io.github.humbleui.jwm App Window EventWindowCloseRequest EventWindowResize EventFrame EventMouseButton EventMouseMove ZOrder]
           [io.github.humbleui.jwm.skija EventFrameSkija LayerGLSkija]
           [io.github.humbleui.skija Canvas Paint PaintMode PaintStrokeCap Font Typeface]
           [java.util.function Consumer]
           [java.io StringWriter PrintWriter]))

;; ============================================================
;; Helpers
;; ============================================================

(defn cfg
  "Get config value with runtime var lookup (survives hot-reload).
   Uses requiring-resolve to load namespace if needed."
  [var-sym]
  (some-> (requiring-resolve var-sym) deref))

(defn config-loaded?
  "Check if app.config namespace is loaded and ready."
  []
  (some? (resolve 'app.config/circle-color)))

;; ============================================================
;; clj-reload hooks
;; ============================================================
;; app.core is unloaded FIRST and loaded LAST (top of dependency tree)
;; so these hooks bracket the entire reload process

(defn before-ns-unload []
  (reset! state/reloading? true))

(defn after-ns-reload []
  (reset! state/reloading? false))

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

(defn get-root-cause
  "Traverse exception chain to find root cause."
  [^Throwable e]
  (if-let [cause (.getCause e)]
    (recur cause)
    e))

(defn get-compiler-location
  "Extract file:line:column from Clojure CompilerException ex-data."
  [^Throwable e]
  (when-let [data (ex-data e)]
    (let [source (:clojure.error/source data)
          line (:clojure.error/line data)
          column (:clojure.error/column data)]
      (when (and source line)
        (str source ":" line (when column (str ":" column)))))))

(defn find-best-message
  "Search exception chain and suppressed exceptions for the most useful message."
  [^Throwable e]
  (let [;; Collect all exceptions in the chain
        chain (loop [ex e, acc []]
                (if ex
                  (recur (.getCause ex) (conj acc ex))
                  acc))
        ;; Also check suppressed exceptions
        all-exceptions (into chain (mapcat #(.getSuppressed ^Throwable %) chain))
        ;; Find messages, preferring non-null, non-empty ones
        messages (->> all-exceptions
                      (map #(.getMessage ^Throwable %))
                      (filter #(and % (not= % "null") (seq %))))]
    ;; Return first good message, or nil
    (first messages)))

(defn get-error-info
  "Extract useful error information from exception chain.
   Returns {:message :location :root-message :type}."
  [^Throwable e]
  (let [root (get-root-cause e)
        ;; Try to find CompilerException in the chain for location info
        compiler-ex (loop [ex e]
                      (cond
                        (nil? ex) nil
                        (instance? clojure.lang.Compiler$CompilerException ex) ex
                        :else (recur (.getCause ex))))
        location (when compiler-ex (get-compiler-location compiler-ex))
        ;; Get the best available message
        root-msg (.getMessage root)
        best-msg (if (or (nil? root-msg) (= root-msg "null") (empty? root-msg))
                   (or (find-best-message e) "(see console for details)")
                   root-msg)]
    {:type (.getSimpleName (.getClass root))
     :message best-msg
     :location location
     :original-message (.getMessage e)}))

(defn get-stack-trace-string
  "Get stack trace as a string."
  [^Throwable e]
  (let [sw (StringWriter.)
        pw (PrintWriter. sw)]
    (.printStackTrace e pw)
    (.toString sw)))

(defn draw-error
  "Draw error message and stack trace on canvas with red background."
  [^Canvas canvas ^Exception e]
  (let [bg-color    0xFFCC4444
        text-color  0xFFFFFFFF
        font-size   14
        padding     20
        line-height 18
        {:keys [type message location original-message]} (get-error-info e)]
    ;; Red background
    (.clear canvas (unchecked-int bg-color))
    ;; Draw error text
    (with-open [typeface (Typeface/makeDefault)
                font (Font. typeface (float font-size))
                paint (doto (Paint.)
                        (.setColor (unchecked-int text-color)))]
      ;; Header
      (.drawString canvas "ERROR" (float padding) (float (+ padding line-height)) font paint)
      ;; Location (file:line:column) if available (for compile errors)
      (when location
        (.drawString canvas (str "at " location) (float padding) (float (+ padding (* 2.5 line-height))) font paint))
      ;; Root cause message (the actual error like "Unable to resolve symbol: forma")
      (let [root-msg (str type ": " message)
            y-offset (if location 4.0 2.5)]
        (.drawString canvas root-msg (float padding) (float (+ padding (* y-offset line-height))) font paint))
      ;; Original message if different (shows context like "Failed to load namespace")
      (let [base-y-offset (if location 5.5 4.0)
            has-context? (and original-message (not= original-message message))]
        (when has-context?
          (.drawString canvas
                       (str "(while: " (subs original-message 0 (min (count original-message) 80)) ")")
                       (float padding)
                       (float (+ padding (* base-y-offset line-height)))
                       font paint))
        ;; Stack trace
        (let [stack-lines (-> (get-stack-trace-string e)
                              (str/split #"\n")
                              (->> (take 15)))
              stack-y-offset (if has-context? (+ base-y-offset 1.5) base-y-offset)]
          (doseq [[idx line] (map-indexed vector stack-lines)]
            (.drawString canvas
                         (subs line 0 (min (count line) 100))
                         (float padding)
                         (float (+ padding (* (+ stack-y-offset idx) line-height)))
                         font paint)))))))

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
    (when-let [draw-panel-fn (requiring-resolve 'app.controls/draw-panel)]
      (draw-panel-fn canvas width))))

;; ============================================================
;; Game loop infrastructure
;; ============================================================

(defn create-event-listener
  "Create an event listener for the window.
   Uses state/reloading? guard to skip event handling during namespace reload."
  [^Window window layer]
  (let [last-time (atom (System/nanoTime))]
    (reify Consumer
      (accept [_ event]
        (condp instance? event
          ;; Always handle close - no resolve needed
          EventWindowCloseRequest
          (do
            (reset! state/running? false)
            (.close window)
            (App/terminate))

          ;; Always request frames - keeps render loop alive during reload
          EventFrame
          (.requestFrame window)

          ;; Skip other events during reload (vars not available)
          (when-not @state/reloading?
            (condp instance? event
              EventMouseButton
              (let [^EventMouseButton me event]
                (if (.isPressed me)
                  ((requiring-resolve 'app.controls/handle-mouse-press) me)
                  ((requiring-resolve 'app.controls/handle-mouse-release) me)))

              EventMouseMove
              ((requiring-resolve 'app.controls/handle-mouse-move) event)

              EventWindowResize
              (let [^io.github.humbleui.jwm.EventWindowResize re event
                    scale @state/scale
                    w (/ (.getContentWidth re) scale)
                    h (/ (.getContentHeight re) scale)]
                (reset! state/window-width w)
                (reset! state/window-height h)
                ((requiring-resolve 'app.core/recalculate-grid!) w h))

              EventFrameSkija
              (let [^EventFrameSkija frame-event event
                    surface (.getSurface frame-event)
                    canvas (.getCanvas surface)
                    scale (if-let [screen (.getScreen window)]
                            (.getScale screen)
                            1.0)
                    _ (reset! state/scale scale)
                    pw (.getWidth surface)
                    ph (.getHeight surface)
                    w (/ pw scale)
                    h (/ ph scale)
                    _ (reset! state/window-width w)
                    _ (reset! state/window-height h)
                    now (System/nanoTime)
                    dt (/ (- now @last-time) 1e9)]
                (reset! last-time now)
                (when (pos? dt)
                  (let [current-fps (/ 1.0 dt)
                        smoothing 0.9]
                    (reset! state/fps (+ (* smoothing @state/fps)
                                         (* (- 1.0 smoothing) current-fps)))))
                (try
                  (.save canvas)
                  (.scale canvas (float scale) (float scale))
                  ;; Check for pending reload error (e.g., parse errors where old code still works)
                  (if-let [reload-err @state/last-reload-error]
                    (draw-error canvas reload-err)
                    (do
                      (when-let [tick-fn (requiring-resolve 'app.core/tick)]
                        (tick-fn dt))
                      (when-let [draw-fn (requiring-resolve 'app.core/draw)]
                        (draw-fn canvas w h))))
                  (catch Exception e
                    ;; Show reload error (root cause) if available, otherwise runtime error
                    (let [error-to-show (or @state/last-reload-error e)]
                      (draw-error canvas error-to-show))
                    (println "Render error:" (.getMessage e)))
                  (finally
                    (.restore canvas)))
                (.requestFrame window))

              nil)))))))

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

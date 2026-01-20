(ns app.core
  "Main application - Love2D style game loop with SDL3/Skija.

   Architecture (clj-reload pattern):
   - ALL callbacks use (resolve ...) for dynamic dispatch
   - This allows hot-reloading of ALL namespaces including app.core
   - Only defonce values in app.state persist across reloads

   Game loop callbacks (hot-reloadable):
   - init - called once at startup
   - tick - called every frame with delta time (dt)
   - draw - called every frame for rendering"
  (:require [app.state :as state]
            [clojure.string :as str]
            [lib.layout.core :as layout]
            [lib.layout.render :as layout-render]
            [lib.window.core :as window]
            [lib.window.events :as e]
            [lib.window.macos :as macos])
  (:import [io.github.humbleui.skija Canvas Paint PaintMode PaintStrokeCap Font Typeface]
           [io.github.humbleui.types Rect]
           [java.io StringWriter PrintWriter]
           [java.awt Toolkit]
           [java.awt.datatransfer StringSelection]
           [lib.window.events EventClose EventResize EventMouseButton EventMouseMove
            EventKey EventFrameSkija EventFingerDown EventFingerMove EventFingerUp]))

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

(defn clj-reload-parse-error?
  "Check if exception is from clj-reload's file reading/parsing (scan function)."
  [^Throwable e]
  (let [stack-trace (.getStackTrace e)]
    (some #(str/includes? (.getClassName ^StackTraceElement %) "clj_reload.core$scan")
          stack-trace)))

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
        ;; Get the message
        root-msg (.getMessage root)
        msg-empty? (or (nil? root-msg) (= root-msg "null") (empty? root-msg))
        ;; Only suggest console for clj-reload parse errors with empty messages
        best-msg (if (and msg-empty? (clj-reload-parse-error? e))
                   "(see console for details)"
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

(defn copy-to-clipboard!
  "Copy text to system clipboard."
  [^String text]
  (let [clipboard (.getSystemClipboard (Toolkit/getDefaultToolkit))
        selection (StringSelection. text)]
    (.setContents clipboard selection nil)))

(defn format-error-for-clipboard
  "Format error with full stack trace for clipboard."
  [^Throwable e]
  (let [{:keys [type message location original-message]} (get-error-info e)
        lines [(str "ERROR")
               (when location (str "Location: " location))
               (str type ": " message)
               (when (and original-message (not= original-message message))
                 (str "Context: " original-message))
               ""
               "Stack trace:"
               (get-stack-trace-string e)]]
    (str/join "\n" (remove nil? lines))))

(defn truncate-with-ellipsis
  "Truncate string to max-len, adding ellipsis if truncated."
  [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 max-len) "…")
    s))

(defn copy-current-error-to-clipboard!
  "Copy the current error (if any) to clipboard.
   Prioritizes reload error over runtime error."
  []
  (when-let [err (or @state/last-reload-error @state/last-runtime-error)]
    (copy-to-clipboard! (format-error-for-clipboard err))))

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
                       (str "(while: " (truncate-with-ellipsis original-message 80) ")")
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
                         (truncate-with-ellipsis line 100)
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
  ;; Point all libs to use game-time
  (when-let [time-source (requiring-resolve 'lib.time/time-source)]
    (reset! @time-source #(deref state/game-time)))

  ;; Set initial anchor position to center of window
  (reset! state/demo-anchor-x (/ @state/window-width 2))
  (reset! state/demo-anchor-y (/ @state/window-height 2))
  (reset! state/demo-circle-x (/ @state/window-width 2))
  (reset! state/demo-circle-y (/ @state/window-height 2))
  ;; Initial grid calculation
  (recalculate-grid! @state/window-width @state/window-height)

  ;; Register gesture targets
  (when-let [register-gestures! (requiring-resolve 'app.gestures/register-gestures!)]
    (register-gestures!)))

(defn tick
  "Called every frame with delta time in seconds.
   Update your game state here."
  [dt]
  ;; Advance game time (dt is in seconds, apply time scale)
  (swap! state/game-time + (* dt @state/time-scale))

  ;; Track velocity during drag (frame-based, not event-based)
  ;; Uses a ring buffer of last 3 position samples to compute velocity.
  ;; This captures momentum from "just before" mouse stops, solving the
  ;; stale velocity problem where event-based sampling gives velocity ≈ 0
  ;; when user stops moving before releasing.
  (when @state/demo-dragging?
    (let [history @state/demo-position-history
          current-x @state/demo-circle-x
          current-t @state/game-time
          new-history (-> history
                          (conj {:x current-x :t current-t})
                          (->> (take-last 3))
                          vec)]
      (reset! state/demo-position-history new-history)
      ;; Calculate velocity from oldest to newest sample
      (when (>= (count new-history) 2)
        (let [oldest (first new-history)
              newest (last new-history)
              dt-hist (- (:t newest) (:t oldest))]
          (when (pos? dt-hist)
            (reset! state/demo-velocity-x
                    (/ (- (:x newest) (:x oldest)) dt-hist)))))))

  ;; Check long-press timers in gesture system
  (when-let [check-long-press! (requiring-resolve 'lib.gesture.api/check-long-press!)]
    (check-long-press!))

  ;; Update demo circle decay animation (X-axis only)
  (when-not @state/demo-dragging?
    (when-let [decay-x @state/demo-decay-x]
      (when-let [decay-now (requiring-resolve 'lib.anim.decay/decay-now)]
        (let [{:keys [value at-rest?]} (decay-now decay-x)]
          (reset! state/demo-circle-x value)
          (when at-rest? (reset! state/demo-decay-x nil)))))))

(defn draw-demo-anchor
  "Draw the anchor/rest position for the spring demo."
  [^Canvas canvas]
  (let [x @state/demo-anchor-x
        y @state/demo-anchor-y
        radius (or (cfg 'app.config/demo-circle-radius) 25)]
    (with-open [paint (doto (Paint.)
                        (.setColor (unchecked-int (or (cfg 'app.config/demo-anchor-color) 0x44FFFFFF)))
                        (.setMode PaintMode/STROKE)
                        (.setStrokeWidth 2.0)
                        (.setAntiAlias true))]
      (.drawCircle canvas (float x) (float y) (float radius) paint))))

(defn draw-demo-circle
  "Draw the draggable demo circle."
  [^Canvas canvas]
  (let [x @state/demo-circle-x
        y @state/demo-circle-y
        radius (or (cfg 'app.config/demo-circle-radius) 25)]
    (with-open [paint (doto (Paint.)
                        (.setColor (unchecked-int (or (cfg 'app.config/demo-circle-color) 0xFF4A90D9)))
                        (.setMode PaintMode/FILL)
                        (.setAntiAlias true))]
      (.drawCircle canvas (float x) (float y) (float radius) paint))))

;; ============================================================
;; Layout Demo
;; ============================================================

(defn demo-ui
  "Layout system demo using new Subform-style API."
  []
  {:layout {:x {:size "100%"} :y {:size "100%"}}
   :children-layout {:mode :stack-y
                     :x {:before 20 :after 20}
                     :y {:before 20 :between 12 :after 20}}
   :children
   [;; Row 1: Fixed + Spacer + Fixed
    {:layout {:y {:size 50}}
     :children-layout {:mode :stack-x :x {:between 10}}
     :children
     [{:layout {:x {:size 100}} :fill 0xFF4A90D9 :label "100px"}
      {:layout {:x {:size "1s"}} :fill 0x20FFFFFF :label "spacer (1s)"}
      {:layout {:x {:size 100}} :fill 0xFF4A90D9 :label "100px"}]}

    ;; Row 2: Stretch weights 1:2:1
    {:layout {:y {:size 60}}
     :children-layout {:mode :stack-x :x {:between 10}}
     :children
     [{:layout {:x {:size "1s"}} :fill 0xFF44AA66 :label "1s"}
      {:layout {:x {:size "2s"}} :fill 0xFF66CC88 :label "2s"}
      {:layout {:x {:size "1s"}} :fill 0xFF44AA66 :label "1s"}]}

    ;; Row 3: Percentages
    {:layout {:y {:size 50}}
     :children-layout {:mode :stack-x :x {:between 10}}
     :children
     [{:layout {:x {:size "30%"}} :fill 0xFFD94A4A :label "30%"}
      {:layout {:x {:size "70%"}} :fill 0xFFD97A4A :label "70%"}]}

    ;; Row 4: Vertical stretch (fills remaining)
    {:layout {:y {:size "1s"}} :fill 0x15FFFFFF :label "stretch (1s)"}

    ;; Row 5: Grid
    {:layout {:y {:size 120}}
     :fill 0x10FFFFFF
     :label "grid 3 x-count"
     :children-layout {:mode :grid
                       :x-count 3
                       :x {:before 10 :between 8 :after 10}
                       :y {:before 10 :between 8 :after 10}}
     :children
     (vec (for [i (range 6)]
            {:layout {:y {:size 45}}
             :fill (+ 0xFF505050 (* i 0x101010))
             :label (str "cell " i)}))}]})

(defn draw-layout-demo
  "Draw the layout demo UI."
  [^Canvas canvas width height]
  (with-open [fill-paint (Paint.)
              text-paint (doto (Paint.) (.setColor (unchecked-int 0xFFFFFFFF)))
              font (Font. (Typeface/makeDefault) (float 10))]
    (layout-render/render-tree canvas (demo-ui) {:x 0 :y 0 :w width :h height}
                               (fn [^Canvas c node {:keys [x y w h]}]
                                 ;; Draw fill
                                 (when-let [color (:fill node)]
                                   (.setColor fill-paint (unchecked-int color))
                                   (.setMode fill-paint PaintMode/FILL)
                                   (.drawRect c (Rect/makeXYWH x y w h) fill-paint))
                                 ;; Draw label only on leaf nodes (no children)
                                 (when (and (:label node) (not (:children node)))
                                   (.drawString c (:label node) (float (+ x 4)) (float (+ y 12)) font text-paint))))))

(defn draw
  "Called every frame for rendering.
   Draw your game here."
  [^Canvas canvas width height]
  ;; Clear background
  (.clear canvas (unchecked-int 0xFF222222))

  ;; Draw layout demo
  (draw-layout-demo canvas width height))

;; ============================================================
;; Game loop infrastructure
;; ============================================================

(defn create-event-handler
  "Create an event handler for the window.
   Uses state/reloading? guard to skip event handling during namespace reload."
  [win]
  (let [last-time (atom (System/nanoTime))]
    (fn [event]
      (cond
        ;; Close event
        (instance? EventClose event)
        (do
          (reset! state/running? false)
          (window/close! win))

        ;; Frame event - always request next frame, draw only when not reloading
        ;; This keeps the render loop alive during hot-reload
        ;; Returns true if drew (signals render-frame! to swap buffers)
        ;; Returns nil if skipped (preserves previous frame - no flicker)
        (instance? EventFrameSkija event)
        (do
          ;; Always request next frame - keeps render loop alive during reload
          (window/request-frame! win)
          ;; Only draw when not reloading
          (when-not @state/reloading?
            (let [{:keys [canvas]} event
                  scale @state/scale
                  w @state/window-width
                  h @state/window-height
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
                ;; Check for pending reload error
                (if-let [reload-err @state/last-reload-error]
                  (draw-error canvas reload-err)
                  (do
                    (reset! state/last-runtime-error nil)
                    (when-let [tick-fn (requiring-resolve 'app.core/tick)]
                      (tick-fn dt))
                    (when-let [draw-fn (requiring-resolve 'app.core/draw)]
                      (draw-fn canvas w h))))
                (catch Exception e
                  (reset! state/last-runtime-error e)
                  (let [error-to-show (or @state/last-reload-error e)]
                    (draw-error canvas error-to-show))
                  (println "Render error:" (.getMessage e)))
                (finally
                  (.restore canvas)))
              ;; Return true to signal that we drew - render-frame! will swap buffers
              true)))

        ;; Skip other events during reload (vars not available)
        @state/reloading?
        nil

        ;; Resize event
        (instance? EventResize event)
        (let [{:keys [width height scale]} event]
          (reset! state/window-width width)
          (reset! state/window-height height)
          (reset! state/scale scale)
          ((requiring-resolve 'app.core/recalculate-grid!) width height))

        ;; Mouse button event
        (instance? EventMouseButton event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-button)]
          (handle-fn event {:scale @state/scale
                            :window-width @state/window-width}))

        ;; Mouse move event
        (instance? EventMouseMove event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-move)]
          (handle-fn event {:scale @state/scale
                            :window-width @state/window-width}))

        ;; Touch/finger events
        (instance? EventFingerDown event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-finger-down)]
          (handle-fn event {:scale @state/scale
                            :window-width @state/window-width}))

        (instance? EventFingerMove event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-finger-move)]
          (handle-fn event {:scale @state/scale
                            :window-width @state/window-width}))

        (instance? EventFingerUp event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-finger-up)]
          (handle-fn event {:scale @state/scale
                            :window-width @state/window-width}))

        ;; Keyboard event
        (instance? EventKey event)
        (let [{:keys [key pressed? modifiers]} event]
          (when pressed?
            ;; F2 copies error to clipboard
            ;; Ctrl+` toggles panel
            ;; Note: key codes differ from JWM, may need adjustment
            nil))

        ;; Unknown event - ignore
        :else nil))))

(defn- macos?
  "Check if running on macOS."
  []
  (str/includes? (str/lower-case (System/getProperty "os.name" "")) "mac"))

(defn- start-app-impl
  "Internal: Start the application - creates window and runs event loop.
   Must be called on macOS main thread for SDL3 compatibility."
  []
  (reset! state/running? true)
  (let [win (window/create-window {:title "Skija Demo - Hot Reload with clj-reload"
                                   :width 800
                                   :height 600
                                   :resizable? true
                                   :high-dpi? true})]
    (reset! state/window win)
    ;; Initialize scale and dimensions from window
    (reset! state/scale (window/get-scale win))
    (let [[w h] (window/get-size win)]
      (reset! state/window-width w)
      (reset! state/window-height h))
    ;; Call init once at startup
    (when-let [init-fn (resolve 'app.core/init)]
      (init-fn))
    ;; Set up event handler and request first frame
    (window/set-event-handler! win (create-event-handler win))
    (window/request-frame! win)
    ;; Run event loop (blocks)
    (window/run! win)))

(defn start-app
  "Start the application - creates window and runs event loop.
   On macOS, dispatches SDL operations to the main thread (thread 0)."
  []
  (when-not @state/running?
    (if (macos?)
      ;; macOS: dispatch to main thread for SDL3/Cocoa compatibility
      (macos/run-on-main-thread-sync! start-app-impl)
      ;; Other platforms: run directly
      (start-app-impl))))

(defn -main
  "Entry point for running the application."
  [& args]
  (println "Starting Skija demo...")
  (println "Tip: Connect a REPL and use (user/reload) to hot-reload changes!")
  (start-app))

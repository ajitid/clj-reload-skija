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
  (:require [app.state.sources :as src]
            [app.state.signals :as sig]
            [app.state.animations :as anim]
            [app.state.system :as sys]
            [app.util :refer [cfg]]
            [clojure.string :as str]
            [lib.flex.core :as flex]
            [lib.layout.core :as layout]
            [lib.layout.mixins :as mixins]
            [lib.layout.render :as layout-render]
            [lib.layout.scroll :as scroll]
            [lib.window.core :as window]
            [lib.window.events :as e]
            [lib.window.macos :as macos])
  (:import [io.github.humbleui.skija Canvas Paint PaintMode PaintStrokeCap Font Typeface]
           [io.github.humbleui.types Rect]
           [java.io StringWriter PrintWriter]
           [lib.window.events EventClose EventResize EventMouseButton EventMouseMove EventMouseWheel
            EventKey EventFrameSkija EventFingerDown EventFingerMove EventFingerUp]))

;; ============================================================
;; Helpers
;; ============================================================

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
  (reset! sys/reloading? true))

(defn after-ns-reload []
  (reset! sys/reloading? false))

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
  "Draw a grid of circles using batched points API.
   Uses the grid-positions signal which auto-recomputes."
  [^Canvas canvas]
  (let [positions @sig/grid-positions]
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
  "Copy text to system clipboard using SDL3."
  [^String text]
  (window/set-clipboard-text! text))

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
  (when-let [err (or @sys/last-reload-error @sys/last-runtime-error)]
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
      (.drawString canvas "ERROR (Ctrl+E or middle-click to copy)" (float padding) (float (+ padding line-height)) font paint)
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
    (reset! @time-source #(deref sys/game-time)))

  ;; Set initial demo position at top of window (x-centered, y fixed near top)
  (let [demo-y 50]
    (reset! anim/demo-anchor-x (/ @src/window-width 2))
    (reset! anim/demo-anchor-y demo-y)
    (reset! anim/demo-circle-x (/ @src/window-width 2))
    (reset! anim/demo-circle-y demo-y))

  ;; Initialize scroll state for demos
  (scroll/init! :scroll-demo)
  (scroll/init! :virtual-list)

  ;; Register gesture targets
  (when-let [register-gestures! (requiring-resolve 'app.gestures/register-gestures!)]
    (register-gestures!)))

(defn tick
  "Called every frame with delta time in seconds.
   Update your game state here."
  [dt]
  ;; Advance game time (dt is in seconds, apply time scale)
  (swap! sys/game-time + (* dt @sys/time-scale))

  ;; Track velocity during drag (frame-based, not event-based)
  ;; Uses a ring buffer of last 3 position samples to compute velocity.
  ;; This captures momentum from "just before" mouse stops, solving the
  ;; stale velocity problem where event-based sampling gives velocity ≈ 0
  ;; when user stops moving before releasing.
  (when @src/demo-dragging?
    (let [history @anim/demo-position-history
          current-x @anim/demo-circle-x
          current-t @sys/game-time
          new-history (-> history
                          (conj {:x current-x :t current-t})
                          (->> (take-last 3))
                          vec)]
      (reset! anim/demo-position-history new-history)
      ;; Calculate velocity from oldest to newest sample
      (when (>= (count new-history) 2)
        (let [oldest (first new-history)
              newest (last new-history)
              dt-hist (- (:t newest) (:t oldest))]
          (when (pos? dt-hist)
            (reset! anim/demo-velocity-x
                    (/ (- (:x newest) (:x oldest)) dt-hist)))))))

  ;; Check long-press timers in gesture system
  (when-let [check-long-press! (requiring-resolve 'lib.gesture.api/check-long-press!)]
    (check-long-press!))

  ;; Tick all registered animations (includes demo circle decay)
  (when-let [tick-fn (requiring-resolve 'lib.anim.registry/tick-all!)]
    (tick-fn)))

(defn draw-demo-anchor
  "Draw the anchor/rest position for the spring demo."
  [^Canvas canvas]
  (let [x @anim/demo-anchor-x
        y @anim/demo-anchor-y
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
  (let [x @anim/demo-circle-x
        y @anim/demo-circle-y
        radius (or (cfg 'app.config/demo-circle-radius) 25)]
    (with-open [paint (doto (Paint.)
                        (.setColor (unchecked-int (or (cfg 'app.config/demo-circle-color) 0xFF4A90D9)))
                        (.setMode PaintMode/FILL)
                        (.setAntiAlias true))]
      (.drawCircle canvas (float x) (float y) (float radius) paint))))

;; ============================================================
;; Layout Demo
;; ============================================================

;; Virtual scroll mixin for 10,000 items
(def virtual-scroll-mixin
  (mixins/virtual-scroll
    (vec (range 10000))   ;; 10,000 items
    40                     ;; 40px per item
    (fn [item idx]
      {:fill (+ 0xFF303050 (* (mod idx 5) 0x101010))
       :label (str "Item " idx)})))

(defn demo-ui
  "Layout system demo using new Subform-style API."
  [viewport-height]
  {:layout {:x {:size "100%"} :y {:size "100%"}}
   :children-layout {:mode :stack-x
                     :x {:before 20 :between 20 :after 20}
                     :y {:before 20 :after 20}}
   :children
   [;; Left column: Original layout demo
    {:layout {:x {:size "1s"} :y {:size "100%"}}
     :children-layout {:mode :stack-y
                       :y {:between 12}}
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
      {:layout {:y {:size "1s"}} :fill 0x15FFFFFF :label "stretch (1s)"}]}

    ;; Middle column: Scrollable list demo (30 items)
    {:id :scroll-demo
     :layout {:x {:size 180} :y {:size "100%"}}
     :fill 0x20FFFFFF
     :children-layout {:mode :stack-y
                       :overflow {:y :scroll}
                       :y {:before 10 :between 8 :after 10}
                       :x {:before 10 :after 10}}
     :children
     (vec (for [i (range 30)]
            {:layout {:y {:size 40}}
             :fill (+ 0xFF303050 (* (mod i 5) 0x101010))
             :label (str "Item " (inc i))}))}

    ;; Right column: Virtual scroll demo (10,000 items)
    {:id :virtual-list
     :layout {:x {:size 180} :y {:size "100%"}}
     :fill 0x20FFFFFF
     :children-layout {:mode :stack-y
                       :overflow {:y :scroll}
                       :y {:before 10 :after 10}
                       :x {:before 10 :after 10}}
     :children (mixins/compute-visible-children virtual-scroll-mixin :virtual-list (- viewport-height 40))}]})

(defn- find-node-by-id
  "Find a node in laid-out tree by :id."
  [tree id]
  (when tree
    (if (= (:id tree) id)
      tree
      (some #(find-node-by-id % id) (:children tree)))))

(defn- calculate-content-height
  "Calculate total content height from children."
  [node]
  (if-let [children (:children node)]
    (let [bounds (map :bounds children)
          max-bottom (reduce max 0 (map #(+ (:y %) (:h %)) bounds))
          parent-top (get-in node [:bounds :y] 0)]
      (- max-bottom parent-top))
    0))

(defn draw-layout-demo
  "Draw the layout demo UI.
   offset-y: vertical offset for layout bounds (screen space)"
  [^Canvas canvas width height offset-y]
  ;; Step 1: Layout (compute bounds in screen space, including offset)
  (let [tree (demo-ui height)
        parent-bounds {:x 0 :y offset-y :w width :h height}
        laid-out (layout/layout tree parent-bounds)]

    ;; Reconcile lifecycle (mount/unmount in correct order)
    (layout/reconcile! laid-out)

    ;; Step 2: Update scroll dimensions BEFORE rendering (this clamps scroll)
    (when-let [scroll-node (find-node-by-id laid-out :scroll-demo)]
      (let [bounds (:bounds scroll-node)
            viewport {:w (:w bounds) :h (:h bounds)}
            content-h (calculate-content-height scroll-node)
            content {:w (:w bounds) :h content-h}]
        (scroll/set-dimensions! :scroll-demo viewport content)))

    ;; Store laid-out tree for scroll hit testing
    (reset! sys/current-tree laid-out)

    ;; Step 3: Render with clamped scroll position
    (with-open [fill-paint (Paint.)
                text-paint (doto (Paint.) (.setColor (unchecked-int 0xFFFFFFFF)))
                font (Font. (Typeface/makeDefault) (float 10))]
      (layout-render/walk-layout laid-out canvas
        (fn [node {:keys [x y w h]} _canvas]
          ;; Draw fill
          (when-let [color (:fill node)]
            (.setColor fill-paint (unchecked-int color))
            (.setMode fill-paint PaintMode/FILL)
            (.drawRect canvas (Rect/makeXYWH x y w h) fill-paint))
          ;; Draw label only on leaf nodes (no children)
          (when (and (:label node) (not (:children node)))
            (.drawString canvas (:label node) (float (+ x 4)) (float (+ y 12)) font text-paint)))))))

(defn draw
  "Called every frame for rendering.
   Draw your game here."
  [^Canvas canvas width height]
  ;; Clear background
  (.clear canvas (unchecked-int 0xFF222222))

  ;; Demo ball area at top (100px reserved)
  (let [demo-area-height 100]
    ;; Draw demo ball in the reserved top area (x-axis draggable with momentum)
    (draw-demo-anchor canvas)
    (draw-demo-circle canvas)

    ;; Draw layout demo below the demo area (bounds computed in screen space)
    (draw-layout-demo canvas width (- height demo-area-height) demo-area-height))

  ;; Draw control panel (on top) when visible
  (when @src/panel-visible?
    (when-let [draw-panel (requiring-resolve 'app.controls/draw-panel)]
      (draw-panel canvas width))))

;; ============================================================
;; Game loop infrastructure
;; ============================================================

(defn create-event-handler
  "Create an event handler for the window.
   Uses sys/reloading? guard to skip event handling during namespace reload."
  [win]
  (let [last-time (atom (System/nanoTime))]
    (fn [event]
      (cond
        ;; Close event
        (instance? EventClose event)
        (do
          ;; Stop recording if active
          (when @src/recording-active?
            (when-let [stop-recording! (requiring-resolve 'lib.window.capture/stop-recording!)]
              (stop-recording!))
            (src/recording-active? false))
          (reset! sys/running? false)
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
          (when-not @sys/reloading?
            (let [{:keys [canvas]} event
                  scale @src/scale
                  w @src/window-width
                  h @src/window-height
                  now (System/nanoTime)
                  raw-dt (/ (- now @last-time) 1e9)
                  dt (min raw-dt 0.033)]  ;; Clamp to 30 FPS floor to prevent animation jumps
              (reset! last-time now)
              (when (pos? raw-dt)
                (let [current-fps (/ 1.0 raw-dt)
                      smoothing 0.8]
                  (src/fps (+ (* smoothing @src/fps)
                             (* (- 1.0 smoothing) current-fps)))))
              (try
                (.save canvas)
                (.scale canvas (float scale) (float scale))
                ;; Check for pending reload error
                (if-let [reload-err @sys/last-reload-error]
                  (draw-error canvas reload-err)
                  (do
                    (reset! sys/last-runtime-error nil)
                    (when-let [tick-fn (requiring-resolve 'app.core/tick)]
                      (tick-fn dt))
                    (when-let [draw-fn (requiring-resolve 'app.core/draw)]
                      (draw-fn canvas w h))))
                (catch Exception e
                  (reset! sys/last-runtime-error e)
                  (let [error-to-show (or @sys/last-reload-error e)]
                    (draw-error canvas error-to-show))
                  (println "Render error:" (.getMessage e)))
                (finally
                  (.restore canvas)))
              ;; Return true to signal that we drew - render-frame! will swap buffers
              true)))

        ;; Skip other events during reload (vars not available)
        @sys/reloading?
        nil

        ;; Resize event
        (instance? EventResize event)
        (let [{:keys [width height scale]} event]
          ;; Update Flex sources - grid-positions signal auto-recomputes
          (src/window-width width)
          (src/window-height height)
          (src/scale scale))

        ;; Mouse button event
        (instance? EventMouseButton event)
        (do
          ;; Middle click copies error to clipboard (only if error exists)
          (when (and (= (:button event) :middle) (:pressed? event)
                     (or @sys/last-reload-error @sys/last-runtime-error))
            (copy-current-error-to-clipboard!))
          (when (= (:button event) :primary)
            (if (:pressed? event)
              ;; Mouse down: check scrollbar first, then gesture system
              (let [scrollbar-handler (requiring-resolve 'lib.gesture.api/handle-scrollbar-mouse-down)
                    scrollbar-hit? (when scrollbar-handler
                                     (scrollbar-handler event {:tree @sys/current-tree}))]
                (when scrollbar-hit?
                  (window/request-frame! win))
                ;; If not a scrollbar hit, pass to gesture system
                (when-not scrollbar-hit?
                  (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-button)]
                    (handle-fn event {:scale @src/scale
                                      :window-width @src/window-width}))))
              ;; Mouse up: end scrollbar drag, then gesture system
              (do
                (when-let [end-scrollbar (requiring-resolve 'lib.gesture.api/handle-scrollbar-mouse-up)]
                  (end-scrollbar))
                (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-button)]
                  (handle-fn event {:scale @src/scale
                                    :window-width @src/window-width}))))))

        ;; Mouse move event
        (instance? EventMouseMove event)
        (let [scrollbar-dragging? (when-let [f (requiring-resolve 'lib.gesture.api/scrollbar-dragging?)]
                                    (f))]
          (if scrollbar-dragging?
            ;; Handle scrollbar drag movement
            (when-let [handle-scrollbar-move (requiring-resolve 'lib.gesture.api/handle-scrollbar-mouse-move)]
              (when (handle-scrollbar-move event)
                (window/request-frame! win)))
            ;; Regular gesture system handling
            (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-move)]
              (handle-fn event {:scale @src/scale
                                :window-width @src/window-width}))))

        ;; Mouse wheel event
        (instance? EventMouseWheel event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-mouse-wheel)]
          (when (handle-fn event {:scale @src/scale
                                  :tree @sys/current-tree})
            ;; Request frame redraw if scroll was handled
            (window/request-frame! win)))

        ;; Touch/finger events
        (instance? EventFingerDown event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-finger-down)]
          (handle-fn event {:scale @src/scale
                            :window-width @src/window-width}))

        (instance? EventFingerMove event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-finger-move)]
          (handle-fn event {:scale @src/scale
                            :window-width @src/window-width}))

        (instance? EventFingerUp event)
        (when-let [handle-fn (requiring-resolve 'lib.gesture.api/handle-finger-up)]
          (handle-fn event {:scale @src/scale
                            :window-width @src/window-width}))

        ;; Keyboard event
        (instance? EventKey event)
        (let [{:keys [key pressed? modifiers]} event]
          (when pressed?
            ;; Ctrl+E copies error to clipboard (only if error exists)
            ;; SDL3 'e' keycode = 0x65, CTRL modifier = 0x00C0 (LCTRL | RCTRL)
            (when (and (= key 0x65) (pos? (bit-and modifiers 0x00C0))
                       (or @sys/last-reload-error @sys/last-runtime-error))
              (copy-current-error-to-clipboard!))

            ;; Ctrl+S captures screenshot
            ;; SDL3 's' keycode = 0x73
            (when (and (= key 0x73) (pos? (bit-and modifiers 0x00C0)))
              (when-let [screenshot! (requiring-resolve 'lib.window.capture/screenshot!)]
                (let [timestamp (java.time.LocalDateTime/now)
                      formatter (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-mm-ss-SSS")
                      filename (str "screenshot_" (.format timestamp formatter) ".png")]
                  (screenshot! filename :png)
                  (println "[keybind] Screenshot captured:" filename))))

            ;; Ctrl+R toggles recording
            ;; SDL3 'r' keycode = 0x72
            (when (and (= key 0x72) (pos? (bit-and modifiers 0x00C0)))
              (if @src/recording-active?
                ;; Stop recording
                (do
                  (when-let [stop-recording! (requiring-resolve 'lib.window.capture/stop-recording!)]
                    (stop-recording!))
                  (src/recording-active? false)
                  ;; Restore original title
                  (window/set-window-title! @sys/window @sys/window-title)
                  (println "[keybind] Recording stopped"))
                ;; Start recording
                (do
                  (when-let [start-recording! (requiring-resolve 'lib.window.capture/start-recording!)]
                    (let [timestamp (java.time.LocalDateTime/now)
                          formatter (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-mm-ss")
                          filename (str "recording_" (.format timestamp formatter) ".mp4")]
                      (start-recording! filename {:fps 60})
                      (src/recording-active? true)
                      ;; Update title with [Recording] indicator
                      (window/set-window-title! @sys/window
                                               (str @sys/window-title " [Recording]"))
                      (println "[keybind] Recording started:" filename))))))

            ;; Ctrl+` toggles panel visibility
            ;; SDL3 '`' (grave/backtick) keycode = 0x60
            (when (and (= key 0x60) (pos? (bit-and modifiers 0x00C0)))
              (src/panel-visible? (not @src/panel-visible?))
              (println "[keybind] Panel visible:" @src/panel-visible?))))

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
  (reset! sys/running? true)
  (let [win (window/create-window {:title "Skija Demo - Hot Reload with clj-reload"
                                   :width 800
                                   :height 600
                                   :resizable? true
                                   :high-dpi? true})]
    (reset! sys/window win)
    ;; On macOS, activate app to receive keyboard/mouse focus
    ;; (SDL_RaiseWindow alone only raises z-order, doesn't grant input focus)
    (when (macos?)
      (macos/activate-app!))
    ;; Initialize window title state for recording indicator
    (reset! sys/window-title "Skija Demo - Hot Reload with clj-reload")
    ;; Initialize scale and dimensions from window (Flex sources)
    (src/scale (window/get-scale win))
    (let [[w h] (window/get-size win)]
      (src/window-width w)
      (src/window-height h))
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
  (when-not @sys/running?
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

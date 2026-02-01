(ns app.shell.core
  "Shell lifecycle that wraps examples.

   The shell provides:
   - Example resolution (:playground/ball-spring -> ns symbol)
   - Example loading and init
   - Debug panel overlay

   Examples provide init/tick/draw/cleanup callbacks.
   Each launch is a fresh process — no switching between examples."
  (:require [app.shell.state :as state]
            [app.shell.debug-panel :as debug-panel]
            [app.state.system :as sys])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Example resolution
;; ============================================================

(defn resolve-example-ns
  "Convert example key to namespace symbol.
   :playground/ball-spring -> 'app.projects.playground.ball-spring"
  [example-key]
  (let [ns-name (-> (str example-key)
                    (subs 1)  ;; Remove leading :
                    (clojure.string/replace "/" ".")
                    (clojure.string/replace "-" "-"))]
    (symbol (str "app.projects." ns-name))))

(defn require-example!
  "Load an example namespace."
  [example-key]
  (let [ns-sym (resolve-example-ns example-key)]
    (require ns-sym)
    ns-sym))

(defn example-fn
  "Get a function from the current example, or nil if not found."
  [fn-name]
  (when-let [example-key @state/current-example]
    (let [ns-sym (resolve-example-ns example-key)
          fn-sym (symbol (str ns-sym) fn-name)]
      (requiring-resolve fn-sym))))

;; ============================================================
;; Example lifecycle
;; ============================================================

(defn read-example-config
  "Read the window-config var from an example namespace.
   Returns the config map or nil if the example doesn't define one."
  [example-key]
  (let [ns-sym (resolve-example-ns example-key)
        config-sym (symbol (str ns-sym) "window-config")]
    (when-let [config-var (resolve config-sym)]
      @config-var)))

(defn load-example!
  "Load an example namespace and track it as the current example."
  [example-key]
  (require-example! example-key)
  (reset! state/current-example example-key))

(defn init-example!
  "Call the current example's init function and register default control gestures."
  []
  (when-let [init-fn (example-fn "init")]
    (init-fn))
  ;; Register default control panel gestures (FPS checkbox, etc.)
  (when-let [register-defaults! (requiring-resolve 'app.shell.control-panel/register-default-control-gestures!)]
    (register-defaults!)))

;; ============================================================
;; Shell callbacks (delegating to active example)
;; ============================================================

(defn init
  "Shell init - called once at startup.
   Sets up time source. Does NOT call example init (caller does that)."
  []
  (println "Shell initialized")
  ;; Point all libs to use game-time
  (when-let [time-source (requiring-resolve 'lib.time/time-source)]
    (reset! @time-source #(deref sys/game-time))))

(defn tick
  "Shell tick - called every frame.
   Advances game time and delegates to example tick."
  [dt]
  ;; Advance game time (dt is in seconds, apply time scale)
  (swap! sys/game-time + (* dt @sys/time-scale))
  ;; Check long-press timers in gesture system
  (when-let [check-long-press! (requiring-resolve 'lib.gesture.api/check-long-press!)]
    (check-long-press!))
  ;; Tick all registered animations
  (when-let [tick-fn (requiring-resolve 'lib.anim.registry/tick-all!)]
    (tick-fn))
  ;; Delegate to example tick
  (when-let [tick-fn (example-fn "tick")]
    (tick-fn dt)))

(defn draw
  "Shell draw - called every frame.
   Delegates to example draw, then overlays debug panel."
  [^Canvas canvas width height]
  ;; Clear background
  (.clear canvas (unchecked-int 0xFF222222))
  ;; Delegate to example draw
  (when-let [draw-fn (example-fn "draw")]
    (draw-fn canvas width height))
  ;; Draw debug overlays (FPS display and control panel each check their own visibility)
  (debug-panel/draw-panel canvas width))

(defn handle-key-event!
  "Shell key event handler - delegates to example.
   Returns true if the example consumed the event, false otherwise."
  [{:keys [key pressed? modifiers]}]
  (when pressed?
    (when-let [handle-fn (example-fn "on-key-pressed")]
      (handle-fn key modifiers)
      true)))

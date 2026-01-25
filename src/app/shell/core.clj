(ns app.shell.core
  "Shell lifecycle that wraps examples.

   The shell provides:
   - Example resolution (:playground/ball-spring -> ns symbol)
   - Example switching with cleanup
   - Debug panel overlay

   Examples provide init/tick/draw/cleanup callbacks."
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

(defn switch-example!
  "Switch to a new example. Calls cleanup on old, init on new."
  [example-key]
  ;; Cleanup old example
  (when-let [old-key @state/current-example]
    (when-let [cleanup-fn (example-fn "cleanup")]
      (cleanup-fn))
    ;; Clear gesture targets
    (when-let [clear! (requiring-resolve 'lib.gesture.api/clear-targets!)]
      (clear!)))
  ;; Load and init new example
  (require-example! example-key)
  (reset! state/current-example example-key)
  ;; Call example init
  (when-let [init-fn (example-fn "init")]
    (init-fn)))

;; ============================================================
;; Shell callbacks (delegating to active example)
;; ============================================================

(defn init
  "Shell init - called once at startup.
   Delegates to active example's init."
  []
  (println "Shell initialized")
  ;; Point all libs to use game-time
  (when-let [time-source (requiring-resolve 'lib.time/time-source)]
    (reset! @time-source #(deref sys/game-time)))
  ;; Initialize current example if set
  (when-let [init-fn (example-fn "init")]
    (init-fn)))

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
  ;; Draw debug panel overlay when visible
  (when @state/panel-visible?
    (debug-panel/draw-panel canvas width)))

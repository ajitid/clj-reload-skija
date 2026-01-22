(ns user
  "Development namespace - loaded automatically when REPL starts.

   Usage:
   1. Open the app: (open)
   2. Open another terminal and connect: clj -M:connect
   3. Edit ANY source file (config, controls, core, etc.)
   4. In connected REPL: (reload)
   5. See changes immediately!

   Architecture (clj-reload pattern):
   - Event listener uses (resolve ...) for ALL callbacks
   - Everything reloads except 'user' namespace and defonce values
   - See: https://github.com/tonsky/clj-reload"
  (:require [clj-reload.core :as reload]
            [nrepl.server :as nrepl]))

;; Start nREPL server for hot-reloading (connect from another terminal)
;; Port can be specified via:
;;   - NREPL_PORT env var: NREPL_PORT=7888 clj -M:dev:macos-arm64
;;   - nrepl.port system property: clj -J-Dnrepl.port=7888 -M:dev:macos-arm64
;; Note: These are custom properties read by THIS code, not built-in nREPL features.
;; Default: port 0 (random available port, allows multiple JVM instances)
(defonce nrepl-server
  (let [configured-port (or (some-> (System/getenv "NREPL_PORT") parse-long)
                            (some-> (System/getProperty "nrepl.port") parse-long)
                            0)
        server (nrepl/start-server :port configured-port)
        port (:port server)]
    (println "")
    (println "nREPL server running on port" port)
    (println "Connect: clj -M:connect --port" port)
    (println "")
    server))

;; Initialize clj-reload
;; Only 'user' is excluded - app.core uses resolve for all callbacks so it can reload safely
(reload/init
 {:dirs ["src" "dev"]
  :no-reload '#{user}})

(defn reload
  "Reload all changed namespaces.
   - app.config values (blur, shadow) will update
   - app.state values (sizes) will persist (defonce)"
  []
  (println "Reloading...")
  ;; Set guard BEFORE unload starts (protects against all namespace reloads)
  ;; @(resolve ...) - deref var to get atom, then reset! the atom
  (reset! @(resolve 'app.state/reloading?) true)
  (try
    ;; Unmount all BEFORE reload (so will-unmount runs with OLD mixin code)
    (when-let [reset-fn (resolve 'lib.layout.core/reset-mounted-nodes!)]
      (reset-fn))
    (let [result (reload/reload)]
      ;; Clear error on successful reload
      (reset! @(resolve 'app.state/last-reload-error) nil)
      (println "Reloaded:" (:loaded result))
      result)
    (catch Exception e
      ;; Store compile error so UI can display it
      (reset! @(resolve 'app.state/last-reload-error) e)
      (throw e))  ;; Re-throw for REPL feedback
    (finally
      ;; Always clear guard, even on error
      (reset! @(resolve 'app.state/reloading?) false))))

(defn open
  "Open the application window. For interactive REPL use.
   When window closes, returns to REPL prompt."
  []
  (require 'app.core)
  (try
    ((resolve 'app.core/start-app))
    (finally
      ;; Reset running state so app can be reopened
      (reset! @(resolve 'app.state/running?) false))))

(defn quick-open
  "Open the application window and exit when closed. For quick demos/testing.
   Usage: clj -M:dev:macos-arm64 -e \"(quick-open)\""
  []
  (open)
  (System/exit 0))

(comment
  ;; Quick REPL commands:

  ;; Open the app
  (open)

  ;; After editing config.clj, reload to see changes
  (reload)

  ;; Check current state values (these persist)
  @(resolve 'app.state/circles-x)
  @(resolve 'app.state/circles-y)

  ;; You can also modify persistent state from REPL
  (reset! @(resolve 'app.state/circles-x) 5)
  (reset! @(resolve 'app.state/circles-y) 4)
  )

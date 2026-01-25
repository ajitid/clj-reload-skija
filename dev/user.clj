(ns user
  "Development namespace - loaded automatically when REPL starts.

   Usage:
   1. Open an example: (open :playground/ball-spring)
   2. Open another terminal and connect: clj -M:connect
   3. Edit ANY source file
   4. In connected REPL: (reload)
   5. See changes immediately!

   Architecture (clj-reload pattern):
   - Event listener uses (resolve ...) for ALL callbacks
   - Everything reloads except 'user' namespace and defonce values
   - See: https://github.com/tonsky/clj-reload"
  (:require [clj-reload.core :as reload]
            [nrepl.server :as nrepl]))

;; Start nREPL server for hot-reloading
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
(reload/init
 {:dirs ["src" "dev"]
  :no-reload '#{user}})

(defn reload
  "Reload all changed namespaces."
  []
  (println "Reloading...")
  (reset! @(resolve 'app.state.system/reloading?) true)
  (try
    ;; Dispose Flex effects first
    (when-let [dispose-fn (resolve 'lib.flex.core/dispose-all-effects!)]
      (dispose-fn))
    ;; Unmount all BEFORE reload
    (when-let [reset-fn (resolve 'lib.layout.core/reset-mounted-nodes!)]
      (reset-fn))
    (let [result (reload/reload)]
      (reset! @(resolve 'app.state.system/last-reload-error) nil)
      (println "Reloaded:" (:loaded result))
      result)
    (catch Exception e
      (reset! @(resolve 'app.state.system/last-reload-error) e)
      (throw e))
    (finally
      (reset! @(resolve 'app.state.system/reloading?) false))))

(defn open
  "Open the application window with an example.

   Usage:
     (open :playground/ball-spring)

   Examples are resolved as:
     :playground/ball-spring -> app.projects.playground.ball-spring"
  [example-key]
  (require 'app.core)
  (try
    ((resolve 'app.core/start-app) example-key)
    (finally
      (reset! @(resolve 'app.state.system/running?) false))))

(defn quick-open
  "Open the application window and exit when closed. For quick demos/testing.
   Usage: clj -M:dev:macos-arm64 -e \"(quick-open :playground/ball-spring)\""
  [example-key]
  (open example-key)
  (System/exit 0))

(comment
  ;; Quick REPL commands:

  ;; Open an example
  (open :playground/ball-spring)

  ;; After editing, reload to see changes
  (reload)
  )

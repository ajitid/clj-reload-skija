(ns user
  "Development namespace - loaded automatically when REPL starts.

   Usage:
   1. Open an example: (open :howto/anchor-spring)
   2. Open another terminal and connect: clj -M:connect
   3. Edit ANY source file
   4. In connected REPL: (reload)
   5. See changes immediately!

   Architecture (clj-reload pattern):
   - Event listener uses (resolve ...) for ALL callbacks
   - Everything reloads except 'user' namespace and defonce values
   - See: https://github.com/tonsky/clj-reload"
  (:require [clj-reload.core :as reload]
            [clojure.string :as str]
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
  (when-let [cancel-fn (resolve 'lib.render/cancel-current-frame!)]
    (cancel-fn))
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
     (open :howto/anchor-spring)

   Examples are resolved as:
     :howto/anchor-spring -> app.projects.howto.anchor-spring"
  [example-key]
  (require 'app.core)
  (try
    ((resolve 'app.core/start-app) example-key)
    (finally
      (reset! @(resolve 'app.state.system/running?) false))))

(defn quick-open
  "Open the application window and exit when closed. For quick demos/testing.
   Usage: clj -M:dev:macos-arm64 -e \"(quick-open :howto/anchor-spring)\""
  [example-key]
  (open example-key)
  (System/exit 0))

(defonce watcher (atom nil))

(defn start-watcher!
  "Start auto-reload on .clj file changes. Call (stop-watcher!) to disable."
  []
  (if @watcher
    (println "Watcher already running.")
    (let [watch-fn (requiring-resolve 'nextjournal.beholder/watch)]
      (reset! watcher
        (watch-fn
          (fn [event]
            (when (and (#{:modify :create} (:type event))
                       (str/ends-with? (str (:path event)) ".clj"))
              (reload)))
          "src"))
      (println "Auto-reload: watching src/ for .clj changes"))))

(defn stop-watcher!
  "Stop auto-reload file watcher."
  []
  (when-let [w @watcher]
    (let [stop-fn (requiring-resolve 'nextjournal.beholder/stop)]
      (stop-fn w)
      (reset! watcher nil)
      (println "Watcher stopped."))))

(comment
  ;; Quick REPL commands:

  ;; Open an example
  (open :howto/anchor-spring)

  ;; After editing, reload to see changes
  (reload)

  ;; Auto-reload on file save
  (start-watcher!)
  (stop-watcher!)
  )

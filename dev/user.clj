(ns user
  "Development namespace - loaded automatically when REPL starts.

   Usage:
   1. Start the app: (start)
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
(defonce nrepl-server
  (let [server (nrepl/start-server :port 7888)]
    (println "")
    (println "nREPL server running on port 7888")
    (println "Connect from another terminal: clj -M:connect")
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
    (let [result (reload/reload)]
      (println "Reloaded:" (:loaded result))
      result)
    (finally
      ;; Always clear guard, even on error
      (reset! @(resolve 'app.state/reloading?) false))))

(defn start
  "Start the application."
  []
  (require 'app.core)
  ((resolve 'app.core/start-app)))

(comment
  ;; Quick REPL commands:

  ;; Start the app
  (start)

  ;; After editing config.clj, reload to see changes
  (reload)

  ;; Check current state values (these persist)
  @(resolve 'app.state/circle-radius)
  @(resolve 'app.state/rect-width)
  @(resolve 'app.state/rect-height)

  ;; You can also modify persistent state from REPL
  (reset! (resolve 'app.state/circle-radius) 100)
  (reset! (resolve 'app.state/rect-width) 200)
  )

(ns user
  "Development namespace - loaded automatically when REPL starts.

   Usage:
   1. Start the app: (start)
   2. Open another terminal and connect: clj -M:connect
   3. Edit ANY source file (config, controls, core, etc.)
   4. In connected REPL: (reload)
   5. See changes immediately!

   Other commands:
   - (stop)    - Close window, reset state (can restart)
   - (restart) - stop + reopen window

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

(defn start
  "Start the application."
  []
  (require 'app.core)
  ((resolve 'app.core/start-app)))

(defn stop
  "Stop the application (closes window, resets state, keeps event loop for restart)."
  []
  (when @@(resolve 'app.state/running?)
    (reset! @(resolve 'app.state/running?) false)
    (io.github.humbleui.jwm.App/runOnUIThread
     (fn []
       (when-let [window @@(resolve 'app.state/window)]
         (.close window))))
    ;; Give UI thread time to close window, then reset state
    (Thread/sleep 100)
    ((resolve 'app.state/reset-state!))))

(defn restart
  "Restart the application fresh (closes window, resets state, creates new window)."
  []
  (stop)
  ;; Create new window on UI thread
  (io.github.humbleui.jwm.App/runOnUIThread
   (fn []
     (require 'app.core)
     ((resolve 'app.core/create-window)))))

(comment
  ;; Quick REPL commands:

  ;; Start the app
  (start)

  ;; Stop the app (window closes, can restart)
  (stop)

  ;; Restart the app (closes window and creates new one)
  (restart)

  ;; After editing config.clj, reload to see changes
  (reload)

  ;; Check current state values (these persist)
  @(resolve 'app.state/circles-x)
  @(resolve 'app.state/circles-y)

  ;; You can also modify persistent state from REPL
  (reset! @(resolve 'app.state/circles-x) 5)
  (reset! @(resolve 'app.state/circles-y) 4)
  )

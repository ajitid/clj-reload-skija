(ns user
  "Development namespace - loaded automatically when REPL starts.

   Usage:
   1. Start the app: (start)
   2. Edit src/app/config.clj (change blur/shadow values)
   3. Reload: (reload)
   4. See changes immediately!

   Note: Values in app.state (sizes) persist across reloads.
         Values in app.config (effects) change on reload."
  (:require [clj-reload.core :as reload]))

;; Initialize clj-reload
(reload/init
 {:dirs ["src" "dev"]
  :no-reload '#{user}})  ;; Don't reload the user namespace itself

(defn reload
  "Reload all changed namespaces.
   - app.config values (blur, shadow) will update
   - app.state values (sizes) will persist (defonce)"
  []
  (println "Reloading...")
  (let [result (reload/reload)]
    (println "Reloaded:" (:loaded result))
    result))

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

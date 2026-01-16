(ns app.config
  "Reloadable configuration values.
   These use regular def so they WILL change when you reload.

   Edit these values and call (user/reload) to see changes immediately!

   clj-reload hook functions (optional):
   - before-ns-unload - called before this namespace is unloaded
   - after-ns-reload  - called after this namespace is reloaded
   See: https://github.com/tonsky/clj-reload")

;; Grid circle settings
(def circle-color 0xFFFF69B4)      ;; Hot pink (ARGB format)

;; Grid settings
(def grid-circle-radius 100)       ;; Each circle is 200x200 (radius 100)
(def grid-bg-color 0xFF222222)     ;; Dark background
(def min-circles 1)
(def max-circles 10)

;; Control panel settings (in logical pixels, will be scaled)
(def panel-x 20)
(def panel-y 20)
(def panel-width 280)
(def panel-height 120)
(def panel-bg-color 0xDD333333)
(def panel-text-color 0xFFFFFFFF)
(def slider-track-color 0xFF555555)
(def slider-fill-color 0xFFFF69B4)
(def slider-height 16)
(def slider-width 160)
(def font-size 18)

;; ============================================================
;; clj-reload hooks (optional - for resource cleanup/restart)
;; ============================================================

(defn before-ns-unload
  "Called by clj-reload before this namespace is unloaded.
   Use for cleanup: stop servers, close connections, release resources."
  []
  ;; Example: (when-let [conn @db-connection] (.close conn))
  nil)

(defn after-ns-reload
  "Called by clj-reload after this namespace is reloaded.
   Use for restart: reconnect, reinitialize resources."
  []
  ;; Example: (reset! db-connection (connect-to-db))
  nil)

(ns app.config
  "Reloadable configuration values.
   These use regular def so they WILL change when you reload.

   Edit these values and call (user/reload) to see changes immediately!

   clj-reload hook functions (optional):
   - before-ns-unload - called before this namespace is unloaded
   - after-ns-reload  - called after this namespace is reloaded
   See: https://github.com/tonsky/clj-reload")

;; Drop shadow settings for the pink circle
;; dx, dy = shadow offset in pixels
;; sigma = blur amount (higher = more blur)
(def shadow-dx 0)
(def shadow-dy 0)
(def shadow-sigma 0.0)

;; Blur settings for the green rectangle
;; sigma-x, sigma-y = blur radius (higher = more blur)
(def blur-sigma-x 2.0)
(def blur-sigma-y 2.0)

;; Colors (can also be changed on reload)
(def circle-color 0xFFFF69B4)      ;; Hot pink (ARGB format)
(def shadow-color 0x80000000)      ;; Semi-transparent black
(def rect-color 0xAA1132FF)        ;; Lime green

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

(ns app.state.system
  "System/lifecycle state - plain atoms.

   These are system-level atoms for window management, error handling,
   and game loop state. They don't need reactivity as they're managed
   directly by the window system.

   These persist across hot-reloads via defonce.")

;; ============================================================
;; Window/app lifecycle
;; ============================================================

(defonce window (atom nil))
(defonce running? (atom false))
(defonce reloading? (atom false))
(defonce app-activated? (atom false))
(defonce recording? (atom false))

;; ============================================================
;; Error handling
;; ============================================================

(defonce last-reload-error (atom nil))
(defonce last-runtime-error (atom nil))

;; ============================================================
;; Window configuration
;; ============================================================

(def default-window-config
  "Default window properties. Examples can override any subset via
   a `window-config` var in their namespace."
  {:title          "Skija Demo"
   :width          800
   :height         600
   :resizable?     true
   :always-on-top? false
   :bordered?      true
   :fullscreen?    false
   :opacity        1.0
   :min-size       nil       ;; [w h] or nil
   :max-size       nil       ;; [w h] or nil
   :position       nil})     ;; [x y] or nil (centered)

(defonce window-config (atom default-window-config))

;; ============================================================
;; Game time
;; ============================================================

(defonce game-time (atom 0.0))
(defonce time-scale (atom 1.0))

;; ============================================================
;; Layout tree
;; ============================================================

(defonce current-tree (atom nil))

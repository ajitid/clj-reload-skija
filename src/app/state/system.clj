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

;; ============================================================
;; Error handling
;; ============================================================

(defonce last-reload-error (atom nil))
(defonce last-runtime-error (atom nil))

;; ============================================================
;; Window title
;; ============================================================

(defonce window-title (atom "Skija Demo"))

;; ============================================================
;; Game time
;; ============================================================

(defonce game-time (atom 0.0))
(defonce time-scale (atom 1.0))

;; ============================================================
;; Layout tree
;; ============================================================

(defonce current-tree (atom nil))

(ns app.state
  "Persistent state that survives reloads.
   Uses defonce so values are set only once and persist across clj-reload cycles.")

;; ============================================================
;; App state (persists across reloads)
;; ============================================================

;; Window
(defonce window (atom nil))
(defonce running? (atom false))

;; Layout
(defonce scale (atom 1.0))
(defonce window-width (atom 800))
(defonce window-height (atom 600))
(defonce circles-x (atom 2))
(defonce circles-y (atom 2))
(defonce grid-positions (atom []))

;; UI state
(defonce dragging-slider (atom nil))
(defonce fps (atom 0.0))
(defonce panel-visible? (atom true))

;; Reload state
(defonce reloading? (atom false))
(defonce last-reload-error (atom nil))
(defonce last-runtime-error (atom nil))

;; Game time
(defonce game-time (atom 0.0))
(defonce time-scale (atom 1.0))

;; Demo animation
(defonce demo-decay-x (atom nil))
(defonce demo-circle-x (atom 400.0))
(defonce demo-circle-y (atom 300.0))
(defonce demo-dragging? (atom false))
(defonce demo-anchor-x (atom 400.0))
(defonce demo-anchor-y (atom 300.0))
(defonce demo-last-mouse-x (atom 0.0))
(defonce demo-last-mouse-y (atom 0.0))
(defonce demo-last-mouse-time (atom 0))
(defonce demo-velocity-x (atom 0.0))
(defonce demo-velocity-y (atom 0.0))
(defonce demo-drag-offset-x (atom 0.0))
(defonce demo-position-history (atom []))

(ns app.state
  "Persistent state that survives reloads.
   Uses defonce so values are set only once and persist across clj-reload cycles.")

;; ============================================================
;; Permanent state (not reset on restart)
;; ============================================================

(defonce window (atom nil))
(defonce running? (atom false))

;; ============================================================
;; Resettable state
;; ============================================================

(defonce scale (atom 1.0))
(defonce window-width (atom 800))
(defonce window-height (atom 600))
(defonce circles-x (atom 2))
(defonce circles-y (atom 2))
(defonce dragging-slider (atom nil))
(defonce fps (atom 0.0))
(defonce grid-positions (atom []))
(defonce reloading? (atom false))
(defonce last-reload-error (atom nil))
(defonce last-runtime-error (atom nil))

;; ============================================================
;; Reset function
;; ============================================================

(defn reset-state!
  "Reset resettable state to initial values (for restart)."
  []
  (reset! scale 1.0)
  (reset! window-width 800)
  (reset! window-height 600)
  (reset! circles-x 2)
  (reset! circles-y 2)
  (reset! dragging-slider nil)
  (reset! fps 0.0)
  (reset! grid-positions [])
  (reset! reloading? false)
  (reset! last-reload-error nil)
  (reset! last-runtime-error nil))

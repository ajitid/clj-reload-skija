(ns app.state
  "Persistent state that survives reloads.
   Uses defonce so values are set only once and persist across clj-reload cycles.")

;; Window reference - persists so we don't create multiple windows
(defonce window (atom nil))

;; Display scale factor (for HiDPI support)
(defonce scale (atom 1.0))

;; Window dimensions (logical pixels, for control positioning)
(defonce window-width (atom 800))
(defonce window-height (atom 600))

;; App running state
(defonce running? (atom false))

;; Grid settings - user-adjustable, persist across reloads
(defonce circles-x (atom 3))
(defonce circles-y (atom 2))

;; Slider drag state
(defonce dragging-slider (atom nil))

;; FPS tracking
(defonce fps (atom 0.0))

;; Cached grid positions - recalculated only on resize or grid change
;; Each entry is {:cx :cy :radius} for each circle
(defonce grid-positions (atom []))

;; Reload guard - true while namespaces are being reloaded
;; Used to skip event handling during reload (avoids NPE from unloaded vars)
(defonce reloading? (atom false))

;; Last reload error - stores compile-time errors from (reload)
;; Displayed in UI so users see the actual error, not just "unbound fn"
(defonce last-reload-error (atom nil))

;; Last runtime error - stores runtime errors caught during rendering
;; Used for F2 copy-to-clipboard when no reload error exists
(defonce last-runtime-error (atom nil))

(defn reset-state!
  "Reset state to initial values (for restart)."
  []
  (reset! scale 1.0)
  (reset! window-width 800)
  (reset! window-height 600)
  (reset! circles-x 3)
  (reset! circles-y 2)
  (reset! dragging-slider nil)
  (reset! fps 0.0)
  (reset! grid-positions [])
  (reset! reloading? false)
  (reset! last-reload-error nil)
  (reset! last-runtime-error nil))

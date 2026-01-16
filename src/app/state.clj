(ns app.state
  "Persistent state that survives reloads.
   Uses defonce so values are set only once and persist across clj-reload cycles.")

;; Window reference - persists so we don't create multiple windows
(defonce window (atom nil))

;; Display scale factor (for HiDPI support)
(defonce scale (atom 1.0))

;; App running state
(defonce running? (atom false))

;; Grid settings - user-adjustable, persist across reloads
(defonce circles-x (atom 3))          ;; Number of circles in X direction
(defonce circles-y (atom 2))          ;; Number of circles in Y direction

;; Slider drag state
(defonce dragging-slider (atom nil))  ;; :x or :y when dragging

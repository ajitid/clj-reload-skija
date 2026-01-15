(ns app.state
  "Persistent state that survives reloads.
   Uses defonce so values are set only once and persist across clj-reload cycles.")

;; Circle size - persists across reloads
(defonce circle-radius (atom 80))

;; Rectangle size - persists across reloads
(defonce rect-width (atom 150))
(defonce rect-height (atom 100))

;; Window reference - persists so we don't create multiple windows
(defonce window (atom nil))

;; App running state
(defonce running? (atom false))

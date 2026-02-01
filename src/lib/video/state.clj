(ns lib.video.state
  "Global state for video playback system.")

;; Counter for generating unique source IDs
(defonce source-counter (atom 0))

;; Map of source-id -> source implementation (decoder)
(defonce sources (atom {}))

;; Map of source-id -> metadata (hwaccel info, path, etc.)
(defonce source-meta (atom {}))

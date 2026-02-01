(ns lib.video.state
  "Global state for video playback system.")

;; Counter for generating unique source IDs
(defonce source-counter (atom 0))

;; Map of source-id -> source implementation
(defonce sources (atom {}))

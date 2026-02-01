(ns lib.audio.state
  "Persistent state for the audio system.
   Uses defonce so state survives hot-reloads.")

;; Registry of all loaded sources
;; {id -> {:clip Clip :path String :looping? boolean :volume float}}
(defonce sources (atom {}))

;; Counter for generating unique source IDs
(defonce source-counter (atom 0))

(ns lib.audio.state
  "Persistent state for the audio system.
   Uses defonce so state survives hot-reloads.")

;; Registry of all loaded sources
;; {id -> {:type :static/:stream :clip Clip :path String :looping? boolean :volume float ...}}
(defonce sources (atom {}))

;; Counter for generating unique source IDs
(defonce source-counter (atom 0))

;; Registry of active streaming threads for cleanup
;; {id -> Thread}
(defonce stream-threads (atom {}))

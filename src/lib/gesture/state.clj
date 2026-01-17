(ns lib.gesture.state
  "Persistent state for the gesture system.
   Uses defonce so state survives hot-reloads.")

;; Layer order: highest priority first (events go to modal before content)
(def layer-order [:modal :overlay :content :background])

;; Registry of all hit targets that can receive gestures
;; {:target-id -> hit-target-def}
(defonce hit-targets (atom {}))

;; The gesture arena - tracks competing recognizers for current pointer
(defonce arena
  (atom {:pointer-id    nil      ;; Active pointer (nil when idle)
         :recognizers   []       ;; Competing recognizers
         :winner        nil      ;; Winning recognizer (nil if undecided)
         :state         :idle    ;; :idle | :tracking | :resolved
         :blocked-layers #{}}))  ;; Layers blocked by modal

;; Recognizer type priorities (higher wins ties)
(def recognizer-priorities
  {:drag       50
   :long-press 40
   :tap        30})

;; Recognizer configs (thresholds, timing)
(def recognizer-configs
  {:drag       {:min-distance 10}          ;; px to start drag
   :long-press {:min-duration 500          ;; ms to trigger
                :max-distance 10}          ;; max movement during hold
   :tap        {:max-distance 10           ;; max movement for tap
                :max-duration 300}})       ;; max ms from down to up

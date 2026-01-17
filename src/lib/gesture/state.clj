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
  {:drag       {:min-distance 0}           ;; iOS: UIPanGestureRecognizer starts immediately
   :long-press {:min-duration 500          ;; iOS: minimumPressDuration = 0.5s
                :max-distance 10}          ;; iOS: allowableMovement = 10pt
   :tap        {:max-distance 10}})         ;; iOS: ~10pt tolerance, no duration limit

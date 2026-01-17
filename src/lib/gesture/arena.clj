(ns lib.gesture.arena
  "Gesture arena resolution algorithm.
   Determines the winner among competing recognizers.")

(defn active-recognizers
  "Filter to recognizers that can still win."
  [recognizers]
  (filter :can-win? recognizers))

(defn declared-winners
  "Filter to recognizers that have declared victory."
  [recognizers]
  (filter :wants-to-win? recognizers))

(defn resolve-arena
  "Determine the winner among competing recognizers.

   Resolution rules (Flutter-inspired):
   1. Single declaration → that recognizer wins
   2. Multiple declarations → highest priority wins
   3. Otherwise → nil (still undecided)

   Note: Unlike a naive implementation, we do NOT auto-win when only one
   recognizer is active. In Flutter/iOS, a recognizer must explicitly declare
   victory (wants-to-win? = true) through user action (e.g., movement threshold).
   The 'last one standing' rule only applies when others have explicitly failed,
   not when there was only one recognizer to begin with. Sweep handles forced
   resolution on pointer-up.

   Returns the winning recognizer or nil."
  [recognizers]
  (let [active (active-recognizers recognizers)
        declared (declared-winners active)]
    (cond
      ;; Single declaration wins
      (= 1 (count declared))
      (first declared)

      ;; Multiple declarations: highest priority wins
      (> (count declared) 1)
      (apply max-key :priority declared)

      ;; Still undecided - wait for declaration or sweep
      :else nil)))

(defn sweep-arena
  "Force resolution when pointer up with no declared winner.
   Highest priority among remaining active recognizers wins.

   Returns the winning recognizer or nil."
  [recognizers]
  (let [active (active-recognizers recognizers)]
    (when (seq active)
      (apply max-key :priority active))))

(defn cancel-losers
  "Mark all recognizers except winner as cancelled.
   Returns updated recognizers vector."
  [recognizers winner]
  (mapv (fn [r]
          (if (= (:target-id r) (:target-id winner))
            r
            (-> r
                (assoc :state :cancelled)
                (assoc :can-win? false))))
        recognizers))

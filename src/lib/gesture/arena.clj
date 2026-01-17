(ns lib.gesture.arena
  "Gesture arena resolution algorithm and pure state transitions.

   All functions in this namespace are pure - data in, data out.
   No atom access, no side effects."
  (:require [lib.gesture.recognizers :as recognizers]
            [lib.gesture.hit-test :as hit-test]))

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

   Resolution rules (Flutter-style):
   1. Single declaration → that recognizer wins
   2. Multiple declarations → first in list wins (lowest :priority index)
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

      ;; Multiple declarations: first in list wins (lowest index)
      (> (count declared) 1)
      (apply min-key :priority declared)

      ;; Still undecided - wait for declaration or sweep
      :else nil)))

(defn sweep-arena
  "Force resolution when pointer up with no declared winner.
   First in list (lowest :priority index) among remaining active recognizers wins.

   Returns the winning recognizer or nil."
  [recognizers]
  (let [active (active-recognizers recognizers)]
    (when (seq active)
      (apply min-key :priority active))))

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

;; =============================================================================
;; Pure Arena State Transitions
;; =============================================================================
;; These functions compute the next arena state + effects to execute.
;; Effects are data descriptions, not side effects.

(def ^:private idle-arena
  "Initial/reset arena state."
  {:pointer-id nil
   :recognizers []
   :winner nil
   :state :idle
   :blocked-layers #{}})

(defn- maybe-resolve
  "Try to resolve arena, returning updated arena + effects if winner found."
  [arena]
  (let [{:keys [recognizers winner]} arena]
    (if winner
      {:arena arena :effects []}
      (if-let [new-winner (resolve-arena recognizers)]
        {:arena (assoc arena
                       :winner new-winner
                       :recognizers (cancel-losers recognizers new-winner))
         :effects [{:type :deliver-gesture
                    :recognizer new-winner
                    :event-type (:state new-winner)}]}
        {:arena arena :effects []}))))

(defn arena-on-pointer-down
  "Pure: process pointer down event.

   Arguments:
   - arena: current arena state
   - px, py: pointer position
   - ctx: context for bounds-fn
   - targets: map of target-id -> target
   - time: timestamp in ms

   Returns {:arena new-arena :effects [...]} or nil if no target hit."
  [arena px py ctx targets time]
  (let [blocked (:blocked-layers arena)
        hits (hit-test/hit-test px py ctx targets blocked)]
    (when (seq hits)
      (let [target (:target (first hits))
            recs (recognizers/create-recognizers-for-target target [px py] time)]
        (when (seq recs)
          (let [new-arena (assoc arena
                                 :pointer-id 0
                                 :recognizers recs
                                 :winner nil
                                 :state :tracking)]
            (maybe-resolve new-arena)))))))

(defn arena-on-pointer-move
  "Pure: process pointer move event.

   Returns {:arena new-arena :effects [...]}."
  [arena pos time]
  (let [{:keys [recognizers winner state]} arena]
    (if (not= state :tracking)
      {:arena arena :effects []}
      (if winner
        ;; Winner exists - update it and check for state transitions
        (let [prev-state (:state winner)
              updated (recognizers/update-recognizer-move winner pos time)
              new-arena (assoc arena :winner updated)
              effects (cond-> []
                        ;; Deliver :began when transitioning from :possible
                        (and (= prev-state :possible) (= (:state updated) :began))
                        (conj {:type :deliver-gesture
                               :recognizer updated
                               :event-type :began})
                        ;; Deliver :changed for continued movement
                        (= (:state updated) :changed)
                        (conj {:type :deliver-gesture
                               :recognizer updated
                               :event-type :changed}))]
          {:arena new-arena :effects effects})
        ;; No winner yet - update all recognizers and try to resolve
        (let [updated-recs (mapv #(recognizers/update-recognizer-move % pos time)
                                 recognizers)
              arena-with-recs (assoc arena :recognizers updated-recs)]
          (maybe-resolve arena-with-recs))))))

(defn arena-on-pointer-up
  "Pure: process pointer up event.

   Returns {:arena new-arena :effects [...]}."
  [arena pos time]
  (let [{:keys [recognizers winner state]} arena]
    (if (not= state :tracking)
      {:arena arena :effects []}
      (let [effects
            (if winner
              ;; Deliver end to winner
              (let [updated (recognizers/update-recognizer-up winner pos time)]
                (if (= (:state updated) :ended)
                  [{:type :deliver-gesture :recognizer updated :event-type :ended}]
                  []))
              ;; No winner - update all and sweep
              (let [updated-recs (mapv #(recognizers/update-recognizer-up % pos time)
                                       recognizers)
                    sweep-winner (or (resolve-arena updated-recs)
                                     (sweep-arena updated-recs))]
                (if sweep-winner
                  [{:type :deliver-gesture
                    :recognizer sweep-winner
                    :event-type (:state sweep-winner)}]
                  [])))]
        ;; Reset arena on pointer up, but preserve blocked-layers
        {:arena (assoc idle-arena :blocked-layers (:blocked-layers arena))
         :effects effects}))))

(defn arena-check-time-thresholds
  "Pure: check time-based thresholds (for long-press).

   Returns {:arena new-arena :effects [...]}."
  [arena time]
  (let [{:keys [recognizers winner state]} arena]
    (if (or (not= state :tracking) winner)
      {:arena arena :effects []}
      (let [updated-recs (mapv #(recognizers/check-time-threshold % time)
                               recognizers)
            arena-with-recs (assoc arena :recognizers updated-recs)]
        (maybe-resolve arena-with-recs)))))

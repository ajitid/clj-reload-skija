(ns lib.gesture.recognizers
  "Gesture recognizer implementations.
   Each recognizer tracks pointer events and decides if its gesture occurred."
  (:require [lib.gesture.state :as state]))

(defn distance
  "Euclidean distance between two points."
  [[x1 y1] [x2 y2]]
  (Math/sqrt (+ (Math/pow (- x2 x1) 2)
                (Math/pow (- y2 y1) 2))))

(defn now-ms
  "Current time in milliseconds."
  []
  (System/currentTimeMillis))

;; -----------------------------------------------------------------------------
;; Recognizer Creation
;; -----------------------------------------------------------------------------

(defn create-recognizer
  "Create a new recognizer instance for a target.

   Arguments:
   - type: :drag, :tap, or :long-press
   - target: the hit target that created this recognizer
   - pos: [x y] pointer position
   - time: timestamp in ms"
  [type target pos time]
  (let [config (get state/recognizer-configs type {})
        ;; For drag with min-distance=0, start immediately in :began state
        immediate-drag? (and (= type :drag)
                             (zero? (get config :min-distance 10)))]
    {:type        type
     :target-id   (:id target)
     :target      target
     :state       (if immediate-drag? :began :possible)
     :priority    (get state/recognizer-priorities type 0)
     :config      config
     :start-pos   pos
     :start-time  time
     :current-pos pos
     :can-win?    true
     :wants-to-win? immediate-drag?}))

(defn create-recognizers-for-target
  "Create all recognizers specified by a target."
  [target pos time]
  (mapv #(create-recognizer % target pos time)
        (:gesture-recognizers target)))

;; -----------------------------------------------------------------------------
;; Recognizer Updates
;; -----------------------------------------------------------------------------

(defmulti update-recognizer-move
  "Update recognizer on pointer move. Returns updated recognizer."
  (fn [recognizer _pos _time] (:type recognizer)))

(defmulti update-recognizer-up
  "Update recognizer on pointer up. Returns updated recognizer."
  (fn [recognizer _pos _time] (:type recognizer)))

(defmulti check-time-threshold
  "Check if time-based threshold met (for long-press). Returns updated recognizer."
  (fn [recognizer _time] (:type recognizer)))

;; -----------------------------------------------------------------------------
;; Drag Recognizer
;; -----------------------------------------------------------------------------

(defmethod update-recognizer-move :drag
  [recognizer pos time]
  (let [{:keys [start-pos config state]} recognizer
        {:keys [min-distance]} config
        dist (distance start-pos pos)]
    (-> recognizer
        (assoc :current-pos pos)
        (cond->
          ;; Declare victory when movement exceeds threshold
          (and (= state :possible) (>= dist min-distance))
          (-> (assoc :state :began)
              (assoc :wants-to-win? true))

          ;; Continue tracking after began (transition to changed)
          (#{:began :changed} state)
          (assoc :state :changed)))))

(defmethod update-recognizer-up :drag
  [recognizer pos _time]
  (let [{:keys [state]} recognizer]
    (-> recognizer
        (assoc :current-pos pos)
        (cond->
          ;; End drag if we were dragging
          (#{:began :changed} state)
          (assoc :state :ended)

          ;; Fail if never started
          (= state :possible)
          (-> (assoc :state :failed)
              (assoc :can-win? false))))))

(defmethod check-time-threshold :drag [recognizer _time] recognizer)

;; -----------------------------------------------------------------------------
;; Tap Recognizer
;; -----------------------------------------------------------------------------

(defmethod update-recognizer-move :tap
  [recognizer pos time]
  (let [{:keys [start-pos start-time config]} recognizer
        {:keys [max-distance max-duration]} config
        dist (distance start-pos pos)
        elapsed (- time start-time)]
    (-> recognizer
        (assoc :current-pos pos)
        (cond->
          ;; Fail if moved too far or took too long
          (or (> dist max-distance) (> elapsed max-duration))
          (-> (assoc :state :failed)
              (assoc :can-win? false))))))

(defmethod update-recognizer-up :tap
  [recognizer pos time]
  (let [{:keys [start-pos start-time config state]} recognizer
        {:keys [max-distance max-duration]} config
        dist (distance start-pos pos)
        elapsed (- time start-time)]
    (-> recognizer
        (assoc :current-pos pos)
        (cond->
          ;; Success if within thresholds and not already failed
          (and (= state :possible)
               (<= dist max-distance)
               (<= elapsed max-duration))
          (-> (assoc :state :ended)
              (assoc :wants-to-win? true))

          ;; Already failed
          (= state :failed)
          identity))))

(defmethod check-time-threshold :tap
  [recognizer time]
  (let [{:keys [start-time config state]} recognizer
        {:keys [max-duration]} config
        elapsed (- time start-time)]
    (cond-> recognizer
      ;; Fail if time exceeded while still possible
      (and (= state :possible) (> elapsed max-duration))
      (-> (assoc :state :failed)
          (assoc :can-win? false)))))

;; -----------------------------------------------------------------------------
;; Long-Press Recognizer
;; -----------------------------------------------------------------------------

(defmethod update-recognizer-move :long-press
  [recognizer pos _time]
  (let [{:keys [start-pos config state]} recognizer
        {:keys [max-distance]} config
        dist (distance start-pos pos)]
    (-> recognizer
        (assoc :current-pos pos)
        (cond->
          ;; Fail if moved too far while waiting
          (and (#{:possible :began} state) (> dist max-distance))
          (-> (assoc :state :failed)
              (assoc :can-win? false))))))

(defmethod update-recognizer-up :long-press
  [recognizer pos time]
  (let [{:keys [start-time config state]} recognizer
        {:keys [min-duration]} config
        elapsed (- time start-time)]
    (-> recognizer
        (assoc :current-pos pos)
        (cond->
          ;; Success if held long enough
          (and (#{:began :changed} state) (>= elapsed min-duration))
          (assoc :state :ended)

          ;; Fail if released too early
          (and (= state :possible) (< elapsed min-duration))
          (-> (assoc :state :failed)
              (assoc :can-win? false))))))

(defmethod check-time-threshold :long-press
  [recognizer time]
  (let [{:keys [start-time config state]} recognizer
        {:keys [min-duration]} config
        elapsed (- time start-time)]
    (cond-> recognizer
      ;; Declare victory when duration threshold met
      (and (= state :possible) (>= elapsed min-duration))
      (-> (assoc :state :began)
          (assoc :wants-to-win? true)))))

;; -----------------------------------------------------------------------------
;; Default fallbacks
;; -----------------------------------------------------------------------------

(defmethod update-recognizer-move :default [r _ _] r)
(defmethod update-recognizer-up :default [r _ _] r)
(defmethod check-time-threshold :default [r _] r)

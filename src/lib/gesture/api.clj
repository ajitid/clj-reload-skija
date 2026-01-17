(ns lib.gesture.api
  "Public API for the gesture system."
  (:require [lib.gesture.state :as state]
            [lib.gesture.hit-test :as hit-test]
            [lib.gesture.recognizers :as recognizers]
            [lib.gesture.arena :as arena])
  (:import [io.github.humbleui.jwm EventMouseButton EventMouseMove MouseButton]))

;; -----------------------------------------------------------------------------
;; Target Registration
;; -----------------------------------------------------------------------------

(defn register-target!
  "Register a hit target that can receive gestures.

   Target map:
   {:id         :unique-id          ;; Required: unique identifier
    :layer      :content            ;; :modal | :overlay | :content | :background
    :z-index    10                  ;; Within-layer priority (higher = on top)
    :bounds-fn  (fn [ctx] [x y w h]) ;; Returns bounds given context
    :gesture-recognizers [:drag :tap] ;; Which recognizer types to create
    :handlers   {:on-drag-start fn   ;; Gesture callbacks
                 :on-drag fn
                 :on-drag-end fn
                 :on-tap fn
                 :on-long-press fn}}"
  [target]
  (swap! state/hit-targets assoc (:id target) target))

(defn unregister-target!
  "Remove a hit target."
  [target-id]
  (swap! state/hit-targets dissoc target-id))

(defn clear-targets!
  "Remove all hit targets."
  []
  (reset! state/hit-targets {}))

;; -----------------------------------------------------------------------------
;; Modal System
;; -----------------------------------------------------------------------------

(defn push-modal!
  "Block events to layers below the specified layer.
   For example, (push-modal! :modal) blocks :overlay, :content, :background."
  [modal-layer]
  (let [idx (.indexOf state/layer-order modal-layer)
        blocked (set (drop (inc idx) state/layer-order))]
    (swap! state/arena update :blocked-layers into blocked)))

(defn pop-modal!
  "Remove all modal blocking."
  []
  (swap! state/arena assoc :blocked-layers #{}))

;; -----------------------------------------------------------------------------
;; Gesture Event Creation
;; -----------------------------------------------------------------------------

(defn- make-gesture-event
  "Create a gesture event map to pass to handlers."
  [recognizer event-type]
  (let [{:keys [target-id target start-pos current-pos start-time]} recognizer
        [sx sy] start-pos
        [cx cy] current-pos]
    {:type      event-type
     :target-id target-id
     :pointer   {:x cx :y cy}
     :start     {:x sx :y sy}
     :delta     {:x (- cx sx) :y (- cy sy)}
     :time      (- (recognizers/now-ms) start-time)
     :target    target}))

(defn- deliver-gesture!
  "Call the appropriate handler for a gesture event."
  [recognizer event-type]
  (let [handlers (get-in recognizer [:target :handlers])
        handler-key (case [(:type recognizer) event-type]
                      [:drag :began]   :on-drag-start
                      [:drag :changed] :on-drag
                      [:drag :ended]   :on-drag-end
                      [:tap :ended]    :on-tap
                      [:long-press :began] :on-long-press
                      [:long-press :ended] :on-long-press-end
                      nil)]
    (when-let [handler (get handlers handler-key)]
      (handler (make-gesture-event recognizer event-type)))))

;; -----------------------------------------------------------------------------
;; Arena Management
;; -----------------------------------------------------------------------------

(defn- reset-arena!
  "Reset arena to idle state."
  []
  (swap! state/arena assoc
         :pointer-id nil
         :recognizers []
         :winner nil
         :state :idle))

(defn- try-resolve-and-deliver!
  "Try to resolve arena. If winner found, deliver and update state."
  []
  (let [{:keys [recognizers winner state]} @state/arena]
    (when (and (= state :tracking) (nil? winner))
      (when-let [new-winner (arena/resolve-arena recognizers)]
        (swap! state/arena assoc
               :winner new-winner
               :state :resolved
               :recognizers (arena/cancel-losers recognizers new-winner))
        ;; Deliver the initial gesture event
        (deliver-gesture! new-winner (:state new-winner))))))

;; -----------------------------------------------------------------------------
;; Event Handlers
;; -----------------------------------------------------------------------------

(defn handle-pointer-down
  "Handle pointer down event. Performs hit test and creates recognizers."
  [px py ctx]
  (let [time (recognizers/now-ms)
        hits (hit-test/hit-test px py ctx)]
    (when (seq hits)
      ;; Take topmost target only (like Flutter)
      (let [top-hit (first hits)
            target (:target top-hit)
            recs (recognizers/create-recognizers-for-target target [px py] time)]
        (when (seq recs)
          (swap! state/arena assoc
                 :pointer-id 0  ;; Simple single-pointer for now
                 :recognizers recs
                 :winner nil
                 :state :tracking)
          ;; Try immediate resolution (single recognizer case)
          (try-resolve-and-deliver!))))))

(defn handle-pointer-move
  "Handle pointer move event. Updates recognizers and checks for victory."
  [px py ctx]
  (let [{:keys [recognizers winner state]} @state/arena
        time (recognizers/now-ms)]
    (when (= state :tracking)
      (if winner
        ;; Winner already determined - deliver continued gesture
        (let [updated (recognizers/update-recognizer-move winner [px py] time)]
          (swap! state/arena assoc :winner updated)
          (when (= (:state updated) :changed)
            (deliver-gesture! updated :changed)))
        ;; No winner yet - update all and try to resolve
        (let [updated (mapv #(recognizers/update-recognizer-move % [px py] time)
                            recognizers)]
          (swap! state/arena assoc :recognizers updated)
          (try-resolve-and-deliver!)
          ;; Check if winner emerged and deliver
          (when-let [w (:winner @state/arena)]
            (deliver-gesture! w (:state w))))))))

(defn handle-pointer-up
  "Handle pointer up event. Forces resolution and delivers end gesture."
  [px py ctx]
  (let [{:keys [recognizers winner state]} @state/arena
        time (recognizers/now-ms)]
    (when (= state :tracking)
      (if winner
        ;; Deliver end to winner
        (let [updated (recognizers/update-recognizer-up winner [px py] time)]
          (when (= (:state updated) :ended)
            (deliver-gesture! updated :ended)))
        ;; No winner - update all and sweep
        (let [updated (mapv #(recognizers/update-recognizer-up % [px py] time)
                            recognizers)
              sweep-winner (or (arena/resolve-arena updated)
                               (arena/sweep-arena updated))]
          (when sweep-winner
            (deliver-gesture! sweep-winner (:state sweep-winner)))))
      ;; Reset arena
      (reset-arena!))))

;; -----------------------------------------------------------------------------
;; JWM Event Integration
;; -----------------------------------------------------------------------------

(defn handle-mouse-button
  "Handle JWM EventMouseButton. Entry point from core.clj."
  [^EventMouseButton event ctx]
  (when (= (.getButton event) MouseButton/PRIMARY)
    (let [scale (:scale ctx 1.0)
          px (/ (.getX event) scale)
          py (/ (.getY event) scale)]
      (if (.isPressed event)
        (handle-pointer-down px py ctx)
        (handle-pointer-up px py ctx)))))

(defn handle-mouse-move
  "Handle JWM EventMouseMove. Entry point from core.clj."
  [^EventMouseMove event ctx]
  (let [{:keys [state]} @state/arena]
    (when (= state :tracking)
      (let [scale (:scale ctx 1.0)
            px (/ (.getX event) scale)
            py (/ (.getY event) scale)]
        (handle-pointer-move px py ctx)))))

(defn check-long-press!
  "Check long-press timers. Call from tick loop."
  []
  (let [{:keys [recognizers winner state]} @state/arena
        time (recognizers/now-ms)]
    (when (and (= state :tracking) (nil? winner))
      (let [updated (mapv #(recognizers/check-time-threshold % time) recognizers)]
        (swap! state/arena assoc :recognizers updated)
        (try-resolve-and-deliver!)))))

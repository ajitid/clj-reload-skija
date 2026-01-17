(ns lib.gesture.api
  "Public API for the gesture system.

   Architecture: Thin impure shell over pure core.
   - Entry points (handle-mouse-*) obtain time, read atoms
   - Pure functions in arena.clj compute state transitions
   - Shell applies state changes and executes effects"
  (:require [lib.gesture.state :as state]
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
;; Effect Execution (impure - calls handlers)
;; -----------------------------------------------------------------------------

(defn- make-gesture-event
  "Create a gesture event map to pass to handlers."
  [recognizer event-type time]
  (let [{:keys [target-id target start-pos current-pos start-time]} recognizer
        [sx sy] start-pos
        [cx cy] current-pos]
    {:type      event-type
     :target-id target-id
     :pointer   {:x cx :y cy}
     :start     {:x sx :y sy}
     :delta     {:x (- cx sx) :y (- cy sy)}
     :time      (- time start-time)
     :target    target}))

(defn- gesture-handler-key
  "Map recognizer type + event type to handler key."
  [recognizer-type event-type]
  (case [recognizer-type event-type]
    [:drag :began]       :on-drag-start
    [:drag :changed]     :on-drag
    [:drag :ended]       :on-drag-end
    [:tap :ended]        :on-tap
    [:long-press :began] :on-long-press
    [:long-press :ended] :on-long-press-end
    nil))

(defn- execute-effect!
  "Execute a single effect. Effects are data descriptions of side effects."
  [effect time]
  (case (:type effect)
    :deliver-gesture
    (let [{:keys [recognizer event-type]} effect
          handlers (get-in recognizer [:target :handlers])
          handler-key (gesture-handler-key (:type recognizer) event-type)]
      (when-let [handler (get handlers handler-key)]
        (handler (make-gesture-event recognizer event-type time))))
    ;; Unknown effect type - ignore
    nil))

(defn- execute-effects!
  "Execute a sequence of effects."
  [effects time]
  (doseq [effect effects]
    (execute-effect! effect time)))

;; -----------------------------------------------------------------------------
;; Event Handlers (thin impure shell)
;; -----------------------------------------------------------------------------
;; Pattern: read atoms → call pure fn → write atom → execute effects

(defn- handle-pointer-down
  "Handle pointer down event. Thin shell over pure arena-on-pointer-down."
  [px py ctx time]
  (let [targets @state/hit-targets
        current-arena @state/arena]
    (when-let [{:keys [arena effects]}
               (arena/arena-on-pointer-down current-arena px py ctx targets time)]
      (reset! state/arena arena)
      (execute-effects! effects time))))

(defn- handle-pointer-move
  "Handle pointer move event. Thin shell over pure arena-on-pointer-move."
  [px py time]
  (let [{:keys [arena effects]}
        (arena/arena-on-pointer-move @state/arena [px py] time)]
    (reset! state/arena arena)
    (execute-effects! effects time)))

(defn- handle-pointer-up
  "Handle pointer up event. Thin shell over pure arena-on-pointer-up."
  [px py time]
  (let [{:keys [arena effects]}
        (arena/arena-on-pointer-up @state/arena [px py] time)]
    (reset! state/arena arena)
    (execute-effects! effects time)))

;; -----------------------------------------------------------------------------
;; JWM Event Integration (entry points - obtain time here)
;; -----------------------------------------------------------------------------

(defn handle-mouse-button
  "Handle JWM EventMouseButton. Entry point from core.clj.
   Obtains time once and passes through to pure functions."
  [^EventMouseButton event ctx]
  (when (= (.getButton event) MouseButton/PRIMARY)
    (let [time (System/currentTimeMillis)
          scale (:scale ctx 1.0)
          px (/ (.getX event) scale)
          py (/ (.getY event) scale)]
      (if (.isPressed event)
        (handle-pointer-down px py ctx time)
        (handle-pointer-up px py time)))))

(defn handle-mouse-move
  "Handle JWM EventMouseMove. Entry point from core.clj.
   Obtains time once and passes through to pure functions."
  [^EventMouseMove event ctx]
  (let [{:keys [state]} @state/arena]
    (when (= state :tracking)
      (let [time (System/currentTimeMillis)
            scale (:scale ctx 1.0)
            px (/ (.getX event) scale)
            py (/ (.getY event) scale)]
        (handle-pointer-move px py time)))))

(defn check-long-press!
  "Check long-press timers. Call from tick loop.
   Thin shell over pure arena-check-time-thresholds."
  []
  (let [time (System/currentTimeMillis)
        {:keys [arena effects]}
        (arena/arena-check-time-thresholds @state/arena time)]
    (reset! state/arena arena)
    (execute-effects! effects time)))

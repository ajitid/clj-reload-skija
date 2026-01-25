(ns lib.gesture.api
  "Public API for the gesture system.

   Architecture: Thin impure shell over pure core.
   - Entry points (handle-mouse-*) obtain time, read atoms
   - Pure functions in arena.clj compute state transitions
   - Shell applies state changes and executes effects"
  (:require [lib.gesture.state :as state]
            [lib.gesture.arena :as arena]
            [lib.gesture.hit-test :as hit-test]
            [lib.layout.scroll :as scroll]
            [lib.window.events :as e]))

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

(defn- make-pointer-event
  "Create a pointer event map for pointer-down/up handlers."
  [target pos time]
  (let [[px py] pos]
    {:type :pointer
     :target-id (:id target)
     :pointer {:x px :y py}
     :time time
     :target target}))

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

    :deliver-pointer-down
    (let [{:keys [target pos]} effect
          handlers (:handlers target)]
      (when-let [handler (:on-pointer-down handlers)]
        (handler (make-pointer-event target pos time))))

    :deliver-pointer-up
    (let [{:keys [target pos]} effect
          handlers (:handlers target)]
      (when-let [handler (:on-pointer-up handlers)]
        (handler (make-pointer-event target pos time))))

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
;; SDL3 Event Integration (entry points - obtain time here)
;; -----------------------------------------------------------------------------

(defn handle-mouse-button
  "Handle lib.window EventMouseButton. Entry point from core.clj.
   Obtains time once and passes through to pure functions."
  [event ctx]
  (when (= (:button event) :primary)
    (let [time (System/currentTimeMillis)
          scale (:scale ctx 1.0)
          ;; Coordinates are already in logical pixels from SDL
          px (:x event)
          py (:y event)]
      (if (:pressed? event)
        (handle-pointer-down px py ctx time)
        (handle-pointer-up px py time)))))

(defn handle-mouse-move
  "Handle lib.window EventMouseMove. Entry point from core.clj.
   Obtains time once and passes through to pure functions."
  [event ctx]
  (let [{:keys [state]} @state/arena]
    (when (= state :tracking)
      (let [time (System/currentTimeMillis)
            ;; Coordinates are already in logical pixels from SDL
            px (:x event)
            py (:y event)]
        (handle-pointer-move px py time)))))

;; -----------------------------------------------------------------------------
;; Touch/Finger Event Handlers (multitouch support)
;; -----------------------------------------------------------------------------

(defn handle-finger-down
  "Handle lib.window EventFingerDown. Entry point from core.clj.
   Touch events work the same as mouse for single-finger gestures."
  [event ctx]
  (let [time (System/currentTimeMillis)
        ;; Coordinates are already converted to logical pixels
        px (:x event)
        py (:y event)]
    (handle-pointer-down px py ctx time)))

(defn handle-finger-move
  "Handle lib.window EventFingerMove. Entry point from core.clj."
  [event ctx]
  (let [{:keys [state]} @state/arena]
    (when (= state :tracking)
      (let [time (System/currentTimeMillis)
            px (:x event)
            py (:y event)]
        (handle-pointer-move px py time)))))

(defn handle-finger-up
  "Handle lib.window EventFingerUp. Entry point from core.clj."
  [event ctx]
  (let [time (System/currentTimeMillis)
        px (:x event)
        py (:y event)]
    (handle-pointer-up px py time)))

(defn check-long-press!
  "Check long-press timers. Call from tick loop.
   Thin shell over pure arena-check-time-thresholds."
  []
  (let [time (System/currentTimeMillis)
        {:keys [arena effects]}
        (arena/arena-check-time-thresholds @state/arena time)]
    (reset! state/arena arena)
    (execute-effects! effects time)))

;; -----------------------------------------------------------------------------
;; Mouse Wheel Handler (for scrolling)
;; -----------------------------------------------------------------------------

(defn- normalize-overflow
  "Convert overflow spec to normalized map form."
  [overflow]
  (cond
    (nil? overflow) {:x :visible :y :visible}
    (keyword? overflow) {:x overflow :y overflow}
    (map? overflow) (merge {:x :visible :y :visible} overflow)))

;; SDL modifier masks
(def ^:private SDL_KMOD_SHIFT 0x0003)  ;; SDL_KMOD_LSHIFT | SDL_KMOD_RSHIFT

;; -----------------------------------------------------------------------------
;; Drag-to-Scroll Handler
;; -----------------------------------------------------------------------------

(defn handle-scroll-drag
  "Handle drag gesture for scrolling.

   Use this as an :on-drag handler for scrollable containers.
   Scrolls the container in the opposite direction of the drag.

   Arguments:
   - event: gesture event with :delta {:x :y} and :pointer {:x :y}
   - ctx: context map with :tree (laid-out tree)

   Example usage:
     (register-target! {:id :my-scroll-area
                        :gesture-recognizers [:drag]
                        :handlers {:on-drag handle-scroll-drag}})"
  [event ctx]
  (let [{:keys [delta pointer]} event
        {:keys [tree]} ctx
        {:keys [x y]} pointer]
    (when-let [scrollable (hit-test/find-scrollable-container x y tree)]
      (scroll/scroll-by! (:id scrollable)
        {:x (- (:x delta 0))
         :y (- (:y delta 0))}))))

(defn handle-mouse-wheel
  "Handle mouse wheel scroll events.

   Finds scrollable container under cursor and updates scroll offset.
   Shift+wheel swaps axes for horizontal scrolling.

   Arguments:
   - event: EventMouseWheel with :x :y :dx :dy :modifiers
   - ctx: context map with :tree (laid-out tree) and optional :scale

   Returns: true if scroll was handled, nil otherwise"
  [event ctx]
  (let [{:keys [x y dx dy modifiers]} event
        {:keys [tree]} ctx]
    (when tree
      ;; Find scrollable container under cursor
      (when-let [scrollable (hit-test/find-scrollable-container x y tree)]
        (let [id (:id scrollable)
              overflow (normalize-overflow (get-in scrollable [:bounds :overflow]))
              scroll-x? (= :scroll (:x overflow))
              scroll-y? (= :scroll (:y overflow))
              ;; Shift+wheel: swap axes for horizontal scrolling
              shift? (pos? (bit-and (or modifiers 0) SDL_KMOD_SHIFT))
              [effective-dx effective-dy] (if shift? [dy dx] [dx dy])
              ;; SDL3 wheel: positive dy = scroll up (content moves down = negative scroll offset change)
              ;; We want: scroll wheel down = see content below = increase scroll offset
              ;; So we negate dy for natural scrolling
              scroll-multiplier 20  ;; pixels per wheel tick
              delta {:x (if scroll-x? (* (- effective-dx) scroll-multiplier) 0)
                     :y (if scroll-y? (* (- effective-dy) scroll-multiplier) 0)}]
          (when (or (not= 0 (:x delta)) (not= 0 (:y delta)))
            (scroll/scroll-by! id delta)
            true))))))

;; -----------------------------------------------------------------------------
;; Scrollbar Thumb Dragging
;; -----------------------------------------------------------------------------

(defn- point-in-rect?
  "Check if point (px, py) is inside rect {:x :y :w :h}."
  [px py {:keys [x y w h]}]
  (and (>= px x) (< px (+ x w))
       (>= py y) (< py (+ y h))))

(defn- find-scrollbar-thumb-at
  "Find scrollbar thumb under the given screen coordinates.

   Returns {:container-id :id :axis :x|:y :geometry {...}} or nil.

   Arguments:
   - screen-x, screen-y: mouse position
   - tree: laid-out tree with :bounds"
  [screen-x screen-y tree]
  (when tree
    ;; Walk the tree looking for scrollable containers
    (let [hits (hit-test/hit-test-tree screen-x screen-y tree)]
      (some (fn [node]
              (when-let [id (:id node)]
                (let [bounds (:bounds node)]
                  ;; Check vertical scrollbar first, then horizontal
                  (or (when-let [v-geom (scroll/get-vertical-scrollbar-geometry id bounds)]
                        (when (point-in-rect? screen-x screen-y (:thumb v-geom))
                          {:container-id id :axis :y :geometry v-geom}))
                      (when-let [h-geom (scroll/get-horizontal-scrollbar-geometry id bounds)]
                        (when (point-in-rect? screen-x screen-y (:thumb h-geom))
                          {:container-id id :axis :x :geometry h-geom}))))))
            hits))))

(defn- find-scrollbar-track-at
  "Find scrollbar track (but not thumb) under the given screen coordinates.

   Returns {:container-id :id :axis :x|:y :geometry {...} :click-pos number} or nil.
   :click-pos is the position within the track (0.0 to 1.0).

   Arguments:
   - screen-x, screen-y: mouse position
   - tree: laid-out tree with :bounds"
  [screen-x screen-y tree]
  (when tree
    (let [hits (hit-test/hit-test-tree screen-x screen-y tree)]
      (some (fn [node]
              (when-let [id (:id node)]
                (let [bounds (:bounds node)]
                  ;; Check vertical scrollbar track (but not thumb)
                  (or (when-let [v-geom (scroll/get-vertical-scrollbar-geometry id bounds)]
                        (let [track (:track v-geom)
                              thumb (:thumb v-geom)]
                          (when (and (point-in-rect? screen-x screen-y track)
                                     (not (point-in-rect? screen-x screen-y thumb)))
                            ;; Calculate click-pos to center thumb on click position
                            ;; Maps click to scroll-progress (0-1) accounting for thumb size
                            (let [thumb-height (:h thumb)
                                  track-height (:h track)
                                  click-in-track (- screen-y (:y track))
                                  desired-thumb-top (- click-in-track (/ thumb-height 2))
                                  scroll-range (- track-height thumb-height)
                                  click-pos (if (pos? scroll-range)
                                              (max 0.0 (min 1.0 (/ desired-thumb-top scroll-range)))
                                              0.0)]
                              {:container-id id :axis :y :geometry v-geom :click-pos click-pos}))))
                      ;; Check horizontal scrollbar track (but not thumb)
                      (when-let [h-geom (scroll/get-horizontal-scrollbar-geometry id bounds)]
                        (let [track (:track h-geom)
                              thumb (:thumb h-geom)]
                          (when (and (point-in-rect? screen-x screen-y track)
                                     (not (point-in-rect? screen-x screen-y thumb)))
                            ;; Calculate click-pos to center thumb on click position
                            (let [thumb-width (:w thumb)
                                  track-width (:w track)
                                  click-in-track (- screen-x (:x track))
                                  desired-thumb-left (- click-in-track (/ thumb-width 2))
                                  scroll-range (- track-width thumb-width)
                                  click-pos (if (pos? scroll-range)
                                              (max 0.0 (min 1.0 (/ desired-thumb-left scroll-range)))
                                              0.0)]
                              {:container-id id :axis :x :geometry h-geom :click-pos click-pos}))))))))
            hits))))

(defn handle-scrollbar-mouse-down
  "Handle mouse down for scrollbar interaction.

   Handles two cases:
   1. Click on thumb: start dragging
   2. Click on track (not thumb): jump scroll to that position

   Arguments:
   - event: EventMouseButton with :x :y :pressed?
   - ctx: context map with :tree (laid-out tree)

   Returns: true if scrollbar interaction started, nil otherwise"
  [event ctx]
  (when (:pressed? event)
    (let [{:keys [x y]} event
          {:keys [tree]} ctx]
      ;; First check for thumb click (drag)
      (if-let [{:keys [container-id axis geometry]} (find-scrollbar-thumb-at x y tree)]
        ;; Thumb click: start drag
        (let [current-scroll (scroll/get-scroll container-id)
                start-pos (if (= axis :y) y x)
                start-scroll (if (= axis :y) (:y current-scroll) (:x current-scroll))]
            (reset! state/scrollbar-drag
              {:container-id container-id
               :axis axis
               :start-mouse-pos start-pos
               :start-scroll start-scroll
               :scroll-per-pixel (:scroll-per-pixel geometry)})
          true)
        ;; Not thumb - check for track click (jump)
        (if-let [{:keys [container-id axis geometry click-pos]} (find-scrollbar-track-at x y tree)]
          ;; Track click: jump scroll to position, then start drag from there
          (let [max-scroll (:max-scroll geometry)
                new-scroll (* click-pos max-scroll)
                current (scroll/get-scroll container-id)
                start-pos (if (= axis :y) y x)]
            ;; Jump to the clicked position
            (scroll/set-scroll! container-id
              (if (= axis :y)
                {:x (:x current) :y new-scroll}
                {:x new-scroll :y (:y current)}))
            ;; Start drag state so holding and dragging continues to scroll
            (reset! state/scrollbar-drag
              {:container-id container-id
               :axis axis
               :start-mouse-pos start-pos
               :start-scroll new-scroll
               :scroll-per-pixel (:scroll-per-pixel geometry)})
            true)
          ;; No scrollbar hit
          nil)))))

(defn handle-scrollbar-mouse-move
  "Handle mouse move during scrollbar thumb dragging.

   Arguments:
   - event: EventMouseMove with :x :y

   Returns: true if scroll was updated, nil otherwise"
  [event]
  (when-let [{:keys [container-id axis start-mouse-pos start-scroll scroll-per-pixel]}
             @state/scrollbar-drag]
    (let [{:keys [x y]} event
          current-pos (if (= axis :y) y x)
          delta-pixels (- current-pos start-mouse-pos)
          delta-scroll (* delta-pixels scroll-per-pixel)
          new-scroll (+ start-scroll delta-scroll)
          current (scroll/get-scroll container-id)]
      (scroll/set-scroll! container-id
        (if (= axis :y)
          {:x (:x current) :y new-scroll}
          {:x new-scroll :y (:y current)}))
      true)))

(defn handle-scrollbar-mouse-up
  "Handle mouse up to end scrollbar thumb dragging.

   Returns: true if scrollbar drag was active, nil otherwise"
  []
  (when @state/scrollbar-drag
    (reset! state/scrollbar-drag nil)
    true))

(defn scrollbar-dragging?
  "Check if a scrollbar is currently being dragged."
  []
  (some? @state/scrollbar-drag))

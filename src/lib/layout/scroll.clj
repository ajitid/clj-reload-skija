(ns lib.layout.scroll
  "Scroll state management with Flex reactivity.

   Each scroll container gets Flex sources for scroll position.
   Watchers are now Flex effects that auto-dispose on hot-reload.

   State is owned by this module (defonce atoms) and survives hot-reload.
   User code interacts via public APIs only."
  (:require [lib.flex.core :as flex]))

;; ============================================================
;; State (internal, survives hot-reload)
;; ============================================================

(defonce ^:private scroll-states (atom {}))
;; {:sidebar {:scroll-x (source)
;;            :scroll-y (source)
;;            :viewport {:w 200 :h 600}
;;            :content {:w 200 :h 2000}}}

;; ============================================================
;; Lifecycle (called by mixins)
;; ============================================================

(defn init!
  "Initialize scroll state for a container. Called on mount."
  [id]
  (when-not (contains? @scroll-states id)
    (swap! scroll-states assoc id
      {:scroll-x (flex/source 0)
       :scroll-y (flex/source 0)
       :viewport {:w 0 :h 0}
       :content {:w 0 :h 0}})))

(defn destroy!
  "Remove scroll state for a container. Called on unmount."
  [id]
  (swap! scroll-states dissoc id))

(defn set-dimensions!
  "Update viewport and content dimensions. Called after layout.
   Also clamps scroll position to new valid range."
  [id viewport-bounds content-size]
  (when-let [state (get @scroll-states id)]
    (let [;; Calculate new max scroll
          max-x (max 0 (- (:w content-size) (:w viewport-bounds)))
          max-y (max 0 (- (:h content-size) (:h viewport-bounds)))
          ;; Get current scroll from sources
          current-x @(:scroll-x state)
          current-y @(:scroll-y state)
          ;; Clamp to new range
          clamped-x (max 0 (min current-x max-x))
          clamped-y (max 0 (min current-y max-y))]
      ;; Update metadata
      (swap! scroll-states update id assoc
        :viewport viewport-bounds
        :content content-size)
      ;; Update scroll sources if clamped (flex sources are callable)
      (when (not= current-x clamped-x)
        ((:scroll-x state) clamped-x))
      (when (not= current-y clamped-y)
        ((:scroll-y state) clamped-y)))))

;; ============================================================
;; Read APIs
;; ============================================================

(defn get-scroll
  "Get current scroll offset for container as {:x :y} map."
  [id]
  (if-let [state (get @scroll-states id)]
    {:x @(:scroll-x state) :y @(:scroll-y state)}
    {:x 0 :y 0}))

(defn get-dimensions
  "Get viewport, content, and scroll for container."
  [id]
  (when-let [state (get @scroll-states id)]
    {:viewport (:viewport state)
     :content (:content state)
     :scroll (get-scroll id)}))

(defn get-scrollable-size
  "Calculate max scrollable distance on each axis."
  [id]
  (when-let [state (get @scroll-states id)]
    (let [{:keys [viewport content]} state]
      {:x (max 0 (- (:w content) (:w viewport)))
       :y (max 0 (- (:h content) (:h viewport)))})))

(defn scrollable?
  "Check if container is scrollable on given axis."
  [id axis]
  (let [scrollable-size (get-scrollable-size id)]
    (> (get scrollable-size axis 0) 0)))

(defn get-scroll-progress
  "Get scroll progress on axis (0.0 to 1.0)."
  [id axis]
  (let [scroll (get-scroll id)
        max-scroll (get (get-scrollable-size id) axis 0)]
    (if (> max-scroll 0)
      (/ (get scroll axis 0) max-scroll)
      0.0)))

;; ============================================================
;; Write APIs
;; ============================================================

(defn- clamp-scroll-value
  "Clamp a single scroll value to valid range."
  [value max-value]
  (max 0 (min value max-value)))

(defn set-scroll!
  "Set scroll position (clamped to valid range)."
  [id pos]
  (when-let [state (get @scroll-states id)]
    (let [max-scroll (or (get-scrollable-size id) {:x 0 :y 0})
          new-x (clamp-scroll-value (get pos :x 0) (:x max-scroll))
          new-y (clamp-scroll-value (get pos :y 0) (:y max-scroll))]
      ;; Update Flex sources - watchers notified automatically
      ((:scroll-x state) new-x)
      ((:scroll-y state) new-y))))

(defn scroll-by!
  "Scroll by delta amount."
  [id delta]
  (let [current (get-scroll id)]
    (set-scroll! id {:x (+ (:x current) (get delta :x 0))
                     :y (+ (:y current) (get delta :y 0))})))

(defn scroll-to-top! [id]
  (set-scroll! id {:x 0 :y 0}))

(defn scroll-to-bottom! [id]
  (let [{:keys [x y]} (get-scrollable-size id)]
    (set-scroll! id {:x 0 :y y})))

(defn scroll-to-right! [id]
  (let [{:keys [x y]} (get-scrollable-size id)]
    (set-scroll! id {:x x :y 0})))

;; ============================================================
;; Watchers (now Flex effects)
;; ============================================================

(defn watch!
  "Register a callback for scroll changes using Flex effects.
   Callback signature: (fn [old-pos new-pos] ...)
   Returns: Flex effect (for disposal)

   The effect is automatically tracked for hot-reload disposal."
  [id callback]
  (when-let [state (get @scroll-states id)]
    (let [;; Track previous value for old/new comparison
          prev-pos (atom (get-scroll id))
          ;; Create signal that combines both scroll sources
          scroll-signal (flex/signal
                          {:x @(:scroll-x state)
                           :y @(:scroll-y state)})]
      ;; Create and track effect
      (flex/track-effect!
        (flex/listen scroll-signal
          (fn [new-pos]
            (let [old-pos @prev-pos]
              (reset! prev-pos new-pos)
              (when (not= old-pos new-pos)
                (try
                  (callback old-pos new-pos)
                  (catch Exception e
                    (println "Scroll watcher error:" e)))))))))))

(defn unwatch!
  "Remove a scroll watcher (dispose the Flex effect)."
  [_id effect]
  (when effect
    (flex/untrack-effect! effect)
    (flex/dispose! effect)))

(defn unwatch-all!
  "Remove all watchers for a container.
   Note: With Flex, this is handled automatically via effect disposal."
  [_id]
  ;; No-op with Flex - effects are disposed via the lifecycle system
  nil)

;; ============================================================
;; Scrollbar Geometry (for hit testing and dragging)
;; ============================================================

;; Constants must match render.clj
(def scrollbar-width 6)
(def scrollbar-margin 2)
(def scrollbar-min-thumb 20)

(defn get-vertical-scrollbar-geometry
  "Calculate vertical scrollbar track and thumb geometry.

   Args:
     id - scroll container id
     container-bounds - {:x :y :w :h} of the container

   Returns:
     {:track {:x :y :w :h}
      :thumb {:x :y :w :h}
      :max-scroll number
      :scroll-per-pixel number}  ;; how much scroll per pixel of thumb movement
   Or nil if not scrollable vertically."
  [id container-bounds]
  (when-let [state (get @scroll-states id)]
    (let [{:keys [viewport content]} state
          scroll (get-scroll id)]
      (when (and (pos? (:h viewport)) (> (:h content) (:h viewport)))
        (let [{:keys [x y w h]} container-bounds
              track-height (- h (* 2 scrollbar-margin))
              thumb-ratio (/ (:h viewport) (:h content))
              thumb-height (max scrollbar-min-thumb (* track-height thumb-ratio))
              max-scroll (- (:h content) (:h viewport))
              scroll-progress (if (pos? max-scroll)
                                (/ (:y scroll) max-scroll)
                                0)
              thumb-y (* scroll-progress (- track-height thumb-height))

              track-x (- (+ x w) scrollbar-width scrollbar-margin)
              track-y (+ y scrollbar-margin)

              ;; How much scroll for each pixel of thumb movement
              scroll-per-pixel (if (> (- track-height thumb-height) 0)
                                 (/ max-scroll (- track-height thumb-height))
                                 0)]
          {:track {:x track-x :y track-y :w scrollbar-width :h track-height}
           :thumb {:x track-x :y (+ track-y thumb-y) :w scrollbar-width :h thumb-height}
           :max-scroll max-scroll
           :scroll-per-pixel scroll-per-pixel})))))

(defn get-horizontal-scrollbar-geometry
  "Calculate horizontal scrollbar track and thumb geometry.

   Args:
     id - scroll container id
     container-bounds - {:x :y :w :h} of the container

   Returns:
     {:track {:x :y :w :h}
      :thumb {:x :y :w :h}
      :max-scroll number
      :scroll-per-pixel number}
   Or nil if not scrollable horizontally."
  [id container-bounds]
  (when-let [state (get @scroll-states id)]
    (let [{:keys [viewport content]} state
          scroll (get-scroll id)]
      (when (and (pos? (:w viewport)) (> (:w content) (:w viewport)))
        (let [{:keys [x y w h]} container-bounds
              track-width (- w (* 2 scrollbar-margin))
              thumb-ratio (/ (:w viewport) (:w content))
              thumb-width (max scrollbar-min-thumb (* track-width thumb-ratio))
              max-scroll (- (:w content) (:w viewport))
              scroll-progress (if (pos? max-scroll)
                                (/ (:x scroll) max-scroll)
                                0)
              thumb-x (* scroll-progress (- track-width thumb-width))

              track-x (+ x scrollbar-margin)
              track-y (- (+ y h) scrollbar-width scrollbar-margin)

              scroll-per-pixel (if (> (- track-width thumb-width) 0)
                                 (/ max-scroll (- track-width thumb-width))
                                 0)]
          {:track {:x track-x :y track-y :w track-width :h scrollbar-width}
           :thumb {:x (+ track-x thumb-x) :y track-y :w thumb-width :h scrollbar-width}
           :max-scroll max-scroll
           :scroll-per-pixel scroll-per-pixel})))))

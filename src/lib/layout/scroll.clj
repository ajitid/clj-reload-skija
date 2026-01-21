(ns lib.layout.scroll
  "Scroll state management and APIs.

  State is owned by this module (defonce atoms) and survives hot-reload.
  User code interacts via public APIs only.")

;; ============================================================
;; State (internal, survives hot-reload)
;; ============================================================

(defonce ^:private scroll-states (atom {}))
;; {:sidebar {:scroll {:x 0 :y 150}
;;            :viewport {:w 200 :h 600}
;;            :content {:w 200 :h 2000}}}

(defonce ^:private watchers (atom {}))
;; {:sidebar {watcher-uuid-1 (fn [old new] ...)
;;            watcher-uuid-2 (fn [old new] ...)}}

;; ============================================================
;; Lifecycle (called by mixins)
;; ============================================================

(defn init!
  "Initialize scroll state for a container. Called on mount."
  [id]
  (when-not (contains? @scroll-states id)
    (swap! scroll-states assoc id
      {:scroll {:x 0 :y 0}
       :viewport {:w 0 :h 0}
       :content {:w 0 :h 0}})))

(defn destroy!
  "Remove scroll state for a container. Called on unmount."
  [id]
  (swap! scroll-states dissoc id)
  (swap! watchers dissoc id))

(defn set-dimensions!
  "Update viewport and content dimensions. Called after layout."
  [id viewport-bounds content-size]
  (swap! scroll-states update id
    (fn [state]
      (assoc state
        :viewport viewport-bounds
        :content content-size))))

;; ============================================================
;; Read APIs
;; ============================================================

(defn get-scroll
  "Get current scroll offset for container."
  [id]
  (get-in @scroll-states [id :scroll] {:x 0 :y 0}))

(defn get-dimensions
  "Get viewport, content, and scroll for container."
  [id]
  (get @scroll-states id))

(defn get-scrollable-size
  "Calculate max scrollable distance on each axis."
  [id]
  (when-let [{:keys [viewport content]} (get @scroll-states id)]
    {:x (max 0 (- (:w content) (:w viewport)))
     :y (max 0 (- (:h content) (:h viewport)))}))

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

(defn- clamp-scroll
  "Clamp scroll offset to valid range."
  [scroll max-scroll]
  {:x (max 0 (min (:x scroll) (:x max-scroll)))
   :y (max 0 (min (:y scroll) (:y max-scroll)))})

(defn- notify-watchers!
  "Notify all watchers for a container of scroll change."
  [id old-pos new-pos]
  (when (not= old-pos new-pos)
    (doseq [[_watcher-id callback] (get @watchers id)]
      (try
        (callback old-pos new-pos)
        (catch Exception e
          (println "Scroll watcher error:" e))))))

(defn set-scroll!
  "Set scroll position (clamped to valid range)."
  [id pos]
  (when-let [state (get @scroll-states id)]
    (let [old-pos (:scroll state)
          max-scroll (or (get-scrollable-size id) {:x 0 :y 0})
          new-pos (clamp-scroll pos max-scroll)]
      (swap! scroll-states assoc-in [id :scroll] new-pos)
      (notify-watchers! id old-pos new-pos))))

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
;; Watchers
;; ============================================================

(defn watch!
  "Register a callback for scroll changes.
  Callback signature: (fn [old-pos new-pos] ...)
  Returns: watcher-id for removal"
  [id callback]
  (let [watcher-id (random-uuid)]
    (swap! watchers update id (fnil assoc {}) watcher-id callback)
    watcher-id))

(defn unwatch!
  "Remove a specific watcher."
  [id watcher-id]
  (swap! watchers update id dissoc watcher-id))

(defn unwatch-all!
  "Remove all watchers for a container."
  [id]
  (swap! watchers dissoc id))

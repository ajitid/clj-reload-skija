(ns lib.magic-move
  "Layout animation system - animate bounds changes smoothly.

   Inspired by Apple Keynote's Magic Move feature.
   Tracks element positions across frames and animates enter/exit/move.

   Thin impure shell over pure spring animations.
   Pattern matches lib.anim.registry and lib.gesture.api."
  (:require [lib.anim.spring :as spring]
            [lib.time :as time]
            [clojure.set :as set]))

;; ============================================================
;; State (defonce for hot-reload persistence)
;; ============================================================

(defonce ^:private state
  (atom {:bounds {}       ;; id -> {:x :y :w :h} from last frame
         :animations {}   ;; id -> {:x spring, :y spring, :w spring, :h spring, :opacity spring}
         :exiting {}}))   ;; id -> {:bounds {:x :y :w :h}, :animation {...}}

;; ============================================================
;; Internal: Tree Walking
;; ============================================================

(defn- extract-bounds
  "Walk layout tree, return map of id -> bounds for nodes with :id"
  [tree]
  (loop [nodes [tree]
         result (transient {})]
    (if (empty? nodes)
      (persistent! result)
      (let [node (first nodes)
            rest-nodes (rest nodes)]
        (if (nil? node)
          (recur rest-nodes result)
          (let [result' (if-let [id (:id node)]
                          (assoc! result id (:bounds node))
                          result)
                children (:children node [])]
            (recur (into rest-nodes children) result')))))))

;; ============================================================
;; Internal: Change Detection
;; ============================================================

(def ^:private epsilon 0.5)  ;; tolerance for float comparison

(defn- bounds-changed?
  "Check if bounds changed significantly (> epsilon)"
  [old new]
  (or (> (abs (- (:x old) (:x new))) epsilon)
      (> (abs (- (:y old) (:y new))) epsilon)
      (> (abs (- (:w old) (:w new))) epsilon)
      (> (abs (- (:h old) (:h new))) epsilon)))

(defn- detect-changes
  "Compare previous and current bounds, return {:entered :exited :moved}"
  [prev-bounds curr-bounds]
  (let [prev-ids (set (keys prev-bounds))
        curr-ids (set (keys curr-bounds))]
    {:entered (set/difference curr-ids prev-ids)
     :exited (set/difference prev-ids curr-ids)
     :moved (filter (fn [id]
                      (and (prev-bounds id) (curr-bounds id)
                           (bounds-changed? (prev-bounds id) (curr-bounds id))))
                    curr-ids)}))

;; ============================================================
;; Internal: Animation Creation
;; ============================================================

(defn- make-spring [spring-opts from to]
  (spring/spring-perceptual (assoc spring-opts :from from :to to)))

(defn- animate-move!
  "Create springs for position/size change"
  [id old-bounds new-bounds opts]
  (let [spring-opts (merge {:duration 0.4 :bounce 0.15} (:spring opts))]
    (swap! state assoc-in [:animations id]
      {:x (make-spring spring-opts (:x old-bounds) (:x new-bounds))
       :y (make-spring spring-opts (:y old-bounds) (:y new-bounds))
       :w (make-spring spring-opts (:w old-bounds) (:w new-bounds))
       :h (make-spring spring-opts (:h old-bounds) (:h new-bounds))
       :opacity nil
       :start-time (time/now)})))

(defn- animate-enter!
  "Create springs for element appearing"
  [id bounds opts]
  (when-let [enter-opts (:enter opts)]
    (let [from (:from enter-opts {})
          spring-opts (merge {:duration 0.3 :bounce 0} (:spring enter-opts) (:spring opts))]
      (swap! state assoc-in [:animations id]
        {:x (when (:x from) (make-spring spring-opts (+ (:x bounds) (:x from)) (:x bounds)))
         :y (when (:y from) (make-spring spring-opts (+ (:y bounds) (:y from)) (:y bounds)))
         :w nil
         :h nil
         :opacity (when (contains? from :opacity)
                    (make-spring spring-opts (:opacity from) 1))
         :start-time (time/now)}))))

(defn- animate-exit!
  "Create springs for element disappearing"
  [id bounds opts]
  (when-let [exit-opts (:exit opts)]
    (let [to (:to exit-opts {})
          spring-opts (merge {:duration 0.2 :bounce 0} (:spring exit-opts) (:spring opts))]
      (swap! state update :exiting assoc id
        {:bounds bounds
         :animation
         {:x (when (:x to) (make-spring spring-opts (:x bounds) (+ (:x bounds) (:x to))))
          :y (when (:y to) (make-spring spring-opts (:y bounds) (+ (:y bounds) (:y to))))
          :opacity (when (contains? to :opacity)
                     (make-spring spring-opts 1 (:opacity to)))}
         :start-time (time/now)}))))

(defn- animation-done?
  "Check if all springs in animation are at rest"
  [anim]
  (let [now (time/now)]
    (every? (fn [[k v]]
              (or (nil? v)
                  (= k :start-time)
                  (:at-rest? (spring/spring-at v now))))
            anim)))

(defn- clean-finished-animations!
  "Remove completed move/enter animations"
  []
  (swap! state update :animations
    (fn [anims]
      (into {} (remove (fn [[_ anim]] (animation-done? anim)) anims)))))

(defn- clean-finished-exits!
  "Remove completed exit animations"
  []
  (swap! state update :exiting
    (fn [exiting]
      (into {} (remove (fn [[_ {:keys [animation]}]] (animation-done? animation)) exiting)))))

;; ============================================================
;; Public API
;; ============================================================

(defn update!
  "Process layout tree, detect changes, create animations.
   Call once per frame after layout/layout.

   Options:
     :spring {:duration 0.4 :bounce 0.2}  - default spring params
     :enter {:from {:opacity 0 :y 20}}    - enter animation
     :exit {:to {:opacity 0}}             - exit animation"
  [laid-out-tree opts]
  (let [curr-bounds (extract-bounds laid-out-tree)
        prev-bounds (:bounds @state)
        {:keys [entered exited moved]} (detect-changes prev-bounds curr-bounds)]

    ;; Animate moves (only if not already animating)
    (doseq [id moved]
      (when-not (get-in @state [:animations id])
        (animate-move! id (prev-bounds id) (curr-bounds id) opts)))

    ;; Animate enters
    (doseq [id entered]
      (animate-enter! id (curr-bounds id) opts))

    ;; Animate exits
    (doseq [id exited]
      (when-not (get-in @state [:exiting id])  ;; don't restart exit animation
        (animate-exit! id (prev-bounds id) opts)))

    ;; Clean up finished animations
    (clean-finished-animations!)
    (clean-finished-exits!)

    ;; Update bounds cache
    (swap! state assoc :bounds curr-bounds)))

(defn bounds
  "Get animated bounds for element. Returns raw-bounds if no animation.

   Returns {:x :y :w :h :opacity}"
  [id raw-bounds]
  (if-let [anim (get-in @state [:animations id])]
    (let [now (time/now)]
      {:x (if-let [s (:x anim)] (:value (spring/spring-at s now)) (:x raw-bounds))
       :y (if-let [s (:y anim)] (:value (spring/spring-at s now)) (:y raw-bounds))
       :w (if-let [s (:w anim)] (:value (spring/spring-at s now)) (:w raw-bounds))
       :h (if-let [s (:h anim)] (:value (spring/spring-at s now)) (:h raw-bounds))
       :opacity (if-let [s (:opacity anim)] (:value (spring/spring-at s now)) 1)})
    (assoc raw-bounds :opacity 1)))

(defn exiting-elements
  "Get currently exiting elements for drawing during exit animation.

   Returns seq of {:id :x :y :w :h :opacity}"
  []
  (let [now (time/now)]
    (for [[id {:keys [bounds animation]}] (:exiting @state)]
      {:id id
       :x (if-let [s (:x animation)] (:value (spring/spring-at s now)) (:x bounds))
       :y (if-let [s (:y animation)] (:value (spring/spring-at s now)) (:y bounds))
       :w (:w bounds)
       :h (:h bounds)
       :opacity (if-let [s (:opacity animation)] (:value (spring/spring-at s now)) 1)})))

(defn reset-all!
  "Clear all state. For hot-reload or testing."
  []
  (reset! state {:bounds {} :animations {} :exiting {}}))

(defn debug-state
  "Return current state for debugging."
  []
  @state)

(ns lib.anim.timeline
  "Timeline for sequencing and parallelizing animations.

   Combine tweens, springs, decays, and timers with precise timing control.

   Usage:
     (-> (timeline)
         (add tween1 0)              ;; at 0s
         (add tween2 [:+ 0.1])       ;; 0.1s after timeline end
         (add spring1 :<)            ;; at tween2's end (sequential)
         (label :section2)           ;; mark current position
         (add decay1 [:label :section2])
         (add tween3 :<<))           ;; parallel with decay1

   Positioning syntax:
     0.5            - absolute: at 0.5s
     [:+ 0.1]       - relative: 0.1s after timeline end
     [:- 0.05]      - relative: 0.05s before timeline end
     :<             - at previous item's END (sequential)
     :<<            - at previous item's START (parallel)
     [:< :+ 0.1]    - previous end + 0.1s
     [:<< :+ 0.25]  - previous start + 0.25s
     [:label :name] - at label position
     [:label :name :+ 0.1] - label + 0.1s

   Query state:
     (timeline-now my-timeline)
     ;; => {:elapsed 1.5
     ;;     :progress 0.6
     ;;     :children [{:id :tween1 :value 80 :done? true} ...]
     ;;     :done? false}

   Sources:
     - anime.js timeline API"
  (:require [lib.time :as time]
            [lib.anim.spring :as spring]
            [lib.anim.tween :as tween]
            [lib.anim.timer :as timer]
            [lib.anim.decay :as decay]
            [lib.anim.projection :as projection]))

;; ============================================================
;; Animation Type Detection
;; ============================================================

(defn- animation-type
  "Detect the type of animation based on its keys."
  [anim]
  (cond
    ;; Spring has stiffness
    (:stiffness anim) :spring
    ;; Decay has rate
    (:rate anim) :decay
    ;; Tween has both :from/:to and :easing
    (and (:easing anim) (contains? anim :from) (contains? anim :to)) :tween
    ;; Timer has duration but no :from/:to (or no easing)
    (:duration anim) :timer
    ;; Fallback
    :else :unknown))

;; ============================================================
;; Duration Calculation
;; ============================================================

(defn- tween-total-duration
  "Calculate total duration of a tween including delay and loops."
  [{:keys [duration delay loop loop-delay] :or {delay 0 loop-delay 0}}]
  (let [iterations (cond
                     (true? loop) ##Inf
                     (number? loop) loop
                     :else 1)]
    (if (= iterations ##Inf)
      ##Inf
      (+ delay
         (* iterations duration)
         (* (max 0 (dec iterations)) loop-delay)))))

(defn- timer-total-duration
  "Calculate total duration of a timer including delay and loops."
  [{:keys [duration delay loop loop-delay] :or {delay 0 loop-delay 0}}]
  (let [iterations (cond
                     (true? loop) ##Inf
                     (number? loop) loop
                     :else 1)]
    (if (= iterations ##Inf)
      ##Inf
      (+ delay
         (* iterations duration)
         (* (max 0 (dec iterations)) loop-delay)))))

(defn- child-duration
  "Get the duration of a child animation in seconds."
  [{:keys [animation type]}]
  (case type
    :spring (spring/spring-perceptual-duration animation)
    :decay (decay/decay-perceptual-duration animation)
    :tween (tween-total-duration animation)
    :timer (timer-total-duration animation)
    0.0))

;; ============================================================
;; Position Parsing
;; ============================================================

(defn- parse-position
  "Parse position syntax and return absolute offset in seconds.

   Arguments:
     tl         - the timeline
     position   - the position specifier
     prev-child - the previous child (or nil if none)"
  [tl position prev-child]
  (let [;; Helper to get timeline end
        tl-end (or (:duration tl) 0)
        ;; Helper to get previous animation end/start
        prev-end (when prev-child
                   (+ (:offset prev-child) (child-duration prev-child)))
        prev-start (when prev-child (:offset prev-child))
        ;; Get label position
        get-label (fn [name] (get-in tl [:labels name] 0))]

    (cond
      ;; Number: absolute position (in seconds)
      (number? position)
      position

      ;; :< - previous end (sequential)
      (= position :<)
      (or prev-end tl-end)

      ;; :<< - previous start (parallel)
      (= position :<<)
      (or prev-start tl-end)

      ;; Vector: parse components
      (vector? position)
      (let [[first-elem & rest-elems] position]
        (cond
          ;; [:+ offset] - relative to timeline end
          (= first-elem :+)
          (+ tl-end (first rest-elems))

          ;; [:- offset] - relative to timeline end (subtract)
          (= first-elem :-)
          (- tl-end (first rest-elems))

          ;; [:< :+ offset] - previous end + offset
          (= first-elem :<)
          (let [base (or prev-end tl-end)
                [op offset] rest-elems]
            (case op
              :+ (+ base offset)
              :- (- base offset)
              base))

          ;; [:<< :+ offset] - previous start + offset
          (= first-elem :<<)
          (let [base (or prev-start tl-end)
                [op offset] rest-elems]
            (case op
              :+ (+ base offset)
              :- (- base offset)
              base))

          ;; [:label :name] or [:label :name :+ offset]
          (= first-elem :label)
          (let [[label-name & offset-spec] rest-elems
                base (get-label label-name)]
            (if (seq offset-spec)
              (let [[op offset] offset-spec]
                (case op
                  :+ (+ base offset)
                  :- (- base offset)
                  base))
              base))

          ;; Unknown vector format
          :else tl-end))

      ;; Default: at timeline end
      :else tl-end)))

;; ============================================================
;; Timeline Duration
;; ============================================================

(defn- recalc-duration
  "Recalculate timeline duration based on children."
  [tl]
  (let [children (:children tl)
        max-end (reduce
                  (fn [acc child]
                    (let [child-end (+ (:offset child) (child-duration child))]
                      (max acc child-end)))
                  0.0
                  children)]
    (assoc tl :duration max-end)))

;; ============================================================
;; Public API - Building
;; ============================================================

(defn timeline
  "Create an empty timeline.

   Example:
     (-> (timeline)
         (add tween1 0)
         (add tween2 :<))"
  []
  {:start-time (time/now)
   :labels {}
   :children []
   :duration 0.0})

(defn add
  "Add an animation to the timeline at the specified position.

   Arguments:
     tl        - the timeline
     animation - a tween, spring, decay, or timer
     position  - where to place it (see positioning syntax)
     opts      - optional map with :id key

   Returns: updated timeline

   Example:
     (add tl my-tween [:+ 100])
     (add tl my-spring :< {:id :bounce})"
  ([tl animation position]
   (add tl animation position {}))
  ([tl animation position opts]
   (let [prev-child (last (:children tl))
         offset (parse-position tl position prev-child)
         anim-type (animation-type animation)
         child {:id (:id opts)
                :animation animation
                :type anim-type
                :offset offset}]
     (-> tl
         (update :children conj child)
         recalc-duration))))

(defn label
  "Add a label at the current timeline end.

   Example:
     (-> tl
         (add tween1 0)
         (label :intro)
         (add tween2 [:label :intro]))"
  [tl name]
  (assoc-in tl [:labels name] (:duration tl)))

(defn label-at
  "Add a label at a specific position.

   Example:
     (label-at tl :midpoint 2.5)"
  [tl name position]
  (let [prev-child (last (:children tl))
        offset (parse-position tl position prev-child)]
    (assoc-in tl [:labels name] offset)))

;; ============================================================
;; Query Functions
;; ============================================================

(defn- query-child-state
  "Query the state of a single child at time t (relative to timeline start)."
  [{:keys [id animation type offset]} tl-start-time t]
  (let [;; Calculate absolute time for this child
        child-start-time (+ tl-start-time offset)
        ;; Create a version of the animation with correct start-time
        anim-with-time (assoc animation :start-time child-start-time)
        ;; Query the animation
        state (case type
                :spring (spring/spring-at anim-with-time t)
                :decay (decay/decay-at anim-with-time t)
                :tween (tween/tween-at anim-with-time t)
                :timer (timer/timer-at anim-with-time t)
                {:value 0 :done? true})]
    (assoc state :id id)))

(defn timeline-at
  "Get timeline state at a specific time. Pure function.

   Returns:
     {:elapsed    - seconds since timeline start
      :progress   - 0.0-1.0 overall progress
      :children   - vector of child states [{:id :value :done? ...}]
      :done?      - true if all children complete}"
  [tl t]
  (let [{:keys [start-time duration children]} tl
        elapsed (- t start-time)
        progress (if (or (zero? duration) (= duration ##Inf))
                   (if (pos? elapsed) 1.0 0.0)
                   (min 1.0 (max 0.0 (/ elapsed duration))))
        child-states (mapv #(query-child-state % start-time t) children)
        all-done? (every? :done? child-states)]
    {:elapsed elapsed
     :progress progress
     :children child-states
     :done? all-done?}))

(defn timeline-now
  "Get timeline state at current time. Uses configured time source.

   Returns:
     {:elapsed    - seconds since timeline start
      :progress   - 0.0-1.0 overall progress
      :children   - vector of child states [{:id :value :done? ...}]
      :done?      - true if all children complete}"
  [tl]
  (timeline-at tl (time/now)))

(defn timeline-duration
  "Get the total duration of the timeline in seconds."
  [tl]
  (:duration tl))

;; ============================================================
;; Update Functions
;; ============================================================

(defn timeline-restart
  "Restart timeline from (time/now), keeping all config.
   Returns a new timeline."
  [tl]
  (assoc tl :start-time (time/now)))

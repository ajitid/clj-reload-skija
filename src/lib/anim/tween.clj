(ns lib.anim.tween
  "Time-based tweening with easing, loops, and direction control.

   Tweens are immutable data. Query state at any time with tween-at.

   Usage:
     (def t (tween {:from 0 :to 100 :duration 2.0}))
     (tween-now t)  ;; => {:value 50.0 :progress 0.5 :phase :active :done? false}

   With options:
     (tween {:from 0 :to 100
             :duration 2.0
             :delay 0.5           ;; wait before starting
             :easing :out-cubic   ;; or a function
             :loop 3              ;; repeat 3 times (true = infinite)
             :loop-delay 0.2      ;; pause between loops
             :alternate true      ;; reverse direction each loop
             :reversed false})    ;; start playing backwards

   Sources:
     - anime.js: https://github.com/juliangarnier/anime"
  (:require [lib.time :as time]
            [lib.anim.easing :as easing]))

;; ============================================================
;; Default Values
;; ============================================================

(def defaults
  {:from 0.0
   :to 1.0
   :duration 1.0
   :delay 0.0
   :loop-delay 0.0
   :loop false            ;; false = no loop, true = infinite, number = count
   :alternate false       ;; reverse direction each loop
   :reversed false        ;; start backwards
   :easing :linear})

;; ============================================================
;; Core Algorithm
;; ============================================================

(defn- calculate-tween-state
  "Calculate tween state at a specific time.
   Returns {:value :progress :total-progress :iteration :direction :phase :done?}"
  [{:keys [from to duration delay loop-delay loop alternate reversed easing start-time]} t]
  (let [elapsed (- t start-time)

        ;; Phase 1: Delay
        _ nil
        in-delay? (< elapsed delay)

        ;; Calculate active time (after initial delay)
        active-elapsed (- elapsed delay)

        ;; Single iteration duration + loop delay (except last iteration)
        iteration-duration duration
        iteration-with-delay (+ duration loop-delay)

        ;; Calculate iteration count
        max-iterations (cond
                         (true? loop) ##Inf
                         (number? loop) loop
                         :else 1)

        ;; Which iteration are we in?
        raw-iteration (if (<= active-elapsed 0)
                        0
                        (Math/floor (/ active-elapsed iteration-with-delay)))
        iteration (min raw-iteration (dec max-iterations))

        ;; Time within current iteration
        iteration-start (* iteration iteration-with-delay)
        time-in-iteration (- active-elapsed iteration-start)

        ;; Are we in the loop-delay portion?
        in-loop-delay? (and (> time-in-iteration duration)
                            (< iteration (dec max-iterations)))

        ;; Progress within current iteration (0-1)
        raw-progress (/ (min time-in-iteration duration) duration)
        clamped-progress (max 0.0 (min 1.0 raw-progress))

        ;; Direction (forward or backward based on alternate + reversed)
        base-forward? (if alternate
                        (even? (long iteration))
                        true)
        direction (if reversed
                    (if base-forward? :backward :forward)
                    (if base-forward? :forward :backward))

        ;; Adjust progress for direction
        directed-progress (if (= direction :backward)
                            (- 1.0 clamped-progress)
                            clamped-progress)

        ;; Apply easing
        ease-fn (easing/easing easing)
        eased-progress (ease-fn directed-progress)

        ;; Calculate value
        value (+ from (* eased-progress (- to from)))

        ;; Total progress across all iterations
        total-duration (if (true? loop)
                         ##Inf
                         (+ delay (* max-iterations duration)
                            (* (dec max-iterations) loop-delay)))
        total-progress (if (= total-duration ##Inf)
                         0.0  ;; Can't calculate total progress for infinite
                         (/ (min elapsed total-duration) total-duration))

        ;; Phase and done?
        phase (cond
                in-delay? :delay
                (>= iteration max-iterations) :done
                (>= active-elapsed total-duration) :done
                in-loop-delay? :loop-delay
                :else :active)
        done? (= phase :done)]

    {:value (if done?
              (if (= direction :backward) from to)
              value)
     :progress clamped-progress
     :total-progress (if done? 1.0 total-progress)
     :iteration (long iteration)
     :direction direction
     :phase phase
     :done? done?}))

;; ============================================================
;; Public API
;; ============================================================

(defn tween
  "Create a tween with the given config, merged with defaults.
   :start-time defaults to (time/now) if not provided.

   Options:
     :from       - starting value (default 0.0)
     :to         - ending value (default 1.0)
     :duration   - animation duration in seconds (default 1.0)
     :delay      - initial delay before animation starts (default 0.0)
     :loop       - false (no loop), true (infinite), or number of iterations
     :loop-delay - pause between loop iterations (default 0.0)
     :alternate  - reverse direction each loop (default false)
     :reversed   - start playing backwards (default false)
     :easing     - keyword like :out-cubic, or an easing function

   Example:
     (tween {:from 0 :to 100 :duration 2.0})
     (tween {:from 0 :to 100 :duration 2.0 :easing :out-elastic})"
  [config]
  (merge defaults
         config
         {:start-time (or (:start-time config) (time/now))}))

(defn tween-at
  "Get tween state at a specific time. Pure function.
   Returns {:value :progress :total-progress :iteration :direction :phase :done?}"
  [tween t]
  (calculate-tween-state tween t))

(defn tween-now
  "Get tween state at current time. Uses configured time source.
   Returns {:value :progress :total-progress :iteration :direction :phase :done?}"
  [tween]
  (tween-at tween (time/now)))

(defn tween-restart
  "Restart tween from (time/now), keeping all other config.
   Returns a new tween."
  [tween]
  (assoc tween :start-time (time/now)))

(defn tween-retarget
  "Change the target value mid-animation, starting from current value.
   Preserves easing, duration, loop settings but resets iteration.
   Returns a new tween."
  [tween new-to]
  (let [{:keys [value]} (tween-now tween)]
    (assoc tween
           :from value
           :to new-to
           :start-time (time/now))))

(defn tween-update
  "Update tween config mid-animation.
   Starts from current value with new settings.
   Returns a new tween.

   Example:
     (tween-update t {:duration 0.5 :easing :out-bounce})"
  [tween changes]
  (let [{:keys [value]} (tween-now tween)]
    (merge tween
           changes
           {:from value
            :start-time (time/now)})))

(defn tween-reverse
  "Reverse the tween direction, starting from current value.
   Swaps :from and :to, toggles :reversed.
   Returns a new tween."
  [tween]
  (let [{:keys [value]} (tween-now tween)
        {:keys [from to reversed]} tween]
    (assoc tween
           :from value
           :to from
           :reversed (not reversed)
           :start-time (time/now))))


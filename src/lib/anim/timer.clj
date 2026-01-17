(ns lib.anim.timer
  "Time-based timers with loop, delay, and direction support.

   Timers are immutable data. Query progress at any time with timer-at.

   Basic usage:
     (def t (timer 2.0))  ;; 2 second timer
     (timer-now t)  ;; => {:elapsed 0.5 :progress 0.25 :done? false ...}

   With options:
     (timer 2.0 {:delay 0.5        ;; wait before starting
                 :loop 3           ;; repeat 3 times (true = infinite)
                 :loop-delay 0.2   ;; pause between loops
                 :alternate true   ;; reverse direction each loop
                 :reversed false}) ;; start counting backwards

   Restart:
     (timer-restart t)  ;; restart from now, same config"
  (:require [lib.time :as time]))

;; ============================================================
;; Default Values
;; ============================================================

(def defaults
  {:delay 0.0
   :loop-delay 0.0
   :loop false            ;; false = no loop, true = infinite, number = count
   :alternate false       ;; reverse direction each loop
   :reversed false})      ;; start backwards

;; ============================================================
;; Core Algorithm
;; ============================================================

(defn- calculate-timer-state
  "Calculate timer state at a specific time.
   Returns {:elapsed :progress :iteration :direction :phase :done?}"
  [{:keys [start-time duration delay loop-delay loop alternate reversed]} t]
  (let [elapsed (- t start-time)

        ;; Phase 1: Initial delay
        in-delay? (< elapsed delay)

        ;; Calculate active time (after initial delay)
        active-elapsed (- elapsed delay)

        ;; Single iteration duration + loop delay (except last iteration)
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

        ;; Total progress across all iterations
        total-duration (if (true? loop)
                         ##Inf
                         (+ delay (* max-iterations duration)
                            (* (dec max-iterations) loop-delay)))

        ;; Phase and done?
        phase (cond
                in-delay? :delay
                (>= iteration max-iterations) :done
                (and (not (true? loop))
                     (>= active-elapsed (- total-duration delay))) :done
                in-loop-delay? :loop-delay
                :else :active)
        done? (= phase :done)]

    {:elapsed elapsed
     :progress (if done?
                 (if (= direction :backward) 0.0 1.0)
                 directed-progress)
     :raw-progress clamped-progress  ;; Undirected progress
     :iteration (long iteration)
     :direction direction
     :phase phase
     :done? done?}))

;; ============================================================
;; Public API
;; ============================================================

(defn timer
  "Create a timer with the given duration in seconds.
   Starts at (time/now).

   Options (optional second argument map):
     :delay      - initial delay before timer starts (default 0.0)
     :loop       - false (no loop), true (infinite), or number of iterations
     :loop-delay - pause between loop iterations (default 0.0)
     :alternate  - reverse direction each loop (default false)
     :reversed   - start counting backwards (default false)

   Examples:
     (timer 2.0)                              ;; simple 2 second timer
     (timer 2.0 {:delay 0.5})                 ;; with 0.5s delay
     (timer 1.0 {:loop 3 :alternate true})    ;; ping-pong 3 times"
  ([duration]
   (timer duration {}))
  ([duration opts]
   (merge defaults
          opts
          {:start-time (time/now)
           :duration duration})))

(defn timer-at
  "Get timer state at a specific time. Pure function.
   Returns {:elapsed :progress :raw-progress :iteration :direction :phase :done?}

   - :elapsed      - total time since timer creation
   - :progress     - 0.0-1.0 progress (direction-aware)
   - :raw-progress - 0.0-1.0 progress within iteration (ignores direction)
   - :iteration    - current loop iteration (0-indexed)
   - :direction    - :forward or :backward
   - :phase        - :delay, :active, :loop-delay, or :done
   - :done?        - true if timer has completed"
  [timer t]
  (calculate-timer-state timer t))

(defn timer-now
  "Get timer state at current time. Uses configured time source.
   Returns {:elapsed :progress :raw-progress :iteration :direction :phase :done?}"
  [timer]
  (timer-at timer (time/now)))

(defn timer-restart
  "Restart timer from (time/now), keeping all config.
   Returns a new timer."
  [timer]
  (assoc timer :start-time (time/now)))

(defn timer-with-duration
  "Create a new timer with a different duration, starting now.
   Preserves other config (delay, loop, etc.)."
  [timer new-duration]
  (assoc timer
         :start-time (time/now)
         :duration new-duration))

(defn timer-update
  "Update timer config and restart from now.
   Returns a new timer.

   Example:
     (timer-update t {:loop true :alternate false})"
  [timer changes]
  (merge timer
         changes
         {:start-time (time/now)}))

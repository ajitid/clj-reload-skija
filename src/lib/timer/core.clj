(ns lib.timer.core
  "Simple time-based timers.

   Timers are immutable data. Query progress at any time with timer-at.

   Usage:
     (def t (timer 2.0))  ;; 2 second timer
     (timer-now t)  ;; => {:elapsed 0.5 :progress 0.25 :done? false}

   Restart:
     (timer-restart t)  ;; restart from now, same duration")

;; ============================================================
;; Configurable Time Source
;; ============================================================

(defonce time-source (atom #(/ (System/currentTimeMillis) 1000.0)))

(defn set-time-source!
  "Set the time source function. Call once at app startup to use game-time.
   Example: (set-time-source! #(deref app.state/game-time))"
  [f]
  (reset! time-source f))

(defn now
  "Get current time from configured source."
  []
  (@time-source))

;; ============================================================
;; Public API
;; ============================================================

(defn timer
  "Create a timer with the given duration in seconds.
   Starts at (now)."
  [duration]
  {:start-time (now)
   :duration duration})

(defn timer-at
  "Get timer state at a specific time. Pure function.
   Returns {:elapsed :progress :done?}
   progress is clamped to 0.0-1.0"
  [{:keys [start-time duration]} t]
  (let [elapsed (- t start-time)
        progress (/ (double elapsed) duration)
        clamped-progress (max 0.0 (min 1.0 progress))]
    {:elapsed elapsed
     :progress clamped-progress
     :done? (>= elapsed duration)}))

(defn timer-now
  "Get timer state at current time. Uses configured time source.
   Returns {:elapsed :progress :done?}"
  [timer]
  (timer-at timer (now)))

(defn timer-restart
  "Restart timer from (now), keeping the same duration.
   Returns a new timer."
  [{:keys [duration]}]
  {:start-time (now)
   :duration duration})

(defn timer-with-duration
  "Create a new timer with a different duration, starting now."
  [timer new-duration]
  {:start-time (now)
   :duration new-duration})

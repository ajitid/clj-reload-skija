(ns lib.time
  "Shared time source for all libraries (anim, timer, etc.).

   All libs use this single time source for pause/slow-mo support.
   Default: wall-clock time.

   Usage:
     ;; At app startup, configure to use game-time:
     (reset! lib.time/time-source #(deref app.state/game-time))

     ;; Query current time:
     (lib.time/now)  ;; current time in seconds")

;; ============================================================
;; Shared Time Source
;; ============================================================

(defonce time-source
  "Atom holding the time source function. Returns time in seconds.
   Default: wall-clock time. Reset to use game-time for pause/slow-mo."
  (atom #(/ (System/currentTimeMillis) 1000.0)))

(defn now
  "Get current time from configured source."
  []
  (@time-source))

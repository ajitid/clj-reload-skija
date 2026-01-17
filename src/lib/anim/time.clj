(ns lib.anim.time
  "Shared time source for all animation libraries.

   All animation libs (spring, decay, etc.) use this single time source.
   By default uses wall-clock time. Reset to game-time for pause/slow-mo support.

   Usage:
     ;; At app startup, configure to use game-time:
     (reset! lib.anim.time/time-source #(deref app.state/game-time))

     ;; Or keep default wall-clock time:
     @(lib.anim.time/now)  ;; current time in seconds")

;; ============================================================
;; Shared Time Source
;; ============================================================

(defonce time-source
  "Atom holding the time source function. Returns time in seconds.
   Default: wall-clock time. Reset to use game-time for animation control."
  (atom #(/ (System/currentTimeMillis) 1000.0)))

(defn now
  "Get current time from configured source."
  []
  (@time-source))

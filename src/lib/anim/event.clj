(ns lib.anim.event
  "Event detection helpers for animations.

   Compare previous and current state to detect events.
   All functions return true/false.

   Usage:
     (when (tween-event? prev curr :complete)
       (println \"Done!\"))

     (when (physics-event? prev curr :at-rest)
       (println \"Settled!\"))")

;; ============================================================
;; Tween/Timer Events
;; ============================================================

(defn tween-event?
  "Check if an event occurred between two tween or timer states.

   Events:
     :begin            - animation started (delay -> active)
     :complete         - animation finished (done? became true)
     :loop             - iteration changed
     :direction-change - forward <-> backward (alternate mode)
     :loop-delay-begin - entered pause between loops

   Example:
     (defonce state (atom nil))

     (defn tick [dt]
       (let [prev @state
             curr (tween-now @my-tween)]

         (when (tween-event? prev curr :begin)
           (println \"Started!\"))

         (when (tween-event? prev curr :complete)
           (println \"Done!\"))

         (reset! state curr)))"
  [prev curr event]
  (case event
    :begin            (and (= (:phase prev) :delay)
                           (= (:phase curr) :active))
    :complete         (and (not (:done? prev))
                           (:done? curr))
    :loop             (and (some? (:iteration prev))
                           (not= (:iteration prev) (:iteration curr)))
    :direction-change (and (some? (:direction prev))
                           (not= (:direction prev) (:direction curr)))
    :loop-delay-begin (and (not= (:phase prev) :loop-delay)
                           (= (:phase curr) :loop-delay))
    false))

;; ============================================================
;; Spring/Decay Events
;; ============================================================

(defn physics-event?
  "Check if an event occurred between two spring or decay states.

   Events:
     :at-rest            - came to rest (at-rest? became true)
     :in-motion          - started moving (at-rest? became false)
     :at-perceptual-rest - key motion complete (earlier than :at-rest)
                           spring: perceptual duration elapsed
                           decay: 99% of projected distance traveled

   Example:
     (defonce state (atom nil))

     (defn tick [dt]
       (let [prev @state
             curr (spring-now @my-spring)]

         (when (physics-event? prev curr :at-perceptual-rest)
           (println \"Key motion done!\"))

         (when (physics-event? prev curr :at-rest)
           (println \"Settled!\"))

         (when (physics-event? prev curr :in-motion)
           (println \"Moving again!\"))

         (reset! state curr)))"
  [prev curr event]
  (case event
    :at-rest            (and (not (:at-rest? prev))
                             (:at-rest? curr))
    :in-motion          (and (:at-rest? prev)
                             (not (:at-rest? curr)))
    :at-perceptual-rest (and (not (:at-perceptual-rest? prev))
                             (:at-perceptual-rest? curr))
    false))

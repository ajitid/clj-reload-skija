(ns lib.anim.event
  "Event detection for animations.

   Compare previous and current state to detect events.
   Works with tween, timer, spring, and decay animations.

   Events:
     :begin    - animation started (delay -> active)
     :complete - ALL loops finished (done? became true)
     :loop     - iteration changed

   Usage:
     (when (anim-event? prev curr :complete)
       (println \"Done!\"))")

(defn anim-event?
  "Check if an event occurred between two animation states.
   Works with tween, timer, spring, and decay.

   Events:
     :begin    - animation started (delay -> active)
     :complete - ALL loops finished (done? became true)
     :loop     - iteration changed

   Example:
     (defonce state (atom nil))

     (defn tick [dt]
       (let [prev @state
             curr (spring-now @my-spring)]

         (when (anim-event? prev curr :begin)
           (println \"Started!\"))

         (when (anim-event? prev curr :loop)
           (println \"Iteration ended!\"))

         (when (anim-event? prev curr :complete)
           (println \"All loops done!\"))

         (reset! state curr)))"
  [prev curr event]
  (case event
    :begin    (and (= (:phase prev) :delay)
                   (= (:phase curr) :active))
    :complete (and (not (:done? prev))
                   (:done? curr))
    :loop     (and (some? (:iteration prev))
                   (not= (:iteration prev) (:iteration curr)))
    false))

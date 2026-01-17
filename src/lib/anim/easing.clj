(ns lib.anim.easing
  "Time-based easing functions ported from anime.js.

   All easings are pure functions: (ease-fn t) => eased-t
   where t is progress from 0.0 to 1.0.

   Categories:
   - Linear: linear
   - Power: quad, cubic, quart, quint (in/out/inOut/outIn variants)
   - Trigonometric: sine, circ, expo
   - Physical: back (overshoot), elastic (spring-like), bounce
   - Special: steps, cubic-bezier

   Usage:
     (in-quad 0.5)              ;; => 0.25
     (out-cubic 0.5)            ;; => 0.875
     ((in-back 2.0) 0.5)        ;; parametric with custom overshoot
     ((steps 5) 0.33)           ;; stepped easing
     (ease :out-bounce 0.8)     ;; keyword lookup

   Sources:
     - anime.js: https://github.com/juliangarnier/anime
     - Robert Penner's easing equations
     - https://easings.net/")

;; ============================================================
;; Constants
;; ============================================================

(def ^:private PI Math/PI)
(def ^:private HALF-PI (/ PI 2))
(def ^:private TAU (* PI 2))

;; Default parameters
(def ^:private default-back-overshoot 1.70158)
(def ^:private default-elastic-amplitude 1.0)
(def ^:private default-elastic-period 0.3)

;; ============================================================
;; Linear
;; ============================================================

(defn linear
  "No easing, linear progression."
  [t]
  t)

;; ============================================================
;; Power Easings - Quad (power 2)
;; ============================================================

(defn in-quad
  "Quadratic ease-in: accelerating from zero velocity."
  [t]
  (* t t))

(defn out-quad
  "Quadratic ease-out: decelerating to zero velocity."
  [t]
  (- 1.0 (in-quad (- 1.0 t))))

(defn in-out-quad
  "Quadratic ease-in-out: acceleration until halfway, then deceleration."
  [t]
  (if (< t 0.5)
    (* 2.0 t t)
    (- 1.0 (* 2.0 (- 1.0 t) (- 1.0 t)))))

(defn out-in-quad
  "Quadratic ease-out-in: deceleration until halfway, then acceleration."
  [t]
  (if (< t 0.5)
    (* 0.5 (out-quad (* 2.0 t)))
    (+ 0.5 (* 0.5 (in-quad (- (* 2.0 t) 1.0))))))

;; ============================================================
;; Power Easings - Cubic (power 3)
;; ============================================================

(defn in-cubic
  "Cubic ease-in: accelerating from zero velocity."
  [t]
  (* t t t))

(defn out-cubic
  "Cubic ease-out: decelerating to zero velocity."
  [t]
  (- 1.0 (in-cubic (- 1.0 t))))

(defn in-out-cubic
  "Cubic ease-in-out: acceleration until halfway, then deceleration."
  [t]
  (if (< t 0.5)
    (* 4.0 t t t)
    (- 1.0 (* 4.0 (- 1.0 t) (- 1.0 t) (- 1.0 t)))))

(defn out-in-cubic
  "Cubic ease-out-in: deceleration until halfway, then acceleration."
  [t]
  (if (< t 0.5)
    (* 0.5 (out-cubic (* 2.0 t)))
    (+ 0.5 (* 0.5 (in-cubic (- (* 2.0 t) 1.0))))))

;; ============================================================
;; Power Easings - Quart (power 4)
;; ============================================================

(defn in-quart
  "Quartic ease-in: accelerating from zero velocity."
  [t]
  (* t t t t))

(defn out-quart
  "Quartic ease-out: decelerating to zero velocity."
  [t]
  (- 1.0 (in-quart (- 1.0 t))))

(defn in-out-quart
  "Quartic ease-in-out: acceleration until halfway, then deceleration."
  [t]
  (if (< t 0.5)
    (* 8.0 t t t t)
    (- 1.0 (* 8.0 (- 1.0 t) (- 1.0 t) (- 1.0 t) (- 1.0 t)))))

(defn out-in-quart
  "Quartic ease-out-in: deceleration until halfway, then acceleration."
  [t]
  (if (< t 0.5)
    (* 0.5 (out-quart (* 2.0 t)))
    (+ 0.5 (* 0.5 (in-quart (- (* 2.0 t) 1.0))))))

;; ============================================================
;; Power Easings - Quint (power 5)
;; ============================================================

(defn in-quint
  "Quintic ease-in: accelerating from zero velocity."
  [t]
  (* t t t t t))

(defn out-quint
  "Quintic ease-out: decelerating to zero velocity."
  [t]
  (- 1.0 (in-quint (- 1.0 t))))

(defn in-out-quint
  "Quintic ease-in-out: acceleration until halfway, then deceleration."
  [t]
  (if (< t 0.5)
    (* 16.0 t t t t t)
    (- 1.0 (* 16.0 (- 1.0 t) (- 1.0 t) (- 1.0 t) (- 1.0 t) (- 1.0 t)))))

(defn out-in-quint
  "Quintic ease-out-in: deceleration until halfway, then acceleration."
  [t]
  (if (< t 0.5)
    (* 0.5 (out-quint (* 2.0 t)))
    (+ 0.5 (* 0.5 (in-quint (- (* 2.0 t) 1.0))))))

;; ============================================================
;; Trigonometric - Sine
;; ============================================================

(defn in-sine
  "Sinusoidal ease-in: accelerating from zero velocity."
  [t]
  (- 1.0 (Math/cos (* t HALF-PI))))

(defn out-sine
  "Sinusoidal ease-out: decelerating to zero velocity."
  [t]
  (Math/sin (* t HALF-PI)))

(defn in-out-sine
  "Sinusoidal ease-in-out: acceleration until halfway, then deceleration."
  [t]
  (* 0.5 (- 1.0 (Math/cos (* t PI)))))

(defn out-in-sine
  "Sinusoidal ease-out-in: deceleration until halfway, then acceleration."
  [t]
  (if (< t 0.5)
    (* 0.5 (out-sine (* 2.0 t)))
    (+ 0.5 (* 0.5 (in-sine (- (* 2.0 t) 1.0))))))

;; ============================================================
;; Trigonometric - Circular
;; ============================================================

(defn in-circ
  "Circular ease-in: accelerating from zero velocity."
  [t]
  (- 1.0 (Math/sqrt (- 1.0 (* t t)))))

(defn out-circ
  "Circular ease-out: decelerating to zero velocity."
  [t]
  (Math/sqrt (- 1.0 (* (- t 1.0) (- t 1.0)))))

(defn in-out-circ
  "Circular ease-in-out: acceleration until halfway, then deceleration."
  [t]
  (if (< t 0.5)
    (* 0.5 (- 1.0 (Math/sqrt (- 1.0 (* 4.0 t t)))))
    (* 0.5 (+ 1.0 (Math/sqrt (- 1.0 (* 4.0 (- t 1.0) (- t 1.0))))))))

(defn out-in-circ
  "Circular ease-out-in: deceleration until halfway, then acceleration."
  [t]
  (if (< t 0.5)
    (* 0.5 (out-circ (* 2.0 t)))
    (+ 0.5 (* 0.5 (in-circ (- (* 2.0 t) 1.0))))))

;; ============================================================
;; Exponential
;; ============================================================

(defn in-expo
  "Exponential ease-in: accelerating from zero velocity."
  [t]
  (if (= t 0.0)
    0.0
    (Math/pow 2.0 (* 10.0 (- t 1.0)))))

(defn out-expo
  "Exponential ease-out: decelerating to zero velocity."
  [t]
  (if (= t 1.0)
    1.0
    (- 1.0 (Math/pow 2.0 (* -10.0 t)))))

(defn in-out-expo
  "Exponential ease-in-out: acceleration until halfway, then deceleration."
  [t]
  (cond
    (= t 0.0) 0.0
    (= t 1.0) 1.0
    (< t 0.5) (* 0.5 (Math/pow 2.0 (- (* 20.0 t) 10.0)))
    :else (- 1.0 (* 0.5 (Math/pow 2.0 (- (* -20.0 t) -10.0))))))

(defn out-in-expo
  "Exponential ease-out-in: deceleration until halfway, then acceleration."
  [t]
  (if (< t 0.5)
    (* 0.5 (out-expo (* 2.0 t)))
    (+ 0.5 (* 0.5 (in-expo (- (* 2.0 t) 1.0))))))

;; ============================================================
;; Back (Overshoot)
;; ============================================================

(defn in-back
  "Back ease-in: slightly overshoots then accelerates.
   Optional overshoot parameter (default 1.70158)."
  ([t] (in-back t default-back-overshoot))
  ([t s]
   (let [s1 (+ s 1.0)]
     (* t t (- (* s1 t) s)))))

(defn out-back
  "Back ease-out: decelerates then slightly overshoots.
   Optional overshoot parameter (default 1.70158)."
  ([t] (out-back t default-back-overshoot))
  ([t s]
   (let [t1 (- t 1.0)
         s1 (+ s 1.0)]
     (+ 1.0 (* t1 t1 (+ (* s1 t1) s))))))

(defn in-out-back
  "Back ease-in-out: overshoot on both ends.
   Optional overshoot parameter (default 1.70158)."
  ([t] (in-out-back t default-back-overshoot))
  ([t s]
   (let [s2 (* s 1.525)]
     (if (< t 0.5)
       (* 0.5 (* (* 2.0 t) (* 2.0 t) (- (* (+ s2 1.0) (* 2.0 t)) s2)))
       (let [t1 (- (* 2.0 t) 2.0)]
         (* 0.5 (+ (* t1 t1 (+ (* (+ s2 1.0) t1) s2)) 2.0)))))))

(defn out-in-back
  "Back ease-out-in: overshoot in the middle.
   Optional overshoot parameter (default 1.70158)."
  ([t] (out-in-back t default-back-overshoot))
  ([t s]
   (if (< t 0.5)
     (* 0.5 (out-back (* 2.0 t) s))
     (+ 0.5 (* 0.5 (in-back (- (* 2.0 t) 1.0) s))))))

;; ============================================================
;; Elastic (Spring-like oscillation)
;; ============================================================

(defn in-elastic
  "Elastic ease-in: exponentially decaying sine wave.
   Optional amplitude (default 1.0) and period (default 0.3)."
  ([t] (in-elastic t default-elastic-amplitude default-elastic-period))
  ([t amplitude] (in-elastic t amplitude default-elastic-period))
  ([t amplitude period]
   (cond
     (= t 0.0) 0.0
     (= t 1.0) 1.0
     :else
     (let [;; Ensure amplitude >= 1 for the formula to work correctly
           a (max amplitude 1.0)
           ;; Calculate s (phase offset)
           s (/ (* period (Math/asin (/ 1.0 a))) TAU)
           t1 (- t 1.0)]
       (- (* a
             (Math/pow 2.0 (* 10.0 t1))
             (Math/sin (/ (* (- t1 s) TAU) period))))))))

(defn out-elastic
  "Elastic ease-out: exponentially decaying sine wave.
   Optional amplitude (default 1.0) and period (default 0.3)."
  ([t] (out-elastic t default-elastic-amplitude default-elastic-period))
  ([t amplitude] (out-elastic t amplitude default-elastic-period))
  ([t amplitude period]
   (cond
     (= t 0.0) 0.0
     (= t 1.0) 1.0
     :else
     (let [a (max amplitude 1.0)
           s (/ (* period (Math/asin (/ 1.0 a))) TAU)]
       (+ 1.0
          (* a
             (Math/pow 2.0 (* -10.0 t))
             (Math/sin (/ (* (- t s) TAU) period))))))))

(defn in-out-elastic
  "Elastic ease-in-out: exponentially decaying sine wave on both ends.
   Optional amplitude (default 1.0) and period (default 0.3)."
  ([t] (in-out-elastic t default-elastic-amplitude default-elastic-period))
  ([t amplitude] (in-out-elastic t amplitude default-elastic-period))
  ([t amplitude period]
   (cond
     (= t 0.0) 0.0
     (= t 1.0) 1.0
     :else
     (let [a (max amplitude 1.0)
           p (* period 1.5)  ;; Adjusted period for in-out
           s (/ (* p (Math/asin (/ 1.0 a))) TAU)
           t2 (- (* 2.0 t) 1.0)]
       (if (< t 0.5)
         (* -0.5 a (Math/pow 2.0 (* 10.0 t2))
            (Math/sin (/ (* (- t2 s) TAU) p)))
         (+ 1.0
            (* 0.5 a (Math/pow 2.0 (* -10.0 t2))
               (Math/sin (/ (* (- t2 s) TAU) p)))))))))

(defn out-in-elastic
  "Elastic ease-out-in: decaying sine wave in the middle.
   Optional amplitude (default 1.0) and period (default 0.3)."
  ([t] (out-in-elastic t default-elastic-amplitude default-elastic-period))
  ([t amplitude] (out-in-elastic t amplitude default-elastic-period))
  ([t amplitude period]
   (if (< t 0.5)
     (* 0.5 (out-elastic (* 2.0 t) amplitude period))
     (+ 0.5 (* 0.5 (in-elastic (- (* 2.0 t) 1.0) amplitude period))))))

;; ============================================================
;; Bounce
;; ============================================================

(defn out-bounce
  "Bounce ease-out: exponentially decaying parabolic bounce."
  [t]
  (let [n1 7.5625
        d1 2.75]
    (cond
      (< t (/ 1.0 d1))
      (* n1 t t)

      (< t (/ 2.0 d1))
      (let [t1 (- t (/ 1.5 d1))]
        (+ (* n1 t1 t1) 0.75))

      (< t (/ 2.5 d1))
      (let [t1 (- t (/ 2.25 d1))]
        (+ (* n1 t1 t1) 0.9375))

      :else
      (let [t1 (- t (/ 2.625 d1))]
        (+ (* n1 t1 t1) 0.984375)))))

(defn in-bounce
  "Bounce ease-in: exponentially decaying parabolic bounce."
  [t]
  (- 1.0 (out-bounce (- 1.0 t))))

(defn in-out-bounce
  "Bounce ease-in-out: bounce on both ends."
  [t]
  (if (< t 0.5)
    (* 0.5 (in-bounce (* 2.0 t)))
    (+ 0.5 (* 0.5 (out-bounce (- (* 2.0 t) 1.0))))))

(defn out-in-bounce
  "Bounce ease-out-in: bounce in the middle."
  [t]
  (if (< t 0.5)
    (* 0.5 (out-bounce (* 2.0 t)))
    (+ 0.5 (* 0.5 (in-bounce (- (* 2.0 t) 1.0))))))

;; ============================================================
;; Steps (Discrete)
;; ============================================================

(defn steps
  "Create a stepped easing function.
   n: number of steps (default 10)
   from-start?: if true, jumps happen at start of step (default false, jumps at end)

   Usage:
     ((steps 5) 0.33)      ;; => 0.2 (jumped at 0.2, waiting for 0.4)
     ((steps 5 true) 0.33) ;; => 0.4 (jumped at 0.2 to 0.4)"
  ([n] (steps n false))
  ([n from-start?]
   (fn [t]
     (let [stepped (if from-start?
                     (Math/ceil (* t n))
                     (Math/floor (* t n)))]
       (/ (min stepped n) n)))))

;; ============================================================
;; Cubic Bezier
;; ============================================================

(defn- cubic-bezier-sample
  "Sample a cubic bezier curve at parameter t for a single axis."
  [p0 p1 p2 p3 t]
  (let [t2 (* t t)
        t3 (* t2 t)
        mt (- 1.0 t)
        mt2 (* mt mt)
        mt3 (* mt2 mt)]
    (+ (* mt3 p0)
       (* 3.0 mt2 t p1)
       (* 3.0 mt t2 p2)
       (* t3 p3))))

(defn- cubic-bezier-solve-t
  "Find t for a given x using Newton-Raphson iteration."
  [x1 x2 x epsilon max-iterations]
  (loop [t x
         i 0]
    (if (>= i max-iterations)
      t
      (let [current-x (cubic-bezier-sample 0.0 x1 x2 1.0 t)
            ;; Derivative: d/dt of cubic bezier
            dx (+ (* 3.0 (- 1.0 t) (- 1.0 t) (- x1 0.0))
                  (* 6.0 (- 1.0 t) t (- x2 x1))
                  (* 3.0 t t (- 1.0 x2)))]
        (if (< (Math/abs (- current-x x)) epsilon)
          t
          (if (< (Math/abs dx) 1e-10)
            t  ;; Derivative too small, stop
            (recur (- t (/ (- current-x x) dx)) (inc i))))))))

(defn cubic-bezier
  "Create a cubic bezier easing function.
   Control points: (0,0) -> (x1,y1) -> (x2,y2) -> (1,1)

   Common CSS easings:
     (cubic-bezier 0.25 0.1 0.25 1.0)   ;; ease
     (cubic-bezier 0.42 0 1 1)           ;; ease-in
     (cubic-bezier 0 0 0.58 1)           ;; ease-out
     (cubic-bezier 0.42 0 0.58 1)        ;; ease-in-out"
  [x1 y1 x2 y2]
  (fn [x]
    (cond
      (<= x 0.0) 0.0
      (>= x 1.0) 1.0
      :else
      (let [t (cubic-bezier-solve-t x1 x2 x 1e-6 10)]
        (cubic-bezier-sample 0.0 y1 y2 1.0 t)))))

;; ============================================================
;; CSS Named Easings (using cubic-bezier)
;; ============================================================

(def ease
  "CSS 'ease' timing function."
  (cubic-bezier 0.25 0.1 0.25 1.0))

(def ease-in
  "CSS 'ease-in' timing function."
  (cubic-bezier 0.42 0.0 1.0 1.0))

(def ease-out
  "CSS 'ease-out' timing function."
  (cubic-bezier 0.0 0.0 0.58 1.0))

(def ease-in-out
  "CSS 'ease-in-out' timing function."
  (cubic-bezier 0.42 0.0 0.58 1.0))

;; ============================================================
;; Easing Lookup Map
;; ============================================================

(def easings
  "Map of easing keywords to functions for dynamic lookup."
  {:linear       linear

   ;; Quad
   :in-quad      in-quad
   :out-quad     out-quad
   :in-out-quad  in-out-quad
   :out-in-quad  out-in-quad

   ;; Cubic
   :in-cubic     in-cubic
   :out-cubic    out-cubic
   :in-out-cubic in-out-cubic
   :out-in-cubic out-in-cubic

   ;; Quart
   :in-quart     in-quart
   :out-quart    out-quart
   :in-out-quart in-out-quart
   :out-in-quart out-in-quart

   ;; Quint
   :in-quint     in-quint
   :out-quint    out-quint
   :in-out-quint in-out-quint
   :out-in-quint out-in-quint

   ;; Sine
   :in-sine      in-sine
   :out-sine     out-sine
   :in-out-sine  in-out-sine
   :out-in-sine  out-in-sine

   ;; Circ
   :in-circ      in-circ
   :out-circ     out-circ
   :in-out-circ  in-out-circ
   :out-in-circ  out-in-circ

   ;; Expo
   :in-expo      in-expo
   :out-expo     out-expo
   :in-out-expo  in-out-expo
   :out-in-expo  out-in-expo

   ;; Back (default overshoot)
   :in-back      in-back
   :out-back     out-back
   :in-out-back  in-out-back
   :out-in-back  out-in-back

   ;; Elastic (default amplitude/period)
   :in-elastic      in-elastic
   :out-elastic     out-elastic
   :in-out-elastic  in-out-elastic
   :out-in-elastic  out-in-elastic

   ;; Bounce
   :in-bounce      in-bounce
   :out-bounce     out-bounce
   :in-out-bounce  in-out-bounce
   :out-in-bounce  out-in-bounce

   ;; CSS named
   :ease         ease
   :ease-in      ease-in
   :ease-out     ease-out
   :ease-in-out  ease-in-out})

(defn easing
  "Look up an easing function by keyword or return function as-is.
   Falls back to linear if not found.

   Usage:
     (easing :out-cubic)     ;; => out-cubic function
     (easing out-cubic)      ;; => out-cubic function (pass-through)
     (easing :unknown)       ;; => linear function"
  [ease-or-kw]
  (cond
    (keyword? ease-or-kw) (get easings ease-or-kw linear)
    (fn? ease-or-kw) ease-or-kw
    :else linear))

(defn apply-easing
  "Apply an easing to a progress value.

   Usage:
     (apply-easing :out-cubic 0.5)  ;; => 0.875
     (apply-easing out-cubic 0.5)   ;; => 0.875"
  [ease-or-kw t]
  ((easing ease-or-kw) t))


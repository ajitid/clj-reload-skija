(ns lib.anim.stagger
  "Stagger - compute animation delays for sequenced elements.

   The core idea: apply an easing function to the *distribution* of delays,
   not to each individual animation. This creates organic, cascading effects.

   ## The Math

   For n elements with total stagger time T:
   1. Calculate each element's 'distance' from origin (0 for first, n-1 for last)
   2. Normalize distance to 0-1 range
   3. Apply easing function to redistribute
   4. Scale back to [0, T] range

   ## Easing Effects on Gaps

   The 'gap' is the time between one child starting and the next child starting.

   With linear (no easing):  gaps are equal
   With out-* easings:       gaps DECREASE (accelerating cascade)
   With in-* easings:        gaps INCREASE (decelerating cascade)
   With in-out-* easings:    gaps are small at edges, large in middle

   ## Accelerating Cascade (out-* easings)

   Use :out-quad, :out-cubic, :out-expo etc. when you want:
   - First child appears, then a LONG wait
   - Second child appears after a slightly shorter wait
   - Third child appears after an even shorter wait
   - ...gaps keep decreasing...
   - Last children appear in rapid succession

   This creates an 'accelerating' feel - slow start, fast finish.
   The LAST child has the SMALLEST gap from its predecessor.

   ## Example: 5 elements over 1 second

   | index | linear | out-quad | in-quad |
   |-------|--------|----------|---------|
   |   0   |  0.00  |   0.00   |  0.00   |
   |   1   |  0.25  |   0.44   |  0.06   |
   |   2   |  0.50  |   0.75   |  0.25   |
   |   3   |  0.75  |   0.94   |  0.56   |
   |   4   |  1.00  |   1.00   |  1.00   |

   Gaps for out-quad: 0.44, 0.31, 0.19, 0.06 (decreasing!)
   Gaps for in-quad:  0.06, 0.19, 0.31, 0.44 (increasing!)

   ## Usage

   Basic - generate all delays:
     (stagger-delays 10 1.0)
     ;; => (0.0 0.111 0.222 ... 1.0)

   With easing - decreasing gaps:
     (stagger-delays 10 1.0 {:ease :out-quad})
     ;; => (0.0 0.19 0.36 ... 1.0)  gaps decrease

   From center outward:
     (stagger-delays 10 1.0 {:from :center})
     ;; => center elements at 0, edges at 1.0

   Single delay lookup:
     (stagger-delay 3 10 1.0 {:ease :out-cubic})
     ;; => delay for element 3 of 10

   ## Why Not Use Springs for Stagger?

   Stagger controls WHEN each child STARTS. Springs control HOW each child MOVES.

   Underdamped springs oscillate and overshoot:
   - Position goes: 0 → 1.2 → 0.9 → 1.05 → 1.0 (settles)
   - Using this for delays would cause children to appear OUT OF ORDER
   - Child 3 might start BEFORE child 2!

   For spring-like delay distribution without oscillation risk:
   - Use :out-expo (fast approach, similar to critically damped spring)
   - Use :out-quint (smooth deceleration)

   For bouncy MOTION on each child:
   - Use stagger for delays (with regular easings)
   - Use springs for each child's animation

   Example - stagger + springs with :delay property:
     (let [delays (stagger-delays 10 2.0 {:ease :out-quad})]
       (map-indexed
         (fn [i delay]
           (spring {:from 0 :to 100 :delay delay}))
         delays))

   Or with timeline positioning:
     (let [delays (stagger-delays 10 2.0 {:ease :out-quad})]
       (reduce-kv
         (fn [tl i delay]
           (add tl (spring {:from 0 :to 100}) delay))
         (timeline)
         (vec delays)))

   ## Sources

   - anime.js stagger: https://animejs.com/documentation/utilities/stagger/
   - GSAP stagger ease: https://gsap.com/resources/getting-started/Staggers/
   - Motion stagger: https://motion.dev/docs/stagger"
  (:require [lib.anim.easing :as easing]))

;; ============================================================
;; Distance Calculations
;; ============================================================

(defn- distance-1d
  "Distance from origin index in 1D sequence."
  [index from-index]
  (Math/abs (double (- index from-index))))

(defn- distance-2d
  "Euclidean distance from origin in 2D grid."
  [index from-index cols axis]
  (let [to-x   (mod index cols)
        to-y   (quot index cols)
        from-x (mod from-index cols)
        from-y (quot from-index cols)
        dx     (- to-x from-x)
        dy     (- to-y from-y)]
    (case axis
      :x (Math/abs (double dx))
      :y (Math/abs (double dy))
      (Math/sqrt (+ (* dx dx) (* dy dy))))))

(defn- resolve-from-index
  "Convert :first/:center/:last/number to actual index."
  [from total]
  (case from
    :first  0
    :last   (dec total)
    :center (/ (dec total) 2.0)
    (if (number? from) from 0)))

(defn- resolve-from-index-grid
  "Resolve from-index for grid, handling :center specially."
  [from cols rows]
  (if (= from :center)
    ;; Center of grid - return fractional position
    {:x (/ (dec cols) 2.0)
     :y (/ (dec rows) 2.0)}
    ;; Otherwise convert linear index to grid position
    (let [idx (resolve-from-index from (* cols rows))]
      {:x (mod idx cols)
       :y (quot idx cols)})))

(defn- max-distance-1d
  "Maximum possible distance from origin in 1D."
  [from-index total]
  (max from-index (- (dec total) from-index)))

(defn- max-distance-2d
  "Maximum possible distance from origin in 2D grid."
  [from-pos cols rows axis]
  (let [{:keys [x y]} from-pos
        max-dx (max x (- (dec cols) x))
        max-dy (max y (- (dec rows) y))]
    (case axis
      :x max-dx
      :y max-dy
      (Math/sqrt (+ (* max-dx max-dx) (* max-dy max-dy))))))

;; ============================================================
;; Core Functions
;; ============================================================

(defn stagger-delay
  "Calculate the stagger delay for a single element.

   Arguments:
     index    - element index (0-based)
     total    - total number of elements
     duration - total stagger duration in seconds
     opts     - options map (optional):
       :ease  - easing keyword or fn (default :linear)
       :from  - origin: :first, :center, :last, or index (default :first)
       :start - base delay added to all (default 0)
       :grid  - [cols rows] for 2D grid layout
       :axis  - :x, :y, or nil for radial (grid only)

   Returns: delay in seconds for this element

   Examples:
     (stagger-delay 0 5 1.0)                      ;; => 0.0
     (stagger-delay 2 5 1.0)                      ;; => 0.5
     (stagger-delay 2 5 1.0 {:ease :out-quad})    ;; => 0.75
     (stagger-delay 0 9 1.0 {:from :center})      ;; => 1.0 (edge)
     (stagger-delay 4 9 1.0 {:from :center})      ;; => 0.0 (center)"
  ([index total duration]
   (stagger-delay index total duration {}))
  ([index total duration opts]
   (let [{:keys [ease from start grid axis]
          :or   {ease :linear from :first start 0}} opts
         ease-fn (easing/easing ease)]

     (if grid
       ;; 2D grid stagger
       (let [[cols rows] grid
             from-pos     (resolve-from-index-grid from cols rows)
             from-idx     (+ (* (:y from-pos) cols) (:x from-pos))
             dist         (distance-2d index from-idx cols axis)
             max-dist     (max-distance-2d from-pos cols rows axis)
             normalized   (if (zero? max-dist) 0.0 (/ dist max-dist))
             eased        (ease-fn normalized)]
         (+ start (* eased duration)))

       ;; 1D linear stagger
       (let [from-idx   (resolve-from-index from total)
             dist       (distance-1d index from-idx)
             max-dist   (max-distance-1d from-idx total)
             normalized (if (zero? max-dist) 0.0 (/ dist max-dist))
             eased      (ease-fn normalized)]
         (+ start (* eased duration)))))))

(defn stagger-delays
  "Generate a sequence of stagger delays for n elements.

   Arguments:
     n        - number of elements
     duration - total stagger duration in seconds
     opts     - options map (optional, same as stagger-delay)

   Returns: lazy sequence of delays in seconds

   Examples:
     (stagger-delays 5 1.0)
     ;; => (0.0 0.25 0.5 0.75 1.0)

     (stagger-delays 5 1.0 {:ease :out-quad})
     ;; => (0.0 0.4375 0.75 0.9375 1.0)
     ;; Gaps: 0.44, 0.31, 0.19, 0.06 (decreasing!)

     (stagger-delays 5 1.0 {:ease :in-quad})
     ;; => (0.0 0.0625 0.25 0.5625 1.0)
     ;; Gaps: 0.06, 0.19, 0.31, 0.44 (increasing!)

     (stagger-delays 5 2.0 {:from :center})
     ;; => (2.0 1.0 0.0 1.0 2.0)  center first, edges last

     (stagger-delays 9 1.0 {:grid [3 3] :from :center})
     ;; => radial stagger from grid center"
  ([n duration]
   (stagger-delays n duration {}))
  ([n duration opts]
   (map #(stagger-delay % n duration opts) (range n))))

(defn stagger-gaps
  "Calculate the gaps between consecutive delays.
   Useful for understanding the stagger effect.

   Examples:
     (stagger-gaps 5 1.0 {:ease :out-quad})
     ;; => (0.44 0.31 0.19 0.06)  decreasing gaps

     (stagger-gaps 5 1.0 {:ease :in-quad})
     ;; => (0.06 0.19 0.31 0.44)  increasing gaps"
  ([n duration]
   (stagger-gaps n duration {}))
  ([n duration opts]
   (let [delays (stagger-delays n duration opts)]
     (->> delays
          (partition 2 1)
          (map (fn [[a b]] (- b a)))))))

;; ============================================================
;; Convenience Constructors
;; ============================================================

(defn stagger
  "Create a stagger function for use with map-indexed or timeline.

   Returns: (fn [index total] delay)

   This is useful when you don't know the total count upfront,
   or want to pass the stagger as a parameter.

   Examples:
     (def my-stagger (stagger 1.0 {:ease :out-cubic}))
     (my-stagger 3 10)  ;; => delay for element 3 of 10

     ;; With map-indexed:
     (let [s (stagger 2.0 {:ease :out-quad})]
       (map-indexed (fn [i item]
                      {:item item :delay (s i (count items))})
                    items))

     ;; With timeline:
     (let [s (stagger 1.5 {:ease :out-expo :from :center})]
       (reduce-kv
         (fn [tl i anim]
           (add tl anim (s i n)))
         (timeline)
         animations))"
  ([duration]
   (stagger duration {}))
  ([duration opts]
   (fn [index total]
     (stagger-delay index total duration opts))))

;; ============================================================
;; Grid Helpers
;; ============================================================

(defn stagger-grid
  "Generate a 2D grid of stagger delays.

   Returns a vector of vectors (row-major order).

   Examples:
     (stagger-grid 3 3 1.0 {:from :center})
     ;; => [[1.0  0.71 1.0 ]
     ;;     [0.71 0.0  0.71]
     ;;     [1.0  0.71 1.0 ]]

     (stagger-grid 4 3 0.5 {:from :first :ease :out-quad})
     ;; => delays radiating from top-left"
  ([cols rows duration]
   (stagger-grid cols rows duration {}))
  ([cols rows duration opts]
   (let [opts-with-grid (assoc opts :grid [cols rows])]
     (vec (for [row (range rows)]
            (vec (for [col (range cols)]
                   (stagger-delay (+ (* row cols) col)
                                  (* cols rows)
                                  duration
                                  opts-with-grid))))))))

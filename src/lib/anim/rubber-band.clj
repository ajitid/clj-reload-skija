(ns lib.anim.rubber-band
  "Rubber band effect for boundary resistance.

   Applies resistance when dragging past bounds, like iOS scroll views.
   The further you drag past the boundary, the more resistance you feel.

   Formula (from @chpwn, confirmed by Apple's behavior):
     f(x) = (1 - 1/((x × c / d) + 1)) × d

   Where:
     x = distance past boundary
     c = coefficient (0.55 typical - controls stiffness)
     d = dimension (view size - also the maximum possible offset)

   Properties:
     - As x → 0, f(x) → 0 (no offset at boundary)
     - As x → ∞, f(x) → d (max offset is the dimension)
     - Smaller c = more rigid, larger c = more stretchy

   Usage:
     (rubber-band 100 {:coeff 0.55 :dim 800})  ;; => ~52.4

   Sources:
     - @chpwn tweet: https://twitter.com/chpwn/status/285540192096497664
     - Ilya Lobanov: How UIScrollView works")

;; ============================================================
;; Default Values
;; ============================================================

(def defaults
  {:coeff 0.55    ;; Apple's iOS coefficient (reverse-engineered by @chpwn)
   :dim 800.0})   ;; Default dimension (view size)

;; ============================================================
;; Core Algorithm
;; ============================================================

(defn rubber-band
  "Apply rubber band effect to a distance past boundary.

   Arguments:
     x    - distance past boundary (must be positive)
     opts - map with :coeff and :dim

   Returns the rubber-banded offset (always less than dim).

   Example:
     (rubber-band 100 {:coeff 0.55 :dim 800})  ;; => ~52.4
     (rubber-band 500 {:coeff 0.55 :dim 800})  ;; => ~205.0"
  [x {:keys [coeff dim] :or {coeff 0.55 dim 800.0}}]
  (if (<= x 0)
    0.0
    (* (- 1.0 (/ 1.0 (+ (* x coeff (/ 1.0 dim)) 1.0))) dim)))

(defn rubber-band-clamp
  "Clamp a value within bounds, applying rubber band effect outside.

   Arguments:
     x      - the value to clamp
     min-v  - minimum bound
     max-v  - maximum bound
     opts   - map with :coeff and :dim

   Returns:
     - x unchanged if within bounds
     - rubber-banded offset if outside bounds

   Example:
     (rubber-band-clamp 50 0 100 {:coeff 0.55 :dim 100})   ;; => 50 (in bounds)
     (rubber-band-clamp 150 0 100 {:coeff 0.55 :dim 100})  ;; => ~122 (past max)
     (rubber-band-clamp -50 0 100 {:coeff 0.55 :dim 100})  ;; => ~-22 (past min)"
  [x min-v max-v {:keys [coeff dim] :as opts}]
  (cond
    ;; Within bounds - no effect
    (and (>= x min-v) (<= x max-v))
    x

    ;; Past maximum - apply rubber band above max
    (> x max-v)
    (let [overshoot (- x max-v)]
      (+ max-v (rubber-band overshoot opts)))

    ;; Past minimum - apply rubber band below min
    :else
    (let [overshoot (- min-v x)]
      (- min-v (rubber-band overshoot opts)))))

(defn rubber-band-clamp-2d
  "Clamp a 2D point within rectangular bounds, applying rubber band effect outside.

   Arguments:
     point  - [x y] coordinates
     bounds - {:min-x :max-x :min-y :max-y} content bounds (where scrolling is valid)
     opts   - {:coeff :dims}

   Note on bounds vs dims:
     - bounds = content area limits (e.g., scrollable range of a large image)
     - dims   = viewport size [w h], controls rubber band stiffness
                Larger viewport = more stretch before resistance maxes out

   Example (scrolling a 2000x3000 image in a 400x600 viewport):
     (rubber-band-clamp-2d
       [1700 2500]                        ;; current position (past bounds)
       {:min-x 0 :max-x 1600              ;; content scrolls 0-1600 in X
        :min-y 0 :max-y 2400}             ;; content scrolls 0-2400 in Y
       {:coeff 0.55 :dims [400 600]})     ;; viewport is 400x600

   Returns [clamped-x clamped-y]

   Sources:
     - @chpwn: https://twitter.com/chpwn/status/285540192096497664
     - Ilya Lobanov: https://medium.com/@esskeetit/how-uiscrollview-works-e418adc47c5c"
  [[px py] {:keys [min-x max-x min-y max-y]} {:keys [coeff dims]}]
  (let [[dim-x dim-y] dims]
    [(rubber-band-clamp px min-x max-x {:coeff coeff :dim dim-x})
     (rubber-band-clamp py min-y max-y {:coeff coeff :dim dim-y})]))

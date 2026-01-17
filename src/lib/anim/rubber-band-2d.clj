(ns lib.anim.rubber-band-2d
  "2D rubber band effect for boundary resistance.

   Clamps a 2D point within rectangular bounds, applying rubber band effect outside.

   Usage:
     (rubber-band-clamp-2d
       [1700 2500]                        ;; current position (past bounds)
       {:min-x 0 :max-x 1600              ;; content scrolls 0-1600 in X
        :min-y 0 :max-y 2400}             ;; content scrolls 0-2400 in Y
       {:coeff 0.55 :dims [400 600]})     ;; viewport is 400x600

   Sources:
     - @chpwn: https://twitter.com/chpwn/status/285540192096497664
     - Ilya Lobanov: https://medium.com/@esskeetit/how-uiscrollview-works-e418adc47c5c"
  (:require [lib.anim.rubber-band :as rb]))

;; ============================================================
;; Public API
;; ============================================================

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

   Returns [clamped-x clamped-y]"
  [[px py] {:keys [min-x max-x min-y max-y]} {:keys [coeff dims]}]
  (let [[dim-x dim-y] dims]
    [(rb/rubber-band-clamp px min-x max-x {:coeff coeff :dim dim-x})
     (rb/rubber-band-clamp py min-y max-y {:coeff coeff :dim dim-y})]))

(ns app.state.signals
  "Derived signals that auto-recompute when dependencies change.

   Signals are computed values that depend on sources. When any source
   they depend on changes, they automatically recompute.

   Key pattern: Grid positions auto-recompute when window size or
   grid settings change - no manual recalculation needed."
  (:require [lib.flex.core :as flex]
            [app.state.sources :as src]))

;; ============================================================
;; Grid positions (auto-recomputes on dependency change)
;; ============================================================

(def grid-positions
  "Computed grid positions for all circles.
   Auto-recomputes when circles-x, circles-y, window-width, or window-height change."
  (flex/signal
    (let [nx @src/circles-x
          ny @src/circles-y
          w @src/window-width
          h @src/window-height
          cell-w (/ w nx)
          cell-h (/ h ny)
          radius (min (/ cell-w 2.2) (/ cell-h 2.2) 100)]
      (vec (for [row (range ny)
                 col (range nx)]
             {:cx (+ (* col cell-w) (/ cell-w 2))
              :cy (+ (* row cell-h) (/ cell-h 2))
              :radius radius})))))

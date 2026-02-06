(ns lib.graphics.curves
  "Curve interpolation algorithms — Natural Cubic Spline, Hobby, Catmull-Rom.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern).

   ## Quick Start

   ```clojure
   (require '[lib.graphics.curves :as curves])

   ;; All take [[x y] ...] or [Vec2 ...] (min 2 points), return Skija Path
   (curves/natural-cubic-spline points)         ;; C2 continuous
   (curves/hobby-curve points)                   ;; G1, Hobby's algorithm
   (curves/hobby-curve points {:curl 0.0})       ;; with endpoint curl
   (curves/hobby-curve points {:closed true})    ;; closed loop
   (curves/hobby-curve points {:tensions (fn [i in-deg out-deg] [1.5 1.5])})
   (curves/catmull-rom points)                   ;; C1 centripetal
   (curves/catmull-rom points {:alpha 0.5})      ;; with options
   ```"
  (:require [lib.graphics.curves.spline :as spline]
            [lib.graphics.curves.hobby :as hobby]
            [lib.graphics.curves.catmull-rom :as cr]))

(def natural-cubic-spline spline/natural-cubic-spline)
(def hobby-curve hobby/hobby-curve)
(def catmull-rom cr/catmull-rom)

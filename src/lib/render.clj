(ns lib.render
  "Frame cancellation for hot-reload.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern).")

(defonce ^:private cancel-flag (volatile! false))

;; Pre-allocated Error singleton — extends Error (not Exception) so it won't be
;; swallowed by (catch Exception e ...) in example code. fillInStackTrace is a
;; no-op so throwing is effectively free (~2ns volatile read + throw).
(defonce ^:private ^Error frame-cancelled-ex
  (proxy [Error] ["FrameCancelledException"]
    (fillInStackTrace [] this)))

(defn cancelled-exception?
  "Returns true if t is the frame-cancellation sentinel."
  [t]
  (identical? t frame-cancelled-ex))

(defn cancel-current-frame! []
  (vreset! cancel-flag true))

(defn reset-cancel! []
  (vreset! cancel-flag false))

(defn check-cancelled!
  "Throws if reload has signalled cancellation.
   ~2ns cost — safe in tight inner loops. Called automatically by with-paint;
   call manually in path-construction or pure-computation loops."
  []
  (when @cancel-flag
    (throw frame-cancelled-ex)))

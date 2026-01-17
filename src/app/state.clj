(ns app.state
  "Persistent state that survives reloads.
   Uses defonce so values are set only once and persist across clj-reload cycles.")

;; ============================================================
;; Permanent state (not reset on restart)
;; ============================================================

(defonce window (atom nil))
(defonce running? (atom false))

;; ============================================================
;; Reset values - SINGLE SOURCE OF TRUTH
;; ============================================================

(def ^:private reset-values
  "Initial/reset values for resettable state.
   Both defonce and reset-state! reference this map."
  {:scale 1.0
   :window-width 800
   :window-height 600
   :circles-x 2
   :circles-y 2
   :dragging-slider nil
   :fps 0.0
   :grid-positions []
   :reloading? false
   :last-reload-error nil
   :last-runtime-error nil})

;; ============================================================
;; Resettable state (values from reset-values)
;; ============================================================

(defonce scale (atom (:scale reset-values)))
(defonce window-width (atom (:window-width reset-values)))
(defonce window-height (atom (:window-height reset-values)))
(defonce circles-x (atom (:circles-x reset-values)))
(defonce circles-y (atom (:circles-y reset-values)))
(defonce dragging-slider (atom (:dragging-slider reset-values)))
(defonce fps (atom (:fps reset-values)))
(defonce grid-positions (atom (:grid-positions reset-values)))
(defonce reloading? (atom (:reloading? reset-values)))
(defonce last-reload-error (atom (:last-reload-error reset-values)))
(defonce last-runtime-error (atom (:last-runtime-error reset-values)))

;; ============================================================
;; Reset function (uses same reset-values map)
;; ============================================================

(defn reset-state!
  "Reset resettable state to initial values (for restart)."
  []
  (doseq [[k v] reset-values]
    (when-let [atom-var (ns-resolve 'app.state (symbol (name k)))]
      (reset! @atom-var v))))

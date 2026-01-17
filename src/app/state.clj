(ns app.state
  "Persistent state that survives reloads.
   Uses defonce so values are set only once and persist across clj-reload cycles.")

;; ============================================================
;; Permanent state (not reset on restart)
;; ============================================================

(defonce window (atom nil))
(defonce running? (atom false))

;; ============================================================
;; Initial state - SINGLE SOURCE OF TRUTH
;; ============================================================

(def ^:private initial-state
  "Initial values for resettable state.
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
;; Resettable state (values from initial-state)
;; ============================================================

(defonce scale (atom (:scale initial-state)))
(defonce window-width (atom (:window-width initial-state)))
(defonce window-height (atom (:window-height initial-state)))
(defonce circles-x (atom (:circles-x initial-state)))
(defonce circles-y (atom (:circles-y initial-state)))
(defonce dragging-slider (atom (:dragging-slider initial-state)))
(defonce fps (atom (:fps initial-state)))
(defonce grid-positions (atom (:grid-positions initial-state)))
(defonce reloading? (atom (:reloading? initial-state)))
(defonce last-reload-error (atom (:last-reload-error initial-state)))
(defonce last-runtime-error (atom (:last-runtime-error initial-state)))

;; ============================================================
;; Reset function (uses same initial-state map)
;; ============================================================

(defn reset-state!
  "Reset resettable state to initial values (for restart)."
  []
  (doseq [[k v] initial-state]
    (when-let [atom-var (ns-resolve 'app.state (symbol (name k)))]
      (reset! @atom-var v))))

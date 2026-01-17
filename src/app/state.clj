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
   :last-runtime-error nil
   ;; Game time (for animation libs)
   :game-time 0              ;; accumulated game time in ms
   :time-scale 1.0           ;; 1.0 = normal, 0.5 = slow-mo, 0 = paused
   ;; Spring demo state
   :demo-spring-x nil        ;; spring for X axis (or nil)
   :demo-spring-y nil        ;; spring for Y axis (or nil)
   :demo-circle-x 400.0      ;; current circle X position
   :demo-circle-y 300.0      ;; current circle Y position
   :demo-dragging? false     ;; is user currently dragging?
   :demo-anchor-x 400.0      ;; rest/anchor position X
   :demo-anchor-y 300.0      ;; rest/anchor position Y
   :demo-last-mouse-x 0.0    ;; for velocity calculation
   :demo-last-mouse-y 0.0
   :demo-last-mouse-time 0})

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
;; Game time
(defonce game-time (atom (:game-time initial-state)))
(defonce time-scale (atom (:time-scale initial-state)))
;; Spring demo
(defonce demo-spring-x (atom (:demo-spring-x initial-state)))
(defonce demo-spring-y (atom (:demo-spring-y initial-state)))
(defonce demo-circle-x (atom (:demo-circle-x initial-state)))
(defonce demo-circle-y (atom (:demo-circle-y initial-state)))
(defonce demo-dragging? (atom (:demo-dragging? initial-state)))
(defonce demo-anchor-x (atom (:demo-anchor-x initial-state)))
(defonce demo-anchor-y (atom (:demo-anchor-y initial-state)))
(defonce demo-last-mouse-x (atom (:demo-last-mouse-x initial-state)))
(defonce demo-last-mouse-y (atom (:demo-last-mouse-y initial-state)))
(defonce demo-last-mouse-time (atom (:demo-last-mouse-time initial-state)))

;; ============================================================
;; Reset function (uses same initial-state map)
;; ============================================================

(defn reset-state!
  "Reset resettable state to initial values (for restart)."
  []
  (doseq [[k v] initial-state]
    (when-let [atom-var (ns-resolve 'app.state (symbol (name k)))]
      (reset! @atom-var v))))

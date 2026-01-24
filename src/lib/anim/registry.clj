(ns lib.anim.registry
  "Centralized animation registry with scoped cleanup.

   Animations are registered with an ID and optional scope.
   Scope defaults to the animation ID itself.

   Usage:
     (animate! :card-x (spring {:to 100}) {:target atom})
     (animate! :card-5-x (spring ...) {:scope :card-5})
     (cancel-scope! :card-5)  ;; cancels all with scope :card-5
     (tick-all!)              ;; call once per frame"
  (:require [lib.anim.spring :as spring]
            [lib.anim.decay :as decay]
            [lib.anim.tween :as tween]))

;; ============================================================
;; State
;; ============================================================

(defonce ^:private animations (atom {}))
;; Structure: {id {:anim <spring/decay/tween>
;;                 :type :spring|:decay|:tween
;;                 :scope <keyword>
;;                 :target <atom or nil>
;;                 :on-update <fn or nil>
;;                 :on-complete <fn or nil>}}

;; ============================================================
;; Internal Helpers
;; ============================================================

(defn- get-type [anim]
  (cond
    (:stiffness anim) :spring
    (:rate anim) :decay        ;; decay uses :rate for deceleration
    (:duration anim) :tween
    :else (throw (ex-info "Unknown animation type" {:anim anim}))))

(defn- now-fn [type]
  (case type
    :spring spring/spring-now
    :decay decay/decay-now
    :tween tween/tween-now))

(defn- update-fn [type]
  (case type
    :spring spring/spring-update
    :decay decay/decay-update
    :tween tween/tween-update))

(defn- reloading? []
  (if-let [r (resolve 'app.state/reloading?)]
    @(deref r)
    false))

(defn- scope-matches? [entry-scope target-scope]
  (cond
    (= entry-scope target-scope) true
    ;; Vector prefix matching: [:modal :card] matches [:modal]
    (and (vector? entry-scope) (vector? target-scope))
    (= (take (count target-scope) entry-scope) (seq target-scope))
    ;; Vector with keyword: [:modal :card] matches :modal
    (and (vector? entry-scope) (keyword? target-scope))
    (= (first entry-scope) target-scope)
    :else false))

;; ============================================================
;; Public API
;; ============================================================

(defn animate!
  "Register an animation.

   Options:
     :scope       - keyword or vector for grouped cleanup (default: id)
     :target      - atom to auto-update with value
     :on-update   - (fn [value]) called each frame
     :on-complete - (fn []) called when animation completes"
  ([id anim] (animate! id anim {}))
  ([id anim opts]
   (let [type (get-type anim)
         scope (get opts :scope id)]  ;; default scope = id
     (swap! animations assoc id
            {:anim anim
             :type type
             :scope scope
             :target (:target opts)
             :on-update (:on-update opts)
             :on-complete (:on-complete opts)})
     id)))

(defn update!
  "Update animation properties mid-flight.
   Uses spring-update/decay-update/tween-update internally."
  [id changes]
  (swap! animations
         (fn [anims]
           (if-let [{:keys [anim type]} (get anims id)]
             (assoc-in anims [id :anim] ((update-fn type) anim changes))
             anims))))

(defn cancel!
  "Cancel a single animation by ID."
  [id]
  (swap! animations dissoc id))

(defn cancel-scope!
  "Cancel all animations with matching scope.
   No-op during hot-reload to prevent animation restart.

   Scope matching:
     :card-5 matches :card-5
     :card-5 matches [:card-5 :child]
     [:modal] matches [:modal :card]"
  ([scope] (cancel-scope! scope false))
  ([scope force?]
   (when (or force? (not (reloading?)))
     (swap! animations
            (fn [anims]
              (into {}
                    (remove (fn [[_ v]] (scope-matches? (:scope v) scope)))
                    anims))))))

(defn value
  "Get current value of an animation, or nil if not found."
  [id]
  (when-let [{:keys [anim type]} (get @animations id)]
    (:value ((now-fn type) anim))))

(defn running?
  "Check if animation is still running (not at rest)."
  [id]
  (when-let [{:keys [anim type]} (get @animations id)]
    (not (:at-rest? ((now-fn type) anim)))))

(defn tick-all!
  "Tick all animations. Call once per frame.
   Updates targets, calls callbacks, removes completed animations."
  []
  (let [to-remove (atom [])]
    (doseq [[id {:keys [anim type target on-update on-complete]}] @animations]
      (let [{:keys [value at-rest?]} ((now-fn type) anim)]
        ;; Update target atom
        (when target
          (reset! target value))
        ;; Call update callback
        (when on-update
          (on-update value))
        ;; Mark for removal if done
        (when at-rest?
          (when on-complete
            (on-complete))
          (swap! to-remove conj id))))
    ;; Remove completed animations
    (when (seq @to-remove)
      (swap! animations #(apply dissoc % @to-remove)))))

(defn reset-all!
  "Clear all animations. For testing/debugging."
  []
  (reset! animations {}))

(defn all-animations
  "Return all animations (for debugging)."
  []
  @animations)

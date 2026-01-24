(ns lib.flex.core
  "Hot-reload compatible Flex integration.

   Flex is a Vue 3-style reactive signal library. This namespace provides:
   - Re-exports of core Flex APIs
   - Effect tracking for hot-reload disposal
   - defsource macro for persistent reactive sources"
  (:require [town.lilac.flex :as flex]))

;; ============================================================
;; Re-export core APIs
;; ============================================================

;; Functions can be aliased directly
(def source flex/source)
(def listen flex/listen)
(def dispose! flex/dispose!)

;; Macros need wrapper macros
(defmacro signal
  "Create a derived signal that auto-recomputes when dependencies change."
  [& body]
  `(flex/signal ~@body))

(defmacro batch
  "Batch multiple source updates, coalescing reactive recomputation."
  [& body]
  `(flex/batch ~@body))

;; ============================================================
;; Effect tracking for hot-reload
;; ============================================================

(defonce ^:private active-effects (atom #{}))

(defn track-effect!
  "Register an effect for disposal during hot-reload.
   Returns the effect for chaining."
  [effect]
  (swap! active-effects conj effect)
  effect)

(defn untrack-effect!
  "Remove an effect from tracking (call after manual disposal)."
  [effect]
  (swap! active-effects disj effect))

(defn dispose-all-effects!
  "Dispose all tracked effects. Call before hot-reload."
  []
  (doseq [effect @active-effects]
    (try
      (dispose! effect)
      (catch Exception _)))
  (reset! active-effects #{}))

(defn get-active-effect-count
  "Get count of active effects (for debugging)."
  []
  (count @active-effects))

;; ============================================================
;; Convenience macros
;; ============================================================

(defmacro defsource
  "Define a Flex source that persists across hot-reloads.
   Uses defonce internally so the source is only created once.

   Example:
     (defsource window-width 800)
     @window-width       ;; => 800
     (window-width 1024) ;; sets to 1024"
  [name init]
  `(defonce ~name (source ~init)))

(defmacro with-batch
  "Execute body in a batch, coalescing reactive updates.
   Signals only recompute once after all source updates.

   Example:
     (with-batch
       (width 800)
       (height 600)
       (scale 2.0))
     ;; Dependent signals update only once"
  [& body]
  `(batch (fn [] ~@body)))

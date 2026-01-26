(ns app.shell.state
  "Shell-level reactive state (FPS, panel visibility).

   These are dev infrastructure sources that persist across hot-reloads
   and example switches."
  (:require [lib.flex.core :as flex]))

;; ============================================================
;; FPS display
;; ============================================================

;; Smoothed FPS - updated every frame with exponential moving average
(flex/defsource fps 0.0)

;; FPS history for graph - primitive float array (true zero allocations)
;; Frame-based: one sample per frame, time window varies with FPS (industry standard)
(def fps-history-size 120)
(defonce fps-history (float-array fps-history-size))
(defonce fps-history-idx (atom 0))

;; ============================================================
;; Debug panel visibility
;; ============================================================

;; Control panel visibility (Ctrl+` toggle)
(flex/defsource panel-visible? false)

;; FPS display visibility (toggled via control panel checkbox)
(flex/defsource fps-display-visible? false)

;; ============================================================
;; Active example tracking
;; ============================================================

;; Current example key (e.g., :playground/ball-spring)
(defonce current-example (atom nil))

;; ============================================================
;; Control panel - Group collapse state
;; ============================================================

;; Group collapse state: {group-id -> collapsed?}
(defonce group-collapse-state (atom {}))

(defn toggle-group-collapse!
  "Toggle the collapse state of a group."
  [group-id]
  (swap! group-collapse-state update group-id not))

(defn is-group-collapsed?
  "Check if a group is collapsed. Default: expanded (false)."
  [group-id]
  (get @group-collapse-state group-id false))

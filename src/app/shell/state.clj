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

(flex/defsource panel-visible? false)

;; ============================================================
;; Active example tracking
;; ============================================================

;; Current example key (e.g., :playground/ball-spring)
(defonce current-example (atom nil))

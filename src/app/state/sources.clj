(ns app.state.sources
  "Flex sources for reactive UI state.

   These are the primary sources of truth for UI state that can trigger
   reactive updates. Call sources as functions to update values:
     (window-width 1024)  ; sets window-width to 1024
   Dependent signals will automatically recompute.

   Sources persist across hot-reloads via defonce."
  (:require [lib.flex.core :as flex]))

;; ============================================================
;; Window dimensions
;; ============================================================

(flex/defsource window-width 800)
(flex/defsource window-height 600)
(flex/defsource scale 1.0)

;; ============================================================
;; Grid configuration
;; ============================================================

(flex/defsource circles-x 2)
(flex/defsource circles-y 2)

;; ============================================================
;; UI interaction state
;; ============================================================

(flex/defsource dragging-slider nil)
(flex/defsource panel-visible? true)
(flex/defsource demo-dragging? false)

;; ============================================================
;; Recording state
;; ============================================================

(flex/defsource recording-active? false)

;; ============================================================
;; FPS display
;; ============================================================

;; Smoothed FPS - updated every frame with exponential moving average
(flex/defsource fps 0.0)

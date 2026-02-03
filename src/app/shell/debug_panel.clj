(ns app.shell.debug-panel
  "Debug panel orchestrator - coordinates FPS display and control panel.

   The debug panel is shell infrastructure that overlays on top of examples.
   - FPS display (top-left): toggleable via control panel checkbox
   - Control panel (top-right): toggleable via Ctrl+`"
  (:require [app.shell.state :as state])
  (:import [io.github.humbleui.skija Canvas]))

;; ============================================================
;; Orchestration
;; ============================================================

(defn draw-panel
  "Draw debug overlays (FPS display only).
   Control panel is rendered in its own window."
  [^Canvas canvas _window-width]
  ;; FPS display (top-left) - independent of control panel
  (when-let [fps-draw (requiring-resolve 'app.shell.fps-display/draw)]
    (fps-draw canvas)))

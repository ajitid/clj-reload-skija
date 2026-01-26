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
  "Draw all debug overlays.
   Delegates to fps-display (top-left) and control-panel (top-right)."
  [^Canvas canvas window-width]
  ;; FPS display (top-left) - independent of control panel
  (when-let [fps-draw (requiring-resolve 'app.shell.fps-display/draw)]
    (fps-draw canvas))
  ;; Control panel (top-right) - toggleable with Ctrl+`
  (when-let [panel-draw (requiring-resolve 'app.shell.control-panel/draw)]
    (panel-draw canvas window-width)))

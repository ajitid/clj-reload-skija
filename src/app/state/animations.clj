(ns app.state.animations
  "Animation target atoms - plain atoms, NO Flex.

   Animation targets are updated at 60fps so they remain as plain atoms
   to avoid reactive overhead. The animation system (lib.anim) writes
   directly to these atoms each frame.

   These persist across hot-reloads via defonce.")

;; ============================================================
;; Demo circle animation state
;; ============================================================

;; Position
(defonce demo-circle-x (atom 400.0))
(defonce demo-circle-y (atom 300.0))

;; Anchor/rest position (where spring returns to)
(defonce demo-anchor-x (atom 400.0))
(defonce demo-anchor-y (atom 300.0))

;; Velocity for momentum
(defonce demo-velocity-x (atom 0.0))
(defonce demo-velocity-y (atom 0.0))

;; Drag offset (keeps ball from jumping to cursor)
(defonce demo-drag-offset-x (atom 0.0))

;; Position history for velocity calculation
(defonce demo-position-history (atom []))

;; Active decay animation
(defonce demo-decay-x (atom nil))

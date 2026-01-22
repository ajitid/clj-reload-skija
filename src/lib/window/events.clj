(ns lib.window.events
  "Event records for the windowing abstraction.
   These mirror JWM's event model for easy migration.")

;; Frame events
(defrecord EventFrame [])
(defrecord EventFrameSkija [surface canvas])

;; Window events
(defrecord EventResize [width height scale])
(defrecord EventClose [])
(defrecord EventExposed [])

;; Mouse events - coordinates in logical pixels (already scaled)
(defrecord EventMouseButton [button x y pressed?])
(defrecord EventMouseMove [x y])
(defrecord EventMouseWheel [x y dx dy modifiers])

;; Keyboard events
(defrecord EventKey [key pressed? modifiers])

;; Touch/finger events - coordinates in logical pixels
(defrecord EventFingerDown [id x y pressure])
(defrecord EventFingerMove [id x y pressure])
(defrecord EventFingerUp [id x y])

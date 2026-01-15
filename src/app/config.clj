(ns app.config
  "Reloadable configuration values.
   These use regular def so they WILL change when you reload.

   Edit these values and call (user/reload) to see changes immediately!")

;; Drop shadow settings for the pink circle
;; dx, dy = shadow offset in pixels
;; sigma = blur amount (higher = more blur)
(def shadow-dx 8)
(def shadow-dy 8)
(def shadow-sigma 10.0)

;; Blur settings for the green rectangle
;; sigma-x, sigma-y = blur radius (higher = more blur)
(def blur-sigma-x 5.0)
(def blur-sigma-y 5.0)

;; Colors (can also be changed on reload)
(def circle-color 0xFFFF69B4)      ;; Hot pink (ARGB format)
(def shadow-color 0x80000000)      ;; Semi-transparent black
(def rect-color 0xFF32CD32)        ;; Lime green

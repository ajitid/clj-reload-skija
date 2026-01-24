(ns app.config
  "Reloadable configuration values.
   These use regular def so they WILL change when you reload.

   Edit these values and call (user/reload) to see changes immediately!

   clj-reload hook functions (optional):
   - before-ns-unload - called before this namespace is unloaded
   - after-ns-reload  - called after this namespace is reloaded
   See: https://github.com/tonsky/clj-reload")

;; Grid circle settings
(def circle-color 0xFFFF69B4)      ;; Hot pink (ARGB format)

;; Grid settings
(def grid-circle-radius 100)       ;; Each circle is 200x200 (radius 100)
(def grid-bg-color 0xFF222222)     ;; Dark background
(def min-circles 1)
(def max-circles 250)

;; Control panel settings (in logical pixels, will be scaled)
(def panel-right-offset 20)    ;; Distance from right edge
(def panel-y 20)
(def panel-width 200)
(def panel-height 220)
(def panel-padding 15)
(def panel-bg-color 0xDD333333)
(def panel-text-color 0xFFFFFFFF)
(def slider-track-color 0xFF555555)
(def slider-fill-color 0xFFFF69B4)
(def slider-height 16)
(def slider-width 160)
(def font-size 18)

;; FPS graph settings
(def fps-graph-height 40)
(def fps-graph-samples 120)        ;; Number of samples to display (~2 sec at 60fps)
(def fps-graph-color 0xFF4AE88C)   ;; Green
(def fps-graph-low-color 0xFFE84A4A) ;; Red for low FPS
(def fps-graph-bg-color 0xFF1A1A1A)
(def fps-target 60)                ;; Target FPS for reference line

;; Spring demo settings
(def demo-circle-radius 25)
(def demo-circle-color 0xFF4A90D9)     ;; Blue
(def demo-anchor-color 0x44FFFFFF)     ;; Faint white
(def demo-spring-stiffness 180)        ;; Apple-like bouncy
(def demo-spring-damping 12)           ;; Light damping
(def demo-spring-mass 1.0)

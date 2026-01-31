(ns lib.color.open-color
  "Open Color palette - optimized for UI design.

   Sources:
   - https://yeun.github.io/open-color/
   - https://github.com/yeun/open-color

   13 color families × 10 shades (0-9).
   Shade 0 is lightest, shade 9 is darkest."
  (:require [lib.color.core :refer [hex]]))

;; ============================================================
;; Gray
;; ============================================================

(def gray-0 (hex "#f8f9fa"))
(def gray-1 (hex "#f1f3f5"))
(def gray-2 (hex "#e9ecef"))
(def gray-3 (hex "#dee2e6"))
(def gray-4 (hex "#ced4da"))
(def gray-5 (hex "#adb5bd"))
(def gray-6 (hex "#868e96"))
(def gray-7 (hex "#495057"))
(def gray-8 (hex "#343a40"))
(def gray-9 (hex "#212529"))

;; ============================================================
;; Red
;; ============================================================

(def red-0 (hex "#fff5f5"))
(def red-1 (hex "#ffe3e3"))
(def red-2 (hex "#ffc9c9"))
(def red-3 (hex "#ffa8a8"))
(def red-4 (hex "#ff8787"))
(def red-5 (hex "#ff6b6b"))
(def red-6 (hex "#fa5252"))
(def red-7 (hex "#f03e3e"))
(def red-8 (hex "#e03131"))
(def red-9 (hex "#c92a2a"))

;; ============================================================
;; Pink
;; ============================================================

(def pink-0 (hex "#fff0f6"))
(def pink-1 (hex "#ffdeeb"))
(def pink-2 (hex "#fcc2d7"))
(def pink-3 (hex "#faa2c1"))
(def pink-4 (hex "#f783ac"))
(def pink-5 (hex "#f06595"))
(def pink-6 (hex "#e64980"))
(def pink-7 (hex "#d6336c"))
(def pink-8 (hex "#c2255c"))
(def pink-9 (hex "#a61e4d"))

;; ============================================================
;; Grape
;; ============================================================

(def grape-0 (hex "#f8f0fc"))
(def grape-1 (hex "#f3d9fa"))
(def grape-2 (hex "#eebefa"))
(def grape-3 (hex "#e599f7"))
(def grape-4 (hex "#da77f2"))
(def grape-5 (hex "#cc5de8"))
(def grape-6 (hex "#be4bdb"))
(def grape-7 (hex "#ae3ec9"))
(def grape-8 (hex "#9c36b5"))
(def grape-9 (hex "#862e9c"))

;; ============================================================
;; Violet
;; ============================================================

(def violet-0 (hex "#f3f0ff"))
(def violet-1 (hex "#e5dbff"))
(def violet-2 (hex "#d0bfff"))
(def violet-3 (hex "#b197fc"))
(def violet-4 (hex "#9775fa"))
(def violet-5 (hex "#845ef7"))
(def violet-6 (hex "#7950f2"))
(def violet-7 (hex "#7048e8"))
(def violet-8 (hex "#6741d9"))
(def violet-9 (hex "#5f3dc4"))

;; ============================================================
;; Indigo
;; ============================================================

(def indigo-0 (hex "#edf2ff"))
(def indigo-1 (hex "#dbe4ff"))
(def indigo-2 (hex "#bac8ff"))
(def indigo-3 (hex "#91a7ff"))
(def indigo-4 (hex "#748ffc"))
(def indigo-5 (hex "#5c7cfa"))
(def indigo-6 (hex "#4c6ef5"))
(def indigo-7 (hex "#4263eb"))
(def indigo-8 (hex "#3b5bdb"))
(def indigo-9 (hex "#364fc7"))

;; ============================================================
;; Blue
;; ============================================================

(def blue-0 (hex "#e7f5ff"))
(def blue-1 (hex "#d0ebff"))
(def blue-2 (hex "#a5d8ff"))
(def blue-3 (hex "#74c0fc"))
(def blue-4 (hex "#4dabf7"))
(def blue-5 (hex "#339af0"))
(def blue-6 (hex "#228be6"))
(def blue-7 (hex "#1c7ed6"))
(def blue-8 (hex "#1971c2"))
(def blue-9 (hex "#1864ab"))

;; ============================================================
;; Cyan
;; ============================================================

(def cyan-0 (hex "#e3fafc"))
(def cyan-1 (hex "#c5f6fa"))
(def cyan-2 (hex "#99e9f2"))
(def cyan-3 (hex "#66d9e8"))
(def cyan-4 (hex "#3bc9db"))
(def cyan-5 (hex "#22b8cf"))
(def cyan-6 (hex "#15aabf"))
(def cyan-7 (hex "#1098ad"))
(def cyan-8 (hex "#0c8599"))
(def cyan-9 (hex "#0b7285"))

;; ============================================================
;; Teal
;; ============================================================

(def teal-0 (hex "#e6fcf5"))
(def teal-1 (hex "#c3fae8"))
(def teal-2 (hex "#96f2d7"))
(def teal-3 (hex "#63e6be"))
(def teal-4 (hex "#38d9a9"))
(def teal-5 (hex "#20c997"))
(def teal-6 (hex "#12b886"))
(def teal-7 (hex "#0ca678"))
(def teal-8 (hex "#099268"))
(def teal-9 (hex "#087f5b"))

;; ============================================================
;; Green
;; ============================================================

(def green-0 (hex "#ebfbee"))
(def green-1 (hex "#d3f9d8"))
(def green-2 (hex "#b2f2bb"))
(def green-3 (hex "#8ce99a"))
(def green-4 (hex "#69db7c"))
(def green-5 (hex "#51cf66"))
(def green-6 (hex "#40c057"))
(def green-7 (hex "#37b24d"))
(def green-8 (hex "#2f9e44"))
(def green-9 (hex "#2b8a3e"))

;; ============================================================
;; Lime
;; ============================================================

(def lime-0 (hex "#f4fce3"))
(def lime-1 (hex "#e9fac8"))
(def lime-2 (hex "#d8f5a2"))
(def lime-3 (hex "#c0eb75"))
(def lime-4 (hex "#a9e34b"))
(def lime-5 (hex "#94d82d"))
(def lime-6 (hex "#82c91e"))
(def lime-7 (hex "#74b816"))
(def lime-8 (hex "#66a80f"))
(def lime-9 (hex "#5c940d"))

;; ============================================================
;; Yellow
;; ============================================================

(def yellow-0 (hex "#fff9db"))
(def yellow-1 (hex "#fff3bf"))
(def yellow-2 (hex "#ffec99"))
(def yellow-3 (hex "#ffe066"))
(def yellow-4 (hex "#ffd43b"))
(def yellow-5 (hex "#fcc419"))
(def yellow-6 (hex "#fab005"))
(def yellow-7 (hex "#f59f00"))
(def yellow-8 (hex "#f08c00"))
(def yellow-9 (hex "#e67700"))

;; ============================================================
;; Orange
;; ============================================================

(def orange-0 (hex "#fff4e6"))
(def orange-1 (hex "#ffe8cc"))
(def orange-2 (hex "#ffd8a8"))
(def orange-3 (hex "#ffc078"))
(def orange-4 (hex "#ffa94d"))
(def orange-5 (hex "#ff922b"))
(def orange-6 (hex "#fd7e14"))
(def orange-7 (hex "#f76707"))
(def orange-8 (hex "#e8590c"))
(def orange-9 (hex "#d9480f"))

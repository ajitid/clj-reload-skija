(ns lib.color.reasonable
  "Reasonable Colors - accessible color system for design.

   Sources:
   - https://www.reasonable.work/colors/
   - https://github.com/matthewhowell/reasonable-colors

   24 color families × 6 shades (1-6).
   Shade 1 is lightest, shade 6 is darkest.
   Designed for WCAG accessibility."
  (:require [lib.color.core :refer [hex]]))

;; ============================================================
;; Gray
;; ============================================================

(def gray-1 (hex "#f6f6f6"))
(def gray-2 (hex "#e2e2e2"))
(def gray-3 (hex "#8b8b8b"))
(def gray-4 (hex "#6f6f6f"))
(def gray-5 (hex "#3e3e3e"))
(def gray-6 (hex "#222222"))

;; ============================================================
;; Rose
;; ============================================================

(def rose-1 (hex "#fff7f9"))
(def rose-2 (hex "#ffdce5"))
(def rose-3 (hex "#ff3b8d"))
(def rose-4 (hex "#db0072"))
(def rose-5 (hex "#800040"))
(def rose-6 (hex "#4c0023"))

;; ============================================================
;; Raspberry
;; ============================================================

(def raspberry-1 (hex "#fff8f8"))
(def raspberry-2 (hex "#ffdddf"))
(def raspberry-3 (hex "#ff426c"))
(def raspberry-4 (hex "#de0051"))
(def raspberry-5 (hex "#82002c"))
(def raspberry-6 (hex "#510018"))

;; ============================================================
;; Red
;; ============================================================

(def red-1 (hex "#fff8f6"))
(def red-2 (hex "#ffddd8"))
(def red-3 (hex "#ff4647"))
(def red-4 (hex "#e0002b"))
(def red-5 (hex "#830014"))
(def red-6 (hex "#530003"))

;; ============================================================
;; Orange
;; ============================================================

(def orange-1 (hex "#fff8f5"))
(def orange-2 (hex "#ffded1"))
(def orange-3 (hex "#fd4d00"))
(def orange-4 (hex "#cd3c00"))
(def orange-5 (hex "#752100"))
(def orange-6 (hex "#401600"))

;; ============================================================
;; Cinnamon
;; ============================================================

(def cinnamon-1 (hex "#fff8f3"))
(def cinnamon-2 (hex "#ffdfc6"))
(def cinnamon-3 (hex "#d57300"))
(def cinnamon-4 (hex "#ac5c00"))
(def cinnamon-5 (hex "#633300"))
(def cinnamon-6 (hex "#371d00"))

;; ============================================================
;; Amber
;; ============================================================

(def amber-1 (hex "#fff8ef"))
(def amber-2 (hex "#ffe0b2"))
(def amber-3 (hex "#b98300"))
(def amber-4 (hex "#926700"))
(def amber-5 (hex "#523800"))
(def amber-6 (hex "#302100"))

;; ============================================================
;; Yellow
;; ============================================================

(def yellow-1 (hex "#fff9e5"))
(def yellow-2 (hex "#ffe53e"))
(def yellow-3 (hex "#9c8b00"))
(def yellow-4 (hex "#7d6f00"))
(def yellow-5 (hex "#463d00"))
(def yellow-6 (hex "#292300"))

;; ============================================================
;; Lime
;; ============================================================

(def lime-1 (hex "#f7ffac"))
(def lime-2 (hex "#d5f200"))
(def lime-3 (hex "#819300"))
(def lime-4 (hex "#677600"))
(def lime-5 (hex "#394100"))
(def lime-6 (hex "#222600"))

;; ============================================================
;; Chartreuse
;; ============================================================

(def chartreuse-1 (hex "#e5ffc3"))
(def chartreuse-2 (hex "#98fb00"))
(def chartreuse-3 (hex "#5c9b00"))
(def chartreuse-4 (hex "#497c00"))
(def chartreuse-5 (hex "#264500"))
(def chartreuse-6 (hex "#182600"))

;; ============================================================
;; Green
;; ============================================================

(def green-1 (hex "#e0ffd9"))
(def green-2 (hex "#72ff6c"))
(def green-3 (hex "#00a21f"))
(def green-4 (hex "#008217"))
(def green-5 (hex "#004908"))
(def green-6 (hex "#062800"))

;; ============================================================
;; Emerald
;; ============================================================

(def emerald-1 (hex "#dcffe6"))
(def emerald-2 (hex "#5dffa2"))
(def emerald-3 (hex "#00a05a"))
(def emerald-4 (hex "#008147"))
(def emerald-5 (hex "#004825"))
(def emerald-6 (hex "#002812"))

;; ============================================================
;; Aquamarine
;; ============================================================

(def aquamarine-1 (hex "#daffef"))
(def aquamarine-2 (hex "#42ffc6"))
(def aquamarine-3 (hex "#009f78"))
(def aquamarine-4 (hex "#007f5f"))
(def aquamarine-5 (hex "#004734"))
(def aquamarine-6 (hex "#00281b"))

;; ============================================================
;; Teal
;; ============================================================

(def teal-1 (hex "#d7fff7"))
(def teal-2 (hex "#00ffe4"))
(def teal-3 (hex "#009e8c"))
(def teal-4 (hex "#007c6e"))
(def teal-5 (hex "#00443c"))
(def teal-6 (hex "#002722"))

;; ============================================================
;; Cyan
;; ============================================================

(def cyan-1 (hex "#c4fffe"))
(def cyan-2 (hex "#00fafb"))
(def cyan-3 (hex "#00999a"))
(def cyan-4 (hex "#007a7b"))
(def cyan-5 (hex "#004344"))
(def cyan-6 (hex "#002525"))

;; ============================================================
;; Powder
;; ============================================================

(def powder-1 (hex "#dafaff"))
(def powder-2 (hex "#8df0ff"))
(def powder-3 (hex "#0098a9"))
(def powder-4 (hex "#007987"))
(def powder-5 (hex "#004048"))
(def powder-6 (hex "#002227"))

;; ============================================================
;; Sky
;; ============================================================

(def sky-1 (hex "#e3f7ff"))
(def sky-2 (hex "#aee9ff"))
(def sky-3 (hex "#0094b4"))
(def sky-4 (hex "#007590"))
(def sky-5 (hex "#00404f"))
(def sky-6 (hex "#001f28"))

;; ============================================================
;; Cerulean
;; ============================================================

(def cerulean-1 (hex "#e8f6ff"))
(def cerulean-2 (hex "#b9e3ff"))
(def cerulean-3 (hex "#0092c5"))
(def cerulean-4 (hex "#00749d"))
(def cerulean-5 (hex "#003c54"))
(def cerulean-6 (hex "#001d2a"))

;; ============================================================
;; Azure
;; ============================================================

(def azure-1 (hex "#e8f2ff"))
(def azure-2 (hex "#c6e0ff"))
(def azure-3 (hex "#008fdb"))
(def azure-4 (hex "#0071af"))
(def azure-5 (hex "#003b5e"))
(def azure-6 (hex "#001c30"))

;; ============================================================
;; Blue
;; ============================================================

(def blue-1 (hex "#f0f4ff"))
(def blue-2 (hex "#d4e0ff"))
(def blue-3 (hex "#0089fc"))
(def blue-4 (hex "#006dca"))
(def blue-5 (hex "#00386d"))
(def blue-6 (hex "#001a39"))

;; ============================================================
;; Indigo
;; ============================================================

(def indigo-1 (hex "#f3f3ff"))
(def indigo-2 (hex "#deddff"))
(def indigo-3 (hex "#657eff"))
(def indigo-4 (hex "#0061fc"))
(def indigo-5 (hex "#00328a"))
(def indigo-6 (hex "#001649"))

;; ============================================================
;; Violet
;; ============================================================

(def violet-1 (hex "#f7f1ff"))
(def violet-2 (hex "#e8daff"))
(def violet-3 (hex "#9b70ff"))
(def violet-4 (hex "#794aff"))
(def violet-5 (hex "#2d0fbf"))
(def violet-6 (hex "#0b0074"))

;; ============================================================
;; Purple
;; ============================================================

(def purple-1 (hex "#fdf4ff"))
(def purple-2 (hex "#f7d9ff"))
(def purple-3 (hex "#d150ff"))
(def purple-4 (hex "#b01fe3"))
(def purple-5 (hex "#660087"))
(def purple-6 (hex "#3a004f"))

;; ============================================================
;; Magenta
;; ============================================================

(def magenta-1 (hex "#fff3fc"))
(def magenta-2 (hex "#ffd7f6"))
(def magenta-3 (hex "#f911e0"))
(def magenta-4 (hex "#ca00b6"))
(def magenta-5 (hex "#740068"))
(def magenta-6 (hex "#44003c"))

;; ============================================================
;; Pink
;; ============================================================

(def pink-1 (hex "#fff7fb"))
(def pink-2 (hex "#ffdcec"))
(def pink-3 (hex "#ff2fb2"))
(def pink-4 (hex "#d2008f"))
(def pink-5 (hex "#790051"))
(def pink-6 (hex "#4b0030"))

# Conversation: https://github.com/texel-org/color we need something like this because always specifying color in ar

**Date:** 2026-01-24T22:45:34.218Z
**Project:** /Users/as186073/Downloads/vvvv-clj/clj-reload-skija
**Messages:** 4

## Table of Contents

1. [https://github.com/texel-org/color we need something like this because always specifying color in ar...](#user-1)

---

## <a id="user-1"></a>User #1

https://github.com/texel-org/color we need something like this because always specifying color in argb is unintuitive, can you check if something like this already exist in clojure/java world or if we need to make a port

---

**Assistant:**

Let me check what the texel-org/color library offers and search for Clojure/Java alternatives.

---

**Assistant:**

Good news - there are existing options! Let me get more details on the most promising one:

---

**Assistant:**

Great news - there are already solid options in both Clojure and Java:

## Clojure: clojure2d.color (Best Option)

Part of the [clojure2d](https://clojure2d.github.io/clojure2d/docs/codox/clojure2d.color.html) library with **40+ color spaces** including OKLCH:

```clojure
;; deps.edn
{:deps {clojure2d/clojure2d {:mvn/version "1.4.5"}}}

;; Usage
(require '[clojure2d.color :as c])

(c/color :red)               ; Named color
(c/color "#ff6432")          ; Hex
(c/color 255 100 50)         ; RGB

(c/to-Oklch (c/color :red))  ; Convert to OKLCH
(c/from-Oklch [0.5 0.15 29]) ; Create from OKLCH [L C H]

(c/mix :red :blue)           ; Blend colors
(c/darken :red 0.5)          ; Manipulate lightness
```

Supports Oklab, Oklch, Okhsl, Okhsv, HSL, LAB, LCH, XYZ, and many more.

## Java: EsotericSoftware/color

[esoteric-color](https://github.com/EsotericSoftware/color) - Pure Java, **50+ color spaces**, zero dependencies:

```java
RGB rgb = new RGB(1, 0.5f, 0.25f);
Oklab ok = rgb.Oklab();
Oklch lch = rgb.Oklch();
```

## Also worth mentioning

- [jolby/colors](https://github.com/jolby/colors) (`com.evocomputing/colors`) - Mature Clojure lib for HSL/RGB (no OKLCH)
- [thi-ng/color](https://github.com/thi-ng/color) - Another Clojure option with good color space support

## Recommendation

**Use clojure2d.color** - it has OKLCH support, is actively maintained, and integrates well with Clojure. No need to port texel/color.

Sources:
- [clojure2d.color docs](https://clojure2d.github.io/clojure2d/docs/codox/clojure2d.color.html)
- [EsotericSoftware/color](https://github.com/EsotericSoftware/color)
- [jolby/colors](https://github.com/jolby/colors)

---


(ns lib.graphics.text
  "Text rendering utilities - Love2D-style text API.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:require [lib.graphics.state :as gfx])
  (:import [io.github.humbleui.skija Canvas Paint Font Typeface]))

;; ============================================================
;; Font Cache
;; ============================================================

(def ^:private font-cache
  "Cache of Typeface -> Size -> Font to reduce allocations."
  (atom {}))

(defn get-font
  "Get or create a cached font.

   Args:
     typeface - Typeface instance (default: Typeface/makeDefault)
     size     - font size in points (default: 14)

   Returns: Font instance (cached, do not close)"
  ([] (get-font (Typeface/makeDefault) 14))
  ([typeface] (get-font typeface 14))
  ([typeface size]
   (let [cache-key [typeface size]]
     (if-let [cached-font (get @font-cache cache-key)]
       cached-font
       (let [new-font (Font. typeface (float size))]
         (swap! font-cache assoc cache-key new-font)
         new-font)))))

(defn clear-font-cache!
  "Clear the font cache (useful for memory cleanup)."
  []
  (reset! font-cache {}))

;; ============================================================
;; Text Drawing
;; ============================================================

(defn text
  "Draw text at the given position.

   Args:
     canvas - drawing canvas
     text   - string to draw
     x, y   - position (baseline for y)
     opts   - optional map:
              :size  - font size in points (default: 14)
              All paint options supported (see shapes/circle)
              Common: :color, :blur, :shadow, :gradient, etc.

   Examples:
     (text canvas \"Hello\" 10 20)
     (text canvas \"Hello\" 10 20 {:color 0xFF4A90D9})
     (text canvas \"Hello\" 10 20 {:size 24 :color 0xFFFF0000})
     (text canvas \"GLOW\" 10 50 {:size 48 :shadow {:dx 0 :dy 0 :blur 10 :color 0xFF00FFFF}})"
  ([^Canvas canvas text x y]
   (text canvas text x y {}))
  ([^Canvas canvas text x y opts]
   (let [;; Get font from cache (hide Typeface/Font from users)
         font (get-font (Typeface/makeDefault) (or (:size opts) 14))
         ;; Remove text-specific opts before passing to paint
         paint-opts (dissoc opts :size :font :typeface)]
     (if-let [paint (:paint opts)]
       (.drawString canvas (str text) (float x) (float y) font paint)
       (gfx/with-paint [paint paint-opts]
         (.drawString canvas (str text) (float x) (float y) font paint))))))

(defn measure-text
  "Measure text width using a font.

   Args:
     text - string to measure
     opts - optional map:
            :font     - Font instance (default: cached default font)
            :size     - font size if no :font provided (default: 14)
            :typeface - Typeface if no :font provided (default: Typeface/makeDefault)

   Returns: width in pixels (float)

   Example:
     (measure-text \"Hello\")
     (measure-text \"Hello\" {:size 18})"
  ([text] (measure-text text {}))
  ([text opts]
   (let [font (or (:font opts)
                  (get-font (or (:typeface opts) (Typeface/makeDefault))
                            (or (:size opts) 14)))]
     (.measureTextWidth font (str text)))))

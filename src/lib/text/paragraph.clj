(ns lib.text.paragraph
  "Multi-line text with word wrap and rich styling.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:require [lib.text.core :as core])
  (:import [io.github.humbleui.skija Canvas Paint FontMgr]
           [io.github.humbleui.skija.paragraph
            Paragraph ParagraphBuilder ParagraphStyle TextStyle
            FontCollection Alignment Direction DecorationStyle
            DecorationLineStyle BaselineMode HeightMode]))

;; ============================================================
;; Font Collection (Singleton)
;; ============================================================

(defonce ^:private font-collection
  "Shared FontCollection for paragraph system."
  (doto (FontCollection.)
    (.setDefaultFontManager (FontMgr/getDefault))))

;; ============================================================
;; Style Builders
;; ============================================================

(defn- resolve-alignment
  "Convert alignment keyword to Alignment enum."
  [align]
  (case align
    :left Alignment/LEFT
    :center Alignment/CENTER
    :right Alignment/RIGHT
    :justify Alignment/JUSTIFY
    :start Alignment/START
    :end Alignment/END
    Alignment/LEFT))

(defn- resolve-direction
  "Convert direction keyword to Direction enum."
  [dir]
  (case dir
    :ltr Direction/LTR
    :rtl Direction/RTL
    Direction/LTR))

(defn- resolve-decoration-style
  "Convert decoration style keyword to DecorationLineStyle enum."
  [style]
  (case style
    :solid DecorationLineStyle/SOLID
    :double DecorationLineStyle/DOUBLE
    :dotted DecorationLineStyle/DOTTED
    :dashed DecorationLineStyle/DASHED
    :wavy DecorationLineStyle/WAVY
    DecorationLineStyle/SOLID))

(defn- resolve-weight
  "Convert weight keyword or number to int."
  [w]
  (cond
    (nil? w) 400
    (keyword? w) (get {:thin 100 :extra-light 200 :light 300 :normal 400
                       :medium 500 :semi-bold 600 :bold 700 :extra-bold 800 :black 900} w 400)
    (number? w) (int w)
    :else 400))

(defn- build-paragraph-style
  "Build ParagraphStyle from options."
  [opts]
  (let [{:keys [align direction max-lines ellipsis height-mode]} opts
        style (ParagraphStyle.)]
    (when align (.setAlignment style (resolve-alignment align)))
    (when direction (.setDirection style (resolve-direction direction)))
    (when max-lines (.setMaxLinesCount style (int max-lines)))
    (when ellipsis (.setEllipsis style (str ellipsis)))
    (when height-mode
      (.setHeightMode style (case height-mode
                              :all HeightMode/ALL
                              :disable-first-ascent HeightMode/DISABLE_FIRST_ASCENT
                              :disable-last-descent HeightMode/DISABLE_LAST_DESCENT
                              :disable-all HeightMode/DISABLE_ALL
                              HeightMode/ALL)))
    style))

(defn- build-text-style
  "Build TextStyle from options."
  [opts]
  (let [{:keys [size weight slant family color background-color
                underline strikethrough letter-spacing word-spacing
                line-height baseline-shift]} opts
        style (TextStyle.)]
    ;; Font properties
    (when size (.setFontSize style (float size)))
    (when family (.setFontFamilies style (into-array String [(str family)])))
    (when (or weight slant)
      (let [w (resolve-weight weight)
            s (case slant :italic 1 :oblique 2 0)]
        (.setFontStyle style (io.github.humbleui.skija.FontStyle. w 5 s))))

    ;; Colors
    (when color (.setColor style (unchecked-int color)))
    (when background-color
      (.setBackgroundPaint style (doto (Paint.) (.setColor (unchecked-int background-color)))))

    ;; Decorations
    (when underline
      (if (map? underline)
        (let [{:keys [style color thickness-multiplier]} underline]
          (.setDecorationStyle style (DecorationStyle.
                                       true false false false
                                       (unchecked-int (or color 0xFF000000))
                                       (resolve-decoration-style style)
                                       (float (or thickness-multiplier 1.0)))))
        (.setDecorationStyle style (DecorationStyle.
                                     true false false false
                                     0xFF000000
                                     DecorationLineStyle/SOLID
                                     1.0))))

    (when strikethrough
      (if (map? strikethrough)
        (let [{:keys [style color thickness-multiplier]} strikethrough]
          (.setDecorationStyle style (DecorationStyle.
                                       false false true false
                                       (unchecked-int (or color 0xFF000000))
                                       (resolve-decoration-style style)
                                       (float (or thickness-multiplier 1.0)))))
        (.setDecorationStyle style (DecorationStyle.
                                     false false true false
                                     0xFF000000
                                     DecorationLineStyle/SOLID
                                     1.0))))

    ;; Spacing
    (when letter-spacing (.setLetterSpacing style (float letter-spacing)))
    (when word-spacing (.setWordSpacing style (float word-spacing)))
    (when line-height (.setHeight style (float line-height)))
    (when baseline-shift (.setBaselineShift style (float baseline-shift)))

    style))

;; ============================================================
;; Paragraph Creation
;; ============================================================

(defn paragraph
  "Create a multi-line paragraph with word wrap.

   Args:
     text - string to render
     opts - options map:

   Layout Options:
     :width      - wrap width (required for layout)
     :max-lines  - maximum number of lines
     :ellipsis   - ellipsis string for truncation (e.g. \"...\")
     :align      - :left, :center, :right, :justify (default: :left)
     :direction  - :ltr, :rtl (default: :ltr)

   Font Options:
     :size   - font size in points (default: 14)
     :weight - :normal, :bold, or 100-900
     :slant  - :upright, :italic
     :family - font family name

   Style Options:
     :color            - text color (32-bit ARGB)
     :background-color - text background color
     :underline        - true or {:style :wavy :color 0xFF...}
     :strikethrough    - true or {:style :solid :color 0xFF...}
     :letter-spacing   - additional spacing between characters
     :word-spacing     - additional spacing between words
     :line-height      - line height multiplier

   Returns: Paragraph object (call layout! before drawing)

   Examples:
     (def p (paragraph \"Hello World\" {:width 300}))
     (draw canvas p 50 50)

     (paragraph \"Long text...\" {:width 300 :max-lines 3 :ellipsis \"...\"})"
  [text opts]
  (let [width (get opts :width Float/MAX_VALUE)
        para-style (build-paragraph-style opts)
        text-style (build-text-style opts)
        builder (doto (ParagraphBuilder. para-style font-collection)
                  (.pushStyle text-style)
                  (.addText (str text))
                  (.popStyle))]
    (doto (.build builder)
      (.layout (float width)))))

(defn rich-text
  "Create a paragraph with mixed styles (rich text).

   Args:
     opts  - paragraph-level options (same as paragraph)
     spans - vector of text spans, each with:
             :text  - string content (required)
             Plus any style options from paragraph

   Returns: Paragraph object

   Examples:
     (rich-text {:width 300}
       [{:text \"Hello \" :size 24}
        {:text \"World\" :size 24 :weight :bold :color 0xFF0000FF}])"
  [opts spans]
  (let [width (get opts :width Float/MAX_VALUE)
        para-style (build-paragraph-style opts)
        builder (ParagraphBuilder. para-style font-collection)]
    (doseq [span spans]
      (let [style (build-text-style (merge opts span))]
        (.pushStyle builder style)
        (.addText builder (str (:text span)))
        (.popStyle builder)))
    (doto (.build builder)
      (.layout (float width)))))

;; ============================================================
;; Drawing
;; ============================================================

(defn draw
  "Draw a paragraph at the given position.

   Args:
     canvas    - drawing canvas
     paragraph - Paragraph object
     x, y      - top-left position

   Examples:
     (draw canvas (paragraph \"Hello\" {:width 300}) 50 50)"
  [^Canvas canvas ^Paragraph para x y]
  (.paint para canvas (float x) (float y)))

;; ============================================================
;; Measurement
;; ============================================================

(defn height
  "Get the total height of a paragraph.

   Returns: height in pixels (float)"
  [^Paragraph para]
  (.getHeight para))

(defn longest-line
  "Get the width of the longest line.

   Returns: width in pixels (float)"
  [^Paragraph para]
  (.getLongestLine para))

(defn max-width
  "Get the maximum width used by the paragraph.

   Returns: width in pixels (float)"
  [^Paragraph para]
  (.getMaxWidth para))

(defn min-intrinsic-width
  "Get the minimum width needed (word-break at every opportunity).

   Returns: width in pixels (float)"
  [^Paragraph para]
  (.getMinIntrinsicWidth para))

(defn max-intrinsic-width
  "Get the maximum width needed (no word breaks).

   Returns: width in pixels (float)"
  [^Paragraph para]
  (.getMaxIntrinsicWidth para))

(defn line-count
  "Get the number of lines in the paragraph.

   Returns: line count (int)"
  [^Paragraph para]
  (.getLineCount para))

;; ============================================================
;; Hit Testing
;; ============================================================

(defn index-at-point
  "Get character index at a point.

   Args:
     para - Paragraph object
     x, y - coordinates relative to paragraph origin

   Returns: {:index n :affinity :upstream|:downstream}
            index is the character position
            affinity indicates cursor position at line breaks"
  [^Paragraph para x y]
  (let [pos (.getGlyphPositionAtCoordinate para (float x) (float y))]
    {:index (.getPosition pos)
     :affinity (if (= (.getAffinity pos) io.github.humbleui.skija.paragraph.Affinity/UPSTREAM)
                 :upstream
                 :downstream)}))

(defn position-at-index
  "Get x coordinate for a character index.

   Args:
     para  - Paragraph object
     index - character index

   Returns: float (x coordinate)"
  [^Paragraph para index]
  (let [rects (.getRectsForRange para (int index) (int (inc index))
                                  io.github.humbleui.skija.paragraph.RectHeightMode/TIGHT
                                  io.github.humbleui.skija.paragraph.RectWidthMode/TIGHT)]
    (if (seq rects)
      (.getLeft (first rects))
      0.0)))

(defn rects-for-range
  "Get bounding rectangles for a character range.

   Args:
     para  - Paragraph object
     start - start character index
     end   - end character index

   Returns: [{:x :y :width :height :direction} ...]
            direction is :ltr or :rtl"
  [^Paragraph para start end]
  (let [rects (.getRectsForRange para (int start) (int end)
                                  io.github.humbleui.skija.paragraph.RectHeightMode/TIGHT
                                  io.github.humbleui.skija.paragraph.RectWidthMode/TIGHT)]
    (mapv (fn [box]
            {:x (.getLeft (.getRect box))
             :y (.getTop (.getRect box))
             :width (.getWidth (.getRect box))
             :height (.getHeight (.getRect box))
             :direction (if (= (.getDirection box) Direction/LTR) :ltr :rtl)})
          rects)))

(defn word-boundary
  "Get word boundary at a character index.

   Args:
     para  - Paragraph object
     index - character index

   Returns: {:start :end}"
  [^Paragraph para index]
  (let [range (.getWordBoundary para (int index))]
    {:start (.getStart range)
     :end (.getEnd range)}))

;; ============================================================
;; Layout
;; ============================================================

(defn layout!
  "Re-layout paragraph with a new width.

   Args:
     para  - Paragraph object
     width - new wrap width

   Returns: para (for chaining)"
  [^Paragraph para width]
  (.layout para (float width))
  para)

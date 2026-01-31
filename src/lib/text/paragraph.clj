(ns lib.text.paragraph
  "Multi-line text with word wrap and rich styling.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:require [lib.text.core :as core]
            [lib.color.core :as color])
  (:import [io.github.humbleui.skija Canvas Paint FontMgr FontStyle FontSlant FontFeature Color4f]
           [io.github.humbleui.skija.paragraph
            Paragraph ParagraphBuilder ParagraphStyle TextStyle
            FontCollection Alignment Direction DecorationStyle
            DecorationLineStyle BaselineMode HeightMode
            LineMetrics StrutStyle PlaceholderStyle PlaceholderAlignment]))

;; ============================================================
;; Font Collection (Singleton)
;; ============================================================

;; Shared FontCollection for paragraph system.
(defonce ^:private font-collection
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

(defn- resolve-placeholder-alignment
  "Convert placeholder alignment keyword to PlaceholderAlignment enum."
  [align]
  (case align
    :baseline PlaceholderAlignment/BASELINE
    :above-baseline PlaceholderAlignment/ABOVE_BASELINE
    :below-baseline PlaceholderAlignment/BELOW_BASELINE
    :top PlaceholderAlignment/TOP
    :bottom PlaceholderAlignment/BOTTOM
    :middle PlaceholderAlignment/MIDDLE
    PlaceholderAlignment/BASELINE))

(defn- resolve-baseline-mode
  "Convert baseline mode keyword to BaselineMode enum."
  [mode]
  (case mode
    :alphabetic BaselineMode/ALPHABETIC
    :ideographic BaselineMode/IDEOGRAPHIC
    BaselineMode/ALPHABETIC))

(defn- color->int
  "Convert [r g b a] float color to 32-bit ARGB int for Skija APIs."
  [[r g b a]]
  (color/color4f->hex [r g b (or a 1.0)]))

(defn- build-font-features
  "Build FontFeature array from flexible input.

   Accepts:
     String \"tnum\" or \"cv06 cv07\" → FontFeature/parse
     Vector [\"cv06\" \"cv07\"] → individual FontFeature objects
     FontFeature objects → pass through

   Returns: FontFeature[]"
  [features]
  (cond
    (string? features)
    (FontFeature/parse features)

    (sequential? features)
    (into-array FontFeature
      (map (fn [f]
             (if (instance? FontFeature f)
               f
               (first (FontFeature/parse (str f)))))
           features))

    (instance? FontFeature features)
    (into-array FontFeature [features])

    :else
    (FontFeature/parse (str features))))

(defn- build-strut-style
  "Build StrutStyle from options map.

   Options:
     :font-size       - font size (float)
     :height          - line height multiplier (float)
     :leading         - extra leading (float)
     :families        - vector of font family names
     :weight          - font weight keyword or number
     :slant           - :italic, :oblique, or nil
     :enabled         - whether strut is active (default true)
     :force-height    - force strut height even if text is taller
     :override-height - override calculated height"
  [opts]
  (let [{:keys [font-size height leading families weight slant
                enabled force-height override-height]} opts
        style (StrutStyle.)]
    (when font-size (.setFontSize style (float font-size)))
    (when height (.setHeight style (float height)))
    (when leading (.setLeading style (float leading)))
    (when families
      (.setFontFamilies style (into-array String (map str families))))
    (when (or weight slant)
      (let [w (resolve-weight weight)
            s (case slant
                :italic FontSlant/ITALIC
                :oblique FontSlant/OBLIQUE
                FontSlant/UPRIGHT)]
        (.setFontStyle style (FontStyle. w 5 s))))
    (.setEnabled style (if (nil? enabled) true (boolean enabled)))
    (when force-height (.setHeightForced style (boolean force-height)))
    (when override-height (.setHeightOverridden style (boolean override-height)))
    style))

(defn- build-paragraph-style
  "Build ParagraphStyle from options."
  [opts]
  (let [{:keys [align direction max-lines ellipsis height-mode strut]} opts
        style (ParagraphStyle.)]
    (when align (.setAlignment style (resolve-alignment align)))
    (when direction (.setDirection style (resolve-direction direction)))
    (when max-lines (.setMaxLinesCount style (long max-lines)))
    (when ellipsis (.setEllipsis style (str ellipsis)))
    (when height-mode
      (.setHeightMode style (case height-mode
                              :all HeightMode/ALL
                              :disable-first-ascent HeightMode/DISABLE_FIRST_ASCENT
                              :disable-last-descent HeightMode/DISABLE_LAST_DESCENT
                              :disable-all HeightMode/DISABLE_ALL
                              HeightMode/ALL)))
    (when strut (.setStrutStyle style (build-strut-style strut)))
    style))

(defn- build-text-style
  "Build TextStyle from options."
  [opts]
  (let [{:keys [size weight slant family color background-color
                underline strikethrough letter-spacing word-spacing
                line-height baseline-mode features]} opts
        style (TextStyle.)]
    ;; Font properties
    (when size (.setFontSize style (float size)))
    (when family
      (if (sequential? family)
        (.setFontFamilies style (into-array String (map str family)))
        (.setFontFamilies style (into-array String [(str family)]))))
    (when (or weight slant)
      (let [w (resolve-weight weight)
            s (case slant
                :italic FontSlant/ITALIC
                :oblique FontSlant/OBLIQUE
                FontSlant/UPRIGHT)]
        (.setFontStyle style (FontStyle. w 5 s))))

    ;; Colors - [r g b a] floats (0.0-1.0)
    (when color (.setColor style (color->int color)))
    (when background-color
      (let [[r g b a] background-color]
        (.setBackground style (doto (Paint.)
                                (.setColor4f (Color4f. (float r) (float g) (float b) (float (or a 1.0))))))))

    ;; Decorations - [r g b a] floats for :color
    (when underline
      (if (map? underline)
        (let [{line-style :style ucolor :color thickness-multiplier :thickness-multiplier} underline
              ^DecorationLineStyle dls (resolve-decoration-style line-style)
              color-int (if ucolor (color->int ucolor) (color->int [0 0 0 1]))]
          (.setDecorationStyle style (DecorationStyle.
                                       true false false false
                                       color-int
                                       dls
                                       (float (or thickness-multiplier 1.0)))))
        (.setDecorationStyle style (DecorationStyle.
                                     true false false false
                                     (color->int [0 0 0 1])
                                     DecorationLineStyle/SOLID
                                     (float 1.0)))))

    (when strikethrough
      (if (map? strikethrough)
        (let [{line-style :style scolor :color thickness-multiplier :thickness-multiplier} strikethrough
              ^DecorationLineStyle dls (resolve-decoration-style line-style)
              color-int (if scolor (color->int scolor) (color->int [0 0 0 1]))]
          (.setDecorationStyle style (DecorationStyle.
                                       false false true false
                                       color-int
                                       dls
                                       (float (or thickness-multiplier 1.0)))))
        (.setDecorationStyle style (DecorationStyle.
                                     false false true false
                                     (color->int [0 0 0 1])
                                     DecorationLineStyle/SOLID
                                     (float 1.0)))))

    ;; Spacing
    (when letter-spacing (.setLetterSpacing style (float letter-spacing)))
    (when word-spacing (.setWordSpacing style (float word-spacing)))
    (when line-height (.setHeight style (float line-height)))
    (when baseline-mode
      (.setBaselineMode style (resolve-baseline-mode baseline-mode)))

    ;; Font features
    (when features (.addFontFeatures style (build-font-features features)))

    style))

(defn- build-placeholder-style
  "Build PlaceholderStyle from options map.

   Options:
     :width         - placeholder width (required, float)
     :height        - placeholder height (required, float)
     :align         - alignment keyword (default :baseline)
     :baseline-mode - :alphabetic or :ideographic (default :alphabetic)
     :baseline      - baseline offset (float, default 0)"
  [opts]
  (let [{:keys [width height align baseline-mode baseline]} opts]
    (PlaceholderStyle.
      (float (or width 0))
      (float (or height 0))
      (resolve-placeholder-alignment (or align :baseline))
      (resolve-baseline-mode (or baseline-mode :alphabetic))
      (float (or baseline 0)))))

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
     :size     - font size in points (default: 14)
     :weight   - :normal, :bold, or 100-900
     :slant    - :upright, :italic
     :family   - font family name or vector of families for fallback
     :features - OpenType features: string \"tnum\", vector [\"cv06\" \"cv07\"],
                 or space-separated \"cv06 cv07\"

   Style Options:
     :color            - text color as [r g b a] floats (0.0-1.0)
     :background-color - text background color as [r g b a] floats
     :underline        - true or {:style :wavy :color [r g b a]}
     :strikethrough    - true or {:style :solid :color [r g b a]}
     :letter-spacing   - additional spacing between characters
     :word-spacing     - additional spacing between words
     :line-height      - line height multiplier

   Strut Options:
     :strut - map with {:font-size :height :leading :families :weight :slant
                        :enabled :force-height :override-height}

   Returns: Paragraph object (call layout! before drawing)

   Examples:
     (def p (paragraph \"Hello World\" {:width 300}))
     (draw canvas p 50 50)

     (paragraph \"Long text...\" {:width 300 :max-lines 3 :ellipsis \"...\"})
     (paragraph \"0123456789\" {:width 200 :features \"tnum\"})
     (paragraph \"Hello\" {:width 300 :family [\"Inter\" \"Apple Color Emoji\"]})"
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
     spans - vector of spans, each either:
             Text span: {:text \"content\" ...style-options...}
             Placeholder: {:placeholder true :width 24 :height 24
                           :align :middle :baseline-mode :alphabetic :baseline 0}

   Returns: Paragraph object

   Examples:
     (rich-text {:width 300}
       [{:text \"Hello \" :size 24}
        {:text \"World\" :size 24 :weight :bold :color [0 0 1 1]}])

     ;; With inline placeholder (e.g. for icon):
     (rich-text {:width 300}
       [{:text \"Click \"}
        {:placeholder true :width 24 :height 24 :align :middle}
        {:text \" to continue\"}])"
  [opts spans]
  (let [width (get opts :width Float/MAX_VALUE)
        para-style (build-paragraph-style opts)
        builder (ParagraphBuilder. para-style font-collection)]
    (doseq [span spans]
      (if (:placeholder span)
        (.addPlaceholder builder (build-placeholder-style span))
        (let [style (build-text-style (merge opts span))]
          (.pushStyle builder style)
          (.addText builder (str (:text span)))
          (.popStyle builder))))
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
  (.getLineNumber para))

(defn line-metrics
  "Get per-line layout metrics for a paragraph.

   Returns: vector of maps, one per line:
     :start-index             - first character index on this line
     :end-index               - last character index (exclusive)
     :end-excluding-ws        - end index excluding trailing whitespace
     :end-including-newline   - end index including newline character
     :hard-break              - true if line ends with a hard break
     :ascent                  - ascent for this line (positive value)
     :descent                 - descent for this line
     :unscaled-ascent         - ascent before any scaling
     :height                  - total line height
     :width                   - width of text content on this line
     :left                    - left edge of text on this line
     :baseline                - baseline y position
     :line-number             - zero-based line index"
  [^Paragraph para]
  (let [metrics (.getLineMetrics para)]
    (mapv (fn [^LineMetrics m]
            {:start-index (.getStartIndex m)
             :end-index (.getEndIndex m)
             :end-excluding-ws (.getEndExcludingWhitespaces m)
             :end-including-newline (.getEndIncludingNewline m)
             :hard-break (.isHardBreak m)
             :ascent (.getAscent m)
             :descent (.getDescent m)
             :unscaled-ascent (.getUnscaledAscent m)
             :height (.getHeight m)
             :width (.getWidth m)
             :left (.getLeft m)
             :baseline (.getBaseline m)
             :line-number (.getLineNumber m)})
          metrics)))

(defn exceeded-max-lines?
  "Check if the paragraph exceeded the maximum number of lines.

   Returns true when the text was truncated due to :max-lines limit.

   Returns: boolean"
  [^Paragraph para]
  (.didExceedMaxLines para))

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

(defn placeholder-rects
  "Get bounding rectangles for all placeholder spans.

   Returns: [{:x :y :width :height :direction} ...]
            Same shape as rects-for-range results."
  [^Paragraph para]
  (let [rects (.getRectsForPlaceholders para)]
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

;; ============================================================
;; In-Place Updates
;; ============================================================

(defn update-alignment!
  "Update paragraph alignment in-place (without rebuilding).

   Args:
     para  - Paragraph object
     align - alignment keyword (:left, :center, :right, :justify)

   Returns: para (for chaining)"
  [^Paragraph para align]
  (.updateAlignment para (resolve-alignment align))
  para)

(defn update-font-size!
  "Update font size for a character range in-place.

   Args:
     para - Paragraph object
     from - start character index
     to   - end character index
     size - new font size (float)

   Returns: para (for chaining)"
  [^Paragraph para from to size]
  (.updateFontSize para (int from) (int to) (float size))
  para)

(defn update-foreground!
  "Update foreground color for a character range in-place.

   Args:
     para  - Paragraph object
     from  - start character index
     to    - end character index
     color - [r g b a] floats (0.0-1.0)

   Returns: para (for chaining)"
  [^Paragraph para from to [r g b a]]
  (let [paint (doto (Paint.)
                (.setColor4f (Color4f. (float r) (float g) (float b) (float (or a 1.0)))))]
    (.updateForegroundPaint para (int from) (int to) paint))
  para)

(defn update-background!
  "Update background color for a character range in-place.

   Args:
     para  - Paragraph object
     from  - start character index
     to    - end character index
     color - [r g b a] floats (0.0-1.0)

   Returns: para (for chaining)"
  [^Paragraph para from to [r g b a]]
  (let [paint (doto (Paint.)
                (.setColor4f (Color4f. (float r) (float g) (float b) (float (or a 1.0)))))]
    (.updateBackgroundPaint para (int from) (int to) paint))
  para)

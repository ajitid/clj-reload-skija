(ns lib.text.core
  "Basic text drawing with font styles, alignment, and variable fonts.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:require [lib.graphics.state :as gfx])
  (:import [io.github.humbleui.skija
            Canvas Paint Font FontMgr FontStyle FontSlant FontWeight FontWidth Typeface
            FontVariation FontVariationAxis FontHinting TextLine FontFeature]
           [io.github.humbleui.skija.shaper ShapingOptions]))

;; ============================================================
;; Weight/Slant Mappings
;; ============================================================

(def ^:private weights
  "Keyword to FontWeight mapping."
  {:thin 100 :extra-light 200 :light 300 :normal 400
   :medium 500 :semi-bold 600 :bold 700 :extra-bold 800 :black 900})

(def ^:private slants
  "Keyword to FontSlant mapping."
  {:upright FontSlant/UPRIGHT
   :italic FontSlant/ITALIC
   :oblique FontSlant/OBLIQUE})

(defn- resolve-weight
  "Convert weight keyword or number to int."
  [w]
  (cond
    (nil? w) 400
    (keyword? w) (get weights w 400)
    (number? w) (int w)
    :else 400))

(defn- resolve-slant
  "Convert slant keyword to FontSlant."
  [s]
  (cond
    (nil? s) FontSlant/UPRIGHT
    (keyword? s) (get slants s FontSlant/UPRIGHT)
    :else s))

;; ============================================================
;; Font Caches
;; ============================================================

;; Cache: [family weight slant] -> Typeface
(defonce ^:private typeface-cache (atom {}))

;; Cache: [base-typeface variations-map] -> Typeface
(defonce ^:private varied-typeface-cache (atom {}))

;; Cache: [typeface size] -> Font
(defonce ^:private font-cache (atom {}))

(defn clear-font-cache!
  "Clear all font caches (useful for memory cleanup)."
  []
  (reset! typeface-cache {})
  (reset! varied-typeface-cache {})
  (reset! font-cache {}))

;; ============================================================
;; Typeface Management
;; ============================================================

(defn default-typeface
  "Get the default system typeface."
  []
  (.matchFamilyStyle (FontMgr/getDefault) nil FontStyle/NORMAL))

(defn get-typeface
  "Get a typeface by family, weight, and slant. Cached.

   Args:
     family - font family name (nil for system default)
     weight - :normal, :bold, or 100-900 (default: :normal)
     slant  - :upright, :italic, :oblique (default: :upright)

   Returns: Typeface instance (cached, do not close)"
  ([] (default-typeface))
  ([family] (get-typeface family :normal :upright))
  ([family weight] (get-typeface family weight :upright))
  ([family weight slant]
   (let [w (resolve-weight weight)
         s (resolve-slant slant)
         cache-key [family w s]]
     (if-let [cached (get @typeface-cache cache-key)]
       cached
       (let [style (FontStyle. w FontWidth/NORMAL s)
             typeface (.matchFamilyStyle (FontMgr/getDefault) family style)]
         (swap! typeface-cache assoc cache-key typeface)
         typeface)))))

(defn load-typeface
  "Load a typeface from a file path.

   Args:
     path - path to font file (.ttf, .otf, etc.)

   Returns: Typeface instance (do not close)"
  ([path]
   (load-typeface path 0))
  ([path ttc-index]
   (.makeFromFile (FontMgr/getDefault) path ttc-index)))

;; ============================================================
;; Variable Font Support
;; ============================================================

(defn variation-axes
  "Query available variation axes of a typeface.

   Returns: [{:tag \"wght\" :min 100.0 :default 400.0 :max 900.0} ...]
   Returns empty vector if typeface is not variable."
  [^Typeface typeface]
  (if-let [axes (.getVariationAxes typeface)]
    (mapv (fn [^FontVariationAxis axis]
            {:tag (.getTag axis)
             :min (.getMinValue axis)
             :default (.getDefaultValue axis)
             :max (.getMaxValue axis)})
          axes)
    []))

(defn- make-variations
  "Convert {:wght 700 :wdth 85} to FontVariation array."
  [variations-map]
  (into-array FontVariation
    (map (fn [[k v]]
           (FontVariation. (name k) (float v)))
         variations-map)))

(defn vary
  "Create a variant of typeface with given variations.

   Args:
     typeface   - base Typeface
     variations - map of axis tag to value, e.g. {:wght 700 :wdth 85}

   Returns: new Typeface with variations applied (cached)"
  [^Typeface typeface variations]
  (let [cache-key [typeface variations]]
    (if-let [cached (get @varied-typeface-cache cache-key)]
      cached
      (let [varied (.makeClone typeface (make-variations variations))]
        (swap! varied-typeface-cache assoc cache-key varied)
        varied))))

;; ============================================================
;; Font Management
;; ============================================================

(defn get-font
  "Get or create a cached font.

   Args:
     typeface - Typeface instance
     size     - font size in points
     animated - if true, configure for smooth animation (no hinting, subpixel)

   Returns: Font instance (cached, do not close)"
  ([typeface size]
   (get-font typeface size false))
  ([typeface size animated]
   (let [cache-key [typeface size animated]]
     (if-let [cached (get @font-cache cache-key)]
       cached
       (let [new-font (Font. typeface (float size))]
         (when animated
           ;; Configure for smooth animation:
           ;; - Subpixel positioning for smooth horizontal movement
           ;; - No hinting to prevent glyph snapping/jumping
           ;; - No baseline snapping for smooth vertical movement
           (.setSubpixel new-font true)
           (.setHinting new-font FontHinting/NONE)
           (.setBaselineSnapped new-font false))
         (swap! font-cache assoc cache-key new-font)
         new-font)))))

(defn make-font
  "Create a Font from options map. Cached.

   Options:
     :size       - font size in points (default: 14)
     :weight     - :normal, :bold, or 100-900 (default: :normal)
     :slant      - :upright, :italic, :oblique (default: :upright)
     :family     - font family name (default: system)
     :typeface   - explicit Typeface (overrides family/weight/slant)
     :variations - variable font axes {:wght 700 :wdth 85}
     :animated   - if true, configure for smooth animation (default: false)

   Returns: Font instance (cached, do not close)

   Examples:
     (make-font {:size 24})
     (make-font {:size 24 :weight :bold})
     (make-font {:family \"SF Pro\" :size 18 :variations {:wght 600}})"
  ([] (make-font {}))
  ([opts]
   (let [{:keys [size weight slant family typeface variations animated]
          :or {size 14 weight :normal slant :upright animated false}} opts
         base-typeface (or typeface (get-typeface family weight slant))
         final-typeface (if (and variations (seq variations))
                          (vary base-typeface variations)
                          base-typeface)]
     (get-font final-typeface size animated))))

;; ============================================================
;; Alignment
;; ============================================================

(defn- align-x
  "Calculate x offset for text alignment.

   Args:
     font  - Font instance
     text  - string to measure
     x     - original x position
     align - :left, :center, or :right

   Returns: adjusted x position"
  [^Font font text x align]
  (case align
    :left x
    :center (- x (/ (.measureTextWidth font (str text)) 2))
    :right (- x (.measureTextWidth font (str text)))
    x))

;; ============================================================
;; Text Drawing
;; ============================================================

(defn- draw-text-simple
  "Draw text using simple drawString (no font features)."
  [^Canvas canvas text-str ^Font font x y paint-opts]
  (if-let [paint (:paint paint-opts)]
    (.drawString canvas text-str (float x) (float y) font paint)
    (gfx/with-paint [paint paint-opts]
      (.drawString canvas text-str (float x) (float y) font paint))))

(defn- draw-text-with-features
  "Draw text using TextLine with font features (e.g., tnum for tabular numbers).

   Note: alignment must be calculated using TextLine width (which respects features)
   rather than Font.measureTextWidth (which doesn't apply features)."
  [^Canvas canvas text-str ^Font font x y align features paint-opts]
  (let [shaping-opts (.withFeatures ShapingOptions/DEFAULT features)
        text-line (TextLine/make text-str font shaping-opts)
        ;; Use TextLine width for alignment - this respects font features like tnum
        text-width (.getWidth text-line)
        final-x (case align
                  :left x
                  :center (- x (/ text-width 2))
                  :right (- x text-width)
                  x)
        blob (.getTextBlob text-line)]
    (when blob
      (if-let [paint (:paint paint-opts)]
        (.drawTextBlob canvas blob (float final-x) (float y) paint)
        (gfx/with-paint [paint paint-opts]
          (.drawTextBlob canvas blob (float final-x) (float y) paint))))))

(defn text
  "Draw text at the given position.

   Args:
     canvas - drawing canvas
     text   - string to draw
     x, y   - position (y is baseline)
     opts   - optional map:

   Font Options:
     :size       - font size in points (default: 14)
     :weight     - :normal, :bold, or 100-900 (default: :normal)
     :slant      - :upright, :italic, :oblique (default: :upright)
     :family     - font family name (default: system)
     :typeface   - explicit Typeface (overrides family/weight/slant)
     :variations - variable font axes {:wght 700 :wdth 85}
     :align      - :left, :center, :right (default: :left)
     :features   - OpenType features string, e.g. \"tnum\" for tabular numbers

   Paint Options (same as shapes):
     :color, :shadow, :blur, :gradient, :alphaf, etc.

   Examples:
     (text canvas \"Hello\" 10 20)
     (text canvas \"Hello\" 10 20 {:color 0xFF4A90D9})
     (text canvas \"Bold\" 10 50 {:size 24 :weight :bold})
     (text canvas \"Centered\" 200 80 {:align :center})
     (text canvas \"Variable\" 10 110 {:size 48 :variations {:wght 600}})
     (text canvas \"123\" 10 140 {:features \"tnum\"})  ;; tabular numbers"
  ([^Canvas canvas text x y]
   (text canvas text x y {}))
  ([^Canvas canvas text x y opts]
   (let [font (make-font opts)
         align (get opts :align :left)
         text-str (str text)
         features (:features opts)
         ;; Remove text-specific opts before passing to paint
         paint-opts (dissoc opts :size :weight :slant :family :typeface
                           :variations :align :font :features :animated)]
     (if features
       ;; When features are used, alignment is calculated inside draw-text-with-features
       ;; using TextLine width (which respects features like tnum)
       (draw-text-with-features canvas text-str font x y align features paint-opts)
       ;; Without features, use Font.measureTextWidth for alignment
       (let [final-x (align-x font text-str x align)]
         (draw-text-simple canvas text-str font final-x y paint-opts))))))

(defn draw-line
  "Draw a pre-built TextLine at the given position.

   Use when you need the same TextLine for both measurement and rendering
   (e.g., text fields where cursor positioning must match rendered glyphs).

   Args:
     canvas - drawing canvas
     line   - TextLine instance (from measure/text-line)
     x, y   - position (y is baseline)
     opts   - paint options: :color, :shadow, :blur, :gradient, :alphaf, etc.

   Examples:
     (let [line (measure/text-line \"Hello\" {:size 24})]
       (draw-line canvas line 10 20 {:color 0xFF4A90D9}))"
  [^Canvas canvas ^TextLine line x y opts]
  (if-let [paint (:paint opts)]
    (.drawTextLine canvas line (float x) (float y) paint)
    (gfx/with-paint [paint opts]
      (.drawTextLine canvas line (float x) (float y) paint))))

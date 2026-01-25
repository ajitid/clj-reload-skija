(ns lib.text.core
  "Basic text drawing with font styles, alignment, and variable fonts.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:require [lib.graphics.state :as gfx])
  (:import [io.github.humbleui.skija
            Canvas Paint Font FontMgr FontStyle FontSlant FontWeight Typeface
            FontVariation FontVariationAxis]))

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

(defonce ^:private typeface-cache
  "Cache: [family weight slant] -> Typeface"
  (atom {}))

(defonce ^:private varied-typeface-cache
  "Cache: [base-typeface variations-map] -> Typeface"
  (atom {}))

(defonce ^:private font-cache
  "Cache: [typeface size] -> Font"
  (atom {}))

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
       (let [style (FontStyle. w FontStyle/WIDTH_NORMAL s)
             typeface (.matchFamilyStyle (FontMgr/getDefault) family style)]
         (swap! typeface-cache assoc cache-key typeface)
         typeface)))))

(defn load-typeface
  "Load a typeface from a file path.

   Args:
     path - path to font file (.ttf, .otf, etc.)

   Returns: Typeface instance (do not close)"
  [path]
  (Typeface/makeFromFile path))

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

   Returns: Font instance (cached, do not close)"
  [typeface size]
  (let [cache-key [typeface size]]
    (if-let [cached (get @font-cache cache-key)]
      cached
      (let [new-font (Font. typeface (float size))]
        (swap! font-cache assoc cache-key new-font)
        new-font))))

(defn- resolve-font
  "Resolve font from options map.

   Options:
     :size       - font size in points (default: 14)
     :weight     - :normal, :bold, or 100-900 (default: :normal)
     :slant      - :upright, :italic, :oblique (default: :upright)
     :family     - font family name (default: system)
     :typeface   - explicit Typeface (overrides family/weight/slant)
     :variations - variable font axes {:wght 700 :wdth 85}

   Returns: Font instance"
  [opts]
  (let [{:keys [size weight slant family typeface variations]
         :or {size 14 weight :normal slant :upright}} opts
        base-typeface (or typeface (get-typeface family weight slant))
        final-typeface (if (and variations (seq variations))
                         (vary base-typeface variations)
                         base-typeface)]
    (get-font final-typeface size)))

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

   Paint Options (same as shapes):
     :color, :shadow, :blur, :gradient, :alphaf, etc.

   Examples:
     (text canvas \"Hello\" 10 20)
     (text canvas \"Hello\" 10 20 {:color 0xFF4A90D9})
     (text canvas \"Bold\" 10 50 {:size 24 :weight :bold})
     (text canvas \"Centered\" 200 80 {:align :center})
     (text canvas \"Variable\" 10 110 {:size 48 :variations {:wght 600}})"
  ([^Canvas canvas text x y]
   (text canvas text x y {}))
  ([^Canvas canvas text x y opts]
   (let [font (resolve-font opts)
         align (get opts :align :left)
         final-x (align-x font text x align)
         ;; Remove text-specific opts before passing to paint
         paint-opts (dissoc opts :size :weight :slant :family :typeface :variations :align :font)]
     (if-let [paint (:paint opts)]
       (.drawString canvas (str text) (float final-x) (float y) font paint)
       (gfx/with-paint [paint paint-opts]
         (.drawString canvas (str text) (float final-x) (float y) font paint))))))

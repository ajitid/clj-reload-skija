#!/usr/bin/env bb
;; Color conversion script - replaces hardcoded [r g b a] colors with Open Color references
;;
;; Uses delta-E*2000 for perceptually accurate color matching.
;;
;; Usage:
;;   bb scripts/convert_colors.clj --report           # Show what would change
;;   bb scripts/convert_colors.clj --apply            # Apply changes to files
;;   bb scripts/convert_colors.clj --report file.clj  # Check specific file

(require '[clojure.java.io :as io]
         '[clojure.string :as str])

;; ============================================================
;; Open Color Palette (hex -> [r g b a])
;; ============================================================

(defn hex->rgba
  "Convert hex string to [r g b a] floats."
  [hex-str]
  (let [hex (if (str/starts-with? hex-str "#") (subs hex-str 1) hex-str)
        r (/ (Integer/parseInt (subs hex 0 2) 16) 255.0)
        g (/ (Integer/parseInt (subs hex 2 4) 16) 255.0)
        b (/ (Integer/parseInt (subs hex 4 6) 16) 255.0)]
    [r g b 1.0]))

(def open-colors
  "Open Color palette with names and [r g b a] values."
  {"gray-0"   (hex->rgba "#f8f9fa")
   "gray-1"   (hex->rgba "#f1f3f5")
   "gray-2"   (hex->rgba "#e9ecef")
   "gray-3"   (hex->rgba "#dee2e6")
   "gray-4"   (hex->rgba "#ced4da")
   "gray-5"   (hex->rgba "#adb5bd")
   "gray-6"   (hex->rgba "#868e96")
   "gray-7"   (hex->rgba "#495057")
   "gray-8"   (hex->rgba "#343a40")
   "gray-9"   (hex->rgba "#212529")
   "red-0"    (hex->rgba "#fff5f5")
   "red-1"    (hex->rgba "#ffe3e3")
   "red-2"    (hex->rgba "#ffc9c9")
   "red-3"    (hex->rgba "#ffa8a8")
   "red-4"    (hex->rgba "#ff8787")
   "red-5"    (hex->rgba "#ff6b6b")
   "red-6"    (hex->rgba "#fa5252")
   "red-7"    (hex->rgba "#f03e3e")
   "red-8"    (hex->rgba "#e03131")
   "red-9"    (hex->rgba "#c92a2a")
   "pink-0"   (hex->rgba "#fff0f6")
   "pink-1"   (hex->rgba "#ffdeeb")
   "pink-2"   (hex->rgba "#fcc2d7")
   "pink-3"   (hex->rgba "#faa2c1")
   "pink-4"   (hex->rgba "#f783ac")
   "pink-5"   (hex->rgba "#f06595")
   "pink-6"   (hex->rgba "#e64980")
   "pink-7"   (hex->rgba "#d6336c")
   "pink-8"   (hex->rgba "#c2255c")
   "pink-9"   (hex->rgba "#a61e4d")
   "grape-0"  (hex->rgba "#f8f0fc")
   "grape-1"  (hex->rgba "#f3d9fa")
   "grape-2"  (hex->rgba "#eebefa")
   "grape-3"  (hex->rgba "#e599f7")
   "grape-4"  (hex->rgba "#da77f2")
   "grape-5"  (hex->rgba "#cc5de8")
   "grape-6"  (hex->rgba "#be4bdb")
   "grape-7"  (hex->rgba "#ae3ec9")
   "grape-8"  (hex->rgba "#9c36b5")
   "grape-9"  (hex->rgba "#862e9c")
   "violet-0" (hex->rgba "#f3f0ff")
   "violet-1" (hex->rgba "#e5dbff")
   "violet-2" (hex->rgba "#d0bfff")
   "violet-3" (hex->rgba "#b197fc")
   "violet-4" (hex->rgba "#9775fa")
   "violet-5" (hex->rgba "#845ef7")
   "violet-6" (hex->rgba "#7950f2")
   "violet-7" (hex->rgba "#7048e8")
   "violet-8" (hex->rgba "#6741d9")
   "violet-9" (hex->rgba "#5f3dc4")
   "indigo-0" (hex->rgba "#edf2ff")
   "indigo-1" (hex->rgba "#dbe4ff")
   "indigo-2" (hex->rgba "#bac8ff")
   "indigo-3" (hex->rgba "#91a7ff")
   "indigo-4" (hex->rgba "#748ffc")
   "indigo-5" (hex->rgba "#5c7cfa")
   "indigo-6" (hex->rgba "#4c6ef5")
   "indigo-7" (hex->rgba "#4263eb")
   "indigo-8" (hex->rgba "#3b5bdb")
   "indigo-9" (hex->rgba "#364fc7")
   "blue-0"   (hex->rgba "#e7f5ff")
   "blue-1"   (hex->rgba "#d0ebff")
   "blue-2"   (hex->rgba "#a5d8ff")
   "blue-3"   (hex->rgba "#74c0fc")
   "blue-4"   (hex->rgba "#4dabf7")
   "blue-5"   (hex->rgba "#339af0")
   "blue-6"   (hex->rgba "#228be6")
   "blue-7"   (hex->rgba "#1c7ed6")
   "blue-8"   (hex->rgba "#1971c2")
   "blue-9"   (hex->rgba "#1864ab")
   "cyan-0"   (hex->rgba "#e3fafc")
   "cyan-1"   (hex->rgba "#c5f6fa")
   "cyan-2"   (hex->rgba "#99e9f2")
   "cyan-3"   (hex->rgba "#66d9e8")
   "cyan-4"   (hex->rgba "#3bc9db")
   "cyan-5"   (hex->rgba "#22b8cf")
   "cyan-6"   (hex->rgba "#15aabf")
   "cyan-7"   (hex->rgba "#1098ad")
   "cyan-8"   (hex->rgba "#0c8599")
   "cyan-9"   (hex->rgba "#0b7285")
   "teal-0"   (hex->rgba "#e6fcf5")
   "teal-1"   (hex->rgba "#c3fae8")
   "teal-2"   (hex->rgba "#96f2d7")
   "teal-3"   (hex->rgba "#63e6be")
   "teal-4"   (hex->rgba "#38d9a9")
   "teal-5"   (hex->rgba "#20c997")
   "teal-6"   (hex->rgba "#12b886")
   "teal-7"   (hex->rgba "#0ca678")
   "teal-8"   (hex->rgba "#099268")
   "teal-9"   (hex->rgba "#087f5b")
   "green-0"  (hex->rgba "#ebfbee")
   "green-1"  (hex->rgba "#d3f9d8")
   "green-2"  (hex->rgba "#b2f2bb")
   "green-3"  (hex->rgba "#8ce99a")
   "green-4"  (hex->rgba "#69db7c")
   "green-5"  (hex->rgba "#51cf66")
   "green-6"  (hex->rgba "#40c057")
   "green-7"  (hex->rgba "#37b24d")
   "green-8"  (hex->rgba "#2f9e44")
   "green-9"  (hex->rgba "#2b8a3e")
   "lime-0"   (hex->rgba "#f4fce3")
   "lime-1"   (hex->rgba "#e9fac8")
   "lime-2"   (hex->rgba "#d8f5a2")
   "lime-3"   (hex->rgba "#c0eb75")
   "lime-4"   (hex->rgba "#a9e34b")
   "lime-5"   (hex->rgba "#94d82d")
   "lime-6"   (hex->rgba "#82c91e")
   "lime-7"   (hex->rgba "#74b816")
   "lime-8"   (hex->rgba "#66a80f")
   "lime-9"   (hex->rgba "#5c940d")
   "yellow-0" (hex->rgba "#fff9db")
   "yellow-1" (hex->rgba "#fff3bf")
   "yellow-2" (hex->rgba "#ffec99")
   "yellow-3" (hex->rgba "#ffe066")
   "yellow-4" (hex->rgba "#ffd43b")
   "yellow-5" (hex->rgba "#fcc419")
   "yellow-6" (hex->rgba "#fab005")
   "yellow-7" (hex->rgba "#f59f00")
   "yellow-8" (hex->rgba "#f08c00")
   "yellow-9" (hex->rgba "#e67700")
   "orange-0" (hex->rgba "#fff4e6")
   "orange-1" (hex->rgba "#ffe8cc")
   "orange-2" (hex->rgba "#ffd8a8")
   "orange-3" (hex->rgba "#ffc078")
   "orange-4" (hex->rgba "#ffa94d")
   "orange-5" (hex->rgba "#ff922b")
   "orange-6" (hex->rgba "#fd7e14")
   "orange-7" (hex->rgba "#f76707")
   "orange-8" (hex->rgba "#e8590c")
   "orange-9" (hex->rgba "#d9480f")})

;; ============================================================
;; Color Space Conversions (RGB -> Lab for delta-E*2000)
;; ============================================================

(defn rgb->xyz
  "Convert sRGB [r g b] (0-1) to XYZ color space."
  [[r g b]]
  (let [;; Linearize sRGB
        linearize (fn [v]
                    (if (<= v 0.04045)
                      (/ v 12.92)
                      (Math/pow (/ (+ v 0.055) 1.055) 2.4)))
        r' (linearize r)
        g' (linearize g)
        b' (linearize b)]
    ;; Matrix multiplication (D65 illuminant)
    [(+ (* r' 0.4124564) (* g' 0.3575761) (* b' 0.1804375))
     (+ (* r' 0.2126729) (* g' 0.7151522) (* b' 0.0721750))
     (+ (* r' 0.0193339) (* g' 0.1191920) (* b' 0.9503041))]))

(defn xyz->lab
  "Convert XYZ to CIE Lab (D65 white point)."
  [[x y z]]
  (let [;; D65 white point
        xn 0.95047
        yn 1.00000
        zn 1.08883
        ;; Lab transformation function
        f (fn [t]
            (if (> t 0.008856)
              (Math/pow t (/ 1.0 3.0))
              (+ (* 7.787 t) (/ 16.0 116.0))))
        fx (f (/ x xn))
        fy (f (/ y yn))
        fz (f (/ z zn))]
    [(- (* 116.0 fy) 16.0)  ;; L*
     (* 500.0 (- fx fy))    ;; a*
     (* 200.0 (- fy fz))])) ;; b*

(defn rgb->lab
  "Convert RGB [r g b] (0-1) to CIE Lab."
  [rgb]
  (xyz->lab (rgb->xyz rgb)))

;; ============================================================
;; Delta-E*2000 (CIEDE2000)
;; ============================================================

(defn delta-e-2000
  "Calculate CIEDE2000 color difference between two Lab colors.
   Returns a perceptual distance (0 = identical, ~2.3 = JND)."
  [[L1 a1 b1] [L2 a2 b2]]
  (let [;; Mean L'
        L-bar (/ (+ L1 L2) 2.0)
        ;; C1, C2 (chroma in ab plane)
        C1 (Math/sqrt (+ (* a1 a1) (* b1 b1)))
        C2 (Math/sqrt (+ (* a2 a2) (* b2 b2)))
        C-bar (/ (+ C1 C2) 2.0)
        ;; G factor
        C-bar-7 (Math/pow C-bar 7)
        G (* 0.5 (- 1.0 (Math/sqrt (/ C-bar-7 (+ C-bar-7 (Math/pow 25 7))))))
        ;; a' (adjusted a)
        a1' (* a1 (+ 1.0 G))
        a2' (* a2 (+ 1.0 G))
        ;; C' (chroma with adjusted a)
        C1' (Math/sqrt (+ (* a1' a1') (* b1 b1)))
        C2' (Math/sqrt (+ (* a2' a2') (* b2 b2)))
        C-bar' (/ (+ C1' C2') 2.0)
        ;; h' (hue angle)
        h1' (if (and (zero? a1') (zero? b1)) 0.0
                (let [h (Math/toDegrees (Math/atan2 b1 a1'))]
                  (if (neg? h) (+ h 360.0) h)))
        h2' (if (and (zero? a2') (zero? b2)) 0.0
                (let [h (Math/toDegrees (Math/atan2 b2 a2'))]
                  (if (neg? h) (+ h 360.0) h)))
        ;; Delta values
        dL' (- L2 L1)
        dC' (- C2' C1')
        ;; Delta h'
        dh' (cond
              (or (zero? C1') (zero? C2')) 0.0
              (<= (Math/abs (- h2' h1')) 180.0) (- h2' h1')
              (> (- h2' h1') 180.0) (- (- h2' h1') 360.0)
              :else (+ (- h2' h1') 360.0))
        dH' (* 2.0 (Math/sqrt (* C1' C2')) (Math/sin (Math/toRadians (/ dh' 2.0))))
        ;; H-bar' (mean hue)
        H-bar' (cond
                 (or (zero? C1') (zero? C2')) (+ h1' h2')
                 (<= (Math/abs (- h1' h2')) 180.0) (/ (+ h1' h2') 2.0)
                 (< (+ h1' h2') 360.0) (/ (+ h1' h2' 360.0) 2.0)
                 :else (/ (- (+ h1' h2') 360.0) 2.0))
        ;; T term
        T (- (+ 1.0
                (* -0.17 (Math/cos (Math/toRadians (- H-bar' 30.0))))
                (* 0.24 (Math/cos (Math/toRadians (* 2.0 H-bar'))))
                (* 0.32 (Math/cos (Math/toRadians (+ (* 3.0 H-bar') 6.0)))))
             (* 0.20 (Math/cos (Math/toRadians (- (* 4.0 H-bar') 63.0)))))
        ;; SL, SC, SH
        L-bar-minus-50-sq (* (- L-bar 50.0) (- L-bar 50.0))
        SL (+ 1.0 (/ (* 0.015 L-bar-minus-50-sq)
                     (Math/sqrt (+ 20.0 L-bar-minus-50-sq))))
        SC (+ 1.0 (* 0.045 C-bar'))
        SH (+ 1.0 (* 0.015 C-bar' T))
        ;; RT (rotation term)
        dTheta (* 30.0 (Math/exp (- (Math/pow (/ (- H-bar' 275.0) 25.0) 2))))
        C-bar'-7 (Math/pow C-bar' 7)
        RC (* 2.0 (Math/sqrt (/ C-bar'-7 (+ C-bar'-7 (Math/pow 25 7)))))
        RT (- (* RC (Math/sin (Math/toRadians (* 2.0 dTheta)))))
        ;; kL, kC, kH (weighting factors - use 1.0 for reference)
        kL 1.0
        kC 1.0
        kH 1.0
        ;; Final calculation
        term1 (/ dL' (* kL SL))
        term2 (/ dC' (* kC SC))
        term3 (/ dH' (* kH SH))]
    (Math/sqrt (+ (* term1 term1)
                  (* term2 term2)
                  (* term3 term3)
                  (* RT term2 term3)))))

(defn color-distance
  "Calculate delta-E*2000 between two [r g b a] colors (ignores alpha)."
  [[r1 g1 b1 _] [r2 g2 b2 _]]
  (delta-e-2000 (rgb->lab [r1 g1 b1]) (rgb->lab [r2 g2 b2])))

;; ============================================================
;; Special Colors
;; ============================================================

;; Delta-E threshold - colors with distance > this are skipped
;; ≤5 = not easily noticeable difference (see CIEDE2000 guidelines)
(def delta-e-threshold 5.0)

(def special-colors
  "Colors that map to lib.color.core constants."
  {[1.0 1.0 1.0 1.0] {:name "color/white" :require-as nil}
   [0.0 0.0 0.0 1.0] {:name "color/black" :require-as nil}
   [0.0 0.0 0.0 0.0] {:name "color/transparent" :require-as nil}})

(defn approx-equal?
  "Check if two floats are approximately equal."
  [a b]
  (< (Math/abs (- a b)) 0.001))

(defn color-approx-equal?
  "Check if two colors are approximately equal."
  [[r1 g1 b1 a1] [r2 g2 b2 a2]]
  (and (approx-equal? r1 r2)
       (approx-equal? g1 g2)
       (approx-equal? b1 b2)
       (approx-equal? a1 a2)))

;; ============================================================
;; Find Nearest Open Color
;; ============================================================

(defn find-nearest-color
  "Find the nearest Open Color to the given [r g b a] color.
   Returns {:name \"color-name\" :distance delta-e :alpha alpha :skip? bool}."
  [[r g b a :as rgba]]
  ;; Check for pure white (with any alpha)
  (cond
    ;; Pure white (any alpha)
    (and (approx-equal? r 1.0) (approx-equal? g 1.0) (approx-equal? b 1.0))
    {:name "color/white" :distance 0.0 :alpha a :is-white true}

    ;; Pure black (any alpha)
    (and (approx-equal? r 0.0) (approx-equal? g 0.0) (approx-equal? b 0.0))
    {:name "color/black" :distance 0.0 :alpha a :is-black true}

    ;; Check special colors for exact match
    :else
    (if-let [special (some (fn [[spec-color info]]
                             (when (color-approx-equal? rgba spec-color)
                               info))
                           special-colors)]
      (assoc special :distance 0.0 :alpha a)
      ;; Find nearest Open Color
      (let [results (map (fn [[name oc-color]]
                           {:name name
                            :oc-color oc-color
                            :distance (color-distance rgba oc-color)})
                         open-colors)
            nearest (first (sort-by :distance results))]
        {:name (str "oc/" (:name nearest))
         :oc-name (:name nearest)
         :distance (:distance nearest)
         :alpha a
         :skip? (> (:distance nearest) delta-e-threshold)}))))

;; ============================================================
;; Parse Color Literals from Clojure Source
;; ============================================================

;; Regex to match [r g b a] color literals
;; Matches patterns like [0.333 0.333 0.333 1.0] or [1.0 0.41 0.71 1.0]
(def color-regex
  #"\[(\d+\.?\d*)\s+(\d+\.?\d*)\s+(\d+\.?\d*)\s+(\d+\.?\d*)\]")

(defn parse-float
  "Parse a float from string, handling various formats."
  [s]
  (Double/parseDouble s))

(defn parse-color-literal
  "Parse a color literal string into [r g b a] floats."
  [match-str]
  (when-let [m (re-matches color-regex match-str)]
    (let [[_ r g b a] m]
      [(parse-float r) (parse-float g) (parse-float b) (parse-float a)])))

(defn is-valid-color?
  "Check if parsed values form a valid color (all values in 0-1 range)."
  [[r g b a]]
  (and (<= 0.0 r 1.0)
       (<= 0.0 g 1.0)
       (<= 0.0 b 1.0)
       (<= 0.0 a 1.0)))

(defn find-colors-in-file
  "Find all color literals in a file with their positions.
   Returns seq of {:match original-text :color [r g b a] :line line-num :col column}."
  [file-path]
  (let [content (slurp file-path)
        lines (str/split-lines content)]
    (for [line-num (range (count lines))
          :let [line (nth lines line-num)]
          match (re-seq color-regex line)
          :let [match-str (first match)
                color (parse-color-literal match-str)]
          :when (and color (is-valid-color? color))]
      {:match match-str
       :color color
       :line (inc line-num)
       :col (inc (str/index-of line match-str))
       :line-text line})))

;; ============================================================
;; Generate Replacement
;; ============================================================

(defn format-replacement
  "Generate the replacement string for a color.
   - Full alpha (1.0): oc/blue-5 or color/white
   - Partial alpha: (color/with-alpha oc/blue-5 0.27)"
  [{:keys [name distance alpha is-white is-black]}]
  (cond
    ;; Pure white with full alpha
    (and is-white (approx-equal? alpha 1.0))
    "color/white"

    ;; Pure white with partial alpha
    is-white
    (str "(color/with-alpha color/white " alpha ")")

    ;; Pure black with full alpha
    (and is-black (approx-equal? alpha 1.0))
    "color/black"

    ;; Pure black with partial alpha
    is-black
    (str "(color/with-alpha color/black " alpha ")")

    ;; Other special colors
    (str/starts-with? name "color/")
    name

    ;; Full alpha
    (approx-equal? alpha 1.0)
    name

    ;; Partial alpha
    :else
    (str "(color/with-alpha " name " " alpha ")")))

;; ============================================================
;; Skip Patterns (colors in docstrings, comments)
;; ============================================================

(defn in-comment?
  "Check if position is in a comment (after ;;)."
  [line-text col]
  (let [before-match (subs line-text 0 (dec col))]
    (str/includes? before-match ";;")))

(defn in-docstring?
  "Check if the line appears to be in a docstring (heuristic)."
  [line-text]
  (or (str/includes? line-text "(default [")
      (and (str/includes? line-text "\"")
           (str/includes? line-text "[r g b a]"))))

(defn should-skip?
  "Check if a color match should be skipped."
  [{:keys [line-text col]}]
  (or (in-comment? line-text col)
      (in-docstring? line-text)))

;; ============================================================
;; File Analysis and Transformation
;; ============================================================

(defn analyze-file
  "Analyze a file and return all color replacements.
   Returns seq of {:file path :line num :match original :replacement new :distance delta-e}.
   Skips colors with delta-E > threshold and those in comments/docstrings."
  [file-path]
  (let [colors (find-colors-in-file file-path)]
    (for [color-info colors
          :when (not (should-skip? color-info))
          :let [nearest (find-nearest-color (:color color-info))]
          :when (not (:skip? nearest))
          :let [replacement (format-replacement nearest)]]
      {:file file-path
       :line (:line color-info)
       :col (:col color-info)
       :match (:match color-info)
       :color (:color color-info)
       :replacement replacement
       :oc-name (:oc-name nearest)
       :distance (:distance nearest)
       :alpha (:alpha nearest)})))

(defn apply-replacements
  "Apply replacements to a file's content.
   Returns the new content string."
  [content replacements]
  (reduce (fn [text {:keys [match replacement]}]
            (str/replace text match replacement))
          content
          ;; Sort by length desc to replace longer matches first
          (sort-by #(- (count (:match %))) replacements)))

(defn needs-oc-require?
  "Check if any replacement uses oc/ prefix."
  [replacements]
  (some #(str/starts-with? (:replacement %) "oc/") replacements))

(defn needs-color-require?
  "Check if any replacement uses color/ functions."
  [replacements]
  (some #(or (str/starts-with? (:replacement %) "color/")
             (str/starts-with? (:replacement %) "(color/"))
        replacements))

(defn has-require?
  "Check if the file already has a specific require alias."
  [content alias]
  (re-find (re-pattern (str "\\[.+:as " alias "\\]")) content))

(defn add-requires
  "Add necessary requires to a file's ns form."
  [content replacements]
  (let [needs-oc (needs-oc-require? replacements)
        needs-color (needs-color-require? replacements)
        has-oc (has-require? content "oc")
        has-color (has-require? content "color")
        ;; Build the requires to add
        new-requires (str
                       (when (and needs-color (not has-color))
                         "[lib.color.core :as color]\n            ")
                       (when (and needs-oc (not has-oc))
                         "[lib.color.open-color :as oc]\n            "))]
    (if (seq new-requires)
      (str/replace content #"(\(:require\s*)" (str "$1" new-requires))
      content)))

;; ============================================================
;; Main Operations
;; ============================================================

(defn find-clj-files
  "Find all .clj files in src/app/"
  []
  (->> (file-seq (io/file "src/app"))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".clj"))
       (map #(.getPath %))
       sort))

(defn print-report
  "Print a report of all replacements that would be made."
  [all-replacements]
  (doseq [[file replacements] (group-by :file all-replacements)]
    (println)
    (println (str file ":"))
    (doseq [{:keys [line match replacement distance]} (sort-by :line replacements)]
      (println (str "  L" line ": " match))
      (println (str "       -> " replacement " (delta-E: " (format "%.1f" distance) ")"))))
  (println)
  (println (str "Total: " (count all-replacements) " replacements in "
                (count (distinct (map :file all-replacements))) " files")))

(defn apply-to-file
  "Apply all replacements to a single file."
  [file-path replacements]
  (let [original-content (slurp file-path)
        with-replacements (apply-replacements original-content replacements)
        final-content (add-requires with-replacements replacements)]
    (spit file-path final-content)
    (println (str "Updated: " file-path " (" (count replacements) " colors)"))))

(defn apply-all
  "Apply all replacements to all files."
  [all-replacements]
  (doseq [[file replacements] (group-by :file all-replacements)]
    (apply-to-file file replacements))
  (println)
  (println (str "Applied " (count all-replacements) " replacements to "
                (count (distinct (map :file all-replacements))) " files")))

;; ============================================================
;; CLI Entry Point
;; ============================================================

(defn -main [& args]
  (let [mode (first args)
        file-filter (second args)
        files (if file-filter
                [file-filter]
                (find-clj-files))
        all-replacements (mapcat analyze-file files)]
    (cond
      (= mode "--report")
      (if (seq all-replacements)
        (print-report all-replacements)
        (println "No color literals found to replace."))

      (= mode "--apply")
      (if (seq all-replacements)
        (apply-all all-replacements)
        (println "No color literals found to replace."))

      :else
      (do
        (println "Usage:")
        (println "  bb scripts/convert_colors.clj --report           # Show what would change")
        (println "  bb scripts/convert_colors.clj --apply            # Apply changes to files")
        (println "  bb scripts/convert_colors.clj --report file.clj  # Check specific file")
        (System/exit 1)))))

(apply -main *command-line-args*)

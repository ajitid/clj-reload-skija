(ns lib.layout.core
  "Subform-style layout system.

   A simpler alternative to flexbox with fewer concepts applied uniformly.
   Every element has the same model on both axes:
     space-before → size → space-after

   Three unit types:
     - Pixels: fixed sizes (numbers, e.g., 50)
     - Percentages: relative to parent (strings, e.g., \"50%\")
     - Stretch: proportional remaining space (strings, e.g., \"1s\", \"2s\")

   Usage:
     (def tree
       {:layout {:horizontal {:size 400} :vertical {:size 300}}
        :children-layout {:mode :stack-horizontal
                          :gap 10
                          :padding 20}
        :children [{:layout {:horizontal {:size \"1s\"}}}
                   {:layout {:horizontal {:size \"2s\"}}}]})

     (layout tree)
     ;; => tree with :bounds {:x :y :w :h} added to each node

   Parent layout modes:
     :stack-horizontal - children flow left to right
     :stack-vertical   - children flow top to bottom
     :grid             - 2D grid with rows and columns")

;; ============================================================
;; Unit Parsing
;; ============================================================

(defn parse-unit
  "Parse a layout unit value.
   Returns {:type :px|:percent|:stretch, :value number}

   Examples:
     50       => {:type :px :value 50}
     \"50%\"   => {:type :percent :value 50}
     \"1s\"    => {:type :stretch :value 1}
     \"2.5s\"  => {:type :stretch :value 2.5}
     nil      => nil"
  [v]
  (cond
    (nil? v) nil
    (number? v) {:type :px :value v}
    (string? v)
    (cond
      (clojure.string/ends-with? v "%")
      {:type :percent
       :value (Double/parseDouble (subs v 0 (dec (count v))))}

      (clojure.string/ends-with? v "s")
      {:type :stretch
       :value (Double/parseDouble (subs v 0 (dec (count v))))}

      :else
      {:type :px :value (Double/parseDouble v)})

    :else nil))

(defn resolve-unit
  "Resolve a parsed unit to pixels given parent-size and remaining stretch space.
   stretch-total is the sum of all stretch values competing for space.
   stretch-space is the total pixels available for stretch elements."
  [parsed parent-size stretch-space stretch-total]
  (when parsed
    (case (:type parsed)
      :px (:value parsed)
      :percent (* parent-size (/ (:value parsed) 100.0))
      :stretch (if (pos? stretch-total)
                 (* stretch-space (/ (:value parsed) stretch-total))
                 0))))

;; ============================================================
;; Layout Defaults
;; ============================================================

(def axis-defaults
  {:before 0
   :size nil      ; nil means stretch to fill or use content
   :after 0
   :min nil
   :max nil})

(def children-layout-defaults
  {:mode :stack-vertical
   :gap 0
   :padding 0
   :padding-left nil
   :padding-right nil
   :padding-top nil
   :padding-bottom nil
   :align :start   ; :start :center :end :stretch (cross-axis alignment)
   :justify :start ; :start :center :end :space-between :space-around (main-axis distribution)
   })

;; ============================================================
;; Helpers
;; ============================================================

(defn get-padding
  "Extract padding values from children-layout.
   Returns {:left :right :top :bottom}"
  [children-layout]
  (let [p (or (:padding children-layout) 0)]
    {:left   (or (:padding-left children-layout) p)
     :right  (or (:padding-right children-layout) p)
     :top    (or (:padding-top children-layout) p)
     :bottom (or (:padding-bottom children-layout) p)}))

(defn get-axis-props
  "Get layout properties for an axis (:horizontal or :vertical).
   Returns {:before :size :after :min :max} with parsed units."
  [layout axis]
  (let [axis-layout (get layout axis {})]
    {:before (parse-unit (get axis-layout :before 0))
     :size   (parse-unit (get axis-layout :size))
     :after  (parse-unit (get axis-layout :after 0))
     :min    (parse-unit (get axis-layout :min))
     :max    (parse-unit (get axis-layout :max))}))

(defn clamp-size
  "Clamp size between min and max if specified."
  [size min-size max-size]
  (cond-> size
    min-size (max min-size)
    max-size (min max-size)))

;; ============================================================
;; Size Calculation (Three-pass algorithm)
;; ============================================================

(defn calculate-fixed-sizes
  "First pass: Calculate all fixed (px) sizes.
   Returns a map of child-index -> {:w :h} for fixed sizes."
  [children parent-w parent-h]
  (into {}
    (map-indexed
      (fn [idx child]
        (let [layout (:layout child {})
              h-props (get-axis-props layout :horizontal)
              v-props (get-axis-props layout :vertical)
              w (when (= :px (:type (:size h-props)))
                  (:value (:size h-props)))
              h (when (= :px (:type (:size v-props)))
                  (:value (:size v-props)))]
          [idx {:w w :h h :h-props h-props :v-props v-props}]))
      children)))

(defn calculate-percent-sizes
  "Second pass: Calculate percentage sizes based on parent."
  [size-map parent-w parent-h]
  (reduce-kv
    (fn [m idx {:keys [w h h-props v-props]}]
      (assoc m idx
        {:w (or w
               (when (= :percent (:type (:size h-props)))
                 (resolve-unit (:size h-props) parent-w 0 0)))
         :h (or h
               (when (= :percent (:type (:size v-props)))
                 (resolve-unit (:size v-props) parent-h 0 0)))
         :h-props h-props
         :v-props v-props}))
    {}
    size-map))

(defn calculate-stretch-sizes
  "Third pass: Distribute remaining space to stretch elements."
  [size-map available-main available-cross main-axis]
  (let [;; Find stretch elements on main axis
        stretch-children (filter
                           (fn [[_ {:keys [h-props v-props]}]]
                             (let [props (if (= main-axis :horizontal) h-props v-props)]
                               (= :stretch (:type (:size props)))))
                           size-map)

        ;; Calculate total stretch value
        stretch-total (reduce
                        (fn [sum [_ {:keys [h-props v-props]}]]
                          (let [props (if (= main-axis :horizontal) h-props v-props)]
                            (+ sum (or (:value (:size props)) 0))))
                        0
                        stretch-children)

        ;; Calculate used space (fixed + percent)
        main-key (if (= main-axis :horizontal) :w :h)
        used-space (reduce-kv
                     (fn [sum _ sizes]
                       (+ sum (or (get sizes main-key) 0)))
                     0
                     size-map)

        stretch-space (max 0 (- available-main used-space))]

    ;; Update stretch elements
    (reduce-kv
      (fn [m idx {:keys [w h h-props v-props] :as sizes}]
        (let [h-stretch? (= :stretch (:type (:size h-props)))
              v-stretch? (= :stretch (:type (:size v-props)))
              new-w (if (and (= main-axis :horizontal) h-stretch?)
                      (resolve-unit (:size h-props) 0 stretch-space stretch-total)
                      (or w (when (and (= main-axis :vertical) h-stretch?)
                              available-cross)))
              new-h (if (and (= main-axis :vertical) v-stretch?)
                      (resolve-unit (:size v-props) 0 stretch-space stretch-total)
                      (or h (when (and (= main-axis :horizontal) v-stretch?)
                              available-cross)))]
          (assoc m idx (assoc sizes :w new-w :h new-h))))
      {}
      size-map)))

;; ============================================================
;; Stack Layout
;; ============================================================

(defn layout-stack
  "Layout children in a stack (horizontal or vertical).
   Returns children with :bounds added."
  [children parent-bounds children-layout direction]
  (let [{:keys [x y w h]} parent-bounds
        padding (get-padding children-layout)
        gap (or (:gap children-layout) 0)
        align (or (:align children-layout) :start)

        ;; Available space after padding
        content-x (+ x (:left padding))
        content-y (+ y (:top padding))
        content-w (- w (:left padding) (:right padding))
        content-h (- h (:top padding) (:bottom padding))

        horizontal? (= direction :horizontal)
        main-axis (if horizontal? :horizontal :vertical)
        cross-axis (if horizontal? :vertical :horizontal)

        ;; Calculate total gap space
        total-gaps (* gap (max 0 (dec (count children))))
        available-main (- (if horizontal? content-w content-h) total-gaps)
        available-cross (if horizontal? content-h content-w)

        ;; Three-pass size calculation
        fixed-sizes (calculate-fixed-sizes children content-w content-h)
        percent-sizes (calculate-percent-sizes fixed-sizes content-w content-h)
        final-sizes (calculate-stretch-sizes percent-sizes available-main available-cross main-axis)

        ;; Position children along main axis
        positioned (loop [remaining children
                          idx 0
                          pos (if horizontal? content-x content-y)
                          result []]
                     (if (empty? remaining)
                       result
                       (let [child (first remaining)
                             {:keys [w h h-props v-props]} (get final-sizes idx)

                             ;; Handle before margin
                             before-h (resolve-unit (:before h-props) content-w 0 0)
                             before-v (resolve-unit (:before v-props) content-h 0 0)

                             ;; Calculate position based on main axis
                             main-before (if horizontal? (or before-h 0) (or before-v 0))
                             main-pos (+ pos main-before)

                             ;; Default size if not specified
                             child-w (or w content-w)
                             child-h (or h content-h)

                             ;; Cross-axis alignment
                             cross-size (if horizontal? child-h child-w)
                             cross-available (if horizontal? content-h content-w)
                             cross-offset (case align
                                            :start 0
                                            :center (/ (- cross-available cross-size) 2)
                                            :end (- cross-available cross-size)
                                            :stretch 0
                                            0)

                             ;; Apply stretch to cross-axis
                             [final-w final-h]
                             (if (= align :stretch)
                               (if horizontal?
                                 [child-w content-h]
                                 [content-w child-h])
                               [child-w child-h])

                             ;; Calculate bounds
                             bounds (if horizontal?
                                      {:x main-pos
                                       :y (+ content-y cross-offset)
                                       :w final-w
                                       :h final-h}
                                      {:x (+ content-x cross-offset)
                                       :y main-pos
                                       :w final-w
                                       :h final-h})

                             ;; Handle after margin
                             after-h (resolve-unit (:after h-props) content-w 0 0)
                             after-v (resolve-unit (:after v-props) content-h 0 0)
                             main-after (if horizontal? (or after-h 0) (or after-v 0))

                             ;; Main-axis size for advancement
                             main-size (if horizontal? final-w final-h)
                             next-pos (+ main-pos main-size main-after gap)]

                         (recur (rest remaining)
                                (inc idx)
                                next-pos
                                (conj result (assoc child :bounds bounds))))))]
    positioned))

;; ============================================================
;; Grid Layout
;; ============================================================

(defn layout-grid
  "Layout children in a grid.
   Grid properties:
     :cols - number of columns or vector of column sizes
     :rows - number of rows or vector of row sizes
     :gap / :gap-x / :gap-y - spacing between cells"
  [children parent-bounds children-layout]
  (let [{:keys [x y w h]} parent-bounds
        padding (get-padding children-layout)

        ;; Content area
        content-x (+ x (:left padding))
        content-y (+ y (:top padding))
        content-w (- w (:left padding) (:right padding))
        content-h (- h (:top padding) (:bottom padding))

        ;; Grid configuration
        cols (or (:cols children-layout) 1)
        num-cols (if (vector? cols) (count cols) cols)
        rows (or (:rows children-layout) (int (Math/ceil (/ (count children) num-cols))))
        num-rows (if (vector? rows) (count rows) rows)
        gap (or (:gap children-layout) 0)
        gap-x (or (:gap-x children-layout) gap)
        gap-y (or (:gap-y children-layout) gap)

        total-gap-x (* gap-x (max 0 (dec num-cols)))
        total-gap-y (* gap-y (max 0 (dec num-rows)))

        available-w (- content-w total-gap-x)
        available-h (- content-h total-gap-y)

        ;; Parse column sizes
        col-sizes (if (vector? cols)
                    (let [parsed (mapv parse-unit cols)
                          ;; Calculate stretch columns
                          fixed-w (reduce + 0 (keep #(when (= :px (:type %)) (:value %)) parsed))
                          percent-w (reduce + 0 (keep #(when (= :percent (:type %))
                                                         (* available-w (/ (:value %) 100))) parsed))
                          stretch-total (reduce + 0 (keep #(when (= :stretch (:type %)) (:value %)) parsed))
                          stretch-space (- available-w fixed-w percent-w)]
                      (mapv #(resolve-unit % available-w stretch-space stretch-total) parsed))
                    (vec (repeat num-cols (/ available-w num-cols))))

        ;; Parse row sizes
        row-sizes (if (vector? rows)
                    (let [parsed (mapv parse-unit rows)
                          fixed-h (reduce + 0 (keep #(when (= :px (:type %)) (:value %)) parsed))
                          percent-h (reduce + 0 (keep #(when (= :percent (:type %))
                                                         (* available-h (/ (:value %) 100))) parsed))
                          stretch-total (reduce + 0 (keep #(when (= :stretch (:type %)) (:value %)) parsed))
                          stretch-space (- available-h fixed-h percent-h)]
                      (mapv #(resolve-unit % available-h stretch-space stretch-total) parsed))
                    (vec (repeat num-rows (/ available-h num-rows))))

        ;; Calculate column positions
        col-positions (reductions + content-x (map #(+ % gap-x) (butlast col-sizes)))
        row-positions (reductions + content-y (map #(+ % gap-y) (butlast row-sizes)))]

    ;; Position each child in grid
    (vec
      (map-indexed
        (fn [idx child]
          (let [;; Support explicit row/col positioning
                layout (:layout child {})
                col-idx (or (:col layout) (mod idx num-cols))
                row-idx (or (:row layout) (quot idx num-cols))
                col-span (or (:col-span layout) 1)
                row-span (or (:row-span layout) 1)

                ;; Calculate bounds
                cell-x (nth col-positions col-idx content-x)
                cell-y (nth row-positions row-idx content-y)

                ;; Handle spans
                cell-w (+ (reduce + (subvec col-sizes col-idx (min (+ col-idx col-span) num-cols)))
                          (* gap-x (dec col-span)))
                cell-h (+ (reduce + (subvec row-sizes row-idx (min (+ row-idx row-span) num-rows)))
                          (* gap-y (dec row-span)))]

            (assoc child :bounds {:x cell-x :y cell-y :w cell-w :h cell-h})))
        children))))

;; ============================================================
;; Main Layout Function
;; ============================================================

(defn layout
  "Compute layout for a tree of elements.
   Adds :bounds {:x :y :w :h} to each node.

   Root element should have explicit size in :layout, or pass parent-bounds.

   Arguments:
     tree - layout tree (map with :layout, :children-layout, :children)
     parent-bounds - optional {:x :y :w :h} for root positioning

   Returns: tree with :bounds added to all nodes"
  ([tree] (layout tree {:x 0 :y 0 :w 800 :h 600}))
  ([tree parent-bounds]
   (let [layout-spec (:layout tree {})
         h-props (get-axis-props layout-spec :horizontal)
         v-props (get-axis-props layout-spec :vertical)

         ;; Calculate this element's bounds
         ;; Use explicit size or fill parent
         w (or (resolve-unit (:size h-props) (:w parent-bounds) (:w parent-bounds) 1)
               (:w parent-bounds))
         h (or (resolve-unit (:size v-props) (:h parent-bounds) (:h parent-bounds) 1)
               (:h parent-bounds))

         ;; Apply min/max constraints
         min-w (resolve-unit (:min h-props) (:w parent-bounds) 0 0)
         max-w (resolve-unit (:max h-props) (:w parent-bounds) 0 0)
         min-h (resolve-unit (:min v-props) (:h parent-bounds) 0 0)
         max-h (resolve-unit (:max v-props) (:h parent-bounds) 0 0)

         final-w (clamp-size w min-w max-w)
         final-h (clamp-size h min-h max-h)

         ;; Position (using before margins)
         before-x (or (resolve-unit (:before h-props) (:w parent-bounds) 0 0) 0)
         before-y (or (resolve-unit (:before v-props) (:h parent-bounds) 0 0) 0)

         bounds {:x (+ (:x parent-bounds) before-x)
                 :y (+ (:y parent-bounds) before-y)
                 :w final-w
                 :h final-h}

         ;; Layout children if any
         children (:children tree)
         children-layout (merge children-layout-defaults (:children-layout tree))

         laid-out-children
         (when (seq children)
           (let [mode (:mode children-layout)]
             (case mode
               :stack-horizontal (layout-stack children bounds children-layout :horizontal)
               :stack-vertical   (layout-stack children bounds children-layout :vertical)
               :grid             (layout-grid children bounds children-layout)
               ;; Default to vertical stack
               (layout-stack children bounds children-layout :vertical))))

         ;; Recursively layout grandchildren
         final-children
         (when laid-out-children
           (mapv (fn [child]
                   (if (:children child)
                     (layout child (:bounds child))
                     child))
                 laid-out-children))]

     (cond-> (assoc tree :bounds bounds)
       final-children (assoc :children final-children)))))

;; ============================================================
;; Convenience constructors
;; ============================================================

(defn hstack
  "Create a horizontal stack container.

   Options:
     :gap - space between children (default 0)
     :padding - space around all children
     :align - cross-axis alignment :start :center :end :stretch

   Example:
     (hstack {:gap 10 :padding 20}
       [{:layout {:horizontal {:size \"1s\"}}}
        {:layout {:horizontal {:size \"2s\"}}}])"
  ([children] (hstack {} children))
  ([opts children]
   {:children-layout (merge {:mode :stack-horizontal} opts)
    :children children}))

(defn vstack
  "Create a vertical stack container.

   Options:
     :gap - space between children (default 0)
     :padding - space around all children
     :align - cross-axis alignment :start :center :end :stretch

   Example:
     (vstack {:gap 10}
       [{:layout {:vertical {:size 50}}}
        {:layout {:vertical {:size \"1s\"}}}])"
  ([children] (vstack {} children))
  ([opts children]
   {:children-layout (merge {:mode :stack-vertical} opts)
    :children children}))

(defn grid
  "Create a grid container.

   Options:
     :cols - number of columns or vector of sizes [100 \"1s\" \"2s\"]
     :rows - number of rows or vector of sizes
     :gap - space between cells
     :gap-x / :gap-y - horizontal/vertical gap
     :padding - space around grid

   Example:
     (grid {:cols 3 :gap 10}
       (repeat 9 {}))"
  ([opts children]
   {:children-layout (merge {:mode :grid} opts)
    :children (vec children)}))

(defn box
  "Create a box with explicit size.

   Example:
     (box {:w 100 :h 50} [...children...])
     (box {:w \"50%\" :h \"1s\"})"
  ([size-opts] (box size-opts nil))
  ([{:keys [w h min-w max-w min-h max-h]} children]
   (cond-> {:layout {:horizontal (cond-> {}
                                   w (assoc :size w)
                                   min-w (assoc :min min-w)
                                   max-w (assoc :max max-w))
                     :vertical (cond-> {}
                                 h (assoc :size h)
                                 min-h (assoc :min min-h)
                                 max-h (assoc :max max-h))}}
     children (assoc :children children))))

(defn spacer
  "Create a flexible spacer that takes remaining space.

   Example:
     (hstack {} [(box {:w 50}) (spacer) (box {:w 50})])
     ;; The spacer expands to fill available space"
  ([] (spacer 1))
  ([stretch-value]
   {:layout {:horizontal {:size (str stretch-value "s")}
             :vertical {:size (str stretch-value "s")}}}))

(ns lib.layout.core
  "Subform-style layout system.

   A simpler alternative to flexbox with fewer concepts applied uniformly.
   Every element has the same model on both axes:
     space-before → size → space-after

   Three unit types:
     - Pixels: fixed sizes (numbers, e.g., 50)
     - Percentages: relative to parent (strings, e.g., \"50%\")
     - Stretch: proportional remaining space (strings, e.g., \"1s\", \"2s\")
     - Hug: shrink to fit children (string \"hug\")

   Usage:
     (def tree
       {:layout {:x {:size 400} :y {:size 300}}
        :children-layout {:mode :stack-x
                          :x {:between 10}
                          :y {}}
        :children [{:layout {:x {:size \"1s\"}}}
                   {:layout {:x {:size \"2s\"}}}]})

     (layout tree)
     ;; => tree with :bounds {:x :y :w :h} added to each node

   Parent layout modes:
     :stack-x - children flow left to right
     :stack-y - children flow top to bottom
     :grid    - 2D grid with :x-count columns and :y-count rows")

;; ============================================================
;; Unit Parsing
;; ============================================================

(defn parse-unit
  "Parse a layout unit value.
   Returns {:type :px|:percent|:stretch|:hug, :value number}

   Examples:
     50       => {:type :px :value 50}
     \"50%\"   => {:type :percent :value 50}
     \"1s\"    => {:type :stretch :value 1}
     \"2.5s\"  => {:type :stretch :value 2.5}
     \"hug\"   => {:type :hug :value nil}
     nil      => nil"
  [v]
  (cond
    (nil? v) nil
    (number? v) {:type :px :value v}
    (string? v)
    (cond
      (= v "hug")
      {:type :hug :value nil}

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
  "Defaults for element layout on each axis (:x or :y).
   :before/:after - margins
   :size - explicit size (px, %, stretch, hug)
   :min/:max - size constraints"
  {:before 0
   :size nil      ; nil means stretch to fill or use content
   :after 0
   :min nil
   :max nil})

(def children-axis-defaults
  "Defaults for children-layout spacing on each axis.
   :before - space before first child (padding-left/top)
   :between - space between children (gap)
   :after - space after last child (padding-right/bottom)"
  {:before 0
   :between 0
   :after 0})

(def children-layout-defaults
  "Defaults for children-layout.
   :mode - how children are arranged (:stack-x, :stack-y, :grid)
   :x/:y - spacing on each axis with :before/:between/:after
   :overflow - how to handle content exceeding bounds (:visible, :clip, :scroll)"
  {:mode :stack-y
   :x children-axis-defaults
   :y children-axis-defaults
   :overflow {:x :visible :y :visible}})

(defn normalize-overflow
  "Convert overflow spec to normalized map form.

  Examples:
    nil          => {:x :visible :y :visible}
    :clip        => {:x :clip :y :clip}
    :scroll      => {:x :scroll :y :scroll}
    {:y :scroll} => {:x :visible :y :scroll}"
  [overflow]
  (cond
    (nil? overflow) {:x :visible :y :visible}
    (keyword? overflow) {:x overflow :y overflow}
    (map? overflow) (merge {:x :visible :y :visible} overflow)))

;; ============================================================
;; Helpers
;; ============================================================

(defn get-spacing
  "Extract spacing values from children-layout.
   Returns {:left :right :top :bottom :gap-x :gap-y}
   Uses :x/:y with :before/:between/:after."
  [children-layout]
  (let [x-spacing (merge children-axis-defaults (:x children-layout))
        y-spacing (merge children-axis-defaults (:y children-layout))]
    {:left   (:before x-spacing)
     :right  (:after x-spacing)
     :top    (:before y-spacing)
     :bottom (:after y-spacing)
     :gap-x  (:between x-spacing)
     :gap-y  (:between y-spacing)}))

(defn get-axis-props
  "Get layout properties for an axis (:x or :y).
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
              x-props (get-axis-props layout :x)
              y-props (get-axis-props layout :y)
              w (when (= :px (:type (:size x-props)))
                  (:value (:size x-props)))
              h (when (= :px (:type (:size y-props)))
                  (:value (:size y-props)))]
          [idx {:w w :h h :x-props x-props :y-props y-props}]))
      children)))

(defn calculate-percent-sizes
  "Second pass: Calculate percentage sizes based on parent."
  [size-map parent-w parent-h]
  (reduce-kv
    (fn [m idx {:keys [w h x-props y-props]}]
      (assoc m idx
        {:w (or w
               (when (= :percent (:type (:size x-props)))
                 (resolve-unit (:size x-props) parent-w 0 0)))
         :h (or h
               (when (= :percent (:type (:size y-props)))
                 (resolve-unit (:size y-props) parent-h 0 0)))
         :x-props x-props
         :y-props y-props}))
    {}
    size-map))

(defn calculate-stretch-sizes
  "Third pass: Distribute remaining space to stretch elements."
  [size-map available-main available-cross main-axis]
  (let [;; Find stretch elements on main axis
        stretch-children (filter
                           (fn [[_ {:keys [x-props y-props]}]]
                             (let [props (if (= main-axis :horizontal) x-props y-props)]
                               (= :stretch (:type (:size props)))))
                           size-map)

        ;; Calculate total stretch value
        stretch-total (reduce
                        (fn [sum [_ {:keys [x-props y-props]}]]
                          (let [props (if (= main-axis :horizontal) x-props y-props)]
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
      (fn [m idx {:keys [w h x-props y-props] :as sizes}]
        (let [x-stretch? (= :stretch (:type (:size x-props)))
              y-stretch? (= :stretch (:type (:size y-props)))
              new-w (if (and (= main-axis :horizontal) x-stretch?)
                      (resolve-unit (:size x-props) 0 stretch-space stretch-total)
                      (or w (when (and (= main-axis :vertical) x-stretch?)
                              available-cross)))
              new-h (if (and (= main-axis :vertical) y-stretch?)
                      (resolve-unit (:size y-props) 0 stretch-space stretch-total)
                      (or h (when (and (= main-axis :horizontal) y-stretch?)
                              available-cross)))]
          (assoc m idx (assoc sizes :w new-w :h new-h))))
      {}
      size-map)))

;; ============================================================
;; Stack Layout
;; ============================================================

(defn- pop-out?
  "Check if a child has pop-out positioning mode."
  [child]
  (= :pop-out (get-in child [:layout :mode])))

(defn- parse-offset
  "Parse :offset from axis layout spec. Used for pop-out positioning."
  [axis-layout parent-size]
  (let [offset-val (get axis-layout :offset 0)]
    (or (resolve-unit (parse-unit offset-val) parent-size 0 0) 0)))

(defn- layout-pop-out-child
  "Position a pop-out child absolutely within parent content area.
   Supports :anchor for positioning from different reference points.
   Uses :offset (not :before) for positioning from anchor point.

   Anchors: :top-left (default), :top-right, :bottom-left, :bottom-right, :center"
  [child content-x content-y content-w content-h]
  (let [layout (:layout child {})
        anchor (get layout :anchor :top-left)
        z-index (get layout :z 0)
        x-axis (get layout :x {})
        y-axis (get layout :y {})

        ;; Parse offset values
        offset-x (parse-offset x-axis content-w)
        offset-y (parse-offset y-axis content-h)

        ;; Parse size
        x-props (get-axis-props layout :x)
        y-props (get-axis-props layout :y)
        child-w (or (resolve-unit (:size x-props) content-w content-w 1) content-w)
        child-h (or (resolve-unit (:size y-props) content-h content-h 1) content-h)

        ;; Calculate position based on anchor
        [pos-x pos-y]
        (case anchor
          :top-left     [(+ content-x offset-x)
                         (+ content-y offset-y)]
          :top-right    [(+ content-x (- content-w offset-x child-w))
                         (+ content-y offset-y)]
          :bottom-left  [(+ content-x offset-x)
                         (+ content-y (- content-h offset-y child-h))]
          :bottom-right [(+ content-x (- content-w offset-x child-w))
                         (+ content-y (- content-h offset-y child-h))]
          :center       [(+ content-x (/ (- content-w child-w) 2) offset-x)
                         (+ content-y (/ (- content-h child-h) 2) offset-y)]
          ;; Default to top-left
          [(+ content-x offset-x)
           (+ content-y offset-y)])]

    (assoc child :bounds {:x pos-x
                          :y pos-y
                          :w child-w
                          :h child-h
                          :z z-index})))

(defn layout-stack
  "Layout children in a stack (horizontal or vertical).
   Returns children with :bounds added.
   Pop-out children are positioned absolutely and don't participate in flow."
  [children parent-bounds children-layout direction]
  (let [{:keys [x y w h]} parent-bounds
        spacing (get-spacing children-layout)

        ;; Available space after padding
        content-x (+ x (:left spacing))
        content-y (+ y (:top spacing))
        content-w (- w (:left spacing) (:right spacing))
        content-h (- h (:top spacing) (:bottom spacing))

        horizontal? (= direction :horizontal)
        main-axis (if horizontal? :horizontal :vertical)
        gap (if horizontal? (:gap-x spacing) (:gap-y spacing))

        ;; Separate flow and pop-out children (keeping original indices)
        indexed-children (map-indexed vector children)
        flow-indexed (filterv (fn [[_ c]] (not (pop-out? c))) indexed-children)
        flow-children (mapv second flow-indexed)

        ;; Calculate total gap space for flow children
        total-gaps (* gap (max 0 (dec (count flow-children))))
        available-main (- (if horizontal? content-w content-h) total-gaps)
        available-cross (if horizontal? content-h content-w)

        ;; Three-pass size calculation for flow children
        fixed-sizes (calculate-fixed-sizes flow-children content-w content-h)
        percent-sizes (calculate-percent-sizes fixed-sizes
                                               (if horizontal? available-main content-w)
                                               (if horizontal? content-h available-main))
        final-sizes (calculate-stretch-sizes percent-sizes available-main available-cross main-axis)

        ;; Position flow children along main axis
        flow-positioned (loop [remaining (map-indexed vector flow-children)
                               pos (if horizontal? content-x content-y)
                               result {}]
                          (if (empty? remaining)
                            result
                            (let [[idx child] (first remaining)
                                  {:keys [w h x-props y-props]} (get final-sizes idx)

                                  ;; Handle before margin
                                  before-x (resolve-unit (:before x-props) content-w 0 0)
                                  before-y (resolve-unit (:before y-props) content-h 0 0)

                                  ;; Calculate position based on main axis
                                  main-before (if horizontal? (or before-x 0) (or before-y 0))
                                  main-pos (+ pos main-before)

                                  ;; Default size if not specified (fill cross-axis)
                                  child-w (or w content-w)
                                  child-h (or h content-h)

                                  ;; Cross-axis positioning via child's before property
                                  cross-before (if horizontal?
                                                 (or before-y 0)
                                                 (or before-x 0))

                                  ;; Get z-index from child layout
                                  z-index (get-in child [:layout :z] 0)

                                  ;; Calculate bounds
                                  bounds (if horizontal?
                                           {:x main-pos
                                            :y (+ content-y cross-before)
                                            :w child-w
                                            :h child-h
                                            :z z-index}
                                           {:x (+ content-x cross-before)
                                            :y main-pos
                                            :w child-w
                                            :h child-h
                                            :z z-index})

                                  ;; Handle after margin
                                  after-x (resolve-unit (:after x-props) content-w 0 0)
                                  after-y (resolve-unit (:after y-props) content-h 0 0)
                                  main-after (if horizontal? (or after-x 0) (or after-y 0))

                                  ;; Main-axis size for advancement
                                  main-size (if horizontal? child-w child-h)
                                  next-pos (+ main-pos main-size main-after gap)

                                  ;; Store with original index from flow-indexed
                                  orig-idx (first (nth flow-indexed idx))]

                              (recur (rest remaining)
                                     next-pos
                                     (assoc result orig-idx (assoc child :bounds bounds))))))]

    ;; Reconstruct children in original order
    (mapv
      (fn [[orig-idx child]]
        (if (pop-out? child)
          (layout-pop-out-child child content-x content-y content-w content-h)
          (get flow-positioned orig-idx)))
      indexed-children)))

;; ============================================================
;; Grid Layout
;; ============================================================

(defn layout-grid
  "Layout children in a grid.
   Grid properties:
     :x-count - number of columns or vector of column sizes
     :y-count - number of rows or vector of row sizes
   Spacing via :x/:y with :before/:between/:after"
  [children parent-bounds children-layout]
  (let [{:keys [x y w h]} parent-bounds
        spacing (get-spacing children-layout)

        ;; Content area
        content-x (+ x (:left spacing))
        content-y (+ y (:top spacing))
        content-w (- w (:left spacing) (:right spacing))
        content-h (- h (:top spacing) (:bottom spacing))

        ;; Separate flow and pop-out children
        indexed-children (map-indexed vector children)
        flow-indexed (filterv (fn [[_ c]] (not (pop-out? c))) indexed-children)
        flow-children (mapv second flow-indexed)

        ;; Grid configuration
        cols (or (:x-count children-layout) 1)
        num-cols (if (vector? cols) (count cols) cols)
        rows (or (:y-count children-layout) (int (Math/ceil (/ (count flow-children) num-cols))))
        num-rows (if (vector? rows) (count rows) rows)
        gap-x (:gap-x spacing)
        gap-y (:gap-y spacing)

        total-gap-x (* gap-x (max 0 (dec num-cols)))
        total-gap-y (* gap-y (max 0 (dec num-rows)))

        available-w (- content-w total-gap-x)
        available-h (- content-h total-gap-y)

        ;; Parse column sizes
        col-sizes (if (vector? cols)
                    (let [parsed (mapv parse-unit cols)
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
        col-positions (vec (reductions + content-x (map #(+ % gap-x) (butlast col-sizes))))
        row-positions (vec (reductions + content-y (map #(+ % gap-y) (butlast row-sizes))))

        ;; Position flow children in grid
        flow-positioned
        (into {}
              (map-indexed
                (fn [flow-idx [orig-idx child]]
                  (let [layout (:layout child {})
                        col-idx (or (:col layout) (mod flow-idx num-cols))
                        row-idx (or (:row layout) (quot flow-idx num-cols))
                        col-span (or (:col-span layout) 1)
                        row-span (or (:row-span layout) 1)
                        cell-x (nth col-positions col-idx content-x)
                        cell-y (nth row-positions row-idx content-y)
                        cell-w (+ (reduce + (subvec col-sizes col-idx (min (+ col-idx col-span) num-cols)))
                                  (* gap-x (dec col-span)))
                        cell-h (+ (reduce + (subvec row-sizes row-idx (min (+ row-idx row-span) num-rows)))
                                  (* gap-y (dec row-span)))
                        z-index (get layout :z 0)]
                    [orig-idx (assoc child :bounds {:x cell-x :y cell-y :w cell-w :h cell-h :z z-index})]))
                flow-indexed))]

    ;; Reconstruct children in original order
    (mapv
      (fn [[orig-idx child]]
        (if (pop-out? child)
          (layout-pop-out-child child content-x content-y content-w content-h)
          (get flow-positioned orig-idx)))
      indexed-children)))

;; ============================================================
;; Main Layout Function
;; ============================================================

(defn- calculate-hug-size
  "Calculate size for hug sizing based on children bounds.
   Returns the size needed to contain all non-pop-out children."
  [laid-out-children axis spacing]
  (let [flow-children (filterv #(not (pop-out? %)) laid-out-children)]
    (if (empty? flow-children)
      0
      (let [bounds-key (if (= axis :x) :w :h)
            pos-key (if (= axis :x) :x :y)
            ;; Find max extent (position + size)
            max-extent (reduce
                         (fn [max-val child]
                           (let [b (:bounds child)]
                             (max max-val (+ (get b pos-key) (get b bounds-key)))))
                         0
                         flow-children)
            ;; Get starting position (content area start)
            min-pos (reduce
                      (fn [min-val child]
                        (min min-val (get-in child [:bounds pos-key])))
                      Double/MAX_VALUE
                      flow-children)
            ;; Size is extent minus start
            content-size (- max-extent min-pos)
            ;; Add padding
            pad-before (if (= axis :x) (:left spacing) (:top spacing))
            pad-after (if (= axis :x) (:right spacing) (:bottom spacing))]
        (+ content-size pad-before pad-after)))))

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
         x-props (get-axis-props layout-spec :x)
         y-props (get-axis-props layout-spec :y)

         ;; Check for hug sizing (need to layout children first)
         hug-x? (= :hug (:type (:size x-props)))
         hug-y? (= :hug (:type (:size y-props)))

         ;; Calculate initial bounds (may be adjusted for hug)
         initial-w (if hug-x?
                     (:w parent-bounds) ;; Use parent width initially, adjust after children
                     (or (resolve-unit (:size x-props) (:w parent-bounds) (:w parent-bounds) 1)
                         (:w parent-bounds)))
         initial-h (if hug-y?
                     (:h parent-bounds)
                     (or (resolve-unit (:size y-props) (:h parent-bounds) (:h parent-bounds) 1)
                         (:h parent-bounds)))

         ;; Position (using before margins)
         before-x (or (resolve-unit (:before x-props) (:w parent-bounds) 0 0) 0)
         before-y (or (resolve-unit (:before y-props) (:h parent-bounds) 0 0) 0)

         initial-bounds {:x (+ (:x parent-bounds) before-x)
                         :y (+ (:y parent-bounds) before-y)
                         :w initial-w
                         :h initial-h}

         ;; Layout children if any
         children (:children tree)
         children-layout (merge children-layout-defaults (:children-layout tree))
         spacing (get-spacing children-layout)

         laid-out-children
         (when (seq children)
           (let [mode (:mode children-layout)]
             (case mode
               :stack-x (layout-stack children initial-bounds children-layout :horizontal)
               :stack-y (layout-stack children initial-bounds children-layout :vertical)
               :grid    (layout-grid children initial-bounds children-layout)
               (layout-stack children initial-bounds children-layout :vertical))))

         ;; Calculate hug sizes if needed
         hug-w (when (and hug-x? laid-out-children)
                 (calculate-hug-size laid-out-children :x spacing))
         hug-h (when (and hug-y? laid-out-children)
                 (calculate-hug-size laid-out-children :y spacing))

         ;; Final size with hug adjustments
         w (or hug-w initial-w)
         h (or hug-h initial-h)

         ;; Apply aspect ratio if specified
         ;; :aspect = width / height ratio (e.g., 16/9 means width is 1.78x height)
         aspect (get layout-spec :aspect)
         has-explicit-w? (and (not hug-x?) (:size x-props))
         has-explicit-h? (and (not hug-y?) (:size y-props))
         [w h] (if aspect
                 (cond
                   ;; Width specified, derive height
                   (and has-explicit-w? (not has-explicit-h?))
                   [w (/ w aspect)]
                   ;; Height specified, derive width
                   (and has-explicit-h? (not has-explicit-w?))
                   [(* h aspect) h]
                   ;; Both or neither specified, use as-is
                   :else [w h])
                 [w h])

         ;; Apply min/max constraints
         min-w (resolve-unit (:min x-props) (:w parent-bounds) 0 0)
         max-w (resolve-unit (:max x-props) (:w parent-bounds) 0 0)
         min-h (resolve-unit (:min y-props) (:h parent-bounds) 0 0)
         max-h (resolve-unit (:max y-props) (:h parent-bounds) 0 0)

         final-w (clamp-size w min-w max-w)
         final-h (clamp-size h min-h max-h)

         ;; Get overflow mode from children-layout (normalize to map form)
         overflow (normalize-overflow (get children-layout :overflow))

         ;; Get z-index from layout
         z-index (get layout-spec :z 0)

         bounds {:x (+ (:x parent-bounds) before-x)
                 :y (+ (:y parent-bounds) before-y)
                 :w final-w
                 :h final-h
                 :z z-index
                 :overflow overflow}

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
  "Create a horizontal stack container (children flow along x-axis).

   Options (children-layout):
     :x {:before :between :after} - horizontal spacing
     :y {:before :after} - vertical spacing

   Example:
     (hstack {:x {:between 10} :y {:before 20 :after 20}}
       [{:layout {:x {:size \"1s\"}}}
        {:layout {:x {:size \"2s\"}}}])"
  ([children] (hstack {} children))
  ([opts children]
   {:children-layout (merge {:mode :stack-x} opts)
    :children children}))

(defn vstack
  "Create a vertical stack container (children flow along y-axis).

   Options (children-layout):
     :x {:before :after} - horizontal spacing
     :y {:before :between :after} - vertical spacing

   Example:
     (vstack {:y {:between 10}}
       [{:layout {:y {:size 50}}}
        {:layout {:y {:size \"1s\"}}}])"
  ([children] (vstack {} children))
  ([opts children]
   {:children-layout (merge {:mode :stack-y} opts)
    :children children}))

(defn grid
  "Create a grid container.

   Options:
     :x-count - number of columns or vector of sizes [100 \"1s\" \"2s\"]
     :y-count - number of rows or vector of sizes
     :x {:before :between :after} - horizontal spacing
     :y {:before :between :after} - vertical spacing

   Example:
     (grid {:x-count 3 :x {:between 10} :y {:between 10}}
       (repeat 9 {}))"
  ([opts children]
   {:children-layout (merge {:mode :grid} opts)
    :children (vec children)}))

(defn box
  "Create a box with explicit size.

   Example:
     (box {:w 100 :h 50} [...children...])
     (box {:w \"50%\" :h \"1s\"})
     (box {:w \"hug\" :h 50})  ; width hugs children"
  ([size-opts] (box size-opts nil))
  ([{:keys [w h min-w max-w min-h max-h]} children]
   (cond-> {:layout {:x (cond-> {}
                          w (assoc :size w)
                          min-w (assoc :min min-w)
                          max-w (assoc :max max-w))
                     :y (cond-> {}
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
   {:layout {:x {:size (str stretch-value "s")}
             :y {:size (str stretch-value "s")}}}))

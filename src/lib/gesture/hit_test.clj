(ns lib.gesture.hit-test
  "Hit testing algorithm for finding targets under pointer.

   Pure functions - no atom access. All data passed as parameters."
  (:require [lib.gesture.state :as state]
            [lib.layout.scroll :as scroll]))

(defn point-in-rect?
  "Check if point (px, py) is inside rect [x y w h]."
  [px py [x y w h]]
  (and (>= px x) (< px (+ x w))
       (>= py y) (< py (+ y h))))

(defn layer-index
  "Get the index of a layer in layer-order (lower = higher priority)."
  [layer]
  (.indexOf state/layer-order layer))

(defn hit-test
  "Find all hit targets under pointer, sorted by layer then z-index.

   Pure function - takes all data as parameters.

   Arguments:
   - px, py: pointer position in logical pixels
   - ctx: context map passed to bounds-fn (e.g., {:window-width w, :window :main})
   - targets: map of target-id -> target definition
   - blocked-layers: set of layer keywords to exclude

   Window filtering: targets with :window key are only hit-tested when
   ctx contains a matching :window value. Default is :main for both.

   Returns sequence of {:target hit-target :depth index} sorted by priority."
  [px py ctx targets blocked-layers]
  (let [active-window (:window ctx :main)]
    (->> (vals targets)
         ;; Filter out targets in blocked layers
         (remove #(contains? blocked-layers (:layer %)))
         ;; Filter by window - targets default to :main if no :window key
         (filter #(= (get % :window :main) active-window))
         ;; Filter to targets that contain the point (skip if bounds is nil)
         (filter (fn [target]
                   (when-let [bounds-fn (:bounds-fn target)]
                     (when-let [bounds (bounds-fn ctx)]
                       (point-in-rect? px py bounds)))))
         ;; Sort by layer priority (lower index = higher priority)
         ;; then by z-index descending (higher z = on top)
         (sort-by (juxt #(layer-index (:layer %))
                        #(- (:z-index % 0))))
         ;; Add depth index
         (map-indexed (fn [idx target]
                        {:target target :depth idx})))))

(defn topmost-target
  "Get only the topmost hit target under pointer, or nil.

   Pure function - takes all data as parameters."
  [px py ctx targets blocked-layers]
  (first (hit-test px py ctx targets blocked-layers)))

;; ============================================================
;; Scroll-Aware Hit Testing (for layout trees)
;; ============================================================

(defn- normalize-overflow
  "Convert overflow spec to normalized map form."
  [overflow]
  (cond
    (nil? overflow) {:x :visible :y :visible}
    (keyword? overflow) {:x overflow :y overflow}
    (map? overflow) (merge {:x :visible :y :visible} overflow)))

(defn hit-test-tree
  "Hit test a layout tree, accounting for scroll offsets.

   Walks the tree and finds all nodes under the pointer, accounting for
   scroll translation in parent containers.

   Arguments:
   - screen-x, screen-y: pointer position in screen space
   - tree: laid-out tree with :bounds on each node
   - scroll-offset-acc: accumulated scroll offset from parents {:x :y}

   Returns: sequence of nodes under pointer, deepest first (leaf to root)"
  ([screen-x screen-y tree]
   (hit-test-tree screen-x screen-y tree {:x 0 :y 0}))
  ([screen-x screen-y tree scroll-offset-acc]
   (when tree
     (let [bounds (:bounds tree)
           overflow (normalize-overflow (get bounds :overflow))

           ;; Transform mouse coords using PARENT's accumulated scroll offset
           ;; (this node's bounds are in parent's coordinate space, not affected by own scroll)
           layout-x (+ screen-x (:x scroll-offset-acc))
           layout-y (+ screen-y (:y scroll-offset-acc))

           ;; Test against this node's bounds
           hit? (and bounds
                     (>= layout-x (:x bounds))
                     (< layout-x (+ (:x bounds) (:w bounds)))
                     (>= layout-y (:y bounds))
                     (< layout-y (+ (:y bounds) (:h bounds))))]

       (when hit?
         ;; Node hit - now add THIS node's scroll offset for testing children
         ;; Children are positioned relative to scrolled content
         (let [node-scroll (if (and (:id tree)
                                    (or (= :scroll (:x overflow))
                                        (= :scroll (:y overflow))))
                            (scroll/get-scroll (:id tree))
                            {:x 0 :y 0})
               child-offset {:x (+ (:x scroll-offset-acc) (:x node-scroll))
                             :y (+ (:y scroll-offset-acc) (:y node-scroll))}
               child-hits (mapcat #(hit-test-tree screen-x screen-y % child-offset)
                                  (:children tree))]
           ;; Return children first (deepest nodes first), then this node
           (concat child-hits [tree])))))))

(defn find-scrollable-container
  "Find the first scrollable container under pointer.

   Returns the node with :overflow :scroll that contains the pointer,
   or nil if no scrollable container is hit.

   Arguments:
   - screen-x, screen-y: pointer position in screen space
   - tree: laid-out tree with :bounds on each node"
  [screen-x screen-y tree]
  (let [hits (hit-test-tree screen-x screen-y tree)]
    ;; Find first hit node that has scrolling enabled
    (first (filter (fn [node]
                     (let [overflow (normalize-overflow (get-in node [:bounds :overflow]))]
                       (and (:id node)
                            (or (= :scroll (:x overflow))
                                (= :scroll (:y overflow))))))
                   hits))))

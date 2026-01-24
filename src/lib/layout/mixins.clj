(ns lib.layout.mixins
  "Reusable lifecycle mixins for layout nodes.

   Mixins use a new pattern where :did-mount can return a Flex effect
   for automatic disposal on unmount or hot-reload."
  (:require [lib.layout.scroll :as scroll]))

;; ============================================================
;; Scrollable Mixin
;; ============================================================

(defn scrollable
  "Mixin for scrollable containers.

  Args:
    axes - :x, :y, or both
    opts - optional {:persist true} to keep scroll when hidden

  Examples:
    (mixins/scrollable :y)
    (mixins/scrollable :x :y)
    (mixins/scrollable :y {:persist true})"
  [& args]
  (let [[axes opts] (if (map? (last args))
                      [(butlast args) (last args)]
                      [args {}])
        persist? (:persist opts false)]
    {:did-mount
     (fn [node]
       (scroll/init! (:id node))
       nil)  ;; No effect to track

     :will-unmount
     (fn [node]
       (when-not persist?
         (scroll/destroy! (:id node))))}))

;; ============================================================
;; Scroll Change Watcher Mixin
;; ============================================================

(defn on-scroll-changed
  "Mixin that calls a function whenever scroll position changes.

  Args:
    callback - (fn [id old-pos new-pos] ...) called on scroll change

  Example:
    (mixins/on-scroll-changed
      (fn [id old new]
        (println id \"scrolled from\" old \"to\" new)))

  The Flex effect is automatically disposed by the lifecycle system."
  [callback]
  {:did-mount
   (fn [node]
     ;; Return effect for automatic disposal
     (scroll/watch! (:id node)
       (fn [old-pos new-pos]
         (callback (:id node) old-pos new-pos))))

   :will-unmount
   (fn [_node]
     ;; Effect disposed automatically by lifecycle system
     nil)})

;; ============================================================
;; Virtual Scroll Mixin
;; ============================================================

(defn virtual-scroll
  "Mixin for virtualizing long lists (only render visible items).

  This mixin provides a :compute-children function that returns only
  the visible items based on scroll position, plus a buffer of items
  above and below the viewport.

  Args:
    items - vector of all items (data, not layout nodes)
    item-height - fixed height per item (px)
    render-item - (fn [item index] layout-node) returns tree node for item
    opts - optional {:buffer 3 :padding {:before 0 :after 0}}

  Returns:
    Mixin map with :compute-children function

  Example:
    (mixins/virtual-scroll
      @all-items
      50
      (fn [item i] {:fill (:color item) :label (:name item)})
      {:buffer 5 :padding {:before 10 :after 10}})

  Usage:
    The mixin provides compute-visible-children which you call during render:

    (let [visible (mixins/compute-visible-children mixin-instance id viewport-height)]
      {:id id
       :children-layout {:mode :stack-y :overflow {:y :scroll}}
       :children visible})"
  [items item-height render-item & [opts]]
  (let [buffer (:buffer opts 3)
        padding-before (get-in opts [:padding :before] 0)
        padding-after (get-in opts [:padding :after] 0)
        total-items (count items)
        ;; Content height = before + items + after (matches normal scroll behavior)
        total-height (+ padding-before (* total-items item-height) padding-after)]
    {:item-height item-height
     :total-height total-height
     :total-items total-items

     :did-mount
     (fn [node]
       (scroll/init! (:id node))
       nil)  ;; No effect to track

     :will-unmount
     (fn [node]
       (scroll/destroy! (:id node)))

     :compute-children
     (fn [id viewport-height]
       ;; Update scroll dimensions FIRST - this clamps scroll to valid range
       (scroll/set-dimensions! id
         {:w 0 :h viewport-height}
         {:w 0 :h total-height})

       ;; Now read scroll-y (clamped to valid range by set-dimensions!)
       (let [scroll-y (:y (scroll/get-scroll id) 0)

             ;; Calculate visible range (account for padding-before in scroll content)
             ;; Items start at content position padding-before, not 0
             effective-scroll (- scroll-y padding-before)
             start-idx (max 0 (- (int (/ effective-scroll item-height)) buffer))
             visible-count (int (Math/ceil (/ viewport-height item-height)))
             end-idx (min total-items (+ start-idx visible-count (* 2 buffer)))

             ;; Get slice of visible items
             visible-items (subvec (vec items) start-idx end-idx)]

         ;; Render visible items with absolute positioning
         ;; y-offset is relative to content area (which already has padding from children-layout)
         ;; So item 0 at y-offset 0 appears at container_y + children-layout-before
         (vec (for [[i item] (map-indexed vector visible-items)]
                (let [actual-idx (+ start-idx i)
                      y-offset (* actual-idx item-height)]
                  (merge (render-item item actual-idx)
                         {:layout {:mode :pop-out
                                   :anchor :top-left
                                   :x {:offset 0 :size "100%"}
                                   :y {:offset y-offset :size item-height}}}))))))}))

(defn compute-visible-children
  "Compute visible children for a virtual scroll mixin.

  Args:
    mixin - virtual-scroll mixin instance
    id - container id
    viewport-height - height of visible area

  Returns: vector of layout nodes for visible items"
  [mixin id viewport-height]
  (when-let [compute-fn (:compute-children mixin)]
    (compute-fn id viewport-height)))

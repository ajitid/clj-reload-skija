(ns lib.layout.mixins
  "Reusable lifecycle mixins for layout nodes."
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
       (scroll/init! (:id node)))

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
        (println id \"scrolled from\" old \"to\" new)))"
  [callback]
  (let [watcher-id-atom (atom nil)]
    {:did-mount
     (fn [node]
       (let [id (:id node)
             watcher-id (scroll/watch! id
                          (fn [old-pos new-pos]
                            (callback id old-pos new-pos)))]
         (reset! watcher-id-atom watcher-id)))

     :will-unmount
     (fn [node]
       (when-let [watcher-id @watcher-id-atom]
         (scroll/unwatch! (:id node) watcher-id)))}))

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
    opts - optional {:buffer 3} items to render beyond viewport

  Returns:
    Mixin map with :compute-children function

  Example:
    (mixins/virtual-scroll
      @all-items
      50
      (fn [item i] {:fill (:color item) :label (:name item)})
      {:buffer 5})

  Usage:
    The mixin provides compute-visible-children which you call during render:

    (let [visible (mixins/compute-visible-children mixin-instance id viewport-height)]
      {:id id
       :children-layout {:mode :stack-y :overflow {:y :scroll}}
       :children visible})"
  [items item-height render-item & [opts]]
  (let [buffer (:buffer opts 3)
        total-items (count items)
        total-height (* total-items item-height)]
    {:item-height item-height
     :total-height total-height
     :total-items total-items

     :did-mount
     (fn [node]
       (scroll/init! (:id node)))

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

             ;; Calculate visible range
             start-idx (max 0 (- (int (/ scroll-y item-height)) buffer))
             visible-count (int (Math/ceil (/ viewport-height item-height)))
             end-idx (min total-items (+ start-idx visible-count (* 2 buffer)))

             ;; Get slice of visible items
             visible-items (subvec (vec items) start-idx end-idx)]

         ;; Render visible items with absolute positioning
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

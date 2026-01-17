(ns lib.gesture.hit-test
  "Hit testing algorithm for finding targets under pointer.

   Pure functions - no atom access. All data passed as parameters."
  (:require [lib.gesture.state :as state]))

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
   - ctx: context map passed to bounds-fn (e.g., {:window-width w})
   - targets: map of target-id -> target definition
   - blocked-layers: set of layer keywords to exclude

   Returns sequence of {:target hit-target :depth index} sorted by priority."
  [px py ctx targets blocked-layers]
  (->> (vals targets)
       ;; Filter out targets in blocked layers
       (remove #(contains? blocked-layers (:layer %)))
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
                      {:target target :depth idx}))))

(defn topmost-target
  "Get only the topmost hit target under pointer, or nil.

   Pure function - takes all data as parameters."
  [px py ctx targets blocked-layers]
  (first (hit-test px py ctx targets blocked-layers)))

(ns lib.layout.helpers
  "Convenience helpers for common layout patterns.

   These helpers generate layout trees using spacers to achieve
   alignment effects without needing separate align/justify properties.

   Pure Subform philosophy: alignment is achieved through
   space-before / size / space-after with stretch units."
  (:require [lib.layout.core :as l]))

;; ============================================================
;; Spacer-based alignment helpers
;; ============================================================

(defn hcenter
  "Wrap children in an hstack with spacers to center them horizontally.

   Example:
     (hcenter [(l/box {:w 70})])
     ;; => item centered in available horizontal space"
  ([children] (hcenter {} children))
  ([opts children]
   (l/hstack opts
     (vec (concat [(l/spacer)] children [(l/spacer)])))))

(defn vcenter
  "Wrap children in a vstack with spacers to center them vertically.

   Example:
     (vcenter [(l/box {:h 70})])
     ;; => item centered in available vertical space"
  ([children] (vcenter {} children))
  ([opts children]
   (l/vstack opts
     (vec (concat [(l/spacer)] children [(l/spacer)])))))

(defn hend
  "Wrap children in an hstack with a leading spacer to push them right.

   Example:
     (hend [(l/box {:w 70}) (l/box {:w 70})])
     ;; => items pushed to the right edge"
  ([children] (hend {} children))
  ([opts children]
   (l/hstack opts
     (vec (cons (l/spacer) children)))))

(defn vend
  "Wrap children in a vstack with a leading spacer to push them down.

   Example:
     (vend [(l/box {:h 70})])
     ;; => item pushed to the bottom edge"
  ([children] (vend {} children))
  ([opts children]
   (l/vstack opts
     (vec (cons (l/spacer) children)))))

(defn hstart
  "Wrap children in an hstack with a trailing spacer.
   Mostly for symmetry - same as plain hstack since items start left by default.

   Example:
     (hstart [(l/box {:w 70})])
     ;; => items at left edge, spacer fills remaining space"
  ([children] (hstart {} children))
  ([opts children]
   (l/hstack opts
     (vec (concat children [(l/spacer)])))))

(defn vstart
  "Wrap children in a vstack with a trailing spacer.
   Mostly for symmetry - same as plain vstack since items start top by default.

   Example:
     (vstart [(l/box {:h 70})])
     ;; => items at top edge, spacer fills remaining space"
  ([children] (vstart {} children))
  ([opts children]
   (l/vstack opts
     (vec (concat children [(l/spacer)])))))

;; ============================================================
;; Space distribution helpers
;; ============================================================

(defn hspace-between
  "Wrap children in an hstack with spacers between each child.
   First child at start, last child at end, others evenly distributed.

   Example:
     (hspace-between [(l/box {:w 70}) (l/box {:w 70}) (l/box {:w 70})])
     ;; => items spread across with equal space between"
  ([children] (hspace-between {} children))
  ([opts children]
   (l/hstack opts
     (vec (interpose (l/spacer) children)))))

(defn vspace-between
  "Wrap children in a vstack with spacers between each child.
   First child at top, last child at bottom, others evenly distributed.

   Example:
     (vspace-between [(l/box {:h 70}) (l/box {:h 70}) (l/box {:h 70})])
     ;; => items spread vertically with equal space between"
  ([children] (vspace-between {} children))
  ([opts children]
   (l/vstack opts
     (vec (interpose (l/spacer) children)))))

(defn hspace-around
  "Wrap children in an hstack with spacers around and between each child.
   Equal space before first, between each, and after last.

   Example:
     (hspace-around [(l/box {:w 70}) (l/box {:w 70})])
     ;; => [spacer] [item] [spacer] [item] [spacer]"
  ([children] (hspace-around {} children))
  ([opts children]
   (l/hstack opts
     (vec (concat [(l/spacer)]
                  (interpose (l/spacer) children)
                  [(l/spacer)])))))

(defn vspace-around
  "Wrap children in a vstack with spacers around and between each child.
   Equal space before first, between each, and after last.

   Example:
     (vspace-around [(l/box {:h 70}) (l/box {:h 70})])
     ;; => [spacer] [item] [spacer] [item] [spacer]"
  ([children] (vspace-around {} children))
  ([opts children]
   (l/vstack opts
     (vec (concat [(l/spacer)]
                  (interpose (l/spacer) children)
                  [(l/spacer)])))))


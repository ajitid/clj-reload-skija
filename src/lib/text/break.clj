(ns lib.text.break
  "Word boundary detection using Skija's BreakIterator (ICU wrapper).

   Provides Unicode-correct word segmentation for text fields.
   Handles contractions (don't, it's) as single words, matching
   native OS behavior.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:import [io.github.humbleui.skija BreakIterator]))

;; ============================================================
;; Word Boundaries
;; ============================================================

(defn word-start
  "Find start of the word at or before pos.

   Scans backwards from pos using ICU word break rules, skipping
   non-word segments (spaces, punctuation) to find the nearest
   word start boundary.

   Returns 0 if pos is at or before the beginning."
  [^String text ^long pos]
  (if (or (.isEmpty text) (<= pos 0))
    0
    (with-open [iter (BreakIterator/makeWordInstance)]
      (.setText iter text)
      (loop [b (.preceding iter (min pos (.length text)))]
        (cond
          (= b BreakIterator/DONE) 0
          (Character/isLetterOrDigit (.charAt text b)) b
          :else (recur (.previous iter)))))))

(defn word-end
  "Find end of the word at or after pos.

   Scans forward from pos using ICU word break rules, skipping
   non-word segments (spaces, punctuation) to find the nearest
   word end boundary.

   Returns text length if pos is at or past the end."
  [^String text ^long pos]
  (let [len (.length text)]
    (if (or (.isEmpty text) (>= pos len))
      len
      (with-open [iter (BreakIterator/makeWordInstance)]
        (.setText iter text)
        ;; getRuleStatus >= 100 means the segment ending at the boundary
        ;; is a word (WORD_NUMBER, WORD_LETTER, WORD_KANA, WORD_IDEO)
        (loop [b (.following iter pos)]
          (cond
            (= b BreakIterator/DONE) len
            (>= (.getRuleStatus iter) 100) b
            :else (recur (.next iter))))))))

(defn word-at
  "Get word boundary at a character position.

   Returns {:start N :end N} for the word containing or nearest to pos."
  [^String text ^long pos]
  {:start (word-start text pos)
   :end (word-end text pos)})

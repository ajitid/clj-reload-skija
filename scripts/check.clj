#!/usr/bin/env bb
;; Unified Clojure file checker (cross-platform, runs with Babashka)
;;
;; Runs three checks in sequence, stopping at first failure:
;;   1. Parenthesis balance — fast count of ( vs )
;;   2. Syntax validation  — full Clojure reader parse
;;   3. Lint checks        — semantic issues (docstrings, arities, definition order)
;;
;; Usage: bb scripts/check.clj <file-path> [file-path ...]

(require '[clojure.java.io :as io])

;; ============================================================
;; Stage 1: Parenthesis Balance
;; ============================================================

(defn check-parens
  "Count opening vs closing parentheses. Returns 0 if balanced, 1 if not."
  [file-path]
  (let [content (slurp file-path)
        opens   (count (filter #(= \( %) content))
        closes  (count (filter #(= \) %) content))
        diff    (- opens closes)]
    (println (str "  Parens: " opens " open, " closes " close, diff " diff))
    (if (zero? diff)
      (do (println "  Parens balanced.") 0)
      (do
        (println (str "  IMBALANCED by " diff
                      (if (pos? diff)
                        (str " (missing " diff " closing paren(s))")
                        (str " (extra " (- diff) " closing paren(s))"))))
        1))))

;; ============================================================
;; Stage 2: Syntax Validation (Clojure Reader)
;; ============================================================

(defn check-syntax
  "Read all top-level forms with the Clojure reader. Returns 0 if valid, 1 if not."
  [file-path]
  (try
    (with-open [rdr (java.io.PushbackReader. (io/reader file-path))]
      (loop [form-count 0]
        (let [form (try
                     (read {:eof ::eof} rdr)
                     (catch Exception e
                       (println (str "  SYNTAX ERROR: " (.getMessage e)))
                       ::error))]
          (cond
            (= form ::error) 1
            (= form ::eof)   (do (println (str "  Syntax valid (" form-count " forms)."))
                                 0)
            :else             (recur (inc form-count))))))
    (catch Exception e
      (println (str "  ERROR reading file: " (.getMessage e)))
      1)))

;; ============================================================
;; Stage 3: Lint Checks
;; ============================================================

;; --- Lint rules ---

(def ^:private no-docstring-forms
  "def-like forms that do NOT support docstrings."
  {'defonce      "the string becomes the expression value (or causes wrong arity with 3 args)"
   'defrecord    "the string is parsed where the fields vector [x y ...] is expected"
   'deftype      "the string is parsed where the fields vector [x y ...] is expected"
   'definterface "the string is parsed as a method signature"
   'defstruct    "the string is parsed as a struct key"})

(defn- check-misplaced-docstring
  [form]
  (when (and (seq? form)
             (symbol? (first form))
             (contains? no-docstring-forms (first form)))
    (let [head       (first form)
          args       (vec (rest form))
          name-pos   (first args)
          suspect    (second args)]
      (when (and (symbol? name-pos)
                 (string? suspect))
        {:rule    :misplaced-docstring
         :message (str (name head) " does not support docstrings. "
                       "The string \"" suspect "\" will be misinterpreted — "
                       (get no-docstring-forms head) ". "
                       "Use a ;; comment above instead.")
         :form    (list head name-pos suspect '...)}))))

(defn- check-defonce-arity
  [form]
  (when (and (seq? form)
             (= 'defonce (first form)))
    (let [cnt (count (rest form))]
      (when (> cnt 2)
        {:rule    :defonce-arity
         :message (str "defonce takes exactly 2 args (name expr), got "
                       cnt ". Extra args are silently ignored.")
         :form    (take 4 form)}))))

(defn- check-let-bindings-even
  [form]
  (when (and (seq? form)
             (#{'let 'loop 'if-let 'when-let 'if-some 'when-some} (first form))
             (>= (count form) 2))
    (let [bindings (second form)]
      (when (and (vector? bindings)
                 (odd? (count bindings)))
        {:rule    :odd-bindings
         :message (str (first form) " bindings must have even number of forms, got "
                       (count bindings))
         :form    (list (first form) bindings '...)}))))

;; --- File-level check: definition order ---

(defn- collect-call-position-symbols
  [form]
  (cond
    (and (seq? form) (seq form))
    (concat
      (when (symbol? (first form)) [(first form)])
      (mapcat collect-call-position-symbols form))

    (coll? form)
    (mapcat collect-call-position-symbols form)

    :else nil))

(defn- check-definition-order
  [top-level-forms]
  (let [fn-defs    (keep-indexed
                     (fn [idx form]
                       (when (and (seq? form)
                                  (#{'defn 'defn-} (first form))
                                  (symbol? (second form)))
                         {:index idx :name (second form) :form form}))
                     top-level-forms)
        declared   (into #{}
                     (mapcat (fn [form]
                               (when (and (seq? form) (= 'declare (first form)))
                                 (rest form)))
                             top-level-forms))
        name->index (into {} (map (juxt :name :index) fn-defs))
        fn-names    (set (map :name fn-defs))]
    (mapcat
      (fn [{:keys [index name form]}]
        (let [body-symbols (set (collect-call-position-symbols form))
              forward-refs (filter (fn [sym]
                                     (and (contains? fn-names sym)
                                          (not= sym name)
                                          (> (name->index sym) index)
                                          (not (contains? declared sym))))
                                   body-symbols)]
          (map (fn [ref-sym]
                 {:rule    :definition-order
                  :message (str "`" name "` calls `" ref-sym
                                "` which is defined later in the file. "
                                "Move `" ref-sym "` above `" name "`.")
                  :form    (list (first form) name '... ref-sym '...)})
               forward-refs)))
      fn-defs)))

;; --- Form walker ---

(def ^:private all-checks
  [check-misplaced-docstring
   check-defonce-arity
   check-let-bindings-even])

(defn- walk-forms
  [form check-fns]
  (let [issues (filterv some? (mapv #(% form) check-fns))]
    (if (or (seq? form) (vector? form) (set? form))
      (into issues
            (mapcat #(walk-forms % check-fns)
                    (when (seqable? form) form)))
      issues)))

;; --- Lint runner ---

(defn check-lint
  "Run all lint checks on a file. Returns 0 if clean, 1 if issues found."
  [file-path]
  (try
    (let [issues          (atom [])
          top-level-forms (atom [])]
      (with-open [rdr (java.io.PushbackReader. (io/reader file-path))]
        (loop []
          (let [form (try
                       (read {:eof ::eof} rdr)
                       (catch Exception e
                         (swap! issues conj {:rule    :read-error
                                             :message (.getMessage e)
                                             :form    nil})
                         ::eof))]
            (when (not= form ::eof)
              (swap! top-level-forms conj form)
              (swap! issues into (walk-forms form all-checks))
              (recur)))))
      ;; File-level checks
      (swap! issues into (check-definition-order @top-level-forms))
      (if (seq @issues)
        (do
          (println (str "  Lint: " (count @issues) " issue(s):"))
          (doseq [{:keys [rule message form]} @issues]
            (println (str "    [" (name rule) "] " message))
            (when form
              (println (str "    Form: " (pr-str form)))))
          1)
        (do
          (println "  Lint clean.")
          0)))
    (catch Exception e
      (println (str "  ERROR reading file: " (.getMessage e)))
      1)))

;; ============================================================
;; Main: run all stages per file
;; ============================================================

(defn check-file
  "Run all checks on a single file. Returns 0 if all pass, 1 if any fail."
  [file-path]
  (println (str "Checking " file-path " ..."))
  (if (not (.exists (io/file file-path)))
    (do (println (str "  File not found: " file-path))
        1)
    (let [r1 (check-parens file-path)]
      (if (pos? r1)
        1
        (let [r2 (check-syntax file-path)]
          (if (pos? r2)
            1
            (check-lint file-path)))))))

(let [file-paths *command-line-args*]
  (if (seq file-paths)
    (let [exit-code (reduce + (map check-file file-paths))]
      (System/exit (min exit-code 1)))
    (do
      (println "Usage: bb scripts/check.clj <file-path> [file-path ...]")
      (println "")
      (println "Runs three checks in sequence (stops at first failure):")
      (println "  1. Parenthesis balance   — fast ( vs ) count")
      (println "  2. Syntax validation     — full Clojure reader parse")
      (println "  3. Lint checks:")
      (println "     - Misplaced docstrings in defonce, defrecord, deftype, etc.")
      (println "     - defonce with wrong number of args")
      (println "     - let/loop with odd number of bindings")
      (println "     - Definition order: defn calling functions defined later in the file")
      (System/exit 1))))

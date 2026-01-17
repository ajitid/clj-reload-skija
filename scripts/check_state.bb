#!/usr/bin/env bb

;; Babashka script to check if all keys in initial-state are used in defonce declarations
;; Usage: bb scripts/check_state.bb [path-to-state-file]

(require '[clojure.edn :as edn]
         '[clojure.string :as str])

(def default-state-file "src/app/state.clj")

(defn read-forms
  "Read all Clojure forms from a file."
  [file-path]
  (let [content (slurp file-path)
        reader (java.io.PushbackReader. (java.io.StringReader. content))]
    (loop [forms []]
      (let [form (try (edn/read {:eof ::eof} reader)
                      (catch Exception e ::error))]
        (cond
          (= form ::eof) forms
          (= form ::error) forms
          :else (recur (conj forms form)))))))

(defn find-initial-state-keys
  "Find all keys defined in the initial-state map."
  [forms]
  (let [initial-state-form (->> forms
                                (filter #(and (seq? %)
                                              (= 'def (first %))
                                              (= 'initial-state (second %))))
                                first)]
    (when initial-state-form
      ;; The map is the last element (after docstring if present)
      (let [map-form (->> initial-state-form
                          (filter map?)
                          first)]
        (when map-form
          (set (keys map-form)))))))

(defn find-defonce-used-keys
  "Find all initial-state keys referenced in defonce declarations.
   Looks for patterns like: (defonce name (atom (:key initial-state)))"
  [forms]
  (->> forms
       (filter #(and (seq? %)
                     (= 'defonce (first %))))
       (mapcat (fn [form]
                 ;; Walk the form looking for (:keyword initial-state) patterns
                 (let [s (str form)]
                   (->> (re-seq #"\(:([a-zA-Z0-9_?!-]+)\s+initial-state\)" s)
                        (map (comp keyword second))))))
       set))

(defn check-state-file
  [file-path]
  (let [forms (read-forms file-path)
        initial-keys (find-initial-state-keys forms)
        used-keys (find-defonce-used-keys forms)]

    (if (nil? initial-keys)
      (do
        (println "Error: Could not find initial-state definition")
        (System/exit 1))

      (let [unused-keys (clojure.set/difference initial-keys used-keys)
            missing-keys (clojure.set/difference used-keys initial-keys)]

        (if (and (empty? unused-keys) (empty? missing-keys))
          (do
            (println "✅ All of initial-state is being used in defonce")
            (System/exit 0))
          (do
            (when (seq unused-keys)
              (println "⚠️ UNUSED keys (in initial-state but no defonce):")
              (doseq [k (sort unused-keys)]
                (println (str "  " k))))

            (when (seq missing-keys)
              (println "⚠️ MISSING keys (in defonce but not in initial-state):")
              (doseq [k (sort missing-keys)]
                (println (str "  " k))))

            (System/exit 1)))))))

;; Main
(let [file-path (or (first *command-line-args*) default-state-file)]
  (if (.exists (java.io.File. file-path))
    (check-state-file file-path)
    (do
      (println (str "Error: File not found: " file-path))
      (System/exit 1))))

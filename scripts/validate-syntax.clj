#!/usr/bin/env bb
;; Validate Clojure file syntax using babashka
;; Usage: bb scripts/validate-syntax.clj <file-path>

(require '[clojure.java.io :as io])

(defn validate-file [file-path]
  (try
    (let [content (slurp file-path)]
      ;; Try to read all forms from the file
      (with-open [rdr (java.io.PushbackReader. (io/reader file-path))]
        (loop [form-count 0]
          (let [form (try
                       (read {:eof ::eof} rdr)
                       (catch Exception e
                         (println "❌ SYNTAX ERROR in" file-path)
                         (println "   " (.getMessage e))
                         (System/exit 1)))]
            (if (= form ::eof)
              (do
                (println "✅" file-path "is valid (" form-count "forms)")
                0)
              (recur (inc form-count)))))))
    (catch Exception e
      (println "❌ ERROR reading file:" (.getMessage e))
      1)))

(let [file-path (first *command-line-args*)]
  (if file-path
    (System/exit (validate-file file-path))
    (do
      (println "Usage: bb scripts/validate-syntax.clj <file-path>")
      (System/exit 1))))

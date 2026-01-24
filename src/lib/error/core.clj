(ns lib.error.core
  "Pure error handling utilities - parsing, formatting, clipboard.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:require [clojure.string :as str]
            [lib.window.core :as window])
  (:import [java.io StringWriter PrintWriter]))

;; ============================================================
;; Error Analysis
;; ============================================================

(defn get-root-cause
  "Traverse exception chain to find root cause."
  [^Throwable e]
  (if-let [cause (.getCause e)]
    (recur cause)
    e))

(defn get-compiler-location
  "Extract file:line:column from Clojure CompilerException ex-data."
  [^Throwable e]
  (when-let [data (ex-data e)]
    (let [source (:clojure.error/source data)
          line (:clojure.error/line data)
          column (:clojure.error/column data)]
      (when (and source line)
        (str source ":" line (when column (str ":" column)))))))

(defn clj-reload-parse-error?
  "Check if exception is from clj-reload's file reading/parsing (scan function)."
  [^Throwable e]
  (let [stack-trace (.getStackTrace e)]
    (some #(str/includes? (.getClassName ^StackTraceElement %) "clj_reload.core$scan")
          stack-trace)))

(defn get-error-info
  "Extract useful error information from exception chain.
   Returns {:message :location :root-message :type}."
  [^Throwable e]
  (let [root (get-root-cause e)
        ;; Try to find CompilerException in the chain for location info
        compiler-ex (loop [ex e]
                      (cond
                        (nil? ex) nil
                        (instance? clojure.lang.Compiler$CompilerException ex) ex
                        :else (recur (.getCause ex))))
        location (when compiler-ex (get-compiler-location compiler-ex))
        ;; Get the message
        root-msg (.getMessage root)
        msg-empty? (or (nil? root-msg) (= root-msg "null") (empty? root-msg))
        ;; Only suggest console for clj-reload parse errors with empty messages
        best-msg (if (and msg-empty? (clj-reload-parse-error? e))
                   "(see console for details)"
                   root-msg)]
    {:type (.getSimpleName (.getClass root))
     :message best-msg
     :location location
     :original-message (.getMessage e)}))

(defn get-stack-trace-string
  "Get stack trace as a string."
  [^Throwable e]
  (let [sw (StringWriter.)
        pw (PrintWriter. sw)]
    (.printStackTrace e pw)
    (.toString sw)))

;; ============================================================
;; Text Utilities
;; ============================================================

(defn truncate-with-ellipsis
  "Truncate string to max-len, adding ellipsis if truncated."
  [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 max-len) "…")
    s))

;; ============================================================
;; Clipboard Integration
;; ============================================================

(defn copy-to-clipboard!
  "Copy text to system clipboard using SDL3."
  [^String text]
  (window/set-clipboard-text! text))

(defn format-error-for-clipboard
  "Format error with full stack trace for clipboard."
  [^Throwable e]
  (let [{:keys [type message location original-message]} (get-error-info e)
        lines [(str "ERROR")
               (when location (str "Location: " location))
               (str type ": " message)
               (when (and original-message (not= original-message message))
                 (str "Context: " original-message))
               ""
               "Stack trace:"
               (get-stack-trace-string e)]]
    (str/join "\n" (remove nil? lines))))

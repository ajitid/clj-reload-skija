(ns lib.error.overlay
  "Error overlay rendering - debug visualization for exceptions.

   NOTE: Not hot-reloadable (lib.* namespaces require restart per clj-reload pattern)."
  (:require [lib.error.core :as err]
            [lib.color.core :as color]
            [clojure.string :as str])
  (:import [io.github.humbleui.skija Canvas Paint Font FontMgr FontStyle Color4f]))

;; ============================================================
;; Error Overlay Rendering
;; ============================================================

(defn draw-error
  "Draw error message and stack trace on canvas with red background."
  [^Canvas canvas ^Exception e]
  (let [bg-color    [0.8 0.27 0.27 1.0]  ; #CC4444
        text-color  color/white
        font-size   14
        padding     20
        line-height 18
        {:keys [type message location original-message]} (err/get-error-info e)]
    ;; Red background
    (.clear canvas (color/color4f->hex bg-color))
    ;; Draw error text
    (let [[r g b a] text-color]
      (with-open [typeface (.matchFamilyStyle (FontMgr/getDefault) nil FontStyle/NORMAL)
                  font (Font. typeface (float font-size))
                  paint (doto (Paint.)
                          (.setColor4f (Color4f. (float r) (float g) (float b) (float a))))]
      ;; Header
      (.drawString canvas "ERROR (Ctrl+E or middle-click to copy)" (float padding) (float (+ padding line-height)) font paint)
      ;; Location (file:line:column) if available (for compile errors)
      (when location
        (.drawString canvas (str "at " location) (float padding) (float (+ padding (* 2.5 line-height))) font paint))
      ;; Root cause message (the actual error like "Unable to resolve symbol: forma")
      (let [root-msg (str type ": " message)
            y-offset (if location 4.0 2.5)]
        (.drawString canvas root-msg (float padding) (float (+ padding (* y-offset line-height))) font paint))
      ;; Original message if different (shows context like "Failed to load namespace")
      (let [base-y-offset (if location 5.5 4.0)
            has-context? (and original-message (not= original-message message))]
        (when has-context?
          (.drawString canvas
                       (str "(while: " (err/truncate-with-ellipsis original-message 80) ")")
                       (float padding)
                       (float (+ padding (* base-y-offset line-height)))
                       font paint))
        ;; Stack trace
        (let [stack-lines (-> (err/get-stack-trace-string e)
                              (str/split #"\n")
                              (->> (take 15)))
              stack-y-offset (if has-context? (+ base-y-offset 1.5) base-y-offset)]
          (doseq [[idx line] (map-indexed vector stack-lines)]
            (.drawString canvas
                         (err/truncate-with-ellipsis line 100)
                         (float padding)
                         (float (+ padding (* (+ stack-y-offset idx) line-height)))
                         font paint))))))))

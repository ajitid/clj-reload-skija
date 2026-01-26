(ns app.ui.text-field
  "Generic text field widget for UI controls.

   A text field displays an editable text box with cursor and selection.
   Supports focus management, text insertion, deletion, cursor movement,
   clipboard copy/cut/paste, and select-all."
  (:require [app.state.system :as sys]
            [lib.text.core :as text]
            [lib.text.measure :as measure])
  (:import [io.github.humbleui.skija Canvas Paint]
           [io.github.humbleui.types Rect]))

;; ============================================================
;; Key constants (SDL3 keycodes)
;; ============================================================

(def ^:private KEY_BACKSPACE 0x08)
(def ^:private KEY_RETURN    0x0D)
(def ^:private KEY_ESCAPE    0x1B)
(def ^:private KEY_DELETE     0x7F)
(def ^:private KEY_LEFT       0x40000050)
(def ^:private KEY_RIGHT      0x4000004F)
(def ^:private KEY_HOME       0x4000004A)
(def ^:private KEY_END        0x4000004D)
(def ^:private KEY_A          0x61)
(def ^:private KEY_C          0x63)
(def ^:private KEY_V          0x76)
(def ^:private KEY_X          0x78)

;; Modifier masks (SDL3 KMOD values)
(def ^:private MOD_SHIFT 0x0003)  ;; LSHIFT | RSHIFT
(def ^:private MOD_CTRL  0x00C0)  ;; LCTRL | RCTRL
(def ^:private MOD_ALT   0x0300)  ;; LALT | RALT
(def ^:private MOD_GUI   0x0C00)  ;; LGUI | RGUI
(def ^:private MOD_CMD   0x0CC0)  ;; Ctrl or Cmd (for shortcuts)

;; ============================================================
;; Styling
;; ============================================================

(def ^:private box-bg-color        0xFF2A2A2A)
(def ^:private border-focused      0xFF4A90D9)
(def ^:private border-unfocused    0xFF555555)
(def ^:private text-color          0xFFFFFFFF)
(def ^:private cursor-color        0xFFFFFFFF)
(def ^:private label-color         0xFFAAAAAA)
(def ^:private selection-color     0x664A90D9)
(def ^:private font-size           13)
(def ^:private pad-x               5)
(def ^:private pad-y               3)
(def ^:private cursor-blink-ms     500)

;; ============================================================
;; State (persists across hot-reloads)
;; ============================================================

;; Focus state: nil or {:field-id [...] :cursor-pos N :selection-start N-or-nil}
;; When :selection-start is non-nil and differs from cursor-pos,
;; text between selection-start and cursor-pos is selected.
(defonce focus-state (atom nil))

;; Field registry: {field-id value-atom}
(defonce field-registry (atom {}))

;; Last cursor activity time (game-time in seconds) — for blink reset
(defonce last-activity-time (atom 0.0))

;; Field bounds: {field-id [bx by bw bh]} — populated during draw
(defonce field-bounds (atom {}))

;; Mouse drag state for text selection
(defonce dragging? (atom false))

;; ============================================================
;; Value atom access (matching control panel pattern)
;; ============================================================

(defn- deref-value
  "Dereference a value-atom, handling both Var->defsource and direct defsource."
  [va]
  (try (deref (deref va))   ;; Var -> defsource -> value
    (catch Exception _ (deref va))))

(defn- set-value!
  "Set a value-atom, handling both Var->defsource setter and direct defsource."
  [va new-val]
  (try ((deref va) new-val)  ;; Var -> defsource setter
    (catch Exception _ (va new-val))))

;; ============================================================
;; Blink reset
;; ============================================================

(defn- reset-blink!
  "Reset the cursor blink timer so cursor is solid after any action."
  []
  (reset! last-activity-time (or @sys/game-time 0)))

;; ============================================================
;; Selection helpers
;; ============================================================

(defn- selection-range
  "Get the ordered [start end] of the current selection, or nil if no selection."
  []
  (when-let [{:keys [cursor-pos selection-start]} @focus-state]
    (when (and selection-start (not= selection-start cursor-pos))
      [(min selection-start cursor-pos) (max selection-start cursor-pos)])))

(defn- selected-text
  "Get the currently selected text, or nil."
  []
  (when-let [[sel-start sel-end] (selection-range)]
    (when-let [{:keys [field-id]} @focus-state]
      (when-let [va (get @field-registry field-id)]
        (let [current (str (deref-value va))]
          (subs current
                (min sel-start (count current))
                (min sel-end (count current))))))))

(defn- delete-selection!
  "Delete the selected text. Returns true if there was a selection to delete."
  []
  (when-let [[sel-start sel-end] (selection-range)]
    (when-let [{:keys [field-id]} @focus-state]
      (when-let [va (get @field-registry field-id)]
        (let [current (str (deref-value va))
              clamped-end (min sel-end (count current))
              new-text (str (subs current 0 sel-start)
                            (subs current clamped-end))]
          (set-value! va new-text)
          (swap! focus-state assoc
                 :cursor-pos sel-start
                 :selection-start nil)
          true)))))

;; ============================================================
;; Word boundary detection
;; ============================================================

(defn- word-boundary-left
  "Find the start of the word to the left of pos.
   Skips non-word chars (whitespace/punctuation), then skips word chars."
  [^String text pos]
  (if (<= pos 0)
    0
    (let [;; Step 1: skip non-word chars going left
          p (loop [p (dec pos)]
              (if (or (< p 0) (Character/isLetterOrDigit (.charAt text p)))
                p
                (recur (dec p))))
          ;; Step 2: skip word chars going left to find start
          p (loop [p p]
              (if (or (< p 0) (not (Character/isLetterOrDigit (.charAt text p))))
                (inc p)
                (recur (dec p))))]
      (max 0 p))))

(defn- word-boundary-right
  "Find position after end of the word to the right of pos.
   Skips non-word chars (whitespace/punctuation), then skips word chars."
  [^String text pos]
  (let [len (count text)]
    (if (>= pos len)
      len
      (let [;; Step 1: skip non-word chars going right
            p (loop [p pos]
                (if (or (>= p len) (Character/isLetterOrDigit (.charAt text p)))
                  p
                  (recur (inc p))))
            ;; Step 2: skip word chars going right to find end
            p (loop [p p]
                (if (or (>= p len) (not (Character/isLetterOrDigit (.charAt text p))))
                  p
                  (recur (inc p))))]
        (min len p)))))

;; ============================================================
;; Cursor movement helper
;; ============================================================

(defn- move-to!
  "Move cursor to new-pos. If shift? is true, extend/create selection.
   If shift? is false, clear selection."
  [new-pos shift?]
  (if shift?
    ;; Extend selection: anchor at selection-start, or current cursor if no selection
    (let [anchor (or (:selection-start @focus-state) (:cursor-pos @focus-state))]
      (swap! focus-state assoc :cursor-pos new-pos :selection-start anchor))
    ;; No shift: clear selection
    (swap! focus-state assoc :cursor-pos new-pos :selection-start nil)))

;; ============================================================
;; Focus Management
;; ============================================================

(defn register-field!
  "Register a field's value atom for editing."
  [field-id value-atom]
  (swap! field-registry assoc field-id value-atom))

(defn- get-window-handle
  "Get the raw SDL window handle from system state."
  []
  (when-let [win-atom (requiring-resolve 'app.state.system/window)]
    (when-let [win @@win-atom]
      (:handle win))))

(defn focus!
  "Set focus to a field, placing cursor at end of text.
   Starts SDL text input so SDL_EVENT_TEXT_INPUT events are generated."
  [field-id]
  (when-let [va (get @field-registry field-id)]
    (let [text-val (str (deref-value va))]
      (reset! focus-state {:field-id field-id
                           :cursor-pos (count text-val)
                           :selection-start nil})
      (reset-blink!)
      ;; Start SDL text input
      (when-let [handle (get-window-handle)]
        (when-let [start! (requiring-resolve 'lib.window.internal/start-text-input!)]
          (start! handle))))))

(defn unfocus!
  "Clear focus from any text field.
   Stops SDL text input."
  []
  (when (some? @focus-state)
    (reset! focus-state nil)
    ;; Stop SDL text input
    (when-let [handle (get-window-handle)]
      (when-let [stop! (requiring-resolve 'lib.window.internal/stop-text-input!)]
        (stop! handle)))))

(defn any-focused?
  "Check if any text field has focus."
  []
  (some? @focus-state))

(defn- focused-field-id
  "Get the currently focused field ID, or nil."
  []
  (:field-id @focus-state))

;; ============================================================
;; Mouse interaction (cursor positioning & drag-to-select)
;; ============================================================

(defn hit-test
  "Test if (x, y) is within any registered text field bounds.
   Returns the field-id if hit, nil otherwise."
  [x y]
  (some (fn [[fid [bx by bw bh]]]
          (when (and (>= x bx) (<= x (+ bx bw))
                     (>= y by) (<= y (+ by bh)))
            fid))
        @field-bounds))

(defn- x-to-char-pos
  "Convert a pixel x coordinate to a character index within text.
   Linear scan measuring prefix widths, picks nearest boundary."
  [text click-x text-start-x]
  (let [text-str (str text)
        len (count text-str)
        rel-x (- click-x text-start-x)]
    (if (<= rel-x 0)
      0
      (loop [i 0
             prev-w 0.0]
        (if (> i len)
          len
          (let [w (measure/text-width (subs text-str 0 i) {:size font-size})]
            (if (< rel-x w)
              ;; Between prev boundary and this one — pick closest
              (if (< (- rel-x prev-w) (- w rel-x))
                (dec i)
                i)
              (recur (inc i) w))))))))

(defn handle-mouse-down!
  "Handle a primary mouse button press on a text field.
   Focuses the field (if needed) and positions cursor at the clicked character.
   Begins drag-to-select by anchoring selection-start."
  [field-id x _y]
  (when-let [va (get @field-registry field-id)]
    (let [text-val (str (deref-value va))
          [bx _by _bw _bh] (get @field-bounds field-id)
          text-start-x (+ bx pad-x)
          char-pos (x-to-char-pos text-val x text-start-x)
          already-focused? (= field-id (focused-field-id))]
      (if already-focused?
        ;; Already focused — just reposition cursor and start selection anchor
        (do
          (swap! focus-state assoc
                 :cursor-pos char-pos
                 :selection-start char-pos)
          (reset-blink!))
        ;; Not focused — focus at clicked position
        (do
          (reset! focus-state {:field-id field-id
                               :cursor-pos char-pos
                               :selection-start char-pos})
          (reset-blink!)
          (when-let [handle (get-window-handle)]
            (when-let [start! (requiring-resolve 'lib.window.internal/start-text-input!)]
              (start! handle)))))
      (reset! dragging? true))))

(defn handle-mouse-move!
  "Handle mouse move during a text-field drag-to-select.
   Extends selection from the anchor to the character at current x."
  [x _y]
  (when @dragging?
    (when-let [{:keys [field-id selection-start]} @focus-state]
      (when-let [va (get @field-registry field-id)]
        (let [text-val (str (deref-value va))
              [bx _by _bw _bh] (get @field-bounds field-id)
              text-start-x (+ bx pad-x)
              char-pos (x-to-char-pos text-val x text-start-x)]
          (swap! focus-state assoc :cursor-pos char-pos)
          (reset-blink!))))))

(defn handle-mouse-up!
  "Handle mouse button release after a text-field drag.
   Clears dragging state. If no actual selection was made (start == cursor),
   clears selection-start."
  []
  (when @dragging?
    (reset! dragging? false)
    (when-let [{:keys [cursor-pos selection-start]} @focus-state]
      (when (= cursor-pos selection-start)
        (swap! focus-state assoc :selection-start nil)))))

(defn dragging-text?
  "Returns true if user is currently drag-selecting in a text field."
  []
  @dragging?)

;; ============================================================
;; Text Editing
;; ============================================================

(defn handle-text-input!
  "Insert text at cursor position, replacing selection if any.
   Called from EventTextInput handler."
  [input-text]
  (when-let [{:keys [field-id]} @focus-state]
    (when-let [va (get @field-registry field-id)]
      (reset-blink!)
      ;; Delete selection first if any
      (delete-selection!)
      ;; Re-read cursor after potential selection delete
      (let [{:keys [cursor-pos]} @focus-state
            current (str (deref-value va))
            pos (min cursor-pos (count current))
            new-text (str (subs current 0 pos) input-text (subs current pos))]
        (set-value! va new-text)
        (swap! focus-state assoc
               :cursor-pos (+ pos (count input-text))
               :selection-start nil)))))

(defn handle-key-event!
  "Handle a key event when a text field is focused.
   Returns true if the event was consumed."
  [event]
  (when-let [{:keys [field-id cursor-pos]} @focus-state]
    (when-let [va (get @field-registry field-id)]
      (reset-blink!)
      (let [{:keys [key pressed? modifiers]} event
            current (str (deref-value va))
            len (count current)
            pos (min cursor-pos len)
            shift? (pos? (bit-and modifiers MOD_SHIFT))
            ctrl?  (pos? (bit-and modifiers MOD_CTRL))
            alt?   (pos? (bit-and modifiers MOD_ALT))
            gui?   (pos? (bit-and modifiers MOD_GUI))
            cmd?   (or ctrl? gui?)  ;; for shortcuts (Cmd on macOS, Ctrl on Win/Linux)
            ;; Word movement: Alt on macOS, Ctrl on Win/Linux
            word-mod? (or alt? ctrl?)
            has-sel? (some? (selection-range))]
        (cond
          ;; Escape or Return -> unfocus
          (or (= key KEY_ESCAPE) (= key KEY_RETURN))
          (do (unfocus!) true)

          ;; Cmd/Ctrl+A -> select all
          (and cmd? (= key KEY_A))
          (do (swap! focus-state assoc
                     :selection-start 0
                     :cursor-pos len)
              true)

          ;; Cmd/Ctrl+C -> copy selected text
          (and cmd? (= key KEY_C))
          (do
            (when-let [sel-text (selected-text)]
              (when-let [set-clip! (requiring-resolve 'lib.window.internal/set-clipboard-text!)]
                (set-clip! sel-text)))
            true)

          ;; Cmd/Ctrl+X -> cut (copy + delete)
          (and cmd? (= key KEY_X))
          (do
            (when-let [sel-text (selected-text)]
              (when-let [set-clip! (requiring-resolve 'lib.window.internal/set-clipboard-text!)]
                (set-clip! sel-text)))
            (delete-selection!)
            true)

          ;; Cmd/Ctrl+V -> paste (replaces selection)
          (and cmd? (= key KEY_V))
          (do
            (delete-selection!)
            (let [{:keys [cursor-pos]} @focus-state
                  cur (str (deref-value va))
                  p (min cursor-pos (count cur))]
              (when-let [clip-text (when-let [get-clip (requiring-resolve 'lib.window.internal/get-clipboard-text)]
                                     (get-clip))]
                (when (and clip-text (pos? (count clip-text)))
                  (let [new-text (str (subs cur 0 p) clip-text (subs cur p))]
                    (set-value! va new-text)
                    (swap! focus-state assoc
                           :cursor-pos (+ p (count clip-text))
                           :selection-start nil)))))
            true)

          ;; Backspace -> delete selection or char before cursor
          (= key KEY_BACKSPACE)
          (if has-sel?
            (do (delete-selection!) true)
            (when (pos? pos)
              (let [new-text (str (subs current 0 (dec pos)) (subs current pos))]
                (set-value! va new-text)
                (swap! focus-state assoc :cursor-pos (dec pos) :selection-start nil))
              true))

          ;; Delete -> delete selection or char after cursor
          (= key KEY_DELETE)
          (if has-sel?
            (do (delete-selection!) true)
            (when (< pos len)
              (let [new-text (str (subs current 0 pos) (subs current (inc pos)))]
                (set-value! va new-text)
                (swap! focus-state assoc :selection-start nil))
              true))

          ;; Left arrow (+ modifiers for word/line movement and selection)
          (= key KEY_LEFT)
          (do (cond
                ;; Cmd+Left (macOS) -> line start
                gui?     (move-to! 0 shift?)
                ;; Alt+Left (macOS) / Ctrl+Left (Win) -> word left
                word-mod? (move-to! (word-boundary-left current pos) shift?)
                ;; Shift+Left -> extend selection by char
                shift?   (move-to! (max 0 (dec pos)) true)
                ;; Plain Left with selection -> collapse to left edge
                has-sel? (let [[sel-start _] (selection-range)]
                           (swap! focus-state assoc :cursor-pos sel-start :selection-start nil))
                ;; Plain Left -> move left by char
                :else    (move-to! (max 0 (dec pos)) false))
              true)

          ;; Right arrow (+ modifiers for word/line movement and selection)
          (= key KEY_RIGHT)
          (do (cond
                ;; Cmd+Right (macOS) -> line end
                gui?     (move-to! len shift?)
                ;; Alt+Right (macOS) / Ctrl+Right (Win) -> word right
                word-mod? (move-to! (word-boundary-right current pos) shift?)
                ;; Shift+Right -> extend selection by char
                shift?   (move-to! (min len (inc pos)) true)
                ;; Plain Right with selection -> collapse to right edge
                has-sel? (let [[_ sel-end] (selection-range)]
                           (swap! focus-state assoc :cursor-pos sel-end :selection-start nil))
                ;; Plain Right -> move right by char
                :else    (move-to! (min len (inc pos)) false))
              true)

          ;; Home (+ Shift for selection)
          (= key KEY_HOME)
          (do (move-to! 0 shift?) true)

          ;; End (+ Shift for selection)
          (= key KEY_END)
          (do (move-to! len shift?) true)

          ;; Not consumed
          :else false)))))

;; ============================================================
;; Drawing
;; ============================================================

(defn draw
  "Draw a text field with label above it.

   Parameters:
   - canvas: Skija Canvas
   - label: String label above the field
   - text-value: Current text string
   - field-id: Unique field identifier (vector)
   - bounds: [x y w h] bounding box for the text input area
   - opts: Map of options (reserved for future use)"
  [^Canvas canvas label text-value field-id [bx by bw bh] opts]
  ;; Store bounds for mouse hit-testing
  (swap! field-bounds assoc field-id [bx by bw bh])
  (let [focused? (= field-id (focused-field-id))
        border-color (if focused? border-focused border-unfocused)]
    ;; Draw label above
    (text/text canvas (str label ":") bx (- by 4)
               {:size font-size :color label-color})
    ;; Draw box background
    (with-open [bg-paint (doto (Paint.)
                           (.setColor (unchecked-int box-bg-color)))]
      (.drawRect canvas (Rect/makeXYWH (float bx) (float by) (float bw) (float bh)) bg-paint))
    ;; Draw box border
    (with-open [border-paint (doto (Paint.)
                               (.setColor (unchecked-int border-color))
                               (.setMode io.github.humbleui.skija.PaintMode/STROKE)
                               (.setStrokeWidth (float 1.0)))]
      (.drawRect canvas (Rect/makeXYWH (float bx) (float by) (float bw) (float bh)) border-paint))
    ;; Draw text with clipping
    (let [text-x (+ bx pad-x)
          text-y (+ by (/ bh 2) 4)
          text-str (str text-value)
          clip-rect (Rect/makeXYWH (float (+ bx 1)) (float (+ by 1))
                                   (float (- bw 2)) (float (- bh 2)))]
      (.save canvas)
      (.clipRect canvas clip-rect)
      ;; Draw selection highlight when focused
      (when focused?
        (when-let [[sel-start sel-end] (selection-range)]
          (let [clamped-start (min sel-start (count text-str))
                clamped-end (min sel-end (count text-str))
                before-sel (subs text-str 0 clamped-start)
                sel-text (subs text-str clamped-start clamped-end)
                sel-x-start (+ text-x (measure/text-width before-sel {:size font-size}))
                sel-width (measure/text-width sel-text {:size font-size})]
            (when (pos? sel-width)
              (with-open [sel-paint (doto (Paint.)
                                     (.setColor (unchecked-int selection-color)))]
                (.drawRect canvas
                           (Rect/makeXYWH (float sel-x-start)
                                          (float (+ by pad-y))
                                          (float sel-width)
                                          (float (- bh (* 2 pad-y))))
                           sel-paint))))))
      ;; Draw text
      (text/text canvas text-value text-x text-y
                 {:size font-size :color text-color})
      ;; Draw cursor when focused
      (when focused?
        (let [cursor-pos (get @focus-state :cursor-pos 0)
              before-cursor (subs text-str 0 (min cursor-pos (count text-str)))
              cursor-x-offset (measure/text-width before-cursor {:size font-size})
              cursor-x (+ text-x cursor-x-offset)
              ;; Blink logic:
              ;; 1. Selection active -> always visible
              ;; 2. Recently active (within one blink period) -> solid
              ;; 3. Otherwise -> blink normally
              has-sel? (some? (selection-range))
              game-time (or @sys/game-time 0)
              elapsed-since-activity (- game-time @last-activity-time)
              blink-delay (/ cursor-blink-ms 1000.0)
              blink-on? (or has-sel?
                            (< elapsed-since-activity blink-delay)
                            (< (mod (* elapsed-since-activity 1000) (* 2 cursor-blink-ms)) cursor-blink-ms))]
          (when blink-on?
            (with-open [cursor-paint (doto (Paint.)
                                      (.setColor (unchecked-int cursor-color))
                                      (.setStrokeWidth (float 1.5)))]
              (.drawLine canvas
                         (float cursor-x) (float (+ by pad-y))
                         (float cursor-x) (float (+ by bh (- pad-y)))
                         cursor-paint)))))
      (.restore canvas))))

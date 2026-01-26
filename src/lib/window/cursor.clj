(ns lib.window.cursor
  "Mouse cursor management — Love2D-style API.

   Set system cursors by keyword:
     (cursor/set-cursor! :pointer)     ;; hand cursor (links, buttons)
     (cursor/set-cursor! :text)        ;; I-beam (text fields)
     (cursor/set-cursor! :crosshair)   ;; precise selection
     (cursor/set-cursor! :move)        ;; drag/move
     (cursor/set-cursor! :default)     ;; arrow (reset to default)

   Show/hide:
     (cursor/hide!)
     (cursor/show!)
     (cursor/visible?)

   All cursors are cached — calling set-cursor! repeatedly with the same
   keyword does not allocate."
  (:require [lib.window.internal :as sdl])
  (:import [org.lwjgl.sdl SDLMouse]))

;; ============================================================
;; System cursor keyword -> SDL enum mapping
;; ============================================================

(def ^:private system-cursor-ids
  "Map of keyword to SDL_SYSTEM_CURSOR_* enum value."
  {:default      SDLMouse/SDL_SYSTEM_CURSOR_DEFAULT
   :arrow        SDLMouse/SDL_SYSTEM_CURSOR_DEFAULT
   :text         SDLMouse/SDL_SYSTEM_CURSOR_TEXT
   :ibeam        SDLMouse/SDL_SYSTEM_CURSOR_TEXT
   :wait         SDLMouse/SDL_SYSTEM_CURSOR_WAIT
   :crosshair    SDLMouse/SDL_SYSTEM_CURSOR_CROSSHAIR
   :progress     SDLMouse/SDL_SYSTEM_CURSOR_PROGRESS
   :pointer      SDLMouse/SDL_SYSTEM_CURSOR_POINTER
   :hand         SDLMouse/SDL_SYSTEM_CURSOR_POINTER
   :move         SDLMouse/SDL_SYSTEM_CURSOR_MOVE
   :not-allowed  SDLMouse/SDL_SYSTEM_CURSOR_NOT_ALLOWED
   :ew-resize    SDLMouse/SDL_SYSTEM_CURSOR_EW_RESIZE
   :ns-resize    SDLMouse/SDL_SYSTEM_CURSOR_NS_RESIZE
   :nwse-resize  SDLMouse/SDL_SYSTEM_CURSOR_NWSE_RESIZE
   :nesw-resize  SDLMouse/SDL_SYSTEM_CURSOR_NESW_RESIZE
   :n-resize     SDLMouse/SDL_SYSTEM_CURSOR_N_RESIZE
   :e-resize     SDLMouse/SDL_SYSTEM_CURSOR_E_RESIZE
   :s-resize     SDLMouse/SDL_SYSTEM_CURSOR_S_RESIZE
   :w-resize     SDLMouse/SDL_SYSTEM_CURSOR_W_RESIZE
   :ne-resize    SDLMouse/SDL_SYSTEM_CURSOR_NE_RESIZE
   :nw-resize    SDLMouse/SDL_SYSTEM_CURSOR_NW_RESIZE
   :se-resize    SDLMouse/SDL_SYSTEM_CURSOR_SE_RESIZE
   :sw-resize    SDLMouse/SDL_SYSTEM_CURSOR_SW_RESIZE})

;; ============================================================
;; Cursor cache (avoids re-creating SDL cursors)
;; ============================================================

(defonce ^:private cursor-cache (atom {}))

(defn- get-or-create-cursor
  "Return cached SDL cursor handle for the given keyword, creating if needed."
  [cursor-kw]
  (or (get @cursor-cache cursor-kw)
      (let [sdl-id (get system-cursor-ids cursor-kw)]
        (when sdl-id
          (let [handle (sdl/create-system-cursor sdl-id)]
            (when (and handle (not (zero? handle)))
              (swap! cursor-cache assoc cursor-kw handle)
              handle))))))

;; ============================================================
;; Public API
;; ============================================================

(defn set-cursor!
  "Set the mouse cursor to a system cursor by keyword.

   Supported cursors:
     :default / :arrow   — default arrow
     :pointer / :hand    — pointing hand (links, buttons)
     :text / :ibeam      — I-beam (text fields)
     :crosshair          — precise selection
     :move               — four-way move
     :wait               — busy/hourglass
     :progress           — busy + arrow
     :not-allowed        — forbidden

   Resize cursors:
     :ew-resize          — horizontal resize  ↔
     :ns-resize          — vertical resize    ↕
     :nwse-resize        — diagonal resize    ╲
     :nesw-resize        — diagonal resize    ╱
     :n-resize :e-resize :s-resize :w-resize
     :ne-resize :nw-resize :se-resize :sw-resize"
  [cursor-kw]
  (if-let [handle (get-or-create-cursor cursor-kw)]
    (sdl/set-cursor! handle)
    (throw (ex-info (str "Unknown cursor: " cursor-kw
                         ". Available: " (vec (keys system-cursor-ids)))
                    {:cursor cursor-kw}))))

(defn hide!
  "Hide the mouse cursor."
  []
  (sdl/hide-cursor!))

(defn show!
  "Show the mouse cursor (if previously hidden)."
  []
  (sdl/show-cursor!))

(defn visible?
  "Returns true if the mouse cursor is currently visible."
  []
  (sdl/cursor-visible?))

(defn reset-cursor!
  "Reset the cursor to the system default arrow."
  []
  (set-cursor! :default))

(defn cleanup!
  "Destroy all cached cursors. Call on shutdown."
  []
  (doseq [[_ handle] @cursor-cache]
    (when (and handle (not (zero? handle)))
      (sdl/destroy-cursor! handle)))
  (reset! cursor-cache {}))

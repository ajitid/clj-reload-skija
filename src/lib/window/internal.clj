(ns lib.window.internal
  "Private SDL3 interop layer via LWJGL 3.4.0.
   Handles SDL initialization, window/context creation, and event polling."
  (:require [lib.window.events :as e])
  (:import [org.lwjgl.sdl SDLInit SDLVideo SDLMouse SDLEvents SDLError SDLClipboard SDLKeyboard SDL_Event SDL_Rect
            SDL_MouseButtonEvent SDL_MouseMotionEvent SDL_MouseWheelEvent SDL_WindowEvent
            SDL_KeyboardEvent SDL_TouchFingerEvent SDL_TextInputEvent SDL_EventFilterI]
           [org.lwjgl.opengl GL]
           [org.lwjgl.system MemoryStack]))

;; SDL window flags (from SDLVideo)
(def ^:private WINDOW_OPENGL             SDLVideo/SDL_WINDOW_OPENGL)
(def ^:private WINDOW_RESIZABLE          SDLVideo/SDL_WINDOW_RESIZABLE)
(def ^:private WINDOW_HIGH_PIXEL_DENSITY SDLVideo/SDL_WINDOW_HIGH_PIXEL_DENSITY)
(def ^:private WINDOW_ALWAYS_ON_TOP      SDLVideo/SDL_WINDOW_ALWAYS_ON_TOP)

;; SDL event types (from SDLEvents)
(def ^:private EVENT_QUIT              SDLEvents/SDL_EVENT_QUIT)
(def ^:private EVENT_WINDOW_CLOSE      SDLEvents/SDL_EVENT_WINDOW_CLOSE_REQUESTED)
(def ^:private EVENT_WINDOW_RESIZED    SDLEvents/SDL_EVENT_WINDOW_RESIZED)
(def ^:private EVENT_WINDOW_EXPOSED    SDLEvents/SDL_EVENT_WINDOW_EXPOSED)
(def ^:private EVENT_WINDOW_PIXEL_SIZE_CHANGED SDLEvents/SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED)
(def ^:private EVENT_MOUSE_BUTTON_DOWN SDLEvents/SDL_EVENT_MOUSE_BUTTON_DOWN)
(def ^:private EVENT_MOUSE_BUTTON_UP   SDLEvents/SDL_EVENT_MOUSE_BUTTON_UP)
(def ^:private EVENT_MOUSE_MOTION      SDLEvents/SDL_EVENT_MOUSE_MOTION)
(def ^:private EVENT_MOUSE_WHEEL       SDLEvents/SDL_EVENT_MOUSE_WHEEL)
(def ^:private EVENT_KEY_DOWN          SDLEvents/SDL_EVENT_KEY_DOWN)
(def ^:private EVENT_KEY_UP            SDLEvents/SDL_EVENT_KEY_UP)
(def ^:private EVENT_FINGER_DOWN       SDLEvents/SDL_EVENT_FINGER_DOWN)
(def ^:private EVENT_FINGER_MOTION     SDLEvents/SDL_EVENT_FINGER_MOTION)
(def ^:private EVENT_FINGER_UP         SDLEvents/SDL_EVENT_FINGER_UP)
(def ^:private EVENT_TEXT_INPUT        SDLEvents/SDL_EVENT_TEXT_INPUT)

;; Mouse button mapping (SDL uses 1=left, 2=middle, 3=right)
(def ^:private BUTTON_LEFT   1)
(def ^:private BUTTON_MIDDLE 2)
(def ^:private BUTTON_RIGHT  3)

;; GL attribute constants (from SDLVideo)
(def ^:private GL_CONTEXT_MAJOR_VERSION SDLVideo/SDL_GL_CONTEXT_MAJOR_VERSION)
(def ^:private GL_CONTEXT_MINOR_VERSION SDLVideo/SDL_GL_CONTEXT_MINOR_VERSION)
(def ^:private GL_CONTEXT_PROFILE_MASK  SDLVideo/SDL_GL_CONTEXT_PROFILE_MASK)
(def ^:private GL_CONTEXT_PROFILE_CORE  SDLVideo/SDL_GL_CONTEXT_PROFILE_CORE)
(def ^:private GL_STENCIL_SIZE          SDLVideo/SDL_GL_STENCIL_SIZE)
(def ^:private GL_DOUBLEBUFFER          SDLVideo/SDL_GL_DOUBLEBUFFER)

(defn init-sdl!
  "Initialize SDL with video subsystem."
  []
  (when-not (SDLInit/SDL_Init SDLInit/SDL_INIT_VIDEO)
    (throw (ex-info "Failed to initialize SDL" {:error (SDLError/SDL_GetError)}))))

(defn set-gl-attributes!
  "Set OpenGL context attributes before window creation."
  []
  (SDLVideo/SDL_GL_SetAttribute GL_CONTEXT_MAJOR_VERSION 3)
  (SDLVideo/SDL_GL_SetAttribute GL_CONTEXT_MINOR_VERSION 3)
  (SDLVideo/SDL_GL_SetAttribute GL_CONTEXT_PROFILE_MASK GL_CONTEXT_PROFILE_CORE)
  (SDLVideo/SDL_GL_SetAttribute GL_STENCIL_SIZE 8)
  (SDLVideo/SDL_GL_SetAttribute GL_DOUBLEBUFFER 1))

(defn get-displays
  "Get all available display IDs.
   Returns a vector of display IDs (integers)."
  []
  (when-let [displays-buf (SDLVideo/SDL_GetDisplays)]
    (let [count (.remaining displays-buf)]
      (vec (for [i (range count)]
             (.get displays-buf i))))))

(defn get-primary-display
  "Get the primary display ID."
  []
  (SDLVideo/SDL_GetPrimaryDisplay))

(defn get-display-bounds
  "Get the bounds of a display.
   Returns {:x :y :w :h} or nil if failed."
  [display-id]
  (with-open [stack (MemoryStack/stackPush)]
    (let [rect (SDL_Rect/malloc stack)]
      (when (SDLVideo/SDL_GetDisplayBounds display-id rect)
        {:x (.x rect) :y (.y rect) :w (.w rect) :h (.h rect)}))))

(defn get-display-name
  "Get the name of a display."
  [display-id]
  (SDLVideo/SDL_GetDisplayName display-id))

(defn get-window-position
  "Get the window's current position in global coordinates.
   Returns [x y]."
  [window]
  (with-open [stack (MemoryStack/stackPush)]
    (let [x (.mallocInt stack 1)
          y (.mallocInt stack 1)]
      (SDLVideo/SDL_GetWindowPosition window x y)
      [(.get x 0) (.get y 0)])))

(defn get-global-mouse-position
  "Get the mouse position in global screen coordinates.
   Returns [x y]."
  []
  (with-open [stack (MemoryStack/stackPush)]
    (let [x (.mallocFloat stack 1)
          y (.mallocFloat stack 1)]
      (SDLMouse/SDL_GetGlobalMouseState x y)
      [(int (.get x 0)) (int (.get y 0))])))

(defn create-window!
  "Create an SDL window with the given options.
   Options:
     :title        - Window title (default \"Window\")
     :width        - Window width (default 800)
     :height       - Window height (default 600)
     :x            - Window x position (default: centered)
     :y            - Window y position (default: centered)
     :resizable?   - Allow resizing (default true)
     :high-dpi?    - Enable high DPI (default true)
     :always-on-top? - Keep window above others (default false)
     :display      - Display index (0-based) or display ID to open on (default: primary)
   Position precedence: explicit :x/:y > :display (centered) > default (primary, centered)
   Returns the window handle (long pointer)."
  [{:keys [title width height x y resizable? high-dpi? always-on-top? display]
    :or {title "Window" width 800 height 600 resizable? true high-dpi? true always-on-top? false}}]
  (let [flags (cond-> WINDOW_OPENGL
                resizable?     (bit-or WINDOW_RESIZABLE)
                high-dpi?      (bit-or WINDOW_HIGH_PIXEL_DENSITY)
                always-on-top? (bit-or WINDOW_ALWAYS_ON_TOP))
        window (SDLVideo/SDL_CreateWindow title width height flags)]
    (when (zero? window)
      (throw (ex-info "Failed to create window" {})))
    ;; Position window: explicit x/y takes precedence, then display, then default
    (cond
      ;; Explicit position
      (and x y)
      (SDLVideo/SDL_SetWindowPosition window x y)

      ;; Position on specific display (centered)
      display
      (let [displays (get-displays)
            display-id (if (and (integer? display) (< display (count displays)))
                         (nth displays display)
                         display)]
        (when-let [bounds (get-display-bounds display-id)]
          (let [cx (+ (:x bounds) (quot (- (:w bounds) width) 2))
                cy (+ (:y bounds) (quot (- (:h bounds) height) 2))]
            (SDLVideo/SDL_SetWindowPosition window cx cy)))))
    ;; Raise window to front and give it focus
    (SDLVideo/SDL_RaiseWindow window)
    window))

(defn create-gl-context!
  "Create OpenGL context for the window and make it current.
   Returns the GL context handle (long pointer)."
  [window]
  (let [ctx (SDLVideo/SDL_GL_CreateContext window)]
    (when (zero? ctx)
      (throw (ex-info "Failed to create GL context" {})))
    (SDLVideo/SDL_GL_MakeCurrent window ctx)
    ;; Initialize LWJGL's GL bindings
    (GL/createCapabilities)
    ;; Enable vsync (1 = on, 0 = off, -1 = adaptive)
    (SDLVideo/SDL_GL_SetSwapInterval 1)
    ctx))

(defn get-window-scale
  "Get the display scale factor for the window."
  [window]
  (SDLVideo/SDL_GetWindowDisplayScale window))

(defn get-window-size
  "Get the window size in logical pixels.
   Returns [width height]."
  [window]
  (with-open [stack (MemoryStack/stackPush)]
    (let [w (.mallocInt stack 1)
          h (.mallocInt stack 1)]
      (SDLVideo/SDL_GetWindowSize window w h)
      [(.get w 0) (.get h 0)])))

(defn get-window-size-in-pixels
  "Get the window size in physical pixels.
   Returns [width height]."
  [window]
  (with-open [stack (MemoryStack/stackPush)]
    (let [w (.mallocInt stack 1)
          h (.mallocInt stack 1)]
      (SDLVideo/SDL_GetWindowSizeInPixels window w h)
      [(.get w 0) (.get h 0)])))

(defn- button-keyword
  "Convert SDL mouse button to keyword."
  [button]
  (condp = (int button)
    BUTTON_LEFT   :primary
    BUTTON_MIDDLE :middle
    BUTTON_RIGHT  :secondary
    :unknown))

(defn- convert-mouse-button-event
  "Convert SDL mouse button event to EventMouseButton."
  [^SDL_Event event pressed?]
  (let [mb (SDL_MouseButtonEvent/create (.address (.button event)))]
    (e/->EventMouseButton
      (button-keyword (.button mb))
      (.x mb)
      (.y mb)
      pressed?
      (.clicks mb))))

(defn- convert-mouse-motion-event
  "Convert SDL mouse motion event to EventMouseMove."
  [^SDL_Event event]
  (let [mm (SDL_MouseMotionEvent/create (.address (.motion event)))]
    (e/->EventMouseMove (.x mm) (.y mm))))

(defn- convert-mouse-wheel-event
  "Convert SDL mouse wheel event to EventMouseWheel.
   x, y are mouse position; dx, dy are scroll delta; modifiers from keyboard state."
  [^SDL_Event event]
  (let [mw (SDL_MouseWheelEvent/create (.address (.wheel event)))
        modifiers (SDLKeyboard/SDL_GetModState)]
    (e/->EventMouseWheel (.mouse_x mw) (.mouse_y mw) (.x mw) (.y mw) modifiers)))

(defn- convert-finger-event
  "Convert SDL finger event to touch event.
   Note: SDL finger coords are normalized 0..1, we convert to logical pixels."
  [^SDL_Event event event-type window-width window-height]
  (let [tf (SDL_TouchFingerEvent/create (.address (.tfinger event)))
        x (* (.x tf) window-width)
        y (* (.y tf) window-height)
        id (.fingerID tf)
        pressure (.pressure tf)]
    (condp = event-type
      EVENT_FINGER_DOWN   (e/->EventFingerDown id x y pressure)
      EVENT_FINGER_MOTION (e/->EventFingerMove id x y pressure)
      EVENT_FINGER_UP     (e/->EventFingerUp id x y))))

(defn- convert-key-event
  "Convert SDL key event to EventKey."
  [^SDL_Event event pressed?]
  (let [kb (SDL_KeyboardEvent/create (.address (.key event)))
        key (.key kb)
        mod (.mod kb)]
    (e/->EventKey key pressed? mod)))

(defn- convert-text-input-event
  "Convert SDL text input event to EventTextInput."
  [^SDL_Event event]
  (let [ti (SDL_TextInputEvent/create (.address (.text event)))]
    (e/->EventTextInput (.textString ti))))

(defn- convert-resize-event
  "Convert SDL resize event to EventResize."
  [^SDL_Event event window]
  (let [we (SDL_WindowEvent/create (.address (.window event)))
        scale (get-window-scale window)]
    (e/->EventResize (.data1 we) (.data2 we) scale)))

(defn poll-events!
  "Poll all pending SDL events.
   Returns a vector of event records."
  [^SDL_Event event window window-width window-height]
  (let [events (transient [])]
    (while (SDLEvents/SDL_PollEvent event)
      (let [event-type (.type event)]
        (cond
          (or (= event-type EVENT_QUIT)
              (= event-type EVENT_WINDOW_CLOSE))
          (conj! events (e/->EventClose))

          (= event-type EVENT_WINDOW_RESIZED)
          (conj! events (convert-resize-event event window))

          ;; PIXEL_SIZE_CHANGED reports physical pixels, not logical - don't use for EventResize
          ;; The event watcher handles live resize rendering directly
          (= event-type EVENT_WINDOW_PIXEL_SIZE_CHANGED)
          nil

          (= event-type EVENT_WINDOW_EXPOSED)
          (conj! events (e/->EventExposed))

          (= event-type EVENT_MOUSE_BUTTON_DOWN)
          (conj! events (convert-mouse-button-event event true))

          (= event-type EVENT_MOUSE_BUTTON_UP)
          (conj! events (convert-mouse-button-event event false))

          (= event-type EVENT_MOUSE_MOTION)
          (conj! events (convert-mouse-motion-event event))

          (= event-type EVENT_MOUSE_WHEEL)
          (conj! events (convert-mouse-wheel-event event))

          (= event-type EVENT_KEY_DOWN)
          (conj! events (convert-key-event event true))

          (= event-type EVENT_KEY_UP)
          (conj! events (convert-key-event event false))

          (= event-type EVENT_TEXT_INPUT)
          (conj! events (convert-text-input-event event))

          (= event-type EVENT_FINGER_DOWN)
          (conj! events (convert-finger-event event event-type window-width window-height))

          (= event-type EVENT_FINGER_MOTION)
          (conj! events (convert-finger-event event event-type window-width window-height))

          (= event-type EVENT_FINGER_UP)
          (conj! events (convert-finger-event event event-type window-width window-height)))))
    (persistent! events)))

(defn swap-buffers!
  "Swap the window's OpenGL buffers."
  [window]
  (SDLVideo/SDL_GL_SwapWindow window))

(defn set-window-title!
  "Set the window title."
  [window title]
  (SDLVideo/SDL_SetWindowTitle window title))

;; Atom to hold render callback for live resize
(defonce ^:private resize-render-fn (atom nil))

(defn- create-event-watcher
  "Create an SDL_EventFilterI that triggers rendering on resize/expose events."
  []
  (reify SDL_EventFilterI
    (invoke [_ userdata event-ptr]
      (let [event (SDL_Event/create event-ptr)
            event-type (.type event)]
        (when (or (= event-type EVENT_WINDOW_EXPOSED)
                  (= event-type EVENT_WINDOW_PIXEL_SIZE_CHANGED)
                  (= event-type EVENT_WINDOW_RESIZED))
          (when-let [render-fn @resize-render-fn]
            (try
              (render-fn)
              (catch Exception e
                (println "Resize render error:" (.getMessage e)))))))
      ;; Return true to keep event in queue
      true)))

(defn set-resize-render-fn!
  "Set the function to call during live resize.
   This function will be called from the OS thread."
  [f]
  (reset! resize-render-fn f))

(defn add-event-watcher!
  "Add an event watcher for live resize support.
   Returns the watcher (keep reference to prevent GC)."
  []
  (let [watcher (create-event-watcher)]
    (SDLEvents/SDL_AddEventWatch watcher 0)
    watcher))

(defn remove-event-watcher!
  "Remove the event watcher."
  [watcher]
  (when watcher
    (SDLEvents/SDL_RemoveEventWatch watcher 0)))

;; ============================================================
;; Text Input
;; ============================================================

(defn start-text-input!
  "Start text input for the given window.
   SDL3 only generates SDL_EVENT_TEXT_INPUT events when text input is active."
  [window]
  (SDLKeyboard/SDL_StartTextInput window))

(defn stop-text-input!
  "Stop text input for the given window."
  [window]
  (SDLKeyboard/SDL_StopTextInput window))

;; ============================================================
;; Clipboard
;; ============================================================

(defn set-clipboard-text!
  "Set UTF-8 text to system clipboard.
   Returns true on success, false on failure."
  [^String text]
  (SDLClipboard/SDL_SetClipboardText text))

(defn get-clipboard-text
  "Get UTF-8 text from system clipboard.
   Returns string or nil if no text available."
  []
  (SDLClipboard/SDL_GetClipboardText))

(defn has-clipboard-text?
  "Check if clipboard contains text (non-empty).
   Returns boolean."
  []
  (SDLClipboard/SDL_HasClipboardText))

;; ============================================================
;; Window property setters
;; ============================================================

(defn set-window-size!
  "Set the window size in logical pixels."
  [window width height]
  (SDLVideo/SDL_SetWindowSize window width height))

(defn set-window-position!
  "Set the window position in screen coordinates."
  [window x y]
  (SDLVideo/SDL_SetWindowPosition window x y))

(defn set-window-resizable!
  "Enable or disable window resizing."
  [window resizable?]
  (SDLVideo/SDL_SetWindowResizable window (boolean resizable?)))

(defn set-window-bordered!
  "Enable or disable window border/decorations."
  [window bordered?]
  (SDLVideo/SDL_SetWindowBordered window (boolean bordered?)))

(defn set-window-always-on-top!
  "Enable or disable always-on-top."
  [window always-on-top?]
  (SDLVideo/SDL_SetWindowAlwaysOnTop window (boolean always-on-top?)))

(defn set-window-fullscreen!
  "Enable or disable fullscreen mode."
  [window fullscreen?]
  (SDLVideo/SDL_SetWindowFullscreen window (boolean fullscreen?)))

(defn set-window-minimum-size!
  "Set the minimum window size. Pass 0,0 to remove constraint."
  [window width height]
  (SDLVideo/SDL_SetWindowMinimumSize window width height))

(defn set-window-maximum-size!
  "Set the maximum window size. Pass 0,0 to remove constraint."
  [window width height]
  (SDLVideo/SDL_SetWindowMaximumSize window width height))

(defn set-window-opacity!
  "Set window opacity (0.0 = transparent, 1.0 = opaque)."
  [window opacity]
  (SDLVideo/SDL_SetWindowOpacity window (float opacity)))

(defn cleanup!
  "Clean up SDL resources.
   On macOS, we must pump events after destroying the window to let Cocoa
   complete the close operation before calling SDL_Quit."
  [gl-context window]
  (reset! resize-render-fn nil)
  (when (and gl-context (not (zero? gl-context)))
    (SDLVideo/SDL_GL_DestroyContext gl-context))
  (when (and window (not (zero? window)))
    (SDLVideo/SDL_DestroyWindow window))
  ;; Pump remaining events to let macOS process window destruction
  (with-open [stack (MemoryStack/stackPush)]
    (let [event (SDL_Event/malloc stack)]
      (dotimes [_ 10]
        (SDLEvents/SDL_PollEvent event))))
  (SDLInit/SDL_Quit))

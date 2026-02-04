# Multi-Window Rendering Optimization Plan

## Changes Overview

Three improvements to reduce panel window's impact on main window frame budget:

1. **Time-based 30 FPS cap for panel** (monitor-rate independent)
2. **Disable VSync on panel window** (OpenGL: swap interval 0, Metal: displaySyncEnabled NO)
3. **Adaptive VSync for main window** (OpenGL: try -1, fall back to 1)

---

## File-by-File Changes

### 1. `src/lib/window/metal.clj` — Add displaySyncEnabled setter

After `set-layer-framebuffer-only!` (~line 151), add:

```clojure
(defn set-layer-display-sync-enabled!
  "Set whether the layer synchronizes presentation with the display refresh.
   true = vsync on, false = vsync off."
  [layer enabled?]
  (when (and layer (pos? layer))
    (let [sel (get-selector "setDisplaySyncEnabled:")
          msg-send (objc-msg-send)]
      (JNI/invokePPPV layer sel (if enabled? 1 0) msg-send))))
```

Same pattern as existing `set-layer-framebuffer-only!`.

### 2. `src/lib/window/internal.clj` — Remove hardcoded VSync, add setter

**Line 179-180**: Remove `(SDLVideo/SDL_GL_SetSwapInterval 1)` from `create-gl-context!`.

**After `swap-buffers!` (~line 334)**, add:

```clojure
(defn set-swap-interval!
  "Set OpenGL swap interval. 0=off, 1=vsync, -1=adaptive.
   Returns true if supported."
  [interval]
  (SDLVideo/SDL_GL_SetSwapInterval interval))
```

### 3. `src/lib/window/layer_metal.clj` — Wire displaySyncEnabled into init!

**Line 25**: Add `vsync?` keyword arg:

```clojure
(defn init! [window-handle & {:keys [vsync?] :or {vsync? true}}]
```

**After line 50** (`set-layer-framebuffer-only!`), add:

```clojure
(metal/set-layer-display-sync-enabled! metal-layer vsync?)
```

### 4. `src/lib/window/core.clj` — Per-window VSync support

**Window record (line 22-24)**: Add `vsync` field:

```clojure
(defrecord Window [^long handle ^long gl-context window-id event-handler
                   frame-requested? running?
                   width height scale backend
                   vsync])
```

Only `map->Window` is used (confirmed), so adding a field is safe.

**`create-window` (line 53)**: Accept `:vsync` option:

```clojure
{:keys [title width height x y resizable? high-dpi? always-on-top? display backend
        shared-gl-context vsync]
 :or {title "Window" width 800 height 600 resizable? true high-dpi? true backend :auto}}
```

**Metal branch (lines 66-86)**: Pass vsync to Metal init and store in record:

```clojure
(when-not (layer-metal/init! handle :vsync? (not= vsync 0))
  (throw ...))
;; In map->Window:
:vsync (atom (or vsync 1))
```

**OpenGL branch (lines 89-111)**: After context creation, for primary (non-shared) context, probe adaptive VSync:

```clojure
(let [;; For primary window, try adaptive vsync (-1), fall back to standard (1)
      ;; For secondary windows, default to 0 (no vsync)
      desired (or vsync (if shared-gl-context 0 -1))
      effective (if (and (= desired -1) (not (sdl/set-swap-interval! -1)))
                  1
                  desired)]
  (map->Window
    {...
     :vsync (atom effective)}))
```

**`render-frame-opengl!` (lines 139-162)**: Set swap interval before swapping:

```clojure
;; Before (sdl/swap-buffers! handle), add:
(sdl/set-swap-interval! @(:vsync window))
```

### 5. `src/app/core.clj` — Time-based 30 FPS throttle + panel VSync=0

**Near line 37** (after `panel-height` defonce), add:

```clojure
(defonce panel-last-render-time (atom 0))
```

**Lines 291-294**: Replace unconditional panel frame request with time-based throttle:

```clojure
;; Request panel frame at ~30 FPS (time-based, monitor-rate independent)
(when (and @shell-state/panel-visible?
           (some? @sys/panel-window))
  (let [now-ms (/ (System/nanoTime) 1e6)
        elapsed (- now-ms @panel-last-render-time)]
    (when (>= elapsed 33.0)
      (reset! panel-last-render-time now-ms)
      (window/request-frame! @sys/panel-window))))
```

**Lines 550-560**: Add `:vsync 0` to panel window creation:

```clojure
(let [panel-opts (cond-> {:title "Controls"
                          :width 260
                          :height 500
                          :x panel-x
                          :y main-y
                          :resizable? true
                          :high-dpi? true
                          :always-on-top? true
                          :vsync 0}
                   (= :opengl (window/get-backend win))
                   (assoc :shared-gl-context (:gl-context win)))
```

---

## Implementation Order

1. `metal.clj` — add `set-layer-display-sync-enabled!` (pure addition)
2. `internal.clj` — add `set-swap-interval!`, remove hardcoded VSync
3. `layer_metal.clj` — add `vsync?` param to `init!`
4. `core.clj` (lib/window) — add `vsync` to Window record, wire into `create-window` and `render-frame-opengl!`
5. `core.clj` (app) — time-based throttle + `:vsync 0` for panel

Steps 1-3 are independent. Step 4 depends on 2-3. Step 5 depends on 4.

---

## Verification

1. Start app with `clj -A:dev:macos-arm64` (Metal backend)
2. Open an example: `(open :playground/ball-spring)`
3. Toggle control panel with Ctrl+` — verify it opens
4. Check FPS with panel open vs closed — should see minimal FPS difference now
5. Verify panel renders smoothly at ~30 FPS (responsive but not eating main budget)
6. After validating, run `bb scripts/check.clj` on all modified files

For OpenGL testing (Windows/Linux): verify adaptive VSync probe doesn't crash, panel swaps without blocking.

---

## Risk Notes

- **`SDL_GL_SetSwapInterval` per-swap cost**: Negligible — single driver flag set. Can cache if needed.
- **Shared GL context**: If swap interval turns out to be per-context not per-window on some driver, the time-based throttle still correctly limits panel to 30 FPS. Main window might occasionally miss VSync after panel renders, but only ~30 times/sec.
- **`setDisplaySyncEnabled:` availability**: Requires macOS 10.13+ (already required by Metal).

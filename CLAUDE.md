# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Development Commands

```bash
# Download dependencies
clj -P -A:dev

# Start REPL with platform-specific alias
clj -A:dev:macos-arm64   # macOS Apple Silicon
clj -A:dev:macos-x64     # macOS Intel
clj -A:dev:windows       # Windows
clj -A:dev:linux         # Linux

# Run directly (one-liner, no REPL)
clj -M:dev:macos-arm64 -e "(open)"

# Connect to running nREPL from another terminal (for hot-reload)
clj -M:connect
```

**In the REPL:**
```clojure
(open)    ;; Open the window (blocks this REPL)
(reload)  ;; Hot-reload changed namespaces (run from connected REPL)
(close)   ;; Close window and reset state
(reopen)  ;; Close + open new window
```

## Architecture

Clojure demo showcasing hot-reloading with LWJGL/SDL3 (windowing) and Skija (2D graphics), plus animation and gesture libraries.

### Design Pattern: clj-reload + Flex Reactivity

Following [clj-reload](https://github.com/tonsky/clj-reload) best practices with Vue 3-style reactive signals via [Flex](https://github.com/lilactown/flex):
- **Everything reloads** except `user` namespace and `defonce` values
- Use `(requiring-resolve 'ns/sym)` for cross-namespace function calls (vars are removed on unload)
- Use `defonce` for state that must survive reloads
- UI state uses Flex sources/signals for automatic reactivity
- Animation targets stay as plain atoms (no reactive overhead at 60fps)

### Reloadable vs Persistent

| Namespace | Declaration | Reload Behavior |
|-----------|-------------|-----------------|
| `app.state.sources` | `flex/defsource` | Flex sources **persist** (window size, UI state) |
| `app.state.signals` | `flex/signal` | Derived values **recompute** automatically |
| `app.state.animations` | `defonce` atom | Animation targets **persist** |
| `app.state.system` | `defonce` atom | System state **persist** (window, errors) |
| `app.config` | `def` | Values **update** on reload |
| `app.core` | `defn` | Functions **update** on reload |
| `app.controls` | `defn` | Functions **update** on reload |
| `app.gestures` | `defn` | Functions **update** on reload |
| All `lib.*` | `defn` | Functions **update** on reload |

### State Architecture (Flex)

State is split into four namespaces:

```clojure
;; app.state.sources - Flex sources (reactive UI state)
(flex/defsource window-width 800)
(flex/defsource circles-x 2)
@src/window-width        ;; Read value
(src/window-width 1024)  ;; Update (call as function) triggers dependent signals

;; app.state.signals - Derived signals (auto-recompute)
(def grid-positions
  (flex/signal
    (let [nx @src/circles-x ny @src/circles-y ...]
      (compute-grid ...))))
@sig/grid-positions         ;; Auto-recomputes when deps change

;; app.state.animations - Plain atoms (60fps, no reactive overhead)
(defonce demo-circle-x (atom 400.0))

;; app.state.system - System lifecycle atoms
(defonce window (atom nil))
(defonce game-time (atom 0.0))
```

**Key Benefit:** Grid positions auto-recompute when window resizes or slider changes - no manual `recalculate-grid!` calls needed.

### Love2D-Style Game Loop

The app uses three hot-reloadable callbacks in `app.core`:

```clojure
(defn init []  ...)       ;; Called once at startup, configures time sources
(defn tick [dt] ...)      ;; Called every frame, advances game-time, updates springs
(defn draw [canvas w h] ...) ;; Called every frame for rendering
```

### Library Systems (lib/)

**lib.flex/** - Hot-reload compatible Flex integration:
- `source`, `signal`, `listen` - Re-exports from town.lilac/flex
- `defsource` macro - defonce + source for persistent reactive values
- `track-effect!`, `dispose-all-effects!` - Effect lifecycle for hot-reload

**lib.time** - Configurable time source for all animation libs. Configure in `init` via `set-time-source!` to use game-time for slow-mo/pause.

**lib.anim/** - Animation primitives:
- `spring` - Apple-style spring physics (closed-form CASpringAnimation)
- `decay` - iOS-style momentum/friction (scroll flick)
- `tween` - Keyframe animations with easing
- `timer` - Simple countdown timers
- `timeline` - Sequencing multiple animations
- `stagger` - Staggered animations across items
- `rubber-band` - iOS-style overscroll resistance

```clojure
;; Spring example
(spring {:from 0 :to 100})              ;; Create spring
(spring-now s)                           ;; Get {:value :velocity :at-rest?}
(spring-update s {:to 200})              ;; Retarget mid-animation

;; Decay example (momentum scrolling)
(decay {:from 100 :velocity 500})        ;; Start with velocity
(decay-now d)                            ;; Get {:value :velocity :at-rest?}
```

**lib.gesture/** - Touch/mouse gesture recognition:
- `api` - Public API: `register-target!`, `handle-mouse-*`, `handle-finger-*`
- `arena` - Pure gesture resolution logic (hit testing, recognizer state)
- `recognizers` - Drag, tap, long-press recognizers

```clojure
(register-target! {:id :my-button
                   :bounds-fn (fn [ctx] [x y w h])
                   :gesture-recognizers [:tap :drag]
                   :handlers {:on-tap handle-tap
                              :on-drag-start handle-drag-start}})
```

**lib.layout/** - Subform-style constraint layout:
- Sizes: fixed (`100`), percentage (`"50%"`), stretch (`"1s"`, `"2s"`)
- Modes: `:stack-x`, `:stack-y`, `:grid`
- Spacing: `:before`, `:between`, `:after`
- Mixins with Flex effect lifecycle (auto-dispose on unmount/reload)

**lib.window/** - SDL3/OpenGL windowing layer:
- `core` - `create-window`, `run!`, `request-frame!`, `close!`
- `events` - Event records (EventFrameSkija, EventMouseButton, etc.)
- `layer` - Skija surface management

### Game-Time System

For animation synchronization and slow-mo/pause support:
- `app.state.system/game-time` - Accumulated time in seconds (advanced in tick)
- `app.state.system/time-scale` - Speed multiplier (1.0 = normal, 0.5 = slow-mo, 0 = paused)
- `lib.time/time-source` - Atom containing time function, configured in `init`

### Dynamic Dispatch Pattern

The event listener uses `requiring-resolve` for ALL callbacks so they survive namespace reload:

```clojure
;; In event listener (created once, persists):
(when-let [draw-fn (requiring-resolve 'app.core/draw)]
  (draw-fn canvas w h))

;; For config values, use the cfg helper:
(cfg 'app.config/circle-color)  ;; wraps @(requiring-resolve ...)
```

**Why `resolve` not `#'var`?**
- `#'var` captures the var object at compile time
- clj-reload unloads/reloads namespaces, creating NEW var objects
- `resolve` looks up by symbol at runtime, always finding current var

### Namespace Responsibilities

- **app.core** - Game loop callbacks (init/tick/draw), event handler with dynamic dispatch
- **app.state.sources** - Flex sources for reactive UI state
- **app.state.signals** - Derived signals that auto-recompute
- **app.state.animations** - Plain atoms for animation targets (60fps)
- **app.state.system** - System/lifecycle atoms (window, errors)
- **app.config** - Plain `def` values for visual parameters
- **app.controls** - UI drawing and slider/mouse handling
- **app.gestures** - Gesture target registration and handlers
- **lib.flex.core** - Flex integration with hot-reload effect tracking
- **lib.window.*** - SDL3/OpenGL windowing and Skija surface management
- **lib.anim.*** - Animation primitives (spring, decay, tween, timer, etc.)
- **lib.gesture.*** - Gesture recognition system
- **lib.layout.*** - Constraint-based layout engine with Flex effect lifecycle
- **lib.time** - Configurable time source for animations
- **user** (dev/) - REPL namespace with `open` and `reload` functions

### Dependencies

- **LWJGL 3.4.0** - Cross-platform windowing via SDL3 + OpenGL bindings
- **Skija** (platform-specific) - Skia bindings for 2D graphics
- **clj-reload** - Hot-reloading without losing state
- **town.lilac/flex** - Vue 3-style reactive signals

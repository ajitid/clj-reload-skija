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
clj -M:dev:macos-arm64 -e "(open :playground/ball-spring)"

# Connect to running nREPL from another terminal (for hot-reload)
clj -M:connect
```

**In the REPL:**
```clojure
(open :playground/ball-spring)   ;; Open an example (blocks REPL)
(reload)                         ;; Hot-reload changed namespaces (from connected REPL)
```

## Syntax Validation Tool

**IMPORTANT:** Always validate Clojure files after editing to catch parenthesis imbalances and syntax errors immediately.

### Unified Checker (cross-platform, runs with Babashka)
```bash
bb scripts/check.clj src/app/shell/control_panel.clj
bb scripts/check.clj file1.clj file2.clj   # multiple files
```

Checks parenthesis balance, syntax, and common lint issues in sequence. Stops at the first failure. Error messages explain what's wrong and how to fix it. Run with no args for details.

### Workflow for Editing Clojure Files
```bash
# 1. Check file before editing (establish baseline)
bb scripts/check.clj src/app/projects/playground/ball_spring.clj

# 2. Make your edits with Edit/Write tools

# 3. IMMEDIATELY validate after editing
bb scripts/check.clj src/app/projects/playground/ball_spring.clj

# 4. If validation fails, fix before proceeding
```

**Why this matters:** Parenthesis imbalances in Clojure files cause cryptic runtime errors. Semantic issues like `defonce` with docstrings compile but fail at runtime with confusing arity errors. Definition-order mistakes (calling a `defn` before it's defined) cause "Unable to resolve symbol" at compile time. The checker catches all three classes of issues at edit-time.

## Architecture

Multi-example prototyping system with hot-reloading, organized by projects.

### Project Structure

```
src/app/
├── shell/                        # Dev infrastructure (always present)
│   ├── core.clj                  # Shell lifecycle, wraps examples
│   ├── debug_panel.clj           # FPS graph, error display
│   └── state.clj                 # panel-visible?, fps, fps-history
│
├── ui/                           # Reusable widgets
│   └── slider.clj                # Generic slider widget
│
├── projects/                     # All examples organized by project
│   └── playground/               # Playground experiments
│       └── ball_spring.clj       # Default demo (drag ball, layout, grid)
│
├── state/
│   └── system.clj                # Window, running?, game-time (shared)
│
└── core.clj                      # Entry: (open :playground/ball-spring)
```

### Example Interface

Each example file provides these functions:

```clojure
(ns app.projects.playground.ball-spring
  (:require [lib.graphics.shapes :as shapes]
            [lib.anim.spring :as spring]))

;; Optional: example-specific state (defonce for persistence)
(defonce circle-x (atom 400.0))

(defn init []
  "Called once when example starts")

(defn tick [dt]
  "Called every frame with delta time")

(defn draw [canvas w h]
  "Called every frame for rendering")

;; Optional: cleanup when switching away
(defn cleanup [])
```

**Gesture window routing:** When registering gestures for control panel overlays (`:layer :overlay`), always include `:window (state/panel-gesture-window)` in the target map. This ensures gestures route correctly whether the panel is inline or in a separate window. Content-layer gestures (`:layer :content`) don't need this — they always target `:main`.

### Launch Mechanism

**From REPL:**
```clojure
(open :playground/ball-spring)    ;; Launch an example
```

**From CLI (pool.clj workflow):**
```bash
bb scripts/pool.clj open playground/ball-spring
```

**Key resolution:**
- `:playground/ball-spring` -> `'app.projects.playground.ball-spring`

### Design Pattern: clj-reload + Flex Reactivity

Following [clj-reload](https://github.com/tonsky/clj-reload) best practices with Vue 3-style reactive signals via [Flex](https://github.com/lilactown/flex):
- **app.* namespaces reload**; `user` namespace and `lib.*` namespaces do not hot-reload
- Use `(requiring-resolve 'ns/sym)` for cross-namespace function calls
- Use `defonce` for state that must survive reloads
- UI state uses Flex sources/signals for automatic reactivity

### Reloadable vs Persistent

| Namespace | Declaration | Reload Behavior |
|-----------|-------------|-----------------|
| `app.shell.*` | `flex/defsource`, `defonce` | Sources **persist**, functions **update** |
| `app.projects.*.*` | `flex/defsource`, `defonce` | Example state **persists** |
| `app.state.system` | `defonce` atom | System state **persists** |
| `app.ui.*` | `defn` | Functions **update** on reload |
| All `lib.*` | `defn` | **Restart required** (not hot-reloadable) |

### Shell Architecture

The shell wraps examples and provides:
- Example resolution and lifecycle (init/cleanup)
- Debug panel overlay (FPS graph, Ctrl+` to toggle)
- Game time management

```clojure
;; app.shell.core - delegates to active example
(defn init [] ...)    ;; Called once at startup
(defn tick [dt] ...)  ;; Delegates to example tick
(defn draw [canvas w h] ...)  ;; Example draw + debug panel overlay
```

### Library Systems (lib/)

**lib.flex/** - Hot-reload compatible Flex integration:
- `source`, `signal`, `listen` - Re-exports from town.lilac/flex
- `defsource` macro - defonce + source for persistent reactive values
- `track-effect!`, `dispose-all-effects!` - Effect lifecycle for hot-reload

**lib.time** - Configurable time source for all animation libs.

**lib.anim/** - Animation primitives:
- `spring` - Apple-style spring physics
- `decay` - iOS-style momentum/friction
- `tween` - Keyframe animations with easing
- `timer`, `timeline`, `stagger` - Sequencing

**lib.gesture/** - Touch/mouse gesture recognition:
- `api` - Public API: `register-target!`, `handle-mouse-*`, `handle-finger-*`
- `arena` - Pure gesture resolution logic
- `recognizers` - Drag, tap, long-press recognizers

**lib.layout/** - Subform-style constraint layout:
- Sizes: fixed (`100`), percentage (`"50%"`), stretch (`"1s"`, `"2s"`)
- Modes: `:stack-x`, `:stack-y`, `:grid`
- Virtual scrolling via mixins

**lib.window/** - SDL3/OpenGL windowing layer

### Dynamic Dispatch Pattern

The event listener uses `requiring-resolve` for ALL callbacks:

```clojure
;; In event listener (created once, persists):
(when-let [draw-fn (requiring-resolve 'app.shell.core/draw)]
  (draw-fn canvas w h))
```

### Hot-Reload Behavior

| Component | Reload Behavior |
|-----------|-----------------|
| `shell/*` | Reloads, persists state (defonce) |
| `ui/*` | Reloads |
| `projects/*/*` | Reloads, persists defonce state |
| Example switch | Calls cleanup(), clears gestures, calls new init() |

### Dependencies

- **LWJGL 3.4.0** - Cross-platform windowing via SDL3 + OpenGL bindings
- **Skija** (platform-specific) - Skia bindings for 2D graphics
- **clj-reload** - Hot-reloading without losing state
- **town.lilac/flex** - Vue 3-style reactive signals

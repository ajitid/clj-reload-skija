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

# Connect to running nREPL from another terminal (for hot-reload)
clj -M:connect

# Run application directly (without REPL)
clj -A:run
```

**In the REPL:**
```clojure
(start)   ;; Start the JWM window application (blocks this REPL)
(reload)  ;; Hot-reload changed namespaces (run from connected REPL)
```

## Architecture

This is a Clojure demo showcasing hot-reloading with JWM (windowing) and Skija (2D graphics).

### Design Pattern: clj-reload

Following [clj-reload](https://github.com/tonsky/clj-reload) best practices:
- **Everything reloads** except `user` namespace and `defonce` values
- Use `(resolve 'ns/sym)` for cross-namespace function calls (vars are removed on unload)
- Use `defonce` for state that must survive reloads
- Structure code by business logic, not reload constraints

### Reloadable vs Persistent

| Namespace | Declaration | Reload Behavior |
|-----------|-------------|-----------------|
| `app.state` | `defonce` | Values **persist** (window, atoms, running state) |
| `app.config` | `def` | Values **update** on reload |
| `app.controls` | `defn` | Functions **update** on reload |
| `app.core` | `defn` | Functions **update** on reload |

### Love2D-Style Game Loop

The app uses three hot-reloadable callbacks in `app.core`:

```clojure
(defn init []  ...)       ;; Called once at startup
(defn tick [dt] ...)      ;; Called every frame with delta time (seconds)
(defn draw [canvas w h] ...) ;; Called every frame for rendering
```

### Dynamic Dispatch Pattern

The event listener uses `resolve` for ALL callbacks so they survive namespace reload:

```clojure
;; In event listener (created once, persists):
(when-let [draw-fn (resolve 'app.core/draw)]
  (draw-fn canvas w h))

;; For config values, use the cfg helper:
(cfg 'app.config/circle-color)  ;; wraps @(resolve ...)
```

**Why `resolve` not `#'var`?**
- `#'var` captures the var object at compile time
- clj-reload unloads/reloads namespaces, creating NEW var objects
- `resolve` looks up by symbol at runtime, always finding current var

### Namespace Responsibilities

- **app.core** - Game loop callbacks (init/tick/draw), event listener with dynamic dispatch
- **app.state** - Atoms wrapped in `defonce` for persistent state
- **app.controls** - UI drawing and mouse handling (fully reloadable)
- **app.config** - Plain `def` values for visual parameters
- **user** (dev/) - REPL namespace with `start` and `reload` functions

### Dependencies

- **JWM** (io.github.humbleui/jwm) - Cross-platform windowing
- **Skija** (platform-specific) - Skia bindings for 2D graphics
- **clj-reload** - Hot-reloading without losing state

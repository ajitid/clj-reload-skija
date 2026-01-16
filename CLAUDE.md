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

### Key Design Pattern: Reloadable vs Persistent State

The project demonstrates clj-reload's `defonce` vs `def` distinction:

| Namespace | Declaration | Reload Behavior |
|-----------|-------------|-----------------|
| `app.state` | `defonce` | Values **persist** across reloads (window, sizes, running state) |
| `app.config` | `def` | Values **update** on reload (shadow/blur params, colors) |

### Love2D-Style Game Loop

The app uses three hot-reloadable callbacks in `app.core`:

```clojure
(defn init []  ...)       ;; Called once at startup
(defn tick [dt] ...)      ;; Called every frame with delta time (seconds)
(defn draw [canvas w h] ...) ;; Called every frame for rendering
```

Named `init`/`tick` instead of `load`/`update` to avoid shadowing `clojure.core` functions.

### Cross-Namespace Reference Pattern

When referencing values from reloadable namespaces, use `resolve` for runtime lookup:

```clojure
;; Bad - alias becomes stale after reload
config/shadow-dx

;; Good - runtime lookup survives reload (see cfg helper in app.core)
@(resolve 'app.config/shadow-dx)
```

### Namespace Responsibilities

- **app.core** - Main entry point, JWM window setup, Skija rendering loop. Contains `cfg` helper for safe config access.
- **app.state** - Atoms wrapped in `defonce` for persistent state (circle-radius, rect-width/height, window reference).
- **app.config** - Plain `def` values for effect parameters. Includes optional `before-ns-unload` and `after-ns-reload` hooks.
- **user** (dev/) - REPL namespace with `start` and `reload` functions. Starts nREPL on port 7888.

### Dependencies

- **JWM** (io.github.humbleui/jwm) - Cross-platform windowing
- **Skija** (platform-specific) - Skia bindings for 2D graphics
- **clj-reload** - Hot-reloading without losing state

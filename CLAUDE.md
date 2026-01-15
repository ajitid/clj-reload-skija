# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Development Commands

```bash
# Download dependencies
clj -P -A:dev

# Start REPL with dev alias (includes clj-reload and nREPL)
clj -A:dev

# Run application directly
clj -A:run
```

**In the REPL:**
```clojure
(start)   ;; Start the JWM window application
(reload)  ;; Hot-reload changed namespaces
```

## Architecture

This is a Clojure demo showcasing hot-reloading with JWM (windowing) and Skija (2D graphics).

### Key Design Pattern: Reloadable vs Persistent State

The project demonstrates clj-reload's `defonce` vs `def` distinction:

| Namespace | Declaration | Reload Behavior |
|-----------|-------------|-----------------|
| `app.state` | `defonce` | Values **persist** across reloads (window, sizes, running state) |
| `app.config` | `def` | Values **update** on reload (shadow/blur params, colors) |

### Namespace Responsibilities

- **app.core** - Main entry point, JWM window setup, Skija rendering loop. Reads from both state and config namespaces.
- **app.state** - Atoms wrapped in `defonce` for persistent state (circle-radius, rect-width/height, window reference).
- **app.config** - Plain `def` values for effect parameters (shadow offsets, blur sigmas, colors in ARGB format).
- **user** (dev/) - REPL namespace with `start` and `reload` functions. Auto-loaded on REPL start.

### Dependencies

- **JWM** (io.github.humbleui/jwm) - Cross-platform windowing
- **Skija** (io.github.humbleui/skija-windows-x64) - Skia bindings for 2D graphics
- **clj-reload** - Hot-reloading without losing state

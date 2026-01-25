# Clojure Hot-Reloadable Skija Demo

A Clojure demo showcasing hot-reloading with LWJGL/SDL3 (windowing) and [Skija](https://github.com/HumbleUI/Skija) (2D graphics). Organized as a multi-example prototyping system.

## Project Structure

```
clj-reloadable-skija-on-window/
├── deps.edn              # Dependencies (LWJGL, Skija, clj-reload)
├── src/
│   ├── app/
│   │   ├── core.clj          # Entry point, window infrastructure
│   │   ├── shell/            # Dev infrastructure (debug panel, FPS)
│   │   │   ├── core.clj      # Shell lifecycle, wraps examples
│   │   │   ├── debug_panel.clj
│   │   │   └── state.clj
│   │   ├── ui/               # Reusable widgets
│   │   │   └── slider.clj
│   │   ├── projects/         # Examples organized by project
│   │   │   └── playground/
│   │   │       └── ball_spring.clj  # Default demo
│   │   └── state/
│   │       └── system.clj    # System state (window, errors)
│   └── lib/                  # Library systems (animation, gesture, layout)
├── dev/
│   └── user.clj              # REPL namespace (excluded from reload)
└── scripts/
    └── pool.clj              # JVM pool manager
```

## Requirements

- Java 11+
- Clojure CLI

## Installation

### macOS (Homebrew)

```bash
brew install openjdk@11 clojure/tools/clojure
```

**Build native library (required for macOS):**

SDL3 requires window operations to run on macOS's main thread (thread 0). This project includes a native library to handle this. Build it once after cloning:

```bash
./scripts/build-native.sh
```

This compiles:
- `native/libmacos_main.dylib` - Native dispatch to main thread
- `classes/lib/window/macos/MainThread.class` - JNI wrapper

**Build requirement:** `clang` compiler (included in Xcode Command Line Tools: `xcode-select --install`)

### Windows

**Option 1: MSI Installer (Easiest)**

1. Install Java from [Adoptium](https://adoptium.net/) (click "Latest LTS Release")
2. Download Clojure MSI from [clj-msi releases](https://github.com/casselc/clj-msi/releases/latest)
3. Double-click the `.msi` file to install

**Option 2: Scoop**

```powershell
scoop bucket add java
scoop install temurin-lts-jdk
scoop install clojure
```

### Linux

```bash
sudo apt install -y openjdk-11-jdk rlwrap
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh
sudo ./linux-install.sh
```

## Running the Application

First, download dependencies:

```bash
clj -P -A:dev
```

Then start the REPL with your platform alias:

```bash
# macOS Apple Silicon (M1/M2/M3/M4)
clj -M:dev:macos-arm64

# macOS Intel
clj -M:dev:macos-x64

# Windows
clj -M:dev:windows

# Linux
clj -M:dev:linux
```

An nREPL server will automatically start (port shown in console).

### Specifying nREPL Port

By default, nREPL uses a random available port. To use a specific port (e.g., 7888):

```bash
# Option 1: Environment variable
NREPL_PORT=7888 clj -M:dev:macos-arm64

# Option 2: System property
clj -J-Dnrepl.port=7888 -M:dev:macos-arm64
```

The `:connect` alias defaults to port 7888, so using that port simplifies connecting.

In the REPL, open an example:

```clojure
(open :playground/ball-spring)
```

or directly open using:

```bash
clj -M:dev:macos-arm64 -e "(open :playground/ball-spring)"
```

The window opens and the REPL blocks (this is normal).

### Quick Open Mode

For demos or quick testing without keeping the REPL alive, use `quick-open` which exits when the window closes:

```bash
clj -M:dev:macos-arm64 -e "(quick-open :playground/ball-spring)"
```

### Example Key Resolution

Example keys are resolved to namespaces:
- `:playground/ball-spring` → `app.projects.playground.ball-spring`

## Hot Reloading

To hot-reload changes, connect to the running nREPL from another terminal:

```bash
# If you started with NREPL_PORT=7888:
clj -M:connect

# Or specify the port shown in the console:
clj -M:connect --port <port>
```

Now edit ANY source file in `src/app/`:

```clojure
;; Example: change colors in an example file
(def circle-color 0xFFFF6B6B)  ;; Red circles

;; Example: change draw function
(defn draw [canvas w h]
  (.clear canvas 0xFF000000)  ;; Black background
  ...)
```

Then in the connected REPL:

```clojure
(reload)
```

Changes apply immediately - no restart needed.

### Auto-Reload with watchexec

For automatic reload on file save, use [watchexec](https://github.com/watchexec/watchexec) with [rep](https://github.com/eraserhd/rep) (a simple nREPL client):

```bash
# Install (macOS)
brew install watchexec
brew install eraserhd/rep/rep # or directly grab the binary from https://github.com/eraserhd/rep
```

Then run in a separate terminal:

```bash
# If using fixed port (NREPL_PORT=7888):
watchexec -qnrc -e clj -w src -w dev -- rep -p 7888 "(reload)"

# If using JVM pool (macOS/Linux):
watchexec -qnrc -e clj -w src -w dev -- ./scripts/reload.sh

# If using JVM pool (Windows):
watchexec -qnrc -e clj -w src -w dev -- scripts\reload.cmd
```

The reload scripts read the port from `.jvm-pool/active-port` fresh on each reload. This means if you restart the app with `bb scripts/pool.clj open` (which assigns a new port), the next reload automatically uses the new port - no need to restart watchexec.

**Flags explained:**
- `-q` — quiet mode
- `-n` — no shell
- `-r` — restart on change
- `-c` — clear screen
- `-e clj` — watch only `.clj` files
- `-w src -w dev` — watch directories
- `-p <port>` — nREPL port

Now every time you save a `.clj` file, reload happens automatically.

### Error Recovery

If you introduce a syntax or runtime error, the window shows a **red error overlay** with the stack trace. The error message is also printed to the console.

To recover:

1. Fix the error in your code
2. Call `(reload)` in the connected REPL
3. Drawing resumes automatically

**Keyboard shortcuts:**
- `Ctrl+E` or Middle-click — Copy error to clipboard
- `Ctrl+\`` — Toggle debug panel (FPS graph)

## Using Calva (VS Code)

[Calva](https://calva.io/) provides a rich Clojure editing experience in VS Code.

### Option 1: Connect to Running REPL

1. Start the REPL from terminal: `NREPL_PORT=7888 clj -M:dev:macos-arm64` (or your platform)
2. Run `(open :playground/ball-spring)` to launch the window
3. In VS Code, run **Calva: Connect to a Running REPL Server in the Project** (`Ctrl+Alt+C Ctrl+Alt+C`)
4. Select "deps.edn" → "localhost:7888"

### Option 2: Jack-In (Calva Starts REPL)

1. Run **Calva: Start a Project REPL and Connect (aka Jack-In)** (`Ctrl+Alt+C Ctrl+Alt+J`)
2. Select "deps.edn"
3. Check the aliases: `:dev` and your platform (e.g., `:macos-arm64`)
4. Once connected, evaluate `(open :playground/ball-spring)` in the REPL

### Workflow

- Edit files in `src/app/projects/`
- Evaluate `(reload)` in the REPL (or `Ctrl+Alt+C Enter` to eval current form)
- See changes instantly in the window

## Game Loop (Love2D-style)

Each example provides three hot-reloadable callbacks:

```clojure
(defn init []
  "Called once when example starts. Initialize your state here.")

(defn tick [dt]
  "Called every frame with delta time (seconds). Update logic here.")

(defn draw [canvas width height]
  "Called every frame for rendering. Draw your example here.")

(defn cleanup []
  "Optional: Called when switching to another example.")
```

> Note: Named `init`/`tick` instead of `load`/`update` to avoid shadowing `clojure.core` functions.

All functions are hot-reloadable - edit them and call `(reload)` to see changes instantly.

## How It Works

| Namespace | Uses | Behavior on Reload |
|-----------|------|-------------------|
| `app.shell.*` | `defonce`, `defsource` | State **persists**, functions **reload** |
| `app.projects.*.*` | `defonce`, `defsource` | State **persists**, functions **reload** |
| `app.state.system` | `defonce` | **Persists** - window, errors |
| `app.ui.*` | `defn` | **Reloads** - functions update |
| `lib.*` | `defn` | **Restart required** (not hot-reloadable) |
| `user` | - | **Excluded** - keeps REPL stable |

Following [clj-reload](https://github.com/tonsky/clj-reload) best practices:

- `defonce` for persistent state
- `(requiring-resolve 'ns/sym)` for cross-namespace calls (vars are removed on unload)

### Why `requiring-resolve`?

When clj-reload unloads a namespace, vars are **completely removed** (not just re-bound). Direct references become stale:

```clojure
;; Bad - var object is removed on reload
config/shadow-dx
#'app.core/draw

;; Good - runtime lookup by symbol name
@(requiring-resolve 'app.config/shadow-dx)
(when-let [f (requiring-resolve 'app.shell.core/draw)] (f canvas w h))
```

The event listener uses `requiring-resolve` for ALL callbacks, allowing every namespace to reload.

## References

- [clj-reload](https://github.com/tonsky/clj-reload)
- [Skija](https://github.com/HumbleUI/Skija)
- [LWJGL](https://www.lwjgl.org/) (SDL3 bindings)
- [SDL3](https://www.libsdl.org/)

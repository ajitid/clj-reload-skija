# Clojure Hot-Reloadable Skija Demo

A Clojure demo showcasing hot-reloading with LWJGL/SDL3 (windowing) and [Skija](https://github.com/HumbleUI/Skija) (2D graphics).

## Project Structure

```
clj-reloadable-skija-on-window/
├── deps.edn              # Dependencies (JWM, Skija, clj-reload)
├── src/
│   └── app/
│       ├── state.clj     # Persistent state (defonce) - survives reload
│       ├── config.clj    # Reloadable config (def) - visual params
│       ├── controls.clj  # Reloadable UI (defn) - sliders, mouse
│       └── core.clj      # Reloadable app (defn) - game loop callbacks
├── dev/
│   └── user.clj          # REPL namespace (excluded from reload)
└── scripts/
    └── check_state.bb    # Babashka script to check state consistency
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

In the REPL, open the window:

```clojure
(open)
```

or directly open using:

```bash
clj -M:dev:macos-arm64 -e "(open)"
```

The window opens and the REPL blocks (this is normal).

### Quick Open Mode

For demos or quick testing without keeping the REPL alive, use `quick-open` which exits when the window closes:

```bash
clj -M:dev:macos-arm64 -e "(quick-open)"
```

### Other REPL Commands

```clojure
(close)   ;; Close window, reset state (can reopen)
(reopen)  ;; Close + open new window
```

## Hot Reloading

To hot-reload changes, connect to the running nREPL from another terminal:

```bash
# If you started with NREPL_PORT=7888:
clj -M:connect

# Or specify the port shown in the console:
clj -M:connect --port <port>
```

Now edit ANY source file - `config.clj`, `controls.clj`, or even `core.clj`:

```clojure
;; Example: change config values
(def circle-color 0xFFFF6B6B)  ;; Red circles

;; Example: change draw function in core.clj
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

If you introduce a syntax or runtime error (e.g., typo like `forma` instead of `format`), the window shows a **red screen** (`0xFFFF6B6B`) as immediate visual feedback. The error message is printed to the console.

To recover:

1. Fix the error in your code
2. Call `(reload)` in the connected REPL
3. Drawing resumes automatically

## Using Calva (VS Code)

[Calva](https://calva.io/) provides a rich Clojure editing experience in VS Code.

### Option 1: Connect to Running REPL

1. Start the REPL from terminal: `NREPL_PORT=7888 clj -M:dev:macos-arm64` (or your platform)
2. Run `(open)` to launch the window
3. In VS Code, run **Calva: Connect to a Running REPL Server in the Project** (`Ctrl+Alt+C Ctrl+Alt+C`)
4. Select "deps.edn" → "localhost:7888"

### Option 2: Jack-In (Calva Starts REPL)

1. Run **Calva: Start a Project REPL and Connect (aka Jack-In)** (`Ctrl+Alt+C Ctrl+Alt+J`)
2. Select "deps.edn"
3. Check the aliases: `:dev` and your platform (e.g., `:macos-arm64`)
4. Once connected, evaluate `(open)` in the REPL

### Workflow

- Edit `src/app/config.clj`
- Evaluate `(reload)` in the REPL (or `Ctrl+Alt+C Enter` to eval current form)
- See changes instantly in the window

## Game Loop (Love2D-style)

The app uses a Love2D-inspired game loop with three hot-reloadable callbacks:

```clojure
(defn init []
  "Called once at startup. Initialize your game state here.")

(defn tick [dt]
  "Called every frame with delta time (seconds). Update game logic here.")

(defn draw [canvas width height]
  "Called every frame for rendering. Draw your game here.")
```

> Note: Named `init`/`tick` instead of `load`/`update` to avoid shadowing `clojure.core` functions.

All three functions are hot-reloadable - edit them and call `(reload)` to see changes instantly.

> why does love2d has different update and draw fn? does unity, godot, libgdx has it too?

⏺ Great question! The separation exists for important reasons:

Why separate update and draw?

1. Fixed vs variable timestep - Update can run at fixed rate (e.g., 60 times/sec) for deterministic physics, while draw runs at screen refresh rate (30/60/144 fps)
2. Frame skipping - If game runs slow, you can run update multiple times to catch up, but only draw once
3. Interpolation - Draw can interpolate between physics states for smooth visuals even at lower update rates

Other engines:

| Engine       | Update                                     | Draw                          |
| ------------ | ------------------------------------------ | ----------------------------- |
| Unity        | Update(), FixedUpdate()                    | Automatic (or OnRenderObject) |
| Godot        | \_process(delta), \_physics_process(delta) | \_draw() or automatic         |
| LibGDX       | render() (you split it yourself)           | Same method                   |
| Unreal       | Tick(DeltaTime)                            | Separate rendering system     |
| MonoGame/XNA | Update(GameTime)                           | Draw(GameTime)                |

Most engines have this pattern. LibGDX is notable for combining them - you typically write:

```
public void render() {
    update(Gdx.graphics.getDeltaTime());
    draw();
}
```

Love2D just makes it explicit, which is beginner-friendly.

### Example: Animation

```clojure
(defn tick [dt]
  ;; Grow circle by 10 pixels per second
  (swap! state/circle-radius + (* 10 dt)))
```

## How It Works

| Namespace      | Uses      | Behavior on Reload                  |
| -------------- | --------- | ----------------------------------- |
| `app.state`    | `defonce` | **Persists** - atoms survive reload |
| `app.config`   | `def`     | **Reloads** - values update         |
| `app.controls` | `defn`    | **Reloads** - functions update      |
| `app.core`     | `defn`    | **Reloads** - game loop updates     |
| `user`         | -         | **Excluded** - keeps REPL stable    |

Following [clj-reload](https://github.com/tonsky/clj-reload) best practices:

- `defonce` for persistent state
- `(resolve 'ns/sym)` for cross-namespace calls (vars are removed on unload)

### Why `resolve`?

When clj-reload unloads a namespace, vars are **completely removed** (not just re-bound). Direct references become stale:

```clojure
;; Bad - var object is removed on reload
config/shadow-dx
#'app.core/draw

;; Good - runtime lookup by symbol name
@(resolve 'app.config/shadow-dx)
(when-let [f (resolve 'app.core/draw)] (f canvas w h))
```

The event listener uses `resolve` for ALL callbacks, allowing every namespace to reload.

## Scripts

### State Consistency Check

A Babashka script to verify that all keys in `initial-state` have corresponding `defonce` declarations:

```bash
# Check the default state file (src/app/state.clj)
bb scripts/check_state.bb

# Check a specific file
bb scripts/check_state.bb path/to/state.clj
```

The script detects:

- **Unused keys**: defined in `initial-state` but no `defonce` uses them
- **Missing keys**: used in `defonce` but not defined in `initial-state`

Example output when issues are found:

```
UNUSED keys (in initial-state but no defonce):
  - :unused-key
```

Exits with code 0 if synchronized, code 1 if issues found.

## References

- [clj-reload](https://github.com/tonsky/clj-reload)
- [Skija](https://github.com/HumbleUI/Skija)
- [LWJGL](https://www.lwjgl.org/) (SDL3 bindings)
- [SDL3](https://www.libsdl.org/)

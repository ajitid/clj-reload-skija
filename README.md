# Clojure Hot-Reloadable Skija Demo

A Clojure demo showcasing hot-reloading with [JWM](https://github.com/HumbleUI/JWM) (windowing) and [Skija](https://github.com/HumbleUI/Skija) (2D graphics).

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
└── dev/
    └── user.clj          # REPL namespace (excluded from reload)
```

## Requirements

- Java 11+
- Clojure CLI

## Installation

### macOS (Homebrew)

```bash
brew install openjdk@11 clojure/tools/clojure
```

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
clj -A:dev:macos-arm64

# macOS Intel
clj -A:dev:macos-x64

# Windows
clj -A:dev:windows

# Linux
clj -A:dev:linux
```

An nREPL server will automatically start on port 7888.

In the REPL, start the window:

```clojure
(start)
```

The window opens and the REPL blocks (this is normal).

## Hot Reloading

To hot-reload changes, connect to the running nREPL from another terminal:

```bash
clj -M:connect
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

### Error Recovery

If you introduce a syntax or runtime error (e.g., typo like `forma` instead of `format`), the window shows a **red screen** (`0xFFFF6B6B`) as immediate visual feedback. The error message is printed to the console.

To recover:
1. Fix the error in your code
2. Call `(reload)` in the connected REPL
3. Drawing resumes automatically

## Using Calva (VS Code)

[Calva](https://calva.io/) provides a rich Clojure editing experience in VS Code.

### Option 1: Connect to Running REPL

1. Start the REPL from terminal: `clj -A:dev:macos-arm64` (or your platform)
2. Run `(start)` to launch the window
3. In VS Code, run **Calva: Connect to a Running REPL Server in the Project** (`Ctrl+Alt+C Ctrl+Alt+C`)
4. Select "deps.edn" → "localhost:7888"

### Option 2: Jack-In (Calva Starts REPL)

1. Run **Calva: Start a Project REPL and Connect (aka Jack-In)** (`Ctrl+Alt+C Ctrl+Alt+J`)
2. Select "deps.edn"
3. Check the aliases: `:dev` and your platform (e.g., `:macos-arm64`)
4. Once connected, evaluate `(start)` in the REPL

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

| Namespace      | Uses      | Behavior on Reload                    |
| -------------- | --------- | ------------------------------------- |
| `app.state`    | `defonce` | **Persists** - atoms survive reload   |
| `app.config`   | `def`     | **Reloads** - values update           |
| `app.controls` | `defn`    | **Reloads** - functions update        |
| `app.core`     | `defn`    | **Reloads** - game loop updates       |
| `user`         | -         | **Excluded** - keeps REPL stable      |

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

## References

- [clj-reload](https://github.com/tonsky/clj-reload)
- [Skija](https://github.com/HumbleUI/Skija)
- [JWM](https://github.com/HumbleUI/JWM)

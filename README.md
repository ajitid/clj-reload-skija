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
clj -P -M:dev
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

### Auto-Reload with `--watch`

The simplest way to get automatic reloading — edit a file, save, and see changes instantly with no manual `(reload)` calls needed.

**Option 1: Via pool.clj (recommended for new users)**

```bash
bb scripts/pool.clj open --watch playground/ball-spring
```

This launches the app and enables the file watcher in one command.

**Option 2: From the REPL**

If you already have a REPL running and an example open, connect from another terminal and enable the watcher:

```bash
# Connect to the running nREPL
clj -M:connect --port <port>
```

```clojure
(start-watcher!)   ;; Enable auto-reload — watches src/ for .clj changes
(stop-watcher!)    ;; Disable auto-reload
```

The watcher uses [beholder](https://github.com/nextjournal/beholder) (OS-native file events) to detect changes to any `.clj` file in `src/`. When a change is detected, reload happens automatically on the next frame — no manual intervention needed.

#### Alternative: watchexec

If you prefer an external file watcher, use [watchexec](https://github.com/watchexec/watchexec) with [rep](https://github.com/eraserhd/rep) (a lightweight nREPL client):

```bash
# Install (macOS)
brew install watchexec
brew install eraserhd/rep/rep
```

**With pool.clj (dynamic port):**

When you launch via `bb scripts/pool.clj open`, the active nREPL port is written to `.jvm-pool/active-port`. The included reload scripts read this file on each invocation, so they automatically track port changes across app restarts:

```bash
# macOS / Linux
watchexec -qnrc -e clj -w src -- ./scripts/reload.sh

# Windows
watchexec -qnrc -e clj -w src -- scripts\reload.cmd
```

**With a fixed port:**

If you started the REPL with a fixed nREPL port (e.g., `NREPL_PORT=7888`), point `rep` at it directly:

```bash
watchexec -qnrc -e clj -w src -- rep -p 7888 "(reload)"
```

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

| Namespace          | Uses                   | Behavior on Reload                        |
| ------------------ | ---------------------- | ----------------------------------------- |
| `app.shell.*`      | `defonce`, `defsource` | State **persists**, functions **reload**  |
| `app.projects.*.*` | `defonce`, `defsource` | State **persists**, functions **reload**  |
| `app.state.system` | `defonce`              | **Persists** - window, errors             |
| `app.ui.*`         | `defn`                 | **Reloads** - functions update            |
| `lib.*`            | `defn`                 | **Restart required** (not hot-reloadable) |
| `user`             | -                      | **Excluded** - keeps REPL stable          |

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

## Custom Skija Build

This project uses a custom Skija build with additional features:

- `Image.adoptMetalTextureFrom()` - Adopt Metal textures as Skia Images
- `Image.convertYUVToRGBA()` - GPU-based NV12→BGRA conversion via Metal compute shader
- `Image.releaseMetalTexture()` - Release Metal textures
- `Canvas.drawAtlas()` - Native sprite batch rendering

### Quick Start (Recommended)

The easiest way to build Skija is using the included build script:

```bash
# 1. Clone Skija as a sibling directory
cd ..
git clone https://github.com/HumbleUI/Skija.git

# 2. Run the build script (from this project)
cd clj-reload-skija
bb scripts/build-skija.clj
```

The script will:

- Check prerequisites (JAVA_HOME, Python 3, Skia binaries)
- Build Skija native library and Java classes
- Package JARs and copy them to `.jars/`

After building, you can run:

```bash
clj -A:dev:macos-arm64  # or your platform
```

### Why a Custom Build?

The standard Skija release doesn't support adopting CVMetalTexture-backed textures from VideoToolbox (NV12 format). Our custom build adds a Metal compute shader that converts NV12 to BGRA entirely on the GPU, enabling true zero-copy video playback.

### Skija Source Location

Skija should be placed as a sibling directory:

```
parent/
├── clj-reload-skija/    # This project
└── Skija/               # Skija source
```

If you haven't cloned it yet:

```bash
cd ..
git clone https://github.com/HumbleUI/Skija.git
```

### Build Requirements

#### macOS

- **Xcode Command Line Tools**

  ```bash
  xcode-select --install
  ```

  Required components:
  - **clang++** - compiles C++ (`.cc`) and Objective-C++ (`.mm`) files
  - **macOS SDK** - provides Metal.h and Foundation.h headers for the Metal compute shader

  Note: Homebrew's LLVM won't work - it lacks the macOS SDK headers.

- **CMake 3.10+**
  ```bash
  brew install cmake ninja
  ```
- **Python 3**
  ```bash
  brew install python3
  ```
- **JDK 11+** with `JAVA_HOME` set

  ```bash
  # Find your Java home
  /usr/libexec/java_home

  # Set it (add to ~/.zshrc or ~/.bashrc)
  export JAVA_HOME=$(/usr/libexec/java_home)
  ```

#### Windows

- **Visual Studio 2019+** with "Desktop development with C++" workload
  - Includes MSVC compiler (cl.exe), Windows SDK, and CMake
- **CMake 3.10+** (included with Visual Studio or install separately)
- **Ninja** (recommended)

  ```powershell
  # With Scoop
  scoop install ninja

  # Or with Chocolatey
  choco install ninja
  ```

- **Python 3**
  ```powershell
  scoop install python
  ```
- **JDK 11+** with `JAVA_HOME` set
  ```powershell
  # Set in System Environment Variables or PowerShell profile
  $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.1.12-hotspot"
  ```

### Building Skija

#### macOS (Apple Silicon)

```bash
cd /path/to/Skija

# JAVA_HOME is required - set it to your JDK location
export JAVA_HOME=$(/usr/libexec/java_home)

# Build native library and Java classes
python3 script/build.py

# Package into JARs
python3 script/package_shared.py
python3 script/package_platform.py
```

Output files in `target/`:

- `skija-shared-0.0.0-SNAPSHOT.jar` - Java classes
- `skija-macos-arm64-0.0.0-SNAPSHOT.jar` - Native library for Apple Silicon

#### macOS (Intel)

```bash
cd /path/to/Skija
export JAVA_HOME=$(/usr/libexec/java_home)
python3 script/build.py --arch x64
python3 script/package_shared.py
python3 script/package_platform.py
```

Output: `skija-macos-x64-0.0.0-SNAPSHOT.jar`

#### Windows

Open "x64 Native Tools Command Prompt for VS 2022" (or your VS version):

```cmd
cd C:\path\to\Skija

:: JAVA_HOME is required - set it to your JDK location
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.1.12-hotspot

:: Build
python script/build.py
python script/package_shared.py
python script/package_platform.py
```

Output: `skija-windows-x64-0.0.0-SNAPSHOT.jar`

### Local JARs Location

When using `bb scripts/build-skija.clj`, JARs are automatically copied to `.jars/`:

```
.jars/
├── skija-shared.jar        # Java classes (all platforms)
└── skija-macos-arm64.jar   # Native library (platform-specific)
```

The `deps.edn` is already configured to use these paths:

```clojure
{:deps
 {io.github.humbleui/skija-shared {:local/root ".jars/skija-shared.jar"}}

 :aliases
 {:macos-arm64
  {:extra-deps
   {io.github.humbleui/skija-macos-arm64 {:local/root ".jars/skija-macos-arm64.jar"}}}}}
```

The `.jars/` directory is gitignored - each developer builds locally.

### Reverting to Official Skija

To use the official Skija release instead of the custom build, replace `:local/root` with `:mvn/version`:

```clojure
{:deps
 {io.github.humbleui/skija-shared {:mvn/version "0.143.5"}}

 :aliases
 {:macos-arm64
  {:extra-deps
   {io.github.humbleui/skija-macos-arm64 {:mvn/version "0.143.5"}}}}}
```

Note: Official Skija won't have the Metal YUV conversion features, so NV12 video will fall back to CPU conversion.

### Rebuilding After Changes

If you modify the Skija source (e.g., `MetalYUVConverter.mm` or `Image.java`):

```bash
cd /path/to/Skija
export JAVA_HOME=$(/usr/libexec/java_home)  # macOS
# set JAVA_HOME=C:\path\to\jdk              # Windows

# Rebuild (incremental - only recompiles changed files)
python3 script/build.py

# Re-package the platform JAR
python3 script/package_platform.py
```

Then restart your Clojure application to load the new JAR.

## References

- [clj-reload](https://github.com/tonsky/clj-reload)
- [Skija](https://github.com/HumbleUI/Skija)
- [LWJGL](https://www.lwjgl.org/) (SDL3 bindings)
- [SDL3](https://www.libsdl.org/)

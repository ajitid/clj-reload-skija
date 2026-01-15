# Clojure Hot-Reloadable Skija Demo

A Clojure demo showcasing hot-reloading with [JWM](https://github.com/HumbleUI/JWM) (windowing) and [Skija](https://github.com/HumbleUI/Skija) (2D graphics).

## Project Structure

```
clj-reloadable-skija-on-window/
├── deps.edn              # Dependencies (JWM, Skija, clj-reload)
├── src/
│   └── app/
│       ├── state.clj     # Persistent state (defonce) - sizes
│       ├── config.clj    # Reloadable config (def) - blur/shadow
│       └── core.clj      # Main app with JWM window + Skija rendering
└── dev/
    └── user.clj          # REPL namespace with reload function
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

Now edit `src/app/config.clj` to change blur/shadow values:

```clojure
;; Try changing these values:
(def shadow-sigma 20.0)    ;; More shadow blur
(def blur-sigma-x 15.0)    ;; More rectangle blur
```

Then in the connected REPL:

```clojure
(reload)
```

The window will immediately reflect your changes.

## How It Works

| Namespace    | Uses      | Behavior on Reload                              |
|--------------|-----------|------------------------------------------------|
| `app.state`  | `defonce` | Persists - circle radius, rect size stay same  |
| `app.config` | `def`     | Reloads - shadow/blur amounts update           |

This demonstrates [clj-reload](https://github.com/tonsky/clj-reload)'s `defonce` vs `def` distinction for managing state during hot-reloading.

## References

- [clj-reload](https://github.com/tonsky/clj-reload)
- [Skija](https://github.com/HumbleUI/Skija)
- [JWM](https://github.com/HumbleUI/JWM)

● The project is set up. Here's the structure I created:

  clj-reloadable-skija-on-window/
  ├── deps.edn                 # Dependencies (JWM, Skija, clj-reload)
  ├── src/
  │   └── app/
  │       ├── state.clj        # Persistent state (defonce) - sizes
  │       ├── config.clj       # Reloadable config (def) - blur/shadow
  │       └── core.clj         # Main app with JWM window + Skija rendering
  └── dev/
      └── user.clj             # REPL namespace with reload function

  ---
  Instructions

  1. Install Clojure using Scoop

  # Install scoop if you haven't already (run in PowerShell)
  Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
  Invoke-RestMethod -Uri https://get.scoop.sh | Invoke-Expression

  # Add the Java bucket and install Java
  scoop bucket add java
  scoop install temurin-lts-jdk

  # Install Clojure
  scoop install clojure

  2. Install Project Dependencies

  cd C:\Users\ajits\ghq\unknown-source\unknown-author\clj-reloadable-skija-on-window

  # Download dependencies (first run will take a moment)
  clj -P -A:dev

  3. Run the Project with REPL

  # Start the REPL with dev alias
  clj -A:dev

  Then in the REPL:
  ;; Start the application
  (start)

  4. Hot Reload After Making Changes

  Edit src/app/config.clj to change blur/shadow values:

  ;; Try changing these values:
  (def shadow-sigma 20.0)    ;; More shadow blur
  (def blur-sigma-x 15.0)    ;; More rectangle blur

  Then in the REPL, run the reload command:
  (reload)

  The window will immediately reflect your changes.

  ---
  How It Works
  ┌────────────┬─────────┬───────────────────────────────────────────────────┐
  │ Namespace  │  Uses   │                Behavior on Reload                 │
  ├────────────┼─────────┼───────────────────────────────────────────────────┤
  │ app.state  │ defonce │ Persists - circle radius, rect size stay the same │
  ├────────────┼─────────┼───────────────────────────────────────────────────┤
  │ app.config │ def     │ Reloads - shadow/blur amounts update              │
  └────────────┴─────────┴───────────────────────────────────────────────────┘
  Sources:
  - https://github.com/tonsky/clj-reload
  - https://github.com/HumbleUI/Skija
  - https://github.com/HumbleUI/JWM
  - https://mono.github.io/SkiaSharp/docs/effects/image-filters.html

✻ Cooked for 3m 49s

❯ i don't think i can install clojure using scoop. what are my alternatives on windows?

● Web Search("install Clojure Windows 2025 official methods")
  ⎿  Did 1 search in 18s

● Fetch(https://clojure.org/guides/install_clojure)
  ⎿  Received 23.1KB (200 OK)

● Fetch(https://github.com/casselc/clj-msi)
  ⎿  Received 248.4KB (200 OK)

● Here are your alternatives on Windows:

  ---
  Option 1: MSI Installer (Easiest)

  Download and run the official MSI installer:

  1. Install Java first - Download https://adoptium.net/ (click "Latest LTS Release")
  2. Download Clojure MSI from https://github.com/casselc/clj-msi/releases/latest
  3. Double-click the .msi file to install

  Then run the project:
  cd C:\Users\ajits\ghq\unknown-source\unknown-author\clj-reloadable-skija-on-window
  clojure -A:dev

  ---
  Option 2: WSL (Windows Subsystem for Linux)

  This is officially recommended as it matches most Clojure tutorials:

  # Install WSL
  wsl --install

  # Restart, then in WSL terminal:
  sudo apt update
  sudo apt install -y openjdk-21-jdk rlwrap
  curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
  chmod +x linux-install.sh
  sudo ./linux-install.sh

  ---
  Option 3: Leiningen

  A popular alternative build tool (doesn't use deps.edn):

  1. Install Java (Temurin JDK)
  2. Download https://leiningen.org/ and put it on your PATH
  3. Run lein once to self-install

  However, this would require converting the project to use project.clj instead of deps.edn.

  ---
  My Recommendation

  Go with Option 1 (MSI Installer) - it's the simplest native Windows option and works directly with the deps.edn project I created.

  Sources:
  - https://clojure.org/guides/install_clojure
  - https://github.com/casselc/clj-msi/releases/latest
  - https://leiningen.org/

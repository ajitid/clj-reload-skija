# Hot-Reload with watchexec and rep

Using `watchexec` CLI along with `rep` to automatically invoke `(reload)` when files change.

## The Challenge

`clj -M:connect` starts an interactive REPL session - it's not designed to execute a single command and exit. We need a non-interactive way to send `(reload)` to the nREPL server.

## Options

### 1. Use `rep` (recommended)

Install [rep](https://github.com/eraserhd/rep), a simple nREPL CLI client:

```bash
brew install eraserhd/rep/rep
```

Then:

```bash
watchexec -e clj -w src -w dev -- rep -p 7888 "(reload)"
```

### 2. Pipe to nrepl.cmdline

This can work but may have buffering issues:

```bash
watchexec -e clj -w src -w dev -- bash -c 'echo "(reload)" | clj -M:connect'
```

### 3. Use Babashka with nREPL client

If you have `bb` installed:

```bash
watchexec -e clj -w src -w dev -- bb -e '
(require (quote [babashka.nrepl-client :as nrepl]))
(println (:val (first (nrepl/message {:port 7888} {:op "eval" :code "(reload)"}))))
'
```

### 4. Add a one-shot alias to deps.edn

Add this alias:

```clojure
:reload
{:extra-deps {nrepl/nrepl {:mvn/version "1.1.1"}}
 :main-opts ["-e" "(require '[nrepl.core :as nrepl])(with-open [c (nrepl/connect :port 7888)] (println (-> (nrepl/client c 5000) (nrepl/message {:op :eval :code \"(reload)\"}) first :value)))"]}
```

Then:

```bash
watchexec -e clj -w src -w dev -- clj -M:reload
```

## Installing rep

**macOS (Homebrew):**

```bash
brew install eraserhd/rep/rep
```

**From source (any platform):**

```bash
git clone https://github.com/eraserhd/rep.git
cd rep
make install
```

## Using rep with watchexec

1. Start your app in one terminal:

   ```bash
   clj -A:dev:macos-arm64
   # then in REPL: (start)
   ```

2. In another terminal, run watchexec:
   ```bash
   watchexec -qnrc -e clj -w src -w dev -- rep -p 7888 "(reload)"
   ```

**Flags explained:**

- `-e clj` — watch only `.clj` files
- `-w src -w dev` — watch the `src/` and `dev/` directories
- `-p 7888` — connect to nREPL on port 7888
- `"(reload)"` — the code to evaluate

Now whenever you save a `.clj` file, `watchexec` will automatically send `(reload)` to your running REPL.

## Optional: add debounce

If you save rapidly, add a short debounce:

```bash
watchexec -e clj -w src -w dev --debounce 300ms -- rep -p 7888 "(reload)"
```

> I want to know that there are these variables that persist across reloads which is fine but what about if I add a new variable while the program is running will it be picked up and what if I remove it some
> some other variable along with the code that references it obviously will it automatically be picked up

⏺ Let me look at how clj-reload is configured in this project to give you an accurate answer.

⏺ Great question! Here's how clj-reload handles these scenarios:

Adding a new variable

Yes, it will be picked up. When you call (reload):

1. clj-reload detects file changes
2. Reloads the modified namespace
3. New def, defn, or defonce values become available immediately

Example: Add `(def new-color 0xFF00FF00)` to `config.clj`, call `(reload)`, and it's immediately usable.

Removing a variable (and code referencing it)

Yes, this also works — clj-reload does a proper unload before reload. This means:

1. Removed vars are actually removed from the namespace (not left dangling)
2. Dependent namespaces are reloaded in correct order (clj-reload tracks dependencies)

So if you remove circle-color from config.clj AND remove the code in core.clj that uses it, both files are changed → both namespaces reload → everything is consistent.

The defonce caveat

The one exception: defonce values persist in memory even if you remove them from source.

```
  ;; If you add this to state.clj:
  (defonce my-new-atom (atom 42))

  ;; Then later remove it from source and reload:
  ;; The atom STILL EXISTS in memory (defonce = defined once, forever)
  ;; Only a JVM restart clears it
```

This is intentional — it's what keeps your window, running state, etc. alive across reloads.

Key insight

This is why the codebase uses resolve for cross-namespace calls (see user.clj:40-41):

- resolve looks up vars by symbol at runtime
- If a var is removed, resolve returns nil (no crash, just skip)
- If a var is added, resolve finds it immediately

---

## Why macros don't work for DRYing state definitions

**The goal**: Avoid duplicating init values in `src/app/state.clj` between `defonce` declarations and `reset-state!` function.

**Approaches tried (all failed)**:

1. **Registry with `swap!`** — Macro registers atom + init value in a registry atom
   ```clojure
   (defmacro defstate [name init-val]
     `(do
        (defonce ~name (atom ~init-val))
        (swap! state-registry assoc '~name {:var (var ~name) :init ~init-val})))
   ```
   Result: **Hangs on reload** — `swap!` during namespace load conflicts with clj-reload

2. **`defstates` macro** — Single macro generates all defonce forms + an init-values map
   ```clojure
   (defstates
     scale 1.0
     window-width 800
     ...)
   ```
   Result: **Hangs on reload** — the `def init-values` form interferes with clj-reload's defonce handling

3. **Atom metadata** — Store init value in atom metadata
   ```clojure
   (defonce scale (with-meta (atom 1.0) {:init 1.0}))
   ```
   Result: **ClassCastException** — Clojure atoms don't implement IObj (no metadata support)

**Why it fails**: clj-reload has special handling for `defonce` to preserve values across reloads. Any side effects during namespace load (`swap!`, `def`, `alter-meta!`) interfere with this mechanism.

**The working pattern**: Plain `defonce` + plain `reset-state!` with literal values.

```clojure
;; state.clj
(defonce scale (atom 1.0))
(defonce circles-x (atom 3))

(defn reset-state! []
  (reset! scale 1.0)      ; yes, duplicated
  (reset! circles-x 3))   ; that's the cost
```

The 2-place duplication is the price of clj-reload compatibility. This is the same pattern used in Tonsky's own projects.

# File Read & IPC Performance Notes

Reference for fast inter-process communication and file reading strategies.

## IPC Method Benchmarks (2024)

From various benchmarks (brylee10/unix-ipc-benchmarks, goldsborough/ipc-bench, pranitha.dev):

| Method | Latency | Throughput | Notes |
|--------|---------|------------|-------|
| **Shared memory** | ~0.1-1 µs | ~5M msgs/sec | Fastest, needs C/native code |
| **Memory-mapped file (/dev/shm)** | ~0.1-1 µs | ~5M msgs/sec | Same as shared memory, file API |
| **Named pipe (FIFO)** | ~1-6 µs | ~200k msgs/sec | Needs daemon |
| **POSIX message queue** | ~5-10 µs | ~50-500k msgs/sec | Kernel overhead |
| **Unix domain socket** | ~10-20 µs | ~150k msgs/sec | Requires daemon |
| **TCP localhost** | ~20-100 µs | ~30-100k msgs/sec | Most overhead |

**Key insight:** Shared memory / mmap is 10-40× faster than sockets for small messages.

Sources:
- https://github.com/brylee10/unix-ipc-benchmarks
- https://github.com/goldsborough/ipc-bench
- https://pranitha.dev/posts/rust-ipc-ping-pong/
- https://www.kuniga.me/blog/2024/12/07/local-ipc.html

## OS-Specific RAM-Backed Directories for memory-mapped file

| OS | Path | Type | Notes |
|----|------|------|-------|
| **Linux** | `/dev/shm/` | tmpfs | True RAM filesystem, never touches disk |
| **macOS** | `$TMPDIR` | per-user temp | RAM-backed, usually `/var/folders/.../T/` |
| **macOS** | `/tmp` | shared temp | Symlink to `/private/tmp`, cached |
| **Windows** | `%TEMP%` | user temp | Usually `C:\Users\...\AppData\Local\Temp`, OS-cached |
| **Windows** | `%TMP%` | same as TEMP | Alternative env var |

**macOS note:** `$TMPDIR` is preferred over `/tmp` because:
- Per-user isolation
- Often backed by faster storage on modern macOS
- Cleaned up on logout

## Bash/Shell Overhead

Approximate times for shell operations:

| Operation | Time | Notes |
|-----------|------|-------|
| Fork + exec bash | ~2-5 ms | Process creation overhead |
| Fork + exec sh | ~1-3 ms | Slightly lighter than bash |
| `$(cat file)` | ~2-5 ms | Forks cat subprocess |
| `$(<file)` (bash) | ~0.1-0.5 ms | Bash builtin, no subprocess |
| Read 4-byte cached file | ~0.1-0.5 ms | OS buffer cache hit |
| Read 4-byte from tmpfs | ~0.01-0.1 ms | Pure memory access |

## Reload Command Performance Comparison

For our watchexec + rep reload workflow:

| Approach | Overhead | Command |
|----------|----------|---------|
| Script file | ~5-10 ms | `./scripts/reload.sh /path` |
| sh + cat | ~3-5 ms | `sh -c 'rep -p $(cat /path) "(reload)"'` |
| bash + builtin | ~2-3 ms | `bash -c 'rep -p $(<"/path") "(reload)"'` |
| Fixed port | ~0 ms | `rep -p 7888 "(reload)"` |

**Context:** Clojure namespace reload takes ~50-500 ms, so the file read overhead is <1-5% of total time.

## watchexec `-n` Flag Trade-off

When using watchexec with dynamic port reading:

**With `-n` (no shell) - faster per reload:**
```bash
watchexec -qnrc -e clj -w src -w dev -- rep -p $(cat .jvm-pool/active-port) "(reload)"
```
- `$(cat ...)` evaluated by YOUR shell **once** at startup
- Per file change: watchexec directly `exec()` → `rep -p 7888 "(reload)"`
- Overhead per reload: ~0ms (just exec)
- **Trade-off:** Must restart watchexec when app restarts (new port)

**Without `-n` (with shell) - auto-updates port:**
```bash
watchexec -qrc -e clj -w src -w dev -- rep -p $(cat .jvm-pool/active-port) "(reload)"
```
- Per file change: watchexec → `fork(sh)` → shell parses → `fork(cat)` → read file → `exec(rep)`
- Overhead per reload: ~2-6ms (shell + cat)
- **Trade-off:** Slightly slower, but automatically picks up new port

**Full reload time breakdown:**

| Operation | Time |
|-----------|------|
| Shell spawn (without `-n`) | ~1-3ms |
| `cat` on small file | <1ms |
| `rep` network call to nREPL | ~20-100ms |
| clj-reload evaluation | ~50-500ms |
| **Total** | **~70-600ms** |

**Verdict:** Shell overhead is **<5% of total reload time**. Use `-n` for marginally faster reloads if you don't mind restarting watchexec. Skip `-n` for convenience when frequently restarting the app via pool.

## Our Implementation

We use RAM-backed paths with project-specific filenames:

```clojure
;; pool.clj calculates hash of project directory
(defn- project-hash []
  (let [path (str (fs/cwd))
        hash (Math/abs (.hashCode path))]
    (format "%08x" hash)))

;; Filename: jvm-pool-<hash>-port
;; Linux:   /dev/shm/jvm-pool-a1b2c3d4-port
;; macOS:   $TMPDIR/jvm-pool-a1b2c3d4-port  
;; Windows: %TEMP%\jvm-pool-a1b2c3d4-port
```

This gives us:
- ~0.1-1 µs file read (RAM-backed)
- Per-project isolation (hash prevents conflicts)
- Cross-platform support

# Debug Notes

## Port 7888 already in use

**Error:**
```
Exception in thread "main" Syntax error macroexpanding at (user.clj:16:1).
Caused by: java.net.BindException: Address already in use (Bind failed)
```

This happens when a previous REPL session didn't shut down cleanly and is still holding port 7888.

**Fix:**
```bash
lsof -ti:7888 | xargs kill
```

Then restart with `clj -A:dev:macos-arm64`.

#!/bin/sh
# Reload script for watchexec - reads port fresh each time
# Usage: watchexec -qnrc -e clj -w src -w dev -- ./scripts/reload.sh
# Uses /bin/sh (not bash) for faster startup (~1-2ms vs ~2-5ms)

PORT_FILE=".jvm-pool/active-port"

if [ -f "$PORT_FILE" ]; then
    rep -p "$(cat "$PORT_FILE")" "(reload)"
else
    echo "No active port file found at $PORT_FILE"
    echo "Start the app first: bb scripts/pool.clj open"
    exit 1
fi

@echo off
REM Reload script for watchexec - reads port fresh each time
REM Usage: watchexec -qnrc -e clj -w src -w dev -- scripts\reload.cmd
REM Uses cmd.exe (not PowerShell) for faster startup (~10-50ms vs ~100-500ms)

set PORT_FILE=.jvm-pool\active-port

if exist %PORT_FILE% (
    for /f %%p in (%PORT_FILE%) do rep -p %%p "(reload)"
) else (
    echo No active port file found at %PORT_FILE%
    echo Start the app first: bb scripts/pool.clj open
    exit /b 1
)

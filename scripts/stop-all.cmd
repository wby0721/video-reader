@echo off
rem ============================================================
rem  Video Agent one-click stop (wrapper for stop-all.ps1)
rem  Usage: stop-all.cmd
rem  NOTE: keep this file ASCII-only (see start-all.cmd note).
rem ============================================================
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-all.ps1" %*
set "EXITCODE=%ERRORLEVEL%"
echo.
echo [stop-all] Script exited with code %EXITCODE%.
echo [stop-all] Press any key to close this window...
pause >nul
exit /b %EXITCODE%

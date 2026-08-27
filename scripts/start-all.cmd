@echo off
rem ============================================================
rem  Video Agent one-click launcher (wrapper for start-all.ps1)
rem  Usage:
rem    start-all.cmd                start everything incl. frontend
rem    start-all.cmd -SkipFrontend  start services only
rem  NOTE: keep this file ASCII-only. cmd.exe parses batch files
rem  with the system codepage; non-ASCII bytes corrupt the parsing
rem  and make the window flash and close immediately.
rem ============================================================
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all.ps1" %*
set "EXITCODE=%ERRORLEVEL%"
echo.
echo [start-all] Script exited with code %EXITCODE%.
echo [start-all] Press any key to close this window...
pause >nul
exit /b %EXITCODE%

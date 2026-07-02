@echo off
setlocal
rem =====================================================================
rem JustSearch Headless Runtime launcher (tempdoc 657).
rem
rem Runs the backend as a local, loopback-only (127.0.0.1) service with no
rem desktop shell, in the given install/runtime mode:
rem
rem   headless   full local service (retrieval + LLM, no desktop UI)
rem   mcp-lite   fast retrieval for agents (no LLM download / no LLM start)
rem
rem Usage:   justsearch-headless.cmd [headless^|mcp-lite]     (default: headless)
rem
rem To reuse an existing install's data + models, set before launching:
rem   set JUSTSEARCH_DATA_DIR=%%LOCALAPPDATA%%\JustSearch\data
rem   set JUSTSEARCH_HOME=%%APPDATA%%\io.justsearch.shell
rem
rem The runtime manifest (GET /api/runtime/manifest) reports the active mode
rem under `mode.intent` / `mode.realized`.
rem =====================================================================
set "MODE=%~1"
if "%MODE%"=="" set "MODE=headless"

set "HERE=%~dp0"
set "JAVA=%HERE%runtime\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"

"%JAVA%" -cp "%HERE%ui-headless.jar;%HERE%lib\*" "-Djustsearch.mode=%MODE%" io.justsearch.ui.HeadlessApp
endlocal

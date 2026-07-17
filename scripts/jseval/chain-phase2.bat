@echo off
setlocal EnableDelayedExpansion
rem ===========================================================================
rem  Phase-2 follow-ups chain (tempdoc 624 Phase-2 pre-registration 2026-07-17).
rem  TWO runs, sequential, each with its OWN independent hard cap (no cross-run
rem  extrapolation). Per-arm calibrated timeouts flow from calibration.json via
rem  utility-run --calibration (no new flags). ITT resource-exhaustion-as-failure
rem  rule is already in the harness (merged #230).
rem
rem  RUN 1 - email-10k: mixed/en-email-enron-raw-10k-verbose, haiku, A/B,
rem          seeds 3 {0,1,2}, 20q = 120 cells, per-cell max-budget $0.50. CAP $22.
rem  RUN 2 - sonnet tier probe: mixed/en-legal-clerc-10k-verbose, sonnet, A/B,
rem          seeds 1 {0}, 20q = 40 cells, per-cell max-budget $1.00. CAP $25.
rem  Order: email-10k first, then the probe. Per-run guard aborts THIS run and
rem  everything after if its own calibrate cost_estimate_usd > its cap.
rem
rem  Same conventions as chain-step2.bat: rc= markers, heartbeat log, terminal
rem  .done/.FAILED/.BUDGET-ABORT files, preflight step 0, serve-eval-backend.py
rem  lifecycle, resume-safe --log-dir, declared cert mode. Detached-runnable.
rem  The SAME live backend serves REST + /mcp on port 33221.
rem
rem  cmd rule: NO unquoted parentheses in echo text inside parenthesized blocks
rem  -- dashes are used instead (the paren bite from chain-step2 rev1).
rem ===========================================================================

set "WT=F:\justsearch-public\.claude\worktrees\step2-powered"
set "JS=%WT%\scripts\jseval"
set "PYTHONPATH=%JS%"
set "PYTHONUTF8=1"
set "INSPECT_DISPLAY=none"
set "JSEVAL_HEALTH_TIMEOUT_SEC=600"

set "PORT=33221"
set "BASEURL=http://127.0.0.1:%PORT%"
set "CONDS=A,B"
set "CONC=6"
set "CONTAM=private-synthetic"
set "TIER=C"

rem Corpus-signature mode. declared (DEFAULT) = Campaign-D parity: pass the live
rem staged corpus-dir files-mode hash as --corpus-signature, NO --corpus-certification.
rem strict opt-in via:  set STEP2_CERT_MODE=strict
set "CERTMODE=declared"
if not "%STEP2_CERT_MODE%"=="" set "CERTMODE=%STEP2_CERT_MODE%"

set "BASE=%JS%\tmp\phase2"
set "MK=%BASE%\markers"
set "MCP=%BASE%\mcp-config.json"
set "LOG=%BASE%\chain-phase2.log"

rem Runs: <cell>|<member>|<model>|<seeds>|<maxbudget>|<cap>
set "R1=en-email-enron-raw-10k-verbose|en-email-enron-raw|haiku|3|0.50|22"
set "R2=en-legal-clerc-10k-verbose|en-legal-clerc|sonnet|1|1.00|25"

if not exist "%BASE%" mkdir "%BASE%"
if not exist "%MK%" mkdir "%MK%"
cd /d "%JS%"

echo [%date% %time%] chain-phase2 START cert-mode=%CERTMODE%> "%LOG%"

rem ======================= STEP 0 : launch preflight =========================
rem (a) claude CLI resolvable (same probe the run records; no cost, no model call).
python phase2-cli-check.py >> "%LOG%" 2>&1
if errorlevel 1 (
  echo [%date% %time%] FATAL: claude CLI not resolvable - see log.>> "%LOG%"
  echo FATAL: claude CLI not resolvable.
  echo claude-cli-unresolvable > "%BASE%\chain-phase2.FAILED"
  goto :end
)
rem (b) Port 33221 must be free - findstr exit is the pipe's LAST stage = what we want.
netstat -ano -p tcp | findstr "LISTENING" | findstr ":%PORT% " >nul
if not errorlevel 1 (
  echo [%date% %time%] FATAL: port %PORT% already LISTENING - stop the other stack first.>> "%LOG%"
  echo FATAL: port %PORT% already in use.
  echo port-%PORT%-busy > "%BASE%\chain-phase2.FAILED"
  goto :end
)
rem (c) Both run dataset dirs + corpus-dir subdirs + queries.json must exist.
for %%X in ("%R1%" "%R2%") do call :check_dataset %%X
if exist "%BASE%\chain-phase2.FAILED" ( echo FATAL: missing dataset input - see log. & goto :end )
echo [%date% %time%] STEP 0 preflight OK>> "%LOG%"

rem --- One MCP config for condition B - "type":"http" is MANDATORY (url-only is dropped, F-027).
> "%MCP%" echo {"mcpServers":{"justsearch":{"type":"http","url":"http://127.0.0.1:%PORT%/mcp"}}}

rem ======================= two sequential runs ===============================
for %%X in ("%R1%" "%R2%") do call :run %%X

if exist "%BASE%\chain-phase2.FAILED" goto :end
if exist "%BASE%\chain-phase2.BUDGET-ABORT" goto :end
echo [%date% %time%] chain-phase2 END>> "%LOG%"
echo DONE > "%BASE%\chain-phase2.done"
goto :end

rem ===========================================================================
rem  check_dataset (STEP 0c): verify a run's inputs exist. %~1="<cell>|<member>|..."
rem ===========================================================================
:check_dataset
for /f "tokens=1 delims=|" %%a in ("%~1") do set "CELL=%%a"
set "DSDIR=%WT%\datasets\mixed\!CELL!"
if not exist "!DSDIR!\corpus-dir\" ( echo [%date% %time%] MISSING corpus-dir - !DSDIR!\corpus-dir>> "%LOG%" & echo missing-corpus-dir-!CELL! > "%BASE%\chain-phase2.FAILED" )
if not exist "!DSDIR!\queries.json" ( echo [%date% %time%] MISSING queries.json - !DSDIR!\queries.json>> "%LOG%" & echo missing-queries-!CELL! > "%BASE%\chain-phase2.FAILED" )
goto :eof

rem ===========================================================================
rem  run: serve+ingest ONCE -> calibrate -> per-run guard -> utility-run -> down.
rem  %~1 = "<cell>|<member>|<model>|<seeds>|<maxbudget>|<cap>"
rem ===========================================================================
:run
if exist "%BASE%\chain-phase2.FAILED" goto :eof
if exist "%BASE%\chain-phase2.BUDGET-ABORT" goto :eof
for /f "tokens=1-6 delims=|" %%a in ("%~1") do (
  set "CELL=%%a" & set "MEMBER=%%b" & set "MODEL=%%c" & set "SEEDS=%%d" & set "MAXBUDGET=%%e" & set "CAP=%%f"
)
set "SLUG=mixed/!CELL!"
set "OUT=%BASE%\!CELL!-!MODEL!"
set "CORPUSDIR=%WT%\datasets\mixed\!CELL!\corpus-dir"
set "QUERIES=%WT%\datasets\mixed\!CELL!\queries.json"
set "CERT=%JS%\707-corpora\!MEMBER!\structural-certification.v1.json"
set "LOGDIR=!OUT!\logs"
set "RUNOUT=!OUT!\out"
rem Single-tier cell enforcement (tempdoc 624 Phase-2 amendment 2): the Claude CLI
rem makes haiku-class background calls; on a non-haiku campaign they trip the
rem resolved-model cohort guard and void every cell. Pin background + subagent
rem models to the campaign tier via the 725 --agent-env seam. Applied only when
rem the run model is not haiku (haiku campaigns are uniform already).
set "AGENTENV="
if "!MODEL!"=="sonnet" set "AGENTENV=--agent-env ANTHROPIC_DEFAULT_HAIKU_MODEL=claude-sonnet-5 --agent-env CLAUDE_CODE_SUBAGENT_MODEL=claude-sonnet-5"
if not exist "!OUT!" mkdir "!OUT!"
echo [%date% %time%] RUN !CELL! model=!MODEL! seeds=!SEEDS! maxbudget=!MAXBUDGET! cap=!CAP! START>> "%LOG%"

rem Resume: fully-done run -> skip, no serve, no spend.
if exist "!OUT!\run.done" (
  echo [%date% %time%] RUN !CELL!-!MODEL! SKIP - run.done present>> "%LOG%"
  goto :eof
)

rem --- backend up clean + ingest ONCE (index-cache integration REVERTED for this
rem     campaign, 2026-07-17 ~15:00: the chain's split topology hit three live
rem     integration conflicts — subdir-signature disable, corpus-path key binding
rem     vs the datasets/ convention, staging-sidecar indexing + watched-root scope.
rem     Findings handed to the 751 lane as the §P.3.5 chain-integration spec; this
rem     campaign runs the proven fresh-build path.)
call :serve_up !CELL!-!MODEL! "!OUT!"
if not "!SERVE_RC!"=="0" ( call :fail "serve_up !CELL!-!MODEL!" & goto :eof )
call :ingest !CELL!-!MODEL! "!CORPUSDIR!" "!SLUG!" "!OUT!"
if not "!ING_RC!"=="0" ( call :serve_down !CELL!-!MODEL! "!OUT!" & call :fail "ingest !CELL!-!MODEL!" & goto :eof )

rem --- corpus-signature preflight (declared default) -> writes !OUT!\corpus-args.txt
python step2-corpus-args.py --corpus-dir "!CORPUSDIR!" --cert "!CERT!" --dataset "!SLUG!" --mode %CERTMODE% --out "!OUT!\corpus-args.txt" >> "%LOG%" 2>&1
set "PRC=!errorlevel!"
echo rc=!PRC! > "%MK%\preflight-!CELL!-!MODEL!.marker"
if not "!PRC!"=="0" ( call :serve_down !CELL!-!MODEL! "!OUT!" & call :fail "corpus-preflight !CELL!-!MODEL!" & goto :eof )
set "CORPUS_ARGS="
set /p CORPUS_ARGS=<"!OUT!\corpus-args.txt"

rem --- calibrate (skip on resume if present) against the LIVE ingest; emits per-arm timeouts
if exist "!OUT!\calibration.json" (
  echo [%date% %time%] calibrate !CELL!-!MODEL! SKIP - calibration.json present>> "%LOG%"
) else (
  echo [%date% %time%] calibrate !CELL!-!MODEL! START>> "%LOG%"
  python -m jseval utility-calibrate --queries "!QUERIES!" --corpus-dir "!CORPUSDIR!" --mcp-config "%MCP%" --base-url %BASEURL% --model !MODEL! --conditions %CONDS% --seeds !SEEDS! --concurrency %CONC% --output "!OUT!\calibration.json" >> "%LOG%" 2>&1
  set "CRC=!errorlevel!"
  echo rc=!CRC! > "%MK%\calibrate-!CELL!-!MODEL!.marker"
  if not "!CRC!"=="0" ( del "!OUT!\calibration.json" 2>nul & call :serve_down !CELL!-!MODEL! "!OUT!" & call :fail "calibrate !CELL!-!MODEL! rc=!CRC! - calib removed so resume re-attempts" & goto :eof )
)

rem --- PER-RUN BUDGET GUARD - this run's own calibration only, own cap, no extrapolation
echo [%date% %time%] budget guard !CELL!-!MODEL! cap=!CAP!>> "%LOG%"
python step2-budget-guard.py --glob "!OUT!\calibration.json" --cap !CAP! --total 1 >> "%LOG%" 2>&1
set "GRC=!errorlevel!"
echo rc=!GRC! > "%MK%\budget-!CELL!-!MODEL!.marker"
if not "!GRC!"=="0" (
  call :serve_down !CELL!-!MODEL! "!OUT!"
  echo [%date% %time%] BUDGET ABORT at !CELL!-!MODEL! - this run and all after are skipped.>> "%LOG%"
  echo budget-abort-at-!CELL!-!MODEL! > "%BASE%\chain-phase2.BUDGET-ABORT"
  goto :eof
)

rem --- utility-run - resume-safe via --log-dir; per-arm timeouts flow from --calibration
echo [%date% %time%] utility-run !CELL!-!MODEL! START>> "%LOG%"
python -m jseval utility-run --queries "!QUERIES!" --corpus-dir "!CORPUSDIR!" --mcp-config "%MCP%" --model !MODEL! --conditions %CONDS% --seeds !SEEDS! --concurrency %CONC% --max-budget !MAXBUDGET! --calibration "!OUT!\calibration.json" --dataset "!SLUG!" !CORPUS_ARGS! !AGENTENV! --contamination-class %CONTAM% --confidence-tier %TIER% --log-dir "!LOGDIR!" --output-dir "!RUNOUT!" >> "%LOG%" 2>&1
set "RRC=!errorlevel!"
echo rc=!RRC! > "%MK%\run-!CELL!-!MODEL!.marker"
call :serve_down !CELL!-!MODEL! "!OUT!"
if not "!RRC!"=="0" ( call :fail "utility-run !CELL!-!MODEL! rc=!RRC! - resume with same --log-dir" & goto :eof )
echo done > "!OUT!\run.done"
echo [%date% %time%] RUN !CELL!-!MODEL! DONE>> "%LOG%"
goto :eof

rem ===========================================================================
rem  ingest into the LIVE backend + wait for full pipeline readiness. Leaves it up.
rem  %1=tag %2=corpus-dir(subdir) %3=slug %4=out
rem ===========================================================================
:ingest
echo [%date% %time%] ingest %~1 START>> "%LOG%"
python -m jseval run --base-url %BASEURL% --dataset "%~3" --corpus-dir "%~2" --modes lexical --max-queries 1 --pipeline --allow-errors --allow-degraded >> "%LOG%" 2>&1
set "ING_RC=!errorlevel!"
echo rc=!ING_RC! > "%MK%\ingest-%~1.marker"
echo [%date% %time%] ingest %~1 rc=!ING_RC!>> "%LOG%"
goto :eof

rem ===========================================================================
rem  serve_up: background a clean persistent backend on %PORT%, poll ready.
rem  %1=tag %2=out ; sets SERVE_RC (0 ok).
rem ===========================================================================
:serve_up
set "SERVE_RC=1"
set "RDY=%MK%\serve-%~1.ready"
set "STOP=%MK%\serve-%~1.stop"
set "STOPPED=%MK%\serve-%~1.stopped"
set "FAILED=%MK%\serve-%~1.failed"
del "!RDY!" "!STOP!" "!STOPPED!" "!FAILED!" 2>nul
echo [%date% %time%] serve_up %~1 START>> "%LOG%"
start "serve-%~1" /b cmd /c "python serve-eval-backend.py --port %PORT% --clean --ready-file "!RDY!" --stop-file "!STOP!" --stopped-file "!STOPPED!" --failed-file "!FAILED!" > "%~2\serve.log" 2>&1"
rem Poll up to ~660s - greater than the 600s health timeout.
echo [%date% %time%] serve_up %~1 post-start errorlevel=!errorlevel!>> "%LOG%"
set /a _i=0
:serve_up_poll
echo [%date% %time%] serve_up %~1 poll iter !_i! rdy=!RDY!>> "%LOG%"
if exist "!RDY!" ( set "SERVE_RC=0" & goto :serve_up_done )
if exist "!FAILED!" ( echo [%date% %time%] serve_up %~1 FAILED - see serve.log>> "%LOG%" & goto :serve_up_done )
set /a _i+=1
if !_i! GEQ 330 ( echo [%date% %time%] serve_up %~1 TIMEOUT>> "%LOG%" & goto :serve_up_done )
ping -n 3 127.0.0.1 >nul
goto :serve_up_poll
:serve_up_done
echo rc=!SERVE_RC! > "%MK%\serve-up-%~1.marker"
goto :eof

rem ===========================================================================
rem  serve_down: signal the persistent backend to stop - orphan-safe - + wait.
rem  %1=tag %2=out
rem ===========================================================================
:serve_down
set "STOP=%MK%\serve-%~1.stop"
set "STOPPED=%MK%\serve-%~1.stopped"
echo [%date% %time%] serve_down %~1 START>> "%LOG%"
echo stop > "!STOP!"
set /a _j=0
:serve_down_poll
if exist "!STOPPED!" goto :serve_down_done
set /a _j+=1
if !_j! GEQ 60 ( echo [%date% %time%] serve_down %~1 TIMEOUT - backend may need manual kill>> "%LOG%" & goto :serve_down_done )
ping -n 3 127.0.0.1 >nul
goto :serve_down_poll
:serve_down_done
echo rc=0 > "%MK%\serve-down-%~1.marker"
goto :eof

rem ===========================================================================
rem  fail: record a fatal chain state and stop launching new work.
rem ===========================================================================
:fail
echo [%date% %time%] FAIL: %~1>> "%LOG%"
echo %~1 > "%BASE%\chain-phase2.FAILED"
goto :eof

:end
echo [%date% %time%] chain-phase2 EXIT>> "%LOG%"
endlocal

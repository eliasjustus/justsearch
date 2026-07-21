@echo off
setlocal EnableDelayedExpansion
rem ===========================================================================
rem  Confirmatory campaign chain (tempdoc 624 Confirmatory pre-registration
rem  2026-07-17). Zero-asterisk confirmation: claim policy is ACTIVE, the EXACT
rem  four-stratum matrix is required, no partial-value ladder.
rem
rem  FOUR runs, sequential, all haiku, A/B, seeds 3 {0,1,2}, 20q, per-cell
rem  max-budget $0.50, concurrency 6 = 480 cells. RUNNING budget guard with
rem  max-extrapolation across the four datasets, HARD CAP $100. Over -> abort
rem  the WHOLE campaign (a three-stratum record cannot promote; partial spend
rem  buys nothing).
rem
rem  Claim-grade identity (--corpus-root axis, landed this PR): every run attaches
rem  --corpus-certification verified against the DATASET-ROOT signature via
rem  --corpus-root, while --corpus-dir stays the leak-safe exploded subdir. So
rem  root mode SUPERSEDES the declared-mode step2-corpus-args preflight (not used).
rem
rem  Index-cache (751, #242): each dataset is WARMED first (build-or-adopt +
rem  publish), then serve_up ADOPTS the published entry (fast boot); the ingest
rem  step is the idempotent safety net.
rem
rem  Same conventions as chain-phase2.bat: rc= markers, heartbeat log, terminal
rem  .done/.FAILED/.BUDGET-ABORT files, preflight step 0, serve-eval-backend.py
rem  lifecycle, resume-safe --log-dir. Detached-runnable. Backend serves REST +
rem  /mcp on port 33221.
rem
rem  cmd rule: NO unquoted parentheses in echo text inside parenthesized blocks
rem  -- dashes are used instead.
rem ===========================================================================

set "WT=F:\justsearch-public\.claude\worktrees\step2-powered"
set "JS=%WT%\scripts\jseval"
set "PYTHONPATH=%JS%"
set "PYTHONUTF8=1"
set "INSPECT_DISPLAY=none"
set "JSEVAL_HEALTH_TIMEOUT_SEC=600"
rem Pin the harness for the whole chain lifetime -- tempdoc 758 sec B, incident #6: the claude
rem CLI auto-updated 2.1.212 -> 2.1.214 mid-night, hashing a NEW cli_version into
rem agent_cohort_key so the same-night stratum rerun could not rejoin the cohort. Supported
rem env var, verified against https://code.claude.com/docs/en/settings. Belt; the suspenders
rem is the jseval-side cli_version stamp+assert on each calibration.json - utility-run fails
rem closed on drift even if this knob is ineffective.
set "DISABLE_AUTOUPDATER=1"

set "PORT=33221"
set "BASEURL=http://127.0.0.1:%PORT%"
set "MODEL=haiku"
set "CONDS=A,B"
set "SEEDS=3"
set "CONC=6"
set "MAXBUDGET=0.50"
set "CAP=100"
set "NDATASETS=4"
set "CONTAM=private-synthetic"
set "TIER=C"

set "BASE=%JS%\tmp\confirm"
set "MK=%BASE%\markers"
set "MCP=%BASE%\mcp-config.json"
set "LOG=%BASE%\chain-confirm.log"

rem Runs: <cell>|<member>|<dataset-root signature = certified signature>
rem Order: CHEAPEST-FIRST (2026-07-17 ~23:20 reorder): the running guard's
rem max-extrapolation over-projects when the most expensive dataset calibrates
rem first (legal-1k $31.77 x4 = $127 projected vs ~$90 true sum -> spurious
rem abort). Cheapest-first keeps the SAME conservative formula tight:
rem ~46 -> ~60 -> ~91 -> ~91, all under the $100 cap. Matrix unchanged; the
rem claim policy is order-independent.
set "R1=en-email-enron-raw-1k-verbose|en-email-enron-raw|fba5cf691ce88e7689fed5c19b1a68072004beb67a5a964c4e52dea21a8649b7"
set "R2=en-email-enron-raw-10k-verbose|en-email-enron-raw|e6b302ea4d0faead72701f7ae15e1753b75a4f9e9f15935ea9e904a54bca528c"
set "R3=en-legal-clerc-1k-verbose|en-legal-clerc|eff6406d23500db3cc7f4a18807b086d05db75f0c6c9f4a1cee0d82a908b8932"
set "R4=en-legal-clerc-10k-verbose|en-legal-clerc|7b108fc4f2f3b9b95e234fbf251d2a7258cbaf3dd5685f4d11d2b18b15f68395"

if not exist "%BASE%" mkdir "%BASE%"
if not exist "%MK%" mkdir "%MK%"
cd /d "%JS%"

echo [%date% %time%] chain-confirm START model=%MODEL% seeds=%SEEDS% datasets=%NDATASETS% cap=%CAP%> "%LOG%"

rem ======================= STEP 0 : launch preflight =========================
rem (a) claude CLI resolvable - same probe the run records; no cost, no model call.
python phase2-cli-check.py >> "%LOG%" 2>&1
if errorlevel 1 (
  echo [%date% %time%] FATAL: claude CLI not resolvable - see log.>> "%LOG%"
  echo FATAL: claude CLI not resolvable.
  echo claude-cli-unresolvable > "%BASE%\chain-confirm.FAILED"
  goto :end
)
rem (b) Port 33221 must be free - findstr exit is the pipe's LAST stage = what we want.
netstat -ano -p tcp | findstr "LISTENING" | findstr ":%PORT% " >nul
if not errorlevel 1 (
  echo [%date% %time%] FATAL: port %PORT% already LISTENING - stop the other stack first.>> "%LOG%"
  echo FATAL: port %PORT% already in use.
  echo port-%PORT%-busy > "%BASE%\chain-confirm.FAILED"
  goto :end
)
rem (c) All four run dataset dirs + corpus-dir subdirs + queries.json must exist.
for %%X in ("%R1%" "%R2%" "%R3%" "%R4%") do call :check_dataset %%X
if exist "%BASE%\chain-confirm.FAILED" ( echo FATAL: missing dataset input - see log. & goto :end )
echo [%date% %time%] STEP 0 preflight OK>> "%LOG%"

rem --- One MCP config for condition B - "type":"http" is MANDATORY (url-only is dropped, F-027).
> "%MCP%" echo {"mcpServers":{"justsearch":{"type":"http","url":"http://127.0.0.1:%PORT%/mcp"}}}

rem ======================= four sequential runs ==============================
for %%X in ("%R1%" "%R2%" "%R3%" "%R4%") do call :run %%X

if exist "%BASE%\chain-confirm.FAILED" goto :end
if exist "%BASE%\chain-confirm.BUDGET-ABORT" goto :end
echo [%date% %time%] chain-confirm END - all four strata complete; recompose next.>> "%LOG%"
echo DONE > "%BASE%\chain-confirm.done"
goto :end

rem ===========================================================================
rem  check_dataset (STEP 0c): verify a run's inputs exist. %~1="<cell>|<member>|<sig>"
rem ===========================================================================
:check_dataset
for /f "tokens=1 delims=|" %%a in ("%~1") do set "CELL=%%a"
set "DSDIR=%WT%\datasets\mixed\!CELL!"
if not exist "!DSDIR!\corpus-dir\" ( echo [%date% %time%] MISSING corpus-dir - !DSDIR!\corpus-dir>> "%LOG%" & echo missing-corpus-dir-!CELL! > "%BASE%\chain-confirm.FAILED" )
if not exist "!DSDIR!\queries.json" ( echo [%date% %time%] MISSING queries.json - !DSDIR!\queries.json>> "%LOG%" & echo missing-queries-!CELL! > "%BASE%\chain-confirm.FAILED" )
goto :eof

rem ===========================================================================
rem  run: warm -> serve+adopt -> ingest -> calibrate -> guard -> utility-run -> down
rem  %~1 = "<cell>|<member>|<sig>"
rem ===========================================================================
:run
if exist "%BASE%\chain-confirm.FAILED" goto :eof
if exist "%BASE%\chain-confirm.BUDGET-ABORT" goto :eof
for /f "tokens=1-3 delims=|" %%a in ("%~1") do (set "CELL=%%a" & set "MEMBER=%%b" & set "SIG=%%c")
set "SLUG=mixed/!CELL!"
set "OUT=%BASE%\!CELL!"
set "ROOT=%WT%\datasets\mixed\!CELL!"
set "CORPUSDIR=!ROOT!\corpus-dir"
set "QUERIES=!ROOT!\queries.json"
set "CERT=%JS%\707-corpora\!MEMBER!\structural-certification.v1.json"
set "LOGDIR=!OUT!\logs"
set "RUNOUT=!OUT!\out"
if not exist "!OUT!" mkdir "!OUT!"
echo [%date% %time%] RUN !CELL! START>> "%LOG%"

rem Resume: fully-done run -> skip, no warm, no serve, no spend.
if exist "!OUT!\run.done" (
  echo [%date% %time%] RUN !CELL! SKIP - run.done present>> "%LOG%"
  goto :eof
)

rem --- WARM step REMOVED (2026-07-17 ~23:00): the 751 index-cache warm wedged on a
rem     cumulative readiness floor (same root ingested twice within one backend
rem     lifetime -> floor 2002 vs 1001 indexed, unmeetable). Finding filed to the
rem     751 lane; this campaign runs the proven fresh-build path (cache is
rem     economics, not mission -- root-mode claim identity is independent of it).

rem --- serve up (adopt the just-published entry via the #242 wrapper) + ingest ONCE
call :serve_up !CELL! "!OUT!"
if not "!SERVE_RC!"=="0" ( call :fail "serve_up !CELL!" & goto :eof )
call :ingest !CELL! "!CORPUSDIR!" "!SLUG!" "!OUT!"
if not "!ING_RC!"=="0" ( call :serve_down !CELL! "!OUT!" & call :fail "ingest !CELL!" & goto :eof )

rem --- FRESH calibrate (skip on resume if present) against the LIVE ingest; emits per-arm timeouts
if exist "!OUT!\calibration.json" (
  echo [%date% %time%] calibrate !CELL! SKIP - calibration.json present>> "%LOG%"
) else (
  echo [%date% %time%] calibrate !CELL! START>> "%LOG%"
  python -m jseval utility-calibrate --queries "!QUERIES!" --corpus-dir "!CORPUSDIR!" --mcp-config "%MCP%" --base-url %BASEURL% --model %MODEL% --conditions %CONDS% --seeds %SEEDS% --concurrency %CONC% --output "!OUT!\calibration.json" >> "%LOG%" 2>&1
  set "CRC=!errorlevel!"
  echo rc=!CRC! > "%MK%\calibrate-!CELL!.marker"
  if not "!CRC!"=="0" ( del "!OUT!\calibration.json" 2>nul & call :serve_down !CELL! "!OUT!" & call :fail "calibrate !CELL! rc=!CRC! - calib removed so resume re-attempts" & goto :eof )
)

rem --- RUNNING BUDGET GUARD across all four - sum + max-extrapolation, cap $100.
echo [%date% %time%] budget guard !CELL!>> "%LOG%"
python step2-budget-guard.py --glob "%BASE%\*\calibration.json" --cap %CAP% --total %NDATASETS% >> "%LOG%" 2>&1
set "GRC=!errorlevel!"
echo rc=!GRC! > "%MK%\budget-!CELL!.marker"
if not "!GRC!"=="0" (
  call :serve_down !CELL! "!OUT!"
  echo [%date% %time%] BUDGET ABORT at !CELL! - whole campaign stopped; no partial-value ladder.>> "%LOG%"
  echo budget-abort-at-!CELL! > "%BASE%\chain-confirm.BUDGET-ABORT"
  goto :eof
)

rem --- utility-run in ROOT mode (claim-grade identity). --corpus-dir is the leak-safe
rem     subdir; --corpus-root is its parent; --corpus-signature is the ROOT/certified sig.
rem     NO step2-corpus-args preflight (root mode supersedes it). NO --agent-env (haiku).
echo [%date% %time%] utility-run !CELL! START>> "%LOG%"
python -m jseval utility-run --queries "!QUERIES!" --corpus-dir "!CORPUSDIR!" --corpus-root "!ROOT!" --corpus-certification "!CERT!" --corpus-signature !SIG! --mcp-config "%MCP%" --model %MODEL% --conditions %CONDS% --seeds %SEEDS% --concurrency %CONC% --max-budget %MAXBUDGET% --calibration "!OUT!\calibration.json" --dataset "!SLUG!" --contamination-class %CONTAM% --confidence-tier %TIER% --log-dir "!LOGDIR!" --output-dir "!RUNOUT!" >> "%LOG%" 2>&1
set "RRC=!errorlevel!"
echo rc=!RRC! > "%MK%\run-!CELL!.marker"
call :serve_down !CELL! "!OUT!"
if not "!RRC!"=="0" ( call :fail "utility-run !CELL! rc=!RRC! - resume with same --log-dir" & goto :eof )
echo done > "!OUT!\run.done"
echo [%date% %time%] RUN !CELL! DONE>> "%LOG%"
goto :eof

rem ===========================================================================
rem  ingest into the LIVE backend + wait for full pipeline readiness (safety net).
rem  %1=cell %2=corpus-dir(subdir) %3=slug %4=out
rem ===========================================================================
:ingest
echo [%date% %time%] ingest %~1 START>> "%LOG%"
python -m jseval run --base-url %BASEURL% --dataset "%~3" --corpus-dir "%~2" --modes lexical --max-queries 1 --pipeline --allow-errors --allow-degraded >> "%LOG%" 2>&1
set "ING_RC=!errorlevel!"
echo rc=!ING_RC! > "%MK%\ingest-%~1.marker"
echo [%date% %time%] ingest %~1 rc=!ING_RC!>> "%LOG%"
goto :eof

rem ===========================================================================
rem  serve_up: background a clean persistent backend on %PORT% that ADOPTS the
rem  warmed index-cache entry for the corpus axis, poll ready.
rem  %1=cell %2=out %3=corpus-dir ; sets SERVE_RC (0 ok).
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
set /a _i=0
:serve_up_poll
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
rem  %1=cell %2=out
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
echo %~1 > "%BASE%\chain-confirm.FAILED"
goto :eof

:end
echo [%date% %time%] chain-confirm EXIT>> "%LOG%"
endlocal

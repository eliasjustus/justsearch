@echo off
setlocal EnableDelayedExpansion
rem ===========================================================================
rem  Step-2 POWERED agent-utility campaign (owner-ratified matrix, rev 2).
rem  Model haiku; conditions A (file tools) + B (file tools + JustSearch MCP);
rem  seeds 3 (Inspect epochs 3 -> {0,1,2}); concurrency 6.
rem  THREE verbose datasets x 20 committed queries = 360 cells (~$79 run + ~$8
rem  calibrate ~= $87). HARD CAP $90.
rem
rem  Structure: RUNNING GUARD, ONE ingest per dataset. For each dataset in order
rem  (legal-1k, legal-10k, email-1k):
rem     backend up + ingest ONCE -> utility-calibrate -> budget guard ->
rem     utility-run (same backend/ingest, resume-safe --log-dir) -> backend down.
rem  Budget guard after each calibrate projects whole-campaign spend
rem  (sum(known) + max(known) x datasets-still-without-an-estimate) and ABORTS
rem  before any further paid step if projected > $90.
rem
rem  Detached-runnable. Every step writes an rc= marker; no exit code is masked
rem  behind a pipe. Re-invoke after a crash to resume (per-dataset: calibrations
rem  and completed runs are skipped; utility-run resumes via same --log-dir).
rem
rem  The SAME live backend serves REST (calibrate --base-url) and /mcp
rem  (condition B) on port 33221. See LAUNCH-README.md.
rem ===========================================================================

set "WT=F:\justsearch-public\.claude\worktrees\step2-powered"
set "JS=%WT%\scripts\jseval"
set "PYTHONPATH=%JS%"
set "PYTHONUTF8=1"
set "INSPECT_DISPLAY=none"
set "JSEVAL_HEALTH_TIMEOUT_SEC=600"

set "PORT=33221"
set "BASEURL=http://127.0.0.1:%PORT%"
set "CAP=100"
set "MODEL=haiku"
set "CONDS=A,B"
set "SEEDS=3"
set "CONC=6"
set "CONTAM=private-synthetic"
set "TIER=C"
set "NDATASETS=3"

rem Corpus-signature mode. declared (DEFAULT) = Campaign-D parity: pass the live
rem staged corpus-dir files-mode hash as --corpus-signature, NO --corpus-certification
rem (the pre-registration records the cert/hash-equivalence chain separately).
rem strict (opt-in) = require cert.corpus_signature == that hash, fail-closed.
rem Override with:  set STEP2_CERT_MODE=strict  before launch.
set "CERTMODE=declared"
if not "%STEP2_CERT_MODE%"=="" set "CERTMODE=%STEP2_CERT_MODE%"

set "BASE=%JS%\tmp\step2-powered"
set "MK=%BASE%\markers"
set "MCP=%BASE%\mcp-config.json"
set "LOG=%BASE%\chain-step2.log"

rem Datasets (exactly 3): <cell>|<member>   (member -> the 707 cert file)
set "DS1=en-legal-clerc-1k-verbose|en-legal-clerc"
set "DS2=en-legal-clerc-10k-verbose|en-legal-clerc"
set "DS3=en-email-enron-raw-1k-verbose|en-email-enron-raw"

if not exist "%BASE%" mkdir "%BASE%"
if not exist "%MK%" mkdir "%MK%"
cd /d "%JS%"

echo [%date% %time%] chain-step2 START (cert-mode=%CERTMODE%, seeds=%SEEDS%, datasets=%NDATASETS%)> "%LOG%"

rem ======================= STEP 0 : launch preflight =========================
rem (a) Claude CLI must be resolvable + authenticated (the Agent SDK children ride
rem     the CLI's stored login -- jseval never reads ANTHROPIC_API_KEY; verified by
rem     grep and by Campaign T running key-less on this box, 2026-07-14).
python -c "from jseval import agent_utility_run as aur; v = aur.claude_cli_version(); print('claude_cli_version=' + str(v))" >> "%LOG%" 2>&1
if errorlevel 1 (
  echo [%date% %time%] FATAL: Claude CLI not resolvable from this environment.>> "%LOG%"
  echo FATAL: Claude CLI not resolvable -- the Agent SDK cells cannot authenticate.
  echo claude-cli-unresolvable > "%BASE%\chain-step2.FAILED"
  goto :end
)
rem (b) Port 33221 must be free (findstr exit code is the pipe's LAST stage = what we want).
netstat -ano -p tcp | findstr "LISTENING" | findstr ":%PORT% " >nul
if not errorlevel 1 (
  echo [%date% %time%] FATAL: port %PORT% is already LISTENING -- stop the other stack first.>> "%LOG%"
  echo FATAL: port %PORT% already in use.
  echo port-%PORT%-busy > "%BASE%\chain-step2.FAILED"
  goto :end
)
rem (c) All 3 dataset dirs + corpus-dir subdirs + queries.json must exist.
for %%X in ("%DS1%" "%DS2%" "%DS3%") do call :check_dataset %%X
if exist "%BASE%\chain-step2.FAILED" ( echo FATAL: missing dataset input, see log. & goto :end )
echo [%date% %time%] STEP 0 preflight OK>> "%LOG%"

rem --- One MCP config for condition B (deferred exposure; "type":"http" is
rem     MANDATORY -- a url-only entry is silently dropped, F-027).
> "%MCP%" echo {"mcpServers":{"justsearch":{"type":"http","url":"http://127.0.0.1:%PORT%/mcp"}}}

rem ======================= per-dataset running guard =========================
for %%X in ("%DS1%" "%DS2%" "%DS3%") do call :dataset %%X

if exist "%BASE%\chain-step2.FAILED" goto :end
if exist "%BASE%\chain-step2.BUDGET-ABORT" goto :end
echo [%date% %time%] chain-step2 END>> "%LOG%"
echo DONE > "%BASE%\chain-step2.done"
goto :end

rem ===========================================================================
rem  check_dataset (STEP 0c): verify a dataset's inputs exist. %~1="<cell>|<member>"
rem ===========================================================================
:check_dataset
for /f "tokens=1,2 delims=|" %%a in ("%~1") do (set "CELL=%%a")
set "DSDIR=%WT%\datasets\mixed\!CELL!"
if not exist "!DSDIR!\corpus-dir\" ( echo [%date% %time%] MISSING corpus-dir: !DSDIR!\corpus-dir>> "%LOG%" & echo missing-corpus-dir-!CELL! > "%BASE%\chain-step2.FAILED" )
if not exist "!DSDIR!\queries.json" ( echo [%date% %time%] MISSING queries.json: !DSDIR!\queries.json>> "%LOG%" & echo missing-queries-!CELL! > "%BASE%\chain-step2.FAILED" )
goto :eof

rem ===========================================================================
rem  dataset: serve+ingest ONCE -> calibrate -> guard -> utility-run -> teardown
rem  %~1 = "<cell>|<member>"
rem ===========================================================================
:dataset
if exist "%BASE%\chain-step2.FAILED" goto :eof
if exist "%BASE%\chain-step2.BUDGET-ABORT" goto :eof
for /f "tokens=1,2 delims=|" %%a in ("%~1") do (set "CELL=%%a" & set "MEMBER=%%b")
set "SLUG=mixed/!CELL!"
set "OUT=%BASE%\!CELL!"
set "CORPUSDIR=%WT%\datasets\mixed\!CELL!\corpus-dir"
set "QUERIES=%WT%\datasets\mixed\!CELL!\queries.json"
set "CERT=%JS%\707-corpora\!MEMBER!\structural-certification.v1.json"
set "LOGDIR=!OUT!\logs"
set "RUNOUT=!OUT!\out"
if not exist "!OUT!" mkdir "!OUT!"
echo [%date% %time%] DATASET !CELL! START>> "%LOG%"

rem Resume: fully-done dataset -> skip (no serve, no spend).
if exist "!OUT!\run.done" (
  echo [%date% %time%] DATASET !CELL! SKIP - run.done present>> "%LOG%"
  goto :eof
)

rem --- backend up (clean) + ingest ONCE (embed/splade/chunks/NER + embed-compat settle)
call :serve_up !CELL! "!OUT!"
if not "!SERVE_RC!"=="0" ( call :fail "serve_up !CELL!" & goto :eof )
call :ingest !CELL! "!CORPUSDIR!" "!SLUG!" "!OUT!"
if not "!ING_RC!"=="0" ( call :serve_down !CELL! "!OUT!" & call :fail "ingest !CELL!" & goto :eof )

rem --- corpus-signature preflight (declared default) -> writes !OUT!\corpus-args.txt
python step2-corpus-args.py --corpus-dir "!CORPUSDIR!" --cert "!CERT!" --dataset "!SLUG!" --mode %CERTMODE% --out "!OUT!\corpus-args.txt" >> "%LOG%" 2>&1
set "PRC=!errorlevel!"
echo rc=!PRC! > "%MK%\preflight-!CELL!.marker"
if not "!PRC!"=="0" ( call :serve_down !CELL! "!OUT!" & call :fail "corpus-preflight !CELL! (%CERTMODE%)" & goto :eof )
set "CORPUS_ARGS="
set /p CORPUS_ARGS=<"!OUT!\corpus-args.txt"

rem --- calibrate (skip on resume if already present) against the LIVE ingest
if exist "!OUT!\calibration.json" (
  echo [%date% %time%] calibrate !CELL! SKIP - calibration.json present>> "%LOG%"
) else (
  echo [%date% %time%] calibrate !CELL! START>> "%LOG%"
  python -m jseval utility-calibrate --queries "!QUERIES!" --corpus-dir "!CORPUSDIR!" --mcp-config "%MCP%" --base-url %BASEURL% --model %MODEL% --conditions %CONDS% --seeds %SEEDS% --concurrency %CONC% --output "!OUT!\calibration.json" >> "%LOG%" 2>&1
  set "CRC=!errorlevel!"
  echo rc=!CRC! > "%MK%\calibrate-!CELL!.marker"
  if not "!CRC!"=="0" ( del "!OUT!\calibration.json" 2>nul & call :serve_down !CELL! "!OUT!" & call :fail "calibrate !CELL! rc=!CRC! (readiness/pilot; calib removed so resume re-attempts)" & goto :eof )
)

rem --- BUDGET GUARD (running, with extrapolation over datasets not yet calibrated)
echo [%date% %time%] budget guard !CELL!>> "%LOG%"
python step2-budget-guard.py --glob "%BASE%\*\calibration.json" --cap %CAP% --total %NDATASETS% >> "%LOG%" 2>&1
set "GRC=!errorlevel!"
echo rc=!GRC! > "%MK%\budget-!CELL!.marker"
if not "!GRC!"=="0" (
  call :serve_down !CELL! "!OUT!"
  echo [%date% %time%] BUDGET ABORT at !CELL! -- no further utility-run launched.>> "%LOG%"
  echo budget-abort-at-!CELL! > "%BASE%\chain-step2.BUDGET-ABORT"
  goto :eof
)

rem --- utility-run (resume-safe via --log-dir) on the SAME backend/ingest
echo [%date% %time%] utility-run !CELL! START>> "%LOG%"
python -m jseval utility-run --queries "!QUERIES!" --corpus-dir "!CORPUSDIR!" --mcp-config "%MCP%" --model %MODEL% --conditions %CONDS% --seeds %SEEDS% --concurrency %CONC% --calibration "!OUT!\calibration.json" --dataset "!SLUG!" !CORPUS_ARGS! --contamination-class %CONTAM% --confidence-tier %TIER% --log-dir "!LOGDIR!" --output-dir "!RUNOUT!" >> "%LOG%" 2>&1
set "RRC=!errorlevel!"
echo rc=!RRC! > "%MK%\run-!CELL!.marker"
call :serve_down !CELL! "!OUT!"
if not "!RRC!"=="0" ( call :fail "utility-run !CELL! rc=!RRC! (resume with same --log-dir)" & goto :eof )
echo done > "!OUT!\run.done"
echo [%date% %time%] DATASET !CELL! DONE>> "%LOG%"
goto :eof

rem ===========================================================================
rem  ingest into the LIVE backend + wait for full pipeline readiness. Leaves it up.
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
rem  serve_up: background a clean persistent backend on %PORT%, poll ready.
rem  %1=cell %2=out ; sets SERVE_RC (0 ok).
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
rem Poll up to ~660s (330 * ~2s) for ready or failed (> the 600s health timeout).
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
rem  serve_down: signal the persistent backend to stop (orphan-safe) + wait.
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
echo %~1 > "%BASE%\chain-step2.FAILED"
goto :eof

:end
echo [%date% %time%] chain-step2 EXIT>> "%LOG%"
endlocal

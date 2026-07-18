@echo off
setlocal EnableDelayedExpansion
rem ===========================================================================
rem  Confirmatory campaign - email-1k STRATUM RERUN (incident #5, 2026-07-18).
rem
rem  Why: the v4 chain (launched 23:43 at 079e63e5) found a BANKED
rem  calibration.json for en-email-enron-raw-1k-verbose written 23:33 by launch
rem  attempt 3 at 92ec2e6d, and skipped recalibration. The banked pin embeds
rem  config_cohort_key f1566eff... (git_sha=92ec2e6d) while the other three
rem  strata pinned ab705cf9... (git_sha=079e63e5). utility-recompose correctly
rem  fails closed: "with-tool arms span multiple search configs". The two
rem  intervening commits are eval-harness-only, but a promoted claim must not
rem  rest on a hand-patched identity label -> rerun the stratum cleanly.
rem
rem  Identical pre-registered parameters (tempdoc 624 Confirmatory
rem  pre-registration): haiku, A/B, seeds 3, 20q, conc 6, max-budget 0.50,
rem  root-mode certified identity. FRESH output dir *-r2 (fresh calibration by
rem  construction). Budget guard sums ALL calibrations under tmp/confirm
rem  including the voided v4 email-1k spend - conservative total vs cap $100.
rem
rem  cmd rule: NO unquoted parentheses in echo text inside parenthesized blocks.
rem ===========================================================================

set "WT=F:\justsearch-public\.claude\worktrees\step2-powered"
set "JS=%WT%\scripts\jseval"
set "PYTHONPATH=%JS%"
set "PYTHONUTF8=1"
set "INSPECT_DISPLAY=none"
set "JSEVAL_HEALTH_TIMEOUT_SEC=600"

set "PORT=33221"
set "BASEURL=http://127.0.0.1:%PORT%"
set "MODEL=haiku"
set "CONDS=A,B"
set "SEEDS=3"
set "CONC=6"
set "MAXBUDGET=0.50"
set "CAP=100"
set "CONTAM=private-synthetic"
set "TIER=C"

set "BASE=%JS%\tmp\confirm"
set "MK=%BASE%\markers"
set "MCP=%BASE%\mcp-config.json"
set "LOG=%BASE%\chain-confirm-r1.log"

set "CELL=en-email-enron-raw-1k-verbose"
set "MEMBER=en-email-enron-raw"
set "SIG=fba5cf691ce88e7689fed5c19b1a68072004beb67a5a964c4e52dea21a8649b7"
set "SLUG=mixed/%CELL%"
set "OUT=%BASE%\%CELL%-r2"
set "ROOT=%WT%\datasets\mixed\%CELL%"
set "CORPUSDIR=%ROOT%\corpus-dir"
set "QUERIES=%ROOT%\queries.json"
set "CERT=%JS%\707-corpora\%MEMBER%\structural-certification.v1.json"
set "LOGDIR=%OUT%\logs"
set "RUNOUT=%OUT%\out"

if not exist "%OUT%" mkdir "%OUT%"
cd /d "%JS%"

echo [%date% %time%] chain-confirm-r1 START rerun of %CELL% at HEAD> "%LOG%"

rem ======================= STEP 0 : launch preflight =========================
python phase2-cli-check.py >> "%LOG%" 2>&1
if errorlevel 1 (
  echo [%date% %time%] FATAL: claude CLI not resolvable - see log.>> "%LOG%"
  echo claude-cli-unresolvable > "%BASE%\chain-confirm-r1.FAILED"
  goto :end
)
netstat -ano -p tcp | findstr "LISTENING" | findstr ":%PORT% " >nul
if not errorlevel 1 (
  echo [%date% %time%] FATAL: port %PORT% already LISTENING - stop the other stack first.>> "%LOG%"
  echo port-%PORT%-busy > "%BASE%\chain-confirm-r1.FAILED"
  goto :end
)
if not exist "%CORPUSDIR%\" (
  echo [%date% %time%] MISSING corpus-dir - %CORPUSDIR%>> "%LOG%"
  echo missing-corpus-dir > "%BASE%\chain-confirm-r1.FAILED"
  goto :end
)
if not exist "%QUERIES%" (
  echo [%date% %time%] MISSING queries.json - %QUERIES%>> "%LOG%"
  echo missing-queries > "%BASE%\chain-confirm-r1.FAILED"
  goto :end
)
if not exist "%MCP%" (
  > "%MCP%" echo {"mcpServers":{"justsearch":{"type":"http","url":"http://127.0.0.1:%PORT%/mcp"}}}
)
echo [%date% %time%] STEP 0 preflight OK>> "%LOG%"

rem ======================= serve up ==========================================
set "RDY=%MK%\serve-%CELL%-r2.ready"
set "STOP=%MK%\serve-%CELL%-r2.stop"
set "STOPPED=%MK%\serve-%CELL%-r2.stopped"
set "SFAILED=%MK%\serve-%CELL%-r2.failed"
del "%RDY%" "%STOP%" "%STOPPED%" "%SFAILED%" 2>nul
echo [%date% %time%] serve_up START>> "%LOG%"
start "serve-%CELL%-r2" /b cmd /c "python serve-eval-backend.py --port %PORT% --clean --ready-file "%RDY%" --stop-file "%STOP%" --stopped-file "%STOPPED%" --failed-file "%SFAILED%" > "%OUT%\serve.log" 2>&1"
set /a _i=0
:serve_up_poll
if exist "%RDY%" goto :serve_ok
if exist "%SFAILED%" (
  echo [%date% %time%] serve_up FAILED - see serve.log>> "%LOG%"
  echo serve-up-failed > "%BASE%\chain-confirm-r1.FAILED"
  goto :end
)
set /a _i+=1
if !_i! GEQ 330 (
  echo [%date% %time%] serve_up TIMEOUT>> "%LOG%"
  echo serve-up-timeout > "%BASE%\chain-confirm-r1.FAILED"
  goto :end
)
ping -n 3 127.0.0.1 >nul
goto :serve_up_poll
:serve_ok
echo rc=0 > "%MK%\serve-up-%CELL%-r2.marker"

rem ======================= ingest ============================================
echo [%date% %time%] ingest START>> "%LOG%"
python -m jseval run --base-url %BASEURL% --dataset "%SLUG%" --corpus-dir "%CORPUSDIR%" --modes lexical --max-queries 1 --pipeline --allow-errors --allow-degraded >> "%LOG%" 2>&1
set "ING_RC=!errorlevel!"
echo rc=!ING_RC! > "%MK%\ingest-%CELL%-r2.marker"
echo [%date% %time%] ingest rc=!ING_RC!>> "%LOG%"
if not "!ING_RC!"=="0" ( call :serve_down & echo ingest-failed > "%BASE%\chain-confirm-r1.FAILED" & goto :end )

rem ======================= FRESH calibrate ===================================
echo [%date% %time%] calibrate START>> "%LOG%"
python -m jseval utility-calibrate --queries "%QUERIES%" --corpus-dir "%CORPUSDIR%" --mcp-config "%MCP%" --base-url %BASEURL% --model %MODEL% --conditions %CONDS% --seeds %SEEDS% --concurrency %CONC% --output "%OUT%\calibration.json" >> "%LOG%" 2>&1
set "CRC=!errorlevel!"
echo rc=!CRC! > "%MK%\calibrate-%CELL%-r2.marker"
if not "!CRC!"=="0" ( del "%OUT%\calibration.json" 2>nul & call :serve_down & echo calibrate-failed > "%BASE%\chain-confirm-r1.FAILED" & goto :end )

rem ======================= budget guard - ALL calibrations vs cap ============
echo [%date% %time%] budget guard>> "%LOG%"
python step2-budget-guard.py --glob "%BASE%\*\calibration.json" --cap %CAP% --total 5 >> "%LOG%" 2>&1
set "GRC=!errorlevel!"
echo rc=!GRC! > "%MK%\budget-%CELL%-r2.marker"
if not "!GRC!"=="0" (
  call :serve_down
  echo [%date% %time%] BUDGET ABORT - rerun would exceed cap.>> "%LOG%"
  echo budget-abort > "%BASE%\chain-confirm-r1.BUDGET-ABORT"
  goto :end
)

rem ======================= utility-run - ROOT mode ===========================
echo [%date% %time%] utility-run START>> "%LOG%"
python -m jseval utility-run --queries "%QUERIES%" --corpus-dir "%CORPUSDIR%" --corpus-root "%ROOT%" --corpus-certification "%CERT%" --corpus-signature %SIG% --mcp-config "%MCP%" --model %MODEL% --conditions %CONDS% --seeds %SEEDS% --concurrency %CONC% --max-budget %MAXBUDGET% --calibration "%OUT%\calibration.json" --dataset "%SLUG%" --contamination-class %CONTAM% --confidence-tier %TIER% --log-dir "%LOGDIR%" --output-dir "%RUNOUT%" >> "%LOG%" 2>&1
set "RRC=!errorlevel!"
echo rc=!RRC! > "%MK%\run-%CELL%-r2.marker"
call :serve_down
if not "!RRC!"=="0" ( echo utility-run-rc-!RRC! > "%BASE%\chain-confirm-r1.FAILED" & goto :end )
echo done > "%OUT%\run.done"
echo [%date% %time%] RUN DONE>> "%LOG%"
echo [%date% %time%] chain-confirm-r1 END - rerun complete; recompose with -r2 logs next.>> "%LOG%"
echo DONE > "%BASE%\chain-confirm-r1.done"
goto :end

:serve_down
echo [%date% %time%] serve_down START>> "%LOG%"
echo stop > "%STOP%"
set /a _j=0
:serve_down_poll
if exist "%STOPPED%" goto :serve_down_done
set /a _j+=1
if !_j! GEQ 60 (
  echo [%date% %time%] serve_down TIMEOUT - backend may need manual kill>> "%LOG%"
  goto :serve_down_done
)
ping -n 3 127.0.0.1 >nul
goto :serve_down_poll
:serve_down_done
echo rc=0 > "%MK%\serve-down-%CELL%-r2.marker"
goto :eof

:end
echo [%date% %time%] chain-confirm-r1 EXIT>> "%LOG%"
endlocal

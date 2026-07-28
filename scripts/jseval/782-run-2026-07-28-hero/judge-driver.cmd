@echo off
cd /d F:\justsearch-public\.claude\worktrees\pub-hero
set PYTHONPATH=F:\justsearch-public\.claude\worktrees\pub-hero\scripts\jseval
set PYTHONUTF8=1
set INSPECT_DISPLAY=none
set RUN=tmp\782-run-2026-07-28b-hero
del "%RUN%\judge-driver.status" 2>nul
for %%c in (en-email-enron-raw-1k-verbose en-email-enron-raw-10k-verbose en-legal-clerc-1k-verbose) do (
  echo START %%c >> "%RUN%\judge-driver.status"
  python -m jseval utility-judge "%RUN%\%%c\logs" --judge-url http://127.0.0.1:33221 --contamination-class private-synthetic --confidence-tier C >> "%RUN%\judge-driver.log" 2>&1
  if errorlevel 1 (
    echo FAILED %%c >> "%RUN%\judge-driver.status"
    exit /b 1
  )
  echo OK %%c >> "%RUN%\judge-driver.status"
)
echo DONE >> "%RUN%\judge-driver.status"

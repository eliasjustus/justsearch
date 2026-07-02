"""Throwaway, uncommitted driver (tempdoc 624 judge-scoring-gap follow-up, 2026-07-02).

Starts the jseval eval backend with LLM inference enabled (mirrors `jseval run
--start-backend --llm`), waits for readiness (backend.start_backend already blocks
until `/api/inference/status` reports `mode=online`), runs
`_leak_free_judged_recompose.run()` against the live judge, then stops the backend
in a `finally` -- single process, no cross-command backend lifecycle split.

Judge URL: the Head API's OWN base_url (`http://127.0.0.1:33221` for jseval's eval
backend), NOT llama-server's raw ephemeral port. Verified via
`modules/ui/src/main/java/io/justsearch/ui/api/OpenAiCompatController.java` (mounted
on the same Javalin app as `/api/status` in `LocalApiServer.java:568-569`): the Head
proxies `POST /v1/chat/completions` / `GET /v1/models` to whatever port llama-server
actually bound, specifically so callers don't have to discover that ephemeral port
themselves (its own docstring names this as the reason it exists).

Run from `scripts/jseval/`: `python _run_judge_with_backend.py`
"""
from __future__ import annotations

import logging
import sys
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

from jseval import backend as be

import _leak_free_judged_recompose as lfj

JUDGE_URL = "http://127.0.0.1:33221"

# This worktree's native-bin/llama-server/ was never staged (only `dev-runner.cjs`
# auto-stages it; `backend.start_backend` runs bare `gradlew runHeadlessEval`, which
# does not -- tempdoc 618 §3 / 656). Self-staged locally via
# `./gradlew.bat :modules:ui:stageLlamaServerFromPrebuilt` (CPU baseline, no
# cross-worktree reach) into `modules/ui/build/llama-server/stage/`.
_REPO_ROOT = Path(__file__).resolve().parents[2]
_SERVER_EXE = _REPO_ROOT / "modules" / "ui" / "build" / "llama-server" / "stage" / "llama-server.exe"


def main():
    if not _SERVER_EXE.is_file():
        raise FileNotFoundError(
            f"{_SERVER_EXE} not found -- run "
            "`./gradlew.bat :modules:ui:stageLlamaServerFromPrebuilt` first")
    info = be.start_backend(llm=True, port=33221,
                             env_overrides={"JUSTSEARCH_SERVER_EXE": str(_SERVER_EXE)})
    try:
        lfj.run(JUDGE_URL)
    finally:
        be.stop_backend(info.proc)


if __name__ == "__main__":
    sys.exit(main() or 0)

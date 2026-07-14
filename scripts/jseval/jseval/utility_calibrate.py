"""Pre-run calibration + readiness pass for the agent-utility eval (tempdoc 624).

The floor run committed $30 + 40 min blind: it ran on a degraded index, picked
timeout/concurrency from a guess (→ 26 % timeout), and labelled the search cohort
by hand. This module is the cheap pre-pass that *conditions* the expensive run —
the agent-eval instance of the `calibrate`/`readiness`/`preflight` seam:

1. **Readiness gate** — reuse `readiness.check_search_ready` (dense+sparse).
2. **Pin the real `config_cohort_key`** — reuse `manifest` snapshots +
   `release.config_cohort_key` (confidence pass #3 B3: `/api/debug/commit-metadata`,
   NOT `/api/status`, which carries the corpus-dependent fps).
3. **Pilot at the *target* concurrency** → `timeout ≈ 2× contended-p95`
   (confidence pass #3 B1: a *low*-concurrency pilot underestimates ~1.8×).
4. **Closed-book filter** — drop the memorizable queries (confidence pass #3 B2:
   the kept set ~doubles the C-beats-A signal).

The output is a `calibration` dict that `jseval utility-run --calibration` consumes.
"""

from __future__ import annotations

import concurrent.futures
import json
import subprocess
import tempfile
from pathlib import Path

import httpx

from jseval.types import ReadinessResult


def _normalize_root_path(path: str) -> str:
    """Normalize a filesystem path for watched-root scope comparison.

    Watched-root paths (from the Worker/Head) and the eval's own `corpus_dir` can differ
    in separator style, trailing separator, or case (Windows paths are case-insensitive) while
    still denoting the same directory. Resolve to an absolute path and case-fold so
    `C:\\foo\\bar`, `c:/foo/bar/`, and `C:\\Foo\\Bar\\` all compare equal.
    """
    return str(Path(path).resolve()).rstrip("\\/").lower()


def check_watched_roots_scoped(
    base_url: str, corpus_dir: str, *, timeout_sec: float = 15.0
) -> ReadinessResult:
    """Fail loudly if any currently-watched root is broader than the corpus's own `corpus_dir`.

    Watched roots are accretive and persistent (`RootLifecycleOps.addWatchedRoot`,
    `WatchedRootsState` — modules/app-services/.../worker/): once added, a root stays watched
    until explicitly removed via `DELETE /api/indexing/roots`; nothing auto-narrows or collapses
    overlapping roots. The Worker's file-tree walk only scans strictly downward from each watched
    root, so a *broader* root (e.g. a corpus's parent directory, added once and never removed)
    keeps indexing everything beneath it — including sibling gold-answer-key files — for the rest
    of that dataDir's life, invisibly. This was the confirmed root cause of a real answer-key leak
    into eval results (tempdoc 624 As-built #7, `golden/synth-scan-v1`).

    Queries `GET /api/indexing/roots` (`IndexingController.handleListRoots`,
    `{"roots": [{"collection", "path", "fileCount", "lastIndexed"?}, ...]}`) and compares each
    watched root's path against `corpus_dir`. Does NOT attempt to remove any stray root —
    fail-and-report only, so an operator can inspect and remove it deliberately.
    """
    expected = _normalize_root_path(corpus_dir)
    try:
        with httpx.Client(base_url=base_url, timeout=timeout_sec) as client:
            resp = client.get("/api/indexing/roots")
            resp.raise_for_status()
            payload = resp.json()
    except Exception as e:
        return ReadinessResult(
            passed=False,
            failure_reasons=[f"watched_roots_endpoint_unreachable: {type(e).__name__}: {e}"],
        )

    roots = payload.get("roots", [])
    stray = []
    seen_stray = set()
    for r in roots:
        raw_path = r.get("path")
        if not raw_path:
            continue
        if _normalize_root_path(raw_path) != expected and raw_path not in seen_stray:
            stray.append(raw_path)
            seen_stray.add(raw_path)

    if stray:
        stray_list = "\n".join(f"  - {p}" for p in stray)
        message = (
            f"Watched-roots scope check FAILED: {len(stray)} watched root(s) do not match the "
            f"corpus's own corpus-dir ({corpus_dir!r}). Watched roots are accretive and never "
            f"auto-narrowed (RootLifecycleOps.addWatchedRoot) — a broader/stale root keeps indexing "
            f"everything beneath it, including sibling gold-answer-key files, for the rest of this "
            f"dataDir's life. Remove the stray root(s) below via `DELETE /api/indexing/roots` before "
            f"running the eval, then re-run readiness:\n{stray_list}"
        )
        return ReadinessResult(
            passed=False,
            failure_reasons=["stray_watched_root", message],
            snapshot={"roots": roots},
        )

    return ReadinessResult(passed=True, snapshot={"roots": roots})


class StrayWatchedRootError(RuntimeError):
    """A live eval run detected a watched root broader than its own `corpus_dir`.

    Raised by `assert_watched_roots_scoped` — the automatic-prevention call site wired
    directly into `run_utility_eval` (agent_utility_inspect.py), the record-grade
    function that actually EXECUTEs an eval (the classic `run_agent_eval` that also
    shared this gate was retired in tempdoc 675). Before this,
    `check_watched_roots_scoped` was only reachable via the separate, optional
    `utility-calibrate` CLI — an eval could run (and silently leak) without ever going
    through it. See `check_watched_roots_scoped`'s docstring for the underlying
    mechanism (tempdoc 624 As-built #7).
    """


def assert_watched_roots_scoped(base_url: str, corpus_dir: str, *, timeout_sec: float = 15.0) -> None:
    """Run `check_watched_roots_scoped` and raise `StrayWatchedRootError` on failure.

    Unlike `utility-calibrate` (optional, report-only), any code path that is about to
    actually run an eval against `base_url` must abort here rather than warn-and-continue
    — a stray root silently serves leaked content for the rest of the run.
    """
    result = check_watched_roots_scoped(base_url, corpus_dir, timeout_sec=timeout_sec)
    if not result.passed:
        raise StrayWatchedRootError("; ".join(result.failure_reasons))


def base_url_from_mcp_config(mcp_config_path: str) -> str | None:
    """Derive the JustSearch backend's base_url from an eval's `--mcp-config` file.

    `run_utility_eval` does not take its own `--base-url` option — the backend address
    it actually needs for the watched-roots safety check is already
    carried by the `--mcp-config` file the agent session uses for its own MCP
    transport: `{"mcpServers":{"justsearch":{"type":"http","url":"http://127.0.0.1:PORT/mcp"}}}`
    (see `util-smoke/README.md`). **The `"type":"http"` field is mandatory** — a `url`-only
    entry is silently DROPPED by the `claude` CLI (see `assert_mcp_config_http_typed`), so an
    omitted `"type"` is not just a documentation nit here, it is the exact shape that produces
    a dead config. Deriving the base_url here — instead of adding a second, possibly divergent
    base-url flag — guarantees the roots check always targets the SAME backend the agent under
    test is configured to talk to.

    Returns None if the file is missing/malformed or doesn't carry a `justsearch` server
    `url` (e.g. condition A's `{"mcpServers":{}}` empty config) — callers should treat
    that as "no search backend is in play for this call," not "verified clean."
    """
    try:
        cfg = json.loads(Path(mcp_config_path).read_text(encoding="utf-8"))
        url = cfg["mcpServers"]["justsearch"]["url"]
    except Exception:
        return None
    if not isinstance(url, str) or not url:
        return None
    return url[: -len("/mcp")] if url.endswith("/mcp") else url.rstrip("/")


class McpConfigMissingTypeError(ValueError):
    """A `--mcp-config` file has an `mcpServers` entry with a `url` but no `type` field.

    Proven (2026-07-03 A/B probe, tempdoc 624 battlefield retrospective): the `claude` CLI
    SILENTLY DROPS an `mcpServers` entry shaped `{"url": "..."}` without `"type": "http"` —
    the init event reports `mcp_servers: []` and 0 `mcp__`-prefixed tools, with no warning
    and a clean exit code. Every condition B/C battlefield cell that used this shape ran with
    zero MCP tool calls, indistinguishable from a healthy run by exit code or stdout length
    alone. Fix: add `"type": "http"` to the server entry.
    """


class McpConfigInvalidAlwaysLoadError(ValueError):
    """A `--mcp-config` server entry's `alwaysLoad` key is present but not a JSON boolean.

    `alwaysLoad` (tempdoc 725 increment 2/4) is the harness-side signal
    `_derive_exposure_mode` reads to decide eager vs. deferred exposure —
    `agent_utility_inspect._capture_exposure_config` reads it straight off the parsed
    config with `bool(raw_always_load) if raw_always_load is not None else None`, and
    `_derive_exposure_mode`'s own check is `always_load is True` (an identity check, not
    a truthiness check upstream of that cast). A string `"true"`, an int `1`, or any
    other non-bool JSON value would silently take a DIFFERENT path through that logic
    than a real `true` literal — the exposure identity recorded for the campaign could
    disagree with what the config author intended, undetected. Fail closed instead of
    letting a typo mismeasure the eager/deferred arm.
    """


def assert_mcp_config_http_typed(mcp_config_path: str) -> None:
    """Raise `McpConfigMissingTypeError` if `mcp_config_path` carries an `mcpServers` entry
    with a `url` but no `type` — the exact shape the `claude` CLI silently drops (see
    `McpConfigMissingTypeError`'s docstring). Called up front by any code path about to
    actually hand this config to a `claude` subprocess (`run_utility_eval`), so a dead
    config aborts the run immediately instead of producing 0-tool-call cells that read as
    healthy.

    Also raises `McpConfigInvalidAlwaysLoadError` (tempdoc 725 increment 4) if any server
    entry carries an `alwaysLoad` key whose value is not a JSON boolean — see that error's
    docstring for why a non-bool value must fail closed rather than degrade.

    A missing/malformed config file, a config with no `mcpServers` key, an empty
    `mcpServers` (condition A's `{"mcpServers":{}}`), or a command-style entry
    (`{"command": ..., "args": [...]}`, no `url`) is NOT an error here — this guards only
    the specific silent-drop shape (`url` present, `type` absent) plus the `alwaysLoad`
    type check above.
    """
    try:
        cfg = json.loads(Path(mcp_config_path).read_text(encoding="utf-8"))
    except Exception:
        return  # missing/malformed config file: not this function's concern
    servers = cfg.get("mcpServers") if isinstance(cfg, dict) else None
    if not isinstance(servers, dict):
        return
    for name, entry in servers.items():
        if not isinstance(entry, dict):
            continue
        if "alwaysLoad" in entry and not isinstance(entry["alwaysLoad"], bool):
            raise McpConfigInvalidAlwaysLoadError(
                f"mcp_config {mcp_config_path!r} server {name!r} has a non-boolean "
                f"`alwaysLoad` ({entry['alwaysLoad']!r}, type "
                f"{type(entry['alwaysLoad']).__name__}) -- alwaysLoad must be a JSON "
                "boolean (true/false) or omitted entirely. Fix: set it to `true`/`false`, "
                f"e.g. {{\"mcpServers\":{{{name!r}:{{\"alwaysLoad\":true}}}}}}."
            )
        if "url" in entry and "type" not in entry:
            raise McpConfigMissingTypeError(
                f"mcp_config {mcp_config_path!r} server {name!r} has a `url` but no `type` "
                "-- the `claude` CLI silently DROPS this entry (proven: url-only -> init "
                "event mcp_servers=[], 0 tools, clean exit). Fix: add `\"type\": \"http\"` "
                f"to the server entry, e.g. "
                f'{{"mcpServers":{{{name!r}:{{"type":"http","url":{entry.get("url")!r}}}}}}}.'
            )


def check_readiness(
    base_url: str, corpus_dir: str, *, require_dense: bool = True, timeout_sec: float = 15.0
) -> ReadinessResult:
    """Reuse `readiness.check_search_ready` (dense+sparse) AND verify watched-roots scope.

    A dense+sparse-ready index that is scoped to a stray/broader watched root is not actually
    ready for a clean eval run — it can silently serve leaked content (tempdoc 624 As-built #7).
    """
    from jseval.readiness import check_search_ready
    search_rd = check_search_ready(
        base_url, dense_enabled=require_dense, splade_enabled=True, timeout_sec=timeout_sec)
    roots_rd = check_watched_roots_scoped(base_url, corpus_dir, timeout_sec=timeout_sec)
    return ReadinessResult(
        passed=search_rd.passed and roots_rd.passed,
        failure_reasons=[*search_rd.failure_reasons, *roots_rd.failure_reasons],
        snapshot={**search_rd.snapshot, **roots_rd.snapshot},
    )


def pin_config_cohort_key(base_url: str) -> tuple[str | None, dict]:
    """The *real* search backend `config_cohort_key` (B3) — reuse the manifest seam.

    Builds the config-global slice of a manifest from the live `/api/debug/commit-metadata`
    + session-policies + model snapshots and hashes it with `release.config_cohort_key`.
    """
    from jseval import manifest as mf
    from jseval import release
    from jseval.run import _snapshot_models

    snaps = mf.capture_state_snapshots(base_url)
    manifest = {
        "git_sha": mf._git_sha_full(),
        "commit_metadata": mf._normalise_commit_metadata(
            snaps.get("/api/debug/commit-metadata") or {}),
        "policy_hash": mf._sha256_canonical(snaps.get("/api/debug/session-policies") or {}),
        "model_fingerprints": _snapshot_models(base_url) or {},
        "eval_protocol_hash": mf._sha256_canonical({"agent_utility_eval": "v1"}),
    }
    return release.config_cohort_key(manifest), manifest["commit_metadata"]


def calibrate_timeout(pilot_log_dir: str, *, multiplier: float = 2.0,
                      floor_s: int = 120, ceil_s: int = 600) -> int:
    """timeout ≈ multiplier × the pilot's *contended* p95 (B1), clamped to [floor, ceil]."""
    from inspect_ai.log import read_eval_log

    times: list[float] = []
    for lf in sorted(Path(pilot_log_dir).glob("*.json")) + sorted(Path(pilot_log_dir).glob("*.eval")):
        if lf.name in ("eval-set.json", "logs.json"):
            continue
        try:
            log = read_eval_log(lf.as_posix())
        except Exception:
            continue
        for s in (log.samples or []):
            if not (s.metadata or {}).get("error") and s.total_time:
                times.append(s.total_time)
    if not times:
        return ceil_s
    s = sorted(times)
    p95 = s[min(len(s) - 1, int(len(s) * 0.95))]
    return int(max(floor_s, min(ceil_s, multiplier * p95)))


def closed_book_filter(queries: list[dict], *, model: str = "haiku",
                       concurrency: int = 8) -> tuple[list[int], int]:
    """Drop the memorizable (closed-book-correct) queries (B2).

    Returns ``(retained_indices, n_dropped)`` — the retained set is exactly the
    *retrieval-relevant* queries (closed-book-WRONG), where C-beats-A is sharpest.
    """
    from jseval.agent_retrieval_eval import _score_answer

    def _cb(i_q):
        i, q = i_q
        prompt = f"Answer this question concisely from your own knowledge. Question: {q['query']}"
        try:
            r = subprocess.run(
                ["claude", "-p", prompt, "--model", model, "--output-format", "json",
                 "--max-budget-usd", "0.10", "--permission-mode", "bypassPermissions"],
                capture_output=True, text=True, timeout=90, cwd=tempfile.mkdtemp())
            d = json.loads(r.stdout or "{}")
            return i, _score_answer(q["answer"], d.get("result", ""))
        except Exception:
            return i, False  # treat failures as retrieval-relevant (keep)

    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as ex:
        res = dict(ex.map(_cb, list(enumerate(queries))))
    retained = [i for i in range(len(queries)) if not res.get(i)]
    return retained, len(queries) - len(retained)


def calibrate(*, base_url: str, queries: list[dict], corpus_dir: str, mcp_config: str | None,
              model: str, concurrency: int, seeds: int, conditions=("A", "C"),
              require_dense: bool = True, pilot_n: int = 5, max_budget: float = 0.50,
              do_closed_book: bool = True) -> dict:
    """Orchestrate the pre-run calibration. Needs the live backend (readiness/pin/pilot)."""
    from jseval import agent_utility_inspect as aui
    from jseval import agent_utility_run as aur

    rd = check_readiness(base_url, corpus_dir, require_dense=require_dense)
    cck, commit_meta = pin_config_cohort_key(base_url)

    # Pilot at the TARGET concurrency (a few queries, both arms) → contended p95 → timeout.
    pilot_dir = tempfile.mkdtemp(prefix="util-pilot-").replace("\\", "/")
    pq = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
    json.dump(queries[:pilot_n], pq); pq.close()
    aui.run_utility_eval(
        queries_path=pq.name, corpus_dir=corpus_dir, mcp_config=mcp_config, model=model,
        conditions=conditions, seeds=1, concurrency=concurrency, log_dir=pilot_dir,
        max_queries=pilot_n, max_budget=max_budget, cli_version=aur.claude_cli_version(),
        corpus_dataset="pilot", corpus_signature="pilot")
    timeout_s = calibrate_timeout(pilot_dir)

    retained, n_dropped = ([list(range(len(queries))), 0])
    if do_closed_book:
        retained, n_dropped = closed_book_filter(queries, model=model, concurrency=concurrency)

    # Per-cell cost estimate from the pilot → project the full matrix.
    from jseval.utility_governance import compute_loss_accounting
    pilot_arms = compute_loss_accounting(pilot_dir)
    per_cell_cost = 0.12  # fallback
    summ = aur.eval_logs_to_summaries(pilot_dir)
    costs = [v["cost_usd"] for s in summ for v in s["per_query"].values() if v.get("cost_usd")]
    if costs:
        per_cell_cost = sum(costs) / len(costs)
    n_cells = len(retained) * len(conditions) * seeds
    return {
        "readiness_passed": rd.passed,
        "readiness_reasons": rd.failure_reasons,
        "config_cohort_key": cck,
        "timeout_s": timeout_s,
        "concurrency": concurrency,
        "retained_query_indices": retained,
        "n_dropped_contaminated": n_dropped,
        "n_cells": n_cells,
        "cost_estimate_usd": round(per_cell_cost * n_cells, 2),
        "time_estimate_min": round(n_cells * (timeout_s / 2) / max(concurrency, 1) / 60, 1),
    }

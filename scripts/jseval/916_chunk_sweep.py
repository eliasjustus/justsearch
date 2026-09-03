#!/usr/bin/env python
"""Tempdoc 916 — chunk-size sweep driver (12 arms, every arm a full reindex).

Sibling of `916_collapse_ab.py`, and deliberately the same shape: argparse
`run`/`analyze`, a machine `signature()` before AND after every arm, `child_env()`,
an `arm()`-style subprocess wrapper writing a per-arm log, and the SAME
admissibility rule — an arm counts only if `ce_coverage.verdict == "ok"` AND
`per_mode.<mode>.comparable is true`. A void arm is printed `**VOID**`, never
averaged in. Above the 2% `ce_coverage` tolerance on `mixed/legal-clerc-200` a
degraded arm is biased **upward** (F-056 finding 4), so this is a hard filter.

**What differs from the A/B driver.** Chunk size is a fingerprint input, so there
is no shared index to hold still: `916_collapse_ab.py` builds once and runs every
arm `--skip-ingest`, whereas here every arm is a full `--clean --pipeline`
reindex. That makes the sweep long and interruptible, so the driver is resumable
(`ARM.done` per arm, `CORPUS.done`, `RUN.done`) and detached-driver friendly
(append-only `signatures.jsonl` + `progress.jsonl`).

**Arms.** `{128,256,384,500} x {0,25,50}` = 12, each with a scaled
`min_tokens = max(1, target // 5)`. Scaling is required, not cosmetic: with the
shipped `min_tokens = 100`, `ChunkSplitter` floors the stride at
`max(chunkLength - overlapChars, minChars)`, so at target 128 a requested
50-token overlap is silently suppressed and the 128/25 and 128/50 arms collapse
onto nearly the same boundaries — measured in
`modules/indexing/src/test/java/io/justsearch/indexing/chunking/ChunkingPolicyTest.java:99-144`.
At target 500, `500 // 5 == 100` reproduces the incumbent exactly, so the
incumbent arm is unchanged by the scaling.

`JUSTSEARCH_CHUNKING_SWEEP_THRESHOLD_CHARS` exists (`EnvRegistry.java:1343`) but
is HELD FIXED at its default across all 12 arms — `--threshold-chars` exposes it
for a one-off, it is not swept.

Usage:
  python 916_chunk_sweep.py run --out <dir> [--corpora a,b] [--reps 1] [--threshold-chars N]
  python 916_chunk_sweep.py analyze --out <dir> [--reps 1] [--floor 0.0068] [--mode hybrid]
"""
import argparse
import datetime
import glob
import io
import json
import os
import statistics
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
MODES = "lexical,vector,splade,hybrid"
DEFAULT_CORPORA = "mixed/enron-qa,mixed/legal-clerc-200"

TARGET_TOKENS = (128, 256, 384, 500)
OVERLAP_TOKENS = (0, 25, 50)

ARM_DONE = "ARM.done"
CORPUS_DONE = "CORPUS.done"
RUN_DONE = "RUN.done"
EVIDENCE_NAME = "splade_truncation_evidence.json"

KEY_TARGET = "JUSTSEARCH_CHUNKING_SWEEP_TARGET_TOKENS"
KEY_OVERLAP = "JUSTSEARCH_CHUNKING_SWEEP_OVERLAP_TOKENS"
KEY_MIN = "JUSTSEARCH_CHUNKING_SWEEP_MIN_TOKENS"
KEY_THRESHOLD = "JUSTSEARCH_CHUNKING_SWEEP_THRESHOLD_CHARS"
KEY_EVIDENCE = "JUSTSEARCH_SPLADE_EVIDENCE_PATH"

METRIC_NDCG = "nDCG@10"
METRIC_R10 = "R@10"
# NOT emitted by jseval: `scoring.DEFAULT_METRICS` is [nDCG@10, AP@10, RR@10, R@10, P@1]
# (`jseval/scoring.py:9`) and nothing overrides it, so `aggregate_metrics["R@50"]` is absent
# on every run today. It is read (and reported as `-`) rather than silently swapped for R@10.
METRIC_R50 = "R@50"

# Statistic keys carried per replicate and averaged per arm.
STAT_KEYS = ("ndcg", "r50", "r10", "leak", "union", "trunc_rate", "index_bytes", "primary_docs_s")


def min_tokens_for(target):
    """Per-arm chunk floor. `max(1, target // 5)`; at target 500 this is the incumbent 100."""
    return max(1, target // 5)


def arm_matrix():
    """The 12 `(target, overlap)` arms, target-major then overlap-ascending."""
    return [(t, o) for t in TARGET_TOKENS for o in OVERLAP_TOKENS]


def arm_label(target, overlap):
    return "%d/%d" % (target, overlap)


def arm_tag(target, overlap, rep):
    return "t%d-o%d-r%d" % (target, overlap, rep)


def arm_yaml(target, overlap, threshold_chars=None, evidence_path=None):
    """A jseval `--config` file whose `env:` block carries the arm's chunking keys."""
    lines = ["env:"]
    lines.append('  %s: "%d"' % (KEY_TARGET, target))
    lines.append('  %s: "%d"' % (KEY_OVERLAP, overlap))
    lines.append('  %s: "%d"' % (KEY_MIN, min_tokens_for(target)))
    if threshold_chars is not None:
        lines.append('  %s: "%d"' % (KEY_THRESHOLD, int(threshold_chars)))
    if evidence_path:
        # Forward slashes: a Windows path in a double-quoted YAML scalar would eat backslashes.
        lines.append('  %s: "%s"' % (KEY_EVIDENCE, str(evidence_path).replace("\\", "/")))
    return "\n".join(lines) + "\n"


def split_csv(raw):
    return [x.strip() for x in (raw or "").split(",") if x.strip()]


def log(m):
    print("[%s] %s" % (datetime.datetime.now().strftime("%H:%M:%S"), m), flush=True)


def touch(path):
    io.open(path, "w", encoding="utf-8").write("done\n")


def child_env():
    e = dict(os.environ)
    # 300s, not jseval's 120s default: a cold worktree backend boot is ~150s and the shorter
    # ceiling lost three arms of a previous campaign to a timeout that measured nothing.
    e.setdefault("JSEVAL_HEALTH_TIMEOUT_SEC", "300")
    return e


def signature(out, tag):
    sig = {"tag": tag, "at": datetime.datetime.now().isoformat()}
    try:
        sig["gpu"] = subprocess.run(
            ["nvidia-smi", "--query-gpu=memory.used,utilization.gpu", "--format=csv,noheader"],
            capture_output=True, text=True, timeout=30).stdout.strip()
    except Exception as exc:                                    # noqa: BLE001 - advisory only
        sig["gpu"] = "ERR %s" % exc
    try:
        sig["games"] = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "(Get-Process | Where-Object {$_.ProcessName -match 'League|Riot|VALORANT|cs2'} "
             "| Select-Object -ExpandProperty ProcessName) -join ','"],
            capture_output=True, text=True, timeout=60).stdout.strip()
    except Exception as exc:                                    # noqa: BLE001 - advisory only
        sig["games"] = "ERR %s" % exc
    with io.open(os.path.join(out, "signatures.jsonl"), "a", encoding="utf-8") as fh:
        fh.write(json.dumps(sig) + "\n")
    log("sig %-30s gpu=%-16s games=%s" % (tag, sig.get("gpu"), sig.get("games") or "none"))


def progress(out, record):
    with io.open(os.path.join(out, "progress.jsonl"), "a", encoding="utf-8") as fh:
        fh.write(json.dumps(record) + "\n")


def _subprocess_runner(cmd, cwd, log_path, env):
    with io.open(log_path, "w", encoding="utf-8", errors="replace") as fh:
        return subprocess.run(cmd, cwd=cwd, stdout=fh, stderr=subprocess.STDOUT,
                              text=True, env=env).returncode


def run_arm(out, corpus, target, overlap, rep, threshold_chars=None, runner=None):
    """Run one arm. Returns the exit code, or None when the arm was already done.

    Resumable: an arm dir holding `ARM.done` is skipped without spawning anything,
    and `ARM.done` is written only after a zero exit — so a killed overnight driver
    restarts on the first arm that did not finish.
    """
    slug = corpus.replace("/", "_")
    tag = arm_tag(target, overlap, rep)
    armdir = os.path.join(out, slug, tag)
    os.makedirs(armdir, exist_ok=True)
    started = datetime.datetime.now()
    if os.path.exists(os.path.join(armdir, ARM_DONE)):
        log("SKIP (done) %s / %s" % (slug, tag))
        progress(out, {"arm": tag, "corpus": corpus, "rc": None,
                       "started": started.isoformat(), "finished": started.isoformat(),
                       "seconds": 0.0, "skipped": True})
        return None

    cfg = os.path.join(armdir, "arm.yaml")
    io.open(cfg, "w", encoding="utf-8", newline="").write(
        arm_yaml(target, overlap, threshold_chars, os.path.join(armdir, EVIDENCE_NAME)))
    # Every arm is a full reindex: chunk size is a fingerprint input, so there is no
    # `--skip-ingest` trick and no shared index to hold still.
    cmd = [sys.executable, "-m", "jseval", "run", "--dataset", corpus, "--modes", MODES,
           "--start-backend", "--clean", "--pipeline", "--json",
           "--output-dir", armdir, "--config", cfg]

    signature(out, "%s/%s:pre" % (slug, tag))
    log("ARM %s / %s target=%d overlap=%d min_tokens=%d"
        % (slug, tag, target, overlap, min_tokens_for(target)))
    t0 = time.time()
    rc = (runner or _subprocess_runner)(cmd, HERE, os.path.join(armdir, "arm.log"), child_env())
    seconds = round(time.time() - t0, 1)
    signature(out, "%s/%s:post" % (slug, tag))
    log("ARM %s / %s exit=%s in %.1fs" % (slug, tag, rc, seconds))

    capture_arm_metrics(armdir, target, overlap)
    if rc == 0:
        touch(os.path.join(armdir, ARM_DONE))
    progress(out, {"arm": tag, "corpus": corpus, "rc": rc,
                   "started": started.isoformat(), "finished": datetime.datetime.now().isoformat(),
                   "seconds": seconds, "skipped": False})
    return rc


def _run_dir(armdir):
    """The jseval run directory jseval created under `--output-dir <armdir>` (latest wins)."""
    ps = sorted(glob.glob(os.path.join(armdir, "*", "summary.json")))
    return os.path.dirname(ps[-1]) if ps else None


def _read_json(path):
    if not path or not os.path.exists(path):
        return {}
    try:
        return json.load(io.open(path, encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def load(armdir, mode="hybrid"):
    """One replicate's record, or None when the arm produced no `summary.json`."""
    rd = _run_dir(armdir)
    if not rd:
        return None
    d = _read_json(os.path.join(rd, "summary.json"))
    pm = (d.get("per_mode") or {}).get(mode) or {}
    am = pm.get("aggregate_metrics") or {}
    acc = _read_json(
        os.path.join(rd, "projections", "staged_recall_accounting.json")).get("aggregate") or {}
    trunc = _read_json(os.path.join(rd, "projections", "splade_truncation.json"))
    ingest = d.get("ingest") or {}
    run_metrics = d.get("run_metrics") or {}
    prim = (ingest.get("pipeline_summary") or {}).get("primary_indexing") or {}
    cev = (d.get("ce_coverage") or {}).get("verdict")
    cmp_ = pm.get("comparable")
    return {
        "run_id": os.path.basename(rd),
        "run_dir": rd,
        "ndcg": am.get(METRIC_NDCG),
        "r10": am.get(METRIC_R10),
        # Absent on every run today (see METRIC_R50): recorded as null, never back-filled from R@10.
        "r50": am.get(METRIC_R50),
        "leak": acc.get("leak_rate"),
        "union": acc.get("leg_union_recall"),
        "trunc_rate": trunc.get("truncation_rate"),
        "trunc_available": bool(trunc.get("available")),
        "trunc_reason": trunc.get("reason"),
        # Reported by the backend on /api/status as `indexSizeBytes` and carried into the run
        # summary by `jseval/ingest.py:140` -- so it is read, not summed off disk or guessed.
        "index_bytes": ingest.get("index_size_bytes"),
        "primary_docs_s": run_metrics.get("primary_docs_s", prim.get("docs_per_s")),
        "enrich_docs_s": run_metrics.get("enrich_docs_s", ingest.get("docs_per_sec")),
        "ce_cov": cev,
        "comparable": cmp_,
        "admissible": cev == "ok" and cmp_ is True,
    }


def capture_arm_metrics(armdir, target=None, overlap=None, mode="hybrid"):
    """Write `<armdir>/arm-metrics.json`. Returns the document."""
    rec = load(armdir, mode=mode)
    doc = {
        "target_tokens": target,
        "overlap_tokens": overlap,
        "min_tokens": min_tokens_for(target) if target is not None else None,
        "mode": mode,
        "captured_at": datetime.datetime.now().isoformat(),
    }
    if rec is None:
        doc["note"] = "no summary.json under %s -- arm produced no run" % armdir
        doc.update({k: None for k in STAT_KEYS})
    else:
        doc.update(rec)
    io.open(os.path.join(armdir, "arm-metrics.json"), "w", encoding="utf-8").write(
        json.dumps(doc, indent=2, sort_keys=True) + "\n")
    return doc


def do_run(a):
    os.makedirs(a.out, exist_ok=True)
    for corpus in split_csv(a.corpora):
        slug = corpus.replace("/", "_")
        os.makedirs(os.path.join(a.out, slug), exist_ok=True)
        for target, overlap in arm_matrix():
            for rep in range(a.reps):
                run_arm(a.out, corpus, target, overlap, rep, a.threshold_chars)
        touch(os.path.join(a.out, slug, CORPUS_DONE))
    touch(os.path.join(a.out, RUN_DONE))
    log("RUN COMPLETE")


def mean_sigma(values, floor):
    """`(mean, sigma, sigma_is_floor)` over the non-null values.

    Sigma is the SAMPLE standard deviation (`statistics.stdev`) when n>=2. With a
    single replicate there is no observed spread, so the run-level noise floor is
    reported rather than a fake sigma=0.
    """
    vals = [v for v in values if isinstance(v, (int, float)) and not isinstance(v, bool)]
    if not vals:
        return (None, None, False)
    mean = sum(vals) / len(vals)
    if len(vals) >= 2:
        return (mean, statistics.stdev(vals), False)
    return (mean, floor, True)


def summarize_arm(records, floor):
    """Roll one arm's replicates up. Void replicates are excluded from every mean AND sigma."""
    adm = [r for r in records if r and r.get("admissible")]
    out = {"n_total": len([r for r in records if r]), "n_admissible": len(adm),
           "void": not adm}
    for key in STAT_KEYS:
        mean, sigma, from_floor = mean_sigma([r.get(key) for r in adm], floor)
        out[key] = {"mean": mean, "sigma": sigma, "sigma_is_floor": from_floor}
    src = (adm or [r for r in records if r] or [None])[0]
    out["ce_cov"] = src.get("ce_cov") if src else None
    out["comparable"] = src.get("comparable") if src else None
    return out


def fmt_ms(stat, digits=4):
    if stat["mean"] is None:
        return "-"
    if stat["sigma"] is None:
        return "%.*f" % (digits, stat["mean"])
    return "%.*f +/- %.*f%s" % (digits, stat["mean"], digits, stat["sigma"],
                                "*" if stat["sigma_is_floor"] else "")


def fmt_one(stat, digits=4):
    return "-" if stat["mean"] is None else "%.*f" % (digits, stat["mean"])


def fmt_mb(stat):
    return "-" if stat["mean"] is None else "%.1f" % (stat["mean"] / (1024.0 * 1024.0))


def do_analyze(a):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    corpora = split_csv(a.corpora)
    print("| corpus | arm | n adm | nDCG@10 | R@50 | R@10 | leak | trunc | index MB "
          "| docs/s | ce_cov | comparable | admissible |")
    print("| :-- | :-- | --: | --: | --: | --: | --: | --: | --: | --: | :-- | :-- | :-- |")
    for corpus in corpora:
        slug = corpus.replace("/", "_")
        for target, overlap in arm_matrix():
            records = []
            for rep in range(a.reps):
                records.append(load(os.path.join(a.out, slug, arm_tag(target, overlap, rep)),
                                    mode=a.mode))
            s = summarize_arm(records, a.floor)
            admissible = "**VOID**" if s["void"] else "YES"
            print("| %s | %s | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
                corpus, arm_label(target, overlap), s["n_admissible"],
                fmt_ms(s["ndcg"]), fmt_ms(s["r50"]), fmt_one(s["r10"]), fmt_one(s["leak"]),
                fmt_one(s["trunc_rate"]), fmt_mb(s["index_bytes"]), fmt_one(s["primary_docs_s"], 1),
                s["ce_cov"] or "-", s["comparable"], admissible))
    print("\n`*` = sigma is the --floor noise floor (%.4f), not an observed replicate spread "
          "(n=1)." % a.floor)
    print("`R@50` is `-` on every run: jseval emits [nDCG@10, AP@10, RR@10, R@10, P@1] "
          "(`jseval/scoring.py:9`) and never R@50.")
    print("A `**VOID**` arm has no admissible replicate; void replicates are excluded from "
          "every mean and sigma above.")


def main():
    ap = argparse.ArgumentParser(
        description="Tempdoc 916 chunk-size sweep: 12 (target, overlap) arms, each a full reindex.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("run", "analyze"):
        s = sub.add_parser(name)
        s.add_argument("--out", required=True)
        s.add_argument("--corpora", default=DEFAULT_CORPORA,
                       help="the two chunked English corpora by default")
        s.add_argument("--reps", type=int, default=1,
                       help="replicates per arm; every arm is a full reindex, so 1 by default")
        s.add_argument("--threshold-chars", type=int, default=None,
                       help="%s; HELD FIXED at its default across the sweep -- not an arm axis"
                            % KEY_THRESHOLD)
        s.add_argument("--floor", type=float, default=0.0068,
                       help="run-level noise floor reported as sigma when an arm has n=1")
        s.add_argument("--mode", default="hybrid", help="per_mode key the roll-up reads")
    a = ap.parse_args()
    (do_run if a.cmd == "run" else do_analyze)(a)


if __name__ == "__main__":
    main()

#!/usr/bin/env python
"""Tempdoc 916 §J — committed same-index A/B driver with per-arm admissibility.

This lives under `scripts/jseval/` rather than in gitignored `tmp/` deliberately: the 916 §J review
found that the admissibility filter which decides whether an arm counts was itself untracked, so the
rule could not be audited against the code that enforced it. It is now reviewable.

Admissibility (916 §I.2): an arm counts only if `ce_coverage.verdict == "ok"` AND
`per_mode.<mode>.comparable is true`. A void arm is re-run, never cited, never averaged in.
Above the 2% `ce_coverage` tolerance on `mixed/legal-clerc-200` a degraded arm is biased **upward**
(F-056 finding 4), which is why this is a hard filter and not a warning.

**The lever it was written for is gone.** 916 Part 2's chunk-collapse aggregation was refuted and
reverted (F-056), so no key name is hardcoded here: `--sweep-key` names the env var under test and
`--sweep-values` its arm values. What survives is the reusable part — the admissibility filter, the
per-arm machine-signature record, and a noise-floored delta table.

Usage:
  python 916_collapse_ab.py run --out <dir> --sweep-key JUSTSEARCH_FOO --sweep-values 0.1,0.3 \
      [--fixed-env K=V,K=V] [--corpora a,b] [--reps 2]
  python 916_collapse_ab.py analyze --out <dir> --sweep-values 0.1,0.3 [--floor 0.0068]
"""
import argparse
import datetime
import glob
import io
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MODES = "lexical,vector,splade,hybrid"


def arm_yaml(fixed_env, sweep_key, value):
    """A jseval `--config` file setting the fixed env plus one swept key."""
    lines = ["env:"]
    for pair in fixed_env:
        k, _, v = pair.partition("=")
        lines.append('  %s: "%s"' % (k.strip(), v.strip()))
    lines.append('  %s: "%s"' % (sweep_key, value))
    return "\n".join(lines) + "\n"


def split_values(raw):
    return [x.strip() for x in (raw or "").split(",") if x.strip()]


def log(m):
    print("[%s] %s" % (datetime.datetime.now().strftime("%H:%M:%S"), m), flush=True)


def child_env():
    e = dict(os.environ)
    e.setdefault("JSEVAL_HEALTH_TIMEOUT_SEC", "600")
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


def arm(out, corpus, tag, extra, cfg=None):
    slug = corpus.replace("/", "_")
    d = os.path.join(out, slug)
    os.makedirs(d, exist_ok=True)
    cmd = [sys.executable, "-m", "jseval", "run", "--dataset", corpus, "--modes", MODES,
           "--start-backend", "--json", "--output-dir", os.path.join(d, tag)] + extra
    if cfg:
        cmd += ["--config", cfg]
    signature(out, "%s/%s:pre" % (slug, tag))
    log("ARM %s / %s" % (slug, tag))
    with io.open(os.path.join(d, tag + ".log"), "w", encoding="utf-8", errors="replace") as fh:
        rc = subprocess.run(cmd, cwd=HERE, stdout=fh, stderr=subprocess.STDOUT,
                            text=True, env=child_env()).returncode
    log("ARM %s / %s exit=%d" % (slug, tag, rc))
    signature(out, "%s/%s:post" % (slug, tag))
    return rc


def do_run(a):
    os.makedirs(a.out, exist_ok=True)
    values = split_values(a.sweep_values)
    fixed = [p for p in (a.fixed_env or "").split(",") if p.strip()]
    for corpus in [c.strip() for c in a.corpora.split(",") if c.strip()]:
        slug = corpus.replace("/", "_")
        os.makedirs(os.path.join(a.out, slug), exist_ok=True)
        for v in values:
            io.open(os.path.join(a.out, slug, "v%s.yaml" % v), "w",
                    encoding="utf-8", newline="").write(arm_yaml(fixed, a.sweep_key, v))
        # The build arm establishes the shared index; OFF and every ON arm are --skip-ingest, so
        # they are symmetric and the only difference between them is the swept key.
        arm(a.out, corpus, "build", ["--pipeline", "--clean", "--max-queries", "5"])
        arm(a.out, corpus, "off", ["--skip-ingest"])
        for v in values:
            for r in range(a.reps):
                arm(a.out, corpus, "v%s-r%d" % (v, r), ["--skip-ingest"],
                    cfg=os.path.join(a.out, slug, "v%s.yaml" % v))
        io.open(os.path.join(a.out, slug, "CORPUS.done"), "w", encoding="utf-8").write("done\n")
    io.open(os.path.join(a.out, "RUN.done"), "w", encoding="utf-8").write("done\n")
    log("RUN COMPLETE")


def load(out, slug, tag, mode="hybrid"):
    ps = sorted(glob.glob(os.path.join(out, slug, tag, "*", "summary.json")))
    if not ps:
        return None
    p = ps[-1]
    d = json.load(io.open(p, encoding="utf-8"))
    pm = d.get("per_mode", {}).get(mode, {})
    am = pm.get("aggregate_metrics", {})
    rd = os.path.dirname(p)
    acc = {}
    ap = os.path.join(rd, "projections", "staged_recall_accounting.json")
    if os.path.exists(ap):
        acc = json.load(io.open(ap, encoding="utf-8")).get("aggregate", {})
    cev = (d.get("ce_coverage") or {}).get("verdict")
    cmp_ = pm.get("comparable")
    return {"run_id": os.path.basename(rd), "ndcg": am.get("nDCG@10"), "r10": am.get("R@10"),
            "p1": am.get("P@1"), "leak": acc.get("leak_rate"),
            "union": acc.get("leg_union_recall"), "ce_cov": cev, "comparable": cmp_,
            "admissible": cev == "ok" and cmp_ is True}


def do_analyze(a):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    values = split_values(a.sweep_values)
    corpora = [c.strip() for c in a.corpora.split(",") if c.strip()]
    print("| corpus | arm | run id | ce_cov | comparable | admissible | nDCG@10 | R@10 | leak |")
    print("| :-- | :-- | :-- | :-- | :-- | :-- | --: | --: | --: |")
    data = {}
    for corpus in corpora:
        slug = corpus.replace("/", "_")
        off = load(a.out, slug, "off")
        data[corpus] = {"off": off, "arms": {}}
        for rec, tag in ([(off, "OFF")] if off else []):
            print("| %s | %s | `%s` | %s | %s | %s | %.4f | %.4f | %.4f |" % (
                corpus, tag, rec["run_id"], rec["ce_cov"], rec["comparable"],
                "YES" if rec["admissible"] else "**VOID**", rec["ndcg"], rec["r10"], rec["leak"]))
        for lam in values:
            reps = []
            for r in range(a.reps):
                rec = load(a.out, slug, "v%s-r%d" % (lam, r))
                if not rec:
                    continue
                reps.append(rec)
                print("| %s | %s r%d | `%s` | %s | %s | %s | %.4f | %.4f | %.4f |" % (
                    corpus, lam, r, rec["run_id"], rec["ce_cov"], rec["comparable"],
                    "YES" if rec["admissible"] else "**VOID**",
                    rec["ndcg"], rec["r10"], rec["leak"]))
            data[corpus]["arms"][lam] = reps

    print("\n| corpus | value | n adm | R@10 mean | spread | d R@10 | d nDCG | d leak | noise | beats |")
    print("| :-- | :-- | --: | --: | --: | --: | --: | --: | --: | :-- |")
    verdict = {}
    for corpus in corpora:
        off = data[corpus]["off"]
        if not off or not off["admissible"]:
            print("| %s | OFF VOID - corpus unusable | | | | | | | | |" % corpus)
            continue
        for lam in values:
            adm = [r for r in data[corpus]["arms"].get(lam, []) if r["admissible"]]
            if not adm:
                print("| %s | %s | 0 | - | - | - | - | - | - | **no admissible replicate** |"
                      % (corpus, lam))
                continue
            r10 = [r["r10"] for r in adm]
            mean = sum(r10) / len(r10)
            spread = (max(r10) - min(r10)) if len(r10) > 1 else 0.0
            noise = max(spread, a.floor)
            d10 = mean - off["r10"]
            dn = sum(r["ndcg"] for r in adm) / len(adm) - off["ndcg"]
            dl = sum(r["leak"] for r in adm) / len(adm) - off["leak"]
            verdict.setdefault(lam, {})[corpus] = {"d10": d10, "dl": dl, "beats": d10 > noise}
            print("| %s | %s | %d | %.4f | %.4f | %+.4f | %+.4f | %+.4f | %.4f | %s |" % (
                corpus, lam, len(adm), mean, spread, d10, dn, dl, noise,
                "**YES**" if d10 > noise else "no"))

    print("\n**Verdict**\n")
    winners = []
    for lam in values:
        v = verdict.get(lam, {})
        if len(v) < len(corpora):
            print("- value %s: not shippable (missing an admissible corpus result)" % lam)
            continue
        c1 = all(v[c]["beats"] for c in corpora)
        c3 = all(v[c]["dl"] <= 0 for c in corpora)
        print("- value %s: R@10 both-beat=%s leak-not-worse=%s (%s)" % (
            lam, c1, c3, ", ".join("%s %+.4f" % (c.split("/")[-1], v[c]["d10"]) for c in corpora)))
        if c1 and c3:
            winners.append(lam)
    print("\n**%s**" % ("SHIP candidate(s): %s" % winners if winners else
                        "PARK - no value satisfies the rule on every chunked corpus."))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("run", "analyze"):
        s = sub.add_parser(name)
        s.add_argument("--out", required=True)
        s.add_argument("--corpora", default="mixed/legal-clerc-200,mixed/enron-qa")
        s.add_argument("--sweep-key", default="",
                       help="env var under test, e.g. JUSTSEARCH_HYBRID_SOMETHING")
        s.add_argument("--sweep-values", default="",
                       help="comma-separated arm values for --sweep-key")
        s.add_argument("--fixed-env", default="",
                       help="comma-separated K=V applied to every ON arm")
        s.add_argument("--reps", type=int, default=2)
        s.add_argument("--floor", type=float, default=0.0068,
                       help="noise floor for d R@10; the observed replicate spread wins if larger")
    a = ap.parse_args()
    if not split_values(a.sweep_values):
        ap.error("--sweep-values is required (comma-separated arm values)")
    if a.cmd == "run" and not a.sweep_key:
        ap.error("--sweep-key is required for `run`")
    (do_run if a.cmd == "run" else do_analyze)(a)


if __name__ == "__main__":
    main()

"""jseval release commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import sys
from pathlib import Path
import logging

import click

from .._paths import DEFAULT_EVAL_RESULTS

log = logging.getLogger(__name__)


@click.command("release")
@click.option("--run", "runs", multiple=True, type=click.Path(exists=True, resolve_path=True),
              help="Run dir (with summary.json) to include. Repeatable. One or more per corpus. "
                   "All must share one config-cohort key (else compose refuses).")
@click.option("--latest-per-dataset", is_flag=True,
              help="Auto-discover: the most recent run per dataset under --data-dir's eval-results "
                   "(via the manifest index). compose() then validates they are one cohort.")
@click.option("--data-dir", type=click.Path(), default=lambda: str(DEFAULT_EVAL_RESULTS.parent),
              help="Data dir whose eval-results/ holds runs (for --latest-per-dataset).")
@click.option("--default-mode", default="hybrid", show_default=True,
              help="Production-default search mode whose metrics are the per-corpus headline.")
@click.option("--external-baselines", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Path to external-baselines.v1.json (cited published baselines, side-by-side).")
@click.option("--release-id", default=None, help="Optional human label for this release.")
@click.option("--allow-incomparable", is_flag=True,
              help="Compose even if a default-mode run is not comparable (diagnostics only).")
@click.option("--out", "out_path", type=click.Path(), default=None,
              help="Where to write release.v1.json (default: <data-dir>/release.v1.json).")
@click.pass_context
def cmd_release(ctx, runs, latest_per_dataset, data_dir, default_mode, external_baselines,
                release_id, allow_incomparable, out_path):
    """Compose cohort-identical eval runs into one publishable benchmark release (tempdoc 623).

    A release is a PROJECTION over runs that share one config/commit/hardware — not a new
    authority. The relevance-ratchet floors and the register headline read from it (see
    `jseval relevance-gate` re-rooting and scripts/docs/register-headline-sync.mjs).
    """
    from datetime import datetime, timezone

    from .. import release as _release
    from .. import bisection as _bisection

    run_dirs: list[Path] = [Path(r) for r in runs]
    if latest_per_dataset:
        idx_root = Path(data_dir) / "eval-results"
        rows = _bisection.load_index(idx_root)
        latest: dict[str, dict] = {}
        for row in rows:
            ds = row.get("dataset")
            if ds is None:
                continue
            prev = latest.get(ds)
            if prev is None or (row.get("timestamp") or "") > (prev.get("timestamp") or ""):
                latest[ds] = row
        # P1 (tempdoc 623): the latest run per dataset can span commits — the shared `main` moves
        # mid-sweep (the V5 split). Group the latest runs by config_cohort_key and pick the cohort
        # covering the MOST datasets, then WARN + EXCLUDE the split, rather than feeding a mixed set
        # to compose() (which would just refuse the whole thing with an opaque error).
        dataset_to_key: dict[str, str] = {}
        dataset_to_rd: dict[str, Path] = {}
        for ds, row in latest.items():
            rd = Path(row["run_dir"])
            sp = rd / "summary.json"
            if not sp.is_file():
                continue
            manifest = json.loads(sp.read_text(encoding="utf-8")).get("manifest") or {}
            dataset_to_key[ds] = _release.config_cohort_key(manifest)
            dataset_to_rd[ds] = rd
        chosen_key, excluded = _release.select_dominant_cohort(dataset_to_key)
        if excluded:
            click.echo(
                f"WARNING: {len(excluded)} dataset(s) are on a different config-cohort "
                f"(likely a mid-sweep commit move) and are EXCLUDED from this release: "
                f"{', '.join(excluded)}. Re-run them at the chosen cohort's commit to include them.",
                err=True,
            )
        run_dirs.extend(rd for ds, rd in dataset_to_rd.items() if dataset_to_key[ds] == chosen_key)

    if not run_dirs:
        click.echo("Error: provide --run (repeatable) or --latest-per-dataset", err=True)
        sys.exit(2)

    summaries: list[dict] = []
    for rd in run_dirs:
        sp = rd / "summary.json"
        if not sp.is_file():
            click.echo(f"Error: no summary.json in {rd}", err=True)
            sys.exit(2)
        summaries.append(json.loads(sp.read_text(encoding="utf-8")))

    ext = None
    if external_baselines:
        ext_doc = json.loads(Path(external_baselines).read_text(encoding="utf-8"))
        ext = ext_doc.get("baselines", ext_doc)

    try:
        release_doc = _release.compose(
            summaries,
            default_mode=default_mode,
            composed_at=datetime.now(timezone.utc).isoformat(),
            release_id=release_id,
            external_baselines=ext,
            require_comparable=not allow_incomparable,
        )
    except _release.ComposeError as e:
        click.echo(f"compose refused: {e}", err=True)
        sys.exit(1)

    out = Path(out_path) if out_path else Path(data_dir) / "release.v1.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(release_doc, indent=2, sort_keys=True, ensure_ascii=False),
        encoding="utf-8",
    )
    click.echo(json.dumps({
        "release": str(out),
        "config_cohort_key": release_doc["cohort"]["config_cohort_key"][:12],
        "git_sha": (release_doc["cohort"].get("git_sha") or "")[:10],
        "default_mode": default_mode,
        "corpora": sorted(release_doc["measured"]),
        "missing_default_mode": release_doc["notes"]["missing_default_mode"],
    }, indent=2))


@click.command("suite-profile")
@click.option("--suite", required=True, help="Suite tag, e.g. 635-self-demo-v1.")
@click.option("--datasets-dir", default=None, type=click.Path())
@click.option("--records-root", default=None, type=click.Path(),
              help="Dir holding per-member 635-<name>/{release,utility-comparison}.v1.json (optional).")
@click.option("--output", default=None, type=click.Path(),
              help="Write the profile JSON here (the committed, engine-SHA-stamped ceiling snapshot).")
@click.pass_context
def cmd_suite_profile(ctx, suite, datasets_dir, records_root, output):
    """Project the suite's certified members into ONE profile (tempdoc 635 §R-3: a profile, not a number).

    Per-member: type, contamination class, closed-book + fidelity verdicts, retrieval nDCG/difficulty, and
    (where present) the agent delta — exposing where the engine is strong/weak across document/query types.
    With --output, writes a durable snapshot stamped with the engine git SHA (datasets/ is gitignored, so the
    committed snapshot is the durable ceiling record; the levers are default-on, so the SHA pins behaviour)."""
    import datetime as _dt
    import subprocess as _sp

    from .. import suite_profile as sp
    from .._paths import REPO_ROOT

    base = Path(datasets_dir) if datasets_dir else (REPO_ROOT / "datasets")
    sha = None
    try:
        _p = _sp.run(["git", "rev-parse", "HEAD"], cwd=str(REPO_ROOT),
                     capture_output=True, text=True, timeout=10)
        # Only trust stdout on a clean exit (a non-zero exit with partial stdout would stamp garbage).
        sha = _p.stdout.strip() if _p.returncode == 0 else None
        sha = sha or None
    except Exception:
        sha = None
    prof = sp.build_profile(
        suite, base, Path(records_root) if records_root else None,
        engine_git_sha=sha, generated_date=_dt.datetime.now(_dt.timezone.utc).date().isoformat())
    if output:
        Path(output).write_text(json.dumps(prof, ensure_ascii=False, indent=2), encoding="utf-8")
        click.echo(f"Wrote suite profile -> {output} (engine_git_sha={sha[:12] if sha else '?'})", err=True)
    if ctx.obj.get("json"):
        click.echo(json.dumps(prof, indent=2))
    else:
        click.echo(f"Suite '{suite}' — {prof['n_members']} members (profile, not a number):")
        for r in prof["members"]:
            agent = (f" agent_acc_d={r['agent_acc_delta']:+}" if r.get("agent_acc_delta") is not None else "")
            click.echo(f"  [{r['type_axis']:>9}] {r['member']}: clean={r['closed_book_passed']} "
                       f"fidelity={r['fidelity_passed']} nDCG={r['retrieval_ndcg']} "
                       f"({r['retrieval_difficulty']}) leaks={r['shortcut_leak_rate']}{agent}")


COMMANDS = [cmd_release, cmd_suite_profile]

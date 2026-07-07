"""jseval corpus commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import os
from pathlib import Path
import logging

import click

from ._common import assert_run_capabilities

log = logging.getLogger(__name__)


@click.command("corpus-build")
@click.option("--source", required=True, type=click.Path(exists=True),
              help="Committed corpus source dir (scripts/jseval/635-corpora/<name>/).")
@click.option("--name", required=True,
              help="Golden dataset name, e.g. synth-multihop-v1 (-> datasets/golden/<name>/).")
@click.option("--datasets-dir", default=None, type=click.Path(),
              help="Base datasets dir (default: repo datasets/).")
@click.pass_context
def cmd_corpus_build(ctx, source, name, datasets_dir):
    """Materialize a committed corpus source -> golden/ BEIR layout + agent inputs (tempdoc 635).

    One source -> two projections: retrieval view (corpus.jsonl + queries.jsonl + qrels) and
    agent view (queries.json + raw corpus-dir). Writes a metadata.json with the 635 identity fields."""
    from .. import corpus_build as cb
    from .._paths import REPO_ROOT

    base = Path(datasets_dir) if datasets_dir else (REPO_ROOT / "datasets")
    dataset_dir = base / "golden" / name
    meta = cb.build_golden(source, dataset_dir)
    if ctx.obj.get("json"):
        click.echo(json.dumps(meta, indent=2))
    else:
        click.echo(f"Built golden/{name}: {meta['corpus_size']} docs, "
                   f"{meta['query_count']} queries -> {dataset_dir}")
        click.echo(f"  sig={(meta['corpus_signature'] or '')[:16]} type={meta['type_axis']} "
                   f"suite={meta['suite']} class={meta['contamination_class']}")


def _write_recipe(name: str, recipe: dict) -> Path:
    """Commit the small fetch recipe (tempdoc 666) — never the fetched corpus content itself —
    mirroring `635-corpora/*/meta.json`'s `generation_provenance` discipline, one level up (a fetch
    source, not a generation seed)."""
    from .._paths import REPO_ROOT

    recipe_dir = REPO_ROOT / "scripts" / "jseval" / "666-corpora" / name
    recipe_dir.mkdir(parents=True, exist_ok=True)
    recipe_path = recipe_dir / "recipe.json"
    recipe_path.write_text(json.dumps(recipe, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return recipe_path


def _fetch_and_build_mixed(name: str, datasets_dir, fetch_fn) -> dict:
    """Shared plumbing for the two `corpus-fetch-*` commands: fetch into an ephemeral temp source dir,
    then materialize via the existing `corpus_build.build_golden` into `datasets/mixed/<name>/`
    (gitignored — nothing this writes is ever committed; see `corpus_fetch.py`'s module docstring)."""
    import tempfile

    from .. import corpus_build as cb
    from .._paths import REPO_ROOT

    base = Path(datasets_dir) if datasets_dir else (REPO_ROOT / "datasets")
    dataset_dir = base / "mixed" / name
    with tempfile.TemporaryDirectory() as td:
        provenance = fetch_fn(td)
        meta = cb.build_golden(td, dataset_dir)
    _write_recipe(name, provenance)
    return meta


@click.command("corpus-fetch-miracl")
@click.option("--name", required=True, help="Mixed dataset name, e.g. miracl-de-2k (-> datasets/mixed/<name>/).")
@click.option("--lang", required=True, help="MIRACL language code, e.g. de, fr.")
@click.option("--seed", required=True, type=int, help="Deterministic sampling seed (recorded in the recipe).")
@click.option("--n-docs", required=True, type=int, help="Target total document count (qrelled + sampled distractors).")
@click.option("--split", default="dev", show_default=True, help="ir_datasets MIRACL split.")
@click.option("--datasets-dir", default=None, type=click.Path())
@click.pass_context
def cmd_corpus_fetch_miracl(ctx, name, lang, seed, n_docs, split, datasets_dir):
    """Fetch + deterministically sample a MIRACL corpus into mixed/ (tempdoc 666).

    Real, public, Apache-2.0-licensed data via the already-installed `ir_datasets` dependency — no new
    dependency. Commits only a small recipe (source/seed/sizes) to `scripts/jseval/666-corpora/<name>/`;
    the fetched corpus content itself is never committed (datasets/ is gitignored)."""
    from .. import corpus_fetch as cf

    meta = _fetch_and_build_mixed(
        name, datasets_dir,
        lambda td: cf.fetch_miracl_sample(td, lang=lang, seed=seed, n_docs=n_docs, split=split))
    if ctx.obj.get("json"):
        click.echo(json.dumps(meta, indent=2))
    else:
        click.echo(f"Fetched mixed/{name}: {meta['corpus_size']} docs, {meta['query_count']} queries "
                   f"from miracl/{lang}/{split} (seed={seed})")


@click.command("corpus-fetch-clerc")
@click.option("--name", required=True, help="Mixed dataset name (-> datasets/mixed/<name>/).")
@click.option("--seed", required=True, type=int, help="Deterministic sampling seed (recorded in the recipe).")
@click.option("--n-queries", required=True, type=int, help="Target sampled query count.")
@click.option("--datasets-dir", default=None, type=click.Path())
@click.pass_context
def cmd_corpus_fetch_clerc(ctx, name, seed, n_queries, datasets_dir):
    """Fetch + deterministically sample a CLERC-based legal corpus into mixed/ (tempdoc 666).

    Public data (no access gate, unlike COLIEE) via a direct HTTP fetch — CLERC is not `ir_datasets`-
    registered. See `corpus_fetch.py`'s module docstring for the licensing note this design already
    accounts for: nothing fetched here is ever committed (datasets/ is gitignored), only a small recipe."""
    from .. import corpus_fetch as cf

    meta = _fetch_and_build_mixed(
        name, datasets_dir, lambda td: cf.fetch_clerc_sample(td, seed=seed, n_queries=n_queries))
    if ctx.obj.get("json"):
        click.echo(json.dumps(meta, indent=2))
    else:
        click.echo(f"Fetched mixed/{name}: {meta['corpus_size']} docs, {meta['query_count']} queries "
                   f"from CLERC (seed={seed})")


@click.command("corpus-certify")
@click.option("--dataset", required=True, help="Golden dataset name, e.g. synth-multihop-v1.")
@click.option("--datasets-dir", default=None, type=click.Path())
@click.option("--model", default="haiku", show_default=True)
@click.option("--threshold", default=0.15, show_default=True, type=float,
              help="Max closed-book accuracy to PASS (a clean corpus should score ~0).")
@click.option("--concurrency", default=8, show_default=True, type=int)
@click.pass_context
def cmd_corpus_certify(ctx, dataset, datasets_dir, model, threshold, concurrency):
    """Closed-book certification: a corpus is clean only if the model FAILS it closed-book (tempdoc 635).

    Writes closed_book_certification + fidelity into the dataset's metadata.json. Needs the `claude` CLI
    (no JustSearch dev stack)."""
    from .. import corpus_certify as cc
    from .._paths import REPO_ROOT

    base = Path(datasets_dir) if datasets_dir else (REPO_ROOT / "datasets")
    dataset_dir = base / "golden" / dataset
    queries = json.loads((dataset_dir / "queries.json").read_text(encoding="utf-8"))
    result = cc.certify_corpus(queries, model=model, threshold=threshold, concurrency=concurrency)

    meta_path = dataset_dir / "metadata.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.is_file() else {}

    # Descriptor-collision self-consistency check (tempdoc 664): no live backend needed — pure
    # content analysis over corpus.jsonl + the already-loaded queries. Best-effort on a missing
    # corpus.jsonl (older/non-committed corpora) rather than failing certification on an unrelated
    # file-layout gap.
    # tempdoc 664 (seventh-pass fix): corpus_build.build_golden() writes `corpus.jsonl`, never
    # `docs.jsonl` -- this previously looked for a file that never exists in a real materialized
    # dataset, so the collision check silently computed nothing in production (confirmed live via
    # a CliRunner invocation against a real materialized corpus). The unit tests for
    # descriptor_collision_report() passed because they call the function directly; no test
    # exercised this file-loading line until now.
    docs_path = dataset_dir / "corpus.jsonl"
    collisions = None
    if docs_path.is_file():
        docs = [json.loads(line) for line in docs_path.read_text(encoding="utf-8").splitlines() if line.strip()]
        collisions = cc.descriptor_collision_report(docs, queries)

    # Regeneration-determinism check (tempdoc 664, seventh pass): reads the corpus's own recorded
    # `generation_provenance` (top-level in metadata.json, passed through unchanged from the
    # committed source by corpus_build.build_golden()) and actually regenerates it twice to verify
    # "seeded -> reproducible" rather than trusting the claim. Gracefully skips (passed: None) when
    # provenance is absent/incomplete/hand-authored — see regeneration_determinism_report's docstring.
    determinism = cc.regeneration_determinism_report(meta.get("generation_provenance"))

    # The two co-equal gates (memory = certify, retrieval-difficulty = fidelity) BOTH write the
    # `fidelity` block; MERGE the sub-block so neither clobbers the other regardless of run order
    # (symmetric to corpus-fidelity's merge — without this, certify-after-fidelity wiped the
    # retrieval_ndcg/by_mode/comparable fields).
    meta.update({k: v for k, v in result.items() if k != "fidelity"})
    fid = dict(meta.get("fidelity") or {})
    # Skip None values: certify emits `retrieval_difficulty: None` as a placeholder for the
    # post-retrieval-run population — it must NOT clobber a real value already set by
    # corpus-fidelity when certify runs second. (Only memory_independence is certify's to own.)
    fid.update({k: v for k, v in (result.get("fidelity") or {}).items() if v is not None})
    if collisions is not None:
        fid["descriptor_collisions"] = collisions
    fid["regeneration_determinism"] = determinism
    meta["fidelity"] = fid
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    cert = result["closed_book_certification"]
    if ctx.obj.get("json"):
        out = dict(result)
        if collisions is not None:
            out["descriptor_collisions"] = collisions
        out["regeneration_determinism"] = determinism
        click.echo(json.dumps(out, indent=2))
    else:
        verdict = "PASS" if cert["passed"] else "FAIL"
        click.echo(f"corpus-certify golden/{dataset}: closed-book acc={cert['closed_book_accuracy']:.3f} "
                   f"(<= {threshold}) -> {verdict}")
        click.echo(f"  memory_independence={result['fidelity']['memory_independence']} "
                   f"(retrieval_difficulty set post-retrieval-run)  written to {meta_path.name}")
        if collisions is not None:
            coll_verdict = "PASS" if collisions["passed"] else "FAIL"
            click.echo(f"  descriptor_collisions: {collisions['n_groups']} groups / "
                       f"{collisions['n_docs_involved']} docs ({collisions['n_gold_involved']} "
                       f"gold-involved / qrel-corrupting) -> {coll_verdict}")
        if determinism["passed"] is None:
            click.echo(f"  regeneration_determinism: SKIP ({determinism['reason']})")
        else:
            det_verdict = "PASS" if determinism["passed"] else "FAIL"
            click.echo(f"  regeneration_determinism: -> {det_verdict}")


@click.command("corpus-fidelity")
@click.option("--dataset", required=True, help="Golden dataset name (e.g. synth-code-v1).")
@click.option("--base-url", default="http://127.0.0.1:33221", show_default=True,
              help="Live backend with the corpus already ingested (ignored when --start-backend).")
@click.option("--datasets-dir", default=None, type=click.Path())
@click.option("--modes", default="bm25_splade", show_default=True, help="Retrieval modes; headline = last.")
@click.option("--embedding/--no-embedding", default=False, help="Enable dense (needs chunking docs).")
@click.option("--band-low", default=None, type=float,
              help="Min nDCG@10 to pass (default: corpus_fidelity.DEFAULT_BAND_LOW; multi-hop scores low).")
@click.option("--band-high", default=None, type=float, help="Max nDCG@10 to pass (default module constant).")
@click.option("--leak-threshold", default=None, type=float, help="Max single-doc shortcut-leak rate.")
@click.option("--model", default="haiku", show_default=True)
@click.option("--concurrency", default=8, show_default=True, type=int)
@click.option("--start-backend", is_flag=True,
              help="Self-contained: start runHeadlessEval, ingest the dataset, assess, stop. The "
                   "harness backend auto-discovers the reranker (default-on engine). Mirrors `jseval run`.")
@click.option("--clean", is_flag=True, help="Clean the data dir before starting (requires --start-backend).")
@click.option("--allow-degraded", is_flag=True, help="Tempdoc 644 Axis 2: proceed even when an intended engine (the reranker for a hybrid mode) is not loaded. Default OFF — fail closed rather than score with a silently-degraded engine set.")
@click.pass_context
def cmd_corpus_fidelity(ctx, dataset, base_url, datasets_dir, modes, embedding,
                        band_low, band_high, leak_threshold, model, concurrency,
                        start_backend, clean, allow_degraded):
    """FIDELITY gate (tempdoc 635 §D.5): a corpus passes only if it is non-trivial yet retrievable
    (in-band nDCG@10) AND genuinely multi-hop (low single-doc shortcut leaks). The retrieval-difficulty
    axis, symmetric to corpus-certify's memory axis. Stack-bound: pass --start-backend to ingest+assess
    self-contained (the harness backend's auto-discovered reranker is the DEFAULT-ON engine), or point
    --base-url at a backend with the corpus already ingested."""
    from .. import corpus_fidelity as cf
    from .._paths import REPO_ROOT

    base = Path(datasets_dir) if datasets_dir else (REPO_ROOT / "datasets")
    dataset_dir = base / "golden" / dataset
    mode_list = tuple(m.strip() for m in modes.split(",") if m.strip())

    # Self-contained mode: bring up the harness backend, ingest the member, assess, stop.
    # Reuses the exact backend + ingest+readiness path `jseval run --start-backend` uses
    # (cli._run_single_iteration), so the reranker auto-discovery + reaper-free lifecycle apply.
    backend_proc = None
    if start_backend:
        from .. import backend as backend_mod
        from .. import ingest as ingest_mod
        from ..types import IngestConfig
        if not clean:
            # A self-contained gate run must start from a clean index: cache-verification
            # (tempdoc 635) binds the materialized corpus to its source, but a dirty Lucene
            # index would still co-ingest a prior corpus. Both are needed to bind index==corpus,
            # so refuse rather than silently pollute the verdict.
            raise click.UsageError(
                "--start-backend requires --clean (a self-contained fidelity run must start "
                "from a clean index, else a prior corpus co-ingests and pollutes the verdict)."
            )
        # Resolve ONE port and pass it to both start_backend and base_url (D3): start_backend binds
        # `port` and sets the child's env from it, so deriving base_url from the parent's env would
        # diverge whenever JUSTSEARCH_API_PORT is set to a non-default value.
        port = int(os.environ.get("JUSTSEARCH_API_PORT", "33221"))
        backend_proc = backend_mod.start_backend(clean=clean, llm=False, port=port)
        base_url = f"http://127.0.0.1:{port}"
    try:
        # Tempdoc 644 Axis 2: refuse if the cross-encoder is intended (a hybrid mode is in the
        # list) but not realized — before the expensive ingest.
        assert_run_capabilities(base_url, mode_list, allow_degraded=allow_degraded)
        if start_backend:
            popen = backend_proc.proc
            ingest_mod.prepare_corpus(
                f"golden/{dataset}",
                config=IngestConfig(
                    base_url=base_url, dense_enabled=embedding, splade_enabled=True,
                    pipeline=True, json_mode=ctx.obj.get("json", False),
                    process_check=(lambda: popen.poll() is None),
                ),
            )
        result = cf.assess_fidelity(
            dataset_dir, f"golden/{dataset}", base_url, modes=mode_list,
            embedding_enabled=embedding, splade_enabled=True,
            band_low=cf.DEFAULT_BAND_LOW if band_low is None else band_low,
            band_high=cf.DEFAULT_BAND_HIGH if band_high is None else band_high,
            leak_threshold=cf.DEFAULT_LEAK_THRESHOLD if leak_threshold is None else leak_threshold,
            model=model, concurrency=concurrency, base_dir=base)
    finally:
        if backend_proc is not None:
            from .. import backend as backend_mod
            backend_mod.stop_backend(backend_proc.proc)

    meta_path = dataset_dir / "metadata.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.is_file() else {}
    # Merge into the cert's fidelity block, preserving memory_independence. Skip None values for
    # symmetry with corpus-certify's merge (D2) — neither co-equal gate clobbers the other's fields
    # regardless of run order.
    fid = dict(meta.get("fidelity") or {})
    fid.update({k: v for k, v in result.items() if v is not None})
    meta["fidelity"] = fid
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2))
    else:
        verdict = "PASS" if result["passed"] else "FAIL"
        click.echo(f"corpus-fidelity golden/{dataset}: nDCG@10={result['retrieval_ndcg']} "
                   f"(band {result['band'][0]}-{result['band'][1]}, in_band={result['in_band']}), "
                   f"difficulty={result['retrieval_difficulty']}, "
                   f"shortcut_leaks={result['shortcut_leak_rate']} -> {verdict}")


@click.command("corpus-probe")
@click.option("--dataset", required=True, help="Golden dataset name (e.g. synth-code-v1).")
@click.option("--base-url", default="http://127.0.0.1:33221", show_default=True,
              help="Live backend with the corpus ingested (ignored when --start-backend).")
@click.option("--datasets-dir", default=None, type=click.Path())
@click.option("--modes", default="vector,bm25_splade,hybrid", show_default=True,
              help="Retrieval modes to probe (comma-separated).")
@click.option("--embedding/--no-embedding", default=True, help="Enable dense (needed for vector/hybrid).")
@click.option("--top-k", default=10, show_default=True, type=int)
@click.option("--start-backend", is_flag=True,
              help="Self-contained: start runHeadlessEval, ingest, probe, stop.")
@click.option("--clean", is_flag=True, help="Clean the data dir before starting (requires --start-backend).")
@click.pass_context
def cmd_corpus_probe(ctx, dataset, base_url, datasets_dir, modes, embedding, top_k, start_backend, clean):
    """PROBE the retrieval binding (tempdoc 635 witness): per-query expected-head rank + top-k across modes,
    plus a control search of the head's own descriptor. The inspectable 'show your work' companion to
    corpus-fidelity's scalar verdict — diagnoses whether/why retrieval finds the certified head (and whether
    the measured index is even the certified corpus: an exactly-0.0 / head-never-found probe is the
    plumbing-mismatch signature, not a retrieval-quality one)."""
    import collections
    from .. import retriever as retr
    from .._paths import REPO_ROOT

    base = Path(datasets_dir) if datasets_dir else (REPO_ROOT / "datasets")
    dataset_dir = base / "golden" / dataset
    mode_list = [m.strip() for m in modes.split(",") if m.strip()]

    queries: dict[str, str] = {}
    for line in (dataset_dir / "queries.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            d = json.loads(line); queries[d["_id"]] = d["text"]
    head: dict[str, str] = {}
    for line in (dataset_dir / "qrels" / "test.tsv").read_text(encoding="utf-8").splitlines()[1:]:
        if line.strip():
            qid, cid, _ = line.split("\t"); head[qid] = cid
    titles: dict[str, str] = {}
    for line in (dataset_dir / "corpus.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            d = json.loads(line); titles[d["_id"]] = d.get("title", "")

    backend_proc = None
    if start_backend:
        if not clean:
            raise click.UsageError("--start-backend requires --clean (see corpus-fidelity).")
        from .. import backend as backend_mod
        from .. import ingest as ingest_mod
        from ..types import IngestConfig
        port = int(os.environ.get("JUSTSEARCH_API_PORT", "33221"))
        backend_proc = backend_mod.start_backend(clean=clean, llm=False, port=port)
        base_url = f"http://127.0.0.1:{port}"
    try:
        if start_backend:
            popen = backend_proc.proc
            ingest_mod.prepare_corpus(
                f"golden/{dataset}",
                config=IngestConfig(
                    base_url=base_url, dense_enabled=embedding, splade_enabled=True,
                    pipeline=True, json_mode=ctx.obj.get("json", False),
                    process_check=(lambda: popen.poll() is None)))
        rows = []
        for mode in mode_list:
            scored, _ = retr.retrieve(queries, base_url, mode=mode, top_k=top_k, allow_errors=True)
            by_q: dict[str, list[str]] = collections.defaultdict(list)
            for sd in scored:
                by_q[sd.query_id].append(sd.doc_id)
            ranks, found = [], 0
            for qid in queries:
                preds = by_q.get(qid, [])
                h = head.get(qid)
                if h and h in preds:
                    found += 1; ranks.append(preds.index(h) + 1)
            rows.append({"mode": mode, "head_at_topk": f"{found}/{len(queries)}",
                         "mean_rank": (round(sum(ranks) / len(ranks), 2) if ranks else None)})
        ctrl = None
        if queries:
            q0 = next(iter(queries)); h0 = head.get(q0)
            if h0:
                cs, _ = retr.retrieve({"ctrl": titles.get(h0, "")}, base_url,
                                      mode=mode_list[-1], top_k=top_k, allow_errors=True)
                preds = [sd.doc_id for sd in cs]
                ctrl = {"head": h0, "rank": (preds.index(h0) + 1 if h0 in preds else None)}
    finally:
        if backend_proc is not None:
            from .. import backend as backend_mod
            backend_mod.stop_backend(backend_proc.proc)

    out = {"dataset": f"golden/{dataset}", "n_queries": len(queries), "modes": rows, "control": ctrl}
    if ctx.obj.get("json"):
        click.echo(json.dumps(out, indent=2))
    else:
        click.echo(f"corpus-probe golden/{dataset} ({len(queries)} queries):")
        for r in rows:
            click.echo(f"  [{r['mode']:12}] head@top{top_k}: {r['head_at_topk']}  mean_rank={r['mean_rank']}")
        if ctrl is not None:
            click.echo(f"  control (head's own descriptor): rank={ctrl['rank']}")


COMMANDS = [cmd_corpus_build, cmd_corpus_certify, cmd_corpus_fidelity, cmd_corpus_probe,
            cmd_corpus_fetch_miracl, cmd_corpus_fetch_clerc]

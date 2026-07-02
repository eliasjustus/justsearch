"""Tests for the corpus-as-governed-artifact machinery (tempdoc 635).

Pure-function / fixture tests over the corpus identity, certification, build, and
metadata-validation surfaces — NO live claude, NO dev stack (the closed-book call is
mocked). Mirrors ``test_corpora.py`` + ``test_utility_comparison.py`` inline-fixture style.
"""

from __future__ import annotations

import json
import warnings
from pathlib import Path
from unittest.mock import patch

import pytest

from jseval import corpus_build, corpus_certify, corpus_fidelity, corpus_identity, corpora
from jseval.types import CorpusMeta, QueryRecord


def _summary(ndcg, mode="bm25_splade", comparable=True):
    """Mirror run.execute_run's *summary* per-mode shape (run.py:416-431).

    The summary FLATTENS comparability into ``comparable`` (bool) +
    ``comparability_reasons`` (list) — it is NOT a ComparabilityResult object (that
    lives in the internal mode_results, not the returned summary). Mirroring the real
    producer here is the `unreachable-seed-green` guard. ``ndcg`` may be a float
    (single mode) or a ``{mode: ndcg}`` dict (multi-mode).

    LIMIT (D4): this mock can drift from the real ``execute_run`` summary shape and the
    tests would still pass — the comparability KeyError shipped exactly this way. The
    authoritative contract check is the LIVE re-cert run (`corpus-fidelity --start-backend`
    against the dev stack), not this mock (`static-green != live-working`).
    """
    by_mode = ndcg if isinstance(ndcg, dict) else {mode: ndcg}
    return {"per_mode": {m: {
        "aggregate_metrics": {"nDCG@10": v},
        "comparable": comparable,
        "comparability_reasons": [],
    } for m, v in by_mode.items()}}


# ---------------------------------------------------------------------------
# Fixtures — a tiny inline corpus source
# ---------------------------------------------------------------------------

def _write_source(src: Path) -> None:
    """Write a 4-doc / 2-query committed corpus source (genuine 2-hop)."""
    src.mkdir(parents=True, exist_ok=True)
    docs = [
        {"_id": "d_a", "title": "Alpha", "text": "The Alpha device was designed by Bex Ko."},
        {"_id": "d_b", "title": "Bex Ko", "text": "Bex Ko was born in the city of Quill."},
        {"_id": "d_c", "title": "Gamma", "text": "The Gamma engine was built by Tas Vrel."},
        {"_id": "d_d", "title": "Tas Vrel", "text": "Tas Vrel lives in the town of Mire."},
    ]
    with (src / "docs.jsonl").open("w", encoding="utf-8") as f:
        for d in docs:
            f.write(json.dumps(d) + "\n")
    (src / "queries.json").write_text(json.dumps([
        {"query": "Where was the designer of the Alpha device born?", "answer": "Quill",
         "question_type": "two_hop", "evidence_ids": ["d_a", "d_b"]},
        {"query": "In which town does the builder of the Gamma engine live?", "answer": "Mire",
         "question_type": "two_hop", "evidence_ids": ["d_c", "d_d"]},
    ]), encoding="utf-8")
    (src / "meta.json").write_text(json.dumps({
        "version": "1.0", "type_axis": "prose", "suite": "test-suite",
        "contamination_class": "private-synthetic",
        "generation_provenance": {"method": "test"},
    }), encoding="utf-8")


# ---------------------------------------------------------------------------
# corpus_identity — signature stability
# ---------------------------------------------------------------------------

def test_corpus_signature_stable_and_content_sensitive(tmp_path):
    a, b = tmp_path / "ds_a" / "golden" / "x", tmp_path / "ds_b" / "golden" / "x"
    _write_source(tmp_path / "src")
    corpus_build.build_golden(tmp_path / "src", a)
    corpus_build.build_golden(tmp_path / "src", b)
    # Identical defining-file bytes -> identical signature (path-independent).
    assert corpus_identity.corpus_signature(a) == corpus_identity.corpus_signature(b)
    # Mutating the corpus content changes the signature.
    (a / "corpus.jsonl").write_text("changed", encoding="utf-8")
    assert corpus_identity.corpus_signature(a) != corpus_identity.corpus_signature(b)


def test_signature_unified_with_eval_seam(tmp_path):
    """Issue-1 guard: corpus_signature == the eval's run._get_corpus_identity signature.

    The corpus metadata, the run manifest, and the release must all carry ONE corpus
    identity (conform, don't fork). This pins corpus_identity to the eval definition.
    """
    from jseval import run as run_mod
    from jseval.types import CorpusMeta

    base = tmp_path / "datasets"
    _write_source(tmp_path / "src")
    ds = base / "golden" / "x"
    corpus_build.build_golden(tmp_path / "src", ds)

    mine = corpus_identity.corpus_signature(ds)
    meta = CorpusMeta(name="golden/x", source="golden", doc_count=4, query_count=2)
    eval_sig = run_mod._get_corpus_identity("golden/x", meta, {}, base)["signature"]
    assert mine is not None and mine == eval_sig


def test_signature_is_corpus_plus_qrels_sha256(tmp_path):
    """The unified definition: sha256(corpus.jsonl bytes + qrels/test.tsv bytes)."""
    import hashlib
    _write_source(tmp_path / "src")
    ds = tmp_path / "golden" / "x"
    corpus_build.build_golden(tmp_path / "src", ds)
    h = hashlib.sha256()
    h.update((ds / "corpus.jsonl").read_bytes())
    h.update((ds / "qrels" / "test.tsv").read_bytes())
    assert corpus_identity.corpus_signature(ds) == h.hexdigest()


def test_corpus_signature_explicit_files_mode(tmp_path):
    """The `files=` mode (tempdoc 669): signs an arbitrary explicit file list,
    not the golden/mixed two-file shape, for non-eval reference corpora."""
    import hashlib

    d = tmp_path / "demo-corpus"
    d.mkdir()
    a = d / "a.md"
    b = d / "b.md"
    a.write_text("alpha", encoding="utf-8")
    b.write_text("beta", encoding="utf-8")

    sig = corpus_identity.corpus_signature(d, files=[a, b])
    h = hashlib.sha256()
    h.update(a.read_bytes())
    h.update(b.read_bytes())
    assert sig == h.hexdigest()

    # Order matters (files are hashed in the given order, not re-sorted).
    assert corpus_identity.corpus_signature(d, files=[b, a]) != sig

    # A changed file changes the signature.
    a.write_text("alpha-changed", encoding="utf-8")
    assert corpus_identity.corpus_signature(d, files=[a, b]) != sig

    # Default (no `files=`) mode on the same directory is unaffected — no
    # corpus.jsonl/qrels here, so it returns None rather than picking up `a`/`b`.
    assert corpus_identity.corpus_signature(d) is None

    # Empty / all-missing file list -> None, same "nothing to sign" contract.
    assert corpus_identity.corpus_signature(d, files=[]) is None
    assert corpus_identity.corpus_signature(d, files=[d / "missing.md"]) is None


# ---------------------------------------------------------------------------
# corpus_build — single source -> two projections
# ---------------------------------------------------------------------------

def test_build_produces_both_projections(tmp_path):
    _write_source(tmp_path / "src")
    ds = tmp_path / "golden" / "x"
    meta = corpus_build.build_golden(tmp_path / "src", ds, now="2026-06-23")

    # retrieval view
    assert (ds / "corpus.jsonl").is_file()
    assert (ds / "queries.jsonl").is_file()
    qrels = (ds / "qrels" / "test.tsv").read_text(encoding="utf-8").splitlines()
    assert qrels[0].startswith("query-id")
    # Issue-A: qrels mark the FIRST-hop (query-targeted) evidence only (d_a), not the full
    # chain — the retrieval metric measures "find the entry doc", hop-independent.
    rows = [r for r in qrels[1:] if r.startswith("q0001\t")]
    assert [r.split("\t")[1] for r in rows] == ["d_a"]
    # but the AGENT view keeps the full chain (both d_a and d_b)
    agent_q0 = json.loads((ds / "queries.json").read_text(encoding="utf-8"))[0]
    assert agent_q0["evidence_ids"] == ["d_a", "d_b"]

    # agent view
    agent_q = json.loads((ds / "queries.json").read_text(encoding="utf-8"))
    assert agent_q[0]["answer"] == "Quill" and agent_q[0]["question_type"] == "two_hop"
    assert (ds / "corpus-dir" / "d_a.txt").is_file()  # materialized raw docs

    # metadata carries the 635 identity fields + signature
    assert meta["contamination_class"] == "private-synthetic"
    assert meta["type_axis"] == "prose" and meta["suite"] == "test-suite"
    assert meta["query_type_distribution"] == {"two_hop": 2}
    assert meta["corpus_signature"] == corpus_identity.corpus_signature(ds)


def test_build_rejects_unknown_evidence(tmp_path):
    src = tmp_path / "src"
    _write_source(src)
    qp = src / "queries.json"
    q = json.loads(qp.read_text(encoding="utf-8"))
    q[0]["evidence_ids"] = ["d_a", "d_nonexistent"]
    qp.write_text(json.dumps(q), encoding="utf-8")
    with pytest.raises(ValueError, match="unknown evidence"):
        corpus_build.build_golden(src, tmp_path / "golden" / "x")


# ---------------------------------------------------------------------------
# corpus_certify — verdict + fidelity derived from the closed-book pass
# ---------------------------------------------------------------------------

def test_certify_passes_when_unmemorizable():
    queries = [{"query": "q1", "answer": "a1"}, {"query": "q2", "answer": "a2"},
               {"query": "q3", "answer": "a3"}, {"query": "q4", "answer": "a4"}]
    # Mock: nothing answerable closed-book -> retained=all, n_dropped=0.
    with patch("jseval.utility_calibrate.closed_book_filter", return_value=([0, 1, 2, 3], 0)):
        res = corpus_certify.certify_corpus(queries, model="haiku", threshold=0.15, now="2026-06-23")
    cert = res["closed_book_certification"]
    assert cert["closed_book_accuracy"] == 0.0 and cert["passed"] is True
    assert cert["n_memorizable"] == 0 and cert["n_queries"] == 4
    # Issue-2: closed-book measures memory-independence, NOT retrieval difficulty.
    assert res["fidelity"]["memory_independence"] == 1.0
    assert res["fidelity"]["retrieval_difficulty"] is None  # populated post-retrieval-run
    assert "difficulty" not in res["fidelity"]  # no misleading closed-book "difficulty"


def test_certify_fails_when_memorizable():
    queries = [{"query": f"q{i}", "answer": f"a{i}"} for i in range(4)]
    # Mock: 2 of 4 answerable closed-book -> contamination 0.5 > threshold.
    with patch("jseval.utility_calibrate.closed_book_filter", return_value=([0, 1], 2)):
        res = corpus_certify.certify_corpus(queries, threshold=0.15, now="2026-06-23")
    cert = res["closed_book_certification"]
    assert cert["closed_book_accuracy"] == 0.5 and cert["passed"] is False
    assert res["fidelity"]["memory_independence"] == 0.5
    assert res["fidelity"]["retrieval_difficulty"] is None


def test_retrieval_difficulty_label_from_ndcg():
    # Issue-2: retrieval difficulty comes from nDCG@10 (post-run), not closed-book.
    # A high-nDCG corpus is retrieval-EASY (our synth corpus: nDCG 0.98).
    assert corpus_certify.retrieval_difficulty_label(0.98) == "easy"
    assert corpus_certify.retrieval_difficulty_label(0.65) == "moderate"
    assert corpus_certify.retrieval_difficulty_label(0.40) == "hard"


# ---------------------------------------------------------------------------
# descriptor_collision_report — the qrel self-consistency check (tempdoc 664)
# ---------------------------------------------------------------------------

def test_descriptor_collision_report_flags_gold_involved_collision():
    # gold1 and distractor1 accidentally share a title -> qrel-corrupting collision.
    docs = [
        {"_id": "gold1", "title": "The vineyard in the sunny valley", "text": "..."},
        {"_id": "distractor1", "title": "The vineyard in the sunny valley", "text": "..."},
        {"_id": "distractor2", "title": "The reactor in the eastern ridge", "text": "..."},
    ]
    queries = [{"query": "q1", "evidence_ids": ["gold1"]}]
    report = corpus_certify.descriptor_collision_report(docs, queries)
    assert report["passed"] is False
    assert report["n_groups"] == 1
    assert report["n_docs_involved"] == 2
    assert report["n_gold_involved"] == 1
    assert sorted(report["groups"][0]["doc_ids"]) == ["distractor1", "gold1"]


def test_descriptor_collision_report_distractor_only_does_not_fail():
    # Two distractors collide with each other, but no gold chain is involved — reported, not failed.
    docs = [
        {"_id": "gold1", "title": "The vineyard in the sunny valley", "text": "..."},
        {"_id": "distractor1", "title": "The reactor in the eastern ridge", "text": "..."},
        {"_id": "distractor2", "title": "The reactor in the eastern ridge", "text": "..."},
    ]
    queries = [{"query": "q1", "evidence_ids": ["gold1"]}]
    report = corpus_certify.descriptor_collision_report(docs, queries)
    assert report["passed"] is True  # no gold-involved collision
    assert report["n_groups"] == 1
    assert report["n_docs_involved"] == 2
    assert report["n_gold_involved"] == 0


def test_descriptor_collision_report_clean_corpus_passes():
    docs = [
        {"_id": "gold1", "title": "The vineyard in the sunny valley", "text": "..."},
        {"_id": "distractor1", "title": "The reactor in the eastern ridge", "text": "..."},
    ]
    queries = [{"query": "q1", "evidence_ids": ["gold1"]}]
    report = corpus_certify.descriptor_collision_report(docs, queries)
    assert report["passed"] is True
    assert report["n_groups"] == 0
    assert report["n_docs_involved"] == 0


def test_descriptor_collision_report_without_queries_reports_but_cannot_fail():
    docs = [
        {"_id": "a", "title": "Same Title", "text": "..."},
        {"_id": "b", "title": "Same Title", "text": "..."},
    ]
    report = corpus_certify.descriptor_collision_report(docs)  # queries omitted
    assert report["n_groups"] == 1
    assert report["n_gold_involved"] == 0
    assert report["passed"] is True


# ---------------------------------------------------------------------------
# regeneration_determinism_report — the certification-time "seeded -> reproducible"
# verification check (tempdoc 664, seventh pass)
# ---------------------------------------------------------------------------

_FULL_PROVENANCE = {
    "method": "procedural-fabricated", "axis": "prose", "lang": "en", "seed": 1,
    "hops": 1, "distractor_ratio": 3, "semantic": True, "n_chains": 3, "doc_words": 60,
}


def test_regeneration_determinism_skips_when_provenance_missing():
    report = corpus_certify.regeneration_determinism_report(None)
    assert report["passed"] is None
    assert "not applicable" in report["reason"]


def test_regeneration_determinism_skips_hand_authored_corpus():
    report = corpus_certify.regeneration_determinism_report({"method": "hand-authored-fabricated"})
    assert report["passed"] is None
    assert "hand-authored-fabricated" in report["reason"]


def test_regeneration_determinism_skips_incomplete_provenance():
    # missing n_chains/doc_words — a corpus certified before the tempdoc 664 provenance fix.
    incomplete = {k: v for k, v in _FULL_PROVENANCE.items() if k not in ("n_chains", "doc_words")}
    report = corpus_certify.regeneration_determinism_report(incomplete)
    assert report["passed"] is None
    assert "n_chains" in report["reason"] and "doc_words" in report["reason"]


def test_regeneration_determinism_real_regeneration_passes():
    """Unmocked — a real end-to-end run of the certify-level check (mirrors
    test_generate_is_deterministic_across_processes but through the public certify-level
    function, confirming the wiring, not just the underlying generate() fix)."""
    report = corpus_certify.regeneration_determinism_report(_FULL_PROVENANCE)
    assert report["passed"] is True
    assert report["method"] == "cross-process-regeneration-diff"


def test_regeneration_determinism_flags_a_real_mismatch():
    """Mocked subprocess: simulate a mismatch (the pre-fix bug class) without needing to actually
    reintroduce non-determinism into corpus_generate.py."""
    import subprocess as _sp

    calls = {"n": 0}

    def fake_run(cmd, **kwargs):
        # cmd = [python, "-c", script, out_dir, axis, lang, seed, hops, ratio, semantic,
        #        n_chains, doc_words] -- the output dir is always the 4th element (index 3).
        out_dir = Path(cmd[3])
        out_dir.mkdir(parents=True, exist_ok=True)
        calls["n"] += 1
        (out_dir / "docs.jsonl").write_text(f"run{calls['n']}\n", encoding="utf-8")
        (out_dir / "queries.json").write_text("[]", encoding="utf-8")
        return _sp.CompletedProcess(cmd, 0, "", "")

    # tempdoc 664 (twelfth pass): the subprocess call now lives in corpus_generate.regenerate_and_diff
    # (extracted, shared with the pytest determinism test) rather than in corpus_certify itself.
    with patch("jseval.corpus_generate.subprocess.run", side_effect=fake_run):
        report = corpus_certify.regeneration_determinism_report(_FULL_PROVENANCE)
    assert report["passed"] is False
    assert "docs.jsonl" in report["mismatched_files"]


# ---------------------------------------------------------------------------
# corpus_fidelity — the retrieval-difficulty gate (§D.5)
# ---------------------------------------------------------------------------

def _assess(tmp_path, ndcg, leak_rate, *, modes=("bm25_splade",), comparable=True):
    """Run assess_fidelity over a built corpus with execute_run + shortcut mocked.

    ``ndcg`` is a float (single mode) or a ``{mode: ndcg}`` dict (multi-mode).
    """
    _write_source(tmp_path / "src")
    ds = tmp_path / "datasets" / "golden" / "x"
    corpus_build.build_golden(tmp_path / "src", ds)
    with patch("jseval.run.execute_run", return_value=_summary(ndcg, comparable=comparable)), \
         patch("jseval.corpus_fidelity.shortcut_leak_rate",
               return_value=(leak_rate, int(leak_rate * 2))):
        return corpus_fidelity.assess_fidelity(
            ds, "golden/x", "http://127.0.0.1:0", modes=modes)


def test_fidelity_passes_in_band_and_genuine_multihop(tmp_path):
    r = _assess(tmp_path, ndcg=0.70, leak_rate=0.0)
    assert r["passed"] is True and r["in_band"] is True
    assert r["retrieval_ndcg"] == 0.70 and r["retrieval_difficulty"] == "moderate"
    assert r["shortcut_leak_rate"] == 0.0


def test_fidelity_fails_when_trivially_easy(tmp_path):
    r = _assess(tmp_path, ndcg=0.97, leak_rate=0.0)  # above band -> toy
    assert r["passed"] is False and r["in_band"] is False
    assert r["retrieval_difficulty"] == "easy"


def test_fidelity_fails_when_broken(tmp_path):
    r = _assess(tmp_path, ndcg=0.10, leak_rate=0.0)  # below band -> broken/unretrievable
    assert r["passed"] is False and r["in_band"] is False


def test_fidelity_fails_when_shortcut_leaky(tmp_path):
    r = _assess(tmp_path, ndcg=0.70, leak_rate=0.5)  # in-band but not genuine multi-hop
    assert r["passed"] is False and r["in_band"] is True
    assert r["shortcut_leak_rate"] == 0.5


def test_fidelity_surfaces_headline_comparable_and_per_mode(tmp_path):
    # Multi-mode: the diagnostic R-3 contrast (lexical fails, hybrid rescues) must be recorded,
    # the headline = last mode, and the headline's comparability must be surfaced for credibility.
    r = _assess(tmp_path, ndcg={"bm25_splade": 0.13, "hybrid": 0.75}, leak_rate=0.0,
                modes=("bm25_splade", "hybrid"))
    assert r["retrieval_mode"] == "hybrid" and r["retrieval_ndcg"] == 0.75
    assert r["retrieval_ndcg_by_mode"] == {"bm25_splade": 0.13, "hybrid": 0.75}
    assert r["comparable"] is True and r["comparability_reasons"] == []


def test_fidelity_records_incomparable_headline(tmp_path):
    # A non-comparable run (readiness/ANN/error-rate failed) must be flagged, not silently passed.
    r = _assess(tmp_path, ndcg=0.70, leak_rate=0.0, comparable=False)
    assert r["comparable"] is False


def test_certify_computes_descriptor_collisions_end_to_end(tmp_path):
    """tempdoc 664 (seventh-pass regression guard): `corpus-certify` must actually COMPUTE
    descriptor_collisions against the real materialized `corpus.jsonl`, not just the pure
    `descriptor_collision_report()` function in isolation. The sixth-pass fix was live-verified by
    calling the function directly (bypassing the CLI's file-loading), which hid a wrong filename
    (`docs.jsonl` instead of the real `corpus.jsonl` `corpus_build.py` writes) — this test exercises
    the real CLI path end-to-end, mocking only the closed-book call."""
    from click.testing import CliRunner

    from jseval.cli import main

    _write_source(tmp_path / "src")  # 4 clean, non-colliding docs (Alpha/Bex Ko/Gamma/Tas Vrel)
    ds = tmp_path / "datasets" / "golden" / "x"
    corpus_build.build_golden(tmp_path / "src", ds)
    assert (ds / "corpus.jsonl").is_file()  # sanity: confirms the real filename this test guards

    with patch("jseval.utility_calibrate.closed_book_filter", return_value=([], 0)):
        r = CliRunner().invoke(main, ["corpus-certify", "--dataset", "x",
                                      "--datasets-dir", str(tmp_path / "datasets")])
    assert r.exit_code == 0, r.output
    fid = json.loads((ds / "metadata.json").read_text(encoding="utf-8"))["fidelity"]
    assert "descriptor_collisions" in fid, "descriptor_collisions was never computed by the real CLI path"
    assert fid["descriptor_collisions"]["passed"] is True
    assert fid["descriptor_collisions"]["n_groups"] == 0


def test_certify_does_not_clobber_existing_retrieval_fidelity(tmp_path):
    """Regression (the merge-clobber bug): corpus-certify running AFTER corpus-fidelity must MERGE
    the fidelity block, not overwrite it — its placeholder `retrieval_difficulty: None` must not
    wipe the retrieval numbers corpus-fidelity already wrote. The two co-equal gates share the block.
    """
    from click.testing import CliRunner

    from jseval.cli import main

    _write_source(tmp_path / "src")
    ds = tmp_path / "datasets" / "golden" / "x"
    corpus_build.build_golden(tmp_path / "src", ds)
    # seed a retrieval fidelity block (as corpus-fidelity would have written it)
    meta = json.loads((ds / "metadata.json").read_text(encoding="utf-8"))
    meta["fidelity"] = {"retrieval_ndcg": 0.70, "retrieval_difficulty": "moderate",
                        "retrieval_ndcg_by_mode": {"bm25_splade": 0.13, "hybrid": 0.70},
                        "comparable": True, "passed": True}
    (ds / "metadata.json").write_text(json.dumps(meta), encoding="utf-8")
    # certify emits memory_independence + the None placeholder that must NOT clobber
    fake = {"contamination_class": "private-synthetic",
            "closed_book_certification": {"passed": True, "closed_book_accuracy": 0.0},
            "fidelity": {"memory_independence": 1.0, "retrieval_difficulty": None}}
    with patch("jseval.corpus_certify.certify_corpus", return_value=fake):
        r = CliRunner().invoke(main, ["corpus-certify", "--dataset", "x",
                                      "--datasets-dir", str(tmp_path / "datasets")])
    assert r.exit_code == 0, r.output
    fid = json.loads((ds / "metadata.json").read_text(encoding="utf-8"))["fidelity"]
    assert fid["retrieval_ndcg"] == 0.70 and fid["retrieval_difficulty"] == "moderate"
    assert fid["retrieval_ndcg_by_mode"] == {"bm25_splade": 0.13, "hybrid": 0.70}
    assert fid["comparable"] is True and fid["memory_independence"] == 1.0


def test_fidelity_does_not_clobber_existing_memory_independence(tmp_path):
    """Symmetric regression (D2): corpus-fidelity running AFTER corpus-certify must MERGE the fidelity
    block — its retrieval fields must not wipe the `memory_independence` certify already wrote. This is
    the design's intended run order (memory gate first, retrieval gate second).
    """
    from click.testing import CliRunner

    from jseval.cli import main

    _write_source(tmp_path / "src")
    ds = tmp_path / "datasets" / "golden" / "x"
    corpus_build.build_golden(tmp_path / "src", ds)
    # seed a memory block (as corpus-certify would have written it, with the None placeholder)
    meta = json.loads((ds / "metadata.json").read_text(encoding="utf-8"))
    meta["fidelity"] = {"memory_independence": 1.0, "retrieval_difficulty": None}
    (ds / "metadata.json").write_text(json.dumps(meta), encoding="utf-8")
    # This test exercises the fidelity-metadata MERGE; it mocks the pipeline instead of
    # running a real backend, so the tempdoc 644 Axis 2 capability guard (which would refuse
    # on an unreachable backend) is neutralized here — it has dedicated coverage in
    # tests/test_preflight.py::TestAssertCapabilities.
    with patch("jseval.run.execute_run", return_value=_summary(0.70)), \
         patch("jseval.corpus_fidelity.shortcut_leak_rate", return_value=(0.0, 0)), \
         patch("jseval.commands.corpus.assert_run_capabilities"):
        r = CliRunner().invoke(main, ["corpus-fidelity", "--dataset", "x",
                                      "--datasets-dir", str(tmp_path / "datasets")])
    assert r.exit_code == 0, r.output
    fid = json.loads((ds / "metadata.json").read_text(encoding="utf-8"))["fidelity"]
    # retrieval fields written; certify's memory_independence preserved; the real label replaces the placeholder
    assert fid["retrieval_ndcg"] == 0.70 and fid["retrieval_difficulty"] == "moderate"
    assert fid["memory_independence"] == 1.0


def test_validator_warns_on_failed_fidelity():
    msgs, _ = _validate({"suite": "s", "contamination_class": "private-synthetic",
                         "closed_book_certification": {"passed": True, "closed_book_accuracy": 0.0},
                         "fidelity": {"passed": False, "retrieval_ndcg": 0.97,
                                      "band": [0.40, 0.85], "shortcut_leak_rate": 0.0}})
    assert any("FAILED the fidelity gate" in x for x in msgs)


# tempdoc 664 (post-review fix): descriptor_collisions must surface the same way its two sibling
# fidelity sub-checks already do — the original wiring computed and persisted the verdict but
# never warned on it, so a corpus with a real collision defect (confirmed: `needle-burial-v1`)
# produced zero signal in normal `jseval run`/corpus-load usage.

def test_validator_warns_on_failed_descriptor_collisions():
    msgs, _ = _validate({"suite": "s", "contamination_class": "private-synthetic",
                         "closed_book_certification": {"passed": True, "closed_book_accuracy": 0.0},
                         "fidelity": {"passed": True, "retrieval_ndcg": 0.70,
                                      "band": [0.40, 0.85], "shortcut_leak_rate": 0.0,
                                      "descriptor_collisions": {"passed": False, "n_groups": 24,
                                                                 "n_docs_involved": 51, "n_gold_involved": 7}}})
    assert any("FAILED the descriptor-collision check" in x for x in msgs)
    assert any("7 gold chain(s)" in x for x in msgs)


def test_validator_warns_on_missing_descriptor_collisions_verdict():
    """A corpus certified before this check existed has no `descriptor_collisions` key at all —
    flagged (symmetric to the missing closed_book_certification / fidelity checks), not silently
    treated as passing."""
    msgs, _ = _validate({"suite": "s", "contamination_class": "private-synthetic",
                         "closed_book_certification": {"passed": True, "closed_book_accuracy": 0.0},
                         "fidelity": {"passed": True, "retrieval_ndcg": 0.70,
                                      "band": [0.40, 0.85], "shortcut_leak_rate": 0.0}})
    assert any("no descriptor_collisions verdict" in x for x in msgs)


def test_validator_quiet_on_passing_descriptor_collisions():
    msgs, _ = _validate({"suite": "s", "contamination_class": "private-synthetic",
                         "closed_book_certification": {"passed": True, "closed_book_accuracy": 0.0},
                         "fidelity": {"passed": True, "retrieval_ndcg": 0.70,
                                      "band": [0.40, 0.85], "shortcut_leak_rate": 0.0,
                                      "descriptor_collisions": {"passed": True, "n_groups": 0,
                                                                 "n_docs_involved": 0, "n_gold_involved": 0}}})
    assert not any("descriptor-collision" in x or "descriptor_collisions" in x for x in msgs)


# tempdoc 664 (seventh pass): regeneration_determinism validator warnings — symmetric to the three
# checks above, plus the extra "skip is silent" state this check alone has.

def test_validator_warns_on_failed_regeneration_determinism():
    msgs, _ = _validate({"suite": "s", "contamination_class": "private-synthetic",
                         "closed_book_certification": {"passed": True, "closed_book_accuracy": 0.0},
                         "fidelity": {"passed": True, "retrieval_ndcg": 0.70,
                                      "band": [0.40, 0.85], "shortcut_leak_rate": 0.0,
                                      "regeneration_determinism": {"passed": False,
                                                                    "mismatched_files": ["docs.jsonl"]}}})
    assert any("FAILED regeneration-determinism" in x for x in msgs)


def test_validator_warns_on_missing_regeneration_determinism_verdict():
    msgs, _ = _validate({"suite": "s", "contamination_class": "private-synthetic",
                         "closed_book_certification": {"passed": True, "closed_book_accuracy": 0.0},
                         "fidelity": {"passed": True, "retrieval_ndcg": 0.70,
                                      "band": [0.40, 0.85], "shortcut_leak_rate": 0.0}})
    assert any("no regeneration_determinism verdict" in x for x in msgs)


def test_validator_quiet_on_passing_regeneration_determinism():
    msgs, _ = _validate({"suite": "s", "contamination_class": "private-synthetic",
                         "closed_book_certification": {"passed": True, "closed_book_accuracy": 0.0},
                         "fidelity": {"passed": True, "retrieval_ndcg": 0.70,
                                      "band": [0.40, 0.85], "shortcut_leak_rate": 0.0,
                                      "regeneration_determinism": {"passed": True}}})
    assert not any("regeneration-determinism" in x or "regeneration_determinism" in x for x in msgs)


def test_validator_quiet_on_skipped_regeneration_determinism():
    """A deliberate skip (hand-authored/incomplete-provenance corpus) is NOT a failure and NOT a
    missing verdict — it must stay silent, distinct from the other two states."""
    msgs, _ = _validate({"suite": "s", "contamination_class": "private-synthetic",
                         "closed_book_certification": {"passed": True, "closed_book_accuracy": 0.0},
                         "fidelity": {"passed": True, "retrieval_ndcg": 0.70,
                                      "band": [0.40, 0.85], "shortcut_leak_rate": 0.0,
                                      "regeneration_determinism": {"passed": None,
                                                                    "reason": "not applicable"}}})
    assert not any("regeneration-determinism" in x or "regeneration_determinism" in x for x in msgs)


def test_generate_produces_unique_multihop_source(tmp_path):
    from jseval import corpus_generate as cg
    stats = cg.generate(tmp_path / "g", axis="prose", n_chains=5, hops=2,
                        distractor_ratio=4, doc_words=80, seed=1)
    import json as _j
    docs = [_j.loads(l) for l in (tmp_path / "g" / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    ids = [d["_id"] for d in docs]
    assert len(ids) == len(set(ids))  # globally unique (the infinite-loop fix)
    assert stats["distractor_docs"] >= stats["gold_chains"] * 2  # distractors dominate
    qs = _j.loads((tmp_path / "g" / "queries.json").read_text(encoding="utf-8"))
    assert all(len(q["evidence_ids"]) >= 2 for q in qs)  # genuine multi-hop by construction
    answers = [q["answer"] for q in qs]
    assert len(answers) == len(set(answers))  # Issue-C: unique answer per chain (no shared pool)


def test_generate_is_deterministic_across_processes(tmp_path):
    """Regression guard for tempdoc 664: `generate()`'s docstring claims "seeded -> reproducible",
    but the RNG seed used to derive `axis_offset` from `hash(axis)` was per-process-randomized
    (Python's `str.__hash__`, PEP 456) unless `PYTHONHASHSEED` is pinned — invisible to an
    in-process test (like `test_generate_produces_unique_multihop_source` above) because `hash()`
    is stable *within* one process. This test spawns `generate()` in two SEPARATE `python`
    processes with the identical nominal seed and diffs the output, closing the exact blind spot
    that hid the bug (confirmed empirically pre-fix: 280/280 docs differed between two runs)."""
    from jseval import corpus_generate as cg

    out1, out2 = tmp_path / "run1", tmp_path / "run2"
    result = cg.regenerate_and_diff(
        out1, out2, axis="prose", lang="en", n_chains=5, hops=1,
        distractor_ratio=3, doc_words=60, seed=42, semantic=True,
    )
    assert result["ok"], result.get("error")
    assert not result["mismatched_files"], f"differs between two same-seed regenerations: {result['mismatched_files']}"
    # meta.json isn't diffed by regenerate_and_diff (only docs.jsonl/queries.json -- the certification-
    # relevant content); confirm it too, matching the original test's coverage.
    assert (out1 / "meta.json").read_text(encoding="utf-8") == (out2 / "meta.json").read_text(encoding="utf-8")

    # tempdoc 664 (twelfth pass): gold and distractor docs are now interleaved (not written as
    # two unbroken blocks) -- confirm the change actually happened, not just that it's still
    # deterministic. 5 gold chains x 2 docs (hops=1) = 10 gold doc ids among 40 total.
    doc_ids = [json.loads(line)["_id"] for line in (out1 / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    queries = json.loads((out1 / "queries.json").read_text(encoding="utf-8"))
    gold_ids = {eid for q in queries for eid in q["evidence_ids"]}
    assert len(doc_ids) == 40 and len(gold_ids) == 10
    gold_positions = [i for i, did in enumerate(doc_ids) if did in gold_ids]
    assert gold_positions != list(range(10)), "gold docs are still one unbroken leading block -- not interleaved"


@pytest.mark.parametrize("axis,lang", [("code", "en"), ("tabular", "en"), ("prose", "de")])
def test_semantic_mode_defeats_grep_on_all_axes(tmp_path, axis, lang):
    """The grep-defeat invariant (tempdoc 635 hard-non-prose members): with semantic=True the
    query must NOT name its head doc, so a literal grep / pure-BM25 cannot find the entry point
    and dense retrieval is required (a real ceiling, not a trivial nDCG 1.0). Deterministic,
    no-stack structural guard mirroring the prose member's property."""
    from jseval import corpus_generate as cg
    out = tmp_path / "g"
    cg.generate(out, axis=axis, lang=lang, n_chains=5, hops=2,
                distractor_ratio=4, doc_words=80, seed=1, semantic=True)
    import json as _j
    qs = _j.loads((out / "queries.json").read_text(encoding="utf-8"))
    docs = {d["_id"]: d for d in
            (_j.loads(l) for l in (out / "docs.jsonl").read_text(encoding="utf-8").splitlines())}
    assert qs, "semantic generation produced no queries"
    for q in qs:
        head = q["evidence_ids"][0]                 # the query-targeted (qrels) head doc id
        assert head in docs                          # head exists in the corpus
        # grep-defeat: the query does not name its own head entity (verbatim mode WOULD)
        assert head not in q["query"].lower(), f"{axis}/{lang}: query names head {head!r}"
        # provenance records the semantic flag truthfully
    src_meta = _j.loads((out / "meta.json").read_text(encoding="utf-8"))
    assert src_meta["generation_provenance"]["semantic"] is True


def test_validator_quiet_on_passing_fidelity():
    msgs, _ = _validate({"suite": "s", "contamination_class": "private-synthetic",
                         "closed_book_certification": {"passed": True, "closed_book_accuracy": 0.0},
                         "fidelity": {"passed": True, "retrieval_ndcg": 0.70,
                                      "band": [0.40, 0.85], "shortcut_leak_rate": 0.0}})
    assert not any("fidelity gate" in x for x in msgs)


# ---------------------------------------------------------------------------
# metadata validation — warns on uncertified / failed-cert self-demo corpora
# ---------------------------------------------------------------------------

def _validate(meta_dict: dict) -> list[str]:
    """Run _validate_golden_set over an in-memory metadata dict; return warning texts."""
    import tempfile
    td = Path(tempfile.mkdtemp())
    mp = td / "metadata.json"
    mp.write_text(json.dumps(meta_dict), encoding="utf-8")
    m = CorpusMeta(name="golden/x", source="golden", doc_count=4, query_count=2)
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        corpora._validate_golden_set(mp, {"q1": QueryRecord(text="q")}, m)
    return [str(x.message) for x in w], m


def test_validator_warns_on_uncertified_suite_member():
    msgs, m = _validate({"suite": "s", "contamination_class": "private-synthetic"})
    assert any("no closed_book_certification" in x for x in msgs)
    assert m.contamination_class == "private-synthetic" and m.suite == "s"


def test_validator_warns_on_failed_certification():
    msgs, _ = _validate({"suite": "s", "contamination_class": "private-synthetic",
                         "closed_book_certification": {"passed": False, "closed_book_accuracy": 0.5}})
    assert any("FAILED closed-book certification" in x for x in msgs)


def test_validator_quiet_on_passing_certified_corpus():
    # Quiet only when BOTH co-equal axes pass (Issue-D: a missing fidelity verdict is flagged).
    msgs, m = _validate({"suite": "s", "contamination_class": "private-synthetic",
                         "closed_book_certification": {"passed": True, "closed_book_accuracy": 0.0},
                         "fidelity": {"passed": True}})
    assert not any("certification" in x or "fidelity" in x for x in msgs)
    assert m.closed_book_certification["passed"] is True


def test_validator_warns_on_missing_fidelity_verdict():
    # Issue-D: closed-book passes but no fidelity verdict at all -> warn (axes validated alike).
    msgs, _ = _validate({"suite": "s", "contamination_class": "private-synthetic",
                         "closed_book_certification": {"passed": True, "closed_book_accuracy": 0.0}})
    assert any("no fidelity verdict" in x for x in msgs)


def test_validator_silent_on_public_corpus_without_suite():
    # A non-suite (public/comparison) corpus needn't be certified — no 635 warnings.
    msgs, _ = _validate({"version": "1.0", "source": "EnronQA"})
    assert not any("contamination" in x or "certification" in x for x in msgs)


def test_corpus_meta_roundtrips_new_fields():
    m = CorpusMeta(name="x", source="golden", doc_count=1, query_count=1,
                   contamination_class="private-synthetic", type_axis="prose", suite="s",
                   fidelity={"memory_independence": 1.0, "retrieval_difficulty": "easy"},
                   corpus_signature="abc")
    assert m.contamination_class == "private-synthetic" and m.fidelity["memory_independence"] == 1.0


def test_corpus_fidelity_refuses_start_backend_without_clean():
    """A self-contained fidelity run must start from a clean index (tempdoc 635 verification-binding):
    --start-backend without --clean is refused before any backend spins up, so a dirty index can't
    silently pollute the verdict."""
    from click.testing import CliRunner

    from jseval.cli import main

    r = CliRunner().invoke(main, ["corpus-fidelity", "--dataset", "x", "--start-backend"])
    assert r.exit_code != 0
    assert "requires --clean" in r.output

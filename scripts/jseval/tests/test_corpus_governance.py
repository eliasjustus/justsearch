"""Tests for the corpus-as-governed-artifact machinery (tempdoc 635).

Pure-function / fixture tests over the corpus identity, certification, build, and
metadata-validation surfaces — NO live claude, NO dev stack (the closed-book call is
mocked). Mirrors ``test_corpora.py`` + ``test_utility_comparison.py`` inline-fixture style.
"""

from __future__ import annotations

import json
import re
import warnings
from pathlib import Path
from unittest.mock import patch

import pytest

from jseval import corpus_build, corpus_certify, corpus_fidelity, corpus_identity, corpora
from jseval import corpus_generate
from jseval.types import CorpusMeta, QueryRecord

#: The committed fixture entity bank (tempdoc 767). `generate()` mints chain entities
#: type- and length-matched against a frozen bank instead of the deleted syllable pools,
#: so every call site needs one. `tests/fixtures/entity-bank-fixture/host-docs/` holds the
#: synthetic host documents it was harvested from — `test_entity_harvest.py` re-harvests
#: them and asserts the committed bank bytes still reproduce.
BANK = Path(__file__).resolve().parent / "fixtures" / "entity-bank-fixture"


# ---------------------------------------------------------------------------
# gold-chain ceiling — the (type, place, qualifier) triple-injectivity bound
# (tempdoc 624 scale-corpus: lifted from the over-conservative place-pool cap so a
# scale corpus can carry hundreds of distinct queries, not 26)
# ---------------------------------------------------------------------------

def test_max_semantic_chains_is_the_triple_lcm():
    import math
    # tempdoc 624 scale-corpus (pool-growth follow-up): en and de pools are both 21 types x
    # 44 places x 25 quals -> lcm = 23100 (pairwise coprime: 21=3*7, 44=2^2*11, 25=5^2), an
    # order of magnitude above the old len(places)=26 cap and above the prior 780 triple-lcm.
    assert corpus_generate._max_semantic_chains("en") == math.lcm(21, 44, 25) == 23100
    assert corpus_generate._max_semantic_chains("de") == 23100


def test_gold_triples_distinct_and_queries_unambiguous_above_old_cap(tmp_path):
    """The load-bearing correctness claim behind lifting the cap: at n_chains=130 (5x the old
    26 ceiling) every gold head is uniquely identified by its query's (type, place, qualifier)
    synonym triple — no two gold heads share a title, and the descriptor-collision gate finds
    zero gold-involved collisions."""
    n = 130
    corpus_generate.generate(tmp_path, axis="prose", lang="en", n_chains=n, hops=2,
                             distractor_ratio=1, doc_words=60, suite="test", seed=7,
                             semantic=True, entity_bank=BANK)
    queries = json.loads((tmp_path / "queries.json").read_text(encoding="utf-8"))
    # cap no longer clamps to 26 — all n gold chains (hence n distinct queries) are emitted.
    assert len(queries) == n
    # gold reservations are all distinct triples (the injectivity the cap now permits).
    reserved = corpus_generate._gold_descriptor_reservations(n, "en")
    assert len(reserved) == n
    # and no gold head title collides with any other doc's (the qrel-corrupting failure the
    # old cap prevented by brute-force uniqueness of place alone).
    docs = [json.loads(line) for line in (tmp_path / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    gold_ids = {e for q in queries for e in q["evidence_ids"]}
    report = corpus_certify.descriptor_collision_report(docs, queries)
    assert report["n_gold_involved"] == 0, report
    assert report["passed"] is True, report


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

def _full_provenance(**overrides):
    """A complete payload-v2 `generation_provenance` (tempdoc 767).

    The bank reference is recorded exactly as `generate()` would record it — POSIX-relative
    to the jseval package root — so `regeneration_determinism_report` resolves it the same
    way a committed corpus's provenance is resolved.
    """
    from jseval import corpus_generate, entity_bank as _eb

    gp = {
        "method": "procedural-fabricated", "axis": "prose", "lang": "en", "seed": 1,
        "hops": 1, "distractor_ratio": 3, "semantic": True, "n_chains": 3, "doc_words": 60,
        "payload_version": corpus_generate.PAYLOAD_VERSION,
        "entity_bank": _eb.bank_reference(BANK),
        "entity_bank_sha256": _eb.bank_sha256(BANK),
    }
    gp.update(overrides)
    return gp


def test_regeneration_determinism_skips_when_provenance_missing():
    report = corpus_certify.regeneration_determinism_report(None)
    assert report["passed"] is None
    assert "not applicable" in report["reason"]


def test_regeneration_determinism_skips_hand_authored_corpus():
    report = corpus_certify.regeneration_determinism_report({"method": "hand-authored-fabricated"})
    assert report["passed"] is None
    assert "hand-authored-fabricated" in report["reason"]


def test_regeneration_determinism_skips_incomplete_legacy_provenance():
    # missing n_chains/doc_words AND payload_version — a corpus certified before the
    # tempdoc 664 provenance fix, i.e. the legacy (pre-767) key set.
    incomplete = {k: v for k, v in _full_provenance().items()
                  if k not in ("n_chains", "doc_words", "payload_version",
                               "entity_bank", "entity_bank_sha256")}
    report = corpus_certify.regeneration_determinism_report(incomplete)
    assert report["passed"] is None
    assert "n_chains" in report["reason"] and "doc_words" in report["reason"]


def test_regeneration_determinism_fails_loudly_on_incomplete_v2_provenance():
    """tempdoc 767 coupling guard: a corpus that DECLARES a payload version but is missing a
    required key must FAIL, not skip. The pre-767 behaviour returned `passed: None` for any
    missing key, so renaming or dropping a provenance key would have silently converted the
    determinism proof into "check not run" — indistinguishable from "check passed" in the
    certification report."""
    for dropped in ("entity_bank", "entity_bank_sha256", "n_chains", "doc_words"):
        gp = {k: v for k, v in _full_provenance().items() if k != dropped}
        report = corpus_certify.regeneration_determinism_report(gp)
        assert report["passed"] is False, dropped
        assert dropped in report["reason"], report


def test_regeneration_determinism_fails_on_unknown_payload_version():
    report = corpus_certify.regeneration_determinism_report(
        _full_provenance(payload_version="payload.v999"))
    assert report["passed"] is False
    assert "payload.v999" in report["reason"]


def test_regeneration_determinism_fails_on_entity_bank_digest_mismatch():
    """The bank is an INPUT to generation, so a corpus whose pinned bank digest no longer
    matches the on-disk bank is not reproducible — that must be a failure, not a pass."""
    report = corpus_certify.regeneration_determinism_report(
        _full_provenance(entity_bank_sha256="0" * 64))
    assert report["passed"] is False
    assert "digest mismatch" in report["reason"]


def test_regeneration_determinism_real_regeneration_passes():
    """Unmocked — a real end-to-end run of the certify-level check (mirrors
    test_generate_is_deterministic_across_processes but through the public certify-level
    function, confirming the wiring, not just the underlying generate() fix)."""
    report = corpus_certify.regeneration_determinism_report(_full_provenance())
    assert report["passed"] is True
    assert report["method"] == "cross-process-regeneration-diff"


def test_regeneration_determinism_flags_a_real_mismatch():
    """Mocked subprocess: simulate a mismatch (the pre-fix bug class) without needing to actually
    reintroduce non-determinism into corpus_generate.py."""
    import subprocess as _sp

    calls = {"n": 0}

    def fake_run(cmd, **kwargs):
        # cmd = [python, "-c", script, out_dir, params_json] -- tempdoc 767 replaced the
        # by-name-positionally-decoded argv tail with a single json blob (doc_words=None
        # is not expressible as `int(sys.argv[9])`), but the output dir is still the 4th
        # element (index 3), which is all this fake needs.
        out_dir = Path(cmd[3])
        out_dir.mkdir(parents=True, exist_ok=True)
        calls["n"] += 1
        (out_dir / "docs.jsonl").write_text(f"run{calls['n']}\n", encoding="utf-8")
        (out_dir / "queries.json").write_text("[]", encoding="utf-8")
        return _sp.CompletedProcess(cmd, 0, "", "")

    # tempdoc 664 (twelfth pass): the subprocess call now lives in corpus_generate.regenerate_and_diff
    # (extracted, shared with the pytest determinism test) rather than in corpus_certify itself.
    with patch("jseval.corpus_generate.subprocess.run", side_effect=fake_run):
        report = corpus_certify.regeneration_determinism_report(_full_provenance())
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
                        distractor_ratio=4, doc_words=80, seed=1, entity_bank=BANK)
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
        distractor_ratio=3, doc_words=60, seed=42, semantic=True, entity_bank=BANK,
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


# ---------------------------------------------------------------------------
# tempdoc 624 T.1 — construction-time descriptor-collision exclusion + the third
# combinatorial (qualifier) axis. 664 built DETECTION (descriptor_collision_report,
# tested above); these tests exercise the real generator fix that makes a
# gold-involved collision structurally impossible, not merely caught after the fact.
# ---------------------------------------------------------------------------

def test_generate_excludes_gold_reserved_descriptors_from_distractors(tmp_path):
    """Regression test for the original 664-measured bug: `_sem_for`'s distractor branch
    used to draw an INDEPENDENT uniform (type, place) pair from the SAME pool gold chains
    used, with no exclusion of gold-reserved combinations -- so a distractor could
    reproduce a gold chain's exact descriptor, corrupting that query's own qrel (664
    measured 7/20 gold-involved collisions in the committed needle-burial-v1 corpus).

    Generates a REAL corpus via `corpus_generate.generate()` at realistic semantic-mode
    scale -- n_chains=26 (`generate()`'s own semantic-mode place-pool cap) and
    distractor_ratio=30 (the ratio 624's confidence pass measured 94% distractor
    descriptor duplication at) -- and runs the real, unmodified
    `corpus_certify.descriptor_collision_report` against it. This is the regression test
    that would have caught the original bug: with the pre-fix uniform draw, a run at this
    scale reliably produced gold-involved collisions; with the gold-reserved exclusion,
    zero is now a structural guarantee, not a matter of luck.
    """
    from jseval import corpus_generate as cg

    stats = cg.generate(tmp_path / "g", axis="prose", lang="en", n_chains=26, hops=2,
                         distractor_ratio=30, doc_words=60, seed=624, semantic=True,
                         entity_bank=BANK)
    docs = [json.loads(line) for line in
            (tmp_path / "g" / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    queries = json.loads((tmp_path / "g" / "queries.json").read_text(encoding="utf-8"))

    report = corpus_certify.descriptor_collision_report(docs, queries)

    assert stats["gold_chains"] == 26, "sem-mode place-pool cap did not engage as expected"
    assert report["n_gold_involved"] == 0, (
        f"gold-involved descriptor collision at realistic scale (the exact defect tempdoc "
        f"664 measured): {report['groups']}"
    )
    assert report["passed"] is True


@pytest.mark.parametrize("lang", ["en", "de"])
def test_generate_excludes_gold_reserved_descriptors_from_distractors_multi_seed(lang, tmp_path):
    """Same construction-time guarantee as above, swept across seeds and both language
    pools (English + German) -- confirms the fix is structural (a property of the
    exclusion logic itself), not an artifact of one lucky seed."""
    from jseval import corpus_generate as cg

    for seed in range(10):
        out = tmp_path / f"g{seed}"
        cg.generate(out, axis="prose", lang=lang, n_chains=26, hops=2,
                    distractor_ratio=15, doc_words=60, seed=seed, semantic=True,
                    entity_bank=BANK)
        docs = [json.loads(line) for line in
                (out / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
        queries = json.loads((out / "queries.json").read_text(encoding="utf-8"))
        report = corpus_certify.descriptor_collision_report(docs, queries)
        assert report["n_gold_involved"] == 0, f"lang={lang} seed={seed}: {report['groups']}"


def test_generate_third_axis_keeps_distractor_duplication_low_at_scale(tmp_path):
    """Regression test for tempdoc 624 T.1 item 2 (the combinatorial third axis): the
    fixed 2-axis (type, place) pool is only 12 x 26 = 312 combinations, and 624's
    confidence-pass simulation found that even DISTRACTOR-ONLY (non-qrel-corrupting, but
    wasted-diversity) descriptor duplication reached 94% at n_chains=26,
    distractor_ratio=30 -- the scale needed to reach ~800 total docs.

    Asserts the real generator's distractor-only duplication rate at that SAME scale is
    now far below the measured pre-fix 94% figure -- a generous margin (25%), not a tight
    pin, since exact duplication is seed-dependent; the point is "meaningfully improved
    by an order of magnitude", not "reduced to some exact number".
    """
    from jseval import corpus_generate as cg

    stats = cg.generate(tmp_path / "g", axis="prose", lang="en", n_chains=26, hops=2,
                         distractor_ratio=30, doc_words=60, seed=624, semantic=True,
                         entity_bank=BANK)
    docs = [json.loads(line) for line in
            (tmp_path / "g" / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    queries = json.loads((tmp_path / "g" / "queries.json").read_text(encoding="utf-8"))
    report = corpus_certify.descriptor_collision_report(docs, queries)

    # hops=2 -> 3 docs per chain (2 link docs + 1 attribute doc), one descriptor-carrying
    # head doc per chain; distractor_ratio=30 against 78 gold docs is an exact multiple of
    # 3 (2340), so no partial trailing chain to account for.
    n_distractor_chains = stats["distractor_docs"] // 3
    duplication_rate = report["n_docs_involved"] / n_distractor_chains

    assert report["n_gold_involved"] == 0  # the construction-time guarantee still holds
    assert duplication_rate < 0.25, (
        f"distractor-descriptor duplication rate {duplication_rate:.2%} is not meaningfully "
        f"improved over the pre-fix ~94% baseline measured at this same scale "
        f"(tempdoc 624 confidence pass, item 3)"
    )


@pytest.mark.parametrize("axis,lang", [("code", "en"), ("tabular", "en"), ("prose", "de")])
def test_semantic_mode_defeats_grep_on_all_axes(tmp_path, axis, lang):
    """The grep-defeat invariant (tempdoc 635 hard-non-prose members): with semantic=True the
    query must NOT name its head doc, so a literal grep / pure-BM25 cannot find the entry point
    and dense retrieval is required (a real ceiling, not a trivial nDCG 1.0). Deterministic,
    no-stack structural guard mirroring the prose member's property."""
    from jseval import corpus_generate as cg
    out = tmp_path / "g"
    cg.generate(out, axis=axis, lang=lang, n_chains=5, hops=2,
                distractor_ratio=4, doc_words=80, seed=1, semantic=True, entity_bank=BANK)
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


# ---------------------------------------------------------------------------
# tempdoc 624 T.2 — the degraded-scan corpus member (axis="scan"). A new
# axis-renderer within the existing corpus artifact abstraction: documents
# materialize as degraded-scan PNGs (defeats a casual multimodal `Read`, per the
# tempdoc's live confidence-pass probe) while ground-truth `text` -- used for
# retrieval scoring, certification, and the agent's evidence view -- is unchanged.
# Pure-function / fixture tests only, no live claude, no dev stack.
# ---------------------------------------------------------------------------

def test_render_scan_image_produces_a_valid_png():
    pytest.importorskip("PIL")
    import io

    from PIL import Image

    from jseval import corpus_generate as cg

    png_bytes = cg.render_scan_image("Some fabricated chain text to render onto a page.", seed=1)
    img = Image.open(io.BytesIO(png_bytes))
    img.load()  # force decode -- confirms it's a genuinely valid PNG, not just a header
    assert img.format == "PNG"
    assert img.size[0] > 0 and img.size[1] > 0


def test_render_scan_image_deterministic_for_same_seed_varies_by_seed():
    pytest.importorskip("PIL")
    text = "The reactor in the northern marshlands was designed by the engineer Quenby."
    from jseval import corpus_generate as cg

    a1 = cg.render_scan_image(text, seed=42)
    a2 = cg.render_scan_image(text, seed=42)
    b = cg.render_scan_image(text, seed=7)
    assert a1 == a2, "same seed must render byte-identical PNGs (noise is the only randomized step)"
    assert a1 != b, "a different seed must vary the salt-and-pepper noise placement"


def test_render_scan_image_accepts_real_corpus_scale_input():
    """Sanity-bound calibration check (post-624-follow-up): the largest committed scan
    doc today (`635-corpora/synth-scan-v1`, doc_words=520) renders to ~3,888 chars of
    title+text at the only width/font_size any caller in this codebase ever passes
    (900px/13pt). A same-scale input must still render successfully -- the new bounds
    must have real headroom, not just theoretical headroom."""
    pytest.importorskip("PIL")
    import io

    from PIL import Image

    from jseval import corpus_generate as cg

    word = "reactor northern marshland engineer Quenby fabricated province delegate "
    text = (word * 80).strip()  # ~3,900 chars, matching the real worst-case doc
    assert len(text) > 3800

    png_bytes = cg.render_scan_image(text, seed=1)
    img = Image.open(io.BytesIO(png_bytes))
    img.load()
    assert img.format == "PNG"


def test_render_scan_image_rejects_oversized_text():
    pytest.importorskip("PIL")
    from jseval import corpus_generate as cg

    oversized = "word " * (cg.MAX_SCAN_TEXT_CHARS // 4)
    assert len(oversized) > cg.MAX_SCAN_TEXT_CHARS
    with pytest.raises(cg.ScanRenderLimitExceeded):
        cg.render_scan_image(oversized, seed=1)


def test_render_scan_image_rejects_oversized_width():
    pytest.importorskip("PIL")
    from jseval import corpus_generate as cg

    with pytest.raises(cg.ScanRenderLimitExceeded):
        cg.render_scan_image("some short text", width=cg.MAX_SCAN_WIDTH_PX + 1, seed=1)


def test_render_scan_image_rejects_oversized_font_size():
    pytest.importorskip("PIL")
    from jseval import corpus_generate as cg

    with pytest.raises(cg.ScanRenderLimitExceeded):
        cg.render_scan_image("some short text", font_size=cg.MAX_SCAN_FONT_SIZE + 1, seed=1)


def test_render_scan_image_rejects_excessive_wrapped_height():
    """Defense in depth: individually-in-bounds width + text length can still combine
    (e.g. a narrow width forcing near one-word-per-line wrapping of a long text) to a
    wrapped page height beyond the ceiling. The height check must catch this
    combination even though no single parameter alone tripped its own bound."""
    pytest.importorskip("PIL")
    from jseval import corpus_generate as cg

    narrow_width = 40  # near the wrap-width floor -- forces many short lines
    long_text = "reactor province delegate marshland fabricated " * 400
    assert len(long_text) < cg.MAX_SCAN_TEXT_CHARS
    assert narrow_width < cg.MAX_SCAN_WIDTH_PX
    with pytest.raises(cg.ScanRenderLimitExceeded):
        cg.render_scan_image(long_text, width=narrow_width, seed=1)


def test_generate_scan_axis_source_is_plain_text_like_every_other_axis(tmp_path):
    """A `type_axis="scan"` corpus's committed *source* (`docs.jsonl`) must be identical in
    shape to a plain prose source -- no image bytes anywhere -- so it stays small and
    deterministic like every sibling (tempdoc 624 §T.2, revised: the scan-page PNG is a
    materialize-time artifact, not a generation-time one; embedding it in the committed
    source blew a doc_words=520 corpus up to 203MB vs. ~1.4-1.8MB for text-only siblings)."""
    from jseval import corpus_generate as cg

    out = tmp_path / "g"
    stats = cg.generate(out, axis="scan", n_chains=3, hops=2, distractor_ratio=2,
                         doc_words=520, seed=1, entity_bank=BANK)
    docs = [json.loads(line) for line in (out / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    assert len(docs) == stats["docs"]
    for d in docs:
        assert set(d.keys()) == {"_id", "title", "text"}, f"{d['_id']} carries unexpected keys: {d.keys()}"
        assert d["text"]
    # multi-hop / unique-answer invariants (shared with every other axis) still hold
    queries = json.loads((out / "queries.json").read_text(encoding="utf-8"))
    assert all(len(q["evidence_ids"]) >= 2 for q in queries)
    src_meta = json.loads((out / "meta.json").read_text(encoding="utf-8"))
    assert src_meta["type_axis"] == "scan"


def test_generate_scan_axis_deterministic_across_processes(tmp_path):
    """Mirrors `test_generate_is_deterministic_across_processes` for the scan axis: since
    `render()` now routes `axis="scan"` to the same `_render_prose` text generation as the
    plain prose axis, this is really confirming the dispatch didn't introduce a new
    per-process-random source -- the actual image rendering happens later, at build time."""
    from jseval import corpus_generate as cg

    out1, out2 = tmp_path / "run1", tmp_path / "run2"
    result = cg.regenerate_and_diff(
        out1, out2, axis="scan", lang="en", n_chains=2, hops=1,
        distractor_ratio=2, doc_words=40, seed=9, semantic=False, entity_bank=BANK,
    )
    assert result["ok"], result.get("error")
    assert not result["mismatched_files"], f"scan axis differs between two same-seed regenerations: {result['mismatched_files']}"


def test_render_scan_page_deterministic_for_same_doc_id_varies_by_doc_id():
    pytest.importorskip("PIL")
    from jseval import corpus_generate as cg

    a1 = cg.render_scan_page("doc1", "Title", "Some fabricated ground-truth text.")
    a2 = cg.render_scan_page("doc1", "Title", "Some fabricated ground-truth text.")
    b = cg.render_scan_page("doc2", "Title", "Some fabricated ground-truth text.")
    assert a1 == a2, "same doc_id + content must render byte-identical PNGs"
    assert a1 != b, "a different doc_id must vary the salt-and-pepper noise seed"


def test_build_golden_materializes_scan_docs_as_png(tmp_path):
    """§T.2's core mechanism: `corpus_build.build_golden` renders the scan PNG HERE, at
    materialize time, from the doc's own ground-truth `text` (via `render_scan_page`) --
    the committed source never carries image bytes. `corpus-dir/` (the agent file-tools /
    real-ingest view) gets the PNG; `corpus.jsonl` (the retrieval-quality view) keeps using
    ground-truth `text` untouched, since that view scores against the *intended* content,
    not whatever a real ingest pipeline manages to extract from the degraded image."""
    pytest.importorskip("PIL")
    from jseval import corpus_generate as cg
    from jseval.materialize import doc_id_to_filename

    src = tmp_path / "src"
    cg.generate(src, axis="scan", n_chains=2, hops=1, distractor_ratio=1, doc_words=40, seed=3,
                entity_bank=BANK)
    docs = [json.loads(line) for line in (src / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    assert all("image_b64" not in d for d in docs), "committed source must carry no image bytes"

    ds = tmp_path / "golden" / "scan-x"
    corpus_build.build_golden(src, ds, now="2026-07-02")

    corpus_dir = ds / "corpus-dir"
    for d in docs:
        png_path = corpus_dir / doc_id_to_filename(d["_id"], ext="png")
        assert png_path.is_file(), f"{d['_id']} was not materialized as a PNG"
        assert png_path.read_bytes().startswith(b"\x89PNG"), f"{d['_id']}.png is not a real PNG"
        assert not (corpus_dir / doc_id_to_filename(d["_id"])).exists()  # no stray .txt

    # retrieval view is unaffected -- ground-truth text, not image content
    corpus_lines = [json.loads(line) for line in (ds / "corpus.jsonl").read_text(encoding="utf-8").splitlines()]
    by_id = {c["_id"]: c for c in corpus_lines}
    for d in docs:
        assert by_id[d["_id"]]["text"] == d["text"]


def test_build_golden_scan_materialization_is_reproducible_from_source(tmp_path):
    """The whole point of rendering at materialize time instead of embedding in source:
    the PNG artifact must be exactly reconstructable from the committed source alone, any
    number of times (no hidden state) -- this is what makes it safe for `datasets/` to stay
    gitignored for a scan-axis corpus the same as every other axis."""
    pytest.importorskip("PIL")
    from jseval import corpus_generate as cg

    src = tmp_path / "src"
    cg.generate(src, axis="scan", n_chains=2, hops=1, distractor_ratio=1, doc_words=80, seed=11,
                entity_bank=BANK)

    ds1, ds2 = tmp_path / "ds1", tmp_path / "ds2"
    corpus_build.build_golden(src, ds1, now="2026-07-02")
    corpus_build.build_golden(src, ds2, now="2026-07-02")

    pngs1 = sorted((ds1 / "corpus-dir").glob("*.png"))
    pngs2 = sorted((ds2 / "corpus-dir").glob("*.png"))
    assert pngs1 and len(pngs1) == len(pngs2)
    assert [p.name for p in pngs1] == [p.name for p in pngs2]
    assert all(a.read_bytes() == b.read_bytes() for a, b in zip(pngs1, pngs2))


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


def test_certify_resolves_family_qualified_dataset_under_mixed(tmp_path):
    """707 gate-run unblocker: `corpus-certify --dataset mixed/<name>` must resolve
    datasets/mixed/<name> (the 707 member layout) instead of the historical hardcoded
    datasets/golden/<name>. Bare names keep the golden/ resolution (covered by the
    end-to-end tests above)."""
    from click.testing import CliRunner

    from jseval.cli import main

    ds = tmp_path / "datasets" / "mixed" / "m707"
    ds.mkdir(parents=True)
    (ds / "queries.json").write_text(json.dumps(
        [{"query": "q1", "answer": "a1", "evidence_ids": ["d1"]}]), encoding="utf-8")
    fake = {"contamination_class": "private-synthetic",
            "closed_book_certification": {"passed": True, "closed_book_accuracy": 0.0},
            "fidelity": {"memory_independence": 1.0, "retrieval_difficulty": None}}
    with patch("jseval.corpus_certify.certify_corpus", return_value=fake) as mock_cert:
        r = CliRunner().invoke(main, ["corpus-certify", "--dataset", "mixed/m707",
                                      "--datasets-dir", str(tmp_path / "datasets")])
    assert r.exit_code == 0, r.output
    # certify ran against the mixed-dir queries, and metadata landed next to them —
    # NOT under a phantom datasets/golden/mixed/m707.
    assert mock_cert.call_args.args[0][0]["query"] == "q1"
    assert (ds / "metadata.json").is_file()
    assert not (tmp_path / "datasets" / "golden").exists()


def test_generated_gold_source_is_checkout_stable(tmp_path):
    """End-to-end sibling of test_commitment_files_are_checkout_stable (independent-review
    finding, 2026-07-14): the REAL generator must emit LF bytes, because 635-corpora gold is
    git-committed and flows verbatim into the 707 fabricated-* commitments. The commitment-level
    test seeds LF explicitly, so it alone would stay green if corpus_generate regressed to
    platform-default newlines on Windows."""
    corpus_generate.generate(tmp_path, axis="prose", lang="en", n_chains=3, hops=1,
                             distractor_ratio=1, doc_words=40, suite="test", seed=3,
                             semantic=True, entity_bank=BANK)
    for name in ("docs.jsonl", "queries.json", "meta.json"):
        raw = (tmp_path / name).read_bytes()
        assert b"\r" not in raw, f"{name} contains CR bytes — git eol=lf will rewrite it after commit"


def test_two_axis_regime_below_pair_bound(tmp_path):
    """Axis-conditional descriptor width (2026-07-16): at n_chains within min(T, P) the
    generator must emit v0.1.0-style TWO-axis descriptors (no ordinal qualifier) — the
    regime every committed 635-era/707 fabricated-gold cell was generated in. The 624
    triple-render made queries longer (breaking the German short-natural 5-12-word cap
    structurally) and changed difficulty vs committed cells. Guards: no qual synonym in
    any query at n=20, and BOTH languages' short-natural renderings fit the cap."""
    from jseval.corpus_query_strata import build_short_natural_source

    for lang in ("en", "de"):
        out = tmp_path / f"gold-{lang}"
        corpus_generate.generate(out, axis="prose", lang=lang, n_chains=20, hops=1,
                                 distractor_ratio=2, doc_words=60, suite="test",
                                 seed=635, semantic=True, entity_bank=BANK)
        queries = json.loads((out / "queries.json").read_text(encoding="utf-8"))
        quals = corpus_generate._SEM_QUAL_DE if lang == "de" else corpus_generate._SEM_QUAL
        for q in queries:
            for _dq, qq in quals:
                assert qq not in q["query"], (
                    f"{lang}: qualifier synonym {qq!r} leaked into a two-axis-regime query")
        # the load-bearing consumer: short-natural stratum must build (5-12 word cap)
        sn = build_short_natural_source(out, tmp_path / f"sn-{lang}", language=lang)
        assert sn["query_count"] == 20


def _qualifier_hits(out_dir):
    queries = json.loads((out_dir / "queries.json").read_text(encoding="utf-8"))
    qual_syns = {qq for _dq, qq in corpus_generate._SEM_QUAL}
    return sum(1 for q in queries if any(qq in q["query"] for qq in qual_syns)), len(queries)


def test_two_axis_pair_bound_is_exactly_924():
    """The two-axis (type, place) descriptor bound is 924, not min(T, P) = 21.

    `_sem_for` gives gold chain g the index pair (g % 21, g % 44); 21 and 44 are coprime, so
    by the CRT that pair is injective on [0, lcm(21, 44)) = [0, 924) and lossy from 925 on.
    924 is now a load-bearing constant — `generate()`'s descriptor-width guard uses it as the
    *uniqueness* half of the two-concern condition — so it is pinned here rather than
    re-derived. tempdoc 767."""
    for lang in ("en", "de"):
        assert corpus_generate._max_semantic_pair_chains(lang) == 924, lang
        for n in (1, 21, 22, 100, 923, 924):
            pairs = corpus_generate._gold_descriptor_reservations(n, lang, use_qual=False)
            assert len(pairs) == n, f"{lang}: n={n} gave {len(pairs)} distinct pairs"
        lossy = corpus_generate._gold_descriptor_reservations(925, lang, use_qual=False)
        assert len(lossy) == 924, f"{lang}: n=925 gave {len(lossy)} distinct pairs, expected 924"


def test_qualifier_switches_on_for_uniqueness_and_for_distractor_diversity(tmp_path):
    """The descriptor-width guard's two SEPARATE triggers, and that neither fires alone
    below its own bound (tempdoc 767).

    Supersedes `test_three_axis_regime_above_pair_bound`, which asserted the qualifier was
    active at n_chains=30. That pinned the OLD single condition `n_chains > min(T, P) = 21`,
    which conflated two concerns and was 44x over-conservative as a uniqueness bound; 30
    chains need neither trigger. What must hold instead:

      * uniqueness  — above the 924 pair bound the qualifier is what keeps gold descriptors
        distinct, so it must be on there;
      * diversity   — with many generated distractor chains drawn from the pair space the
        qualifier is what keeps them from colliding with each other, so it must be on there
        too, at any n_chains;
      * neither     — a caller below both bounds must NOT pay the qualifier's query-length
        cost (this is the regression the old assertion prevented from being fixed).
    """
    # neither trigger: 30 chains, few distractors -> two-axis
    modest = tmp_path / "modest"
    corpus_generate.generate(modest, axis="prose", lang="en", n_chains=30, hops=1,
                             distractor_ratio=1, doc_words=60, suite="test",
                             seed=7, semantic=True, entity_bank=BANK)
    hits, total = _qualifier_hits(modest)
    assert (hits, total) == (0, 30), f"{hits}/{total} queries carry a qualifier they do not need"

    # diversity trigger: 26 chains but 780 distractor chains into the 898 free pair bins
    crowded = tmp_path / "crowded"
    corpus_generate.generate(crowded, axis="prose", lang="en", n_chains=26, hops=1,
                             distractor_ratio=30, doc_words=60, suite="test",
                             seed=7, semantic=True, entity_bank=BANK)
    hits, total = _qualifier_hits(crowded)
    assert (hits, total) == (26, 26), f"only {hits}/{total} crowded-distractor queries carry a qualifier"

    # uniqueness trigger: above the 924 pair bound, with no distractors at all
    scale = tmp_path / "scale"
    corpus_generate.generate(scale, axis="prose", lang="en", n_chains=925, hops=1,
                             distractor_ratio=0, doc_words=60, suite="test",
                             seed=7, semantic=True, entity_bank=BANK)
    hits, total = _qualifier_hits(scale)
    assert (hits, total) == (925, 925), f"only {hits}/{total} above-pair-bound queries carry a qualifier"


def test_distractor_ratio_zero_leaves_the_gold_payload_identical(tmp_path):
    """`distractor_ratio=0` is a valid call and changes nothing a discarding caller keeps.

    `corpus_inject.assemble` filters the fabricated source down to the docs named by
    `evidence_ids` (corpus_inject.py:282-285) and takes every distractor from the REAL host
    corpus instead — so the generated distractors in a 707 cell are produced and thrown away.
    They are also the sole reason that path could ever hit the descriptor-width guard's
    diversity trigger. This pins the two halves of the claim: the call is legal, and the gold
    payload (docs + queries) it hands to `assemble` is byte-identical either way. tempdoc 767.
    """
    kwargs = dict(axis="prose", lang="en", n_chains=20, hops=1, doc_words=None,
                  suite="test", seed=636, semantic=True, entity_bank=BANK)
    with_distractors = tmp_path / "r6"
    without = tmp_path / "r0"
    stats6 = corpus_generate.generate(with_distractors, distractor_ratio=6, **kwargs)
    stats0 = corpus_generate.generate(without, distractor_ratio=0, **kwargs)
    assert stats6["distractor_docs"] == 240 and stats0["distractor_docs"] == 0

    def gold_payload(out):
        queries = json.loads((out / "queries.json").read_text(encoding="utf-8"))
        keep = {evidence for q in queries for evidence in q["evidence_ids"]}
        docs = [json.loads(line) for line in
                (out / "docs.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
        return sorted((d for d in docs if d["_id"] in keep), key=lambda d: d["_id"]), queries

    docs6, queries6 = gold_payload(with_distractors)
    docs0, queries0 = gold_payload(without)
    assert len(docs0) == 40, len(docs0)
    assert docs6 == docs0
    assert queries6 == queries0


def test_two_axis_regime_collision_gate_still_clean(tmp_path):
    """Two-axis mode has no reservation/exclusion (v0.1.0 rng parity), so the
    descriptor-collision gate is the fail-closed check — assert it passes for the
    exact DE v2 shape (lang=de, n=20, hops=1, seed=635)."""
    out = tmp_path / "gold-de"
    corpus_generate.generate(out, axis="prose", lang="de", n_chains=20, hops=1,
                             distractor_ratio=5, doc_words=60, suite="test",
                             seed=635, semantic=True, entity_bank=BANK)
    docs = [json.loads(l) for l in (out / "docs.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    queries = json.loads((out / "queries.json").read_text(encoding="utf-8"))
    report = corpus_certify.descriptor_collision_report(docs, queries)
    assert report["n_gold_involved"] == 0, report
    assert report["passed"] is True, report


# ---------------------------------------------------------------------------
# tempdoc 767 §I.3 — payload template / descriptor leak channels
#
# Two standing guards for defect classes the leak gates found on a real end-to-end
# dry run of the en-legal-clerc 1k-verbose cell:
#
#   * a single relation/tail phrasing shared by every gold chain, giving one grep
#     anchor that selects half the gold set (measured max_gold_coverage 0.500
#     against a 0.225 native base rate);
#   * a descriptor pair whose doc-side and query-side members share a root word
#     ("Carpathian highlands" / "Carpathian uplands"), so the query and its gold
#     doc share a corpus-wide df=1 token that pins exactly one document.
# ---------------------------------------------------------------------------

#: Unicode-aware token split for the pool guards. Deliberately STRICTER than
#: `corpus_leak._TOKEN_RE` (`[a-z0-9']+`, which mangles German umlauts into
#: fragments): a shared token must be caught in both languages' pools, not only in
#: the one the ASCII gate can see.
_POOL_TOKEN_RE = re.compile(r"[\w']+", re.UNICODE)


def _pool_tokens(phrase: str) -> set[str]:
    return {t.lower() for t in _POOL_TOKEN_RE.findall(phrase)}


@pytest.mark.parametrize("pool_name", [
    "_SEM_TYPE", "_SEM_PLACE", "_SEM_QUAL",
    "_SEM_TYPE_DE", "_SEM_PLACE_DE", "_SEM_QUAL_DE",
])
def test_sem_pools_are_root_disjoint(pool_name):
    """No `_SEM_*` pair may share a token between its doc-side and query-side member.

    The pools ARE the semantic bridge: the doc says one thing, the query says a synonym,
    and a grep/BM25 agent fails at the entry point while dense retrieval succeeds. A pair
    that varies only the head noun and leaves a distinctive modifier verbatim hands that
    modifier back as an exact-match anchor — and when the modifier is minted purely by the
    pool (no real host document contains "carpathian"), its corpus document frequency is 1,
    so the anchor pins the single gold document the query is about. Standing guard: this
    defect class cannot return by someone appending a plausible-looking pair.
    """
    pool = getattr(corpus_generate, pool_name)
    offenders = [
        (doc_side, query_side, sorted(_pool_tokens(doc_side) & _pool_tokens(query_side)))
        for doc_side, query_side in pool
        if _pool_tokens(doc_side) & _pool_tokens(query_side)
    ]
    assert not offenders, (
        f"{pool_name}: {len(offenders)} pair(s) share a token between the doc-side and "
        f"query-side member, leaking a lexical anchor across the semantic bridge: {offenders}")


def test_relation_phrasings_are_token_disjoint():
    """A relation's doc phrasing and question phrasing must share no content token.

    "designed"/"designer", "financed"/"financier" are distinct tokens; a pair that reused
    one verbatim would put the same surface in the query and the gold doc, defeating the
    same synonym bridge `_SEM_*` implements and raising `query_overlap_report`.
    """
    stop = {"was", "by", "the", "of", "a", "an"}
    offenders = []
    for kind, doc_phrasing, question_phrasing in corpus_generate._RELATIONS["prose"]:
        shared = (_pool_tokens(doc_phrasing) & _pool_tokens(question_phrasing)) - stop
        if shared:
            offenders.append((kind, doc_phrasing, question_phrasing, sorted(shared)))
    assert not offenders, f"relation phrasings leak a shared content token: {offenders}"


def test_tail_phrasings_have_no_fixed_5gram():
    """No tail template may contain 5 consecutive LITERAL tokens.

    Spreading the templates across chains caps how many gold docs any ONE template covers,
    but a template carrying a fixed 5-token run is still a `ngram_selectivity_report(n=5)`
    anchor within its own share. The `{last}`/`{attr}` slots are what break the runs, so the
    guard is on the literal spans between them.
    """
    offenders = []
    for tpl in corpus_generate._TAIL_PHRASINGS:
        for span in re.split(r"\{\w+\}", tpl):
            literal = _POOL_TOKEN_RE.findall(span)
            if len(literal) >= 5:
                offenders.append((tpl, literal))
    assert not offenders, f"tail templates carry a fixed 5-gram anchor: {offenders}"


def test_relation_and_tail_templates_are_spread_across_gold(tmp_path):
    """No single relation or tail phrasing may dominate a generated gold set.

    The measured defect: `_render_prose` indexes the relation pool by HOP
    (`rels[i % len(rels)]`) and every 707 cell is `hops=1`, so all 20 gold heads carried
    the byte-identical "was designed by the engineer" — 20 of 40 gold docs, one grep. The
    fix rotates both pools per CHAIN. Asserted against the native base rate the real
    en-legal-clerc dry run measured (0.225): every template's gold coverage must sit
    comfortably under it, not merely under 0.500.
    """
    out = tmp_path / "gold-spread"
    corpus_generate.generate(out, axis="prose", lang="en", n_chains=20, hops=1,
                             distractor_ratio=2, doc_words=None, suite="test",
                             seed=636, semantic=True, entity_bank=BANK)
    docs = [json.loads(l) for l in
            (out / "docs.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    queries = json.loads((out / "queries.json").read_text(encoding="utf-8"))
    gold_ids = {e for q in queries for e in q["evidence_ids"]}
    gold = [d for d in docs if d["_id"] in gold_ids]
    assert len(gold) == 40, f"expected 40 gold docs (20 chains x 2 hops), got {len(gold)}"

    native_base_rate = 0.225
    for _kind, doc_phrasing, _q in corpus_generate._RELATIONS["prose"]:
        hits = sum(1 for d in gold if doc_phrasing in d["text"])
        assert hits / len(gold) <= native_base_rate, (
            f"relation phrasing {doc_phrasing!r} covers {hits}/{len(gold)} gold docs, "
            f"above the {native_base_rate} native base rate — a grep anchor")

    for tpl in corpus_generate._TAIL_PHRASINGS:
        pattern = re.escape(tpl.strip()).replace(r"\{last\}", ".+?").replace(r"\{attr\}", ".+?")
        hits = sum(1 for d in gold if re.search(pattern, d["text"]))
        assert hits / len(gold) <= native_base_rate, (
            f"tail phrasing {tpl!r} covers {hits}/{len(gold)} gold docs, above the "
            f"{native_base_rate} native base rate — a grep anchor")

    # Every phrasing must actually be reachable, or "spread" would be satisfied trivially
    # by a pool whose extra members are dead code.
    used_relations = {dp for _k, dp, _q in corpus_generate._RELATIONS["prose"]
                      if any(dp in d["text"] for d in gold)}
    assert len(used_relations) == len(corpus_generate._RELATIONS["prose"]), (
        f"only {len(used_relations)} of {len(corpus_generate._RELATIONS['prose'])} relation "
        f"phrasings appear in a 20-chain gold set")


# ---------------------------------------------------------------------------
# indistinguishability — the gold-vs-native leak check, wired as a per-cell
# structural certification check (tempdoc 767 defect #2: corpus_leak.py shipped
# with no caller at all, so its verdicts gated nothing)
# ---------------------------------------------------------------------------

def _leaky_cell_docs(n_gold: int = 8, n_native: int = 48) -> tuple[list[dict], list[dict]]:
    """Gold numbered 1..N behind an alphabetic stem, distractors N+1..M — the
    `635-corpora` shape, perfectly separable by `trailing_int(id) <= N`."""
    gold = [
        {"_id": f"brel{index + 1}", "title": f"gold {index}", "text": f"alpha beta {index} gamma"}
        for index in range(n_gold)
    ]
    native = [
        {"_id": f"stone{n_gold + index + 1}", "title": f"native {index}",
         "text": f"delta epsilon {index} zeta"}
        for index in range(n_native)
    ]
    return gold, native


def _clean_cell_docs(n_gold: int = 8, n_native: int = 48) -> tuple[list[dict], list[dict]]:
    """Gold ids drawn from the SAME integer population as the natives (what
    `corpus_inject.mint_native_shaped_ids` produces)."""
    all_ids = [str(1000731 + index * 7919) for index in range(n_gold + n_native)]
    gold_ids = [all_ids[index] for index in range(3, len(all_ids), 7)][:n_gold]
    gold_set = set(gold_ids)
    gold = [
        {"_id": i, "title": f"t {i}", "text": f"varied body {i} unique {i} words here"}
        for i in gold_ids
    ]
    native = [
        {"_id": i, "title": f"t {i}", "text": f"other body {i} distinct {i} tokens here"}
        for i in all_ids if i not in gold_set
    ]
    return gold, native


def test_indistinguishability_report_fails_on_numerically_enumerable_gold():
    gold, native = _leaky_cell_docs()
    queries = [{"query": "q", "evidence_ids": [d["_id"] for d in gold]}]

    report = corpus_certify.indistinguishability_report(gold + native, queries)
    assert report["passed"] is False
    assert report["id_shape_passed"] is False
    assert report["id_shape_rule"] == "trailing_int(id) <= 8"
    assert report["id_shape_separability"] == 1.0
    assert report["method"] == "null-calibrated-id-shape-and-ngram-selectivity"
    assert set(report) == set(corpus_certify._INDISTINGUISHABILITY_KEYS)


def test_indistinguishability_report_fails_on_shared_boilerplate_ngram():
    # Ids are clean; the leak is a byte-identical paragraph in every gold doc
    # (tempdoc 767 defect #1), which only the n-gram half can see.
    gold, native = _clean_cell_docs()
    for doc in gold:
        doc["text"] += " this exact sentence appears in every planted document verbatim"
    queries = [{"query": "q", "evidence_ids": [d["_id"] for d in gold]}]

    report = corpus_certify.indistinguishability_report(gold + native, queries)
    assert report["passed"] is False
    assert report["ngram_passed"] is False
    assert report["ngram_max_gold_coverage"] == 1.0
    assert report["id_shape_passed"] is True, "the id half must not be what failed here"


def test_indistinguishability_report_passes_on_shape_matched_cell():
    gold, native = _clean_cell_docs()
    queries = [{"query": "q", "evidence_ids": [d["_id"] for d in gold]}]

    report = corpus_certify.indistinguishability_report(gold + native, queries)
    assert report["passed"] is True
    assert report["id_shape_passed"] is True and report["ngram_passed"] is True


def test_indistinguishability_report_absent_measurement_is_not_a_pass():
    # No gold docs at all -> both halves return `passed: None`. A check that could
    # not run must never certify a corpus clean.
    docs = [{"_id": "1000731", "title": "t", "text": "body"}]
    report = corpus_certify.indistinguishability_report(docs, [])
    assert report["id_shape_passed"] is None
    assert report["passed"] is False


def _materialize_cell(root: Path, gold: list[dict], native: list[dict], variant: str) -> None:
    """Write the four files `certify_materialized_family` reads for one cell."""
    root.mkdir(parents=True, exist_ok=True)
    docs = gold + native
    (root / "corpus.jsonl").write_text(
        "".join(json.dumps(d) + "\n" for d in docs), encoding="utf-8")
    queries = [{
        "query": f"{variant} question {index}",
        "query_variant": variant,
        "query_family_id": f"fam-{index}",
        "answer": f"answer {index}",
        "evidence_ids": [doc["_id"]],
    } for index, doc in enumerate(gold)]
    (root / "queries.json").write_text(json.dumps(queries), encoding="utf-8")
    (root / "metadata.json").write_text(json.dumps({"corpus_size": len(docs)}), encoding="utf-8")
    (root / "qrels").mkdir(exist_ok=True)
    (root / "qrels" / "test.tsv").write_text("q\t0\td\t1\n", encoding="utf-8")


def test_certify_computes_indistinguishability_end_to_end(tmp_path):
    """Drive the real `certify_materialized_family` over a materialized family and
    assert the per-cell indistinguishability verdict actually lands in the report.

    This is the end-to-end half the repo requires alongside the pure-function tests
    above: `test_certify_computes_descriptor_collisions_end_to_end` exists because a
    pure-function-only test once hid a wrong filename, and the same class of wiring
    bug (a check computed but never projected into the cell, or projected under a key
    the boundary validator does not accept) is invisible to the tests above.
    """
    datasets_dir = tmp_path / "datasets"
    leaky_gold, leaky_native = _leaky_cell_docs()
    clean_gold, clean_native = _clean_cell_docs()
    names, commitments = {}, {}
    for size in ("1000", "10000"):
        names[size], commitments[size] = {}, {}
        for variant in ("verbose", "short-natural"):
            name = f"cell-{size}-{variant}"
            # verbose cells carry the enumerable-id leak, short-natural cells are clean —
            # so one run exercises both verdicts through the identical code path.
            gold, native = (
                (leaky_gold, leaky_native) if variant == "verbose"
                else (clean_gold, clean_native)
            )
            _materialize_cell(datasets_dir / "mixed" / name, gold, native, variant)
            names[size][variant] = name
            commitments[size][variant] = str(tmp_path / "commit" / name)

    report = corpus_certify.certify_materialized_family(
        datasets_dir, member="fixture", dataset_names=names,
        commitment_dirs=commitments)

    for size in ("1000", "10000"):
        for variant in ("verbose", "short-natural"):
            cell = report["datasets"][size][variant]
            assert "indistinguishability" in cell, (
                "indistinguishability was never projected into the cell by the real "
                "certify path")
            detail = cell["indistinguishability"]
            assert set(detail) == set(corpus_certify._INDISTINGUISHABILITY_KEYS)
            expected = variant == "short-natural"
            assert detail["passed"] is expected
            assert cell["checks"]["indistinguishability"] is expected, (
                "the detail block and the boolean in `checks` disagree")
            if not expected:
                assert detail["id_shape_rule"] == "trailing_int(id) <= 8"

    # The check is part of the cell key-set the boundary validator enforces.
    assert "indistinguishability" in corpus_certify._CELL_CHECKS
    assert set(report["datasets"]["1000"]["verbose"]["checks"]) == corpus_certify._CELL_CHECKS


def test_complete_certification_document_rejects_failed_indistinguishability():
    """The boundary validator must refuse a certificate whose indistinguishability
    verdict is false or whose observation sits above its own null — otherwise a
    hand-edited boolean would carry a leaking corpus through certification."""
    from tests.test_corpus_inject import _complete_certificate, _gate_evidence

    def certificate() -> dict:
        gates = {
            gate: _gate_evidence(
                gate, member="fixture", dataset="mixed/fixture",
                signature="c" * 64, query_gold_sha256="b" * 64, query_count=20)
            for gate in corpus_certify.SCIENTIFIC_GATES
        }
        return _complete_certificate("fixture", "mixed/fixture", "c" * 64, gates)

    assert corpus_certify._complete_certification_document(certificate()) is True

    for mutate in (
        lambda d: d.update(passed=False),
        lambda d: d.update(id_shape_passed=False),
        lambda d: d.update(ngram_passed=False),
        lambda d: d.update(method="hand-waved"),
        # observation above its own null, with `passed` still asserted True
        lambda d: d.update(id_shape_separability=0.9),
        lambda d: d.update(ngram_max_gold_coverage=0.9),
        lambda d: d.pop("id_shape_rule"),
    ):
        doc = certificate()
        mutate(doc["datasets"]["1000"]["verbose"]["indistinguishability"])
        assert corpus_certify._complete_certification_document(doc) is False


# ---------------------------------------------------------------------------
# field_selectivity — the per-FIELD gold-vs-native presence leak check (tempdoc
# 776 §I). Catches the tempdoc 774 §J.7 title leak: a field populated only on
# gold, invisible to every content-level check the other gates run.
# ---------------------------------------------------------------------------

def _title_leak_cell_docs(n_gold: int = 8, n_native: int = 48) -> tuple[list[dict], list[dict]]:
    """The §J.7 shape reproduced by `corpus_inject.assemble`: gold docs carry a populated
    `title`, native distractors carry `title: ""`. IDs are drawn from ONE integer population
    (as `mint_native_shaped_ids` produces), so `title` PRESENCE is the only gold-vs-native
    signal — the id and text channels are clean."""
    all_ids = [str(1000731 + index * 7919) for index in range(n_gold + n_native)]
    gold_ids = [all_ids[index] for index in range(3, len(all_ids), 7)][:n_gold]
    gold_set = set(gold_ids)
    gold = [
        {"_id": i, "title": f"The distinct descriptor {i}", "text": f"body {i} unique words here " * 8}
        for i in gold_ids
    ]
    native = [
        {"_id": i, "title": "", "text": f"other body {i} distinct tokens here " * 8}
        for i in all_ids if i not in gold_set
    ]
    return gold, native


def test_field_selectivity_report_flags_field_present_only_on_gold():
    gold, native = _title_leak_cell_docs()
    queries = [{"query": "q", "evidence_ids": [d["_id"] for d in gold]}]

    report = corpus_certify.field_selectivity_report(gold + native, queries)
    assert report["passed"] is False
    assert report["worst_field"] == "title"
    assert report["method"] == "field-presence-null-calibrated-separability"
    title = report["per_field"]["title"]
    assert title["gold_population_rate"] == 1.0
    assert title["native_population_rate"] == 0.0
    assert title["separability"] == 1.0
    assert title["native_base_rate"] == 0.0
    assert title["passed"] is False
    # `text` is present on every doc, so its presence says nothing — it must NOT be flagged.
    assert report["per_field"]["text"]["passed"] is True
    # `_id` is delegated to id_shape_report and never appears here.
    assert "_id" not in report["per_field"]


def test_field_selectivity_report_passes_when_every_field_present_on_both():
    gold, native = _title_leak_cell_docs()
    for doc in native:
        doc["title"] = f"native title {doc['_id']}"  # now populated on natives too
    queries = [{"query": "q", "evidence_ids": [d["_id"] for d in gold]}]

    report = corpus_certify.field_selectivity_report(gold + native, queries)
    assert report["passed"] is True
    assert report["per_field"]["title"]["passed"] is True
    assert report["per_field"]["text"]["passed"] is True


def test_field_selectivity_report_absent_measurement_is_not_a_pass():
    # No gold docs -> nothing to compare. A check that could not run must never certify clean.
    docs = [{"_id": "1000731", "title": "t", "text": "body"}]
    report = corpus_certify.field_selectivity_report(docs, [])
    assert report["passed"] is None
    assert report["per_field"] == {}


def test_certify_computes_field_selectivity_end_to_end(tmp_path):
    """Drive the real `certify_materialized_family` and assert the per-cell field-selectivity
    verdict lands in the report — the wiring half the repo requires alongside the pure-function
    tests, mirroring `test_certify_computes_indistinguishability_end_to_end`."""
    datasets_dir = tmp_path / "datasets"
    leak_gold, leak_native = _title_leak_cell_docs()
    clean_gold, clean_native = _title_leak_cell_docs()
    for doc in clean_native:
        doc["title"] = f"native title {doc['_id']}"  # clean cells: title populated everywhere
    names, commitments = {}, {}
    for size in ("1000", "10000"):
        names[size], commitments[size] = {}, {}
        for variant in ("verbose", "short-natural"):
            name = f"cell-{size}-{variant}"
            # verbose cells carry the title-presence leak; short-natural cells are clean.
            gold, native = (
                (leak_gold, leak_native) if variant == "verbose"
                else (clean_gold, clean_native)
            )
            _materialize_cell(datasets_dir / "mixed" / name, gold, native, variant)
            names[size][variant] = name
            commitments[size][variant] = str(tmp_path / "commit" / name)

    report = corpus_certify.certify_materialized_family(
        datasets_dir, member="fixture", dataset_names=names,
        commitment_dirs=commitments)

    for size in ("1000", "10000"):
        for variant in ("verbose", "short-natural"):
            cell = report["datasets"][size][variant]
            assert "field_selectivity" in cell, (
                "field_selectivity was never projected into the cell by the real certify path")
            detail = cell["field_selectivity"]
            assert set(detail) == set(corpus_certify._FIELD_SELECTIVITY_KEYS)
            expected = variant == "short-natural"
            assert detail["passed"] is expected
            assert cell["checks"]["field_selectivity"] is expected, (
                "the detail block and the boolean in `checks` disagree")
            if not expected:
                assert detail["worst_field"] == "title"

    assert "field_selectivity" in corpus_certify._CELL_CHECKS
    assert set(report["datasets"]["1000"]["verbose"]["checks"]) == corpus_certify._CELL_CHECKS


def test_complete_certification_document_rejects_failed_field_selectivity():
    """The boundary validator must refuse a certificate whose field-selectivity verdict is
    false or whose observation sits above its own null — otherwise a hand-edited boolean would
    carry a title-leaking corpus through certification."""
    from tests.test_corpus_inject import _complete_certificate, _gate_evidence

    def certificate() -> dict:
        gates = {
            gate: _gate_evidence(
                gate, member="fixture", dataset="mixed/fixture",
                signature="c" * 64, query_gold_sha256="b" * 64, query_count=20)
            for gate in corpus_certify.SCIENTIFIC_GATES
        }
        return _complete_certificate("fixture", "mixed/fixture", "c" * 64, gates)

    assert corpus_certify._complete_certification_document(certificate()) is True

    for mutate in (
        lambda d: d.update(passed=False),
        lambda d: d.update(method="hand-waved"),
        # observation above its own null, with `passed` still asserted True
        lambda d: d.update(max_field_separability=0.9),
        lambda d: d.update(n_fields_compared=0),
        lambda d: d.pop("worst_field"),
    ):
        doc = certificate()
        mutate(doc["datasets"]["1000"]["verbose"]["field_selectivity"])
        assert corpus_certify._complete_certification_document(doc) is False

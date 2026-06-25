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
    with patch("jseval.run.execute_run", return_value=_summary(0.70)), \
         patch("jseval.corpus_fidelity.shortcut_leak_rate", return_value=(0.0, 0)):
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

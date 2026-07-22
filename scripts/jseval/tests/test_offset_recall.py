"""Tests for the per-offset recall instrument (tempdoc 783 §B.1).

Covers: offset binning, both resolution sources (metadata + string fallback), unresolved
accounting, determinism, and a fixture-based end-to-end run whose answer we KNOW —
gold at offset 6k retrieved only by the lexical leg, so the curve must show exactly that.

The fixture writes a synthetic run in the EXACT shape ``artifacts.write_run`` produces
(``qrels.json`` + ``<mode>_per_query.json`` with ``qid``/``predictedDocIds``) and a corpus
dir in the ``docs.jsonl`` + ``queries.json`` source shape — an unfaithful seed would give
confident-but-wrong green, so the shape is mirrored from ``artifacts._build_per_query_entries``.
"""
from __future__ import annotations

import json
from pathlib import Path

from jseval import offset_recall
from jseval.evidence_offset import locate_offset


# --- unit: offset binning ----------------------------------------------------------------


def test_bin_labels_and_boundaries():
    assert offset_recall.bin_labels() == ["0-1k", "1k-2k", "2k-4k", "4k-8k", "8k+"]
    # Upper-exclusive edges.
    assert offset_recall.bin_for_offset(0) == "0-1k"
    assert offset_recall.bin_for_offset(999) == "0-1k"
    assert offset_recall.bin_for_offset(1000) == "1k-2k"
    assert offset_recall.bin_for_offset(3999) == "2k-4k"
    assert offset_recall.bin_for_offset(8000) == "8k+"
    assert offset_recall.bin_for_offset(50000) == "8k+"


def test_custom_bin_edges():
    edges = (500, 1500)
    assert offset_recall.bin_labels(edges) == ["0-500", "500-1500", "1500+"]
    assert offset_recall.bin_for_offset(700, edges) == "500-1500"


# --- unit: locate_offset primitive -------------------------------------------------------


def test_locate_offset_exact_and_whitespace_tolerant():
    text = "alpha beta the secret is 42 gamma delta"
    assert locate_offset(text, "the secret is 42") == text.index("the secret is 42")
    # collapsed whitespace in the record still matches runs in the text
    spaced = "alpha   beta the  secret\tis 42 gamma"
    assert locate_offset(spaced, "secret is 42") == spaced.index("secret")
    assert locate_offset(text, "not present") is None
    assert locate_offset("", "x") is None
    assert locate_offset(text, "") is None


# --- unit: both resolution sources -------------------------------------------------------


def test_resolve_prefers_metadata_over_string_match():
    gold = ["d1"]
    doc_texts = {"d1": "prefix ... the answer is here at the end"}
    query_meta = {"q0001": {"answer": "the answer is here"}}
    meta = {"d1": {"char_offset": 4200, "doc_len": 9000, "evidence": "seeded"}}
    off, source, doc, reason = offset_recall.resolve_offset(
        "q0001", gold, doc_texts, query_meta, meta)
    assert (off, source, doc, reason) == (4200, "metadata", "d1", None)


def test_resolve_string_match_when_no_metadata():
    gold = ["d1"]
    text = "x" * 5000 + " the fabricated answer 77 " + "y" * 100
    doc_texts = {"d1": text}
    query_meta = {"q0001": {"answer": "the fabricated answer 77"}}
    off, source, doc, reason = offset_recall.resolve_offset(
        "q0001", gold, doc_texts, query_meta, None)
    assert source == "string_match"
    assert doc == "d1"
    assert off == text.index("the fabricated answer 77")
    assert reason is None


def test_resolve_unresolved_when_evidence_absent():
    gold = ["d1"]
    doc_texts = {"d1": "nothing relevant here"}
    query_meta = {"q0001": {"answer": "totally different string"}}
    off, source, doc, reason = offset_recall.resolve_offset(
        "q0001", gold, doc_texts, query_meta, None)
    assert off is None
    assert source == "unresolved"
    assert doc == "d1"
    assert reason == "evidence_not_located"


# --- unit: report accounting + unresolved counting ---------------------------------------


def test_build_report_unresolved_is_counted_not_dropped():
    modes = {"hybrid": {"q0001": ["d1"], "q0002": ["dX"]}}
    qrels = {
        "q0001": {"d1": 1},           # resolvable by string match
        "q0002": {"d2": 1},           # answer not in doc -> unresolved
        "q0003": {"d3": 0},           # no positive gold -> no_gold
    }
    doc_texts = {"d1": "the cat sat", "d2": "unrelated text", "d3": "z"}
    query_meta = {
        "q0001": {"answer": "the cat sat"},
        "q0002": {"answer": "missing"},
    }
    report = offset_recall.build_report(modes, qrels, doc_texts, query_meta)
    res = report["resolution"]
    assert res["queries_with_gold"] == 2   # q0003 excluded (no positive gold)
    assert res["resolved"] == 1
    assert res["unresolved"] == 1
    assert res["no_gold"] == 1
    assert res["by_source"]["string_match"] == 1
    assert report["unresolved_qids"] == ["q0002"]
    # every query with gold appears in per_query (nothing silently dropped)
    assert {q["qid"] for q in report["per_query"]} == {"q0001", "q0002"}


def test_build_report_determinism():
    modes = {"hybrid": {"q0001": ["d1"]}, "lexical": {"q0001": ["dX", "d1"]}}
    qrels = {"q0001": {"d1": 1}}
    doc_texts = {"d1": "answer alpha"}
    query_meta = {"q0001": {"answer": "answer alpha"}}
    a = offset_recall.build_report(modes, qrels, doc_texts, query_meta)
    b = offset_recall.build_report(modes, qrels, doc_texts, query_meta)
    assert json.dumps(a, sort_keys=True) == json.dumps(b, sort_keys=True)
    # modes are sorted deterministically
    assert a["modes"] == ["hybrid", "lexical"]


# --- fixture end-to-end: gold at offset 6k, retrieved ONLY by lexical ---------------------


def _write_run(run_dir: Path, qrels: dict, per_mode: dict[str, list[dict]]) -> None:
    """Mirror artifacts.write_run's on-disk shape (qrels.json + <mode>_per_query.json)."""
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "qrels.json").write_text(json.dumps(qrels), encoding="utf-8")
    for mode, entries in per_mode.items():
        (run_dir / f"{mode}_per_query.json").write_text(json.dumps(entries), encoding="utf-8")


def _per_query_entry(qid: str, predicted: list[str]) -> dict:
    # The fields the instrument reads; the real writer emits more (harmless here).
    return {"qid": qid, "mode": "x", "predictedDocIds": predicted, "recallAtK": None}


def test_end_to_end_offset_6k_lexical_only(tmp_path):
    # Corpus: one gold doc with the answer buried at ~6000 chars; a distractor.
    answer = "the buried fact is zephyr-9000"
    prefix = "lorem ipsum dolor sit amet. " * 220        # ~6160 chars of filler
    gold_text = prefix + answer + " and the passage continues afterwards."
    offset = gold_text.index(answer)
    assert 6000 <= offset <= 6500, offset  # the offset we assert the curve reflects

    corpus_dir = tmp_path / "corpus"
    corpus_dir.mkdir()
    (corpus_dir / "docs.jsonl").write_text(
        json.dumps({"_id": "gold1", "title": "T", "text": gold_text}) + "\n"
        + json.dumps({"_id": "dist1", "title": "T2", "text": "unrelated distractor text"}) + "\n",
        encoding="utf-8",
    )
    (corpus_dir / "queries.json").write_text(
        json.dumps([{"query": "what is buried?", "answer": answer,
                     "question_type": "single_fact", "evidence_ids": ["gold1"]}]),
        encoding="utf-8",
    )

    run_dir = tmp_path / "run"
    qrels = {"q0001": {"gold1": 1}}
    _write_run(run_dir, qrels, {
        # lexical FINDS the gold at rank 1; dense MISSES it (returns only the distractor).
        "lexical": [_per_query_entry("q0001", ["gold1", "dist1"])],
        "dense": [_per_query_entry("q0001", ["dist1"])],
    })

    report = offset_recall.analyze(run_dir, corpus_dir, k=10)

    # Resolution: exactly one query resolved via string fallback into the 4k-8k bin.
    assert report["resolution"]["resolved"] == 1
    assert report["resolution"]["unresolved"] == 0
    assert report["resolution"]["by_source"]["string_match"] == 1
    pq = report["per_query"][0]
    assert pq["bin"] == "4k-8k"
    assert pq["gold_doc"] == "gold1"
    assert pq["offset"] == offset

    # The curve must show EXACTLY: lexical retrieves it, dense does not — in the 4k-8k bin.
    assert report["curves"]["lexical"]["4k-8k"]["recall_at_k"] == 1.0
    assert report["curves"]["lexical"]["4k-8k"]["median_rank_when_found"] == 1
    assert report["curves"]["dense"]["4k-8k"]["recall_at_k"] == 0.0
    assert report["curves"]["dense"]["4k-8k"]["n"] == 1
    # empty bins report None recall (no queries there), not a fake 0.
    assert report["curves"]["lexical"]["0-1k"]["recall_at_k"] is None


def test_end_to_end_metadata_sidecar_beats_string(tmp_path):
    # A sidecar offset (source a) overrides string-location and bins the query accordingly.
    corpus_dir = tmp_path / "corpus"
    corpus_dir.mkdir()
    text = "short host. " + "the injected sentence is here." + " tail."
    (corpus_dir / "docs.jsonl").write_text(
        json.dumps({"_id": "g1", "title": "", "text": text}) + "\n", encoding="utf-8")
    (corpus_dir / "queries.json").write_text(
        json.dumps([{"query": "q", "answer": "the injected sentence is here",
                     "question_type": "single_fact", "evidence_ids": ["g1"]}]),
        encoding="utf-8")
    # Metadata claims the evidence sits at 8500 (into 8k+), overriding the ~12-char string hit.
    (corpus_dir / "evidence_offsets.json").write_text(
        json.dumps({"schema": "evidence-offsets.v1", "method": "injection-assembly",
                    "offsets": {"g1": {"char_offset": 8500, "doc_len": 9000,
                                       "evidence": "the injected sentence is here."}}}),
        encoding="utf-8")

    run_dir = tmp_path / "run"
    _write_run(run_dir, {"q0001": {"g1": 1}},
               {"hybrid": [_per_query_entry("q0001", ["g1"])]})

    report = offset_recall.analyze(run_dir, corpus_dir, k=10)
    pq = report["per_query"][0]
    assert pq["source"] == "metadata"
    assert pq["offset"] == 8500
    assert pq["bin"] == "8k+"
    assert report["resolution"]["by_source"]["metadata"] == 1
    assert report["curves"]["hybrid"]["8k+"]["recall_at_k"] == 1.0


def test_load_run_and_corpus_roundtrip(tmp_path):
    run_dir = tmp_path / "run"
    _write_run(run_dir, {"q0001": {"d1": 1, "d2": 0}},
               {"hybrid": [_per_query_entry("q0001", ["d1", "d2"])]})
    modes, qrels = offset_recall.load_run(run_dir)
    assert modes == {"hybrid": {"q0001": ["d1", "d2"]}}
    assert qrels == {"q0001": {"d1": 1, "d2": 0}}

    corpus_dir = tmp_path / "corpus"
    corpus_dir.mkdir()
    (corpus_dir / "docs.jsonl").write_text(
        json.dumps({"_id": "d1", "text": "hello"}) + "\n", encoding="utf-8")
    (corpus_dir / "queries.json").write_text(
        json.dumps([{"query": "q", "answer": "a", "evidence_ids": ["d1"]}]), encoding="utf-8")
    doc_texts, query_meta, meta = offset_recall.load_corpus(corpus_dir)
    assert doc_texts == {"d1": "hello"}
    assert query_meta["q0001"]["answer"] == "a"
    assert meta is None  # no sidecar

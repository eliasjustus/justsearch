"""Tests for the per-offset recall instrument (tempdoc 783 §B.1 / §B.1a).

Covers: offset binning, all three resolution sources (metadata, answer-string fallback,
query-locus PROXY) and their strict priority order, unresolved accounting, the proxy's
determinism and separate (never-merged) accounting, and a fixture-based end-to-end run
whose answer we KNOW — gold at offset 6k retrieved only by the lexical leg, so the curve
must show exactly that.

The fixture writes a synthetic run in the EXACT shape ``artifacts.write_run`` produces
(``qrels.json`` + ``<mode>_per_query.json`` with ``qid``/``predictedDocIds``) and a corpus
dir in the ``docs.jsonl`` + ``queries.json`` source shape — an unfaithful seed would give
confident-but-wrong green, so the shape is mirrored from ``artifacts._build_per_query_entries``.
"""
from __future__ import annotations

import json
from pathlib import Path

from jseval import offset_recall, query_locus
from jseval.evidence_offset import locate_offset

# Filler whose vocabulary appears in EVERY fixture doc — corpus-local rarity therefore
# weighs it ~0, which is how the proxy drops ubiquitous words without a stopword list.
_FILLER = "lorem ipsum dolor sit amet consectetur. "
_DISTINCTIVE = "zephyr quixotic tessellation manifold"


def _locus_fixture() -> tuple[str, dict[str, str], str, int]:
    """Gold doc with a distinctive passage buried at ~6k chars + two filler-only docs."""
    prefix = _FILLER * 154                       # ~6160 chars
    gold_text = prefix + _DISTINCTIVE + ". " + _FILLER * 40
    doc_texts = {
        "gold1": gold_text,
        "d2": _FILLER * 30,
        "d3": _FILLER * 30 + "unrelated padding words.",
    }
    query = "what does " + _DISTINCTIVE + " mean"
    return gold_text, doc_texts, query, gold_text.index(_DISTINCTIVE)


def _weights_for(doc_texts: dict[str, str], query: str) -> dict[str, int]:
    df, n_docs = query_locus.document_frequency(doc_texts.values())
    terms = [tok for _, tok in query_locus.tokens_with_offsets(query)]
    return query_locus.rarity_weights(terms, df, n_docs)


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


# --- query-locus PROXY (§B.1a) -----------------------------------------------------------


def test_query_locus_locates_known_window():
    gold_text, doc_texts, query, expected = _locus_fixture()
    weights = _weights_for(doc_texts, query)
    hit = query_locus.locate_query_locus(gold_text, query, weights)
    assert hit is not None
    offset, score = hit
    assert offset == expected          # the distinctive passage, not the filler head
    assert score > 0
    # a doc without any distinguishing query term resolves to nothing, never to offset 0
    assert query_locus.locate_query_locus(doc_texts["d2"], query, weights) is None


def test_query_locus_weighs_ubiquitous_terms_far_below_rare_ones():
    # No stopword list: rarity alone demotes a term that is in every doc.
    _, doc_texts, query, _ = _locus_fixture()
    weights = _weights_for(doc_texts, query + " lorem ipsum")
    assert 0 < weights["lorem"] < weights["zephyr"] / 5
    assert weights["lorem"] == weights["ipsum"]
    # and the demotion deepens with corpus size: at N=200 it is ~1/1000th of a df=1 term
    big = query_locus.rarity_weights(
        ["everywhere", "once"], {"everywhere": 200, "once": 1}, 200)
    assert big["everywhere"] * 1000 < big["once"]


def test_query_locus_deterministic_and_ties_go_to_earliest():
    gold_text, doc_texts, query, expected = _locus_fixture()
    weights = _weights_for(doc_texts, query)
    assert query_locus.locate_query_locus(gold_text, query, weights) == \
        query_locus.locate_query_locus(gold_text, query, weights)
    # Two identical passages -> identical scores; the EARLIER window must win.
    doubled = gold_text + _DISTINCTIVE + ". " + _FILLER * 10
    second = doubled.rindex(_DISTINCTIVE)
    assert second != expected
    off, _ = query_locus.locate_query_locus(doubled, query, weights)
    assert off == expected


def test_resolve_priority_metadata_beats_string_beats_proxy():
    gold_text, doc_texts, query, locus_offset = _locus_fixture()
    answer = "the filed opinion states plainly"
    doc_texts = dict(doc_texts, gold1=gold_text + " " + answer + " here.")
    string_offset = doc_texts["gold1"].index(answer)
    weights = _weights_for(doc_texts, query)
    meta = {"gold1": {"char_offset": 123, "doc_len": len(doc_texts["gold1"])}}

    full = {"q0001": {"answer": answer, "query": query}}
    assert offset_recall.resolve_offset("q0001", ["gold1"], doc_texts, full, meta,
                                        locus_weights=weights)[:2] == (123, "metadata")
    # no metadata -> the answer string, NOT the proxy (which points elsewhere)
    assert offset_recall.resolve_offset("q0001", ["gold1"], doc_texts, full, None,
                                        locus_weights=weights)[:2] == \
        (string_offset, "string_match")
    # no metadata and no answer string -> the proxy, at the distinctive passage
    no_answer = {"q0001": {"answer": "", "query": query}}
    assert offset_recall.resolve_offset("q0001", ["gold1"], doc_texts, no_answer, None,
                                        locus_weights=weights)[:2] == \
        (locus_offset, "query_locus")
    # …and without weights the proxy cannot run at all: unresolved, reason names the tier
    off, source, _, reason = offset_recall.resolve_offset(
        "q0001", ["gold1"], doc_texts, no_answer, None)
    assert (off, source, reason) == (None, "unresolved", "no_evidence_string")


def test_build_report_accounts_proxy_separately_and_never_merges_curves():
    gold_text, doc_texts, query, locus_offset = _locus_fixture()
    doc_texts = dict(doc_texts, d1="the cat sat on the mat")
    modes = {"hybrid": {"q0001": ["d1"], "q0002": ["gold1"]}}
    qrels = {"q0001": {"d1": 1}, "q0002": {"gold1": 1}}
    query_meta = {
        # answer resolvable by string at offset 0 AND a query the proxy could locate:
        # the measured source must win, so this query stays out of the proxy curve.
        "q0001": {"answer": "the cat sat", "query": query},
        "q0002": {"answer": "", "query": query},
    }
    report = offset_recall.build_report(modes, qrels, doc_texts, query_meta)

    assert report["schema"] == "offset-recall.v2"
    res = report["resolution"]
    assert res["by_source"] == {"metadata": 0, "string_match": 1, "query_locus": 1}
    assert (res["resolved"], res["resolved_measured"], res["resolved_proxy"]) == (2, 1, 1)

    by_qid = {q["qid"]: q for q in report["per_query"]}
    assert by_qid["q0001"]["source"] == "string_match"
    assert by_qid["q0001"]["resolution_class"] == "measured"
    assert by_qid["q0002"]["source"] == "query_locus"
    assert by_qid["q0002"]["resolution_class"] == "proxy"
    assert by_qid["q0002"]["offset"] == locus_offset
    assert 4000 <= locus_offset < 8000

    # Disjoint curve blocks: the measured query is in `curves` only, the proxy query in
    # `proxy_curves` only. A merged report would show n=1 in both bins of one block.
    assert report["curves"]["hybrid"]["0-1k"]["n"] == 1
    assert report["curves"]["hybrid"]["4k-8k"]["n"] == 0
    assert report["proxy_curves"]["hybrid"]["4k-8k"]["n"] == 1
    assert report["proxy_curves"]["hybrid"]["0-1k"]["n"] == 0
    assert report["curves_are_proxy"] is False
    assert report["curves_resolution_sources"] == ["metadata", "string_match"]
    assert report["proxy"]["is_proxy"] is True
    assert report["proxy"]["n_resolved"] == 1
    assert report["proxy"]["window_chars"] == query_locus.DEFAULT_WINDOW_CHARS

    # Determinism over the whole (proxy-inclusive) report.
    again = offset_recall.build_report(modes, qrels, doc_texts, query_meta)
    assert json.dumps(report, sort_keys=True) == json.dumps(again, sort_keys=True)


def test_format_table_labels_proxy_rows_distinctly():
    _, doc_texts, query, _ = _locus_fixture()
    modes = {"hybrid": {"q0001": ["gold1"]}}
    report = offset_recall.build_report(
        modes, {"q0001": {"gold1": 1}}, doc_texts,
        {"q0001": {"answer": "", "query": query}})
    table = offset_recall.format_table(report)

    assert "query_locus=1" in table
    assert "PROXY (query_locus)" in table
    assert "NOT where the answer sits" in table
    assert "hybrid~proxy" in table          # proxy rows are tagged in the mode column
    # the measured section must not print rows it has no queries for
    measured, proxy = table.split("[PROXY", 1)
    assert "(no queries resolved from this source)" in measured
    assert "hybrid~proxy        4k-8k" in " ".join(proxy.split("\n"))

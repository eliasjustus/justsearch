"""Tests for jseval.corpus_leak — gold-vs-native leak measurement (tempdoc 767).

Pure-function / fixture tests, no I/O, no network, no model. Mirrors the
table-driven style of ``test_corpus_governance.py``'s descriptor_collision_report
tests: for each measure, a clean corpus that passes, a corpus carrying the specific
defect that gets caught, and a degraded/empty input that does not crash.
"""

from __future__ import annotations

from jseval import corpus_leak


def _doc(doc_id: str, text: str, title: str = "") -> dict:
    return {"_id": doc_id, "title": title, "text": text}


# ---------------------------------------------------------------------------
# token_document_frequency — the token-boundary helper everything else builds on
# ---------------------------------------------------------------------------

def test_token_document_frequency_counts_per_document():
    docs = [
        _doc("d1", "alpha beta alpha"),  # "alpha" appears twice, counts once
        _doc("d2", "beta gamma"),
        _doc("d3", "delta"),
    ]
    report = corpus_leak.token_document_frequency(docs, ["alpha", "beta", "missing"])
    assert report["counts"] == {"alpha": 1, "beta": 2, "missing": 0}
    assert report["n_docs"] == 3
    assert report["matching_mode"] == "token-boundary"
    assert report["unit"] == "per-document"


def test_token_document_frequency_is_token_boundary_not_substring():
    # The exact bug this module exists to prevent: "spa" must NOT match inside
    # "newspaper", "disparate", or "space" — only a standalone "spa" token counts.
    docs = [
        _doc("d1", "I read the newspaper on the disparate space station"),
        _doc("d2", "the day spa was closed"),
        _doc("d3", "nothing relevant here"),
    ]
    report = corpus_leak.token_document_frequency(docs, ["spa"])
    assert report["counts"]["spa"] == 1  # only d2 contains the standalone token
    assert report["matching_mode"] == "token-boundary"


def test_token_document_frequency_empty_docs_does_not_crash():
    report = corpus_leak.token_document_frequency([], ["anything"])
    assert report["counts"] == {"anything": 0}
    assert report["n_docs"] == 0


# ---------------------------------------------------------------------------
# ngram_selectivity_report — defect #1: byte-identical boilerplate across gold docs
# ---------------------------------------------------------------------------

_NGRAM_NATIVE = [
    _doc("native1", "alpha bravo charlie delta echo foxtrot golf hotel india juliet"),
    _doc("native2", "kilo lima mike november oscar papa quebec romeo sierra tango"),
]
_NGRAM_QUERIES = [
    {"query": "q1", "evidence_ids": ["gold1"]},
    {"query": "q2", "evidence_ids": ["gold2"]},
]


def test_ngram_selectivity_report_clean_corpus_passes():
    # No n-gram repeats anywhere in the corpus (every word is unique corpus-wide),
    # so gold's max coverage should not exceed the native-only base rate.
    docs = _NGRAM_NATIVE + [
        _doc("gold1", "uniform victor whiskey xray yankee zulu apple banana cherry date"),
        _doc("gold2", "eggplant fig grape honeydew jujube kiwi lemon mango nectarine olive"),
    ]
    report = corpus_leak.ngram_selectivity_report(docs, _NGRAM_QUERIES, n=3)
    assert report["n_gold"] == 2
    assert report["n_native"] == 2
    assert report["matching_mode"] == "token-boundary"
    assert report["unit"] == "per-document"
    assert report["passed"] is True
    assert report["max_gold_coverage"] <= report["native_base_rate"]


def test_ngram_selectivity_report_catches_boilerplate_defect():
    # Both gold docs share an identical trailing trigram not present in any native
    # doc -> that n-gram covers 100% of gold docs and 0% of native docs.
    boilerplate = "red green blue"
    docs = _NGRAM_NATIVE + [
        _doc("gold1", f"uniform victor whiskey xray yankee zulu apple banana cherry date {boilerplate}"),
        _doc("gold2", f"eggplant fig grape honeydew jujube kiwi lemon mango nectarine olive {boilerplate}"),
    ]
    report = corpus_leak.ngram_selectivity_report(docs, _NGRAM_QUERIES, n=3)
    assert report["passed"] is False
    assert report["max_gold_coverage"] == 1.0
    top = report["top_offenders"][0]
    assert top["ngram"] == boilerplate
    assert top["gold_coverage"] == 1.0
    assert top["native_coverage"] == 0.0


def test_ngram_selectivity_report_real_cell_shape_asymmetric_native_dominant():
    # Real-cell shape (tempdoc 767 coordinator follow-up, en-legal-clerc-1k-verbose):
    # a handful of gold docs sharing a boilerplate n-gram, embedded mid-document
    # among many more, longer, varied-length native docs (n_native >> n_gold).
    # This shape (large gold docs -> tens of thousands of distinct gold n-grams)
    # exposed an O(offenders * n_native) hot loop in the native-coverage lookup
    # (measured ~116M set lookups / ~6s on the real 1000-doc cell); fixed to a
    # single-pass O(total native n-grams) count dict. Covers the positive-catch
    # case at this shape so it isn't verified only by the one-off manual run.
    boilerplate = "quiet markets where traders gather"
    gold = [
        _doc(f"gold{i}", f"{_wordy(20 + i, f'g{i}_')} {boilerplate} {_wordy(15 + i, f'h{i}_')}")
        for i in range(5)
    ]
    native = [_doc(f"native{i}", _wordy(10 + i * 7, f"n{i}_")) for i in range(30)]
    queries = [{"query": "q", "evidence_ids": [d["_id"] for d in gold]}]

    report = corpus_leak.ngram_selectivity_report(gold + native, queries, n=5)
    assert report["n_gold"] == 5
    assert report["n_native"] == 30
    assert report["passed"] is False
    assert report["max_gold_coverage"] == 1.0
    top = report["top_offenders"][0]
    assert top["ngram"] == boilerplate
    assert top["gold_coverage"] == 1.0
    assert top["native_coverage"] == 0.0


def test_ngram_selectivity_report_empty_input_does_not_crash():
    report = corpus_leak.ngram_selectivity_report([], [])
    assert report["n_gold"] == 0
    assert report["n_native"] == 0
    assert report["max_gold_coverage"] == 0.0
    assert report["native_base_rate"] == 0.0
    assert report["passed"] is None
    assert report["top_offenders"] == []


def test_ngram_selectivity_report_is_deterministic():
    docs = _NGRAM_NATIVE + [
        _doc("gold1", "uniform victor whiskey xray yankee zulu apple banana cherry date red green blue"),
        _doc("gold2", "eggplant fig grape honeydew jujube kiwi lemon mango nectarine olive red green blue"),
    ]
    first = corpus_leak.ngram_selectivity_report(docs, _NGRAM_QUERIES, n=3)
    second = corpus_leak.ngram_selectivity_report(docs, _NGRAM_QUERIES, n=3)
    assert first == second


# ---------------------------------------------------------------------------
# length_profile_report — defect #2: gold docs are much longer than native docs
# ---------------------------------------------------------------------------

def _wordy(n_words: int, prefix: str) -> str:
    return " ".join(f"{prefix}{i}" for i in range(n_words))


def test_length_profile_report_clean_corpus_passes():
    native = [_doc(f"native{i}", _wordy(8 + i, "n")) for i in range(5)]  # 8..12 words
    gold = [_doc("gold1", _wordy(9, "g")), _doc("gold2", _wordy(10, "g"))]
    queries = [{"query": "q1", "evidence_ids": ["gold1", "gold2"]}]
    report = corpus_leak.length_profile_report(native + gold, queries)
    assert report["n_gold"] == 2
    assert report["n_native"] == 5
    assert report["gold_median_inside_native_p5_p95"] is True
    assert report["separability"] == 0.0
    assert report["passed"] is True


def test_length_profile_report_catches_length_defect():
    native = [_doc(f"native{i}", _wordy(8 + i, "n")) for i in range(5)]  # 8..12 words
    gold = [_doc("gold1", _wordy(80, "g")), _doc("gold2", _wordy(85, "g"))]  # far longer
    queries = [{"query": "q1", "evidence_ids": ["gold1", "gold2"]}]
    report = corpus_leak.length_profile_report(native + gold, queries)
    assert report["gold_median_inside_native_p5_p95"] is False
    assert report["separability"] == 1.0
    assert report["passed"] is False


def test_length_profile_report_empty_input_does_not_crash():
    report = corpus_leak.length_profile_report([], [])
    assert report["n_gold"] == 0
    assert report["n_native"] == 0
    assert report["gold_median_inside_native_p5_p95"] is None
    assert report["separability"] is None
    assert report["passed"] is None


def test_length_profile_report_is_deterministic():
    native = [_doc(f"native{i}", _wordy(8 + i, "n")) for i in range(5)]
    gold = [_doc("gold1", _wordy(80, "g"))]
    queries = [{"query": "q1", "evidence_ids": ["gold1"]}]
    first = corpus_leak.length_profile_report(native + gold, queries)
    second = corpus_leak.length_profile_report(native + gold, queries)
    assert first == second


# ---------------------------------------------------------------------------
# query_overlap_report — LOW overlap is GOOD (opposite polarity from the rest)
# ---------------------------------------------------------------------------

def test_query_overlap_report_computes_jaccard_and_overlap_coefficient():
    docs = [
        _doc("gold1", "red green blue"),
        _doc("gold2", "apple banana cherry"),
    ]
    queries = [
        {"query": "red purple orange", "evidence_ids": ["gold1"]},  # shares "red"
        {"query": "date fig grape", "evidence_ids": ["gold2"]},  # shares nothing
    ]
    report = corpus_leak.query_overlap_report(docs, queries)
    assert report["n_queries"] == 2
    assert report["polarity"] == "low-is-good"

    q1 = next(p for p in report["per_query"] if p["evidence_ids"] == ["gold1"])
    # query tokens {red,purple,orange}, doc tokens {red,green,blue}; intersection={red}
    assert q1["n_shared_tokens"] == 1
    assert q1["jaccard"] == 1 / 5
    assert q1["overlap_coefficient"] == 1 / 3

    q2 = next(p for p in report["per_query"] if p["evidence_ids"] == ["gold2"])
    assert q2["n_shared_tokens"] == 0
    assert q2["jaccard"] == 0.0
    assert q2["overlap_coefficient"] == 0.0

    assert report["n_zero_overlap"] == 1


def test_query_overlap_report_strips_question_scaffolding_words():
    # "value", "associated", "designer" etc. must not count as overlap even though
    # they appear verbatim in both the query template and could appear in a doc.
    docs = [_doc("gold1", "the value associated with the designer was crimson")]
    queries = [{
        "query": "What is the value associated with the designer of the station?",
        "evidence_ids": ["gold1"],
    }]
    report = corpus_leak.query_overlap_report(docs, queries)
    assert report["per_query"][0]["n_shared_tokens"] == 0


def test_query_overlap_report_empty_input_does_not_crash():
    report = corpus_leak.query_overlap_report([], [])
    assert report["n_queries"] == 0
    assert report["n_zero_overlap"] == 0
    assert report["per_query"] == []


def test_query_overlap_report_is_deterministic():
    docs = [_doc("gold1", "red green blue")]
    queries = [{"query": "red purple orange", "evidence_ids": ["gold1"]}]
    first = corpus_leak.query_overlap_report(docs, queries)
    second = corpus_leak.query_overlap_report(docs, queries)
    assert first == second


# ---------------------------------------------------------------------------
# rare_token_leak_report — THE most important measure: a df<=floor grep anchor
# ---------------------------------------------------------------------------

def test_rare_token_leak_report_clean_corpus_does_not_leak():
    # "common" is shared by gold1 and 5 native docs -> df=6, above the df_floor=5
    # default, so it is not a usable grep anchor.
    gold = [_doc("gold1", "quorbex flarnut wibbleton common")]
    native = [_doc(f"native{i}", f"common filler{i}") for i in range(5)]
    queries = [{"query": "what is common", "evidence_ids": ["gold1"]}]
    report = corpus_leak.rare_token_leak_report(gold + native, queries, df_floor=5)
    assert report["n_leaking_at_floor"] == 0
    assert report["fraction_leaking_at_floor"] == 0.0
    assert report["passed"] is True
    assert report["per_query"][0]["leaks_at_floor"] is False


def test_rare_token_leak_report_catches_rare_anchor_defect():
    # "quorbex" and "flarnut" appear ONLY in gold1 (df=1) and also in the query ->
    # a perfect grep anchor.
    gold = [_doc("gold1", "quorbex flarnut wibbleton")]
    native = [
        _doc("native1", "alpha beta gamma"),
        _doc("native2", "delta epsilon zeta"),
        _doc("native3", "eta theta iota"),
    ]
    queries = [{"query": "quorbex flarnut kappa", "evidence_ids": ["gold1"]}]
    report = corpus_leak.rare_token_leak_report(gold + native, queries, df_floor=5)
    assert report["n_leaking_at_floor"] == 1
    assert report["fraction_leaking_at_floor"] == 1.0
    assert report["passed"] is False
    leaked = report["per_query"][0]["leaked_tokens"]
    leaked_names = {item["token"] for item in leaked}
    assert leaked_names == {"quorbex", "flarnut"}
    assert all(item["df"] == 1 for item in leaked)
    assert report["fraction_leaking_by_threshold"]["1"] == 1.0


def test_rare_token_leak_report_empty_input_does_not_crash():
    report = corpus_leak.rare_token_leak_report([], [])
    assert report["n_queries"] == 0
    assert report["n_leaking_at_floor"] == 0
    assert report["fraction_leaking_at_floor"] == 0.0
    assert report["passed"] is None


def test_rare_token_leak_report_is_deterministic():
    gold = [_doc("gold1", "quorbex flarnut wibbleton")]
    native = [_doc("native1", "alpha beta gamma")]
    queries = [{"query": "quorbex flarnut kappa", "evidence_ids": ["gold1"]}]
    first = corpus_leak.rare_token_leak_report(gold + native, queries, df_floor=5)
    second = corpus_leak.rare_token_leak_report(gold + native, queries, df_floor=5)
    assert first == second


# ---------------------------------------------------------------------------
# id_shape_report — the CHEAPEST channel: gold vs native from the ID alone
# ---------------------------------------------------------------------------

def _numeric_ids(n: int, *, start: int) -> list[str]:
    """Deterministic native-looking CLERC-shaped ids (7-digit integers)."""
    return [str(start + index * 7919) for index in range(n)]


def test_id_shape_report_catches_committed_cell_defect_shape():
    # The exact committed-cell shape (tempdoc 767 defect #3): fabricated entity-surface
    # ids among numeric CLERC hosts. One character-class rule selects every gold doc.
    gold_ids = ["breldac18", "brelker20", "cavby8", "drudell17", "druker7"]
    native_ids = _numeric_ids(95, start=1000731)
    docs = [_doc(i, "body") for i in gold_ids + native_ids]
    queries = [{"query": "q", "evidence_ids": gold_ids}]

    report = corpus_leak.id_shape_report(docs, queries)
    assert report["passed"] is False
    assert report["n_gold"] == 5
    assert report["n_native"] == 95
    assert report["best_rule"]["precision"] == 1.0
    assert report["best_rule"]["recall"] == 1.0
    assert report["separability"] == 1.0
    assert report["gold_shape_classes"] == {"lower-alphanumeric": 5}
    assert report["native_shape_classes"] == {"all-digits": 95}
    assert report["matching_mode"] == "whole-id"
    assert report["unit"] == "per-document"
    assert report["method"] == "id-shape-rule-separability"


def test_id_shape_report_catches_negated_rule():
    # An agent can invert any rule, so a gold set that is the COMPLEMENT of a clean
    # character class must be caught just as hard as the direct form.
    gold_ids = _numeric_ids(5, start=1000731)
    native_ids = [f"case-{index}x" for index in range(95)]
    docs = [_doc(i, "body") for i in gold_ids + native_ids]
    queries = [{"query": "q", "evidence_ids": gold_ids}]

    report = corpus_leak.id_shape_report(docs, queries)
    assert report["passed"] is False
    assert report["separability"] == 1.0
    assert report["best_rule"]["recall"] == 1.0


def test_id_shape_report_catches_length_only_leak():
    # Same character class on both sides — the leak is length alone. The gold ids are
    # zero-padded to 11 chars from values INSIDE the native integer range, so the
    # numeric-magnitude axis cannot separate them and `len` is genuinely the only
    # perfect rule. (The original fixture used 10_000_000_000+index, which is both
    # longer AND numerically larger than every native — so it did not isolate length,
    # and its `len(id)` assertion only held because no numeric axis existed yet.)
    native_ids = _numeric_ids(95, start=1000731)
    gold_ids = [str(1000731 + index * 7919 + 3).zfill(11) for index in range(5)]
    docs = [_doc(i, "body") for i in gold_ids + native_ids]
    queries = [{"query": "q", "evidence_ids": gold_ids}]

    report = corpus_leak.id_shape_report(docs, queries)
    assert report["passed"] is False
    assert report["best_rule"]["recall"] == 1.0
    assert "len(id)" in report["best_rule"]["rule"]


def test_id_shape_report_catches_numeric_magnitude_leak():
    # The 635-corpora shape (tempdoc 767): gold/evidence docs numbered 1..N and
    # distractors N+1..M behind an alphabetic stem, with ZERO range overlap. No
    # character-class, length, or 1-3 char affix rule sees this, but
    # `trailing_int(id) <= N` separates it perfectly. Regression for the blind spot
    # that made this report call `635-corpora/needle-burial-v1` a J = 0.379 corpus.
    stems = ["brel", "cav", "dru", "orr", "quen", "kan", "wend", "olm"]
    gold_ids = [f"{stems[index % len(stems)]}dac{index + 1}" for index in range(40)]
    native_ids = [f"{stems[index % len(stems)]}stone{index + 41}" for index in range(240)]
    docs = [_doc(i, "body") for i in gold_ids + native_ids]
    queries = [{"query": "q", "evidence_ids": gold_ids}]

    report = corpus_leak.id_shape_report(docs, queries)
    assert report["passed"] is False
    assert report["separability"] == 1.0
    assert report["best_rule"]["rule"] == "trailing_int(id) <= 40"
    assert report["best_rule"]["precision"] == 1.0
    assert report["best_rule"]["recall"] == 1.0
    assert report["best_rule"]["false_positives"] == 0


def test_id_shape_report_catches_negated_numeric_magnitude_leak():
    # An agent inverts a rule for free, so the complement form must be caught too:
    # here the GOLD ids carry the high integers and the natives the low ones.
    gold_ids = [f"doc{index + 241}" for index in range(40)]
    native_ids = [f"doc{index + 1}" for index in range(240)]
    docs = [_doc(i, "body") for i in gold_ids + native_ids]
    queries = [{"query": "q", "evidence_ids": gold_ids}]

    report = corpus_leak.id_shape_report(docs, queries)
    assert report["passed"] is False
    assert report["separability"] == 1.0
    assert "trailing_int(id)" in report["best_rule"]["rule"]
    assert report["best_rule"]["rule"].startswith("not (")


def test_id_shape_report_numeric_axis_does_not_fire_on_shape_matched_ids():
    # The numeric axis must not invent a leak where the gold ids are drawn from the
    # SAME integer population as the natives — this is the false-positive direction,
    # and it is why the null runs 25 draws rather than 5 (a max-over-draws null is
    # monotone in the draw count, and at 5 it under-estimates same-population noise).
    all_ids = _numeric_ids(200, start=1000731)
    gold_ids = [all_ids[index] for index in range(3, 200, 17)]
    native_ids = [i for i in all_ids if i not in set(gold_ids)]
    docs = [_doc(i, "body") for i in gold_ids + native_ids]
    queries = [{"query": "q", "evidence_ids": gold_ids}]

    report = corpus_leak.id_shape_report(docs, queries)
    assert report["passed"] is True
    assert report["separability"] <= report["native_base_rate"]


def test_id_shape_report_passes_on_shape_matched_ids():
    # Gold ids drawn from the SAME id space as the natives (what
    # corpus_inject.mint_native_shaped_ids produces) — no rule beats the null.
    all_ids = _numeric_ids(100, start=1000731)
    gold_ids = [all_ids[index] for index in (3, 17, 41, 58, 84)]
    native_ids = [i for i in all_ids if i not in set(gold_ids)]
    docs = [_doc(i, "body") for i in gold_ids + native_ids]
    queries = [{"query": "q", "evidence_ids": gold_ids}]

    report = corpus_leak.id_shape_report(docs, queries)
    assert report["passed"] is True
    assert report["separability"] <= report["native_base_rate"]
    assert report["gold_shape_classes"] == {"all-digits": 5}
    assert report["native_shape_classes"] == {"all-digits": 95}


def test_id_shape_report_empty_input_does_not_crash():
    report = corpus_leak.id_shape_report([], [])
    assert report["n_gold"] == 0
    assert report["n_native"] == 0
    assert report["best_rule"] is None
    # An absent measurement is never a pass.
    assert report["passed"] is None


def test_id_shape_report_all_gold_no_native_does_not_crash():
    docs = [_doc("gold1", "body"), _doc("gold2", "body")]
    queries = [{"query": "q", "evidence_ids": ["gold1", "gold2"]}]
    report = corpus_leak.id_shape_report(docs, queries)
    assert report["n_native"] == 0
    assert report["passed"] is None


def test_id_shape_report_tolerates_missing_and_nonstring_ids():
    docs = [
        {"title": "", "text": "no id at all"},
        {"_id": 1000731, "title": "", "text": "integer id"},
        _doc("breldac18", "body"),
    ]
    queries = [{"query": "q", "evidence_ids": ["breldac18"]}]
    report = corpus_leak.id_shape_report(docs, queries)
    assert report["n_gold"] == 1
    assert report["n_native"] == 1
    assert report["native_shape_classes"] == {"all-digits": 1}


def test_id_shape_report_is_deterministic():
    gold_ids = ["breldac18", "brelker20"]
    native_ids = _numeric_ids(20, start=1000731)
    docs = [_doc(i, "body") for i in gold_ids + native_ids]
    queries = [{"query": "q", "evidence_ids": gold_ids}]
    first = corpus_leak.id_shape_report(docs, queries)
    second = corpus_leak.id_shape_report(docs, queries)
    assert first == second

"""Tests for jseval.grep_replay — offline grep-baseline replay probe (tempdoc 767).

Pure-function / fixture tests, no I/O, no network, no model, no eval log. Mirrors
the table-driven style of ``test_corpus_leak.py``: for each measure, a corpus
where the property holds, a corpus carrying the specific defect, and a degraded
or empty input that does not crash.
"""

from __future__ import annotations

from jseval import grep_replay


def _doc(doc_id: str, text: str, title: str = "") -> dict:
    return {"_id": doc_id, "title": title, "text": text}


def _query(evidence: list[str], qid: str = "q0001") -> dict:
    return {"query": "what is the value", "evidence_ids": evidence, "query_family_id": qid}


# A corpus where the gold docs carry a rare term ("zephyrite") and every doc
# carries a flooding one ("court").
_REACHABLE_DOCS = [
    _doc("gold1", "the zephyrite mill stands in court"),
    _doc("gold2", "zephyrite output was filed in court"),
] + [_doc(f"host{i}", "an ordinary opinion filed in court") for i in range(20)]


# ---------------------------------------------------------------------------
# extract_search_terms — the terms come from the log, not from an assumption
# ---------------------------------------------------------------------------

def test_extract_search_terms_from_grep_tool_pattern():
    calls = [{"tool": "Grep", "input": {"pattern": "upper wetlands|power station",
                                        "path": "C:\\Temp\\corpus-dir"}}]
    # Regex alternation decomposes to its content tokens; the path is not a term.
    assert grep_replay.extract_search_terms(calls) == [
        "power", "station", "upper", "wetlands"]


def test_extract_search_terms_from_bash_grep_takes_pattern_not_path():
    calls = [{"tool": "Bash", "input": {
        "command": 'grep -i "zephyrite" "C:\\Users\\Elias\\AppData\\Local\\Temp\\corpus-dir"/*.txt'}}]
    # Only the pattern argument is a search term — the corpus path that follows
    # it must not be tokenized as if the agent had searched for "users"/"temp".
    assert grep_replay.extract_search_terms(calls) == ["zephyrite"]


def test_extract_search_terms_reads_carry_no_term():
    calls = [{"tool": "Read", "input": {"file_path": "C:\\corpus-dir\\gold1.txt"}}]
    assert grep_replay.extract_search_terms(calls) == []


def test_extract_search_terms_from_powershell_and_glob():
    calls = [
        {"tool": "PowerShell", "input": {"command": 'Select-String -Pattern "Evergreen"'}},
        {"tool": "Glob", "input": {"pattern": "*dale*.txt", "path": "C:\\corpus-dir"}},
    ]
    assert grep_replay.extract_search_terms(calls) == ["dale", "evergreen"]


def test_extract_search_terms_degraded_input_does_not_crash():
    assert grep_replay.extract_search_terms(None) == []
    assert grep_replay.extract_search_terms([{"tool": "Bash", "input": {}}]) == []
    # An unbalanced quote must not raise out of shlex.
    assert grep_replay.extract_search_terms(
        [{"tool": "Bash", "input": {"command": 'grep "unclosed'}}]) == []


# ---------------------------------------------------------------------------
# term_match_sets — token boundary, per document
# ---------------------------------------------------------------------------

def test_term_match_sets_is_token_boundary_not_substring():
    # The exact bug this project was burned by: "spa" must NOT match inside
    # "newspaper", "disparate" or "space".
    docs = [
        _doc("d1", "I read the newspaper on the disparate space station"),
        _doc("d2", "the day spa was closed"),
    ]
    sets = grep_replay.term_match_sets(docs, ["spa"])
    assert sets["spa"] == frozenset({"d2"})


def test_term_match_sets_counts_each_document_once():
    docs = [_doc("d1", "alpha alpha alpha"), _doc("d2", "beta")]
    sets = grep_replay.term_match_sets(docs, ["alpha", "missing"])
    assert sets["alpha"] == frozenset({"d1"})
    assert sets["missing"] == frozenset()


def test_term_match_sets_matches_title_as_well_as_text():
    docs = [_doc("d1", "body text", title="Zephyrite Mill")]
    assert grep_replay.term_match_sets(docs, ["zephyrite"])["zephyrite"] == frozenset({"d1"})


def test_term_match_sets_prebuilt_index_matches_unindexed():
    docs = _REACHABLE_DOCS
    terms = ["zephyrite", "court", "absent"]
    index = grep_replay.build_index(docs)
    assert grep_replay.term_match_sets(docs, terms, index=index) == \
        grep_replay.term_match_sets(docs, terms)


# ---------------------------------------------------------------------------
# query_reachability — the explicit reachability rule
# ---------------------------------------------------------------------------

def test_query_reachability_gold_trivially_reachable():
    report = grep_replay.query_reachability(
        _REACHABLE_DOCS, _query(["gold1", "gold2"]), ["zephyrite"], flood_threshold=20)
    assert report["reachable"] is True
    assert report["n_evidence_reached"] == 2
    assert report["match_set_sizes"]["zephyrite"] == 2
    assert report["matching_mode"] == "token-boundary"
    assert report["unit"] == "per-document"


def test_query_reachability_gold_unreachable_when_no_term_hits_it():
    # The agent searched for real words that simply are not in the gold docs.
    report = grep_replay.query_reachability(
        _REACHABLE_DOCS, _query(["gold1", "gold2"]), ["ordinary", "opinion"],
        flood_threshold=20)
    assert report["reachable"] is False
    assert report["n_evidence_reached"] == 0


def test_query_reachability_flooding_term_does_not_make_gold_reachable():
    # "court" DOES match both gold docs, but it also matches all 22 docs. Under a
    # flood_threshold of 5 the agent would never open its way through that set,
    # so a technically-matching term must not count as reachability.
    docs = _REACHABLE_DOCS
    flooded = grep_replay.query_reachability(
        docs, _query(["gold1", "gold2"]), ["court"], flood_threshold=5)
    assert flooded["reachable"] is False
    assert flooded["n_terms_flooded"] == 1
    assert flooded["n_terms_usable"] == 0
    # Raise the threshold above the match-set size and the same term becomes usable.
    permitted = grep_replay.query_reachability(
        docs, _query(["gold1", "gold2"]), ["court"], flood_threshold=50)
    assert permitted["reachable"] is True


def test_query_reachability_require_all_evidence_is_stricter():
    docs = _REACHABLE_DOCS + [_doc("gold3", "an unrelated rare quartzine note")]
    q = _query(["gold1", "gold3"])
    any_rule = grep_replay.query_reachability(docs, q, ["zephyrite"], flood_threshold=20)
    all_rule = grep_replay.query_reachability(
        docs, q, ["zephyrite"], flood_threshold=20, require_all_evidence=True)
    assert any_rule["reachable"] is True   # gold1 reached
    assert all_rule["reachable"] is False  # gold3 never reached


def test_query_reachability_no_evidence_is_none_not_true():
    # An absent measurement is not a passing one.
    report = grep_replay.query_reachability(
        _REACHABLE_DOCS, _query([]), ["zephyrite"], flood_threshold=20)
    assert report["reachable"] is None


def test_query_reachability_no_terms_is_unreachable():
    report = grep_replay.query_reachability(
        _REACHABLE_DOCS, _query(["gold1"]), [], flood_threshold=20)
    assert report["reachable"] is False
    assert report["n_terms"] == 0


# ---------------------------------------------------------------------------
# grep_reachability_report — corpus-level summary
# ---------------------------------------------------------------------------

_TWO_QUERIES = [_query(["gold1"], "qA"), _query(["gold2"], "qB")]


def test_grep_reachability_report_all_reachable():
    report = grep_replay.grep_reachability_report(
        _REACHABLE_DOCS, _TWO_QUERIES,
        {"qA": ["zephyrite"], "qB": ["zephyrite"]}, flood_threshold=20)
    assert report["reachable_fraction"] == 1.0
    assert report["n_reachable"] == 2
    assert report["n_gold"] == 2
    assert report["n_native"] == 20
    assert report["passed"] is True
    assert report["method"] == "grep-replay-reachability"


def test_grep_reachability_report_none_reachable():
    report = grep_replay.grep_reachability_report(
        _REACHABLE_DOCS, _TWO_QUERIES,
        {"qA": ["ordinary"], "qB": ["opinion"]}, flood_threshold=20)
    assert report["reachable_fraction"] == 0.0
    assert report["passed"] is False


def test_grep_reachability_report_falls_back_to_positional_query_key():
    # Terms keyed "q0"/"q1" (the eval log's ``arm|qid`` convention) resolve even
    # though the queries carry query_family_id "qA"/"qB".
    report = grep_replay.grep_reachability_report(
        _REACHABLE_DOCS, _TWO_QUERIES,
        {"q0": ["zephyrite"], "q1": ["zephyrite"]}, flood_threshold=20)
    assert report["reachable_fraction"] == 1.0
    assert report["n_queries_with_terms"] == 2


def test_grep_reachability_report_no_terms_recovered_is_none_not_false():
    report = grep_replay.grep_reachability_report(
        _REACHABLE_DOCS, _TWO_QUERIES, {}, flood_threshold=20)
    assert report["n_queries_with_terms"] == 0
    assert report["passed"] is None


def test_grep_reachability_report_empty_queries_does_not_crash():
    report = grep_replay.grep_reachability_report(_REACHABLE_DOCS, [], {})
    assert report["n_queries"] == 0
    assert report["passed"] is None


# ---------------------------------------------------------------------------
# reachability_sensitivity_report — the verdict must not be one threshold's artifact
# ---------------------------------------------------------------------------

def test_sensitivity_report_exposes_threshold_dependence():
    # "court" matches all 22 docs, so reachability flips as the threshold crosses 22.
    report = grep_replay.reachability_sensitivity_report(
        _REACHABLE_DOCS, _TWO_QUERIES, {"qA": ["court"], "qB": ["court"]},
        flood_thresholds=(5, 50))
    fractions = report["reachable_fraction_by_threshold"]
    assert fractions[5] == 0.0
    assert fractions[50] == 1.0
    assert report["reachable_fraction_spread"] == 1.0


def test_sensitivity_report_stable_verdict_has_zero_spread():
    report = grep_replay.reachability_sensitivity_report(
        _REACHABLE_DOCS, _TWO_QUERIES, {"qA": ["zephyrite"], "qB": ["zephyrite"]},
        flood_thresholds=(5, 10, 20, 50))
    assert report["reachable_fraction_spread"] == 0.0
    assert set(report["by_threshold"]) == {5, 10, 20, 50}


# ---------------------------------------------------------------------------
# determinism
# ---------------------------------------------------------------------------

def test_reports_are_deterministic_across_repeat_calls():
    terms = {"qA": ["zephyrite", "court"], "qB": ["court", "ordinary"]}
    first = grep_replay.grep_reachability_report(_REACHABLE_DOCS, _TWO_QUERIES, terms)
    second = grep_replay.grep_reachability_report(_REACHABLE_DOCS, _TWO_QUERIES, terms)
    assert first == second
    sens_a = grep_replay.reachability_sensitivity_report(_REACHABLE_DOCS, _TWO_QUERIES, terms)
    sens_b = grep_replay.reachability_sensitivity_report(_REACHABLE_DOCS, _TWO_QUERIES, terms)
    assert sens_a == sens_b


def test_extract_search_terms_is_deterministic_and_sorted():
    calls = [{"tool": "Grep", "input": {"pattern": "zulu|alpha|mike"}}]
    out = grep_replay.extract_search_terms(calls)
    assert out == sorted(out)
    assert out == grep_replay.extract_search_terms(calls)

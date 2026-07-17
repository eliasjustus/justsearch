#!/usr/bin/env python3
"""Self-tests for gen_golden_parity.py's corpus-comparability precondition
(tempdoc 734 finding 5) and its golden-parity-format-v2 baseline (tempdoc 750
Part A: per-leg scores, per-leg top-10 capture, calibration metadata) against
synthetic fixtures -- no dev stack needed.

Run: python scripts/sandbox/test_gen_golden_parity.py
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from gen_golden_parity import (  # noqa: E402
    HELP_DOC_PROBE_FILENAME,
    HELP_DOC_PROBE_QUERY,
    LEG_MODES,
    check_help_docs_indexed,
    generate,
)
from golden_common import (  # noqa: E402
    BASELINE_FORMAT_VERSION,
    extract_doc_identity,
    normalize_identity,
)


def _search_response(filenames: list[str]) -> dict:
    return {"results": [{"fields": {"filename": name}} for name in filenames]}


class HelpDocsIndexedPreconditionTests(unittest.TestCase):
    """Live-verified 2026-07-16: a fresh (non-eval) dev-stack boot always
    auto-ingests SSOT/docs/help/*.md before any user ingest; jseval's eval
    mode explicitly skips this. A golden-parity baseline generated against a
    stack that skipped it is not corpus-comparable to any real Sandbox
    candidate — this precondition mirrors the existing fingerprint/compat
    gates in `generate()` to catch that at generation time, not at finalize."""

    def test_help_docs_present_passes(self):
        with patch(
            "gen_golden_parity.post_search",
            return_value=_search_response([HELP_DOC_PROBE_FILENAME, "other.txt"]),
        ) as mock_search:
            result = check_help_docs_indexed("http://127.0.0.1:1")
        self.assertIsNone(result)
        mock_search.assert_called_once()
        self.assertEqual(mock_search.call_args.args[1], HELP_DOC_PROBE_QUERY)

    def test_help_docs_absent_fails_closed(self):
        with patch(
            "gen_golden_parity.post_search",
            return_value=_search_response(["unrelated.txt"]),
        ):
            result = check_help_docs_indexed("http://127.0.0.1:1")
        self.assertIsNotNone(result)
        self.assertIn("REFUSING", result)
        self.assertIn(HELP_DOC_PROBE_FILENAME, result)
        self.assertIn("eval mode", result)

    def test_empty_results_fails_closed(self):
        with patch("gen_golden_parity.post_search", return_value={"results": []}):
            result = check_help_docs_indexed("http://127.0.0.1:1")
        self.assertIsNotNone(result)
        self.assertIn("REFUSING", result)

    def test_unreachable_backend_fails_closed(self):
        import urllib.error

        with patch(
            "gen_golden_parity.post_search",
            side_effect=urllib.error.URLError("connection refused"),
        ):
            result = check_help_docs_indexed("http://127.0.0.1:1")
        self.assertIsNotNone(result)
        self.assertIn("could not query", result)


# -----------------------------------------------------------------------------
# golden-parity baseline format v2 (tempdoc 750 Part A)
# -----------------------------------------------------------------------------


def _knowledge_status() -> dict:
    return {
        "embeddingFingerprintCurrent": "sha256:deadbeef",
        "embeddingCompatState": "COMPATIBLE",
        "indexedDocuments": 500,
    }


def _help_docs_response() -> dict:
    return {"results": [{"fields": {"filename": HELP_DOC_PROBE_FILENAME}}]}


def _hit_with_trace(
    identity: str,
    dense_score: float,
    sparse_score: float = 0.1,
    splade_score: float = 0.2,
    fusion_score: float = 0.3,
) -> dict:
    return {
        "id": identity,
        "fields": {"doc_id": identity},
        "trace": [
            {"id": "dense-retrieval", "rank": 1, "score": dense_score},
            {"id": "sparse-retrieval", "rank": 1, "score": sparse_score},
            {"id": "splade-retrieval", "rank": 1, "score": splade_score},
            {"id": "fusion", "score": fusion_score},
        ],
    }


def _hybrid_response(identities: list[str], dense_scores: dict[str, float]) -> dict:
    return {
        "results": [_hit_with_trace(i, dense_scores.get(i, 0.5)) for i in identities],
        "searchTrace": {
            "stages": [
                {"id": "dense-retrieval", "status": "executed"},
                {"id": "sparse-retrieval", "status": "executed"},
                {"id": "splade-retrieval", "status": "executed"},
                {"id": "fusion", "status": "executed"},
            ]
        },
    }


def _leg_response(identities: list[str]) -> dict:
    return {"results": [{"id": i, "fields": {"doc_id": i}} for i in identities]}


def _make_post_search_side_effect(hybrid_by_query: dict, leg_by_query_and_mode: dict):
    def _side_effect(base_url, query, limit=10, mode="hybrid"):
        del base_url, limit  # unused by the fixture routing
        if query == HELP_DOC_PROBE_QUERY:
            return _help_docs_response()
        if mode == "hybrid":
            return hybrid_by_query[query]
        return leg_by_query_and_mode[(query, mode)]

    return _side_effect


class GenerateFormatV2Tests(unittest.TestCase):
    """Baseline generation produces the format-v2 shape: formatVersion 2,
    per-identity legScores (full float precision), legTop10 for the three
    additional retrieval-leg-only modes, and the calibration block --
    without disturbing the existing preconditions (fingerprint/compat/
    help-docs/10-hit-minimum)."""

    def test_v2_baseline_contains_leg_scores_top10_and_calibration(self):
        dense_score = 0.57117754
        ten_ids = [f"d{i}.txt" for i in range(1, 11)]
        hybrid_response = _hybrid_response(ten_ids, {"d1.txt": dense_score})

        queries_doc = {"queries": [{"id": "q01", "query": "test query one", "kind": "keyword"}]}

        side_effect = _make_post_search_side_effect(
            hybrid_by_query={"test query one": hybrid_response},
            leg_by_query_and_mode={
                ("test query one", "vector"): _leg_response(ten_ids),
                ("test query one", "text"): _leg_response(ten_ids),
                ("test query one", "splade"): _leg_response(ten_ids),
            },
        )

        with patch("gen_golden_parity.get_knowledge_status", return_value=_knowledge_status()), patch(
            "gen_golden_parity.post_search", side_effect=side_effect
        ):
            baseline = generate("http://127.0.0.1:1", queries_doc, "scifact", SCRIPT_DIR)

        # formatVersion
        self.assertEqual(baseline["formatVersion"], 2)
        self.assertEqual(baseline["formatVersion"], BASELINE_FORMAT_VERSION)

        # legScores: present for each expected identity, dense score verbatim
        query_entry = baseline["queries"][0]
        self.assertEqual(query_entry["expectedTop10"], ten_ids)
        self.assertIn("d1.txt", query_entry["legScores"])
        self.assertEqual(query_entry["legScores"]["d1.txt"]["dense"], dense_score)
        for identity in ten_ids:
            self.assertIn(identity, query_entry["legScores"])
            self.assertIn("sparse", query_entry["legScores"][identity])
            self.assertIn("splade", query_entry["legScores"][identity])
            self.assertIn("fusion", query_entry["legScores"][identity])

        # legTop10: all three modes present
        self.assertEqual(set(query_entry["legTop10"].keys()), set(LEG_MODES))
        self.assertEqual(query_entry["legTop10"]["vector"], ten_ids)
        self.assertEqual(query_entry["legTop10"]["text"], ten_ids)
        self.assertEqual(query_entry["legTop10"]["splade"], ten_ids)

        # calibration block, verbatim
        self.assertEqual(
            baseline["calibration"],
            {
                "population": "same-machine-dev-rebuilds",
                "axesSampled": ["hnsw-rebuild-noise"],
                "axesHeldConstant": [
                    "gpu+driver",
                    "embedding-inference-env",
                    "index-insertion-environment",
                ],
                "n": 3,
                "denseScoreEnvelopeAbs": 2.0e-4,
                "source": (
                    "tempdoc 734 calibration (n=3 dev rebuilds) + tempdoc 750 derisk "
                    "round5-vs-round6 dense-score deltas <= 1.8e-4"
                ),
                "note": (
                    "dense-score envelope measured on the sandbox population (round5 vs round6, "
                    "fingerprint-identical weights); overlap floors were sampled ONLY on the "
                    "same-machine population - applying them cross-environment is exactly what "
                    "this block exists to make visible"
                ),
            },
        )

        # Full JSON round-trip: the dense score must survive serialization
        # bit-for-bit, since the whole point of legScores is full float
        # precision, not a rounded/truncated summary.
        round_tripped = json.loads(json.dumps(baseline))
        self.assertEqual(round_tripped["queries"][0]["legScores"]["d1.txt"]["dense"], dense_score)

    def test_leg_returning_fewer_than_ten_hits_does_not_fail(self):
        ten_ids = [f"d{i}.txt" for i in range(1, 11)]
        short_ids = ["e1.txt", "e2.txt", "e3.txt"]
        hybrid_response = _hybrid_response(ten_ids, {})

        queries_doc = {"queries": [{"id": "q01", "query": "test query one", "kind": "keyword"}]}

        side_effect = _make_post_search_side_effect(
            hybrid_by_query={"test query one": hybrid_response},
            leg_by_query_and_mode={
                ("test query one", "vector"): _leg_response(ten_ids),
                ("test query one", "text"): _leg_response(short_ids),
                ("test query one", "splade"): _leg_response(ten_ids),
            },
        )

        with patch("gen_golden_parity.get_knowledge_status", return_value=_knowledge_status()), patch(
            "gen_golden_parity.post_search", side_effect=side_effect
        ):
            # Must not raise SystemExit: only the hybrid <10 check is
            # fail-closed, a leg is not.
            baseline = generate("http://127.0.0.1:1", queries_doc, "scifact", SCRIPT_DIR)

        self.assertEqual(baseline["queries"][0]["legTop10"]["text"], short_ids)
        self.assertEqual(len(baseline["queries"][0]["legTop10"]["text"]), 3)
        self.assertEqual(len(baseline["queries"][0]["legTop10"]["vector"]), 10)


# -----------------------------------------------------------------------------
# golden_common identity extraction parity (tempdoc 750 Part A) -- ported from
# the pre-existing check_golden_parity.py semantics (parent_doc_id -> doc_id
# -> hit.id fallback chain, basename normalization) to prove golden_common's
# copy behaves identically.
# -----------------------------------------------------------------------------


class GoldenCommonIdentityExtractionTests(unittest.TestCase):
    def test_prefers_parent_doc_id(self):
        hit = {"id": "chunk-1", "fields": {"parent_doc_id": "1234.txt", "doc_id": "chunk-1"}}
        self.assertEqual(extract_doc_identity(hit), "1234.txt")

    def test_falls_back_to_doc_id(self):
        hit = {"id": "whole-doc-1", "fields": {"doc_id": "5678.txt"}}
        self.assertEqual(extract_doc_identity(hit), "5678.txt")

    def test_falls_back_to_hit_id_when_fields_absent(self):
        hit = {"id": "9999.txt"}
        self.assertEqual(extract_doc_identity(hit), "9999.txt")

    def test_normalizes_directory_prefix_from_field(self):
        hit = {"id": "chunk-2", "fields": {"parent_doc_id": r"D:\sandbox\scifact\42.txt"}}
        self.assertEqual(extract_doc_identity(hit), "42.txt")

    def test_returns_none_when_nothing_resolvable(self):
        self.assertIsNone(extract_doc_identity({"score": 1.0}))

    def test_normalize_identity_strips_forward_slash_prefix(self):
        self.assertEqual(normalize_identity("scifact/corpus/1234.txt"), "1234.txt")

    def test_normalize_identity_strips_backslash_prefix(self):
        self.assertEqual(normalize_identity(r"C:\host\scifact\1234.txt"), "1234.txt")


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Self-tests for check_golden_parity.py's core logic against synthetic
fixtures — no corpus, no dev stack, no Sandbox available in this environment.

Run: python scripts/sandbox/test_golden_parity.py
"""

from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from check_golden_parity import (  # noqa: E402
    KNOWLEDGE_STATUS_EVIDENCE_FILENAME,
    GoldenQuery,
    check_corpus_sanity,
    check_dense_leg,
    check_model_identity,
    evaluate_all,
    evaluate_query,
    extract_doc_identity,
    extract_top_identities,
    normalize_identity,
)


def _hit(identity: str) -> dict:
    return {"id": identity, "score": 1.0, "fields": {"doc_id": identity}}


def _response(identities: list[str]) -> dict:
    return {"results": [_hit(i) for i in identities]}


class NormalizeIdentityTests(unittest.TestCase):
    def test_strips_forward_slash_prefix(self):
        self.assertEqual(normalize_identity("scifact/corpus/1234.txt"), "1234.txt")

    def test_strips_backslash_prefix(self):
        self.assertEqual(normalize_identity(r"C:\host\scifact\1234.txt"), "1234.txt")

    def test_bare_basename_unchanged(self):
        self.assertEqual(normalize_identity("1234.txt"), "1234.txt")


class ExtractDocIdentityTests(unittest.TestCase):
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


class ExtractTopIdentitiesTests(unittest.TestCase):
    def test_orders_and_limits(self):
        response = _response([f"d{i}.txt" for i in range(1, 15)])
        top = extract_top_identities(response, limit=10)
        self.assertEqual(top, [f"d{i}.txt" for i in range(1, 11)])

    def test_skips_unresolvable_hits_without_padding(self):
        response = {"results": [_hit("a.txt"), {"score": 1.0}, _hit("b.txt")]}
        self.assertEqual(extract_top_identities(response, limit=10), ["a.txt", "b.txt"])


class EvaluateQueryTests(unittest.TestCase):
    """Core tolerance-rule fixtures required by the harness spec:
    full match pass, 6/10-overlap fail, golden-#1-at-rank-4 fail, and
    basename-normalization-across-roots pass."""

    def _golden(self, expected: list[str]) -> GoldenQuery:
        return GoldenQuery(id="qX", query="test query", kind="keyword", expected_top10=expected)

    def test_full_match_passes(self):
        expected = [f"d{i}.txt" for i in range(1, 11)]
        captured = list(expected)  # identical order
        verdict = evaluate_query(self._golden(expected), captured)
        self.assertTrue(verdict.passed, verdict.reason)
        self.assertEqual(verdict.overlap, 10)

    def test_six_of_ten_overlap_fails(self):
        expected = [f"d{i}.txt" for i in range(1, 11)]
        # 6 shared identities (d1..d6), 4 replaced with never-seen ids.
        captured = [f"d{i}.txt" for i in range(1, 7)] + [f"x{i}.txt" for i in range(1, 5)]
        verdict = evaluate_query(self._golden(expected), captured)
        self.assertFalse(verdict.passed, verdict.reason)
        self.assertEqual(verdict.overlap, 6)
        self.assertIn("FAIL", verdict.reason)

    def test_golden_first_at_rank_four_fails_despite_high_overlap(self):
        expected = [f"d{i}.txt" for i in range(1, 11)]
        # Full 10/10 overlap (same set), but the golden #1 (d1.txt) is pushed
        # to rank 4 (index 3) in the captured ordering — outside top-3.
        captured = ["d2.txt", "d3.txt", "d4.txt", "d1.txt", "d5.txt", "d6.txt", "d7.txt", "d8.txt", "d9.txt", "d10.txt"]
        verdict = evaluate_query(self._golden(expected), captured)
        self.assertFalse(verdict.passed, verdict.reason)
        self.assertEqual(verdict.overlap, 10)
        self.assertIn("NOT found", verdict.reason)

    def test_basename_normalization_different_roots_same_basenames_passes(self):
        # Golden was generated on the dev host with one path root; the
        # sandbox-captured response uses a completely different mapped-folder
        # root. Both normalize to the same basenames, so parity holds.
        expected = [normalize_identity(f"D:\\dev-host\\scifact\\d{i}.txt") for i in range(1, 11)]
        captured_response = _response(
            [f"C:\\Users\\WDAGUtilityAccount\\Desktop\\JustSearchTest\\scifact\\d{i}.txt" for i in range(1, 11)]
        )
        captured = extract_top_identities(captured_response, limit=10)
        verdict = evaluate_query(self._golden(expected), captured)
        self.assertTrue(verdict.passed, verdict.reason)
        self.assertEqual(verdict.overlap, 10)

    def test_empty_expected_top10_fails(self):
        verdict = evaluate_query(self._golden([]), ["a.txt"])
        self.assertFalse(verdict.passed)


class EvaluateAllMissingCaptureTests(unittest.TestCase):
    def test_missing_capture_file_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = Path(tmp) / "evidence"
            (evidence_dir / "golden").mkdir(parents=True)
            # Only q01's capture is present; q02's is missing entirely.
            (evidence_dir / "golden" / "q01.json").write_text(
                json.dumps(_response([f"d{i}.txt" for i in range(1, 11)])), encoding="utf-8"
            )
            golden = {
                "queries": [
                    {"id": "q01", "query": "a", "kind": "keyword", "expectedTop10": [f"d{i}.txt" for i in range(1, 11)]},
                    {"id": "q02", "query": "b", "kind": "keyword", "expectedTop10": [f"e{i}.txt" for i in range(1, 11)]},
                ]
            }
            verdicts = evaluate_all(golden, str(evidence_dir))
            by_id = {v.query.id: v for v in verdicts}
            self.assertTrue(by_id["q01"].passed, by_id["q01"].reason)
            self.assertFalse(by_id["q02"].passed)
            self.assertIn("missing or unreadable capture file", by_id["q02"].reason)

    def test_unreadable_capture_file_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = Path(tmp) / "evidence"
            (evidence_dir / "golden").mkdir(parents=True)
            (evidence_dir / "golden" / "q01.json").write_text("{not valid json", encoding="utf-8")
            golden = {
                "queries": [
                    {"id": "q01", "query": "a", "kind": "keyword", "expectedTop10": ["d1.txt"]},
                ]
            }
            verdicts = evaluate_all(golden, str(evidence_dir))
            self.assertFalse(verdicts[0].passed)


class PreconditionTestsBase(unittest.TestCase):
    """Shared fixture builders for the model-identity / corpus / dense-leg
    preconditions (2026-07-14 golden-parity hardening)."""

    def _make_evidence_dir(self, tmp: str) -> Path:
        evidence_dir = Path(tmp) / "evidence"
        (evidence_dir / "golden").mkdir(parents=True)
        return evidence_dir

    def _write_knowledge_status(self, evidence_dir: Path, **fields) -> None:
        (evidence_dir / KNOWLEDGE_STATUS_EVIDENCE_FILENAME).write_text(
            json.dumps(fields), encoding="utf-8"
        )

    def _write_capture(self, evidence_dir: Path, query_id: str, response: dict) -> None:
        (evidence_dir / "golden" / f"{query_id}.json").write_text(
            json.dumps(response), encoding="utf-8"
        )

    def _baseline(self, **overrides) -> dict:
        base = {
            "embeddingFingerprint": "fp-abc123",
            "indexedDocuments": 100,
            "queries": [
                {"id": "q01", "query": "a", "kind": "keyword", "expectedTop10": ["d1.txt"]},
            ],
        }
        base.update(overrides)
        return base


class ModelIdentityPreconditionTests(PreconditionTestsBase):
    def test_matching_fingerprint_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            self._write_knowledge_status(evidence_dir, embeddingFingerprintCurrent="fp-abc123")
            result = check_model_identity(self._baseline(), str(evidence_dir))
            self.assertIsNone(result)

    def test_mismatched_fingerprint_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            self._write_knowledge_status(evidence_dir, embeddingFingerprintCurrent="fp-DIFFERENT")
            result = check_model_identity(self._baseline(), str(evidence_dir))
            self.assertIsNotNone(result)
            self.assertIn("model identity differs", result)
            self.assertIn("fp-abc123", result)
            self.assertIn("fp-DIFFERENT", result)

    def test_missing_fingerprint_in_round_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            self._write_knowledge_status(evidence_dir, embeddingFingerprintCurrent="")
            result = check_model_identity(self._baseline(), str(evidence_dir))
            self.assertIsNotNone(result)
            self.assertIn("no embedding model / fingerprint", result)

    def test_missing_evidence_file_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            # No api-api-knowledge-status.json written at all.
            result = check_model_identity(self._baseline(), str(evidence_dir))
            self.assertIsNotNone(result)
            self.assertIn("no embedding model / fingerprint", result)

    def test_legacy_baseline_without_fingerprint_warns_and_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            legacy_baseline = self._baseline()
            del legacy_baseline["embeddingFingerprint"]
            buf = io.StringIO()
            with redirect_stderr(buf):
                result = check_model_identity(legacy_baseline, str(evidence_dir))
            self.assertIsNone(result)
            self.assertIn("WARNING", buf.getvalue())
            self.assertIn("embeddingFingerprint", buf.getvalue())


class CorpusSanityPreconditionTests(PreconditionTestsBase):
    def test_matching_corpus_size_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            self._write_knowledge_status(evidence_dir, indexedDocuments=100)
            result = check_corpus_sanity(self._baseline(), str(evidence_dir))
            self.assertIsNone(result)

    def test_corpus_too_small_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            # 5 docs vs a 100-doc baseline (< 50% ratio floor).
            self._write_knowledge_status(evidence_dir, indexedDocuments=5)
            result = check_corpus_sanity(self._baseline(), str(evidence_dir))
            self.assertIsNotNone(result)
            self.assertIn("round indexed 5 docs vs baseline's 100", result)

    def test_corpus_at_exact_ratio_floor_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            self._write_knowledge_status(evidence_dir, indexedDocuments=50)
            result = check_corpus_sanity(self._baseline(indexedDocuments=100), str(evidence_dir))
            self.assertIsNone(result)

    def test_missing_evidence_file_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            result = check_corpus_sanity(self._baseline(), str(evidence_dir))
            self.assertIsNotNone(result)
            self.assertIn("not staged/ingested", result)

    def test_legacy_baseline_without_indexed_documents_warns_and_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            legacy_baseline = self._baseline()
            del legacy_baseline["indexedDocuments"]
            buf = io.StringIO()
            with redirect_stderr(buf):
                result = check_corpus_sanity(legacy_baseline, str(evidence_dir))
            self.assertIsNone(result)
            self.assertIn("WARNING", buf.getvalue())
            self.assertIn("indexedDocuments", buf.getvalue())


class DenseLegPreconditionTests(PreconditionTestsBase):
    def _stage(self, stage_id: str, status: str, reason: str = "") -> dict:
        return {"id": stage_id, "status": status, "reason": reason}

    def test_dense_retrieval_executed_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            response = _response(["d1.txt"])
            response["searchTrace"] = {
                "stages": [self._stage("sparse-retrieval", "executed"), self._stage("dense-retrieval", "executed")]
            }
            self._write_capture(evidence_dir, "q01", response)
            result = check_dense_leg(self._baseline(), str(evidence_dir))
            self.assertIsNone(result)

    def test_dense_retrieval_skipped_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            response = _response(["d1.txt"])
            response["searchTrace"] = {
                "stages": [
                    self._stage("sparse-retrieval", "executed"),
                    self._stage("dense-retrieval", "skipped", reason="NO_EMBEDDING_MODEL"),
                ]
            }
            self._write_capture(evidence_dir, "q01", response)
            result = check_dense_leg(self._baseline(), str(evidence_dir))
            self.assertIsNotNone(result)
            self.assertIn("dense retrieval was skipped", result)
            self.assertIn("NO_EMBEDDING_MODEL", result)
            self.assertIn("q01", result)

    def test_missing_search_trace_does_not_crash_and_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            self._write_capture(evidence_dir, "q01", _response(["d1.txt"]))  # no searchTrace key
            result = check_dense_leg(self._baseline(), str(evidence_dir))
            self.assertIsNone(result)

    def test_missing_capture_is_not_this_preconditions_concern(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            # No golden/q01.json written at all — evaluate_all's fail-closed
            # missing-capture path is responsible for reporting this, not
            # check_dense_leg.
            result = check_dense_leg(self._baseline(), str(evidence_dir))
            self.assertIsNone(result)


class BackwardCompatLegacyBaselineTests(PreconditionTestsBase):
    """A baseline generated before provenance tracking (no embeddingFingerprint
    / indexedDocuments) must still run the full tolerance comparison — the
    new preconditions warn and skip rather than crash."""

    def test_legacy_baseline_runs_tolerance_comparison_with_warnings(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            expected = [f"d{i}.txt" for i in range(1, 11)]
            self._write_capture(evidence_dir, "q01", _response(expected))

            legacy_golden = {
                "queries": [
                    {"id": "q01", "query": "a", "kind": "keyword", "expectedTop10": expected},
                ]
            }

            buf = io.StringIO()
            with redirect_stderr(buf):
                identity_result = check_model_identity(legacy_golden, str(evidence_dir))
                corpus_result = check_corpus_sanity(legacy_golden, str(evidence_dir))
                dense_result = check_dense_leg(legacy_golden, str(evidence_dir))

            self.assertIsNone(identity_result)
            self.assertIsNone(corpus_result)
            self.assertIsNone(dense_result)
            self.assertEqual(buf.getvalue().count("WARNING"), 2)

            verdicts = evaluate_all(legacy_golden, str(evidence_dir))
            self.assertTrue(verdicts[0].passed, verdicts[0].reason)


if __name__ == "__main__":
    unittest.main()

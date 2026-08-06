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
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from check_golden_parity import (  # noqa: E402
    KNOWLEDGE_STATUS_EVIDENCE_FILENAME,
    OVERLAP_DESCRIPTIVE_FLOOR,
    BASELINE_FORMAT_VERSION,
    PARITY_BASELINE_INCOMPLETE,
    PARITY_CAPTURE_MISSING,
    PARITY_CORPUS_MISMATCH,
    PARITY_DENSE_LEG_SKIPPED,
    PARITY_EMBEDDING_VARIANCE,
    PARITY_FIRST_HIT_MISS,
    PARITY_LEG_DIVERGENCE,
    PARITY_MODEL_MISMATCH,
    PARITY_OVERLAP_MISS,
    PARITY_UNCALIBRATED_POPULATION,
    GoldenQuery,
    check_corpus_sanity,
    check_dense_leg,
    check_model_identity,
    compute_dense_score_signal,
    compute_leg_attribution,
    evaluate_all,
    evaluate_query,
    extract_doc_identity,
    extract_top_identities,
    format_leg_attribution_line,
    load_capture,
    load_golden,
    main,
    normalize_identity,
    print_report,
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
    """Core tolerance-rule fixtures. Since the 2026-07-30 demotion the rule
    has two tiers: BLOCKING is golden-#1-in-captured-top-3; overlap@10 is
    computed and reported but descriptive. Fixtures: full match pass,
    6/10-overlap reported-not-blocking, golden-#1-at-rank-4 blocks, and
    basename-normalization-across-roots pass."""

    def _golden(self, expected: list[str]) -> GoldenQuery:
        return GoldenQuery(id="qX", query="test query", kind="keyword", expected_top10=expected)

    def test_full_match_passes(self):
        expected = [f"d{i}.txt" for i in range(1, 11)]
        captured = list(expected)  # identical order
        verdict = evaluate_query(self._golden(expected), captured)
        self.assertTrue(verdict.passed, verdict.reason)
        self.assertEqual(verdict.overlap, 10)
        self.assertTrue(verdict.overlap_ok)
        self.assertIsNone(verdict.blocking_code)

    def test_six_of_ten_overlap_is_reported_but_does_not_block(self):
        """The 2026-07-30 demotion, at the unit level: a sub-floor overlap is
        still COMPUTED, still surfaced in the reason with its own typed code —
        it just no longer makes the verdict block."""
        expected = [f"d{i}.txt" for i in range(1, 11)]
        # 6 shared identities (d1..d6), 4 replaced with never-seen ids. The
        # golden #1 (d1.txt) is still at rank 1, so the blocking assertion holds.
        captured = [f"d{i}.txt" for i in range(1, 7)] + [f"x{i}.txt" for i in range(1, 5)]
        verdict = evaluate_query(self._golden(expected), captured)
        self.assertTrue(verdict.passed, verdict.reason)
        self.assertIsNone(verdict.blocking_code)
        # Descriptive half: computed, below floor, and named in the reason.
        self.assertEqual(verdict.overlap, 6)
        self.assertFalse(verdict.overlap_ok)
        self.assertIn(f"[{PARITY_OVERLAP_MISS}]", verdict.reason)
        self.assertIn("does not block", verdict.reason)

    def test_golden_first_at_rank_four_blocks_despite_high_overlap(self):
        expected = [f"d{i}.txt" for i in range(1, 11)]
        # Full 10/10 overlap (same set), but the golden #1 (d1.txt) is pushed
        # to rank 4 (index 3) in the captured ordering — outside top-3.
        captured = ["d2.txt", "d3.txt", "d4.txt", "d1.txt", "d5.txt", "d6.txt", "d7.txt", "d8.txt", "d9.txt", "d10.txt"]
        verdict = evaluate_query(self._golden(expected), captured)
        self.assertFalse(verdict.passed, verdict.reason)
        self.assertEqual(verdict.blocking_code, PARITY_FIRST_HIT_MISS)
        # Overlap is a perfect 10/10 here, so this can only be blocking
        # because of the golden-#1 assertion — the right reason, not a
        # coincidence of the fixture.
        self.assertEqual(verdict.overlap, 10)
        self.assertTrue(verdict.overlap_ok)
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
        self.assertEqual(verdict.blocking_code, PARITY_BASELINE_INCOMPLETE)


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
            self.assertEqual(by_id["q02"].blocking_code, PARITY_CAPTURE_MISSING)
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


class Utf8BomEvidenceTests(PreconditionTestsBase):
    """Regression test for the BOM false-block bug (2026-07-14 live-evidence run):
    collect-evidence.ps1 (PowerShell) writes UTF-8 WITH a BOM via
    `Out-File -Encoding utf8` (Windows PowerShell 5.1's documented behavior). A
    plain `open(path, encoding="utf-8")` read fails at byte 0 on that BOM
    (json.JSONDecodeError: Expecting value: line 1 column 1), and the checker's
    read-failure paths silently turned that into a false "no fingerprint" /
    "no captured response" BLOCKING verdict against a round where the
    fingerprint and dense retrieval were genuinely present. Every evidence read
    in check_golden_parity.py (load_golden, load_capture, load_evidence_json)
    must be BOM-tolerant. These fixtures are written by hand with the raw BOM
    bytes precisely because the self-tests' existing fixtures are all written by
    Python's `Path.write_text` (no BOM) — a "green fixtures, broken on real
    (PowerShell-written) data" gap this class closes."""

    def _write_bom_json(self, path: Path, payload: dict) -> None:
        path.write_bytes(b"\xef\xbb\xbf" + json.dumps(payload).encode("utf-8"))

    def test_bom_prefixed_baseline_parses(self):
        with tempfile.TemporaryDirectory() as tmp:
            baseline_path = Path(tmp) / "golden-parity.json"
            self._write_bom_json(baseline_path, self._baseline())
            loaded = load_golden(str(baseline_path))
            self.assertEqual(loaded["embeddingFingerprint"], "fp-abc123")

    def test_bom_prefixed_capture_parses(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            response = _response(["d1.txt"])
            self._write_bom_json(evidence_dir / "golden" / "q01.json", response)
            loaded = load_capture(str(evidence_dir), "q01")
            self.assertIsNotNone(loaded)
            self.assertEqual(loaded["results"][0]["id"], "d1.txt")

    def test_bom_prefixed_knowledge_status_does_not_false_block_preconditions(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            self._write_bom_json(
                evidence_dir / KNOWLEDGE_STATUS_EVIDENCE_FILENAME,
                {"embeddingFingerprintCurrent": "fp-abc123", "indexedDocuments": 100},
            )
            identity_result = check_model_identity(self._baseline(), str(evidence_dir))
            self.assertIsNone(identity_result, identity_result)
            corpus_result = check_corpus_sanity(self._baseline(), str(evidence_dir))
            self.assertIsNone(corpus_result, corpus_result)

    def test_end_to_end_bom_round_reaches_normal_tolerance_verdict(self):
        """Full-fidelity regression: baseline + knowledge-status + golden capture
        all BOM-prefixed, as a real PowerShell-collected round looks. The checker
        must get PAST the identity/corpus/dense preconditions to the normal
        per-query tolerance table -- never the false "no fingerprint" block."""
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            expected = [f"d{i}.txt" for i in range(1, 11)]

            self._write_bom_json(
                evidence_dir / KNOWLEDGE_STATUS_EVIDENCE_FILENAME,
                {"embeddingFingerprintCurrent": "fp-abc123", "indexedDocuments": 100},
            )
            response = _response(expected)
            response["searchTrace"] = {
                "stages": [{"id": "dense-retrieval", "status": "executed", "reason": ""}]
            }
            self._write_bom_json(evidence_dir / "golden" / "q01.json", response)

            baseline = self._baseline(
                queries=[{"id": "q01", "query": "a", "kind": "keyword", "expectedTop10": expected}]
            )
            baseline_path = Path(tmp) / "golden-parity.json"
            self._write_bom_json(baseline_path, baseline)
            loaded_baseline = load_golden(str(baseline_path))

            identity_result = check_model_identity(loaded_baseline, str(evidence_dir))
            self.assertIsNone(identity_result, identity_result)
            corpus_result = check_corpus_sanity(loaded_baseline, str(evidence_dir))
            self.assertIsNone(corpus_result, corpus_result)
            dense_result = check_dense_leg(loaded_baseline, str(evidence_dir))
            self.assertIsNone(dense_result, dense_result)

            verdicts = evaluate_all(loaded_baseline, str(evidence_dir))
            self.assertTrue(verdicts[0].passed, verdicts[0].reason)


class DenseScoreSignalTests(unittest.TestCase):
    """Signal 1 (tempdoc 750 Part A1): the score-identity probe compares
    dense-leg scores on the (query, doc) pairs shared by baseline and
    capture against the baseline's calibrated envelope."""

    def test_planted_drift_flags_embedding_variance(self):
        golden_query = GoldenQuery(
            id="q01",
            query="a",
            kind="keyword",
            expected_top10=["d1.txt"],
            leg_scores={"d1.txt": {"dense": 0.50}},
        )
        line = compute_dense_score_signal(golden_query, {"d1.txt": {"dense": 0.51}}, envelope_abs=2.0e-4)
        self.assertIn(f"[{PARITY_EMBEDDING_VARIANCE}]", line)
        self.assertIn("1.00e-02", line)
        self.assertIn("1 shared pairs", line)

    def test_identical_scores_disjoint_tails_reports_consistent(self):
        golden_query = GoldenQuery(
            id="q01",
            query="a",
            kind="keyword",
            expected_top10=["d1.txt", "d2.txt", "tailA.txt"],
            leg_scores={"d1.txt": {"dense": 0.50}, "d2.txt": {"dense": 0.42}},
        )
        captured_leg_scores = {
            "d1.txt": {"dense": 0.50},
            "d2.txt": {"dense": 0.42},
            "tailB.txt": {"dense": 0.10},  # disjoint tail identity, not in baseline
        }
        line = compute_dense_score_signal(golden_query, captured_leg_scores, envelope_abs=2.0e-4)
        self.assertNotIn(PARITY_EMBEDDING_VARIANCE, line)
        self.assertIn("scores consistent", line)
        self.assertIn("selection-side", line)
        self.assertIn("2 shared pairs", line)

    def test_zero_shared_pairs_says_so(self):
        golden_query = GoldenQuery(id="q01", query="a", kind="keyword", expected_top10=["d1.txt"], leg_scores={})
        line = compute_dense_score_signal(golden_query, {"d9.txt": {"dense": 0.1}}, envelope_abs=2.0e-4)
        self.assertIn("no shared score-bearing pairs", line)


class LegAttributionTests(unittest.TestCase):
    """Signal 2 (tempdoc 750 Part A2): per-leg overlap attribution."""

    def _write_leg_capture(self, evidence_dir: Path, query_id: str, mode: str, identities: list[str]) -> None:
        (evidence_dir / "golden" / f"{query_id}.{mode}.json").write_text(
            json.dumps(_response(identities)), encoding="utf-8"
        )

    def test_vector_leg_diverges_names_only_dense(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = Path(tmp) / "evidence"
            (evidence_dir / "golden").mkdir(parents=True)
            baseline_top10 = [f"d{i}.txt" for i in range(1, 11)]

            # vector leg (displayed as "dense"): only 4/10 overlap.
            self._write_leg_capture(
                evidence_dir,
                "q01",
                "vector",
                [f"d{i}.txt" for i in range(1, 5)] + [f"x{i}.txt" for i in range(1, 7)],
            )
            # text and splade legs: full 10/10 overlap.
            self._write_leg_capture(evidence_dir, "q01", "text", baseline_top10)
            self._write_leg_capture(evidence_dir, "q01", "splade", baseline_top10)

            golden_query = GoldenQuery(
                id="q01",
                query="a",
                kind="keyword",
                expected_top10=baseline_top10,
                leg_top10={"vector": baseline_top10, "text": baseline_top10, "splade": baseline_top10},
            )
            overlaps = compute_leg_attribution(golden_query, str(evidence_dir))
            self.assertEqual(overlaps, {"dense": 4, "text": 10, "splade": 10})

            line = format_leg_attribution_line(overlaps)
            self.assertIn(f"[{PARITY_LEG_DIVERGENCE}: dense]", line)
            self.assertNotIn(f"{PARITY_LEG_DIVERGENCE}: dense, text", line)
            self.assertNotIn(f"{PARITY_LEG_DIVERGENCE}: dense, splade", line)

    def test_missing_leg_captures_reports_unavailable(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = Path(tmp) / "evidence"
            (evidence_dir / "golden").mkdir(parents=True)
            golden_query = GoldenQuery(
                id="q01",
                query="a",
                kind="keyword",
                expected_top10=["d1.txt"],
                leg_top10={"vector": ["d1.txt"], "text": ["d1.txt"], "splade": ["d1.txt"]},
            )
            overlaps = compute_leg_attribution(golden_query, str(evidence_dir))
            self.assertIsNone(overlaps)
            self.assertEqual(
                format_leg_attribution_line(overlaps),
                "leg attribution unavailable (no per-leg captures in this evidence set)",
            )


class V1BaselineNoticeTests(PreconditionTestsBase):
    """A v1 baseline (no formatVersion, or formatVersion 1) runs the legacy
    comparison exactly as before, plus one PARITY_UNCALIBRATED_POPULATION
    notice line naming that the new signals aren't available for it."""

    def test_v1_baseline_shows_legacy_report_plus_notice(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            expected = [f"d{i}.txt" for i in range(1, 11)]
            self._write_capture(evidence_dir, "q01", _response(expected))

            golden = self._baseline(
                queries=[{"id": "q01", "query": "a", "kind": "keyword", "expectedTop10": expected}],
            )
            self.assertNotIn("formatVersion", golden)

            verdicts = evaluate_all(golden, str(evidence_dir))
            buf = io.StringIO()
            with redirect_stdout(buf):
                all_passed = print_report(golden, str(evidence_dir), verdicts)
            output = buf.getvalue()

            self.assertTrue(all_passed)
            self.assertIn(f"[{PARITY_UNCALIBRATED_POPULATION}]", output)
            self.assertIn("v1 baseline", output)
            self.assertNotIn("dense-score identity:", output)
            self.assertNotIn("leg attribution unavailable", output)

    def test_explicit_format_version_1_is_also_treated_as_legacy(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            expected = ["d1.txt"]
            self._write_capture(evidence_dir, "q01", _response(expected))
            golden = self._baseline(
                formatVersion=1,
                queries=[{"id": "q01", "query": "a", "kind": "keyword", "expectedTop10": expected}],
            )
            verdicts = evaluate_all(golden, str(evidence_dir))
            buf = io.StringIO()
            with redirect_stdout(buf):
                print_report(golden, str(evidence_dir), verdicts)
            self.assertIn(f"[{PARITY_UNCALIBRATED_POPULATION}]", buf.getvalue())


class PreconditionTypedCodesTests(PreconditionTestsBase):
    """Precondition failures carry their typed PARITY_* reason codes in the
    main()-level BLOCKING output (tempdoc 750 Part A4)."""

    def test_model_identity_failure_carries_typed_code(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            self._write_knowledge_status(evidence_dir, embeddingFingerprintCurrent="fp-DIFFERENT")
            golden_path = Path(tmp) / "golden-parity.json"
            golden_path.write_text(json.dumps(self._baseline()), encoding="utf-8")

            buf = io.StringIO()
            with redirect_stderr(buf):
                rc = main(["--golden", str(golden_path), "--evidence-dir", str(evidence_dir)])
            self.assertEqual(rc, 1)
            self.assertIn(f"[{PARITY_MODEL_MISMATCH}]", buf.getvalue())

    def test_corpus_sanity_failure_carries_typed_code(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            self._write_knowledge_status(evidence_dir, embeddingFingerprintCurrent="fp-abc123", indexedDocuments=5)
            golden_path = Path(tmp) / "golden-parity.json"
            golden_path.write_text(json.dumps(self._baseline()), encoding="utf-8")

            buf = io.StringIO()
            with redirect_stderr(buf):
                rc = main(["--golden", str(golden_path), "--evidence-dir", str(evidence_dir)])
            self.assertEqual(rc, 1)
            self.assertIn(f"[{PARITY_CORPUS_MISMATCH}]", buf.getvalue())

    def test_dense_leg_failure_carries_typed_code(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            self._write_knowledge_status(evidence_dir, embeddingFingerprintCurrent="fp-abc123", indexedDocuments=100)
            response = _response(["d1.txt"])
            response["searchTrace"] = {
                "stages": [{"id": "dense-retrieval", "status": "skipped", "reason": "NO_EMBEDDING_MODEL"}]
            }
            self._write_capture(evidence_dir, "q01", response)
            golden = self._baseline(
                queries=[{"id": "q01", "query": "a", "kind": "keyword", "expectedTop10": ["d1.txt"]}]
            )
            golden_path = Path(tmp) / "golden-parity.json"
            golden_path.write_text(json.dumps(golden), encoding="utf-8")

            buf = io.StringIO()
            with redirect_stderr(buf):
                rc = main(["--golden", str(golden_path), "--evidence-dir", str(evidence_dir)])
            self.assertEqual(rc, 1)
            self.assertIn(f"[{PARITY_DENSE_LEG_SKIPPED}]", buf.getvalue())


class ExitCodePolicyTests(PreconditionTestsBase):
    """main()'s exit code is governed by the BLOCKING tier only: exit 1 iff a
    fail-closed precondition fails, a capture is missing, or a golden #1 falls
    out of the captured top-3. The descriptive tier (overlap@10, dense-score
    identity, leg attribution) is report payload and never moves the exit code
    (2026-07-30 demotion; tempdoc 750 option A4)."""

    def test_passing_v2_fixture_with_variance_flag_still_exits_0(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            expected = [f"d{i}.txt" for i in range(1, 11)]
            self._write_knowledge_status(evidence_dir, embeddingFingerprintCurrent="fp-abc123", indexedDocuments=100)

            hits = [_hit("d1.txt")]
            hits[0]["trace"] = [{"id": "dense-retrieval", "score": 0.51}]
            hits += [_hit(f"d{i}.txt") for i in range(2, 11)]
            response = {
                "results": hits,
                "searchTrace": {"stages": [{"id": "dense-retrieval", "status": "executed"}]},
            }
            self._write_capture(evidence_dir, "q01", response)

            golden = self._baseline(
                formatVersion=BASELINE_FORMAT_VERSION,
                calibration={"denseScoreEnvelopeAbs": 2.0e-4},
                queries=[
                    {
                        "id": "q01",
                        "query": "a",
                        "kind": "keyword",
                        "expectedTop10": expected,
                        "legScores": {"d1.txt": {"dense": 0.50}},
                        "legTop10": {},
                    }
                ],
            )
            golden_path = Path(tmp) / "golden-parity.json"
            golden_path.write_text(json.dumps(golden), encoding="utf-8")

            buf_out, buf_err = io.StringIO(), io.StringIO()
            with redirect_stdout(buf_out), redirect_stderr(buf_err):
                rc = main(["--golden", str(golden_path), "--evidence-dir", str(evidence_dir)])
            self.assertEqual(rc, 0)
            self.assertIn(f"[{PARITY_EMBEDDING_VARIANCE}]", buf_out.getvalue())

    def _write_v2_round(self, tmp: str, evidence_dir: Path, captured: list[str], expected: list[str]) -> Path:
        """Stage a complete, precondition-clean v2 round whose only variable is
        the captured ordering, and return the baseline path."""
        self._write_knowledge_status(evidence_dir, embeddingFingerprintCurrent="fp-abc123", indexedDocuments=100)
        response = _response(captured)
        response["searchTrace"] = {"stages": [{"id": "dense-retrieval", "status": "executed"}]}
        self._write_capture(evidence_dir, "q01", response)

        golden = self._baseline(
            formatVersion=BASELINE_FORMAT_VERSION,
            calibration={
                "denseScoreEnvelopeAbs": 2.0e-4,
                "population": "same-machine-dev-rebuilds",
            },
            queries=[
                {
                    "id": "q01",
                    "query": "a",
                    "kind": "semantic",
                    "expectedTop10": expected,
                    "legScores": {},
                    "legTop10": {},
                }
            ],
        )
        golden_path = Path(tmp) / "golden-parity.json"
        golden_path.write_text(json.dumps(golden), encoding="utf-8")
        return golden_path

    def test_overlap_only_failure_is_reported_but_exits_0(self):
        """The policy change end-to-end: 6/10 overlap (below the old floor of
        7) with the golden #1 still at rank 1 exits 0, while the finding is
        still printed with its typed code, its per-query count, and the
        policy note explaining WHY it is descriptive."""
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            expected = [f"d{i}.txt" for i in range(1, 11)]
            captured = [f"d{i}.txt" for i in range(1, 7)] + [f"x{i}.txt" for i in range(1, 5)]
            golden_path = self._write_v2_round(tmp, evidence_dir, captured, expected)

            buf_out, buf_err = io.StringIO(), io.StringIO()
            with redirect_stdout(buf_out), redirect_stderr(buf_err):
                rc = main(["--golden", str(golden_path), "--evidence-dir", str(evidence_dir)])
            output = buf_out.getvalue()

            self.assertEqual(rc, 0, output)
            # Reported, not suppressed: typed code, the actual count, and the
            # prominent descriptive block.
            self.assertIn(f"[{PARITY_OVERLAP_MISS}]", output)
            self.assertIn(f"overlap 6/{10}", output)
            self.assertIn("descriptive findings (reported, NOT blocking)", output)
            self.assertIn("1 of 1 golden queries are below", output)
            # And the operator can read WHY from the log itself.
            self.assertIn("overlap@10 is DESCRIPTIVE, not blocking", output)
            self.assertIn("same-machine-dev-rebuilds", output)
            self.assertIn("UNCALIBRATED MEASUREMENT", output)
            # No blocking block was emitted.
            self.assertNotIn("BLOCKING: the following golden queries", output)

    def test_first_hit_miss_exits_1(self):
        """The remaining blocking assertion: same 10 identities, but the
        golden #1 is pushed to rank 4. Overlap is a perfect 10/10, so this can
        only block for the golden-#1 reason."""
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            expected = [f"d{i}.txt" for i in range(1, 11)]
            captured = ["d2.txt", "d3.txt", "d4.txt", "d1.txt"] + [f"d{i}.txt" for i in range(5, 11)]
            golden_path = self._write_v2_round(tmp, evidence_dir, captured, expected)

            buf_out, buf_err = io.StringIO(), io.StringIO()
            with redirect_stdout(buf_out), redirect_stderr(buf_err):
                rc = main(["--golden", str(golden_path), "--evidence-dir", str(evidence_dir)])
            output = buf_out.getvalue()

            self.assertEqual(rc, 1, output)
            self.assertIn(f"[{PARITY_FIRST_HIT_MISS}]", output)
            self.assertIn("BLOCKING: the following golden queries", output)
            # Blocked for the right reason: overlap was fine.
            self.assertIn(f"overlap 10/10 (floor >={OVERLAP_DESCRIPTIVE_FLOOR}) OK", output)
            self.assertNotIn(f"[{PARITY_OVERLAP_MISS}] 1 of 1", output)

    def test_missing_capture_still_exits_1(self):
        """Fail-closed on a missing capture survives the demotion: nothing was
        measured, so this is a blocking condition, not a descriptive one."""
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            expected = [f"d{i}.txt" for i in range(1, 11)]
            golden_path = self._write_v2_round(tmp, evidence_dir, list(expected), expected)
            (evidence_dir / "golden" / "q01.json").unlink()

            buf_out, buf_err = io.StringIO(), io.StringIO()
            with redirect_stdout(buf_out), redirect_stderr(buf_err):
                rc = main(["--golden", str(golden_path), "--evidence-dir", str(evidence_dir)])
            output = buf_out.getvalue()

            self.assertEqual(rc, 1, output)
            self.assertIn(f"[{PARITY_CAPTURE_MISSING}]", output)

    def test_clean_round_exits_0_and_says_no_overlap_findings(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            expected = [f"d{i}.txt" for i in range(1, 11)]
            golden_path = self._write_v2_round(tmp, evidence_dir, list(expected), expected)

            buf_out, buf_err = io.StringIO(), io.StringIO()
            with redirect_stdout(buf_out), redirect_stderr(buf_err):
                rc = main(["--golden", str(golden_path), "--evidence-dir", str(evidence_dir)])
            output = buf_out.getvalue()

            self.assertEqual(rc, 0, output)
            self.assertIn(f"[{PARITY_OVERLAP_MISS}] none:", output)


class PreconditionsStillBlockAfterDemotionTests(PreconditionTestsBase):
    """The demotion touched only the overlap tier. Every fail-closed
    PRECONDITION still returns exit 1 EVEN WHEN the round would otherwise be
    a clean pass on the blocking assertion — i.e. they are not reachable-only
    through an overlap failure."""

    def _clean_round(self, tmp: str, evidence_dir: Path) -> dict:
        expected = [f"d{i}.txt" for i in range(1, 11)]
        response = _response(expected)
        response["searchTrace"] = {"stages": [{"id": "dense-retrieval", "status": "executed"}]}
        self._write_capture(evidence_dir, "q01", response)
        return self._baseline(
            formatVersion=BASELINE_FORMAT_VERSION,
            queries=[{"id": "q01", "query": "a", "kind": "semantic", "expectedTop10": expected}],
        )

    def _run(self, tmp: str, evidence_dir: Path, golden: dict) -> tuple[int, str]:
        golden_path = Path(tmp) / "golden-parity.json"
        golden_path.write_text(json.dumps(golden), encoding="utf-8")
        buf_out, buf_err = io.StringIO(), io.StringIO()
        with redirect_stdout(buf_out), redirect_stderr(buf_err):
            rc = main(["--golden", str(golden_path), "--evidence-dir", str(evidence_dir)])
        return rc, buf_err.getvalue()

    def test_model_identity_still_blocks_an_otherwise_clean_round(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            golden = self._clean_round(tmp, evidence_dir)
            self._write_knowledge_status(
                evidence_dir, embeddingFingerprintCurrent="fp-DIFFERENT", indexedDocuments=100
            )
            rc, err = self._run(tmp, evidence_dir, golden)
            self.assertEqual(rc, 1)
            self.assertIn(f"[{PARITY_MODEL_MISMATCH}]", err)

    def test_corpus_sanity_still_blocks_an_otherwise_clean_round(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            golden = self._clean_round(tmp, evidence_dir)
            self._write_knowledge_status(
                evidence_dir, embeddingFingerprintCurrent="fp-abc123", indexedDocuments=5
            )
            rc, err = self._run(tmp, evidence_dir, golden)
            self.assertEqual(rc, 1)
            self.assertIn(f"[{PARITY_CORPUS_MISMATCH}]", err)

    def test_dense_leg_still_blocks_an_otherwise_clean_round(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            golden = self._clean_round(tmp, evidence_dir)
            self._write_knowledge_status(
                evidence_dir, embeddingFingerprintCurrent="fp-abc123", indexedDocuments=100
            )
            # Same otherwise-clean round, dense leg reported as skipped.
            response = _response([f"d{i}.txt" for i in range(1, 11)])
            response["searchTrace"] = {
                "stages": [{"id": "dense-retrieval", "status": "skipped", "reason": "NO_EMBEDDING_MODEL"}]
            }
            self._write_capture(evidence_dir, "q01", response)
            rc, err = self._run(tmp, evidence_dir, golden)
            self.assertEqual(rc, 1)
            self.assertIn(f"[{PARITY_DENSE_LEG_SKIPPED}]", err)


class LegAttributionOnDescriptiveFindingTests(PreconditionTestsBase):
    """A sub-floor overlap no longer blocks, so it must still receive the
    per-leg attribution line — printing attribution only for blocking
    failures is what left earlier rounds unattributable."""

    def test_descriptive_overlap_finding_gets_leg_attribution(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence_dir = self._make_evidence_dir(tmp)
            expected = [f"d{i}.txt" for i in range(1, 11)]
            captured = [f"d{i}.txt" for i in range(1, 7)] + [f"x{i}.txt" for i in range(1, 5)]
            self._write_knowledge_status(
                evidence_dir, embeddingFingerprintCurrent="fp-abc123", indexedDocuments=100
            )
            response = _response(captured)
            response["searchTrace"] = {"stages": [{"id": "dense-retrieval", "status": "executed"}]}
            self._write_capture(evidence_dir, "q01", response)
            for mode, identities in (
                ("vector", [f"d{i}.txt" for i in range(1, 5)] + [f"x{i}.txt" for i in range(1, 7)]),
                ("text", expected),
                ("splade", expected),
            ):
                (evidence_dir / "golden" / f"q01.{mode}.json").write_text(
                    json.dumps(_response(identities)), encoding="utf-8"
                )

            golden = self._baseline(
                formatVersion=BASELINE_FORMAT_VERSION,
                calibration={"denseScoreEnvelopeAbs": 2.0e-4},
                queries=[
                    {
                        "id": "q01",
                        "query": "a",
                        "kind": "semantic",
                        "expectedTop10": expected,
                        "legScores": {},
                        "legTop10": {"vector": expected, "text": expected, "splade": expected},
                    }
                ],
            )
            golden_path = Path(tmp) / "golden-parity.json"
            golden_path.write_text(json.dumps(golden), encoding="utf-8")

            buf_out, buf_err = io.StringIO(), io.StringIO()
            with redirect_stdout(buf_out), redirect_stderr(buf_err):
                rc = main(["--golden", str(golden_path), "--evidence-dir", str(evidence_dir)])
            output = buf_out.getvalue()

            self.assertEqual(rc, 0, output)
            self.assertIn(f"[{PARITY_LEG_DIVERGENCE}: dense]", output)


if __name__ == "__main__":
    unittest.main()

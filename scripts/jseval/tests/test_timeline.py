"""Tests for timeline.py — timeline recording and pipeline summary."""

from __future__ import annotations

from pathlib import Path

from jseval.timeline import (
    compute_pipeline_summary,
    format_pipeline_summary,
    format_timeline_tsv,
    snapshot_to_row,
    write_timeline_tsv,
)


class TestSnapshotToRow:
    def test_basic(self):
        snapshot = {
            "indexedDocuments": 100,
            "pendingJobsCount": 5,
            "processingJobsCount": 2,
            "embeddingCoveragePercent": 45.3,
            "spladeCoveragePercent": 12.1,
            "completedNerCount": 50,
            "pendingNerCount": 50,
            "chunkVectorCoveragePercent": 30.0,
            "memoryUsedBytes": 1024 * 1024 * 512,  # 512 MB
            "throughputDocsPerSec": 247.5,
            "enrichmentCompleted": {"embed": 500, "splade": 400, "ner": 300},
            "batchTiming": {
                "batchCount": {"embed": 10, "splade": 10, "ner": 8,
                               "fetch": 10, "write": 10, "total": 10},
                "totalMs": {"embed": 3200, "splade": 1800, "ner": 900,
                            "fetch": 500, "write": 300, "total": 6000},
            },
        }
        row = snapshot_to_row(32.5, snapshot)
        assert row["elapsed_s"] == 32.5
        assert row["indexed"] == 100
        assert row["pending"] == 7  # 5 + 2
        assert row["embed_pct"] == 45.3
        assert row["ner_done"] == 50
        assert row["heap_mb"] == 512
        assert row["enrich_embed"] == 500
        assert row["enrich_splade"] == 400
        assert row["enrich_ner"] == 300
        assert row["embed_batches"] == 10
        assert row["embed_ms"] == 3200
        assert row["splade_batches"] == 10
        assert row["splade_ms"] == 1800
        assert row["ner_batches"] == 8
        assert row["ner_ms"] == 900
        assert row["fetch_batches"] == 10
        assert row["fetch_ms"] == 500
        assert row["write_batches"] == 10
        assert row["write_ms"] == 300
        assert row["total_batches"] == 10
        assert row["total_ms"] == 6000

    def test_encoder_profiles_carried_in_row(self):
        """357: encoderProfiles from snapshot is carried as _encoder_profiles."""
        ep_data = {
            "embed": {
                "calls": 100,
                "phaseTotalUs": {"tokenize": 50000, "tensor": 2000, "ort": 800000, "extract": 10000},
                "ortMinUs": 5000, "ortMaxUs": 12000,
                "ortP50Us": 7500, "ortP95Us": 10000, "ortP99Us": 11500,
            },
        }
        snapshot = {"encoderProfiles": ep_data}
        row = snapshot_to_row(10.0, snapshot)
        assert row["_encoder_profiles"] == ep_data

    def test_encoder_profiles_absent(self):
        """357: missing encoderProfiles does not add _encoder_profiles key."""
        row = snapshot_to_row(10.0, {})
        assert "_encoder_profiles" not in row

    def test_missing_fields(self):
        row = snapshot_to_row(0.0, {})
        assert row["elapsed_s"] == 0.0
        assert row["indexed"] == 0
        assert row["embed_pct"] == 0


class TestWriteTimelineTsv:
    def test_writes_file(self, tmp_path):
        rows = [
            snapshot_to_row(0.0, {"indexedDocuments": 0}),
            snapshot_to_row(5.0, {"indexedDocuments": 100}),
        ]
        path = tmp_path / "timeline.tsv"
        write_timeline_tsv(rows, path)
        assert path.is_file()
        content = path.read_text(encoding="utf-8")
        assert "elapsed_s" in content  # header
        assert "0.0" in content
        assert "5.0" in content


class TestFormatTimelineTsv:
    def test_formats_string(self):
        rows = [snapshot_to_row(0.0, {}), snapshot_to_row(5.0, {})]
        output = format_timeline_tsv(rows)
        lines = output.strip().split("\n")
        assert len(lines) == 3  # header + 2 rows
        assert "elapsed_s" in lines[0]


class TestComputePipelineSummary:
    def test_empty(self):
        assert compute_pipeline_summary([]) == {}

    def test_basic_pipeline(self):
        rows = [
            {"elapsed_s": 0, "indexed": 0, "pending": 0, "embed_pct": 0,
             "splade_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 512, "throughput": 0},
            {"elapsed_s": 5, "indexed": 0, "pending": 100, "embed_pct": 0,
             "splade_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 512, "throughput": 50},
            {"elapsed_s": 20, "indexed": 500, "pending": 0, "embed_pct": 50,
             "splade_pct": 10, "ner_done": 100, "ner_pending": 400, "chunk_pct": 50,
             "heap_mb": 600, "throughput": 25},
            {"elapsed_s": 60, "indexed": 500, "pending": 0, "embed_pct": 100,
             "splade_pct": 100, "ner_done": 500, "ner_pending": 0, "chunk_pct": 100,
             "heap_mb": 700, "throughput": 0},
        ]
        summary = compute_pipeline_summary(rows)

        assert summary["total_elapsed_s"] == 60
        assert summary["primary_indexing"]["duration_s"] == 15  # 5s to 20s
        assert summary["primary_indexing"]["docs_indexed"] == 500
        assert summary["stages"]["embedding_100_pct_at_s"] == 60
        assert summary["stages"]["splade_100_pct_at_s"] == 60
        assert summary["stages"]["chunk_100_pct_at_s"] == 60
        assert summary["stages"]["ner_complete_at_s"] == 60
        assert summary["stages"]["ner_total_docs"] == 500

    def test_ner_total_docs_uses_max_not_first_pending_zero(self):
        """391 follow-up: ner_total_docs must be the MAX observed ner_done across
        all rows, not the FIRST row where pending==0 AND done>0.

        Observed bug pattern (6 runs of scifact): 4 runs showed ner_total_docs=7303,
        2 outlier runs showed 5500 and 6300 — but all 6 runs actually reached
        ner_done=7303 by the end of the timeline. The outliers were caused by a
        transient drain: NER backfill processed all currently-pending chunks before
        ingestion finished producing more chunks, creating a brief window with
        pending=0 at a partial ner_done value. That window was locked in as the
        reported total.

        The fix is to take max(ner_done) across the whole timeline (monotonic by
        construction, since docs only move PENDING → COMPLETED / FAILED).
        """
        # This reproduces the exact outlier pattern from run 3 of the warm triple:
        # pending hits 0 at ner_done=6300, then more chunks arrive and ner_done
        # climbs to 7303 with pending growing then draining again.
        rows = [
            {"elapsed_s": 0, "indexed": 0, "pending": 100, "embed_pct": 0,
             "splade_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 512, "throughput": 0},
            # Mid-run: first transient drain — ingestion temporarily outrun by NER.
            {"elapsed_s": 100, "indexed": 5184, "pending": 0, "embed_pct": 80,
             "splade_pct": 80, "ner_done": 6300, "ner_pending": 0, "chunk_pct": 80,
             "heap_mb": 600, "throughput": 10},
            # More chunks arrive (chunk pipeline catches up), ner_pending climbs.
            {"elapsed_s": 150, "indexed": 5184, "pending": 0, "embed_pct": 95,
             "splade_pct": 95, "ner_done": 6800, "ner_pending": 500, "chunk_pct": 95,
             "heap_mb": 650, "throughput": 5},
            # Final drain: NER catches up, pipeline is truly complete.
            {"elapsed_s": 200, "indexed": 5184, "pending": 0, "embed_pct": 100,
             "splade_pct": 100, "ner_done": 7303, "ner_pending": 0, "chunk_pct": 100,
             "heap_mb": 700, "throughput": 0},
        ]
        summary = compute_pipeline_summary(rows)

        # PRE-FIX behavior: ner_total_docs=6300 (first pending=0) — wrong.
        # POST-FIX behavior: ner_total_docs=7303 (max across all rows).
        assert summary["stages"]["ner_total_docs"] == 7303
        # ner_complete_at_s is the first row reaching the max with pending=0.
        assert summary["stages"]["ner_complete_at_s"] == 200

    def test_inference_timing_from_backend(self):
        """350/354: compute_pipeline_summary extracts per-stage inference timing."""
        rows = [
            {"elapsed_s": 0, "indexed": 0, "pending": 100, "embed_pct": 0,
             "splade_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 512, "throughput": 0,
             "embed_batches": 0, "embed_ms": 0,
             "splade_batches": 0, "splade_ms": 0,
             "ner_batches": 0, "ner_ms": 0,
             "fetch_batches": 0, "fetch_ms": 0,
             "write_batches": 0, "write_ms": 0,
             "total_batches": 0, "total_ms": 0},
            {"elapsed_s": 60, "indexed": 500, "pending": 0, "embed_pct": 100,
             "splade_pct": 100, "ner_done": 500, "ner_pending": 0, "chunk_pct": 100,
             "heap_mb": 700, "throughput": 0,
             "embed_batches": 10, "embed_ms": 3200,
             "splade_batches": 10, "splade_ms": 1800,
             "ner_batches": 8, "ner_ms": 900,
             "fetch_batches": 10, "fetch_ms": 500,
             "write_batches": 10, "write_ms": 300,
             "total_batches": 10, "total_ms": 6000},
        ]
        summary = compute_pipeline_summary(rows)
        inf = summary["inference"]
        assert inf["embedding"]["batches"] == 10
        assert inf["embedding"]["total_ms"] == 3200
        assert inf["embedding"]["avg_ms_per_batch"] == 320.0
        assert inf["splade"]["batches"] == 10
        assert inf["splade"]["total_ms"] == 1800
        assert inf["splade"]["avg_ms_per_batch"] == 180.0
        assert inf["ner"]["batches"] == 8
        assert inf["ner"]["total_ms"] == 900
        assert inf["ner"]["avg_ms_per_batch"] == 112.5

    def test_inference_timing_skips_zero_batches(self):
        """350/354: stages with no batches during the run are omitted."""
        rows = [
            {"elapsed_s": 0, "indexed": 0, "pending": 0, "embed_pct": 0,
             "splade_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "embed_batches": 5, "embed_ms": 1000,
             "splade_batches": 0, "splade_ms": 0,
             "ner_batches": 0, "ner_ms": 0,
             "fetch_batches": 5, "fetch_ms": 200,
             "write_batches": 5, "write_ms": 100,
             "total_batches": 5, "total_ms": 1500},
            {"elapsed_s": 30, "indexed": 100, "pending": 0, "embed_pct": 100,
             "splade_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "embed_batches": 15, "embed_ms": 4000,
             "splade_batches": 0, "splade_ms": 0,
             "ner_batches": 0, "ner_ms": 0,
             "fetch_batches": 15, "fetch_ms": 600,
             "write_batches": 15, "write_ms": 300,
             "total_batches": 15, "total_ms": 5500},
        ]
        summary = compute_pipeline_summary(rows)
        assert "embedding" in summary["inference"]
        assert summary["inference"]["embedding"]["batches"] == 10
        assert "splade" not in summary["inference"]
        assert "ner" not in summary["inference"]

    def test_inference_timing_absent_fields(self):
        """350: old timeline rows without timing fields produce no inference section."""
        rows = [
            {"elapsed_s": 0, "indexed": 0, "pending": 100, "embed_pct": 0,
             "splade_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0},
            {"elapsed_s": 60, "indexed": 500, "pending": 0, "embed_pct": 100,
             "splade_pct": 100, "ner_done": 500, "ner_pending": 0, "chunk_pct": 100},
        ]
        summary = compute_pipeline_summary(rows)
        assert "inference" not in summary

    def test_overhead_timing_from_backend(self):
        """354: compute_pipeline_summary extracts overhead timing."""
        rows = [
            {"elapsed_s": 0, "indexed": 0, "pending": 100, "embed_pct": 0,
             "splade_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "fetch_batches": 0, "fetch_ms": 0,
             "write_batches": 0, "write_ms": 0,
             "total_batches": 0, "total_ms": 0},
            {"elapsed_s": 60, "indexed": 500, "pending": 0, "embed_pct": 100,
             "splade_pct": 100, "ner_done": 500, "ner_pending": 0, "chunk_pct": 100,
             "fetch_batches": 10, "fetch_ms": 500,
             "write_batches": 10, "write_ms": 300,
             "total_batches": 10, "total_ms": 6000},
        ]
        summary = compute_pipeline_summary(rows)
        oh = summary["overhead"]
        assert oh["fetch"]["batches"] == 10
        assert oh["fetch"]["total_ms"] == 500
        assert oh["fetch"]["avg_ms_per_batch"] == 50.0
        assert oh["write"]["batches"] == 10
        assert oh["write"]["total_ms"] == 300
        assert oh["write"]["avg_ms_per_batch"] == 30.0
        assert oh["total"]["batches"] == 10
        assert oh["total"]["total_ms"] == 6000
        assert oh["total"]["avg_ms_per_batch"] == 600.0

    def test_overhead_timing_absent_fields(self):
        """354: old timeline rows without overhead fields produce no overhead section."""
        rows = [
            {"elapsed_s": 0, "indexed": 0, "pending": 100, "embed_pct": 0,
             "splade_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0},
            {"elapsed_s": 60, "indexed": 500, "pending": 0, "embed_pct": 100,
             "splade_pct": 100, "ner_done": 500, "ner_pending": 0, "chunk_pct": 100},
        ]
        summary = compute_pipeline_summary(rows)
        assert "overhead" not in summary

    def test_splade_churn(self):
        rows = [
            {"elapsed_s": 0, "splade_pct": 0, "indexed": 0, "pending": 0,
             "embed_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 0, "throughput": 0},
            {"elapsed_s": 10, "splade_pct": 50, "indexed": 0, "pending": 0,
             "embed_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 0, "throughput": 0},
            {"elapsed_s": 20, "splade_pct": 40, "indexed": 0, "pending": 0,
             "embed_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 0, "throughput": 0},  # drop!
            {"elapsed_s": 30, "splade_pct": 100, "indexed": 0, "pending": 0,
             "embed_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 0, "throughput": 0},
        ]
        summary = compute_pipeline_summary(rows)
        assert summary["splade_churn_drops"] == 1

    def test_splade_churn_filters_sub_tolerance_drops(self):
        # 391/E-J-N9: sub-tolerance dips (snapshot-cadence noise) must be
        # filtered. With CHURN_DROP_TOLERANCE_PP = 0.5, a 0.3 pp drop is
        # noise; a 1.0 pp drop is signal.
        rows = [
            {"elapsed_s": 0, "splade_pct": 50.0, "indexed": 0, "pending": 0,
             "embed_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 0, "throughput": 0},
            {"elapsed_s": 10, "splade_pct": 49.7, "indexed": 0, "pending": 0,  # 0.3 pp dip — noise
             "embed_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 0, "throughput": 0},
            {"elapsed_s": 20, "splade_pct": 60.0, "indexed": 0, "pending": 0,
             "embed_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 0, "throughput": 0},
            {"elapsed_s": 30, "splade_pct": 59.0, "indexed": 0, "pending": 0,  # 1.0 pp drop — signal
             "embed_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 0, "throughput": 0},
        ]
        summary = compute_pipeline_summary(rows)
        assert summary["splade_churn_drops"] == 1  # only the 1.0 pp drop counts

    def test_splade_churn_filters_all_sub_tolerance(self):
        # 391/E-J-N9: a sequence of small dips that are all sub-tolerance
        # must produce zero drops.
        rows = [
            {"elapsed_s": 0, "splade_pct": 50.0, "indexed": 0, "pending": 0,
             "embed_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 0, "throughput": 0},
            {"elapsed_s": 10, "splade_pct": 49.8, "indexed": 0, "pending": 0,  # 0.2 pp
             "embed_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 0, "throughput": 0},
            {"elapsed_s": 20, "splade_pct": 49.4, "indexed": 0, "pending": 0,  # 0.4 pp
             "embed_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 0, "throughput": 0},
            {"elapsed_s": 30, "splade_pct": 100, "indexed": 0, "pending": 0,
             "embed_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "heap_mb": 0, "throughput": 0},
        ]
        summary = compute_pipeline_summary(rows)
        # No churn drops should be reported — all dips below tolerance.
        assert "splade_churn_drops" not in summary


class TestFormatPipelineSummary:
    def test_formats(self):
        summary = {
            "total_elapsed_s": 120,
            "primary_indexing": {
                "start_s": 2, "end_s": 20, "duration_s": 18,
                "docs_indexed": 500, "docs_per_s": 27.8,
            },
            "stages": {
                "embedding_100_pct_at_s": 60,
                "splade_100_pct_at_s": 120,
            },
        }
        output = format_pipeline_summary(summary)
        assert "120s" in output
        assert "2.0 min" in output
        assert "28 docs/sec" in output

    def test_formats_inference_timing(self):
        summary = {
            "total_elapsed_s": 60,
            "inference": {
                "embedding": {"batches": 10, "total_ms": 3200, "avg_ms_per_batch": 320.0},
                "splade": {"batches": 10, "total_ms": 1800, "avg_ms_per_batch": 180.0},
            },
        }
        output = format_pipeline_summary(summary)
        assert "3200ms total" in output
        assert "320.0ms/batch" in output
        assert "1800ms total" in output

    def test_formats_overhead_timing(self):
        """354: format_pipeline_summary renders overhead section."""
        summary = {
            "total_elapsed_s": 60,
            "overhead": {
                "fetch": {"batches": 10, "total_ms": 500, "avg_ms_per_batch": 50.0},
                "write": {"batches": 10, "total_ms": 300, "avg_ms_per_batch": 30.0},
                "total": {"batches": 10, "total_ms": 6000, "avg_ms_per_batch": 600.0},
            },
        }
        output = format_pipeline_summary(summary)
        assert "Overhead timing (from backend):" in output
        assert "fetch:  500ms total" in output
        assert "50.0ms/batch" in output
        assert "write:  300ms total" in output
        assert "total:  6000ms total" in output
        assert "600.0ms/batch" in output

    def test_formats_encoder_profiles(self):
        """357: format_pipeline_summary renders encoder profiling section."""
        summary = {
            "total_elapsed_s": 60,
            "encoder_profiles": {
                "embed": {
                    "calls": 100,
                    "phaseTotalUs": {"tokenize": 5000000, "tensor": 200000, "ort": 80000000, "extract": 1000000},
                    "ortMinUs": 5000, "ortMaxUs": 12000,
                    "ortP50Us": 7500, "ortP95Us": 10000, "ortP99Us": 11500,
                },
            },
        }
        output = format_pipeline_summary(summary)
        assert "Encoder profiling (from /api/status):" in output
        assert "embed (100 calls):" in output
        assert "tokenize=50000us" in output
        assert "ort=800000us" in output
        assert "total=862000us" in output  # 50000+2000+800000+10000
        assert "p50=7500us" in output
        assert "p95=10000us" in output


class TestEncoderProfilesPipelineSummary:
    """357: encoder profiles in compute_pipeline_summary (delta-based)."""

    def test_encoder_profiles_delta(self):
        """Delta between first and last row gives run-scoped values."""
        first_ep = {
            "embed": {
                "calls": 10, "phaseTotalUs": {"tokenize": 5000, "ort": 80000},
                "ortMinUs": 5000, "ortMaxUs": 10000,
                "ortP50Us": 7000, "ortP95Us": 9000, "ortP99Us": 9500,
            },
        }
        last_ep = {
            "embed": {
                "calls": 110, "phaseTotalUs": {"tokenize": 55000, "ort": 880000},
                "ortMinUs": 4000, "ortMaxUs": 12000,
                "ortP50Us": 7500, "ortP95Us": 10000, "ortP99Us": 11500,
            },
        }
        rows = [
            {"elapsed_s": 0, "indexed": 0, "pending": 100, "embed_pct": 0,
             "splade_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0,
             "_encoder_profiles": first_ep},
            {"elapsed_s": 60, "indexed": 500, "pending": 0, "embed_pct": 100,
             "splade_pct": 100, "ner_done": 500, "ner_pending": 0, "chunk_pct": 100,
             "_encoder_profiles": last_ep},
        ]
        summary = compute_pipeline_summary(rows)
        assert "encoder_profiles" in summary
        ep = summary["encoder_profiles"]["embed"]
        assert ep["calls"] == 100  # 110 - 10
        assert ep["phaseTotalUs"]["tokenize"] == 50000  # 55000 - 5000
        assert ep["phaseTotalUs"]["ort"] == 800000  # 880000 - 80000
        # Percentiles taken from last row (cumulative, not delta-able)
        assert ep["ortP50Us"] == 7500
        assert ep["ortP95Us"] == 10000

    def test_encoder_profiles_no_baseline(self):
        """First row without encoder profiles — last row values used as-is."""
        last_ep = {
            "embed": {
                "calls": 100, "phaseTotalUs": {"tokenize": 50000, "ort": 800000},
                "ortMinUs": 5000, "ortMaxUs": 12000,
                "ortP50Us": 7500, "ortP95Us": 10000, "ortP99Us": 11500,
            },
        }
        rows = [
            {"elapsed_s": 0, "indexed": 0, "pending": 100, "embed_pct": 0,
             "splade_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0},
            {"elapsed_s": 60, "indexed": 500, "pending": 0, "embed_pct": 100,
             "splade_pct": 100, "ner_done": 500, "ner_pending": 0, "chunk_pct": 100,
             "_encoder_profiles": last_ep},
        ]
        summary = compute_pipeline_summary(rows)
        assert "encoder_profiles" in summary
        assert summary["encoder_profiles"]["embed"]["calls"] == 100

    def test_encoder_profiles_absent(self):
        rows = [
            {"elapsed_s": 0, "indexed": 0, "pending": 0, "embed_pct": 0,
             "splade_pct": 0, "ner_done": 0, "ner_pending": 0, "chunk_pct": 0},
            {"elapsed_s": 60, "indexed": 500, "pending": 0, "embed_pct": 100,
             "splade_pct": 100, "ner_done": 500, "ner_pending": 0, "chunk_pct": 100},
        ]
        summary = compute_pipeline_summary(rows)
        assert "encoder_profiles" not in summary

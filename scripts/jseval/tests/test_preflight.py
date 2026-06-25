"""Tests for preflight.py — backend health and model identity checks."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

from jseval.preflight import execute_preflight, format_console


class TestExecutePreflight:
    @patch("jseval.preflight._fetch_endpoint")
    def test_healthy_backend(self, mock_fetch):
        mock_fetch.side_effect = [
            # /api/status
            {
                "indexAvailable": True,
                "uptimeMs": 60000,
                "indexState": "IDLE",
                "indexedDocuments": 500,
                "indexBasePath": "/data/index/default",
                "throughputWindowState": "OK",
                "embedBackend": "onnx",
                "embeddingFingerprintCurrent": "abc123",
                "embeddingFingerprintStored": "abc123",
                "embeddingCompatState": "FINGERPRINT_MATCH",
                "spladeModelPath": "/models/splade.onnx",
                "rerankerModelPath": "/models/reranker.onnx",
                "embedOrtCuda": {"available": True, "attempted": True},
                "spladeOrtCuda": {"available": False, "configured": False},
                "rerankerOrtCuda": {"available": False, "configured": False},
                "embedGpuLayers": 32,
                "embeddingCoveragePercent": 100.0,
                "spladeCoveragePercent": 100.0,
                "chunkVectorCoveragePercent": 100.0,
                "completedNerCount": 500,
                "pendingNerCount": 0,
                "meta": {"workerRpcStale": False},
            },
            # /api/debug/commit-metadata
            {
                "embedding_model_sha256": "sha256abcdef",
                "field_catalog_hash": "hash123",
                "schema_fp": "fp456",
                "index_schema_fp": "ifp789",
            },
        ]

        result = execute_preflight("http://localhost:33221")

        assert result["status"] == "ok"
        assert result["errors"] == []
        assert result["models"]["embed_backend"] == "onnx"
        assert result["gpu"]["embed_cuda_available"] is True
        assert result["index"]["commit_metadata"]["embedding_model_sha256"] == "sha256abcdef"

    @patch("jseval.preflight._fetch_endpoint")
    def test_unreachable_backend(self, mock_fetch):
        mock_fetch.return_value = None

        result = execute_preflight("http://localhost:33221")

        assert result["status"] == "unreachable"
        assert any("backend_unreachable" in e for e in result["errors"])

    @patch("jseval.preflight._fetch_endpoint")
    def test_degraded_worker(self, mock_fetch):
        mock_fetch.side_effect = [
            {
                "indexAvailable": False,
                "indexStatusReason": "Worker process not running",
                "meta": {"workerRpcStale": True},
            },
            None,  # commit-metadata fails
        ]

        result = execute_preflight("http://localhost:33221")

        assert result["status"] == "degraded"
        assert any("worker_unavailable" in e for e in result["errors"])
        assert any("worker_rpc_stale" in e for e in result["errors"])


class TestFormatConsole:
    def test_formats_ok(self):
        result = {
            "status": "ok",
            "errors": [],
            "backend": {"index_available": True, "index_state": "IDLE",
                         "indexed_documents": 500, "index_base_path": "/data"},
            "models": {"embed_backend": "onnx", "embed_compat_state": "OK",
                        "splade_model_path": None, "reranker_model_path": None},
            "gpu": {"embed_cuda_available": True, "embed_gpu_layers": 32,
                     "splade_cuda_available": False, "reranker_cuda_available": False,
                     "embed_cuda_failure": None},
            "index": {"coverage": {"embedding_pct": 100.0, "splade_pct": 100.0,
                                     "chunk_vector_pct": 100.0,
                                     "ner_completed": 500, "ner_pending": 0}},
        }
        output = format_console(result)
        assert "Preflight: OK" in output
        assert "embed_backend: onnx" in output

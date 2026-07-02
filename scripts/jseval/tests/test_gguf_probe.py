"""Tests for the dependency-free GGUF metadata prober (tempdoc 674 remaining-work slice).

The real-file test is the strongest regression guard this module has -- the exact
parsing logic here was validated against `Qwen_Qwen3.5-9B-Q4_K_M.gguf` before this
module was written (tempdoc 674 §Pre-implementation confidence probes, Probe A), so
that same file is the primary fixture, resolved via `_paths.shared_models_dir()`
(worktree-robust: prefers the main checkout's `models/`, which holds the real LFS
binary, over a worktree's pointer-only copy) rather than a hardcoded relative path.
Synthetic-byte tests cover the failure-mode edges a single real file can't exercise
(wrong magic, absent optional keys, unhandled value types).
"""

from __future__ import annotations

import struct

import pytest

from jseval import gguf_probe
from jseval._paths import shared_models_dir

_REAL_MODEL_FILENAME = "Qwen_Qwen3.5-9B-Q4_K_M.gguf"


def _real_model_path():
    models_dir = shared_models_dir()
    if models_dir is None:
        return None
    p = models_dir / _REAL_MODEL_FILENAME
    return p if p.is_file() else None


class TestProbeGgufRealFile:
    def test_reads_the_real_qwen_model_correctly(self):
        path = _real_model_path()
        if path is None:
            pytest.skip(f"{_REAL_MODEL_FILENAME} not found under shared_models_dir() -- "
                        "models/ not staged in this checkout/worktree.")
        info = gguf_probe.probe_gguf(path.as_posix())
        assert info.architecture == "qwen35"
        assert info.size_label == "9B"
        assert info.name == "Qwen3.5 9B"
        assert info.size_bytes == path.stat().st_size
        assert info.size_bytes > 0


# --- synthetic-byte edge cases -------------------------------------------------


def _write_minimal_gguf(path, *, magic=b"GGUF", version=3, kv: dict | None = None):
    """Build a minimal synthetic GGUF file: header + a small set of STRING-typed
    metadata keys. Only exercises the STRING (type 8) encoding -- sufficient for
    the fields this prober actually reads."""
    kv = kv or {}
    body = b""
    for key, value in kv.items():
        key_b = key.encode("utf-8")
        body += struct.pack("<Q", len(key_b)) + key_b
        body += struct.pack("<I", 8)  # STRING
        val_b = value.encode("utf-8")
        body += struct.pack("<Q", len(val_b)) + val_b
    header = struct.pack("<4sIQQ", magic, version, 0, len(kv))
    path.write_bytes(header + body)


class TestProbeGgufSyntheticEdgeCases:
    def test_wrong_magic_raises(self, tmp_path):
        p = tmp_path / "not-a-gguf.bin"
        _write_minimal_gguf(p, magic=b"NOPE")
        with pytest.raises(ValueError, match="not a GGUF file"):
            gguf_probe.probe_gguf(p.as_posix())

    def test_missing_size_label_is_none_not_guessed(self, tmp_path):
        p = tmp_path / "no-size-label.gguf"
        _write_minimal_gguf(p, kv={"general.architecture": "llama", "general.name": "Test Model"})
        info = gguf_probe.probe_gguf(p.as_posix())
        assert info.architecture == "llama"
        assert info.name == "Test Model"
        assert info.size_label is None  # absent, not fabricated

    def test_all_interest_keys_absent_degrades_to_all_none_fields(self, tmp_path):
        p = tmp_path / "no-metadata.gguf"
        _write_minimal_gguf(p, kv={"unrelated.key": "irrelevant"})
        info = gguf_probe.probe_gguf(p.as_posix())
        assert info.architecture is None
        assert info.name is None
        assert info.size_label is None
        assert info.size_bytes == p.stat().st_size  # file size is always known

    def test_size_bytes_always_reflects_real_file_size(self, tmp_path):
        p = tmp_path / "sized.gguf"
        _write_minimal_gguf(p, kv={"general.architecture": "gemma"})
        info = gguf_probe.probe_gguf(p.as_posix())
        assert info.size_bytes == p.stat().st_size

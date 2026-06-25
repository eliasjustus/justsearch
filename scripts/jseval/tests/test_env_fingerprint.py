"""Tests for env_fingerprint (E-J-N11)."""

from __future__ import annotations

import platform

from jseval.env_fingerprint import capture_env_fingerprint


def test_capture_returns_dict_with_core_fields():
    fp = capture_env_fingerprint()
    assert isinstance(fp, dict)
    assert "captured_at" in fp
    assert "platform" in fp
    assert isinstance(fp["platform"], dict)
    assert fp["platform"]["system"] == platform.system()


def test_capture_gracefully_degrades_for_missing_tools():
    """All probe fields must be present even when tools are unavailable."""
    fp = capture_env_fingerprint()
    assert "gpu" in fp
    assert "power_plan" in fp
    assert "services" in fp
    assert "top_processes" in fp


def test_gpu_field_has_available_flag():
    fp = capture_env_fingerprint()
    gpu = fp["gpu"]
    assert isinstance(gpu, dict)
    assert "available" in gpu
    # Either False (no nvidia-smi) or True with details
    if gpu["available"]:
        # When available, driver_version is the load-bearing field
        assert "driver_version" in gpu or "raw" in gpu


def test_platform_non_windows_skips_windows_probes():
    """On non-Windows, Windows-specific probes must return safely (None/empty).

    ``top_processes`` is cross-platform via psutil since tempdoc 396, so it
    may be populated on non-Windows — it just must remain a list.
    """
    if platform.system() == "Windows":
        return
    fp = capture_env_fingerprint()
    assert fp["power_plan"] is None
    assert fp["services"] == {}
    assert isinstance(fp["top_processes"], list)


def test_timestamp_is_iso_format():
    fp = capture_env_fingerprint()
    # ISO 8601 with 'T' separator and timezone
    ts = fp["captured_at"]
    assert "T" in ts
    assert "+" in ts or "Z" in ts


def test_never_raises():
    """Capture must swallow all probe errors and return a dict."""
    # Run it twice to make sure repeated invocation is safe.
    for _ in range(2):
        fp = capture_env_fingerprint()
        assert isinstance(fp, dict)

"""Tests for jseval.projections.base (tempdoc 400 LR4-a)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from jseval.projections.base import (
    Projection,
    register,
    registry,
    reset_registry_for_tests,
    run_all,
)


@pytest.fixture(autouse=True)
def _reset_between_tests():
    reset_registry_for_tests()
    yield
    reset_registry_for_tests()


def _trivial_projection(
    name: str = "hello",
    schema_version: int = 1,
    payload: dict | None = None,
) -> Projection:
    payload = payload if payload is not None else {"greeting": "hi"}
    return Projection(
        name=name,
        schema_version=schema_version,
        description=f"Trivial {name}",
        produce=lambda run_dir: payload,
    )


class TestRegister:
    def test_register_adds_to_registry(self):
        p = register(_trivial_projection())
        assert registry() == {"hello": p}

    def test_register_is_idempotent_same_name(self):
        first = register(_trivial_projection(payload={"v": 1}))
        second = register(_trivial_projection(payload={"v": 2}))
        # Same name, last registration wins; registry has one entry.
        assert list(registry().keys()) == ["hello"]
        assert registry()["hello"].produce(Path("/")) == {"v": 2}

    def test_register_returns_the_projection(self):
        p = _trivial_projection()
        assert register(p) is p

    def test_registry_is_snapshot_not_mutable(self):
        p = register(_trivial_projection())
        snap = registry()
        # Caller mutation must not affect the internal registry.
        snap.pop("hello")
        assert "hello" in registry()


class TestRunAll:
    def test_writes_projection_file_per_registration(self, synthetic_run_dir):
        register(_trivial_projection(name="p1", payload={"a": 1}))
        register(_trivial_projection(name="p2", payload={"b": 2}))

        summary = run_all(synthetic_run_dir.run_dir)
        assert summary["p1"]["status"] == "ok"
        assert summary["p2"]["status"] == "ok"

        out = synthetic_run_dir.run_dir / "projections"
        assert (out / "p1.json").exists()
        assert (out / "p2.json").exists()

    def test_output_stamps_schema_version_and_name(self, synthetic_run_dir):
        register(_trivial_projection(name="stamped", schema_version=7))
        run_all(synthetic_run_dir.run_dir)
        doc = json.loads((synthetic_run_dir.run_dir / "projections" /
                          "stamped.json").read_text(encoding="utf-8"))
        assert doc["projection_name"] == "stamped"
        assert doc["schema_version"] == 7
        assert doc["greeting"] == "hi"

    def test_error_does_not_abort_others(self, synthetic_run_dir):
        def _boom(run_dir):
            raise RuntimeError("synthetic failure")

        register(Projection(name="bad", schema_version=1, produce=_boom))
        register(_trivial_projection(name="good"))

        summary = run_all(synthetic_run_dir.run_dir)
        assert summary["bad"]["status"] == "error"
        assert "synthetic failure" in summary["bad"]["error"]
        assert summary["good"]["status"] == "ok"
        assert (synthetic_run_dir.run_dir / "projections" /
                "good.json").exists()
        assert not (synthetic_run_dir.run_dir / "projections" /
                    "bad.json").exists()

    def test_error_writes_errors_ndjson_self_feed(self, synthetic_run_dir):
        """Phase 6 / 6.1: projection failures emit contract.violation
        events into `projections/_errors.ndjson` so the
        contract_violations aggregator + nightly gate surface them.
        """
        def _boom(run_dir):
            raise ValueError("boom-detail")

        register(Projection(name="bad", schema_version=1, produce=_boom))
        run_all(synthetic_run_dir.run_dir)

        errors_path = synthetic_run_dir.run_dir / "projections" / "_errors.ndjson"
        assert errors_path.is_file()
        lines = [
            json.loads(line) for line in errors_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        assert len(lines) == 1
        span = lines[0]
        assert span["name"] == "projection.failure"
        ev = span["events"][0]
        assert ev["name"] == "contract.violation"
        assert ev["attrs"]["contract.tempdoc"] == "400 §27.3"
        assert ev["attrs"]["contract.tier"] == "@RuntimeContract"
        assert ev["attrs"]["contract.projection"] == "bad"
        assert ev["attrs"]["contract.exception_class"] == "ValueError"
        assert "boom-detail" in ev["attrs"]["contract.description"]

    def test_skip_set_bypasses_projections(self, synthetic_run_dir):
        """Phase 6 / 6.1: `--skip-projection` support via skip kwarg."""
        register(_trivial_projection(name="keep"))
        register(_trivial_projection(name="drop"))

        summary = run_all(synthetic_run_dir.run_dir, skip=frozenset({"drop"}))
        assert summary["keep"]["status"] == "ok"
        assert summary["drop"]["status"] == "skipped"
        out = synthetic_run_dir.run_dir / "projections"
        assert (out / "keep.json").exists()
        assert not (out / "drop.json").exists()

    def test_no_errors_file_when_all_succeed(self, synthetic_run_dir):
        register(_trivial_projection(name="p1"))
        run_all(synthetic_run_dir.run_dir)
        assert not (synthetic_run_dir.run_dir / "projections" / "_errors.ndjson").exists()

    def test_empty_registry_produces_empty_summary(self, synthetic_run_dir):
        summary = run_all(synthetic_run_dir.run_dir)
        assert summary == {}
        # Empty registry must not fail on missing projections dir.
        assert not (synthetic_run_dir.run_dir / "projections").exists()

    def test_projection_receives_run_dir_path(self, synthetic_run_dir):
        received: list[Path] = []

        def _capture(run_dir: Path):
            received.append(run_dir)
            return {"seen": str(run_dir)}

        register(Projection(name="capture", schema_version=1, produce=_capture))
        run_all(synthetic_run_dir.run_dir)
        assert received == [synthetic_run_dir.run_dir]


class TestBootstrap:
    """run_all_discovered imports sibling modules once."""

    def test_discovers_contract_violations(self, synthetic_run_dir):
        # Bootstrap loads the existing Phase-1 contract_violations
        # projection + every extant LR4-* module. Without fixtures,
        # contract_violations returns empty aggregate.
        from jseval.projections import run_all_discovered

        synthetic_run_dir.with_traces([])
        summary = run_all_discovered(synthetic_run_dir.run_dir)
        assert "contract_violations" in summary
        assert summary["contract_violations"]["status"] == "ok"

        out = synthetic_run_dir.run_dir / "projections" / "contract_violations.json"
        doc = json.loads(out.read_text(encoding="utf-8"))
        assert doc["projection_name"] == "contract_violations"
        assert doc["schema_version"] == 1
        assert doc["total_violations"] == 0

    def test_bootstrap_is_idempotent(self, synthetic_run_dir):
        """Repeat bootstrap must not double-register or raise."""
        from jseval.projections import run_all_discovered

        synthetic_run_dir.with_traces([])
        run_all_discovered(synthetic_run_dir.run_dir)
        # Size after first bootstrap.
        first = len(registry())
        run_all_discovered(synthetic_run_dir.run_dir)
        assert len(registry()) == first

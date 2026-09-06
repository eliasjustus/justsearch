from __future__ import annotations

from types import SimpleNamespace

import pytest
from click.testing import CliRunner

from jseval import duplicate_prevalence_enron as enron
from jseval import duplicate_prevalence_production as production
from jseval.commands.analysis import cmd_duplicate_prevalence
from jseval.timeline import snapshot_to_row
from tests.test_duplicate_prevalence_schema import _artifact


def test_wait_command_prepares_once_before_capture_and_records_only_aggregate_timeline(tmp_path, monkeypatch):
    spec, out, timeline = [tmp_path / name for name in ("input.json", "out.json", "timeline.tsv")]
    spec.write_text("{}", encoding="utf-8")
    request = SimpleNamespace(source=SimpleNamespace(raw_root=tmp_path / "raw"))
    events = []
    monkeypatch.setattr(enron, "input_source_kind", lambda _: production.SOURCE_KIND)
    monkeypatch.setattr(production, "load_input_spec", lambda _: request)

    def prepare(actual, *, ingest, timeout_seconds, on_snapshot):
        assert actual is request and ingest is True and timeout_seconds == 30
        events.append("prepare")
        on_snapshot(1, {"pendingVduCount": 2, "vduProcessing": True, "private_path": "PRIVATE"})
        on_snapshot(2, {"pendingVduCount": 0, "vduProcessing": False})

    def analyze(actual):
        assert actual is request
        events.append("capture")
        return _artifact(production.prevalence.PRODUCTION_EXTRACTED)

    monkeypatch.setattr(production, "prepare_request", prepare, raising=False)
    monkeypatch.setattr(production, "analyze_request", analyze)
    result = CliRunner().invoke(cmd_duplicate_prevalence, [
        "--input-spec", str(spec), "--out", str(out), "--ingest",
        "--wait-timeout-seconds", "30", "--timeline", str(timeline),
    ], obj={})
    assert result.exit_code == 0, result.output
    assert events == ["prepare", "capture"]
    assert "vdu_pending\tvdu_processing" in timeline.read_text()
    assert "PRIVATE" not in timeline.read_text()
    assert spec.read_text() == "{}"


@pytest.mark.parametrize("flags,source_kind", [
    (["--ingest"], production.SOURCE_KIND),
    (["--wait-timeout-seconds", "30"], enron.SOURCE_KIND),
])
def test_wait_flags_fail_before_preparation(tmp_path, monkeypatch, flags, source_kind):
    spec = tmp_path / "input.json"
    spec.write_text("{}", encoding="utf-8")
    monkeypatch.setattr(enron, "input_source_kind", lambda _: source_kind)
    monkeypatch.setattr(production, "prepare_request", lambda *a, **kw: pytest.fail("must not prepare"), raising=False)
    result = CliRunner().invoke(cmd_duplicate_prevalence, ["--input-spec", str(spec), "--out", str(tmp_path / "out.json"), *flags], obj={})
    assert result.exit_code != 0
    assert not (tmp_path / "out.json").exists()


def test_vdu_timeline_missing_state_is_unknown_not_zero():
    missing = snapshot_to_row(0, {})
    assert missing["vdu_pending"] == ""
    assert missing["vdu_processing"] == ""
    complete = snapshot_to_row(1, {"pendingVduCount": 0, "vduProcessing": False})
    assert complete["vdu_pending"] == 0
    assert complete["vdu_processing"] == 0


@pytest.mark.parametrize("destination", ["--out", "--timeline", "--review-packet-out"])
def test_production_wait_refuses_outputs_inside_raw_root_before_ingest(tmp_path, monkeypatch, destination):
    raw = tmp_path / "raw"
    raw.mkdir()
    spec = tmp_path / "input.json"
    spec.write_text("{}", encoding="utf-8")
    request = SimpleNamespace(source=SimpleNamespace(raw_root=raw))
    monkeypatch.setattr(enron, "input_source_kind", lambda _: production.SOURCE_KIND)
    monkeypatch.setattr(production, "load_input_spec", lambda _: request)
    monkeypatch.setattr(production, "prepare_request", lambda *a, **kw: pytest.fail("must not ingest"))
    monkeypatch.setattr(production, "analyze_request", lambda *a, **kw: pytest.fail("must not capture"))
    args = ["--input-spec", str(spec), "--out", str(tmp_path / "out.json"), "--ingest", "--wait-timeout-seconds", "30"]
    if destination == "--out":
        args[3] = str(raw / "generated.json")
    else:
        args.extend([destination, str(raw / "generated.json")])
    result = CliRunner().invoke(cmd_duplicate_prevalence, args, obj={})
    assert result.exit_code != 0
    assert "outside the production raw corpus" in result.output
    assert list(raw.iterdir()) == []

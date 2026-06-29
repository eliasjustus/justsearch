"""Tests for jseval.commands._common (tempdoc 645).

Locks the ``_write_bench_output`` write path — which calls ``click.echo`` and
therefore needs ``click`` imported (the bug this test guards: a missing
``import click`` in ``_common.py`` was a latent NameError that only fired when
``output_dir`` was set, so the early-return path kept the suite green).
"""

from __future__ import annotations

import json

from jseval.commands._common import _write_bench_output


def test_write_bench_output_writes_file_and_echoes(tmp_path, capsys):
    """With output_dir set, it writes the JSON and runs the click.echo path."""
    _write_bench_output({"metric": 1.5, "obj": object()}, str(tmp_path), "bench.json")
    out = tmp_path / "bench.json"
    assert out.exists()
    data = json.loads(out.read_text(encoding="utf-8"))
    assert data["metric"] == 1.5
    # default=str renders the non-serializable object rather than raising.
    assert isinstance(data["obj"], str)
    # The click.echo path executed (would NameError without `import click`).
    assert "Written to" in capsys.readouterr().out


def test_write_bench_output_noop_without_output_dir(tmp_path):
    """No output_dir → early return, nothing written."""
    _write_bench_output({"metric": 1.0}, None, "bench.json")
    assert list(tmp_path.iterdir()) == []

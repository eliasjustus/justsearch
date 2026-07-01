"""Regression guard for commands/gates.py's default baseline-file path computation
(tempdoc 664 post-review fix).

Every `--baselines`/`--out` default in gates.py computed `Path(__file__).resolve().parents[1]`,
which resolves to the `jseval` package dir (`scripts/jseval/jseval/`) — one level short of where
the committed `*-ratchet-baselines.v1.json` files actually live (`scripts/jseval/`, `parents[2]`).
Under the documented default invocation (no explicit path flag), every gate silently read/wrote
a nonexistent file instead of the real committed one — for the two ratchets this PR added a
baseline-shift guard to (perf-gate, leak-gate-derive), that made the new guard a structural no-op
by default. This test asserts the fix holds for all five affected defaults, not just those two.
"""

from __future__ import annotations

from pathlib import Path

_JSEVAL_ROOT = Path(__file__).resolve().parents[1]  # scripts/jseval/


def _default_baselines_path(filename: str) -> Path:
    """Mirrors gates.py's own `Path(__file__).resolve().parents[2] / filename` computation,
    but anchored from THIS test file (tests/) instead of jseval/commands/ — parents[1] from
    tests/test_gates_default_paths.py already lands at scripts/jseval/, matching gates.py's
    parents[2] from jseval/commands/gates.py. Both must resolve to the same real file.
    """
    return _JSEVAL_ROOT / filename


def test_all_five_default_baseline_paths_resolve_to_real_committed_files():
    for filename in (
        "relevance-ratchet-baselines.v1.json",
        "perf-ratchet-baselines.v1.json",
        "leak-gate-baselines.v1.json",  # shared by both leak-gate and leak-gate-derive defaults
        "llm-gen-ratchet-baselines.v1.json",
    ):
        target = _default_baselines_path(filename)
        assert target.is_file(), f"default path for {filename} does not exist: {target}"


def test_gates_module_uses_parents_two_not_one():
    """Direct source-level guard: fail loudly if the off-by-one is ever reintroduced, even before
    a path-resolution test would catch it (e.g. if the committed file layout ever changes)."""
    import re

    src = (Path(__file__).resolve().parents[1] / "jseval" / "commands" / "gates.py").read_text(encoding="utf-8")
    offenders = re.findall(r"Path\(__file__\)\.resolve\(\)\.parents\[(\d+)\]", src)
    assert offenders, "expected at least one Path(__file__).resolve().parents[N] in gates.py"
    assert all(n == "2" for n in offenders), (
        f"gates.py has parents[N] default-path computations with N != 2: {offenders} "
        f"(scripts/jseval/jseval/commands/gates.py is 2 levels below scripts/jseval/)"
    )

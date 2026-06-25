"""ui-a11y-gate — the ASSERT capability (tempdoc 615 §11 ASSERT / §13 Move 2).

Turns the NEW-vs-known measurement into a *gate that fails*: capture each surface in
the shared a11y baseline register in the DETERMINISTIC `--fixtures` state, and fail
(exit 1) if any surface has a NEW axe violation — a rule id not in its accepted
`knownRules`. Known/accepted debt stays green; a genuinely new regression goes red.

This is the rendered-UI counterpart to the static 559 presentation gates (which assert
from source/tokens). It reuses the ONE baseline authority `governance/ui-a11y-baseline.v1.json`
and the ONE split `ui_measure.split_new_vs_known` (via the capture summary's `axe_new`),
so the gate and the agent's live summary can never disagree. Local-first (ADR-0026): a
runnable gate, not a CI-wired kernel gate.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable

from .ui_measure import split_new_vs_known


def load_register_surfaces() -> list[dict[str, Any]]:
    """The shared a11y baseline register's surfaces (each {surface, uiShotStep, knownRules})."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        cand = parent / "governance" / "ui-a11y-baseline.v1.json"
        if cand.exists():
            doc = json.loads(cand.read_text(encoding="utf-8"))
            return list(doc.get("surfaces", []))
    return []


def _axe_violations(measure_path: str) -> list[dict[str, Any]]:
    """The axe violations recorded in a `<step>.measure.json` capture."""
    m = json.loads(Path(measure_path).read_text(encoding="utf-8"))
    return (m.get("axe") or {}).get("violations") or []


def evaluate(capture_fn: Callable[[str], dict[str, Any]]) -> dict[str, Any]:
    """Run the gate. ``capture_fn(step)`` captures a step (fixtures on) and returns the
    ui-shot result dict (with ``ok`` and a ``measure`` summary carrying ``measure_path``).

    NEW is computed HERE from the register's ``knownRules`` against the capture's raw
    axe violations (via the shared ``split_new_vs_known``), so the register is the
    single input — the gate does not depend on which baseline `ui_measure` loaded at
    import. Returns ``exit_code`` 0 = clean, 1 = a NEW violation, 2 = a capture error;
    plus per-surface rows. Injecting ``capture_fn`` keeps this unit-testable.
    """
    surfaces = load_register_surfaces()
    rows: list[dict[str, Any]] = []
    any_new = False
    any_error = False

    for s in surfaces:
        step = s.get("uiShotStep")
        if not step:
            continue
        res = capture_fn(step)
        if not res.get("ok"):
            any_error = True
            rows.append({"surface": s.get("surface"), "step": step,
                         "status": "ERROR", "error": res.get("error")})
            continue
        measure_path = (res.get("measure") or {}).get("measure_path")
        if not measure_path:
            any_error = True
            rows.append({"surface": s.get("surface"), "step": step,
                         "status": "ERROR", "error": "no measurement companion (measure disabled?)"})
            continue
        new, known = split_new_vs_known(_axe_violations(measure_path), list(s.get("knownRules") or []))
        if new:
            any_new = True
        rows.append({"surface": s.get("surface"), "step": step,
                     "status": "NEW" if new else "ok",
                     "new": new, "known": known})

    exit_code = 2 if any_error else (1 if any_new else 0)
    return {
        "exit_code": exit_code,
        "summary": (
            "a11y regression: NEW violation(s)" if exit_code == 1
            else "capture error" if exit_code == 2
            else "clean — no NEW a11y violations vs baseline"
        ),
        "surfaces": rows,
    }

"""ui-proportion-gate — the chrome proportion shrink-only ratchet (tempdoc 697).

Mirrors `ui_a11y_gate.py`'s ASSERT shape: capture each step in the shared proportion
baseline register in the DETERMINISTIC `--fixtures` state, and fail (exit 1) if any
registered persistent-chrome element's rendered HEIGHT GREW beyond its recorded
`maxHeightPx` (+ `tolerancePx`). Shrinking — or staying within tolerance — is always
clean. This reuses the ONE baseline authority `governance/ui-proportion-baseline.v1.json`
and the ONE geometry capture `ui_measure.capture_measure` (which unions the baseline's
registered selectors into its shadow-piercing geometry probe), so the gate and the
`.measure.json` geometry it reads can never disagree about which elements were measured.
Local-first (ADR-0026): a runnable gate, not a CI-wired kernel gate.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable

_DEFAULT_TOLERANCE_PX = 2


def load_register_steps() -> list[dict[str, Any]]:
    """The shared proportion baseline register's steps (each {uiShotStep, tolerancePx,
    elements: [{selector, maxHeightPx}]}). A step/element carries its own `tolerancePx`
    only if it overrides the register's top-level default (folded in here so callers
    don't need the top-level doc)."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        cand = parent / "governance" / "ui-proportion-baseline.v1.json"
        if cand.exists():
            doc = json.loads(cand.read_text(encoding="utf-8"))
            default_tol = doc.get("tolerancePx", _DEFAULT_TOLERANCE_PX)
            steps = list(doc.get("steps", []))
            for s in steps:
                s.setdefault("tolerancePx", default_tol)
            return steps
    return []


def _geometry_elements(measure_path: str) -> dict[str, Any]:
    """The `geometry.elements` map recorded in a `<step>.measure.json` capture —
    keyed by the same shadow-piercing selector strings the baseline registers."""
    m = json.loads(Path(measure_path).read_text(encoding="utf-8"))
    return ((m.get("geometry") or {}).get("elements")) or {}


def evaluate(capture_fn: Callable[[str], dict[str, Any]]) -> dict[str, Any]:
    """Run the gate. ``capture_fn(step)`` captures a step (fixtures on) and returns the
    ui-shot result dict (with ``ok`` and a ``measure`` summary carrying ``measure_path``).

    A violation is GROWTH: ``measuredHeight > maxHeightPx + tolerancePx``. Returns
    ``exit_code`` 0 = clean, 1 = a GROWN element, 2 = capture error (including a
    registered selector missing from the captured geometry — a silent miss must not
    read as a false pass); plus per-element rows. Injecting ``capture_fn`` keeps this
    unit-testable (mirrors `ui_a11y_gate.evaluate`).
    """
    steps = load_register_steps()
    rows: list[dict[str, Any]] = []
    any_grown = False
    any_error = False

    for s in steps:
        step = s.get("uiShotStep")
        elements = s.get("elements") or []
        if not step or not elements:
            continue
        res = capture_fn(step)
        if not res.get("ok"):
            any_error = True
            rows.append({"step": step, "status": "ERROR", "error": res.get("error")})
            continue
        measure_path = (res.get("measure") or {}).get("measure_path")
        if not measure_path:
            any_error = True
            rows.append({"step": step, "status": "ERROR",
                         "error": "no measurement companion (measure disabled?)"})
            continue
        geo_elements = _geometry_elements(measure_path)
        tolerance_px = s.get("tolerancePx", _DEFAULT_TOLERANCE_PX)

        for el in elements:
            selector = el.get("selector")
            max_height_px = el.get("maxHeightPx")
            captured = geo_elements.get(selector)
            if captured is None:
                any_error = True
                rows.append({"step": step, "selector": selector, "status": "ERROR",
                             "error": "selector not found in captured geometry"})
                continue
            measured_height = (captured.get("rect") or {}).get("h")
            if measured_height is None:
                any_error = True
                rows.append({"step": step, "selector": selector, "status": "ERROR",
                             "error": "captured geometry has no rect.h"})
                continue
            grown = measured_height > max_height_px + tolerance_px
            if grown:
                any_grown = True
            rows.append({
                "step": step, "selector": selector,
                "status": "GROWN" if grown else "ok",
                "measuredHeight": measured_height,
                "maxHeightPx": max_height_px,
                "tolerancePx": tolerance_px,
            })

    exit_code = 2 if any_error else (1 if any_grown else 0)
    return {
        "exit_code": exit_code,
        "summary": (
            "proportion regression: element(s) GROWN beyond baseline" if exit_code == 1
            else "capture error" if exit_code == 2
            else "clean — no registered element grew beyond baseline"
        ),
        "rows": rows,
    }

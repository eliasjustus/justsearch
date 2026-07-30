"""ui-proportion-gate — the chrome proportion ratchet (tempdoc 697).

Mirrors `ui_a11y_gate.py`'s ASSERT shape: capture each step in the shared proportion
baseline register in the DETERMINISTIC `--fixtures` state, and fail (exit 1) if any
registered element violates a declared geometric constraint (originally only "this
persistent chrome must not GROW beyond `maxHeightPx`"; see the constraint table below).
This reuses the ONE baseline authority `governance/ui-proportion-baseline.v1.json`
and the ONE geometry capture `ui_measure.capture_measure` (which unions the baseline's
registered selectors into its shadow-piercing geometry probe), so the gate and the
`.measure.json` geometry it reads can never disagree about which elements were measured.
Local-first (ADR-0026): a runnable gate, not a CI-wired kernel gate.

Three constraint kinds, all read off the SAME captured rect. An element declares
whichever apply; declaring none is an error, not a silent pass.

  ``maxHeightPx``            697's original shrink-only ratchet — persistent chrome must
                             not GROW. Shrinking is always clean.
  ``minWidthPx``             a FLOOR for primary content — the surface that must not be
                             starved by the chrome around it. Added for the round-7
                             defect where the RAG answer column collapsed to ~one word
                             per line (measured 102px) while the evidence rail and the
                             document pane held their own min-widths.
  ``mustNotOverlapSelector`` two rects must not intersect. Added for the round-7 defect
                             where an unbounded toast stack sat over the chat surface's
                             header control row. A height/width budget cannot express
                             this: each element was individually within budget — the
                             defect was the RELATION between them.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable

_DEFAULT_TOLERANCE_PX = 2


def load_register_steps() -> list[dict[str, Any]]:
    """The shared proportion baseline register's steps (each {uiShotStep, tolerancePx,
    elements: [{selector, maxHeightPx?, minWidthPx?, mustNotOverlapSelector?}]}). A
    step/element carries its own `tolerancePx`
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


def _rects_overlap(a: dict[str, Any], b: dict[str, Any], tolerance_px: int) -> bool:
    """True when two captured rects intersect by MORE than ``tolerance_px`` on both axes.

    A shared edge (or a sub-tolerance nudge) is not an occlusion; a genuine overlap is
    one you can see. Both rects come from the same `getBoundingClientRect` capture, so
    they are already in the same viewport coordinate space.
    """
    overlap_w = min(a["x"] + a["w"], b["x"] + b["w"]) - max(a["x"], b["x"])
    overlap_h = min(a["y"] + a["h"], b["y"] + b["h"]) - max(a["y"], b["y"])
    return overlap_w > tolerance_px and overlap_h > tolerance_px


def _rect_of(geo_elements: dict[str, Any], selector: str) -> dict[str, Any] | None:
    """The captured rect for ``selector``, or None when the selector was not captured.

    Field completeness is checked per CONSTRAINT, not here: a height ceiling needs only
    ``h``, so demanding a full x/y/w/h rect for every element would turn a usable capture
    into a spurious error."""
    captured = geo_elements.get(selector)
    if captured is None:
        return None
    return captured.get("rect") or {}


def _full_rect(rect: dict[str, Any] | None) -> bool:
    """True when a rect carries every field the overlap geometry needs."""
    return rect is not None and all(rect.get(k) is not None for k in ("x", "y", "w", "h"))


def evaluate(capture_fn: Callable[[str], dict[str, Any]]) -> dict[str, Any]:
    """Run the gate. ``capture_fn(step)`` captures a step (fixtures on) and returns the
    ui-shot result dict (with ``ok`` and a ``measure`` summary carrying ``measure_path``).

    Violations, one row per declared constraint:
      - ``GROWN``    — ``rect.h > maxHeightPx + tolerancePx``
      - ``STARVED``  — ``rect.w < minWidthPx - tolerancePx``
      - ``OVERLAPS`` — the element's rect intersects ``mustNotOverlapSelector``'s rect

    Returns ``exit_code`` 0 = clean, 1 = a violation, 2 = capture error (including a
    registered selector missing from the captured geometry, or an element that declares
    no constraint at all — a silent miss must not read as a false pass); plus per-element
    rows. Injecting ``capture_fn`` keeps this unit-testable (mirrors `ui_a11y_gate.evaluate`).
    """
    steps = load_register_steps()
    rows: list[dict[str, Any]] = []
    any_violation = False
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
            min_width_px = el.get("minWidthPx")
            not_overlap = el.get("mustNotOverlapSelector")
            if max_height_px is None and min_width_px is None and not_overlap is None:
                any_error = True
                rows.append({"step": step, "selector": selector, "status": "ERROR",
                             "error": "element declares no constraint (maxHeightPx / "
                                      "minWidthPx / mustNotOverlapSelector)"})
                continue
            rect = _rect_of(geo_elements, selector)
            if rect is None:
                any_error = True
                rows.append({"step": step, "selector": selector, "status": "ERROR",
                             "error": "selector not found in captured geometry"})
                continue

            if max_height_px is not None:
                measured_height = rect.get("h")
                if measured_height is None:
                    any_error = True
                    rows.append({"step": step, "selector": selector,
                                 "constraint": "maxHeightPx", "status": "ERROR",
                                 "error": "captured geometry has no rect.h"})
                else:
                    grown = measured_height > max_height_px + tolerance_px
                    any_violation = any_violation or grown
                    rows.append({
                        "step": step, "selector": selector, "constraint": "maxHeightPx",
                        "status": "GROWN" if grown else "ok",
                        "measuredHeight": measured_height,
                        "maxHeightPx": max_height_px,
                        "tolerancePx": tolerance_px,
                    })

            if min_width_px is not None:
                measured_width = rect.get("w")
                if measured_width is None:
                    any_error = True
                    rows.append({"step": step, "selector": selector,
                                 "constraint": "minWidthPx", "status": "ERROR",
                                 "error": "captured geometry has no rect.w"})
                else:
                    starved = measured_width < min_width_px - tolerance_px
                    any_violation = any_violation or starved
                    rows.append({
                        "step": step, "selector": selector, "constraint": "minWidthPx",
                        "status": "STARVED" if starved else "ok",
                        "measuredWidth": measured_width,
                        "minWidthPx": min_width_px,
                        "tolerancePx": tolerance_px,
                    })

            if not_overlap is not None:
                other = _rect_of(geo_elements, not_overlap)
                if not _full_rect(rect) or not _full_rect(other):
                    any_error = True
                    rows.append({"step": step, "selector": selector,
                                 "constraint": "mustNotOverlapSelector", "status": "ERROR",
                                 "error": f"overlap needs a full x/y/w/h rect for both "
                                          f"{selector!r} and {not_overlap!r}"})
                else:
                    overlaps = _rects_overlap(rect, other or {}, tolerance_px)
                    any_violation = any_violation or overlaps
                    rows.append({
                        "step": step, "selector": selector,
                        "constraint": "mustNotOverlapSelector",
                        "status": "OVERLAPS" if overlaps else "ok",
                        "mustNotOverlapSelector": not_overlap,
                        "rect": rect, "otherRect": other,
                        "tolerancePx": tolerance_px,
                    })

    exit_code = 2 if any_error else (1 if any_violation else 0)
    return {
        "exit_code": exit_code,
        "summary": (
            "proportion regression: a registered geometric constraint was violated"
            if exit_code == 1
            else "capture error" if exit_code == 2
            else "clean — every registered geometric constraint held"
        ),
        "rows": rows,
    }

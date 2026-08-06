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

Five element-level constraint kinds, all read off the SAME captured rect. An element
declares whichever apply; declaring none is an error, not a silent pass. Two STEP-level
checks (not per-element) round out tempdoc 814's D7 enforcement list.

  ``maxHeightPx``            697's original shrink-only ratchet — persistent chrome must
                             not GROW. Shrinking is always clean.
  ``minHeightPx``            the CEILING's floor companion (814 review pass). A ceiling
                             alone is satisfied by an element that COLLAPSED: the Detailed
                             banner's 176px ceiling passes at 34px, i.e. exactly when
                             Detailed expansion has regressed to the collapsed pill and the
                             row is measuring the wrong element. A band whose whole point is
                             that it EXPANDS declares both bounds. (Same anti-vacuity move
                             as `minScrollableRegions` next to `maxScrollableRegions`.)
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
  ``minShareOfSelector``     tempdoc 814 D1/D7.1 — this element's rect.h divided by a
                             second registered selector's rect.h must be >= floor. The
                             "owner of the sum" assertion: primary content must own a
                             minimum SHARE of the surface, not just clear an absolute px
                             floor (a surface can shrink and keep its share, or grow
                             chrome and lose it — an absolute floor catches neither).
  ``maxBottomPx``            tempdoc 814 D6/D7.2 (the F5 close) — rect.y + rect.h must
                             not exceed this value, i.e. the element's bottom edge must
                             stay within a viewport of this height. Added for round 8's
                             F5: clearing results with the document pane open used to
                             clip the composer below a short viewport.

Step-level (not per-element; declared alongside ``elements`` on the step object):

  ``maxScrollableRegions``   tempdoc 814 D3/D7.2 — the one-scroller-per-surface rule.
                             Asserts ``geometry.scrollableCount`` (ui_measure.py's
                             shadow-piercing scrollable-element walk) does not exceed
                             the declared ceiling.
  ``minScrollableRegions``   the closure audit's finding C — a ceiling alone is VACUOUS on a
                             capture whose ``scrollableCount`` is 0: "at most one scroller"
                             passes trivially when the state never produces one, so the
                             one-scroller rule would keep reporting green through a
                             regression that removed the scroller entirely (or through a
                             setup that silently stopped reaching the overflowing state).
                             A step that is SUPPOSED to scroll declares this floor, and the
                             pair (min 1 / max 1) is what makes the rule witness a real
                             scroller.
  ``statusFactsSingleton``   tempdoc 814 D5/D7.3 — the "one authority, one pointer"
                             copy-lint. Asserts every phrase in
                             governance/status-facts.v1.json renders at most its
                             registered ``maxPersistentRenders`` times in this step's
                             captured DOM text (``ui_measure.py``'s statusFacts probe).
  ``absentSelectors``        a selector that must NOT be present in the captured
                             geometry (e.g. the run-spine on a single-turn conversation).
                             A present selector is a violation, not a silent pass —
                             mirrors the "declaring none is an error" discipline for the
                             positive case.
  ``requiredSelectors``      `absentSelectors`' positive twin (814 §D7.2's spine PAIR):
                             a selector that MUST be present in the captured geometry.
                             Registered on the step, not as an element row, because the
                             assertion is presence itself — the step declares no budget for
                             it, and an element row with no constraint is an error by
                             design.
  ``forbiddenVisibleText``   a literal phrase that must not render VISIBLY in this step's
                             capture (814 §D5's chip-yield witness). Counted by the same
                             visibility-filtered `ui_measure.py` probe the status-facts
                             register feeds — the difference is scope: a status fact is
                             register-wide with a `maxPersistentRenders` ceiling, a
                             forbidden phrase is THIS step's state with a ceiling of 0.
                             It exists because the D5 chip-yield is a CROSS-WORDING
                             duplication (banner "Semantic search degraded." vs. chip
                             "Service degraded") that a same-phrase counter cannot witness:
                             what is decidable at capture time is that the surface owning
                             the banner does not ALSO show the chip's wording.
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


def _load_status_facts_register() -> dict[str, int]:
    """The shared status-facts register (tempdoc 814 §D5): {phrase -> maxPersistentRenders}.
    The ONE authority `governance/status-facts.v1.json` — `ui_measure.py` captures the raw
    per-phrase COUNT from the DOM; this gate cross-references the same register for the
    CEILING, mirroring how `load_register_steps` is the one authority for element ceilings.
    Best-effort: an absent/garbled register yields an empty map (a step declaring
    `statusFactsSingleton` then has nothing to check against, reported as a capture error
    rather than a silent pass — see the ERROR row in `evaluate`)."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        cand = parent / "governance" / "status-facts.v1.json"
        if cand.exists():
            try:
                reg = json.loads(cand.read_text(encoding="utf-8"))
                return {
                    f["phrase"]: f.get("maxPersistentRenders", 1)
                    for f in reg.get("facts") or []
                    if f.get("phrase")
                }
            except Exception:
                return {}
    return {}


def _measure_doc(measure_path: str) -> dict[str, Any]:
    """The full parsed `<step>.measure.json` document — read once per step and shared by
    every check (elements, scrollableCount, statusFacts) so the gate never disagrees with
    itself about which capture it is reading."""
    return json.loads(Path(measure_path).read_text(encoding="utf-8"))


def _geometry_elements(measure_path: str) -> dict[str, Any]:
    """The `geometry.elements` map recorded in a `<step>.measure.json` capture —
    keyed by the same shadow-piercing selector strings the baseline registers."""
    m = _measure_doc(measure_path)
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
      - ``GROWN``                       — ``rect.h > maxHeightPx + tolerancePx``
      - ``UNDER_MIN_HEIGHT``            — ``rect.h < minHeightPx - tolerancePx``
      - ``STARVED``                     — ``rect.w < minWidthPx - tolerancePx``
      - ``OVERLAPS``                    — the element's rect intersects
                                           ``mustNotOverlapSelector``'s rect
      - ``UNDER_SHARE``                 — ``(rect.h + tolerancePx) / other.rect.h < floor``
      - ``CLIPPED``                     — ``rect.y + rect.h > maxBottomPx + tolerancePx``
      - ``MULTI_SCROLL``                — ``geometry.scrollableCount > maxScrollableRegions``
      - ``NO_SCROLLER``                 — ``geometry.scrollableCount < minScrollableRegions``
      - ``DUPLICATE_STATUS_FACT``       — a status-facts phrase's count exceeds its
                                           registered ``maxPersistentRenders``
      - ``PRESENT_BUT_SHOULD_BE_ABSENT`` — an ``absentSelectors`` entry WAS captured
      - ``MISSING_REQUIRED``            — a ``requiredSelectors`` entry was NOT captured
      - ``FORBIDDEN_TEXT_VISIBLE``      — a ``forbiddenVisibleText`` phrase rendered
                                           visibly (count > 0)

    Returns ``exit_code`` 0 = clean, 1 = a violation, 2 = capture error (including a
    registered selector missing from the captured geometry, or an element that declares
    no constraint at all — a silent miss must not read as a false pass); plus per-element
    rows. Injecting ``capture_fn`` keeps this unit-testable (mirrors `ui_a11y_gate.evaluate`).
    """
    steps = load_register_steps()
    status_facts_register = _load_status_facts_register()
    rows: list[dict[str, Any]] = []
    any_violation = False
    any_error = False

    for s in steps:
        step = s.get("uiShotStep")
        elements = s.get("elements") or []
        absent_selectors = s.get("absentSelectors") or []
        required_selectors = s.get("requiredSelectors") or []
        forbidden_text = s.get("forbiddenVisibleText") or []
        max_scrollable = s.get("maxScrollableRegions")
        min_scrollable = s.get("minScrollableRegions")
        status_facts_singleton = bool(s.get("statusFactsSingleton"))
        if not step:
            continue
        # A step with NO declared check at all (no elements, no step-level flag) has
        # nothing to assert — skip it (this is the register's placeholder-row escape
        # hatch, not a silent miss: a step that DOES declare a step-level flag with an
        # empty `elements` array, e.g. `chat-spine-single`'s `absentSelectors`-only row,
        # still runs every check below).
        if (not elements and not absent_selectors and not required_selectors
                and not forbidden_text and max_scrollable is None
                and min_scrollable is None and not status_facts_singleton):
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
        doc = _measure_doc(measure_path)
        geo = doc.get("geometry") or {}
        geo_elements = geo.get("elements") or {}
        tolerance_px = s.get("tolerancePx", _DEFAULT_TOLERANCE_PX)

        for el in elements:
            selector = el.get("selector")
            max_height_px = el.get("maxHeightPx")
            min_height_px = el.get("minHeightPx")
            min_width_px = el.get("minWidthPx")
            not_overlap = el.get("mustNotOverlapSelector")
            min_share = el.get("minShareOfSelector")
            max_bottom_px = el.get("maxBottomPx")
            if (max_height_px is None and min_height_px is None and min_width_px is None
                    and not_overlap is None and min_share is None and max_bottom_px is None):
                any_error = True
                rows.append({"step": step, "selector": selector, "status": "ERROR",
                             "error": "element declares no constraint (maxHeightPx / "
                                      "minHeightPx / minWidthPx / mustNotOverlapSelector / "
                                      "minShareOfSelector / maxBottomPx)"})
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

            # The ceiling's floor companion: a band registered because it EXPANDS must not
            # silently collapse into its ceiling's comfortable interior (review pass).
            if min_height_px is not None:
                measured_height = rect.get("h")
                if measured_height is None:
                    any_error = True
                    rows.append({"step": step, "selector": selector,
                                 "constraint": "minHeightPx", "status": "ERROR",
                                 "error": "captured geometry has no rect.h"})
                else:
                    collapsed = measured_height < min_height_px - tolerance_px
                    any_violation = any_violation or collapsed
                    rows.append({
                        "step": step, "selector": selector, "constraint": "minHeightPx",
                        "status": "UNDER_MIN_HEIGHT" if collapsed else "ok",
                        "measuredHeight": measured_height,
                        "minHeightPx": min_height_px,
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

            if min_share is not None:
                other_selector = min_share.get("selector")
                floor = min_share.get("floor")
                other = _rect_of(geo_elements, other_selector)
                measured_height = rect.get("h")
                other_height = (other or {}).get("h")
                if (measured_height is None or other is None or other_height is None
                        or floor is None or other_selector is None):
                    any_error = True
                    rows.append({"step": step, "selector": selector,
                                 "constraint": "minShareOfSelector", "status": "ERROR",
                                 "error": f"minShareOfSelector needs rect.h for both "
                                          f"{selector!r} and {other_selector!r}, plus a "
                                          f"declared floor"})
                elif other_height <= 0:
                    any_error = True
                    rows.append({"step": step, "selector": selector,
                                 "constraint": "minShareOfSelector", "status": "ERROR",
                                 "error": f"denominator {other_selector!r} has rect.h <= 0"})
                else:
                    share = measured_height / other_height
                    # Mirrors STARVED's px-tolerance allowance: the numerator may be up to
                    # tolerancePx short of the floor without failing (a sub-tolerance nudge
                    # is not a real regression).
                    under_share = (measured_height + tolerance_px) < floor * other_height
                    any_violation = any_violation or under_share
                    rows.append({
                        "step": step, "selector": selector, "constraint": "minShareOfSelector",
                        "status": "UNDER_SHARE" if under_share else "ok",
                        "measuredShare": round(share, 4),
                        "floor": floor,
                        "otherSelector": other_selector,
                        "measuredHeight": measured_height,
                        "otherHeight": other_height,
                        "tolerancePx": tolerance_px,
                    })

            if max_bottom_px is not None:
                y = rect.get("y")
                h = rect.get("h")
                if y is None or h is None:
                    any_error = True
                    rows.append({"step": step, "selector": selector,
                                 "constraint": "maxBottomPx", "status": "ERROR",
                                 "error": "captured geometry has no rect.y/rect.h"})
                else:
                    measured_bottom = y + h
                    clipped = measured_bottom > max_bottom_px + tolerance_px
                    any_violation = any_violation or clipped
                    rows.append({
                        "step": step, "selector": selector, "constraint": "maxBottomPx",
                        "status": "CLIPPED" if clipped else "ok",
                        "measuredBottom": measured_bottom,
                        "maxBottomPx": max_bottom_px,
                        "tolerancePx": tolerance_px,
                    })

        # --- Step-level checks (tempdoc 814 D7.2/D7.3): not per-element, read off the
        # whole capture doc rather than a single registered selector's rect. ---

        for sel in absent_selectors:
            present = _rect_of(geo_elements, sel) is not None
            any_violation = any_violation or present
            rows.append({
                "step": step, "selector": sel, "constraint": "absentSelectors",
                "status": "PRESENT_BUT_SHOULD_BE_ABSENT" if present else "ok",
            })

        for sel in required_selectors:
            present = _rect_of(geo_elements, sel) is not None
            any_violation = any_violation or not present
            rows.append({
                "step": step, "selector": sel, "constraint": "requiredSelectors",
                "status": "ok" if present else "MISSING_REQUIRED",
            })

        if max_scrollable is not None:
            scrollable_count = geo.get("scrollableCount")
            if scrollable_count is None:
                any_error = True
                rows.append({"step": step, "constraint": "maxScrollableRegions",
                             "status": "ERROR",
                             "error": "captured geometry has no scrollableCount "
                                      "(ui_measure.py geometry probe stale?)"})
            else:
                multi_scroll = scrollable_count > max_scrollable
                any_violation = any_violation or multi_scroll
                rows.append({
                    "step": step, "constraint": "maxScrollableRegions",
                    "status": "MULTI_SCROLL" if multi_scroll else "ok",
                    "scrollableCount": scrollable_count,
                    "maxScrollableRegions": max_scrollable,
                    "scrollableRegions": geo.get("scrollableRegions"),
                })

        # The anti-vacuity floor: without it, `maxScrollableRegions` on a 0-scroller capture
        # asserts nothing at all (closure-audit finding C).
        if min_scrollable is not None:
            scrollable_count = geo.get("scrollableCount")
            if scrollable_count is None:
                any_error = True
                rows.append({"step": step, "constraint": "minScrollableRegions",
                             "status": "ERROR",
                             "error": "captured geometry has no scrollableCount "
                                      "(ui_measure.py geometry probe stale?)"})
            else:
                no_scroller = scrollable_count < min_scrollable
                any_violation = any_violation or no_scroller
                rows.append({
                    "step": step, "constraint": "minScrollableRegions",
                    "status": "NO_SCROLLER" if no_scroller else "ok",
                    "scrollableCount": scrollable_count,
                    "minScrollableRegions": min_scrollable,
                    "scrollableRegions": geo.get("scrollableRegions"),
                })

        if status_facts_singleton:
            if not status_facts_register:
                any_error = True
                rows.append({"step": step, "constraint": "statusFactsSingleton",
                             "status": "ERROR",
                             "error": "governance/status-facts.v1.json is missing/empty — "
                                      "statusFactsSingleton has nothing to check against"})
            else:
                captured = {f.get("phrase"): f.get("count") for f in doc.get("statusFacts") or []}
                for phrase, max_renders in status_facts_register.items():
                    count = captured.get(phrase)
                    if count is None:
                        any_error = True
                        rows.append({"step": step, "constraint": "statusFactsSingleton",
                                     "status": "ERROR", "phrase": phrase,
                                     "error": "phrase missing from this capture's statusFacts "
                                              "(ui_measure.py probe stale or register drifted)"})
                        continue
                    dup = count > max_renders
                    any_violation = any_violation or dup
                    rows.append({
                        "step": step, "constraint": "statusFactsSingleton",
                        "status": "DUPLICATE_STATUS_FACT" if dup else "ok",
                        "phrase": phrase, "count": count, "maxPersistentRenders": max_renders,
                    })

        # The step-scoped copy assertion (814 §D5 chip-yield witness). Same probe, same
        # `statusFacts` list — a ceiling of 0 rather than the register's per-phrase ceiling.
        if forbidden_text:
            captured = {f.get("phrase"): f.get("count") for f in doc.get("statusFacts") or []}
            for phrase in forbidden_text:
                count = captured.get(phrase)
                if count is None:
                    any_error = True
                    rows.append({"step": step, "constraint": "forbiddenVisibleText",
                                 "status": "ERROR", "phrase": phrase,
                                 "error": "phrase missing from this capture's statusFacts "
                                          "(ui_measure.py does not union this step's "
                                          "forbiddenVisibleText into the probe?)"})
                    continue
                visible = count > 0
                any_violation = any_violation or visible
                rows.append({
                    "step": step, "constraint": "forbiddenVisibleText",
                    "status": "FORBIDDEN_TEXT_VISIBLE" if visible else "ok",
                    "phrase": phrase, "count": count,
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

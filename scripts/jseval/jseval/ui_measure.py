"""Structured measurement companion for ui-shot / ui-check (tempdoc 615 §6.2).

The tempdoc's thesis is *measurement-first*: capture FACTS alongside every
screenshot — the accessibility tree, axe WCAG violations, key-element
geometry/computed-style, overflow flags, and console errors — so a correctness
judgment can target the measurement instead of the pixels. The screenshot
demotes to a non-asserting gestalt attachment (§6.2 / P2).

Reuses, rather than reinvents:
- Playwright's NATIVE `page.accessibility.snapshot()` as the live perception
  channel (§6.3 "reuse the a11y substrate").
- the SAME axe-core bundle the Node e2e harness uses
  (`modules/ui-web/node_modules/axe-core/axe.min.js`), injected via
  `add_script_tag` — mirrors `e2e/ai-harness.ts` `checkAccessibility()`.
- the console-collector pattern from `e2e/ai-harness.ts` `setupConsoleCollector`.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any


def _find_axe() -> Path | None:
    """Locate the axe-core standalone bundle shipped in ui-web's node_modules."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        cand = parent / "modules" / "ui-web" / "node_modules" / "axe-core" / "axe.min.js"
        if cand.exists():
            return cand
    return None


_AXE_PATH = _find_axe()


def _find_a11y_baseline() -> dict[str, list[str]]:
    """Load the shared a11y baseline register (tempdoc 615 §13 Move 2): the ONE
    authority for per-surface known/accepted axe rules. Returns {uiShotStep ->
    [knownRuleId]}. Best-effort: an absent/garbled register yields an empty map
    (every violation then reports as a raw count, never crashes a capture)."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        cand = parent / "governance" / "ui-a11y-baseline.v1.json"
        if cand.exists():
            try:
                reg = json.loads(cand.read_text(encoding="utf-8"))
                return {
                    s["uiShotStep"]: list(s.get("knownRules") or [])
                    for s in reg.get("surfaces", [])
                    if s.get("uiShotStep")
                }
            except Exception:
                return {}
    return {}


_A11Y_BASELINE = _find_a11y_baseline()


def split_new_vs_known(
    axe_violations: list[dict[str, Any]], known: list[str] | None,
) -> tuple[list[str] | None, int | None]:
    """Baseline-relative split (tempdoc 615 §13 Move 2). Given the axe violations and
    a surface's accepted rule-id list, return ``(new_rule_ids, known_count)`` — the
    rule ids NOT in the baseline (the actionable signal) and how many were known.
    Returns ``(None, None)`` when ``known is None`` (the surface has no baseline →
    caller reports a raw count, makes no NEW/known claim). The ONE implementation
    shared by the capture summary AND the `ui-a11y-gate` (no second copy to drift)."""
    if known is None:
        return None, None
    known_set = set(known)
    new_ids = [v.get("id") for v in axe_violations if v.get("id") not in known_set]
    return new_ids, len(axe_violations) - len(new_ids)


# Console-error categorization (tempdoc 615 §12 fix #1 / §14 F1). In the default
# no-backend auto-serve mode the console is dominated by ENVIRONMENT noise — the
# Vite dev-serve proxies `/api/*` to whatever stack is running (or 502s if none),
# and HMR/dev tooling chatters. Those fire non-deterministically run-to-run and
# are NOT defects in the UI under test. Categorizing them lets the summary flag
# mean "a real JS error", not "the environment has no backend".
_ENV_NETWORK_MARKERS = (
    "failed to load resource",
    "net::err",
    "err_connection",
    "err_network",
    "the server responded with a status of",
    " 502",
    " 503",
    " 504",
    " 500 ",
    " 404",
    "/api/",
    "fetch failed",
    "networkerror",
)
_DEV_NOISE_MARKERS = (
    "[vite]",
    "[hmr]",
    "hot module",
    "websocket connection to 'ws",
    "react devtools",
)
# FE-emitted STRUCTURED errors that mention `/api/` but are REAL app-tier signals,
# not env noise — they must win over `_ENV_NETWORK_MARKERS`. `[WireContract]` is the
# parse-boundary (non-fail-open) schema-mismatch: in production it means backend
# drift, so an agent must see it. (Found dogfooding the route-mock experiment: empty
# stub fixtures tripped it, and it was being hidden as env because it contains `/api/`.)
_APP_MARKERS = (
    "[wirecontract]",
    "did not match the generated schema",
)
# Framework SELF-CHECK signals that are NOT authoritative ground truth (tempdoc 615 §43).
# `[jf-control] no accessible name` is jf-control's DEV-only heuristic: it fires on
# `!resolvedName() && !this.textContent` — but for a nested `jf-button` (which forwards a
# `<slot>` into jf-control) the control's own `textContent` is the EMPTY forwarded slot, so it
# FALSE-POSITIVES on the legitimate slot-text-only button pattern (Button.ts's own recommended
# form). The REAL accessible name flattens through the slots correctly (verified: "Load"/"Grant
# family" ARE named — accname slot-flatten + axe's `button-name` agree it is NOT a violation, §43).
# We demote it because it is an UNRELIABLE, false-positive-prone heuristic — NOT because full
# coverage is proven elsewhere (the §43 independent review's caveat: do not overclaim). Real
# nameless controls are still caught by (a) the build-time `controls-a11y` gate, which statically
# forbids a nameless top-level `<jf-control>`, and (b) axe's `button-name`/control-name on captured
# surfaces. Acknowledged residual: a nameless *interpolated* control on an *un-captured* surface or
# state is caught by neither — but that case was only ever "caught" by this same false-positive-prone
# self-check, so the right fix is strengthening the static gate / axe coverage, not trusting a noisy
# signal. Counting it as `app` pollutes `console_real` with phantoms (the §33 trust-pollution class,
# in a11y guise); so it is bucketed informational, excluded from `console_real`.
_SELFCHECK_MARKERS = (
    "[jf-control] no accessible name",
)


def _classify_console(err: dict[str, str]) -> str:
    """Bucket a console entry into 'framework-selfcheck' | 'env-network' | 'dev-noise' | 'app'.

    Only 'app' entries are genuine UI/contract defects worth flagging (counted in
    `console_real`). `pageerror` (uncaught JS) is 'app' unless it is plainly a failed
    backend fetch. 'framework-selfcheck' is an unreliable framework heuristic that a more
    authoritative tier (axe) supersedes — informational, NOT a real defect.
    """
    blob = ((err.get("text") or "") + " " + (err.get("location") or "")).lower()
    if any(m in blob for m in _APP_MARKERS):
        return "app"
    if any(m in blob for m in _SELFCHECK_MARKERS):
        return "framework-selfcheck"
    if any(m in blob for m in _DEV_NOISE_MARKERS):
        return "dev-noise"
    if any(m in blob for m in _ENV_NETWORK_MARKERS):
        return "env-network"
    return "app"


class ConsoleSink:
    """Collects console.error + pageerror over a page's lifetime.

    Port of `setupConsoleCollector` (modules/ui-web/e2e/ai-harness.ts:402). Attach
    once at page creation; the errors accumulate until the measurement is captured.
    """

    def __init__(self) -> None:
        self.errors: list[dict[str, str]] = []

    def attach(self, page) -> None:
        def _on_console(msg) -> None:
            try:
                if msg.type == "error":
                    loc = msg.location or {}
                    self.errors.append({
                        "type": "console.error",
                        "text": (msg.text or "")[:500],
                        "location": loc.get("url", "") if isinstance(loc, dict) else "",
                    })
            except Exception:
                pass

        def _on_pageerror(err) -> None:
            self.errors.append({"type": "pageerror", "text": str(err)[:500], "location": ""})

        page.on("console", _on_console)
        page.on("pageerror", _on_pageerror)


# The shell is a Lit web-components app: its landmarks/roles live inside SHADOW
# roots, which `document.querySelector` (light DOM) cannot see. These probes pierce
# shadow boundaries so the geometry + a11y facts reflect the real rendered UI.
_JS_DEEP = """
    const deepQuery = (selector, root) => {
        root = root || document;
        const hit = root.querySelector(selector);
        if (hit) return hit;
        for (const el of root.querySelectorAll('*')) {
            if (el.shadowRoot) { const f = deepQuery(selector, el.shadowRoot); if (f) return f; }
        }
        return null;
    };
    const deepAll = (root, acc, depth) => {
        root = root || document; acc = acc || []; depth = depth || 0;
        if (depth > 40) return acc;
        for (const el of root.querySelectorAll('*')) {
            acc.push(el);
            if (el.shadowRoot) deepAll(el.shadowRoot, acc, depth + 1);
        }
        return acc;
    };
"""

# Geometry + computed-style facts for a generic key set (landmarks, stage, rail,
# inspector, h1, the focused element) + document overflow flags. Shadow-piercing.
_JS_GEOMETRY = """() => {
""" + _JS_DEEP + """
    const pick = (el) => {
        if (!el) return null;
        const r = el.getBoundingClientRect();
        const cs = getComputedStyle(el);
        return {
            tag: el.tagName.toLowerCase(),
            role: el.getAttribute && el.getAttribute('role') || null,
            label: el.getAttribute && el.getAttribute('aria-label') || null,
            rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
            z: cs.zIndex, fontSize: cs.fontSize, display: cs.display,
        };
    };
    const sels = ['[role="banner"]','[role="navigation"]','[role="main"]','main','.zone-stage','h1',
                  '[data-surface-id]','jf-rail','jf-stage','jf-inspector-pane'];
    const elements = {};
    for (const s of sels) { const e = deepQuery(s); if (e) elements[s] = pick(e); }
    const de = document.documentElement;
    return {
        elements,
        focused: pick(document.activeElement),
        document: {
            scrollWidth: de.scrollWidth, clientWidth: de.clientWidth,
            scrollHeight: de.scrollHeight, clientHeight: de.clientHeight,
            overflowX: de.scrollWidth > de.clientWidth + 1,
            overflowY: de.scrollHeight > de.clientHeight + 1,
        },
    };
}"""

# Shadow-piercing a11y landmark/role/heading collection — the live "perception
# channel" (§6.3). Playwright's native `accessibility.snapshot()` is unreliable on
# this shadow-DOM app (returns None), so this is the authoritative a11y fact set.
_JS_A11Y = """() => {
""" + _JS_DEEP + """
    const out = [];
    const landmarkTags = new Set(['main','nav','header','footer','aside','section']);
    for (const el of deepAll()) {
        const tag = el.tagName.toLowerCase();
        const role = el.getAttribute && el.getAttribute('role');
        const heading = /^h[1-3]$/.test(tag);
        if (role || landmarkTags.has(tag) || heading) {
            out.push({
                tag, role: role || null,
                label: (el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('aria-current'))) || null,
                text: (el.textContent || '').trim().slice(0, 48),
            });
        }
    }
    return out;
}"""

_JS_AXE = """async () => {
    try {
        const res = await axe.run(document, { runOnly: ['wcag2a','wcag2aa','wcag21a','wcag21aa'] });
        return {
            passCount: res.passes.length,
            violations: res.violations.map(v => ({
                id: v.id, impact: v.impact, help: v.help,
                nodes: (v.nodes || []).slice(0, 5).map(n => ({
                    selector: (n.target || []).join(' '), summary: n.failureSummary,
                })),
            })),
        };
    } catch (e) { return { error: String(e) }; }
}"""


async def capture_measure(
    page, name: str, output_dir: Path, console_sink: ConsoleSink | None, *, theme: str = "dark",
) -> tuple[str, dict[str, Any]]:
    """Write ``<name>.measure.json`` next to the screenshot. Returns (path, summary).

    The summary is a compact, agent-readable fact sheet (no PNG needed for a
    correctness judgment). Never raises — measurement is best-effort and must not
    fail a capture.
    """
    # Authoritative a11y fact set: a shadow-piercing landmark/role/heading collection
    # (the native `accessibility.snapshot()` is unreliable on this shadow-DOM app).
    a11y_landmarks: list[dict[str, Any]] = []
    try:
        a11y_landmarks = await page.evaluate(_JS_A11Y) or []
    except Exception:
        pass
    # NB: Playwright's native `page.accessibility.snapshot()` returns None on this
    # shadow-DOM Lit app, so it was pure JSON bloat — dropped (§12 fix #6). The
    # shadow-piercing `a11y_landmarks` above IS the authoritative perception channel.

    geometry = None
    try:
        geometry = await page.evaluate(_JS_GEOMETRY)
    except Exception:
        pass

    axe: dict[str, Any] | None = None
    if _AXE_PATH is not None:
        try:
            await page.add_script_tag(path=str(_AXE_PATH))
            axe = await page.evaluate(_JS_AXE)
        except Exception as e:
            axe = {"error": str(e)[:200]}

    console_errors = list(console_sink.errors) if console_sink else []
    for e in console_errors:
        e["category"] = _classify_console(e)
    console_app = [e for e in console_errors if e["category"] == "app"]

    measure: dict[str, Any] = {
        "schema": "ui-measure.v1",
        "name": name,
        "url": page.url,
        "viewport": page.viewport_size or {},
        "theme": theme,
        "a11y_landmarks": a11y_landmarks,
        "axe": axe,
        "geometry": geometry,
        "console_errors": console_errors,
    }
    out = output_dir / f"{name}.measure.json"
    out.write_text(json.dumps(measure, indent=2) + "\n", encoding="utf-8")

    axe_v = (axe or {}).get("violations") or []
    serious = sum(1 for v in axe_v if v.get("impact") in ("serious", "critical"))
    # Baseline-relative split (§13 Move 2): a violation whose rule id is NOT in this
    # step's known/accepted set is NEW — the actionable signal. Steps with no baseline
    # entry (`known is None`) report a raw count, no NEW/known claim.
    axe_new, axe_known = split_new_vs_known(axe_v, _A11Y_BASELINE.get(name))
    doc = (geometry or {}).get("document", {}) if geometry else {}
    overflow = [ax for ax, key in (("x", "overflowX"), ("y", "overflowY")) if doc.get(key)]
    geo_els = len((geometry or {}).get("elements", {})) if geometry else 0
    summary = {
        "measure_path": str(out),
        "a11y_landmarks": len(a11y_landmarks),
        "geometry_elements": geo_els,
        "axe_violations": len(axe_v),
        "axe_serious": serious,
        # Baseline-relative (§13 Move 2): NEW rule ids vs this surface's accepted set,
        # and how many were known. Both None when the step has no baseline entry.
        "axe_new": axe_new,
        "axe_known": axe_known,
        "console_errors": len(console_errors),
        # Only 'app'-category console errors are real defects; env/dev noise
        # (no-backend 502s, HMR) is counted but NOT flagged (§12 #1 / §14 F1).
        "console_real": len(console_app),
        "console_env": len(console_errors) - len(console_app),
        "overflow": overflow or None,
        "flags": (
            ([f"console-real:{len(console_app)}"] if console_app else [])
            # With a baseline, the loud flag is NEW-only — known debt stays quiet even
            # if serious. Without a baseline (axe_new is None), fall back to axe-serious.
            + ([f"axe-NEW:{','.join(axe_new)}"] if axe_new else [])
            + ([f"axe-serious:{serious}"] if serious and axe_new is None else [])
        ),
    }
    return str(out), summary

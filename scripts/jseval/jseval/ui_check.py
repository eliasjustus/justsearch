"""UI screenshot check — captures and verifies UI states via Playwright.

Usage: ``python -m jseval ui-check [--ui-url URL] [--output-dir DIR]``

All screenshots are declared as Steps in a flat registry. Each step has:
- setup: async function to prepare the page state
- isolated: whether it needs its own browser (True) or shares one (False)
- depends_on: which step must succeed first (for shared-browser chains)
- required: whether failure affects the overall pass/fail
"""

from __future__ import annotations

import asyncio
import json
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlencode, urlparse, urlunparse

from . import ui_fixtures
from . import ui_measure
from . import ui_selectors as S


# ---------------------------------------------------------------------------
# App-mounted readiness gate  (tempdoc 615 §27 — the readiness half of the contract)
# ---------------------------------------------------------------------------
# HTTP-200 means the server accepts connections, NOT that the app mounted (615 §28 U4a:
# 200 in ~1s, a real mount needs up to 15s). The robust mount predicate is the rail
# button VISIBLE (615 §28 U5 — chrome-level, survives data corruption; `jf-shell`-in-DOM
# and state="attached" are too weak — the hydration gap). When the shell never mounts, we
# attribute the failure to the SERVE layer with the best-available reason (615 §28 U3),
# instead of letting a generic Playwright timeout read as a phantom "render-failed".

class AppNotMountedError(Exception):
    """The served app never mounted within the deadline. Carries the best-available reason."""


async def _await_app_ready(page, *, timeout_ms: int = 15_000) -> None:
    """Block until the app shell has mounted (rail button visible), else raise
    AppNotMountedError with the best-available reason (Vite stderr tail / error-overlay
    text / honest fallback). ONE gate, reused by every capture path."""
    try:
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(
            state="visible", timeout=timeout_ms)
        return
    except Exception:
        pass  # fall through to assemble the reason — never let the bare timeout escape

    secs = round(timeout_ms / 1000)
    # (1) Vite boot/compile errors land in the captured server stderr (615 §27 fail-loud).
    # GUARD (615 §34): only trust the server-info stderr when THIS page targets THAT server —
    # else an external `--ui-url` (or a stale server-info) would attach an unrelated server's
    # stderr as the reason. Compare the served port to the recorded port.
    stderr_tail = ""
    try:
        from . import ui_shot
        if ui_shot._SERVER_INFO_PATH.exists():
            info = json.loads(ui_shot._SERVER_INFO_PATH.read_text(encoding="utf-8"))
            try:
                page_port = urlparse(page.url).port
            except Exception:
                page_port = None
            # 615 §35: also require the server-info to be LIVE (pid alive) — a stale dead-pid
            # info that merely shares the page's port (e.g. an external --ui-url) must not
            # supply a misleading stderr tail.
            if (page_port is not None and page_port == info.get("port")
                    and ui_shot._pid_alive(info.get("pid"))):
                stderr_tail = ui_shot._tail_file(info.get("stderr_log", ""), 800)
    except Exception:
        stderr_tail = ""
    # (2) Vite's in-page error overlay (615 §28 U3 — confirmed channel, no false positive).
    overlay = None
    try:
        overlay = await page.evaluate(
            "() => { const o = document.querySelector('vite-error-overlay');"
            " return o ? (o.shadowRoot?.textContent || o.textContent || 'present').slice(0,400) : null; }")
    except Exception:
        overlay = None

    if stderr_tail:
        reason = f"app shell never mounted within {secs}s; vite stderr tail: {stderr_tail}"
    elif overlay:
        reason = f"app shell never mounted within {secs}s; vite error overlay: {overlay.strip()}"
    else:
        reason = (f"app shell never mounted within {secs}s; no Vite stderr or error overlay "
                  "captured (a server may be serving non-app content, or the bundle failed silently)")
    raise AppNotMountedError(reason)


# ---------------------------------------------------------------------------
# Data types
# ---------------------------------------------------------------------------

@dataclass
class ShotResult:
    """Result of a single screenshot capture."""
    name: str
    path: str | None = None
    ok: bool = False
    elapsed_ms: float = 0
    error: str | None = None
    required: bool = True
    # tempdoc 615 §6.2 — the structured-measurement companion (facts, not pixels).
    measure_path: str | None = None
    measure_summary: dict[str, Any] | None = None
    # Tempdoc 669 — set when `--record` captured a video spanning this step's chain replay.
    video_path: str | None = None


@dataclass
class EvalResult:
    """Top-level result of a UI eval run."""
    shots: list[ShotResult] = field(default_factory=list)
    output_dir: str | None = None
    elapsed_ms: float = 0

    @property
    def ok(self) -> bool:
        return all(s.ok for s in self.shots if s.required)

    @property
    def total_shots(self) -> int:
        return len(self.shots)

    @property
    def total_passed(self) -> int:
        return sum(1 for s in self.shots if s.ok)

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema": "ui-check.v1",
            "ok": self.ok,
            "elapsed_ms": round(self.elapsed_ms, 1),
            "output_dir": self.output_dir,
            "total_shots": self.total_shots,
            "total_passed": self.total_passed,
            "shots": [
                {
                    "name": s.name,
                    "ok": s.ok,
                    "required": s.required,
                    "elapsed_ms": round(s.elapsed_ms, 1),
                    **({"error": s.error} if s.error else {}),
                    **({"measure": s.measure_summary} if s.measure_summary else {}),
                }
                for s in self.shots
            ],
        }


# ---------------------------------------------------------------------------
# Step model
# ---------------------------------------------------------------------------

@dataclass
class Step:
    """A declarative screenshot capture step.

    isolated=False: runs in a shared browser context (sequential chain).
    isolated=True: launches its own browser (can run in parallel with others).
    """
    name: str
    setup: Callable[..., Awaitable[None]]
    required: bool = True
    depends_on: str | None = None
    isolated: bool = False
    # For isolated steps: browser config overrides
    color_scheme: str = "dark"
    init_scripts: list[str] = field(default_factory=list)


# ---------------------------------------------------------------------------
# Screenshot helpers
# ---------------------------------------------------------------------------

_JS_SCROLL_TO_TOP = """() => {
    const stage = document.querySelector('.zone-stage') || document.querySelector('main');
    if (!stage) return;
    let best = null;
    for (const el of stage.querySelectorAll('*')) {
        const oy = getComputedStyle(el).overflowY;
        if (oy !== 'auto' && oy !== 'scroll') continue;
        const d = (el.scrollHeight||0) - (el.clientHeight||0);
        if (d <= 1) continue;
        if (!best || (el.clientHeight||0) > best.s) best = { el, s: el.clientHeight||0 };
    }
    if (best?.el) best.el.scrollTop = 0;
}"""

_JS_GET_SCROLL_DELTA = """() => {
    const stage = document.querySelector('.zone-stage') || document.querySelector('main');
    if (!stage) return 0;
    let best = null;
    for (const el of stage.querySelectorAll('*')) {
        const oy = getComputedStyle(el).overflowY;
        if (oy !== 'auto' && oy !== 'scroll') continue;
        const d = (el.scrollHeight||0) - (el.clientHeight||0);
        if (d <= 1) continue;
        if (!best || (el.clientHeight||0) > best.s) best = { d, s: el.clientHeight||0 };
    }
    return best ? best.d : 0;
}"""


async def _screenshot(page, out_path: str, *, cooldown_ms: int = 250) -> bool:
    base_vp = page.viewport_size or {"width": 1280, "height": 720}
    resized = False
    try:
        await page.evaluate(_JS_SCROLL_TO_TOP)
        delta = await page.evaluate(_JS_GET_SCROLL_DELTA)
        if delta and delta > 1:
            h = min(base_vp["height"] + delta + 32, 4096)
            if h > base_vp["height"] + 1:
                await page.set_viewport_size({"width": base_vp["width"], "height": h})
                resized = True
                await asyncio.sleep(0.1)
        if cooldown_ms > 0:
            await asyncio.sleep(cooldown_ms / 1000)
        await page.screenshot(path=out_path, full_page=False)
        return True
    finally:
        if resized:
            await page.set_viewport_size(base_vp)
            await asyncio.sleep(0.1)


async def _capture_shot(
    page, name: str, output_dir: Path, *, cooldown_ms: int = 250,
    console_sink: "ui_measure.ConsoleSink | None" = None, measure: bool = True, theme: str = "dark",
) -> ShotResult:
    t0 = time.monotonic()
    out = str(output_dir / f"{name}.png")
    try:
        ok = await _screenshot(page, out, cooldown_ms=cooldown_ms)
        r = ShotResult(name=name, path=out, ok=ok, elapsed_ms=(time.monotonic() - t0) * 1000)
        # tempdoc 615 §6.2 — capture the measurement companion alongside the PNG so a correctness
        # judgment can target facts (a11y/axe/geometry/console), not pixels. Best-effort: never fails.
        if ok and measure:
            try:
                mp, ms = await ui_measure.capture_measure(
                    page, name, output_dir, console_sink, theme=theme,
                )
                r.measure_path, r.measure_summary = mp, ms
            except Exception as e:
                r.measure_summary = {"error": str(e)[:200]}
        return r
    except Exception as e:
        return ShotResult(name=name, ok=False, elapsed_ms=(time.monotonic() - t0) * 1000, error=str(e)[:200])


# ---------------------------------------------------------------------------
# Shared interaction helpers
# ---------------------------------------------------------------------------

def _demo_url(ui_url: str, **extra: str) -> str:
    parsed = urlparse(ui_url)
    params = parse_qs(parsed.query)
    params["demo"] = ["true"]
    for k, v in extra.items():
        params[k] = [v]
    return urlunparse(parsed._replace(query=urlencode(params, doseq=True)))


async def _type_and_search(page, query: str = "justsearch") -> None:
    # tempdoc 615 §6.1b: the live Lit shell lands on the chat surface, so navigate to the search
    # surface first (rail click, hash-route fallback) before reaching for the search input.
    await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(state="visible", timeout=15_000)
    try:
        await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.dispatch_event("click")
    except Exception:
        await page.evaluate("() => { location.hash = 'justsearch://surface/core.search-surface'; }")
    # tempdoc 615 §11 HARDEN: resolve the search input by accessible role+name first
    # (stable across testid churn), falling back to the testid.
    inp = await S.SEARCH_INPUT.locate(page)
    await inp.wait_for(state="visible", timeout=10_000)
    await inp.click()
    await inp.type(query, delay=30)
    await page.locator(S.CSS_SEARCH_RESULT_ROW).first.wait_for(state="visible", timeout=30_000)


async def _navigate_and_search(page, url: str, query: str = "justsearch", *, timeout_ms: int = 60_000) -> None:
    await page.goto(url, wait_until="networkidle", timeout=timeout_ms)
    await _type_and_search(page, query)


# ---------------------------------------------------------------------------
# Step registry — all screenshots declared here
# ---------------------------------------------------------------------------

def _build_steps(ui_url: str, cooldown_ms: int, timeout_ms: int) -> list[Step]:
    """Build the complete flat step list."""
    demo = _demo_url(ui_url)
    ai_init = "localStorage.setItem('justsearch-inspector-tab', 'ai');"

    # === Shared-browser chain (sequential, depends_on linkage) ===

    async def setup_search_results(page):
        await _type_and_search(page)

    async def setup_command_mode(page):
        inp = page.get_by_test_id(S.TID_SEARCH_INPUT)
        await inp.click()
        await inp.fill("/reindex")
        await page.get_by_test_id(S.TID_GLOBAL_COMMAND_CHROME).get_by_text("/reindex", exact=False).wait_for(state="visible", timeout=10_000)

    async def setup_chat_mode(page):
        await page.get_by_test_id(S.TID_SEARCH_INPUT).press("Escape")
        inp = page.get_by_test_id(S.TID_SEARCH_INPUT)
        await inp.wait_for(state="visible", timeout=10_000)
        await inp.click()
        await inp.fill("??")
        await page.locator(S.CSS_SEARCH_INPUT_TEXTAREA).wait_for(state="visible", timeout=10_000)

    async def setup_filters_chips(page):
        # tempdoc 615 §6.1b: live facets render as a `[data-testid=facet-row]` of `.facet-chip` buttons
        # (only when the response carries facet counts), NOT the retired filter-toggle + type dropdown.
        # Clicking a chip toggles it (`.facet-chip.selected`/`aria-pressed=true`) and re-submits. The
        # always-visible date `[data-testid=filter-row]` is the fallback subject when a query has no facets.
        await _type_and_search(page)
        facet = page.locator('[data-testid="facet-row"] .facet-chip').first
        try:
            await facet.wait_for(state="visible", timeout=10_000)
            await facet.click()
            await page.locator('.facet-chip.selected, .facet-chip[aria-pressed="true"]').first.wait_for(
                state="visible", timeout=10_000
            )
        except Exception:
            await page.locator('[data-testid="filter-row"]').wait_for(state="visible", timeout=5_000)

    async def setup_inspector_open(page):
        # tempdoc 615 §6.1b: live Lit opens the inspector by clicking a result ROW (SearchSurface
        # setSelected -> inspectorState -> chrome inspector pane), not the retired filter-toggle +
        # per-row checkbox. The search-results dependency has already populated the rows.
        row = page.locator(S.CSS_SEARCH_RESULT_ROW).first
        await row.wait_for(state="visible", timeout=30_000)
        await row.click(force=True)
        await page.locator(S.CSS_INSPECTOR_PANE).first.wait_for(state="visible", timeout=10_000)

    async def setup_multi_select(page):
        # tempdoc 615 §6.1b: live multi-select is plain-click (replace) + Ctrl/Cmd-click (toggle) on
        # result rows (SearchSurface.handleClick), reflected on the row as `[data-selected="true"]` /
        # `.row.selected`. No bulk-action bar in V1; the selection publishes to the inspector.
        rows = page.locator(S.CSS_SEARCH_RESULT_ROW)
        await rows.first.wait_for(state="visible", timeout=30_000)
        await rows.nth(0).click()
        await rows.nth(1).click(modifiers=["Control"])
        await rows.nth(2).click(modifiers=["Control"])
        # At least two rows now carry the selected marker.
        await page.locator('[data-testid="search-result-row"][data-selected="true"]').nth(1).wait_for(
            state="visible", timeout=10_000
        )

    async def setup_context_menu(page):
        # tempdoc 615 §6.1b: right-click a result row -> openContextMenu mounts the <jf-context-menu>
        # element (role=menu); the retired per-row checkbox + summarize-testid flow is gone.
        first = page.locator(S.CSS_SEARCH_RESULT_ROW).first
        await first.wait_for(state="visible", timeout=30_000)
        await first.scroll_into_view_if_needed()
        await first.hover()
        if cooldown_ms > 0:
            await asyncio.sleep(cooldown_ms / 1000)
        await first.click(button="right", force=True, timeout=5_000)
        # The <jf-context-menu> host can read as hidden until positioned; wait for its rendered menu
        # items (role=menuitem) — that is the visible content.
        await page.locator('jf-context-menu [role="menuitem"], jf-context-menu .menu').first.wait_for(
            state="visible", timeout=10_000
        )

    async def setup_streaming(page):
        # tempdoc 615 §6.1b: drive the REAL AI Q&A (live `/api/chat/agent` SSE stream), not the retired
        # demo "Summarize" simulation. Switch to the inspector's Ask tab, ask a question, submit with
        # Ctrl+Enter — `sendQuestion()` flips to the Answer tab and streams into <jf-markdown-block>.
        pane = page.locator(S.CSS_INSPECTOR_PANE)
        await pane.get_by_role("button", name="Ask", exact=True).click(timeout=10_000)
        ta = pane.get_by_placeholder("Ask a question about this file...")
        await ta.wait_for(state="visible", timeout=10_000)
        await ta.click()
        # tempdoc 615 §6.2: a retrieval-grounded prompt forces the agent to run a SEARCH tool (so
        # `done` attaches `sources`) and to ground its sentences in those passages (so the embedding
        # matcher emits `citations`) — both are required for inline `.cite-ref` marks to render.
        await ta.fill(
            "Search the indexed documents and summarize what this file is about, "
            "citing the specific sources you used."
        )
        await page.keyboard.press("Control+Enter")
        # Mid-stream: the Answer tab shows the "Thinking…" state or the streaming markdown block.
        await pane.locator("jf-markdown-block, .empty").first.wait_for(state="visible", timeout=20_000)
        await asyncio.sleep(0.25)

    async def setup_summarize_done(page):
        pane = page.locator(S.CSS_INSPECTOR_PANE)
        # Wait for the streamed answer to render. The markdown answer block replaces the "Thinking…"
        # placeholder once the first token arrives — that is the robust "answer started" signal. The
        # retrieval-grounded prompt runs a search-tool loop FIRST, so the answer can start late; allow a
        # very generous window (9B model + agent loop, possibly contended GPU).
        await pane.locator("jf-markdown-block").first.wait_for(state="visible", timeout=280_000)

    async def setup_citation(page):
        pane = page.locator(S.CSS_INSPECTOR_PANE)
        # tempdoc 615 §6.2: citations attach only on the `done` SSE (AFTER the stream completes) and only
        # when the answer grounded a source — `MarkdownBlock` renders `.cite-ref` marks only when
        # `!is-streaming` AND `citations` is non-empty. So wait for the markdown block to STOP streaming
        # (the `is-streaming` attribute clears), then for the mark (generous window for the 4s embedding
        # matcher); on success click it — it dispatches `citation-select`, routed to a preview-highlight.
        await pane.locator("jf-markdown-block").first.wait_for(state="visible", timeout=180_000)
        try:
            await pane.locator("jf-markdown-block:not([is-streaming])").first.wait_for(
                state="visible", timeout=180_000
            )
        except Exception:
            pass
        cite = pane.locator(S.CSS_CITATION_HIGHLIGHT).first
        try:
            await cite.wait_for(state="visible", timeout=20_000)
            # Keep the inline `[n]` mark in frame on the Answer tab (the citation render is the subject).
            # Clicking it dispatches `citation-select` and navigates to the source preview — that
            # highlight is exercised by the live `citation-select` path, but the screenshot here shows
            # the grounded answer WITH its inline citation marks.
            await cite.scroll_into_view_if_needed(timeout=5_000)
            await asyncio.sleep(0.3)
        except Exception:
            # Not every answer grounds a citation; the answer-source chips are the fallback. Capture
            # the answered state regardless.
            pass

    async def setup_skeleton(page):
        await page.goto(_demo_url(ui_url, e2e_view_delay_ms="4000"), wait_until="domcontentloaded", timeout=timeout_ms)
        if cooldown_ms > 0:
            await asyncio.sleep(cooldown_ms / 1000)
        await page.locator(S.rail_css(S.RAIL_SURFACE_LIBRARY)).click(timeout=5_000)
        await page.get_by_test_id(S.TID_SKELETON_LIBRARY).wait_for(state="visible", timeout=5_000)

    async def setup_snippets(page):
        await page.goto(demo, wait_until="domcontentloaded", timeout=timeout_ms)
        await _type_and_search(page)
        toggles = page.locator(f'[data-testid="{S.TID_RESULT_ROW_SNIPPET_TOGGLE}"]')
        try:
            await toggles.first.wait_for(state="visible", timeout=5_000)
            for i in range(min(2, await toggles.count())):
                await toggles.nth(i).click(force=True)
                if cooldown_ms > 0:
                    await asyncio.sleep(cooldown_ms / 1000)
        except Exception:
            pass

    async def setup_zero_results(page):
        await _type_and_search(page)
        await page.get_by_test_id(S.TID_SEARCH_INPUT).fill("zzz_no_results_xyz")
        await page.get_by_text("No results for", exact=False).wait_for(state="visible", timeout=10_000)

    async def setup_selection_preserved(page):
        # tempdoc 615 §6.1b: live selection is a row CLICK (no per-row checkbox). Select the first row,
        # re-search, and confirm a selected row persists across the new query.
        await _type_and_search(page)
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.click(force=True)
        await page.get_by_test_id(S.TID_SEARCH_INPUT).fill("justsearch")
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.wait_for(state="visible", timeout=30_000)

    # === Isolated steps (own browser each) ===

    def _view_setup(view_name: str, theme: str = "dark"):
        async def setup(page):
            base = view_name.replace("-advanced", "") if view_name.endswith("-advanced") else view_name
            surface_id = S.VIEWS.get(base)
            # tempdoc 615 §6.1b: the live Lit shell lands on the CHAT surface by default (not search,
            # as the retired React app did), so EVERY view step — including home/search — must navigate
            # to its rail surface first. Previously home/search were excluded on the stale assumption
            # that the app lands on search.
            if surface_id:
                # The bottom rail items (settings/help) sit under the first-run Walkthrough overlay
                # in demo mode, so a coordinate click hits the overlay, not the button. Dispatch the
                # click straight to the resolved button node — its `@click` handler navigates —
                # bypassing the overlay hit-test. Scroll first so the target is in the capture.
                # tempdoc 615 §6.1b: off-rail DEEPLINK surfaces (Health/Help) have no main-rail button,
                # so fall back to the shell's surface hash route (`#justsearch://surface/<id>`).
                try:
                    btn = page.locator(S.rail_css(surface_id))
                    await btn.scroll_into_view_if_needed(timeout=2_000)
                    await btn.dispatch_event("click")
                except Exception:
                    await page.evaluate(
                        "(id) => { location.hash = `justsearch://surface/${id}`; }", surface_id
                    )
                if cooldown_ms > 0:
                    await asyncio.sleep(cooldown_ms / 1000)
            if view_name == "ai-brain-advanced":
                b = page.get_by_test_id(S.TID_BRAIN_SWITCH_TO_ADVANCED)
                await b.wait_for(state="visible", timeout=10_000)
                await b.click(timeout=5_000)
                if cooldown_ms > 0:
                    await asyncio.sleep(cooldown_ms / 1000)
        return setup

    async def _goto_surface(page, surface_id: str):
        """Navigate to a surface via its rail button, with the surface-hash route as fallback."""
        try:
            btn = page.locator(S.rail_css(surface_id))
            await btn.scroll_into_view_if_needed(timeout=2_000)
            await btn.dispatch_event("click")
        except Exception:
            await page.evaluate("(id) => { location.hash = `justsearch://surface/${id}`; }", surface_id)

    _DENSITY_LABEL = {"compact": "Compact", "comfort": "Comfortable",
                      "comfortable": "Comfortable", "rich": "Spacious"}
    _MODE_LABEL = {"simple": "Simple", "advanced": "Advanced"}

    def _density_setup(density: str):
        async def setup(page):
            # tempdoc 615 §6.1b: density is a LIVE Settings control (the Accessibility section's
            # `button.option-btn` Compact/Comfortable/Spacious -> applyAdaptationProfile, persisted
            # server-side), not the retired `__JUSTSEARCH_STORES__` global. Set it in Settings, then search.
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(state="visible", timeout=15_000)
            await _goto_surface(page, S.RAIL_SURFACE_SETTINGS)
            # Density lives in the Accessibility section as an `option-btn` (Compact/Comfortable/Spacious);
            # the cards carry sub-labels, so match by leading text on the button class, not the full name.
            label = _DENSITY_LABEL.get(density, "Comfortable")
            btn = page.locator("button.option-btn", has_text=label)
            await btn.first.wait_for(state="visible", timeout=10_000)
            await btn.first.click(timeout=10_000)
            if cooldown_ms > 0:
                await asyncio.sleep(cooldown_ms / 1000)
            await _goto_surface(page, S.RAIL_SURFACE_SEARCH)
            await _type_and_search(page)
        return setup

    def _mode_setup(mode: str):
        async def setup(page):
            # tempdoc 615 §6.1b: UI mode is the live Settings Simple/Advanced `option-btn` (persists via
            # `/api/settings/v2` `ui.mode`), not the retired store + filter toggle. Set it, then search.
            await page.locator(S.rail_css(S.RAIL_SURFACE_SEARCH)).first.wait_for(state="visible", timeout=15_000)
            await _goto_surface(page, S.RAIL_SURFACE_SETTINGS)
            # The Simple/Advanced cards are `option-btn`s with sub-labels; match by leading text.
            btn = page.locator("button.option-btn", has_text=_MODE_LABEL.get(mode, "Simple"))
            await btn.first.wait_for(state="visible", timeout=10_000)
            await btn.first.click()
            if cooldown_ms > 0:
                await asyncio.sleep(cooldown_ms / 1000)
            await _goto_surface(page, S.RAIL_SURFACE_SEARCH)
            await _type_and_search(page)
        return setup

    def _cdp_setup(css_sel: str, pseudo: str):
        async def setup(page):
            await _type_and_search(page, "e")
            ctx = page.context
            cdp = await ctx.new_cdp_session(page)
            await cdp.send("DOM.enable")
            await cdp.send("CSS.enable")
            doc = await cdp.send("DOM.getDocument")
            r = await cdp.send("DOM.querySelector", {"nodeId": doc["root"]["nodeId"], "selector": css_sel})
            if r.get("nodeId", 0):
                await cdp.send("CSS.forcePseudoState", {"nodeId": r["nodeId"], "forcedPseudoClasses": [pseudo]})
                await asyncio.sleep(0.1)
        return setup

    def _inspector_setup(setup_fn):
        """Wrap an inspector setup to navigate + search + ensure AI tab."""
        async def setup(page):
            await setup_fn(page)
        return setup

    async def setup_qa(page):
        # tempdoc 615 §6.1b: live Q&A — open a result in the inspector (row click), ask a question on the
        # Ask tab, submit (Ctrl+Enter) and wait for the streamed answer (real `/api/chat/agent`).
        await page.goto(demo, wait_until="domcontentloaded", timeout=timeout_ms)
        await _type_and_search(page)
        await page.locator(S.CSS_SEARCH_RESULT_ROW).first.click(force=True)
        pane = page.locator(S.CSS_INSPECTOR_PANE)
        await pane.first.wait_for(state="visible", timeout=10_000)
        await pane.get_by_role("button", name="Ask", exact=True).click(timeout=10_000)
        ta = pane.get_by_placeholder("Ask a question about this file...")
        await ta.wait_for(state="visible", timeout=10_000)
        await ta.click()
        await ta.fill("What is this file about?")
        await page.keyboard.press("Control+Enter")
        await pane.locator("jf-markdown-block").first.wait_for(state="visible", timeout=180_000)

    async def setup_responsive(page):
        await page.goto(demo, wait_until="domcontentloaded", timeout=timeout_ms)
        await _type_and_search(page)
        await page.set_viewport_size({"width": 780, "height": 720})
        if cooldown_ms > 0:
            await asyncio.sleep(cooldown_ms / 1000)

    async def _shell_demo_goto(page):
        parsed = urlparse(ui_url)
        params = parse_qs(parsed.query)
        params["shell-demo"] = ["1"]
        demo_url = urlunparse(parsed._replace(query=urlencode(params, doseq=True)))
        await page.goto(demo_url, wait_until="domcontentloaded", timeout=timeout_ms)
        # Wait for the dock panel + at least one Lit pane to attach. We
        # use `state="attached"` rather than `visible` because Lumino's
        # initial CSS sets the dock to display:flex via class, but the
        # visibility check depends on layout — Playwright may flag the
        # element hidden during the first frame.
        await page.locator(".jf-shell-dock").wait_for(state="attached", timeout=15_000)
        await page.locator("jf-form").wait_for(state="attached", timeout=15_000)
        # Brief settle for first paint of Lumino's tab bar + Lit's first render.
        await asyncio.sleep(0.5)

    async def setup_shell_demo(page):
        # Bypass the React app via the ?shell-demo=1 branch in main.jsx.
        # The Lit shell mounts directly into #root. Visual verification
        # only — no interaction needed past the initial render (Form
        # pane is the default-active tab).
        await _shell_demo_goto(page)

    async def setup_shell_demo_status(page):
        # Click the Status tab so the screenshot shows the StatusCard
        # render rather than the default Form pane.
        await _shell_demo_goto(page)
        status_tab = page.locator(".lm-TabBar-tab", has_text="Status")
        await status_tab.click()
        await asyncio.sleep(0.3)

    async def setup_shell_demo_action(page):
        # Click the Action tab so the screenshot shows the
        # HIGH-risk ActionButton in its idle state.
        await _shell_demo_goto(page)
        action_tab = page.locator(".lm-TabBar-tab", has_text="Action")
        await action_tab.click()
        await asyncio.sleep(0.3)

    async def setup_shell_demo_table(page):
        # Click the Table tab so the screenshot shows the
        # schema-driven data grid with sortable columns.
        await _shell_demo_goto(page)
        table_tab = page.locator(".lm-TabBar-tab", has_text="Table")
        await table_tab.click()
        await asyncio.sleep(0.3)

    async def setup_presentation_demo(page):
        # 569 — the user-authored frontend demo via the ?presentation-demo=1 branch in main.jsx
        # (engine + §9 spike + interaction statechart + quarantine-to-default). No backend needed.
        parsed = urlparse(ui_url)
        params = parse_qs(parsed.query)
        params["presentation-demo"] = ["1"]
        demo_url = urlunparse(parsed._replace(query=urlencode(params, doseq=True)))
        await page.goto(demo_url, wait_until="domcontentloaded", timeout=timeout_ms)
        await page.locator("jf-declared-surface").first.wait_for(state="attached", timeout=15_000)
        await asyncio.sleep(0.6)

    async def setup_presentation_demo_statechart(page):
        # Drive the Move-8 interaction statechart: guard ON, REQUEST → CONFIRM → state 'done'
        # with the named effects logged. Scroll the section into the viewport for the shot.
        await setup_presentation_demo(page)
        await page.locator("#sc-typed").check()
        await page.get_by_role("button", name="REQUEST", exact=True).click()
        await page.get_by_role("button", name="CONFIRM", exact=True).click()
        await page.locator("#sc-journal").scroll_into_view_if_needed()
        await asyncio.sleep(0.4)

    async def setup_presentation_demo_quarantine(page):
        # Force a runtime contrast failure so the region quarantines to the built-in (Move 6).
        await setup_presentation_demo(page)
        await page.get_by_role("button", name="Force runtime contrast failure").click()
        await page.locator("#q-status").scroll_into_view_if_needed()
        await asyncio.sleep(0.4)

    async def setup_presentation_demo_authoring(page):
        # Authoring origin + anti-spoof: apply an authored skin, then show the trusted channel is
        # unrepresentable (a declaration mounting jf-authorization-host is REJECTED by the gate).
        await setup_presentation_demo(page)
        await page.get_by_role("button", name="Apply a valid authored skin").click()
        await page.get_by_role("button", name="Try to mount the trusted dialog").click()
        await page.locator("#auth-msg").scroll_into_view_if_needed()
        await asyncio.sleep(0.4)

    async def setup_presentation_demo_editor(page):
        # The in-UI authoring editor: type/paste a declaration → Certify & apply → renders live.
        await setup_presentation_demo(page)
        await page.get_by_role("button", name="Certify & apply", exact=True).click()
        await page.locator("#authoring-msg").scroll_into_view_if_needed()
        await asyncio.sleep(0.4)

    async def setup_presentation_demo_llm(page):
        # The LOCAL-LLM-EMITTED SKIN, applied live. This JSON is VERBATIM from a live on-device model
        # run (Llama-3.1-8B / cuda12): a "cool oceanic" theme it authored from the closed token vocab
        # (one dropped closing brace repaired — free chat is not grammar-constrained; /api/chat/extract
        # is). Pasted into the in-UI editor → Certify & apply → the engine recolors the page = the
        # model's skin rendering live.
        await setup_presentation_demo(page)
        ocean = (
            '{"schemaVersion":1,"id":"llm.ocean","displayName":"Oceanic Theme",'
            '"theme":{"tokens":{"accent-tint":"#03A9F4","surface-1":"#2F4F4F","surface-2":"#2F4F4F",'
            '"text-primary":"#FFFFFF","text-secondary":"#C5C5C5"}}}'
        )
        await page.fill("#authoring-editor", ocean)
        await page.get_by_role("button", name="Certify & apply", exact=True).click()
        await asyncio.sleep(0.5)
        await page.evaluate("window.scrollTo(0, 0)")  # show the recoloured page top
        await asyncio.sleep(0.3)

    async def setup_presentation_demo_liveness(page):
        # 569 §14 — the last two co-projected facets (Move 3): the LIVENESS readout (engine derives
        # the live tri-state from the one observed-state authority) + the OVERFLOW strip (engine
        # clips the trailing tail via OverflowController). Section 7 of the demo.
        await setup_presentation_demo(page)
        await page.get_by_text("Co-projected liveness + overflow").scroll_into_view_if_needed()
        # Allow the observed-state poll to complete so the readout reflects the live backend.
        await asyncio.sleep(3.0)

    async def setup_presentation_demo_required(page):
        # 569 §14 — mandatory-region visibility: a present-but-hidden required region (carrying
        # visibleWhen) is quarantined to the default layout. Surface the gate verdict. Section 8.
        await setup_presentation_demo(page)
        await page.get_by_role(
            "button", name="Apply a layout that hides the required region"
        ).click()
        await page.locator("#mr-status").scroll_into_view_if_needed()
        await asyncio.sleep(0.4)

    async def setup_presentation_demo_appearance(page):
        # 569 §14 — behaviour as operating mode (Move 8): the APPEARANCE_FLOW statechart restyles
        # the page live. Click "Light" (a native button, role=button — not the section-1 radios) →
        # the page recolours + the Effect Journal increments. Section 9.
        await setup_presentation_demo(page)
        await page.get_by_role("button", name="Light", exact=True).click()
        await page.locator("#ap-journal").scroll_into_view_if_needed()
        await asyncio.sleep(0.5)

    async def setup_presentation_demo_library(page):
        # 569 §14 — the Library rollout: the indexed-folder cards rendered through the engine (the
        # 2nd real surface). Scroll section 10 into view. Section 10.
        await setup_presentation_demo(page)
        await page.get_by_text("The Library rendered through the engine").scroll_into_view_if_needed()
        await asyncio.sleep(0.4)

    async def setup_presentation_demo_ceremony(page):
        # 569 §15 — the BRANCHING, guarded delete-confirm ceremony (Move 8): REQUEST → confirming;
        # CONFIRM is BLOCKED by the `typed == true` guard until "DELETE" is typed; then CONFIRM → done
        # firing the journaled toast. Section 11.
        await setup_presentation_demo(page)
        await page.get_by_role("button", name="REQUEST delete", exact=True).click()
        await page.get_by_role("button", name="CONFIRM delete", exact=True).click()  # blocked (empty)
        await page.fill("#ce-typed", "DELETE")
        await page.get_by_role("button", name="CONFIRM delete", exact=True).click()  # guard passes → done
        await page.locator("#ce-journal").scroll_into_view_if_needed()
        await asyncio.sleep(0.4)

    async def setup_presentation_demo_surfaces(page):
        # 569 §15 — more real surfaces inverted: the declared Help reference (shortcuts table + lists)
        # and the Health stats (metric cards + overflow strip) rendered through the engine. Section 12.
        # Scroll to the Health stats region (heading "Index": the metric cards + the overflow strip).
        await setup_presentation_demo(page)
        await page.get_by_role("heading", name="Index", exact=True).scroll_into_view_if_needed()
        await asyncio.sleep(0.5)

    views = ["home", "search", "library", "ai-brain", "ai-brain-advanced", "health", "settings", "security", "help"]

    return [
        # --- Shared-browser chain (demo flow) ---
        Step("search-results",       setup=setup_search_results),
        Step("command-mode",         setup=setup_command_mode,       depends_on="search-results"),
        Step("chat-mode",            setup=setup_chat_mode,          depends_on="search-results"),
        Step("filters-chips",        setup=setup_filters_chips,      depends_on="search-results"),
        Step("inspector-open",       setup=setup_inspector_open,     depends_on="search-results"),
        Step("multi-select",         setup=setup_multi_select,       depends_on="search-results"),
        Step("context-menu",         setup=setup_context_menu,       depends_on="search-results"),
        Step("streaming",            setup=setup_streaming,          depends_on="inspector-open"),
        Step("summarize-done",       setup=setup_summarize_done,     depends_on="streaming"),
        Step("citation-highlight",   setup=setup_citation,           depends_on="summarize-done"),
        # tempdoc 615 §6.1b: the React "Action Panel" has NO shell-v0 equivalent (the command palette
        # `core.command-palette` replaced it as a COMMAND-mode surface). The action-panel / -open /
        # -filtered steps are retired rather than repointed.

        # --- Isolated: main views (dark + light) ---
        *[Step(f"{v}", setup=_view_setup(v), isolated=True) for v in views],
        *[Step(f"{v}-light", setup=_view_setup(v, "light"), isolated=True, color_scheme="light") for v in views],

        # --- Isolated: density/mode variants ---
        Step("search-results-light",   setup=_density_setup("comfort"), isolated=True, color_scheme="light"),
        Step("search-results-compact", setup=_density_setup("compact"), isolated=True),
        Step("search-results-rich",    setup=_density_setup("rich"),    isolated=True),
        Step("search-simple-mode",     setup=_mode_setup("simple"),     isolated=True),
        Step("search-advanced-mode",   setup=_mode_setup("advanced"),   isolated=True),

        # --- Isolated: steps that navigate to fresh URLs ---
        Step("skeleton-library",     setup=setup_skeleton,           isolated=True),
        # context-near-limit / context-too-large retired (615 §6.1b): the React-era inspector
        # context-budget pill has no shell-v0 equivalent.
        Step("snippets-expanded",    setup=setup_snippets,           isolated=True),
        Step("zero-results",         setup=setup_zero_results,       isolated=True),
        Step("selection-preserved",  setup=setup_selection_preserved, isolated=True),

        # --- Isolated: CDP pseudo-states ---
        Step("row-hover",    setup=_cdp_setup(S.CSS_SEARCH_RESULT_ROW, "hover"), isolated=True),
        Step("input-focus",  setup=_cdp_setup(S.CSS_SEARCH_INPUT, "focus"),      isolated=True),
        # button-active retired (615 §6.1b): the inspector summarize button (React testid) is gone; the
        # live Ask flow has no equivalent always-present button to force :active on.

        # --- Isolated: inspector edge cases ---
        # error-retryable / context-details-expanded retired (615 §6.1b): demo-error injection
        # (`demo_error`) is inert and the React context-details panel has no shell-v0 equivalent.
        Step("qa-response",              setup=setup_qa,              isolated=True, init_scripts=[ai_init]),
        Step("responsive-collapsed",     setup=setup_responsive,      isolated=True),
        # action-panel-open / action-panel-filtered retired (615 §6.1b) — no shell-v0 equivalent.

        # --- Slice 3a.1 Phase 6: Lit shell-v0 visual verification ---
        # Mounts the standalone shell demo (Lumino DockPanel + Lit panes)
        # via the `?shell-demo=1` query branch in main.jsx. Bypasses the
        # React app entirely. See modules/ui-web/src/shell-v0/demo/.
        Step("shell-v0-demo",         setup=setup_shell_demo,        isolated=True, required=False),
        Step("shell-v0-demo-status",  setup=setup_shell_demo_status, isolated=True, required=False),
        Step("shell-v0-demo-action",  setup=setup_shell_demo_action, isolated=True, required=False),
        Step("shell-v0-demo-table",   setup=setup_shell_demo_table,  isolated=True, required=False),

        # --- 569: the user-authored frontend demo ---
        Step("presentation-demo",            setup=setup_presentation_demo,            isolated=True, required=False),
        Step("presentation-demo-statechart", setup=setup_presentation_demo_statechart, isolated=True, required=False),
        Step("presentation-demo-quarantine", setup=setup_presentation_demo_quarantine, isolated=True, required=False),
        Step("presentation-demo-authoring",  setup=setup_presentation_demo_authoring,  isolated=True, required=False),
        Step("presentation-demo-editor",     setup=setup_presentation_demo_editor,     isolated=True, required=False),
        Step("presentation-demo-llm",        setup=setup_presentation_demo_llm,        isolated=True, required=False),
        Step("presentation-demo-liveness",   setup=setup_presentation_demo_liveness,   isolated=True, required=False),
        Step("presentation-demo-required",   setup=setup_presentation_demo_required,   isolated=True, required=False),
        Step("presentation-demo-appearance", setup=setup_presentation_demo_appearance, isolated=True, required=False),
        Step("presentation-demo-library",    setup=setup_presentation_demo_library,    isolated=True, required=False),
        Step("presentation-demo-ceremony",   setup=setup_presentation_demo_ceremony,   isolated=True, required=False),
        Step("presentation-demo-surfaces",   setup=setup_presentation_demo_surfaces,   isolated=True, required=False),
    ]


# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------

async def _run_shared_steps(
    steps: list[Step], page, output_dir: Path, *, cooldown_ms: int, deadline: float | None,
    console_sink: "ui_measure.ConsoleSink | None" = None, measure: bool = True,
    trace_target: str | None = None,
) -> list[ShotResult]:
    """Run shared-browser steps sequentially with dependency tracking.

    trace_target (tempdoc 615 §11 TRACE): if set, snapshot a PRE measure right before
    that step's interaction and write its {pre, post} trajectory delta — so a chain
    step like inspector-open records what its row-click changed, not just the end state.
    """
    shots: list[ShotResult] = []
    completed: set[str] = set()

    for step in steps:
        if deadline and time.monotonic() >= deadline:
            shots.append(ShotResult(name=step.name, ok=False, error="deadline_exceeded", required=step.required))
            continue
        if step.depends_on and step.depends_on not in completed:
            shots.append(ShotResult(name=step.name, ok=False, error=f"skipped: dependency '{step.depends_on}' failed", required=step.required))
            continue
        try:
            if measure and trace_target and step.name == trace_target:
                try:
                    await ui_measure.capture_measure(page, f"{step.name}.pre", output_dir, None)
                except Exception:
                    pass
            await step.setup(page)
            r = await _capture_shot(
                page, step.name, output_dir, cooldown_ms=cooldown_ms,
                console_sink=console_sink, measure=measure,
            )
            r.required = step.required
            shots.append(r)
            if r.ok:
                completed.add(step.name)
                if measure and trace_target and step.name == trace_target and r.measure_path:
                    _write_trace(step.name, output_dir)
        except Exception as e:
            shots.append(ShotResult(name=step.name, ok=False, error=str(e)[:200], required=step.required))
    return shots


def _write_trace(name: str, output_dir: Path) -> None:
    """TRACE (tempdoc 615 §11): write `<name>.trace.json` = the {pre, post} interaction
    delta, reusing the DIFF engine over the two measure captures the step produced."""
    from . import ui_diff
    pre_p = output_dir / f"{name}.pre.measure.json"
    post_p = output_dir / f"{name}.measure.json"
    if not (pre_p.exists() and post_p.exists()):
        return
    pre = json.loads(pre_p.read_text(encoding="utf-8"))
    post = json.loads(post_p.read_text(encoding="utf-8"))
    trace = {
        "schema": "ui-trace.v1",
        "name": name,
        "pre_url": pre.get("url"),
        "post_url": post.get("url"),
        "delta": ui_diff.diff_measures(pre, post),
    }
    (output_dir / f"{name}.trace.json").write_text(json.dumps(trace, indent=2) + "\n", encoding="utf-8")


async def _run_isolated_step(
    step: Step, ui_url: str, output_dir: Path, *, demo: bool, cooldown_ms: int, timeout_ms: int,
    playwright_module, measure: bool = True, fixtures: bool = False, trace: bool = False,
) -> ShotResult:
    """Run a single isolated step in its own browser.

    fixtures=True (tempdoc 615 §13 Move 1 / §16) installs the deterministic
    route-mock + walkthrough seed: every `/api/*` is served a schema-valid fixture
    so the no-backend 502 storm cannot occur and the capture is byte-stable. Use it
    for STRUCTURAL steps (a11y/layout); leave it off for the AI-chain steps, which
    need a real model.
    """
    t0 = time.monotonic()
    try:
        browser = await playwright_module.chromium.launch(headless=True, args=["--disable-gpu"])
        try:
            ctx = await browser.new_context(
                viewport={"width": 1280, "height": 720},
                color_scheme=step.color_scheme,
            )
            if fixtures:
                await ui_fixtures.install_fixtures(ctx)
            for script in step.init_scripts:
                await ctx.add_init_script(script)
            page = await ctx.new_page()
            # tempdoc 615 §6.2 — collect console.error/pageerror over the step's lifetime for the
            # measurement companion (attach before navigation so boot errors are captured).
            console_sink = ui_measure.ConsoleSink() if measure else None
            if console_sink:
                console_sink.attach(page)
            url = _demo_url(ui_url, theme=step.color_scheme) if demo else ui_url
            await page.goto(url, wait_until="domcontentloaded", timeout=timeout_ms)
            # tempdoc 615 §27 readiness gate: block until the app shell mounted (rail visible),
            # else raise AppNotMountedError with the serve-layer reason (Vite stderr / overlay) —
            # so a never-mount reads as "cannot capture: <reason>", not a phantom render-failed.
            await _await_app_ready(page)
            # tempdoc 615 §11 TRACE: capture a PRE snapshot before the step's interaction
            # trajectory, so the {pre, post} delta records what the flow changed (network/
            # console/layout shift), not just the at-rest end state.
            if trace and measure:
                try:
                    await ui_measure.capture_measure(page, f"{step.name}.pre", output_dir, None, theme=step.color_scheme)
                except Exception:
                    pass
            await step.setup(page)
            r = await _capture_shot(
                page, step.name, output_dir, cooldown_ms=cooldown_ms,
                console_sink=console_sink, measure=measure, theme=step.color_scheme,
            )
            r.required = step.required
            if trace and measure and r.measure_path:
                _write_trace(step.name, output_dir)
            await ctx.close()
            return r
        finally:
            await browser.close()
    except AppNotMountedError as e:
        # tempdoc 615 §27 — loud, attributed serve-layer failure (full reason, not truncated).
        return ShotResult(name=step.name, ok=False, elapsed_ms=(time.monotonic() - t0) * 1000,
                          error=f"cannot capture '{step.name}': {e}", required=step.required)
    except Exception as e:
        return ShotResult(name=step.name, ok=False, elapsed_ms=(time.monotonic() - t0) * 1000, error=str(e)[:200], required=step.required)


# ---------------------------------------------------------------------------
# Orchestrator
# ---------------------------------------------------------------------------

async def _run_eval(ui_url: str, output_dir: Path, *, demo: bool = True, cooldown_ms: int = 250, timeout_ms: int = 120_000, measure: bool = True) -> EvalResult:
    try:
        from playwright.async_api import async_playwright
    except ImportError:
        raise RuntimeError("playwright not installed. Install with: pip install playwright && playwright install chromium")

    output_dir.mkdir(parents=True, exist_ok=True)
    result = EvalResult(output_dir=str(output_dir))
    t0 = time.monotonic()
    deadline = t0 + timeout_ms / 1000

    all_steps = _build_steps(ui_url, cooldown_ms, timeout_ms)
    shared = [s for s in all_steps if not s.isolated]
    isolated = [s for s in all_steps if s.isolated]

    base_url = _demo_url(ui_url) if demo else ui_url

    async with async_playwright() as p:
        # Run shared-browser chain first (sequential, one browser)
        browser = await p.chromium.launch(headless=True, args=["--disable-gpu"])
        try:
            ctx = await browser.new_context(viewport={"width": 1280, "height": 720})
            await ctx.add_init_script("localStorage.setItem('justsearch-inspector-tab', 'ai');")
            page = await ctx.new_page()
            console_sink = ui_measure.ConsoleSink() if measure else None
            if console_sink:
                console_sink.attach(page)
            await page.goto(base_url, wait_until="domcontentloaded", timeout=timeout_ms)
            # tempdoc 615 §27 readiness gate: if the shell never mounts, the whole shared chain
            # is moot — fail every shared step with the serve-layer reason rather than letting
            # each setup time out into an opaque "render-failed".
            try:
                await _await_app_ready(page)
                shared_shots = await _run_shared_steps(
                    shared, page, output_dir, cooldown_ms=cooldown_ms, deadline=deadline,
                    console_sink=console_sink, measure=measure,
                )
            except AppNotMountedError as e:
                shared_shots = [ShotResult(name=s.name, ok=False,
                                           error=f"cannot capture '{s.name}': {e}",
                                           required=s.required) for s in shared]
            result.shots.extend(shared_shots)
            await ctx.close()
        finally:
            await browser.close()

        # Run isolated steps in parallel (bounded concurrency)
        sem = asyncio.Semaphore(4)
        async def bounded(step: Step) -> ShotResult:
            async with sem:
                return await _run_isolated_step(step, ui_url, output_dir, demo=demo, cooldown_ms=cooldown_ms, timeout_ms=timeout_ms, playwright_module=p, measure=measure)

        isolated_results = await asyncio.gather(*[bounded(s) for s in isolated], return_exceptions=True)
        for r in isolated_results:
            if isinstance(r, ShotResult):
                result.shots.append(r)
            else:
                result.shots.append(ShotResult(name="unknown", ok=False, error=str(r)[:200]))

    result.elapsed_ms = (time.monotonic() - t0) * 1000

    # Baseline comparison — detect changed screenshots via file size
    baseline_path = output_dir.parent / "baseline.json"
    drift: list[dict] = []
    sizes: dict[str, int] = {}
    for s in result.shots:
        if s.ok and s.path:
            p = Path(s.path)
            if p.exists():
                sizes[s.name] = p.stat().st_size
    if baseline_path.exists():
        try:
            baseline = json.loads(baseline_path.read_text())
            for name, new_size in sizes.items():
                old_size = baseline.get(name)
                if old_size is None:
                    drift.append({"name": name, "change": "new"})
                elif abs(new_size - old_size) / max(old_size, 1) > 0.10:
                    drift.append({"name": name, "change": "size_drift",
                                  "old_bytes": old_size, "new_bytes": new_size})
            for name in baseline:
                if name not in sizes:
                    drift.append({"name": name, "change": "missing"})
        except Exception:
            pass

    result_dict = result.to_dict()
    if sizes:
        result_dict["file_sizes"] = sizes
    if drift:
        result_dict["drift"] = drift

    (output_dir / "ui-eval.json").write_text(json.dumps(result_dict, indent=2) + "\n")
    # Save current sizes as baseline for next run
    baseline_path.write_text(json.dumps(sizes, indent=2) + "\n")

    return result


def execute_ui_check(
    ui_url: str = "http://localhost:5173",
    *,
    output_dir: str | None = None,
    demo: bool = True,
    cooldown_ms: int = 250,
    timeout_ms: int = 120_000,
    measure: bool = True,
) -> dict:
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    base = Path(output_dir) if output_dir else Path("tmp/ui-check")
    out = base / ts
    return asyncio.run(_run_eval(ui_url, out, demo=demo, cooldown_ms=cooldown_ms, timeout_ms=timeout_ms, measure=measure)).to_dict()


def format_console(result: dict) -> str:
    lines = []
    ok = result.get("ok", False)
    lines.append(f"UI Check: {'PASS' if ok else 'FAIL'} ({result['total_passed']}/{result['total_shots']} screenshots)")
    lines.append(f"Elapsed: {result['elapsed_ms']:.0f}ms  Output: {result.get('output_dir', 'N/A')}")
    lines.append("")
    for s in result.get("shots", []):
        mark = "+" if s["ok"] else "x"
        req = "" if s.get("required", True) else " (optional)"
        line = f"  [{mark}] {s['name']}{req} ({s['elapsed_ms']:.0f}ms)"
        if s.get("error"):
            line += f" — {s['error'][:80]}"
        lines.append(line)
    lines.append("")

    drift = result.get("drift", [])
    if drift:
        lines.append(f"Drift detected ({len(drift)} screenshots changed):")
        for d in drift:
            if d["change"] == "new":
                lines.append(f"  [NEW] {d['name']}")
            elif d["change"] == "missing":
                lines.append(f"  [GONE] {d['name']}")
            elif d["change"] == "size_drift":
                lines.append(f"  [CHANGED] {d['name']} ({d['old_bytes']}B → {d['new_bytes']}B)")
        lines.append("")
    return "\n".join(lines)

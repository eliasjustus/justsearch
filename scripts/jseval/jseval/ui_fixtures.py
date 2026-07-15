"""Deterministic route-mock fixtures for ui-shot / ui-check (tempdoc 615 §13 Move 1 / §16).

The §16 experiment proved a deterministic, zero-env-noise, byte-stable capture is
achievable by intercepting `/api/*` and serving SCHEMA-VALID fixtures — no backend,
no app-level demo-mode rebuild. This module promotes that proof into a reusable,
OPT-IN harness primitive (`install_fixtures`), enabled per-run via `--fixtures`.

Scope: the deterministic STRUCTURAL steps (a11y / layout / contrast facts of the
views). It is deliberately NOT for the AI-chain steps (streaming / summarize /
citation), which need a real model — those stay live (run WITHOUT `--fixtures`).

Two traps the experiment found, encoded here so they can't recur:
- The FE parse boundary is NON-fail-open: an empty `{}` is WORSE than a 502 — it
  fails the generated-schema parse and the shell never mounts. So boot-critical
  contracts get schema-valid bodies (the captured `__fixtures__/*-live.json` for
  status/search/settings; minimal-valid EMPTY catalogs for the registry endpoints).
- A glob `**/api/**` over-matches the FE's own `/src/api/*.ts` Vite modules; the
  matcher MUST be a path predicate (`path == '/api' or startswith('/api/')`).
"""
from __future__ import annotations

import json
from pathlib import Path
from urllib.parse import urlparse

def _find_fixtures_dir() -> Path:
    """Locate `modules/ui-web/src/api/__fixtures__` by walking up to the repo root
    (robust to the file's nesting depth — mirrors ui_measure._find_axe)."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        cand = parent / "modules" / "ui-web" / "src" / "api" / "__fixtures__"
        if cand.exists():
            return cand
    raise FileNotFoundError("ui-web __fixtures__ directory not found from " + str(here))


_FIX_DIR = _find_fixtures_dir()


def _load(name: str) -> str:
    return (_FIX_DIR / name).read_text(encoding="utf-8")


# Captured, schema-valid live payloads for the boot-critical (non-fail-open) contracts.
_BODY_STATUS = _load("status-response-live.json")
_BODY_SEARCH = _load("search-response-live.json")
_BODY_SETTINGS = _load("settings-v2-live.json")


def _empty_catalog(primitive: str) -> str:
    """Minimal schema-valid EMPTY registry catalog (shape per types/registry.ts +
    types/diagnostic.ts). An empty `entries` is valid and content-free, so it cannot
    drift — only a schema-key change would, which the FE contract tests already catch."""
    return json.dumps({
        "schemaVersion": "1.0.0",
        "catalogVersion": 0,
        "namespace": "core",
        "primitive": primitive,
        "entries": [],
    })


# The Library substrate list: a thin {items, count} envelope around IndexedRootView
# (LibrarySurface.ts:62 listResponseSchema, `.loose()`). An empty list is the schema-valid
# "no folders configured" state — the SAME minimal-empty principle as the registry catalogs.
# This endpoint is non-fail-open (parseWireContract), so the unmapped `{}` it used to get tripped
# the parse and logged `[WireContract] contract drift` — a fixtures gap masquerading as an app
# error (tempdoc 615 §33). Mapping it un-pollutes the `console_real` trust signal; the
# fixture-coverage clause of check-ui-step-coverage keeps the next such endpoint from drifting
# silently (615 §37.1).
_BODY_INDEXED_ROOTS = json.dumps({"items": [], "count": 0})


# Path substring -> fixture body. First match wins. `/api/status`, `/api/knowledge/search`,
# `/api/inference/status`, and `/api/settings` are NOT here — all four have a per-variant
# transform and are dispatched explicitly in `fixture_body()` before this table is consulted.
_ROUTES: tuple[tuple[str, str], ...] = (
    ("/api/indexing-roots/substrate", _BODY_INDEXED_ROOTS),
    ("/api/registry/operations", _empty_catalog("Operation")),
    ("/api/registry/resources", _empty_catalog("Resource")),
    ("/api/registry/diagnostic-channels", _empty_catalog("DiagnosticChannel")),
)

# Seed: dismiss the first-run 'welcome' walkthrough (id per canonicalManifest.ts) so
# the overlay never clutters the deterministic capture, and pin the inspector tab.
WALKTHROUGH_SEED = (
    "try {"
    "localStorage.setItem('justsearch-inspector-tab','ai');"
    "localStorage.setItem('justsearch.userState.v2', JSON.stringify({"
    "  version: 2, activeProfileId: 'default', profiles: {},"
    "  walkthroughState: { welcome: { activeStepIndex: 0, completedStepIds: [], dismissed: true } }"
    "}));"
    "} catch (e) {}"
)


def is_api_path(url: str) -> bool:
    """True for the REST root only — NOT the FE's own `/src/api/*.ts` Vite modules."""
    path = urlparse(url).path
    return path == "/api" or path.startswith("/api/")


# Data-extreme variants for the GENERATE fuzzer (tempdoc 615 §11 GENERATE). The
# "data-extreme" axis becomes a fixture transform — not a backend state — because the
# whole point of route-mock is that data is a deterministic fixture. Minimal-viable set;
# add `huge`/`long-names`/`error` here as the set grows.
#
# NOTE (tempdoc 697 activation): `degraded` (the `_status_body` transform below) is
# DELIBERATELY NOT added here. `VARIANTS` is consumed ONLY by the GENERATE fuzzer
# (`ui_fuzz.py`), which crosses it with {viewport x theme} as a data-extreme axis for the
# search surface — adding `degraded` here would silently add a fuzzer cell. `degraded` is
# reachable only via an explicit `install_fixtures(ctx, variant="degraded")` call, made by
# the isolated `chat-proportion` ui-shot step alone (`ui_check.py`'s `Step.fixtures_variant`).
VARIANTS = ("default", "empty")


def _search_body(variant: str) -> str:
    """The search response for a variant. 'empty' = the zero-results edge state."""
    if variant == "empty":
        d = json.loads(_BODY_SEARCH)
        d["results"] = []
        d["totalHits"] = 0
        d["matchCount"] = 0
        return json.dumps(d)
    return _BODY_SEARCH


def _status_body(variant: str) -> str:
    """The status response for a variant. 'degraded' (tempdoc 697 activation) flips
    `readiness.composites.retrieval` to DEGRADED with a real `LifecycleReasonCode`
    (`worker.health.embedding_not_ready` — LifecycleReasonCode.java:29) so the chat
    window's collapsed degradation pill (`.degradation-banner-collapsed`,
    UnifiedChatView.renderCollapsedDegradationBanner) renders deterministically. The
    reason code carries 'warn' severity (verdict.ts severityForCodes), not 'error', so
    severity alone does not force the banner open (UnifiedChatView.ts:2123
    `forcedExpanded = isAdvancedMode() || verdict.severity === 'error'` — the other half,
    Simple-mode disclosure, is `_settings_body`'s job below). Also bumps
    `worker.core.indexedDocuments` off zero: LIVE-VERIFIED (headless
    probe) that `availability.ts:110-125`'s `no_documents` gate — not just AI-online —
    pins the composer's Ask/Delegate escalation to plain search (askPinned() reads
    `projectAvailability('documents', aiState)`, which short-circuits to `unavailable` on a
    zero document count regardless of AI capability); with docs > 0 the SAME projection
    instead returns `{kind:'degraded', caveat}` off this step's own degraded verdict
    (availability.ts:134-143), which does NOT pin Ask. NOT a fuzzer axis — see the
    `VARIANTS` note above."""
    if variant == "degraded":
        d = json.loads(_BODY_STATUS)
        d["readiness"]["composites"]["retrieval"] = {
            "state": "DEGRADED",
            "reasonCodes": ["worker.health.embedding_not_ready"],
        }
        d["worker"]["core"]["indexedDocuments"] = 1
        return json.dumps(d)
    return _BODY_STATUS


def _inference_body(variant: str) -> str:
    """The `/api/inference/status` response for a variant (tempdoc 697 activation).
    LIVE-VERIFIED (headless probe): this endpoint is UNMAPPED for every other variant
    (falls through to `fixture_body`'s generic `{}`), which reads as `available: undefined`
    -> `aiStateStore.computeCapabilities().chat = false` -> the composer's Ask/Delegate
    escalation is pinned to plain search (`UnifiedChatView.askPinned()` via
    `availability.ts:104-106`) for EVERY existing structural step under `--fixtures` — the
    correct behavior for those (AI genuinely offline with no dev stack). 'degraded' reports
    the model ONLINE so the `chat-proportion` step can actually submit a turn (escalateAsk()
    -> send()). Kept variant-gated (NOT added to `_ROUTES`) so no other step's rendering
    changes."""
    if variant == "degraded":
        return json.dumps({
            "mode": "online",
            "available": True,
            "starting": False,
            "embeddingQueueSize": 0,
            "vduQueueSize": 0,
            "llmContextTokens": 4096,
            "configuredContextTokens": 4096,
            "tier": "default",
            "activeModelId": "fixture-model",
            "generation": 1,
            "lastStartupDurationMs": 1000,
            "gpu": {"cudaAvailable": True, "totalVramBytes": 8_000_000_000, "vramDescription": "8 GB"},
        })
    return "{}"


def _settings_body(variant: str) -> str:
    """The `/api/settings` response for a variant (tempdoc 697 activation). The captured
    `_BODY_SETTINGS` fixture carries `ui.mode: "advanced"` (whatever the live capture's
    disclosure was set to at capture time). LIVE-VERIFIED (headless probe): Advanced mode
    force-expands the chat window's degradation banner regardless of severity
    (UnifiedChatView.ts:2123 `forcedExpanded = isAdvancedMode() || verdict.severity ===
    'error'`), so `.degradation-banner-collapsed` never renders under the captured default —
    only the wider expanded `.degradation-banner` form does. 'degraded' flips `ui.mode` to
    "simple" so the collapsed pill (the element this ratchet tracks) renders. Every other
    variant keeps the captured `advanced` default unchanged (no other step reads disclosure
    mode from its screenshot)."""
    if variant == "degraded":
        d = json.loads(_BODY_SETTINGS)
        d["ui"]["mode"] = "simple"
        return json.dumps(d)
    return _BODY_SETTINGS


def fixture_body(url: str, variant: str = "default") -> str:
    """The deterministic body for a given /api URL under a data variant. Unmapped
    endpoints get an empty object (the structural steps don't depend on their contents)."""
    if "/api/inference/status" in url:
        return _inference_body(variant)
    if "/api/status" in url:
        return _status_body(variant)
    if "/api/settings" in url:
        return _settings_body(variant)
    if "/api/knowledge/search" in url:
        return _search_body(variant)
    for needle, body in _ROUTES:
        if needle in url:
            return body
    return "{}"


async def install_fixtures(ctx, variant: str = "default") -> None:
    """Make a browser context deterministic: seed the dismissed walkthrough and
    serve fixtures for every `/api/*` call (so the no-backend 502 storm can't occur).
    ``variant`` selects a per-route transform: `_search_body` (GENERATE data-extreme,
    'empty') and `_status_body` (readiness state, 'degraded' — tempdoc 697) both key off
    the same ``variant`` string. Call once on a fresh context, before `new_page`."""
    await ctx.add_init_script(WALKTHROUGH_SEED)

    async def _handler(route):
        req = route.request
        accept = req.headers.get("accept") or ""
        if "/stream" in req.url or "text/event-stream" in accept:
            await route.fulfill(status=200, content_type="text/event-stream", body="")
            return
        await route.fulfill(status=200, content_type="application/json",
                            body=fixture_body(req.url, variant))

    await ctx.route(lambda url: is_api_path(url), _handler)

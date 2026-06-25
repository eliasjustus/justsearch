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


# Path substring -> fixture body. First match wins.
_ROUTES: tuple[tuple[str, str], ...] = (
    ("/api/status", _BODY_STATUS),
    ("/api/knowledge/search", _BODY_SEARCH),
    ("/api/settings", _BODY_SETTINGS),
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


def fixture_body(url: str, variant: str = "default") -> str:
    """The deterministic body for a given /api URL under a data variant. Unmapped
    endpoints get an empty object (the structural steps don't depend on their contents)."""
    if "/api/knowledge/search" in url:
        return _search_body(variant)
    for needle, body in _ROUTES:
        if needle in url:
            return body
    return "{}"


async def install_fixtures(ctx, variant: str = "default") -> None:
    """Make a browser context deterministic: seed the dismissed walkthrough and
    serve fixtures for every `/api/*` call (so the no-backend 502 storm can't occur).
    ``variant`` selects a data-extreme transform (GENERATE). Call once on a fresh
    context, before `new_page`."""
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

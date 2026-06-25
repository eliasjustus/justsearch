"""Tests for the deterministic route-mock fixtures (tempdoc 615 §13 Move 1 / §16).

These pin the two experiment-found traps (the non-fail-open parse boundary needs
schema-valid bodies; the matcher must not catch the FE's own /src/api modules) and
the registry-catalog required shape, so a future edit can't silently reintroduce
the 502-storm / boot-break / module-load failure.
"""
from __future__ import annotations

import json

from jseval import ui_fixtures


class TestApiPathPredicate:
    def test_rest_root_matches(self):
        assert ui_fixtures.is_api_path("http://localhost:5174/api/status")
        assert ui_fixtures.is_api_path("http://localhost:5174/api/registry/operations")
        assert ui_fixtures.is_api_path("http://localhost:5174/api")

    def test_fe_source_modules_do_NOT_match(self):
        # The experiment's run-2 bug: a glob `**/api/**` served these as JSON and
        # broke module loading. The path predicate must exclude the source tree.
        assert not ui_fixtures.is_api_path("http://localhost:5174/src/api/http.ts")
        assert not ui_fixtures.is_api_path("http://localhost:5174/src/api/domains/status.ts")
        assert not ui_fixtures.is_api_path("http://localhost:5174/node_modules/.vite/deps/api.js")


class TestFixtureBodies:
    def test_boot_critical_bodies_are_valid_json_objects(self):
        for needle in ("/api/status", "/api/knowledge/search", "/api/settings"):
            body = json.loads(ui_fixtures.fixture_body(f"http://x{needle}"))
            assert isinstance(body, dict) and body, f"{needle} fixture is empty/non-object"

    def test_registry_catalogs_have_required_schema_keys(self):
        # operationCatalogSchema / resourceCatalogSchema / diagnosticChannelCatalogSchema
        # all require these keys (types/registry.ts, types/diagnostic.ts). A bare {} fails
        # the non-fail-open parse boundary — these minimal-valid catalogs must not.
        cases = {
            "/api/registry/operations": "Operation",
            "/api/registry/resources": "Resource",
            "/api/registry/diagnostic-channels": "DiagnosticChannel",
        }
        for needle, primitive in cases.items():
            body = json.loads(ui_fixtures.fixture_body(f"http://x{needle}"))
            assert body["primitive"] == primitive
            for key in ("schemaVersion", "catalogVersion", "namespace", "entries"):
                assert key in body, f"{needle} missing required {key}"
            assert body["entries"] == []

    def test_unmapped_api_path_gets_empty_object(self):
        assert ui_fixtures.fixture_body("http://x/api/something/unmapped") == "{}"

    def test_indexed_roots_substrate_is_mapped_and_schema_valid(self):
        # Tempdoc 615 §33/§37.1: LibrarySurface strict-parses /api/indexing-roots/substrate
        # (non-fail-open parseWireContract, listResponseSchema {items, count?}). Before the fix it
        # was unmapped → {} → tripped the parse → logged [WireContract] drift → a fixtures gap that
        # ui_measure tagged `app` (a false bug). The mapped body must be a schema-valid empty list,
        # so `library --fixtures` renders the real empty-roots state with a clean console.
        body = json.loads(ui_fixtures.fixture_body("http://x/api/indexing-roots/substrate"))
        assert body == {"items": [], "count": 0}

    def test_walkthrough_seed_dismisses_welcome(self):
        assert "welcome" in ui_fixtures.WALKTHROUGH_SEED
        assert "dismissed: true" in ui_fixtures.WALKTHROUGH_SEED

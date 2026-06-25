"""Regression tests for the ui-measure console classifier (tempdoc 615 §12 #1 / §14 F1).

The classifier's failure mode is a SILENT WRONG VALUE: if it mis-buckets the
no-backend 502 noise as a real defect (or vice-versa), the summary `console-real`
flag lies and the agent learns to ignore it — exactly the dogfood finding §14
set out to fix. These pin the buckets so that can't regress.
"""

from __future__ import annotations

from jseval.ui_measure import _classify_console, _find_a11y_baseline


class TestClassifyConsole:
    def test_no_backend_502_is_env(self):
        # The dominant noise in default no-backend auto-serve mode.
        err = {
            "type": "console.error",
            "text": "Failed to load resource: the server responded with a status of 502 (Bad Gateway)",
            "location": "http://127.0.0.1:5174/api/status",
        }
        assert _classify_console(err) == "env-network"

    def test_api_fetch_failure_is_env(self):
        err = {"type": "console.error", "text": "GET http://127.0.0.1:5174/api/indexing-jobs/stream net::ERR_FAILED", "location": ""}
        assert _classify_console(err) == "env-network"

    def test_vite_hmr_is_dev_noise(self):
        err = {"type": "console.error", "text": "[vite] failed to connect to websocket", "location": ""}
        assert _classify_console(err) == "dev-noise"

    def test_real_js_console_error_is_app(self):
        err = {"type": "console.error", "text": "Uncaught TypeError: Cannot read properties of undefined (reading 'foo')", "location": "jf-search-surface.ts:42"}
        assert _classify_console(err) == "app"

    def test_pageerror_is_app(self):
        err = {"type": "pageerror", "text": "ReferenceError: x is not defined", "location": ""}
        assert _classify_console(err) == "app"

    def test_pageerror_that_is_a_fetch_failure_is_env(self):
        # A thrown fetch rejection against /api is still environment, not a UI defect.
        err = {"type": "pageerror", "text": "TypeError: fetch failed for /api/health", "location": ""}
        assert _classify_console(err) == "env-network"

    def test_wirecontract_mismatch_is_app_not_env(self):
        # A WireContract schema mismatch mentions /api/ but is a REAL contract signal
        # (backend drift), NOT env noise — it must win over the env-network marker.
        # (Regression for the bug the route-mock experiment surfaced.)
        err = {
            "type": "console.error",
            "text": "[WireContract] GET /api/registry/operations did not match the generated schema (contract drift)",
            "location": "",
        }
        assert _classify_console(err) == "app"

    def test_empty_entry_defaults_to_app(self):
        # Unknown/empty → conservative 'app' so a genuine error is never silently dropped.
        assert _classify_console({"type": "console.error", "text": "", "location": ""}) == "app"

    def test_jf_control_selfcheck_is_not_app(self):
        # Tempdoc 615 §43: jf-control's DEV self-check FALSE-POSITIVES on the nested slot-text-only
        # jf-button pattern (its own textContent is the empty forwarded <slot>), while the REAL
        # accessible name flattens correctly and axe does NOT flag button-name. It is an unreliable,
        # false-positive-prone heuristic, so it must NOT count as a real ('app') defect — else it
        # pollutes console_real with phantoms (the §33 trust-pollution class). Real nameless controls
        # remain caught by the static controls-a11y gate + axe on captured surfaces (§43 review caveat:
        # we demote because it's noisy, not because coverage is proven complete).
        err = {
            "type": "console.error",
            "text": "[jf-control] no accessible name — set `operation-id`, a non-empty `label`, or slot text "
                    "(559 Authority V §11: a nameless control is unrepresentable through the primitive).",
            "location": "",
        }
        assert _classify_console(err) == "framework-selfcheck"

    def test_wirecontract_still_wins_over_selfcheck(self):
        # A genuine app-tier signal must still classify 'app' (the selfcheck demotion is narrow).
        err = {"type": "console.error",
               "text": "[WireContract] GET /api/x did not match the generated schema", "location": ""}
        assert _classify_console(err) == "app"


class TestA11yBaseline:
    """The shared baseline register (§13 Move 2) — the loader maps ui-shot steps to
    their known/accepted axe rules so the summary can flag NEW-vs-known."""

    def test_loads_register_keyed_by_uishot_step(self):
        base = _find_a11y_baseline()
        assert isinstance(base, dict)
        # The structural view steps must be present (a missing 'home' would silently
        # drop the NEW-vs-known signal for the search surface).
        for step in ("home", "library", "settings", "health", "ai-brain", "help"):
            assert step in base, f"baseline missing ui-shot step '{step}'"
            assert isinstance(base[step], list)

    def test_known_rules_are_rule_id_strings(self):
        base = _find_a11y_baseline()
        for step, rules in base.items():
            for r in rules:
                assert isinstance(r, str) and r, f"{step} has a non-string knownRule"

    def test_unmapped_step_is_absent(self):
        # A step with no baseline entry must be ABSENT (→ raw-count path, no false
        # 'all-known' claim), not present-with-empty.
        base = _find_a11y_baseline()
        assert "inspector-open" not in base

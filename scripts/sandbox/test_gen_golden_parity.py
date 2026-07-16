#!/usr/bin/env python3
"""Self-tests for gen_golden_parity.py's corpus-comparability precondition
(tempdoc 734 finding 5) against synthetic fixtures — no dev stack needed.

Run: python scripts/sandbox/test_gen_golden_parity.py
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from gen_golden_parity import (  # noqa: E402
    HELP_DOC_PROBE_FILENAME,
    HELP_DOC_PROBE_QUERY,
    check_help_docs_indexed,
)


def _search_response(filenames: list[str]) -> dict:
    return {"results": [{"fields": {"filename": name}} for name in filenames]}


class HelpDocsIndexedPreconditionTests(unittest.TestCase):
    """Live-verified 2026-07-16: a fresh (non-eval) dev-stack boot always
    auto-ingests SSOT/docs/help/*.md before any user ingest; jseval's eval
    mode explicitly skips this. A golden-parity baseline generated against a
    stack that skipped it is not corpus-comparable to any real Sandbox
    candidate — this precondition mirrors the existing fingerprint/compat
    gates in `generate()` to catch that at generation time, not at finalize."""

    def test_help_docs_present_passes(self):
        with patch(
            "gen_golden_parity.post_search",
            return_value=_search_response([HELP_DOC_PROBE_FILENAME, "other.txt"]),
        ) as mock_search:
            result = check_help_docs_indexed("http://127.0.0.1:1")
        self.assertIsNone(result)
        mock_search.assert_called_once()
        self.assertEqual(mock_search.call_args.args[1], HELP_DOC_PROBE_QUERY)

    def test_help_docs_absent_fails_closed(self):
        with patch(
            "gen_golden_parity.post_search",
            return_value=_search_response(["unrelated.txt"]),
        ):
            result = check_help_docs_indexed("http://127.0.0.1:1")
        self.assertIsNotNone(result)
        self.assertIn("REFUSING", result)
        self.assertIn(HELP_DOC_PROBE_FILENAME, result)
        self.assertIn("eval mode", result)

    def test_empty_results_fails_closed(self):
        with patch("gen_golden_parity.post_search", return_value={"results": []}):
            result = check_help_docs_indexed("http://127.0.0.1:1")
        self.assertIsNotNone(result)
        self.assertIn("REFUSING", result)

    def test_unreachable_backend_fails_closed(self):
        import urllib.error

        with patch(
            "gen_golden_parity.post_search",
            side_effect=urllib.error.URLError("connection refused"),
        ):
            result = check_help_docs_indexed("http://127.0.0.1:1")
        self.assertIsNotNone(result)
        self.assertIn("could not query", result)


if __name__ == "__main__":
    unittest.main()

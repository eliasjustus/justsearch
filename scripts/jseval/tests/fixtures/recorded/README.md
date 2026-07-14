# Recorded delivery-tier fixtures (tempdoc 735 G4/W3)

These fixtures are a version-stamped snapshot of what the MCP surface's raw
`ToolResultBlock.content` looks like at the boundary the Claude Code CLI
hands to the model — the layer the tempdoc 735 delivery-tier probe (2026-07-14)
found is NOT what `McpToolSurface`/`McpEvidenceProjection` authors, once a
tool response carries `structuredContent` (the CLI serializes `structuredContent`
as the delivered `content` string; the human-readable text tier is dropped).

**Stamped against:** Claude Code CLI 2.1.209, JustSearch tool-surface 0.3.1,
recorded 2026-07-14 (session 25f8ac5d, tempdoc 735 W2/W3 increment).

**NOT contracts.** These are observed/reconstructed shapes at a point in time,
not a schema JustSearch or the CLI is bound to reproduce. The delivery
behavior itself is undocumented upstream and has silently changed before
(anthropics/claude-code#9962 — a prior flip from text-preferred to
structured-preferred between CLI v2.0.10-2.0.22; see tempdoc 735's research
pass). Treat every fixture here the way tempdoc 735's design treats the
underlying behavior: an observed-and-versioned fact, not a stable guarantee.

## Per-fixture provenance

Each fixture file below carries its own `provenance` field with one of two
values:

- `"recorded"` — captured verbatim (after path/identifier redaction) from a
  real Claude Code CLI 2.1.209 session against the live 0.3.1 stack.
- `"reconstructed-from-source, not recorded"` — no raw capture of this exact
  shape survived on disk from this session's probes (the executor v2 harness
  keeps tool-result content in-process only; the committed Inspect eval logs
  and the redacted `tool_result_digests` capture never persist raw content —
  see `jseval/agent_utility_inspect.py`'s `_content_sha256`/`_content_text`
  doc comments). These fixtures are instead built field-for-field from the
  producing Java source (cited per file, with `file:line`) and, where a JUnit
  test already exercises that exact code path with concrete values
  (`McpEvidenceProjectionTest.java`), those verified values are reused
  verbatim so the reconstruction is source-faithful, not invented. The one
  literal fragment that IS a genuine capture — the tempdoc 735 debug probe's
  quoted content prefix `{"citations":[{"parentDocId":...`
  (`docs/tempdocs/735-agent-surface-seam-consolidation.md:197`) — corroborates
  the shape of `justsearch_answer_structured.json` below.

| File | Tool | delivered_tier | Provenance |
|---|---|---|---|
| `justsearch_answer_structured.json` | `justsearch_answer` | `structured-json` | recorded (SDK capture via Claude Agent SDK, tool-surface 0.4.0, 2026-07-14) |
| `justsearch_search_structured.json` | `justsearch_search` | `structured-json` | recorded (SDK capture via Claude Agent SDK, tool-surface 0.4.0, 2026-07-14) |
| `justsearch_status_blocks.json` | `justsearch_status` | `blocks` | recorded (SDK capture, CLI via Agent SDK, surface 0.4.0, 2026-07-14) |
| `sdk_block_list.json` | (generic, any tool with no `structuredContent`) | `blocks` | reconstructed-from-source, not recorded |

## Refresh procedure

These fixtures age with the CLI/SDK/surface versions named above. To refresh:

1. Start the dev stack (`justsearch_dev_start` or `jseval run --start-backend`)
   and confirm the MCP HTTP endpoint is reachable (default
   `http://127.0.0.1:33221/mcp`, or pass `--base-url`).
2. Run `python scripts/jseval/experiments/delivery_tier_probe_735.py
   --write-fixtures` from the repo root (or `python
   experiments/delivery_tier_probe_735.py --write-fixtures` from
   `scripts/jseval/`). It calls `justsearch_answer`, `justsearch_search`, and
   `justsearch_status` against the live endpoint, classifies each result's
   delivered tier with the SAME function this test suite imports
   (`jseval.agent_utility_inspect._delivered_tier` — not a duplicate), and
   (with `--write-fixtures`) overwrites the fixture files in this directory
   with freshly captured content (path-redacted post-capture by the probe's `_sanitize_content_for_fixture` (absolute
   drive-letter paths collapse to `<redacted-root>/<last-two-segments>` on every
   `--write-fixtures` run; redaction changes content byte-length vs the true delivery,
   so fixtures are shape/field evidence, not length evidence)) and a `"provenance": "recorded"` stamp plus updated CLI/surface
   version fields.
3. Update this README's "Stamped against" line and the per-file provenance
   table to match what was actually captured.
4. The `sdk_block_list.json` fixture has no live producer in this codebase
   today (no in-repo tool responds without `structuredContent` AND returns a
   multi-block list) — it stays `reconstructed-from-source` until one does;
   `--write-fixtures` does not touch it.

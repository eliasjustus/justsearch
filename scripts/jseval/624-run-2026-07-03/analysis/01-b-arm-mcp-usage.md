# 01 — B-arm MCP usage taxonomy (agent report, 2026-07-03, verbatim)

**Headline finding (measured, verified 3 independent ways): zero MCP tool invocations occurred in any
of the 260 certified B cells (130 EN + 130 DE).** The pooled null (Δacc −0.027, p=0.476) is not
"agents tried it and it didn't help" — it is "agents never tried it."

## Verification of the zero-adoption claim

| Check | Method | EN-B | DE-B |
|---|---|---|---|
| Named-tool scan | Counter over every `metadata.tool_calls[].tool` across all 130 samples | `{Bash, Grep, Read, PowerShell, Glob}` only | same 5 names only |
| Raw substring scan | `"mcp__"` (Claude Code's mandatory prefix for any MCP-server tool) over the entire raw log JSON text | 0 occurrences | 0 occurrences |
| Completion-text scan | `"mcp"` / `"search tool"` keyword scan over `output.completion` (260 final answers) | 0 mentions | 0 mentions |

The capture pipeline itself is not broken — it faithfully recorded 928 Bash / 996 Grep / 966 Read /
103 PowerShell / 25 Glob calls in EN-B and 959/467/689/154/22 in DE-B, so an MCP call would have been
captured had one occurred. `disallowed_tool_calls` and `leak_suspect_tool_calls` are also empty in
every B sample (measured) — this isn't the CLI blocking attempted MCP calls either; none were
attempted.

## Adoption

| Corpus | Cells | Cells w/ ≥1 MCP call | MCP calls total | File-tool calls total |
|---|---|---|---|---|
| EN-B | 130 | **0** | 0 | 3,018 (mean 23.2/cell) |
| DE-B | 130 | **0** | 0 | 2,291 (mean 17.6/cell) |
| Pooled | 260 | **0 (0.0%)** | 0 | 5,309 |

## Ordering / strategy

Not applicable to MCP. For the file-tool-only sequences: first action was Bash in 89.2% of cells
(232/260) and Glob in 10.8% (28/260), identically split 116/14 per corpus — typically `ls`/`find` on
the corpus dir. No cell contains an MCP call anywhere in its sequence.

## Query formulation

No MCP input dicts exist to extract query strings from. (Grep patterns are hop-chaining in style —
e.g. sample q0/EN-B: `"upper wetlands.*power station"` → `"upper wetlands"` → `"wetlands"` →
`"power station"` → Read a matched file → `"first installation|installation"` — but that is Grep
pattern refinement against the filesystem, not MCP query formulation.)

## Correlate usage with outcome

Only one usage bucket exists — 100% of B cells are "no-MCP-call." The only comparison available is
B-vs-A accuracy, which reads as expected given B's agents behaved identically to A's:

| Corpus | A acc | B acc | Δ | B mean turns | B mean cost | B mean cache-creation tokens |
|---|---|---|---|---|---|---|
| EN | 0.815 (106/130) | 0.746 (97/130) | −0.069 | 24.2 | $0.237 | 49,511 |
| DE | 0.562 (73/130) | 0.577 (75/130) | +0.015 | 18.6 | $0.179 | 35,579 |
| Pooled | 0.688 (179/260) | 0.662 (172/260) | **−0.027** | — | — | — |

The sign flips between EN and DE with no MCP calls in either — the signature of sampling noise around
a true-zero effect. Turn count, cost, and token consumption in B track file-tool volume, not any
MCP-related variable.

## Abandonment

Not applicable — 0/260 cells ever made a first MCP call. This is non-engagement, not disengagement.

## Inferred (not measured) mechanism

One candidate explanation, grounded in the fixed prompt template verbatim
(`agent_utility_inspect.py:56-57`): *"Answer the following question using only the documents in
{corpus_dir}. Do not use prior knowledge. Be concise."* This frames the task entirely in terms of the
local filesystem corpus, never mentioning a search capability — plausibly priming the model toward
file tools exclusively. Offered as inference; the orchestrator's subsequent live probe established the
stronger mechanical cause (the MCP config was silently dropped and the tools were never offered at
all — see tempdoc 624 twenty-third pass).

## Files referenced
- `tmp/624-run-2026-07-03/logs-en-v4/2026-07-03T00-35-14-00-00_agent-utility-task_DXJxdcEXuUovNqzNiKjuoW.json` (EN, B, 130 samples)
- `tmp/624-run-2026-07-03/logs-de-v1/2026-07-03T01-42-48-00-00_agent-utility-task_YC345QKrbzt6Y8fdRQig7s.json` (DE, B, 130 samples)
- `tmp/624-run-2026-07-03/logs-en-v4/...YTKcKrLFjLyjBkKqeMKi6y.json` / `...3Z3ntf99MuonkxV5jsxhAa.json` (condition A)
- `scripts/jseval/jseval/agent_utility_inspect.py:56-58` (prompt), `:61-88` (argv), `:134-143` (capture)
- `scripts/jseval/jseval/agent_retrieval_eval.py:1038-1099` (`parse_claude_stream_json` — verified not to filter MCP-prefixed names)

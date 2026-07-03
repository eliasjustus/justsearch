# Mechanism-investigation annex (2026-07-03)

Five independent agent analyses over the certified run's per-cell tool-call traces
(`tmp/624-run-2026-07-03/logs-en-v4`, `logs-de-v1`) and the battlefield corpora, launched to answer:
*in the B arm, did agents call the MCP tools, distrust results, receive wrong chunks, or fail
reasoning?* The load-bearing findings are summarized in tempdoc 624's twenty-third pass and the
search-quality register's F-027 correction; these files preserve the full granular reports (strategy
taxonomies, per-pair classifications, replay tables, exemplars) that those summaries compress.

Headline: the answer was "none of the above" — **zero MCP invocations existed in any B cell** (dead
MCP config, silently dropped by the CLI; see 01 + tempdoc 624 pass 23 for the five-way verification
chain). The remaining reports characterize what both (behaviorally identical) arms actually did, what
the corpus structure contributes, and what the engine would have served a real with-tool arm.

| File | Question | Key result |
|---|---|---|
| `01-b-arm-mcp-usage.md` | Did B use the MCP tools at all? | 0/260 cells; 5,309 file-tool calls captured |
| `02-engine-replay.md` | Would the engine have served the gold docs? | hop-1 top-3: 69% verbatim, 90% descriptor, 94% best-of-3; EN=DE |
| `03-a-arm-strategy.md` | How does the file-tools baseline win? | hop-chaining reads ~1.5% of corpus; evidence_hit=3 → acc 1.00; DE gap = bridging + a hallucinated index file |
| `04-discordant-pairs.md` | Where did losing arms diverge? | one axis: the paraphrase-mapping gamble; 59% abstain / 34% wrong-sibling / 0% reasoning failure |
| `05-corpus-structure.md` | How much is corpus-design artifact? | 73% of qids seed-unstable; oracle ceiling 95-99%; EN/DE structure draw-identical |

Provenance: agent-generated analyses (Sonnet-tier, orchestrated per tempdoc 624), reproduced verbatim
from their final reports; each file's own caveats (measured vs inferred) apply. The raw inputs remain
in `tmp/624-run-2026-07-03/` (uncommitted-by-policy Inspect logs) and `datasets/golden/` (regenerable
by recipe); the certified records these analyses reinterpret sit in the sibling `out-*` directories,
each carrying its `arm_invalidation` notice.

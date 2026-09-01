# 868 — The delegate agent's tool surface: what it can actually do

```
status:  IMPLEMENTED (2026-08-26, PR #566) — §B delivered, live-verified (§C), independently
         reviewed (§C.6). Follow-ups in §D. Was BRIEFING until §A landed, then IMPLEMENTING
         after the owner's "proceed autonomously".
created: 2026-08-26
follows: 866 (the file-read capability sketch — OWNER-DECISION-GATED; this tempdoc is
         the wider frame it sits inside), 865 (delegate evidence authority — what tool
         results can honestly claim), 859 §D (effort-mapped budgets — what runs can
         afford), 852 (the delegate tier's promotion into Search v3)
```

## The issue, plainly

The delegate agent has exactly **six tools**, and the gap between what users *think*
they're asking for and what the tool surface can *actually deliver* is now the biggest
known ceiling on the delegate tier. The founding example: the owner's own test task —
"read these three files and summarize each" — is not literally executable. No tool
reads a file's contents. The agent *searches*, gets back budgeted excerpts, and
summarizes those. The answer looks like file-reading; it is excerpt-reading. 865's
evidence work made that honest (received-vs-retrieved badges), but honesty about a
limitation is not the same as removing it.

866 sketched one candidate capability (read-file) and was gated on the owner. This
tempdoc is the **general** question: what should the delegate's tool surface be, why,
and in what order — with read-file as one member of a family, not the whole question.

## Current surface (verify at source; names/behavior drift)

The six tools, with their registration and execution sites:

- **search** — the only content-bearing tool. `SearchTool.java` (modules/app-agent):
  per-result excerpt budget (~:439-448 divides and clips), `content_preview` clamp
  (~:344), `buildSearchEvidence` mints sources (~:329-367), and the `Excerpt:` /
  `Preview:` dual rendering (dense hits have no excerptRegions). Layer-1/2/3 truncation
  documented in 865 §7.5.
- **browse-folders** — structure only, no contents. (`core_browse_folders` — grep its
  tool class.)
- **ingest** — adds documents to the index.
- **file-operations** — move/rename/etc.; `OutputLineage` CORPUS_QUOTED discipline
  (`OperationResult.withLineage`, stamped at `AgentStepRunner` ~:859).
- **remember** — memory notes.
- **navigate** — UI navigation.

Where the surface is defined and governed:
- Tool registration/selection: `AgentRequest.selectedToolNames`, the tool registry in
  modules/app-agent (grep `AgentTool` implementations and where the default set is
  assembled), `AgentLoopService` / `AgentStepRunner` dispatch.
- The virtual-tool channel: `AgentStepRunner.handleVirtualToolCall` (~:1177) — a second
  dispatch seam capabilities could ride; note it now stamps grounding deltas (#553 F-2).
- Budgets & context: `AgentBudgetPolicy` (rungs 2×/8×/15×), `AgentContextCompressor`
  (per-iteration excerpt stripping + the CompressionReceipt from #553) — any new
  content-bearing tool changes the token economics these encode.
- Evidence: `AgentSession.contributeGroundingSources` / `recordExecution` (the one mint,
  returns the delta — #551), `AgentCitationResolver` (doc-level sources are never
  examined — the #548-review F1 finding), 865 §7.6's **acquisition axis** deferral: the
  vocabulary for "opened by name" vs "retrieved" is designed-but-unbuilt, with the
  recorded invariant *an opened-by-name document has LESS relevance evidence than a
  retrieved one, never more*. A read tool is the trigger that un-defers it.
- Security boundary: the loopback-only invariant, the store lock (423 discipline from
  #550), and whatever policy governs which paths an agent-readable file may come from
  (investigate: is there any existing path-allowlist machinery, or does search's
  index-membership implicitly define the readable universe?).
- Product framing: `docs/tempdocs/866-agent-file-read-capability.md` (the gated sketch),
  `859-sv3-live-findings.md` §D (the budget reality check that exposed the gap),
  `865-agent-tool-read-grounding.md` §3/§4 (the capability-gap-wearing-an-evidence-
  gap's-clothes finding).

## Questions the theorization should answer (surface, don't decide)

1. **The demand map.** From the owner's real usage and the recorded runs (the dataDir's
   conversations, the 859/865 findings): which user intents hit the ceiling today?
   Candidates beyond read-file: read-a-range, list-then-read pipelines, write/draft a
   file, fetch a URL (navigate exists but for UI), run a saved search, structured
   extraction over a named document set. Rank by observed demand, not plausibility.
2. **Read-file specifically (the 866 question, now in context).** Whole-file vs ranged
   reads vs chunk-addressed reads; interaction with n_ctx (a whole file can exceed the
   window — does the tool paginate, summarize-first, or refuse?); how a read becomes
   evidence (865's acquisition axis is the designed landing zone); dedup against search
   results for the same document.
3. **Security/consent surface.** What universe is readable — indexed files only, or any
   path? Does reading un-indexed content need a distinct consent posture? What does the
   loopback/lock discipline require? What appears in the run journal (full text read =
   full text persisted?) — storage and privacy consequences of content-bearing tool
   results at scale.
4. **Token economics.** Content-bearing reads blow through budgets differently than
   excerpts: how do the 859-D rungs, the context gate, and the compressor's stripping
   interact with, say, three whole files? (The compressor strips *excerpts* by marker —
   a read tool's output format determines whether it participates in compression at
   all.)
5. **The tool-selection UX.** Today the tier control is Ask/Delegate + effort. Does a
   richer surface need per-run tool selection exposed to the user, or stay automatic?
   (`selectedToolNames` exists on the wire already — who sets it?)
6. **Prior art (research pass).** Agentic file-access patterns in comparable products:
   permission models, read-evidence presentation, context-budget handling for
   whole-document reads. Also MCP-style capability declaration as a possible framing
   for future tool growth.
7. **Ordering.** If the owner green-lights capability work: which single capability
   first, what it must NOT break (the evidence honesty stack, the budget bounds, the
   compression receipts), and what the acceptance evidence looks like.

## Constraints that bind whatever comes out

- Evidence honesty is settled substrate: any new tool's results flow through the ONE
  mint (`recordExecution`'s delta), respect the producer gates, and land in 865's
  acquisition/inclusion vocabulary — no parallel evidence system.
- The grounding-seam audit (`AgentGroundingSeamAuditTest`) and the durability closure
  guard constrain where tool results may attach — read their javadocs before proposing
  event shapes.
- Budgets: a new tool must state its interaction with `AgentBudgetPolicy`'s structural
  bound (the cap-binds-first arithmetic in its javadoc).
- 866 remains the owner's decision; this tempdoc widens the frame so that decision is
  made once, in context, rather than tool-by-tool.

## First task

Investigation, research, theorization. Read the tool surface at source, build the
demand map from the recorded runs and the 859/865 findings, run the prior-art research
pass, and grow this tempdoc with the capability family, the option space per question
above, tradeoffs, risks, and hidden assumptions. **No design freeze. No implementation.
No PR.** The owner decides direction after theorization.

---

## §A. Investigation results (2026-08-26, four parallel source/run/research passes)

Evidence: source at `main` 0f056460; 37 `core.agent-run` journals under
`modules/ui-web/.dev-data/agent-runs/`; prior-art web pass. Load-bearing claims were
re-verified by the orchestrator at the cited lines.

### A.1 Demand map (Q1) — one intent dominates, and it has never completed

| Rank | Intent | Runs (of 37) | Clean completions |
|---|---|---|---|
| 1 | **Open N named/selected docs, summarize each** | **16–17** (5 of 7 hand-typed early tasks) | **0** |
| 2 | Multi-topic search + quote/compare | 11 | 1 |
| 3 | Long-form essay from one search | 3 | 0 |
| 4 | Corpus-wide count/aggregate | 2 | 1 |
| 5 | Plain search-answer | 3 | 3 |
| — | write/draft file · fetch URL · saved search | **0** | — |

Rank-1 outcomes: 6 `BUDGET_EDGE_FINALIZE`, 4 `MAX_ITERATIONS` (all with **empty**
`finalResponse` and `sources: []`), 4 `ERRORED`, 1 `CANCELLED`, 1 `WAITING_BUDGET`.
Two runs make the mechanism explicit rather than inferred:

- `d0899193` final text: *"I identified 23 documents … but I did not retrieve their
  full content before the token budget was exhausted."*
- `8bc66882`: the model **invented a read op** —
  `core_file_operations {"operations":[{"operation":"read","path":"…317-ce-model-upgrade.md"}]}`
  (`read` is not in `FileOperation.OpType`, `FileOperation.java:16-20`; journal: "Tool
  call rejected by user"). Under pressure the model coerces the MEDIUM-risk FS tool into
  a reader. Strongest single demand datapoint on disk.

Caveat: 28 of 37 runs are 2026-08-25 harness legs (859 L-legs); the 9 early runs are
the clean cohort. Both cohorts have the same dominant shape.

### A.2 The "scoped search already covers it" premise is false as shipped

866 §3 and 865 §4.9 rest on "`search-index` accepts `path_prefix`, so 'search within
these files' is expressible." Across 37 runs `path_prefix` was used **0 times**, and the
cause is code, not model behaviour — the scoping arm is unreachable **twice over**:

1. The LLM-facing schema is `op.intf().inputs()` (`AgentOperationEmitter.java:252`),
   and the catalog `Interface` declares only `{query, limit}`
   (`AgentToolsOperationCatalog.java:123-127`). `SearchTool.PARAMETER_SCHEMA` (which
   *does* declare `path_prefix`, `SearchTool.java:139-169`) is "preserved as a constant
   for unit tests" (`:185`) — no production consumer. The system prompt nevertheless
   instructs the model to use `path_prefix` (`AgentPromptComposer.java:43,96`). The
   **outward MCP surface does declare it** (`McpToolSurface.java:280`) — external agents
   get scoping, the in-app delegate does not.
2. The server-side `docIds` scope (S7 scope chips → `AgentSession.setDocIdsScope`,
   `AgentLoopService.java:474`) is sent **empty by construction** from SV3
   (`sv3-ask.ts:340`); zero journals carry `docIds`.

Consequence: 866 §6.1 ("run the owner's task against scoped search") **cannot be run
today**. Declaring `path_prefix` in the catalog interface is a one-line change and the
cheapest experiment in the whole frame.

### A.3 Tool surface at source (verified; corrects the header list)

Six real operations in `AgentToolsOperationCatalog.java:100-107` — `remember` and
`navigate` are real ops with handlers, not virtual. Plus three dynamic sources into the
same agent-tools partition (`OperationCatalogComposition.java:104-120`): MCP-host
projections (off by default), workflow tools (`core.workflow-*`), and FE-published
virtual `vop_*` tools. `CoreOperationCatalog` ops tagged `ExecutorTag.AGENT` are **not**
in the agent's vocabulary (wrong partition).

- `selectedToolNames`: wire key `tools`; FE always posts `tools: []`
  (`AgentSessionController.ts:1834-1844`); `/api/chat/agent/tools` is rendered
  read-only by `jf-agent-authority-panel`. Backend-complete, no picker UI.
- Readable universe today: search = index; browse = index only (`FolderBrowseEngine`,
  pure Lucene); file-ops = `toRealPath` sandbox to indexed roots; **ingest has no root
  check** (`IngestTool.java:215-220`, deliberate per 811 C-2a) — so the agent can
  already widen the indexed universe behind a MEDIUM inline confirm. `path_prefix` /
  `parent_path` validation **degrades open** when roots are absent
  (`SearchTool.java:516-529`).
- Head never reads document bytes; ingest ships paths (`BatchRequest.file_paths`).
- Virtual channel (`handleVirtualToolCall`) is unsuitable for content: string-only
  result (no `structuredData` → cannot mint grounding), 30 s cap, bypasses the
  risk/consent lattice.

### A.4 The retrieval half of read-file already exists

`indexing.proto:299-357`: `FetchDocuments` and **`FetchDocumentSlice`**
`{doc_id, offset_chars, max_chars}` → `{content, truncated, next_offset_chars}`
("retrieve document content without direct index access"). Worker caps
`DEFAULT_SLICE_CHARS = 20_000`, `MAX = 200_000` (`GrpcSearchService.java:77-81`).
Head wrapper `RemoteDocumentService.fetchSlice`; HTTP `GET /api/preview?docId&offsetChars&maxChars`
(`PreviewController.java`). Reusing it keeps Hard Invariant #1 intact, confines the
readable universe to *indexed* documents (stronger than any path allowlist), and gives
paging for free. **Every unsolved part is downstream of the fetch.**

### A.5 Token economics (Q4) — the deciding constraint

- `ChatModelProfile` carries no `n_ctx`; the window is one knob, default **4096**
  for both profiles (`InferenceConfig.java:130-131`), matching 859-D's measurement.
- 20 KB text ≈ 5,100 tokens = **125 % of the default window**; llama-server rejects it
  (the exact failure at `AgentStepRunner.java:302-306`). Three such reads over ten
  iterations (prompt is cumulative) ≈ 138 k tokens = 2.25× Thorough.
- `AgentBudgetPolicy` javadoc `:35-38`: the structural bound holds *only while prompts
  stay within n_ctx* — a tool that can exceed it voids the Thorough rung's proof, not
  just its budget.
- `MAX_TOOL_RESULT_CHARS = 4000` (`AgentContextCompressor.java:74-75`) is applied at
  both dispatch seams: a naive read tool **reaches the model as a 4 k prefix** — the
  founding complaint reproduced.
- Corpus reality: the tempdoc corpus these runs searched has **median 27.7 KB, p90
  111 KB**; the shipped help set (`SSOT/docs/help/*.md`) is **0.5–2.4 KB, all under the
  cap whole**. "Read this file" is trivially servable for the product's own corpus and
  structurally unservable for a dev corpus. This split is the real design fork.
- Compression: Layer-3 strip keys on `^\s+Excerpt:` (`ToolResultCarrier.java:55-56`);
  a read result matches nothing → `CompressionReceipt` puts it in *neither* intact nor
  removed → `inclusionFor` returns `ABSENT`. Read evidence would be inclusion-mute
  unless `ToolResultCarrier` gains a third label (its declared extension point).

### A.6 Evidence path (Q2) — the mint has no operation gate

- Sole producer gate: `data.get("searchResults") instanceof List` (`AgentSession.java:307-309`).
  A read tool emitting `searchResults` would mint sources **indistinguishable from
  retrieved ones** — exactly the 865 §7.6 invariant violation. The acquisition
  vocabulary is **not in code** (grep confirms; only 865 §7.6 prose).
- `OutputLineage.java:24` javadoc already names "file reads" under `CORPUS_QUOTED`;
  `CORPUS_READERS` set at `:44-45` is the declaration site.
- Doc-level sources (`chunkIndex = -1`) are never matched by `AgentCitationResolver`
  (`:87-88` re-fetches by chunk) → panel entry, no inline marks. A whole-file read that
  mints doc-level sources inherits this dead-end; a **chunk-addressed** read does not.
- `AgentGroundingSeamAuditTest` allows `withGrounding` only at the two dispatch seams;
  `AgentRunDurabilityClosureTest` requires any new event kind to declare durability.

### A.7 Security / storage (Q3)

- Loopback is transport; the 423 lock governs the journal, not corpus reads
  (`INDEX` is `DERIVED/OPAQUE`, not app-encrypted).
- **Full tool output is journaled verbatim** (`toolCompletedPayload` emits the
  pre-truncate message; `events.ndjson`, `<dataDir>/agent-runs/<sid>/`, 30-day
  retention). A read tool = whole documents copied into the journal twice (events +
  checkpoint `messages`). `search-index` declares `AuditPolicy.NONE` while the journal
  persists everything anyway — the journal is ungoverned by `AuditPolicy`.
- Known defect made worse: `RunEventStore.readEvents` returns empty while sealed-and-
  locked (`:168-189`) — the journal would be the only copy of a lot more content.
- Prompt-injection: 770 §4 recorded that OpenAI's search/fetch split is motivated by
  injection containment. A read tool feeds arbitrary document text to a 4B model with
  a HIGH-risk FS tool in the same palette; the inline-confirm lattice is the mitigation
  and must not be bypassed (rules out the virtual channel).

### A.8 Prior art (Q6)

- Consent: directory/corpus-scoped by default, escalate only at the boundary (Claude
  Code Read, Codex `read-only`/`workspace-write`, MCP `roots`). Nobody prompts per read.
- Small-context reads: `offset/limit` + explicit "there is more" (Claude Code
  `PARTIAL view`; LangChain deepagents `read_file` 100-line default + remaining-count —
  their issue #82 documents infinite re-truncation loops when total size isn't signalled
  up front). Aider: summarize-first (repo map, 1 k tokens) then full-add on request.
- **Closest precedent: Khoj's `chunk_read`** — search returns ~256-token snippets and
  the tool description states snippets are "NOT sufficient … you MUST use chunk_read".
  Obsidian Copilot: `@`-mentioned notes are pulled whole, searched notes are not.
- Provenance badge: Microsoft 365 Copilot's file-grounding vs web-grounding split is the
  only shipped analogue; no product badges "chunk hit" vs "full read". Open space.
- MCP `resources` (`resources/read` by URI, `size`/`priority` annotations) is the natural
  declaration shape if the surface later grows.

### A.9 Theorization — option space per question

**Read shape (Q2).** Judged against A.5/A.6:

| Option | Fits n_ctx 4096? | Evidence honesty | Verdict |
|---|---|---|---|
| Whole-file | No (median doc = 125 % of window) | doc-level → citation dead-end | reject |
| Ranged chars (`offset/max` over `FetchDocumentSlice`) | Only with page ≤ ~2.5 k chars; model must loop | doc-level unless page↔chunk mapped | viable, weakest evidence |
| **Chunk-addressed** (`read_chunk(parentDocId, chunkIndex)` / next) | Yes — chunks are already window-sized | mints `parentDocId#chunkIndex` → resolver + inline marks work | **strongest** |
| Summarize-on-read (Worker/LLM-side map) | Yes by construction | summary is `AGENT_AUTHORED`, not corpus-quoted | complement, second wave |

Chunk-addressed is the only shape where the existing citation resolver, the
compression receipt (with a third `ToolResultCarrier` label) and the acquisition
invariant all line up without a parallel evidence system. It also matches the demand
shape (list-then-read, per-document result) and Khoj's precedent.

**Readable universe (Q3).** Indexed documents only, by reusing the Worker fetch —
no new path policy, no new consent posture for LOW-risk reads; un-indexed content
stays behind the existing MEDIUM `ingest` confirm (ingest-then-read is the honest
path to arbitrary files). The journal question is separate and pre-existing (A.7):
whether content-bearing tool outputs are journaled by reference (`docId#chunk`) rather
than by value is a 30-day privacy-footprint decision the owner should make explicitly.

**Budget (Q4).** Any read tool must (a) size its page ≤ `MAX_TOOL_RESULT_CHARS` so
Layer-2 never clips it silently, (b) register a carrier label so inclusion is not mute,
(c) leave the n_ctx-bound premise intact. Whole-file violates (c) outright at defaults.
Re-tuning 859-D rungs is required only if per-call size grows; chunk pages don't.

**Tool-selection UX (Q5).** No evidence for a picker: 0 runs would have benefited, and
the ceiling is capability, not selection. Keep automatic; the authority panel already
shows the set.

**Ordering (Q7), if green-lit.** Two free steps precede any tool:
1. Declare `path_prefix` in the catalog interface (A.2) — makes 866 §6.1 measurable and
   may shrink the whole charter.
2. Refusal honesty (866 §2): "I have no tool that reads a file; I can search inside
   it." Zero infra.
Then **one** capability: chunk-addressed read over the Worker fetch, LOW risk,
`CORPUS_QUOTED`, minting via the existing seam **after** the acquisition axis gets its
second value (865 §7.6's named trigger). Must-not-break: the two-seam audit, durability
closure, the receipt's tri-state, 859-D bounds. Acceptance: the rank-1 task
(`a276d2d9`'s prompt) completes with per-document inline marks on the compact profile.

### A.10 Hidden assumptions surfaced

- "Excerpts are a subset of reading" — false at n_ctx 4096: excerpts are the *only*
  representation that fits; a read is a paging loop, not a bigger excerpt.
- "The delegate can scope search" — false as shipped (A.2).
- "The tool list is the catalog" — false; MCP/workflow/virtual sources share the
  partition, and an external MCP server can already hand the delegate an ungoverned
  read tool (866 §5).
- "Journal ≈ audit policy" — false; the journal stores everything regardless.
- Demand evidence is mostly the harness author's own prompts, not third-party users.

### A.11 Out-of-scope findings (logged to the observations shard)

Stale `list_files` comment (`AgentToolsOperationCatalog.java:187-192`); `BrowseTool.java:189`
ignores caller `max_files` in fallback; `22-agent-system-architecture.md:89-95` claims
file-ops can "delete" (OpType has no DELETE); `path_prefix` schema/prompt drift (A.2).

---

## §B. Design (2026-08-26 — owner said "proceed autonomously" after §A; status → IMPLEMENTING)

Branch `worktree-868-read-chunk`. Scope = §A.9's order: (1) declare `path_prefix`, (2) refusal
honesty, (3) one read capability + the acquisition axis' second value. Nothing else.

### B.1 Correction to §A.9: ranged, not chunk-addressed

§A.9 preferred chunk-addressed reads because doc-level sources hit the citation resolver's
re-fetch dead-end. Re-reading the resolver: `AgentCitationResolver.resolve` calls the
`matchCitations` *overload*, which wraps every source as `VerificationSource(citation, "")` —
but the real method `matchCitationsAgainst` (`DocumentService.java:466-471`, tempdoc 836 §1.4)
verifies a source against its **own literal text** when non-blank. A read source therefore
needs no chunk identity: it carries the text the model actually saw. That is strictly more
honest than a chunk re-fetch (it verifies what was shown, not what the index holds now), and
it needs no new Worker RPC (`lookupChunkContent` is private to `CitationMatchOps`; no chunk
RPC exists). So: **ranged read over `FetchDocumentSlice`**, page-capped below
`MAX_TOOL_RESULT_CHARS`.

### B.2 The tool — `core.read-document` (wire `core_read_document`)

- Catalog: `AgentToolsOperationCatalog` — `RiskTier.LOW`, `ConfirmStrategy.None`,
  `AuditPolicy.NONE`, availability = same `index.unavailable` condition as search.
  Interface `{path: string (required, absolute; or a docId as search/browse return),
  offset_chars: integer, max_chars: integer}`.
- Executor: `tools/ReadDocumentTool.java` (mirrors `SearchTool`'s shape) →
  `DocumentService.fetchSlice(docId, offset, min(max, READ_PAGE_CHARS))`, `READ_PAGE_CHARS =
  3000` (≈ 750 tokens; leaves headroom under the 4000-char Layer-2 cap so a page is never
  silently clipped). Path validated with `AgentToolPaths.validateAgainstRoots` (same
  degrade-open semantics as search; readable universe = indexed docs because the Worker
  only serves indexed content). Not found → `OperationResult.failure` naming the path and
  suggesting `core_search_index`/`core_browse_folders`.
- Text to the model: header `[read] <path> — chars <a>–<b>` + (`truncated` → `More: call again
  with offset_chars=<next>`) + body written as ONE carrier line via
  `ToolResultCarrier.readLine(text)` (label `Read`, strippable like `Excerpt` — it is the
  longest field and only useful in the producing iteration; also a `CARRIER_LINE`).
- Evidence: `structuredData.readResults = [{path, title, excerpt(=page text, uncapped),
  startChar, endChar, truncated}]`. `OutputLineage.CORPUS_READERS` += `core.read-document`.

### B.3 The acquisition axis — second value arrives (865 §7.6 trigger)

- `AgentEvent.AgentSource` gains `acquisition` (String wire: `"retrieved"` | `"opened"`);
  old-arity constructor delegates with `"retrieved"`. Minted `"opened"` only from
  `readResults`.
- `AgentSession.contributeGroundingSources`: second producer key `readResults` → doc-level
  arm (`doc#path`, `DOC_LEVEL_SENTINEL`), `acquisition="opened"`, `excerpt` = page text.
  Run-wide dedup unchanged: a document both retrieved and opened keeps its first identity
  (retrieved-first if search came first — the invariant *opened has LESS evidence* is
  preserved because "opened" never upgrades a retrieved source).
- `AgentCitationResolver`: switch to `matchCitationsAgainst`; `literalText` = `excerpt` for
  `opened` sources, `""` for retrieved (unchanged re-fetch).
- FE (`evidenceProjection.ts` `sourceGroundingLabel` + `dropped` label): prefix `Opened ·`
  instead of `Retrieved ·` when `acquisition === 'opened'`; schema (`agent.ts`/`schemas.ts`)
  gains the optional field defaulting to `retrieved`.

### B.4 The two free steps

- `AgentToolsOperationCatalog.searchIndex()` interface += `path_prefix: string` (description:
  restrict to files under this absolute folder path). `SearchTool.PARAMETER_SCHEMA` stays the
  test-preserved constant; a test asserts the catalog interface declares every property the
  executor honours (`query, limit, path_prefix`).
- `AgentPromptComposer.DEFAULT_SYSTEM_PROMPT`: name the read tool's purpose and paging, and
  the refusal-honesty rule: *if no tool can do what was asked, say which capability is
  missing and what you can do instead; never say "I don't have access".*

### B.5 Must-not-break (acceptance)

`AgentGroundingSeamAuditTest` (no new stamp site), `AgentRunDurabilityClosureTest` (no new
event kind — none added), `OutputLineageTest`, `AgentLoopServiceTest` tool-name predicates,
`LiveRunsEnumerationTest`, `check-live-witness` if the registry snapshot changes,
`./gradlew.bat build -x test` + `:modules:app-agent:test :modules:app-agent-api:test
:modules:app-services:test :modules:ui:test`, `ui-web` typecheck + unit. Live: rank-1 prompt
(`a276d2d9`: "Read three of the indexed help documents and summarize each one in two
sentences.") completes on the compact profile with `Opened` sources in the panel.

### B.6 Deliberately not done

Journal-by-reference (A.7) — pre-existing, owner decision; tool-picker UI (A.9 Q5); write/
fetch/saved-search (zero demand); chunk RPC (B.1 makes it unnecessary).

---

## §C. Implementation + live verification (2026-08-26)

### C.1 What landed (branch `worktree-868-read-chunk`)

- **§B.4 free steps:** `path_prefix` declared in the catalog `Interface`
  (`AgentToolsOperationCatalog.searchIndex`), asserted through the real emitter by
  `AgentToolsOperationCatalogTest`; system prompt names the read tool + paging and the
  refusal-honesty rule (`AgentPromptComposer.DEFAULT_SYSTEM_PROMPT`).
- **§B.2 tool:** `core.read-document` / `core_read_document` — `ReadDocumentTool` over
  `DocumentService.fetchSlice`; `READ_PAGE_CHARS` **derived** from the Layer-2 cap
  (`min(3000, ToolResultCarrier.layerTwoCapChars() − 600)`, floor 200) so a lowered
  `agent.maxToolResultChars` shrinks the page instead of clipping it; header + one `Read:`
  carrier line; `structuredData.readResults`; LOW / no confirm / no retry / same
  `index.unavailable` availability as search; `OutputLineage.CORPUS_READERS` +=
  `core.read-document`; handler registered on both eager and late-bound paths.
- **§B.3 acquisition axis:** `AgentSource.acquisition` (`retrieved` | `opened`), minted
  `opened` from `readResults` via the doc-level arm with run-wide dedup; wire + journal
  carry it; `AgentCitationResolver` now calls `matchCitationsAgainst` with the page text
  as `literalText` for opened sources. FE: `Acquisition` type, one `acquisitionOf()`
  default point, `acquisitionWord()` prefixes every `Retrieved ·` label → `Opened ·`; the
  `dropped` badge's detail follows; CitationsPanel aggregate copy made acquisition-neutral
  ("N sources", "N not cited").
- **Composition-root change (review-worthy):** `ServicePhase.Output` was at the 26-field
  god-record ceiling; the six agent-tool components are now one `AgentToolFactory.Output
  agentTools` component (the `InferenceRuntimeHandles` precedent), record → 21 fields.
- **Diagnostic added:** `AgentLoopService` logs `Agent tools offered (session, selected):
  [names]` once per run — the ONE record of what the model was offered (see C.3).

### C.2 Verification tiers

| Tier | Result |
|---|---|
| Java unit | app-agent-api 225 · app-agent 507 · app-services 2372 · ui 871 — 0 failures; full `./gradlew.bat test` green (worker), re-run after §C.1 edits below |
| ui-web | typecheck clean · 457 files / 6052 tests · full ui-web gate recipe + kernel gates pass · `check-shape-handler-regen` clean |
| Governance | `operation-surface: pass` · `check-live-witness` OK · `AgentGroundingSeamAuditTest` / `AgentRunDurabilityClosureTest` unchanged and green |
| **Live (compact profile, n_ctx 4096, cuda12, 666 docs)** | **Rank-1 prompt completed** — see C.4 |

### C.3 Live finding — availability-gated tools silently absent until `/api/status` is polled

Runs 1–2 on the live stack reproduced the *historical* failure exactly: the model browsed,
then emitted `core_file_operations {"operations":[{"operation":"read",…}]}` — while its own
reasoning said "use core_read_document". Six isolated replays of the same context against
llama-server (streaming and non-streaming, temp 0 and 0.7) *always* chose
`core_read_document` when it was in the tool list. The new `Agent tools offered` log settled
it: the loop had offered **neither `core_search_index` nor `core_read_document`**. Both carry
`Not(ConditionMatches("index.unavailable"))`, and `LifecycleSnapshotTap.accept` reconciles
that condition **only when `StatusLifecycleHandler` builds a readiness envelope** — i.e. when
something polls `/api/status`. The FE polls constantly, so users never see it; an API-only
client (this harness, MCP-driven runs, jseval) inherits the boot-time `worker.starting`
assertion for the life of the process. Logged to the observations shard; **not fixed here**
(out of 868's scope — it is a health-substrate defect, and the fix belongs with the tap, not
the agent). Note the second-order lesson: `/api/chat/agent/tools` was *not* evidence of what
the loop sends — it reads the merged catalog without the availability probe.

Also logged: `POST /api/chat/agent` with a non-empty `tools` selection (either name form)
returns `NO_TOOLS` — the selection path is broken independently of this work.

### C.4 The acceptance run (after one `/api/status` poll)

Prompt: *"Read three of the indexed help documents and summarize each one in two
sentences."* (verbatim `a276d2d9`, the rank-1 shape with 0/16 historical completions).

- Tools offered: `[core_search_index, core_read_document, core_browse_folders, …]`.
- Calls: `browse` ×2 (root, then `list_files`), `core_read_document` ×6 (first page of
  three documents, then continuation pages), **no `file_operations`**.
- Disposition **COMPLETED**; answer = three per-document two-sentence summaries with
  `[1] [2] [3]`, plus an unprompted honesty note: *"I only read portions of these documents
  due to their length."*
- Sources: 3, all `acquisition = opened`, `chunkIndex = −1`, `excerpt` = the 3000-char page.
- Citations: 4 sentence→source matches, scorer `CROSS_ENCODER`, similarity 0.79–0.89 —
  the literal-text verification arm (§B.1) works; doc-level sources are no longer a
  citation dead-end.
- Read header on the wire: `[read] F:\…\278-decision-log.md — chars 0–3000 of more; More:
  call core_read_document again with offset_chars=3000` (bytes verified UTF-8).

### C.5 Not done / owner decisions still open

- Journal-by-reference for content-bearing tool outputs (A.7) — pre-existing, unchanged.
- The stale-condition defect (C.3) and the `tools` selection defect — logged, not fixed.
- Retired-shell banner copy in `UnifiedChatView.ts` — logged.
- 866 §6.1's "how far does scoped search get" measurement is now *runnable* (path_prefix
  is declared) but was not run: the read tool made the question moot for the rank-1 shape.

### C.4b Through the FE (Search v3 → Delegate), same prompt — honest tally

| Profile | Effort | Outcome | What happened |
|---|---|---|---|
| compact 4B | Standard (10 steps) | cut short | browse ×2, then paged **forward** through two long docs at `max_chars=2000` (265: 0→12000, 278: →9000); context gate at 3.4 k, compaction, step cap |
| compact 4B | Standard | cut short | search → read 206 ×2 → browse → invalid browse (`docs` not a root) → step cap |
| compact 4B | Thorough | cut short | read 206 (0→6000), 293 (0→3759), search ×2; step cap — effort raises tokens, not steps |
| standard 9B | Standard | **error** ×2 | "Model failed to generate a response (possible reasoning token exhaustion)" on the turn *after* browse — before any read; pre-existing, logged |

Observed and verified in the FE regardless of outcome: "Read Document <file>" cards render
per call; the sources panel labels every read source **"Opened · grounding check did not
complete"** (correct — no answer, no grounding pass); the 3000-char page rendered as a
wall of text → the excerpt display clamp (320 chars, word boundary) was added and unit-
tested in the same slice.

Reading of the tally: the tool works and the evidence path works (C.4 completed through
the API with `maxIterations: 12`). What the FE runs expose is the **paging economics at
n_ctx 4096 with a 10-step cap and a 4B model**: the model asks for 2000-char pages and
walks whole 30–60 KB documents despite the prompt's "first page or two" guidance, so
three documents cost more steps than the run has. That is §A.5's arithmetic showing up
one layer higher, not a defect in the read. The prompt now says it plainly; the levers
that remain are product levers the owner should set, not this slice: the FE's fixed
`DEFAULT_MAX_ITERATIONS = 10` for delegate runs, a `total_chars` hint in the read header
so the model can budget, and whether a summary task should default to one page per
document server-side. Recorded, not decided.

### C.6 Independent refute-first review (reviewer ≠ implementer) — findings and disposition

| # | Finding | Disposition |
|---|---|---|
| 1 | Cross-producer dedup held only by accident: search keys chunk-precise hits `parentDocId#chunk`, reads key `doc#path`, so "search finds /a.md chunk 3, then read /a.md" minted **two** sources; the test passed for the wrong reason (its search fixture had no `parentDocId`). | **Fixed.** Path index across both arms via one `docKey(path)` (lowercased — the index lowercases on Windows); a document established by any search arm keeps its retrieved identity and the read mints nothing (865 §7.6: opened adds availability, not evidence). Tests: chunk-precise case + case-only-differs. Cost, recorded: when a doc was retrieved first, its later page text is not used as literal verification text. |
| 2 | Empty extraction (`found=true`, `content=""`) produced a successful empty page and a blank-excerpt opened source; blank literal text makes the matcher fall back to an index lookup with `chunkIndex` clamped to 0 — the re-fetch opened sources must never use. | **Fixed.** Blank page at offset 0 → failure naming the path and the Worker's `extraction_reason_code`; blank at a later offset → "end of document", no `readResults`; mint skips blank excerpts. |
| 3 | `READ_PAGE_CHARS = max(200, …)` — at caps below ~500 the floor beats the derivation and Layer-2 clips the page. | **Fixed.** Floor removed; the tool refuses with an explicit message when the derived page is < 200. Header math now exercised with a 400-char Windows path. |
| 4 | Resolver verified the raw slice while the model saw the flattened page. | **Fixed.** Flatten once; the same string is the carrier line and the `excerpt`. |
| 5 | Stale claims: "no auto-retry protects paging" (nothing consumes `OperationPolicy.retry()`); "seventh tool" (seventh component, fifth tool). | **Fixed** (comments). |
| 6 | Outward MCP surface gets no read tool — mirror-image asymmetry of the `path_prefix` argument. | **Intended**, now stated in the catalog javadoc: 770 §4 withdrew `fetch` for the MCP consumer; different surface, different economics. |
| 7 | `SourcesPane` aria-label "…from the assistant's retrieval" over an opened doc; `autonomy/index.ts` core-op enumeration incomplete. | **Fixed** (FE). |
| 8 | `RetryPolicy` on agent operations is declarative only — no production consumer. | Pre-existing; logged to the observations shard. |

Could-not-refute (kept as verified): readable universe = Lucene stored fields only, no disk fallback; both production `DocumentService` impls override `matchCitationsAgainst`; `acquisition` written at both serialization sites and defaulted at one FE site; the two seam/durability audits still bind; both handler registration paths register the read tool; `STRIPPABLE_LINE` cannot strip anything new; the deep-link path still receives the full excerpt.

Directional note on #1: the dedup is read-after-search only. A search that later finds a chunk of an already-read document still mints a chunk-precise retrieved source — it adds ranking evidence and an inline-mark identity the read never had; 865 §7.6 forbids opened claiming what retrieved earned, not the reverse (`AgentSession.java` documentGroundingKeys comment). The `READ_PAGE_CHARS < MIN_PAGE_CHARS` refusal is deliberately not unit-tested: the constant freezes from `ConfigStore` at class load, so a test would be JVM-order-dependent.

---

## §D. Follow-ups (2026-08-26, at close)

Candidates for their own tempdoc:

1. **Availability truth without a poller.** `LifecycleSnapshotTap.accept` reconciles
   conditions only when `/api/status` is built, so availability-gated tools
   (`core_search_index`, `core_read_document`) are silently absent for API-only/headless
   clients until something polls (§C.3). The fix belongs in the health substrate (reconcile
   on worker-readiness transitions, or evaluate availability from the readiness envelope
   directly), plus a test that an agent run started before any status poll still offers
   search. The `Agent tools offered` log is the instrument.
2. **Delegate paging economics.** With n_ctx 4096, a 10-step FE cap, and a 4B model, "read
   three documents" pages to exhaustion (§C.4b). Levers, each an owner call: raise or
   effort-scale the FE's fixed `DEFAULT_MAX_ITERATIONS`; put `total_chars` in the read
   header so the model can budget; a summary-mode default of one page per document;
   per-document progress vocabulary for the budget gate (859 §2.3's "1 of 3 files").
3. **Standard-profile reasoning exhaustion.** Qwen3.5-9B fails 2/2 on the turn after
   `browse` at n_ctx 4096 / max_tokens 1024 ("possible reasoning token exhaustion"), before
   any read — the user-facing profile cannot run the delegate tier at defaults.
   → **Taken up and REFUTED by tempdoc 881.** The failure is real and reproduces 3/3, but the
   name this item gives it is wrong, and the wrong name is this item's own product: the loop
   discarded `finish_reason` and the terminal guessed. Measured (881 §A.2): `finish_reason=stop`
   after 35–55 of 1024 completion tokens — nothing was exhausted. The model emits a well-formed
   tool call *inside* an unterminated thinking block, in the XML `<tool_call><function=…>`
   grammar, which `--reasoning-format deepseek` routes wholly to `reasoning_content` where the
   loop's text-channel-only, JSON-only recovery could not see it. Raising `max_tokens` to 4096
   changes nothing; n_ctx is not implicated (2174 / 4096 at the failing turn). Also corrected:
   "compact 4B does not hit it" — it does, at 5 % of turns against the 9B's 40 % (881 §A.3).

Small, no tempdoc needed:

4. `POST /api/chat/agent` with a non-empty `tools` selection returns `NO_TOOLS` in either
   name form (observations shard) — the selection path is broken independently of 868.
5. 866 §6.1 ("how far does scoped search get") is now runnable — `path_prefix` is declared —
   and worth one measured run for the record, though the read tool made it moot for the
   rank-1 shape.
6. Comment/copy residue logged: retired-shell banner copy; `autonomy/index.ts` still omits
   `core_remember`/`core_navigate_to_surface`; `RetryPolicy` on agent ops is declarative.

Owner decisions still open (unchanged from §C.5): journal-by-reference for content-bearing
tool outputs (A.7); the MCP surface deliberately gets no read/fetch (770 §4).

Tempdoc 866 is superseded by this one (its question was answered: the tool shipped; its §4
questions are settled in §B/§C).

## Open items routed from the retired observations store (tempdoc 872, 2026-08-26)

Routed at retirement per CLAUDE.md `rule:log-pre-existing-issues`; verbatim from the shards folded at commit 7b85a5a6.

- [ ] Retired-shell banner copy 'These N documents were retrieved and informed the answer' assumes every agent source is retrieved; false once opened sources exist (868) — UnifiedChatView.ts:4690,4710,4735 (2026-08-25)
- [ ] api-contract-map.md claims AgentOperationEmitter output is byte-stable per AgentOperationEmitterRegressionTest, but that test deep-equals a baseline built from four HAND-WRITTEN stub ops, not the real AgentToolsOperationCatalog — the shipped LLM-facing tool surface has no baseline guard (found while adding path_prefix, 868 §B.4) — `docs/reference/api-contract-map.md:248` / `modules/app-services/src/test/java/io/justsearch/app/services/registry/emitter/AgentOperationEmitterRegressionTest.java:56` (2026-08-25)
- [ ] POST /api/chat/agent with a non-empty tools selection (either wire name core_search_index or id core.search-index) errors NO_TOOLS even though AgentOperationEmitter.matchesSelection accepts both forms — selection path broken or body key mismatch; found during 868 live verification — ToolIteratingShapeRunner.java:251, AgentLoopService.java:546 (2026-08-25)
- [ ] Availability-gated agent tools (core_search_index, core_read_document) are silently dropped from the model's tool list until something polls /api/status: LifecycleSnapshotTap.accept only reconciles conditions from StatusLifecycleHandler, so the boot-time index.unavailable (worker.starting) persists in the ConditionStore for API-only/headless clients; observed live 2026-08-26 via the new 'Agent tools offered' log — LifecycleSnapshotTap.java:373, AgentOperationEmitter.isAvailableNow (2026-08-26)
- [x] ~~Standard profile (Qwen3.5-9B) delegate run fails 2/2 at the LLM turn after core_browse_folders with 'Model failed to generate a response (possible reasoning token exhaustion)' at n_ctx 4096 / max_tokens 1024 — before any read tool call; compact 4B does not hit it. Seen live 2026-08-26 during 868 FE verification — AgentLlmCaller.java:50, AgentStepRunner.java:302 (2026-08-26)~~ — **fixed in tempdoc 881, with the diagnosis corrected: not token exhaustion (`finish_reason=stop` at 35–55 of 1024 tokens), but a tool call leaked into the reasoning channel in an unrecognised grammar; and the compact 4B does hit it, at 5 % of turns.**
- [ ] OperationPolicy.retry() / RetryPolicy.autoRetry on agent operations has no production consumer — declarative only; javadocs that claim retry semantics (e.g. core.search-index autoRetry(2)) describe behaviour nothing implements — AgentToolsOperationCatalog.java:152 (2026-08-26)
- [ ] BrowseTool folder-list auto-fallback to file listing ignores the caller's max_files and hardcodes DEFAULT_MAX_FILES — `modules/app-agent/src/main/java/io/justsearch/agent/tools/BrowseTool.java:189` (2026-08-25)
- [ ] path_prefix honoured by SearchTool but absent from the LLM-facing catalog Interface (system prompt still tells the model to use it); SV3 sends docIds scope empty by construction — AgentToolsOperationCatalog.java:123-127, AgentPromptComposer.java:43, sv3-ask.ts:340 (2026-08-25)
- [ ] Worktree dev stacks cannot activate the GPU runtime: RuntimeActivationService.resolveVariantsRoot falls back to RepoRootLocator (the WORKTREE root), which has no modules/ui/native-bin; the dev-runner's JUSTSEARCH_SERVER_EXE (shared main cuda12) is not consulted by ai_activate — 'Variant not installed: cuda12'. Workaround used 2026-08-26: copy main's variants/cuda12 into the worktree (1.1 GB) — RuntimeActivationService.java:1688-1705, dev-runner.cjs:494 (2026-08-26)
- [ ] CI javadoc warning MissingSummary at AgentEvent.java:500 (AgentSource acquisition javadoc opens with a @see-style fragment) — cosmetic, from PR #566 (2026-08-26)

---

## §E. Follow-up settlement (2026-09-01 — all §D items landed or routed)

The §D follow-ups and the two critical-analysis passes over the tool subsystem (structural,
sprawl) were executed as seven orchestrated workstreams, each with its own tempdoc, an
independent refute-first review, and a squash-merged PR:

| Tempdoc | PR | Outcome |
|---|---|---|
| 875 consent boundary | #581 | grant risk ceiling (HIGH never durable-grantable), ingest argument-scope containment, offered=resolvable, undo containment |
| 876 offering truth | #584 | availability reconciled off the worker-health poll (no `/api/status` dependency — §D.1 settled), guards witness the real offering, `core_remember` registration, i18n resolution, demo-compose gated |
| 877 centralisation | #583 | dead PARAMETER_SCHEMAs deleted, one cap accessor, structuredData key constants, `RootsView`, `ToolArgs`/`AgentToolErrors`/`AgentTimeouts` |
| 878 run honesty & paging | #576 | MAX_ITERATIONS synthesizes (§D.2's empty-answer half settled), Layer-3 `Elided:` scar, compaction feeds the inclusion ledger, `total_chars` in the read header, model-visibility on the wire, `SourceAcquisition` enum |
| 879 policy axes | #582 | retry/confirm/audit wired with flip-tests; rateLimit deleted; store registers reconciled |
| 880 re-homing | #585 | catalog home in `io.justsearch.agent.tools`, adapters deleted, byte-exact catalog wire baseline, `HeldGate` |
| 881 standard profile | #586 | §D.3's diagnosis REFUTED — it was an XML tool-call grammar leaking into the reasoning channel; recovery + thinking-off retry shipped; live: 0/3 → 2/3 answered (881 §H) |

Live-verified post-merge by the orchestrator: zero-status-poll runs offer search+read; trust
panel matches the offering; the rank-1 prompt returns honest partials at the step cap.

Still open (owner levers, unchanged): 878 §D.8's effort-scaled iteration cap / n_ctx default
(run 2 of 881 §H hit the 16-token context-margin class); journal-by-reference (879 §
OWNER-DECISION); E0a/vop_* channel retirement (880 routed evidence to 532 + an owner call).
Dev-infra: worktree stacks cannot activate cuda12 without copying main's variant
(RuntimeActivationService resolves variants from the worktree root; the dev-runner's
JUSTSEARCH_SERVER_EXE is not consulted by activation) — hit twice this arc, workaround is a
1.1 GB copy; owns its fix with the dev-runner/runtime-activation seam.

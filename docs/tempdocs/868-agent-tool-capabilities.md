# 868 — The delegate agent's tool surface: what it can actually do

```
status:  BRIEFING — investigation, research, and theorization ONLY. No design freeze, no
         implementation, no deliverable beyond this tempdoc's own growth. The owner is
         theorizing in this dedicated thread; your job is to build the decision surface,
         not to decide.
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

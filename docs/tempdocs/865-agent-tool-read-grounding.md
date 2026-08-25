# 865 — Delegate-run evidence authority: mint it where it is established, claim only what it established

*(Chartered as 859's C-deep, "grounding from tool reads". The survey found that
premise broken — there is no read tool — and the scope was redefined to the
evidence-authority work underneath it. Filename kept; see §3.1 and §4.0.)*

```
status:  PLANNED — REV 2 (2026-08-25), amended after adversarial review
         (APPROVE-WITH-AMENDMENTS, 11 findings). Pipeline complete: substrate
         survey §3, theorization §4, research §5, design §7, plan §8. NOT
         IMPLEMENTED (charter is design-through-plan only). Scope redefined
         mid-charter to the EVIDENCE-AUTHORITY slice; the read-tool capability
         split out as 866 (owner-decision-gated).

         REV 2 — the four structural changes, all re-verified at source:
         1. THE CARRIER WINS (§7.1). The new-event-kind design is WITHDRAWN in
            favour of stamping the Java-minted delta onto the existing
            `ToolExecutionCompleted.structuredData`, exactly as `OutputLineage`
            already does. No new event kind, no descriptor/fixture/regen, no
            flush-carrier decision, no parity risk, no new timeline row — and it
            satisfies §5.2's minimal-presentation finding instead of colliding
            with it. This dissolves review finding A3 entirely.
         2. THE TERMINAL CENSUS WAS WRONG (§3.8b, §3.8e). `MAX_ITERATIONS` is not
            an `AgentError` at all — it emits `AgentDone.ofDisposition`, which
            hardcodes `List.of()`, through the SUCCESS path. The terminal with the
            MOST accumulated evidence discards all of it, and the old §3.8b
            two-option dichotomy was false because option (a) cannot reach it.
         3. §7.3 IS PRESENT-TENSE, NOT CREATED BY THIS WORK (§7.3).
            `AgentCitationResolver` degrades to `Resolved.none()` on matcher
            timeout TODAY, so a COMPLETED run already ships "Retrieved · not
            cited" over sources no scorer ever judged. It is independently
            landable AHEAD of PR-1 and is now sliced that way.
         4. THE L-3 GATE IS RETIRED (§4.11, §8.5). The compression→source mapping
            is statically recoverable, so PR-3 is implementation, not a
            measurement bet.
         Citation corrections: `scorerProducer` → `citationScorer`; "~10 error
         sites" → 4 production terminals + `emitError`'s sessionless seam;
         §4.4's divergence mechanism is cross-call accumulation, NOT dedup or
         identity filtering (both inert — the worker collapses to one hit per
         parent). SAC is unblocked; §8.7's block note is stale.
         The charter's premise is CORRECTED: there is no read tool (§3.1), so
         C-deep's gap-2 is a capability gap, not an evidence gap. What IS an
         evidence gap, and is present-tense: evidence is discarded on every
         terminal that is not a clean finish (§4.5), two surfaces already give
         different answers to "what did this run draw on" (§4.4), and the
         delegate plane carries an unlabelled retrieved-vs-received divergence
         (§4.6).
created: 2026-08-25
owner:   session bccfc163-7b8f-4b1a-b9e4-0c011632d8a1
follows: 859 §5 C-deep (the deferred enhancement, unblocked by #533's run-timeline
         projection and #530's evidence projection), 849 (retrieved-vs-received
         inclusion idiom), 603 D-3 (the document-level-provenance sentinel),
         565 §3.A (grounding sources from tool results)
number:  865. `check-tempdoc-numbers` clean at creation (565 distinct numbers,
         20 worktrees + origin/main, no collisions). Re-run before merge.
```

## 0. Boundary (owner-set, binding)

This is **delegate-EVIDENCE** work. It consumes the outputs of search/retrieval; it
does **not** touch search internals — orchestration, fusion, reranking, analyzers,
scoring. The search question is owner-owned. If a finding here implies a retrieval
change, log it and stop; do not design it.

## 1. The frame (from 859 §5 C-deep, §2.1)

Delegate-run grounding today rides **only** on the terminal `done` payload. Two gaps:

1. **No evidence exists during the run.** The reader watches tool cards fire with no
   evidence trail until the terminal event lands. Grounding is a surprise at the end,
   not a growing record.
2. **Answers grounded purely on TOOL READS get nothing.** The mint site
   (`AgentSession.collectGroundingSources`,
   `modules/app-agent/src/main/java/io/justsearch/agent/AgentSession.java:259`) only
   inspects `structuredData.searchResults` — i.e. only a *retrieval* tool result
   mints sources. The owner's real usage pattern ("read these three files and
   summarise") executes reads, never a search, and therefore produces **zero
   evidence**.

The long-term shape named in 859: **tool reads become sources.**

## 2. Substrate now available (all merged)

(Filled in from the substrate survey — see §3.)

- **#533 run-timeline projection** — ordered `ConversationEntry` stream; reasoning
  flushes onto the next *projecting* event. An evidence event needs a projection
  decision: does it project a timeline item, and where does it land?
- **#530 evidence projection** — `agentEvidence.ts`, `AgentSentenceCite`,
  the `admittedMatches` producer gate (CROSS_ENCODER admitted, EMBEDDING_COSINE
  withheld). Open question: what is the honest producer/tier for a source that
  **no scorer ever touched**?
- **#532 budget gate** — its "last action" fact could name the read source.
- **execution-surfaces register** (`governance/execution-surfaces.v1.json`) — a new
  evidence representation must be a **projection, not a fork**.
- **849's inclusion idiom** — "Retrieved · never sent to the model" is the closest
  prior art for an unscored-but-real evidence tier.
- **603 D-3's `DOC_LEVEL_SENTINEL`** (`AgentSession.java:233`) — the existing idiom
  for a source whose identity is a document and whose precise location is absent.

## 3. Substrate survey (primary-source, 2026-08-25, `main` @ `2a99dcd6`)

### 3.1 THE PREMISE-BREAKING FINDING — there is no read tool

859 §5 C-deep says: *"answers grounded purely on tool reads (agent read a file via a
tool, no retrieval call) get NO sources at all."* **The delegate agent cannot read a
file at all.** The complete core tool surface is six operations
(`AgentToolsOperationCatalog.java:100-107`, `definitions = List.of(searchIndex(),
browseFolders(), ingestFiles(), fileOperations(), navigateToSurface(), remember())`):

| Wire ref | Class | Returns content? | Doc identity? | Span? |
|---|---|---|---|---|
| `core.search-index` | `SearchTool.java` | yes — **excerpts** | `path` + `parentDocId#chunkIndex` | `startLine`/`endLine` |
| `core.browse-folders` | `BrowseTool.java` | listings only (names/sizes/paths) | no | no |
| `core.ingest-files` | `IngestTool.java` | no (side-effect) | — | — |
| `core.file-operations` | `FileOperationsTool.java` | no (destructive FS) | — | — |
| `core.remember` | `RememberFactHandler.java` | no (memory write) | — | — |
| `core.navigate-to-surface` | `NavigateToSurfaceHandler.java` | no (UI intent) | — | — |

`modules/app-agent/src/main/java/io/justsearch/agent/tools/` contains exactly
`SearchTool`, `BrowseTool`, `IngestTool`, `FileOperationsTool` (+ `AgentToolPaths`,
`ConflictStrategy`, `FileOperation`, `FileOperationExecutor`, `FileOperationLog`).
No `ReadTool`, no `OpenTool`, no `FetchTool`.

**Corroboration from 859's own live session** (§7 "Budget reality check"): the
read-three-files task's decline arm produced *"I don't have access…"*. That reads as
literal truth about the tool surface, not only as a budget artifact.

**Consequence for the charter.** "Tool reads become sources" presupposes tool reads.
Today the only content-bearing core tool IS the retrieval tool, and its results
already mint sources. So C-deep's gap-2 is **not** an evidence-projection gap; it is
a *capability* gap wearing an evidence gap's clothes. §4 re-frames on this.

**The one live exception: MCP-host tools.** `McpToolProjection.toOperation`
(`modules/app-services/src/main/java/io/justsearch/app/services/mcphost/McpToolProjection.java:66-97`)
projects any tool a connected MCP server advertises into an `Audience.AGENT`
`Operation` with the server's own JSON Schema verbatim
(`Interface.of(tool.inputSchemaJson(), "{\"type\":\"object\"}")`) and
`RiskTier.MEDIUM` + `ConfirmStrategy.Inline`. A user's filesystem MCP server can
therefore give the agent a real read tool **whose result shape JustSearch does not
control**. Any minting rule must be honest about that: a source can only be minted
from a result whose document identity is declared, not guessed.

### 3.2 The mint site — one authority, narrow input

`AgentSession.collectGroundingSources()`
(`modules/app-agent/src/main/java/io/justsearch/agent/AgentSession.java:259-324`) is
the single mint authority. It walks `executedTools` and skips every result whose
`structuredData` lacks `searchResults` (`:265-267`). Two identity arms, both already
present:

- **chunk-precise** — key `parentDocId#chunkIndex` (`:279`), real ordinal + line span.
- **document-level** — key `doc#path` (`:295`), emitted with
  `DOC_LEVEL_SENTINEL = -1` (`:233`) for chunk ordinal and both lines. The javadoc
  (`:241-255`) states the governing principle verbatim: *"A grounding source's
  IDENTITY is the DOCUMENT it came from; chunk identity is OPTIONAL ENRICHMENT…
  Source EXISTENCE is never gated on that enrichment."*

**This is the prior art the charter needs.** A read-source is exactly a
document-level source: identity = path, no chunk ordinal, no matcher-eligible span.
603 D-3 already built the shape and the FE already renders it (deep-links to file
top, no inline marks).

`AgentSource` carries **no score field** (`AgentEvent.java:156-164`) — deliberate,
559 §5. So "unscored" is not a new state for the wire record; it is the existing one.

### 3.3 Retrieved-vs-received is already two data structures

- **Retrieved (full fidelity):** `AgentStepRunner.java:866` —
  `session.recordExecution(call, toolResult)` stores the complete untruncated
  `OperationResult` into `AgentSession.executedTools` (`AgentSession.java:622-631`).
- **Received (progressively lossy):** `AgentStepRunner.java:871-874` appends only
  `AgentContextCompressor.truncate(toolResult.message())` — the text summary, capped
  at `MAX_TOOL_RESULT_CHARS` (`AgentContextCompressor.java:32,51-57`) — as a
  `role:"tool"` message. Then `:875` runs `compressToolMessages`
  (`AgentContextCompressor.java:60-93`), which **strips `Excerpt:` lines**
  (`:99-104`) from all but the most recent `keepLastResults` tool results.

So on the delegate plane the 849 distinction is not hypothetical and not a badge we
would have to invent a producer for: **a source retrieved early in a long run has
provably had its excerpt stripped from the prompt by Layer 3.** The delegate plane
today paints no inclusion badge at all — `agentEvidence.ts:51-60` leaves
`contextInclusion` absent, never zero-filled (correct, since nothing computed it).

### 3.4 The wire and its two carrier rules

Event vocabulary: `AgentEvent` sealed interface,
`modules/app-agent-api/src/main/java/io/justsearch/agent/api/AgentEvent.java:19-594`
(23 variants). Name/payload authority: `AgentEventPayloads.java:34-59` (`name()`),
`:67-236` (`base()`). Every event funnels through ONE chokepoint,
`AgentLoopService.wrapEventConsumer` (`AgentLoopService.java:841-861`): trace-wrap →
`session.deliverToObservers` (live SSE) → `runStore.appendEvent` (durable journal) →
telemetry.

Declared schema (SSOT): `AgentRunShape.EVENT_SCHEMA`
(`modules/app-services/src/main/java/io/justsearch/app/services/conversation/AgentRunShape.java:64-197`),
pinned to the producer by `AgentEventSchemaConformanceTest` +
`AgentEventPayloadConformanceTest`.

Regen chain for a new event kind:
1. add the `AgentEvent` record + `AgentEventPayloads.name`/`base` cases;
2. add the `EventDescriptor` to `AgentRunShape.EVENT_SCHEMA`;
3. `./gradlew.bat :modules:app-services:test -Dupdate.shapes.fixture=true` regenerates
   `scripts/codegen/shapes.fixture.json`;
4. `node scripts/codegen/gen-shape-handlers.mjs` writes
   `modules/ui-web/src/api/generated/shape-handlers/core-agent-run.ts`;
5. CI: `scripts/ci/check-shape-handler-regen.mjs`.

**Two carrier rules exist, and they are different by construction.**

- *Live* — `AgentSessionController.applyReasoningBoundary`
  (`modules/ui-web/src/shell-v0/controllers/AgentSessionController.ts:1469-1505`)
  cuts the open reasoning region on **any** wire event name not in
  `REASONING_BOUNDARY_EXEMPT` (`:147-152` = `heartbeat`, `state_snapshot`,
  `session_started`, `RUN_STARTED_EVENT`). A new event name cuts a region **by
  default**, whether or not it renders anything.
- *Record* — `AgentInteractionMapper.fromRunEvents`
  (`modules/app-agent/src/main/java/io/justsearch/agent/AgentInteractionMapper.java:294-360`)
  flushes pending reasoning onto the next event that **actually projects**
  (`fromRunEvent(...).isEmpty() → continue`, `:296-298`). This is 859 A's corrected
  rule: *a projection must name a carrier that exists in the projected stream.*
- Parity is gated: `modules/ui-web/src/shell-v0/views/search-v3/sv3-timeline-parity.test.ts`
  asserts both paths produce the same `(kind, text)` sequence.

**Therefore a new mid-run evidence event is not free.** Emitting it without adding a
`fromRunEvent` case makes the live path cut a reasoning region that the record path
does not — a parity divergence the existing test should catch. Either it projects on
both sides or it is exempt on both sides. There is no "live-only" option that is
honest.

### 3.5 The durability gap is the same defect, one event earlier

`AgentProgress`'s `phase` is a **free-form string**, not a typed enum — `"budget_raised"`
(`AgentStepRunner.java:960`), `"context_gate_reapplied"` (`:345`),
`"context_compacted"` (`:353`). And `progress` has **no case** in
`AgentInteractionMapper.fromRunEvent` — it falls to `default → Optional.empty()`
(`AgentInteractionMapper.java:253`). So a raise/re-application is narrated live and
**dropped on reload**. Also dropped: `chunk`, `tool_call_approved`,
`tool_batch_proposed`, `tool_call_virtual`, `budget_update`, `budget_gate`,
`context_gate`, `context_compacted`, `session_started`, `handoff_proposed`,
`state_snapshot`, `directive_acknowledged`, `workflow_started`.

This is the fold-in the charter names: **evidence events and progress notes are the
same shape of problem** — a run-narration item that must project durably. They must
share one vocabulary.

### 3.6 The honesty gate, and what it does NOT cover

`isVerifiedProducer` (`modules/ui-web/src/shell-v0/components/chat/evidenceProjection.ts:410-412`)
is the one authority; `VERIFIED_SCORER = 'CROSS_ENCODER'` (`:394`). Applied at
`admittedMatches` (`recordEvidence.ts:76-81`) → `agentEvidence.ts:122`, and inlined
at `citationResolve.ts:146`. `undefined`/`null`/`''` are **admitted** (fail-open for
legacy records); any other known producer, i.e. `EMBEDDING_COSINE`, is withheld.

Java `ScorerKind` (`modules/app-api/src/main/java/io/justsearch/app/api/DocumentService.java:474-493`):
`CROSS_ENCODER`, `EMBEDDING_COSINE`, `NONE`. There is **no "unscored" tier** — and
none is needed: `GroundingTier` (`'high'|'medium'|'low'`,
`evidenceProjection.ts:274-297`) is minted **only** from a similarity, and a source
that was never examined already has an orthogonal state that is not a tier:
`SourceExamination = 'cited' | 'unexamined' | 'examined-uncited'`
(`evidenceProjection.ts:683-687`), labelled by `sourceGroundingLabel`
(`:698-704`) as *"Retrieved · not cited"* / *"Retrieved · not examined"*.

The 849 inclusion axis is separate again: `inclusionBadge`
(`evidenceProjection.ts:776-801`) over `ContextInclusion = 'included' | 'partial' |
'dropped'` (`citationTypes.ts:114`) → *"Sent to the model"* / *"Partly sent to the
model"* / *"Retrieved · never sent to the model"*, with `suppressGroundingFor`
(`:929-931`) stopping a `dropped` source from also claiming it grounded a sentence.

**The binding precedent (847 §:69-71, :274-279 — the cosine-panel lesson):** gating
the inline marks alone is not the gate. Every surface that turns a similarity into a
tier or a verification-sounding label must apply the *same* producer check, because
one surface failing closed while a sibling still paints a cross-encoder-calibrated
tier from a cosine score is worse than a uniformly ungated panel. *The gate must move
with the data, not with one of its two consumers.*

### 3.7 The register

`governance/execution-surfaces.v1.json` auto-scans TS under `modules/ui-web/src` for
`(SearchTrace|RetrievalCitation|AnswerEvidenceSource)` and Java imports of
`SearchTrace` / `DocumentService.ContextCitation` / `evidence.EvidenceSpan`
(`:32-56`), `expectedMinPopulation: 12` (currently 28).

Two facts that settle the projection-vs-fork question:

- **`agentEvidence.ts` is already registered** as `agent-evidence-projection`,
  `kind: "projection"`, guard `test:agentEvidence.projection` (`:246-253`) — with the
  register's own note that registering it as a *carrier* would repeat a false claim
  removed from `sv3-record-evidence`.
- **`SearchTrace` is NOT the authority for agent runs.** The file states it directly
  (`:4`): SearchTrace and ContextCitation "share no field and never co-occur (**the
  RAG ask flow emits no trace**)". Delegate runs emit no `SearchTrace`; the delegate
  plane's evidence is kin to the `ContextCitation` sibling via the shared
  `AnswerEvidenceSource` supertype. The one agent-side `SearchTrace` consumer is
  `SearchTool` reading the CORRECTION stage for corrected query text (`:109-115`) —
  narration, not evidence.

So a read-source must project through `AnswerEvidenceSource` (which the auto-scan
covers) and be declared in the register as a projection under
`agent-evidence-projection`'s lineage — not modelled fresh. The register's stated
honest limit (`:5`) is that it cannot detect an undeclared fork that re-models
evidence without referencing a registered type; that is the failure mode to avoid by
judgment, not by gate.

### 3.8a The register blind spot — VERIFIED, not hypothesised

§4.4 hedged this ("verify in design before asserting"). Verified 2026-08-25; it holds,
and the mechanism is exact:

- The auto-scan's TS pattern is `(SearchTrace|RetrievalCitation|AnswerEvidenceSource)`
  (`governance/execution-surfaces.v1.json:47`).
- `toolSearchCard.ts` imports `CardHit`, `CardSnapshot`, `SearchProvenance` from
  `ResultsCard.js` and `filenameOf` from `evidenceProjection.js`. **None of those
  identifiers matches the pattern** — `SearchProvenance` is not `SearchTrace`. The
  file is invisible to the scan and carries no register row.
- Its consumer **is** registered: `ResultsCard.ts` at `:226-227`, guard
  `test:ResultsCard.searchTrace.test`.
- And `toolSearchCard.ts` does not merely pass data through — it **constructs a
  `SearchProvenance`** (`:92-101`): `actor: 'agent'`, the query, and the counts.
  `ResultsCard.ts:126-133` states what that type is for: *"every label here is a
  positive claim about how the search ran."*

So an unregistered file mints positive claims about how an agent search ran, from the
same `searchResults` the Java mint site reads, under a different identity and dedup
rule (§4.4). That is the register's §5 honest limit — *"an undeclared fork that
re-models execution from scratch without referencing a registered type"* — with a
live instance. **The fix therefore includes a register row**, not only a code change.

### 3.8b The terminal census, corrected (rev 2 — review finding A1)

`AgentError(String error, String errorCode, String errorClass, String retryAction,
Integer retryAttempt, TraceContext trace)` (`AgentEvent.java:307-314`) has no
evidence component, against `AgentDone`'s four: `sources`, `citations`,
**`citationScorer`** (not `scorerProducer` — corrected) and `disposition`
(`:174-303`).

**Rev 1 framed this as a two-option choice. That dichotomy was false**, and the
counter-example is the most important terminal in the set:

> **`MAX_ITERATIONS` is not an error at all.** `AgentLoopService.java:570-586` sets
> `agentSuccess = true` and emits **`AgentEvent.AgentDone.ofDisposition(...)`**,
> whose factory (`AgentEvent.java:287-303`) hardcodes `List.of(), List.of(),
> SCORER_NONE`. So the run that exhausted every iteration — the terminal holding the
> **most** accumulated evidence of any path — discards all of it, through the
> **success** path, and `AgentError` is nowhere near it.

Option (a) ("widen `AgentError`") therefore **cannot reach this terminal at all**.
That is not a cost argument against it; it is a coverage refutation.

The corrected census of evidence-losing terminals:

| Terminal | Site | Event emitted | Evidence |
|---|---|---|---|
| `MAX_ITERATIONS` | `AgentLoopService.java:577-586` | `AgentDone.ofDisposition` (**success path**) | hardcoded empty |
| `INTERNAL_ERROR` | `AgentLoopService.java:588-601` | `AgentError` via `emitError` | none |
| cancel / tool-loop / budget / transient / unknown-tool / handoff-cycle | `AgentStepRunner` (§4.5 list) | `AgentError` | none |
| `COMPLETED`, `BUDGET_EDGE_FINALIZE` | `AgentStepRunner.java:461,581` | `groundedDone` | **present** |

**Out of scope, named rather than silently excluded:** the `NO_TOOLS` path and
`AgentSessionFinalizer`. Neither is part of this design's claim and neither was
audited here.

**Cost correction.** Rev 1 said "~10 construction sites". The real shape is **4
production terminal sites** plus the actual obstacle: `emitError`
(`AgentLoopService.java:880-890`) is a **sessionless seam** — its signature takes a
`Consumer<AgentEvent>` and no `AgentSession`, across ~18 call sites, several of which
have no session in scope at all. Threading evidence through it is the expensive part,
not the record widening.

**And this is why the carrier design (§7.1) wins rather than merely being tidier:**
a delta stamped on the tool result rides the wire long before any terminal runs, so
it does not care which terminal fires, whether that terminal is an error or a
success, or whether a session is in scope at the emit site. It reaches
`MAX_ITERATIONS` — which nothing else in the option set does.

**One honesty correction to §4.5's wording.** "The reader sees zero evidence" was too
strong. `structuredData.searchResults` already reaches both planes on every
`tool_exec_completed` (§4.2), so the tool cards do render hits. What dies at these
terminals is the **mint** — the deduped, identity-bearing, citation-indexable source
set. Which sharpens rather than weakens the fork argument of §4.4: at exactly these
terminals the *only* surviving account of the run's evidence is the unregistered
second projection.

### 3.8c `OutputLineage` is the pattern to follow — and the axis not to overload

`modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/OutputLineage.java`
(577 §2.14 Root III) is a live precedent for §4.7's "declare it on the operation":

- One classifier, `forOperationId`, keyed on a single declaration site:
  `CORPUS_READERS = Set.of("core.search-index", "core.browse-folders")` (`:44-45`).
- Stamped **once at dispatch** (`AgentStepRunner.java:859-864`, only on successful
  results) onto `structuredData.lineage`; the FE frames from it and *never
  re-derives* (`toolOutputLineage.ts` — "the ONE tool-output text-provenance
  authority").
- Its own javadoc states the extension contract: *"a new corpus reader is a one-line
  addition here, not a guess scattered across renderers."*
- And it already anticipates the missing capability in prose — CORPUS_QUOTED is
  documented as *"search hits, browse listings, **file reads**"* (`:24`) — a read
  tool was expected here before 866 named it.

**But it must not be overloaded into the evidence axis, and `browse-folders` is the
proof.** Browse listings are `CORPUS_QUOTED` (the bytes are the user's own content)
yet must mint **no sources** — naming a file is not evidence (§4.7's "content
received, not merely named"). So lineage answers *"are these bytes the user's
corpus?"* and evidence-contribution answers *"do these bytes carry an addressable
document identity?"* — genuinely different questions with a genuinely different
answer for at least one existing tool. Follow `OutputLineage`'s **shape** (one
declaration site, stamped once at dispatch, never re-derived); do not extend its
**enum**.

### 3.8d The emit locus is already clean

`AgentStepRunner.java:866-868`:

```java
session.recordExecution(call, toolResult);
sink.accept(new AgentEvent.ToolExecutionCompleted(call.id(), toolResult));
```

The session (which owns the cross-turn dedup `seen` set) and the `sink` are both in
hand at one point, immediately after the result is recorded and before the
compressor runs (`:870-875`). An incremental evidence emission slots here with no
restructuring.

### 3.8 The budget gate's "last action"

Backend `BudgetGatePending` (`AgentEvent.java:439-445`) carries only
`(tokensNeeded, tokensRemaining, totalTokensConsumed, trace)`. The "last action"
fact is built **client-side**: `sv3-run.ts:366,379` finds the last `kind === 'tool'`
feed item and surfaces `lastTool.call.toolName` — the bare tool name, discarding
`arguments` and `structuredData` (both present on `ToolCall`,
`AgentSessionController.ts:77-94`). So naming the read document at the gate is a
pure FE change over data already on the client.

## 4. Theorization (2026-08-25)

### 4.0 The owner's five questions, re-framed by the survey

| # | As chartered | After §3 |
|---|---|---|
| 1 | What mints a tool-read source? | There are no tool reads. The live question is: **what should a tool have to declare** before its result mints a source — because the mint site hardcodes one tool's key today (§4.7). |
| 2 | Honesty vocabulary for unscored sources? | No new **tier** is needed or wanted — three orthogonal axes already exist. The genuine gap is a missing **acquisition** axis and a label vocabulary written entirely in the retrieval idiom (§4.6). |
| 3 | Mid-run evidence event vs terminal-only? | **A false dichotomy.** The evidence data is already on the wire and already in the record, on every tool completion (§4.2). The real question is *who mints* — and it is a fork question, not a transport question (§4.3). |
| 4 | How does the panel distinguish read- from retrieval-sources? | Premature. The live, un-named defect is that two surfaces **already** give different answers to "what did this run draw on" (§4.4). |
| 5 | L-legs? | Unchanged and load-bearing — plus one new leg the survey makes mandatory (§4.11). |

Two findings the charter did not anticipate reorder the whole thing: evidence is
**already** available mid-run and is being thrown away (§4.2, §4.5), and the delegate
plane **already** carries the retrieved-vs-received divergence, unlabelled (§4.6).
Both are present-tense defects. The chartered enhancement (tool reads) is, by
contrast, blocked on a capability that does not exist.

### 4.1 The charter splits into three, and only two are evidence work

1. **Evidence that exists but is discarded** — mid-run, on unhappy terminals, and in
   the retrieved-vs-received gap. Real today, no capability change, squarely in
   scope. This is the bulk of 865.
2. **The vocabulary that would let a read-source exist honestly if one ever arrived**
   — the acquisition axis, the declared evidence contract, the durability decision.
   Cheap, and it is the substrate the chartered enhancement needs.
3. **The read tool itself** — a capability change on the delegate tool surface.
   Neither evidence work nor search internals; a third thing. Sketched as **#866**
   (§4.9) for the owner to decide, not folded in here.

Naming this split is the main output of theorization. Building (2) without (3) is
not wasted: it is what makes (3) safe to add later, and (1) needs it anyway.

### 4.2 Mid-run evidence needs no new event kind — the data is already on both paths

`ToolExecutionCompleted(String callId, OperationResult result, TraceContext trace)`
carries the **whole** `OperationResult` (`AgentEvent.java`, the record's declaration).
And `structuredData` survives to both consumers:

- **Wire:** `AgentEventPayloads.toolCompletedPayload` (`:302-312`) —
  `if (!e.result().structuredData().isEmpty()) payload.put("structuredData", …)`.
  Declared in the shape SSOT as an optional free-form object
  (`AgentRunShape.java:90-99`, "present only when the operation produced structured
  content").
- **Record:** `AgentInteractionMapper.fromRunEvent`'s `tool_exec_completed` case
  (`:139-152`) copies `structuredData` onto the persisted tool-activity event, with
  the comment (561 #6) *"carry the producer evidence onto the record event so the
  record render shows the same evidence cards as the live overlay."*

So the client holds `searchResults` — the **exact input** `collectGroundingSources`
reads — the moment each search completes, live and on reload. Design question 3's
expensive arm (new event kind → `AgentEventPayloads` → `EVENT_SCHEMA` → fixture
regen → `gen-shape-handlers` → `fromRunEvent` case → flush-carrier decision → parity
test) buys **no new information**. It buys authority, which is a different thing, and
§4.3 is where that is actually decided.

Corollary worth stating plainly, because it inverts the charter's cost intuition:
**a mid-run evidence trail is the cheap option and terminal-only is the expensive
one** — terminal-only is what forces evidence through a `done` event that half the
terminal paths never emit (§4.5).

### 4.3 The real question is authority, not transport — and it is a fork question

If the FE derives mid-run sources from `structuredData`, the mint rule exists twice:
once in Java (`AgentSession.collectGroundingSources`, `:259-324`) and once in TS.
Two languages, one rule, no compiler binding them. The repo's own discipline
(`execution-surfaces`, projection-not-fork, and 553's representation-drift class)
says that is a fork even while the two agree.

Three candidate shapes, with the honest trade:

- **(a) FE re-derives.** Zero backend change, immediate. Fork by construction; and
  §4.4 shows a re-derivation of this exact data has *already* drifted once.
- **(b) Backend emits incrementally.** One authority. Costs the full regen chain and
  a projection decision. But it also fixes §4.5 for free — evidence emitted as it is
  minted survives a terminal that never reaches `groundedDone` — and it is the only
  arm that puts evidence and progress notes in one vocabulary (§4.8).
- **(c) Extract the rule to one place and project it twice.** Superficially the
  "clean" answer; in practice a cross-language shared rule with no shared test is
  (a) with extra ceremony, unless it is generated. Not obviously worth it.

Theorization leans hard to **(b)**, and the reason is not purity: it is that (b)
solves three named defects (mid-run absence, unhappy-terminal loss, durability
vocabulary) with one mechanism, while (a) solves one and adds a fork.

> **Rev 2 — (b) was right about AUTHORITY and wrong about TRANSPORT.** The design
> keeps backend minting (§7.1) but delivers it by **stamping the existing
> `tool_exec_completed.structuredData`**, not by adding an event kind. Everything
> below about the flush-carrier interaction therefore describes a branch the design
> did not take; it is kept because it is why the carrier option is worth its one
> cost. See §7.1.

But (b)-as-a-new-event's flush-carrier interaction was real and would have had to be
designed, not waved at:

> A new event name cuts the live reasoning region **by default**
> (`AgentSessionController.applyReasoningBoundary`, `:1469-1505` — anything outside
> `REASONING_BOUNDARY_EXEMPT`, `:147-152`), while the record path only flushes onto
> events that **actually project** (`AgentInteractionMapper.java:294-360`). An
> evidence event that projects a timeline item is fine on both. An evidence event
> that is *silent* in the timeline must be added to `REASONING_BOUNDARY_EXEMPT`, or
> live and record disagree about where a reasoning block ends — and
> `sv3-timeline-parity.test.ts` is the gate that would catch it.

That is 859 A's corrected rule (*a projection must name a carrier that exists in the
projected stream*) meeting its first new event. Worth noting that the exempt-list is
the mechanism by which "this event is not a timeline item" gets *said* rather than
assumed — a good precedent, and the design should treat adding to it as a deliberate
declaration, not a test-fixing move.

### 4.4 An existing, un-named defect: two surfaces already disagree about the run's evidence

`toolSearchCard.ts` (`modules/ui-web/src/shell-v0/components/chat/toolSearchCard.ts`)
projects the *same* `structuredData.searchResults` into the shared results card that
`ToolCallCard` renders — and it applies a **different rule** from the mint site:

| | `collectGroundingSources` (Java, `AgentSession.java:259-324`) | `agentSearchCardData` (TS, `toolSearchCard.ts:62-90`) |
|---|---|---|
| identity | `parentDocId#chunkIndex`, else `doc#path` | `id = path` (`toAgentSearchHit`, `:41-47`) |
| dedup | yes, `LinkedHashSet seen` across all turns | none |
| identity-less hits | dropped (`:309`) | kept |
| scope | whole run | one tool call |

**Rev 2 correction (review A7): the mechanism above is right on paper but two of its
three rows are inert in practice.** The worker collapses chunk hits to one per parent
document before results reach the agent (`SearchExecutor.collapseChunkHitsToParents`,
`:1029-1051`), falling back to `hit.docId()` when `PARENT_DOC_ID` is missing. So
every hit arrives with a parent identity and no intra-call duplicates: the dedup row
and the identity-less-drop row never fire **within one call**.

The live divergence is therefore **cross-call accumulation, not filtering**: a
document returned by call 1 and again by call 3 appears on two tool cards and once in
the panel. Rev 1's "ten hits on the cards, six in the panel" was the right shape via
the wrong mechanism — it is a multi-call effect, and a run with a single search shows
no divergence at all. That **lowers this finding's severity** (a one-search run is
consistent) without touching the fork argument, which is about two authorities
existing, not about how often they disagree.

The reader still gets two answers to "what did this run draw on" across a multi-call
run, with no surface explaining the difference. Nobody has reported this because both
surfaces look plausible alone.

This is also, plausibly, a live instance of the register's own stated honest limit
(`execution-surfaces.v1.json:5`): the gate "cannot detect an undeclared fork that
re-models execution from scratch without referencing a registered type."
`toolSearchCard.ts` re-models run evidence while referencing only `CardHit` /
`filenameOf`, so the auto-scan's TS pattern
(`SearchTrace|RetrievalCitation|AnswerEvidenceSource`) never sees it. **Verify this
in design before asserting it** — the register may cover it another way — but if it
holds, 865 should register the projection rather than leave the second authority
undeclared.

The design should decide whether these two surfaces are *supposed* to differ (a tool
card is a receipt of one call; the panel is the run's evidence set) — a defensible
position — and if so, make the difference legible rather than silent. Either way,
one rule must be the authority for "what the run drew on."

### 4.5 Evidence dies on every terminal that is not a clean finish

`groundedDone` is called from exactly **two** sites: `AgentStepRunner.java:461`
(`BUDGET_EDGE_FINALIZE`) and `:581` (`COMPLETED`). Every other terminal emits
`AgentError` + `markTerminated(...)` with no sources at all:

`CANCELLED` (user, `:142-147`, `:318-322`, `:630-635`), `CANCELLED` (budget,
`:424-429`), `TOOL_LOOP` (`:179-184`), `BUDGET_EXHAUSTED` (`:481-487`),
`LLM_TRANSIENT` (`:514-519`), `EMPTY_RESPONSE` (`:568-574`), `UNKNOWN_TOOL`
(`:650-656`, `:761-766`), `HANDOFF_CYCLE_DETECTED` (`:671-677`).

A run that searched ten times and was then cancelled, or ran out of budget without
finalising, shows the reader **zero** evidence — while `session.executedTools` holds
every hit. This is not a UX nicety: the reader stopped a run *because* they were
watching it, and stopping is exactly the case where "what did it find before I
stopped it?" matters most. 859 §7's own reality check ("initial 3840 exhausted after
two tool calls") says the owner's real runs land on these paths routinely.

This gap is invisible under a terminal-only design and disappears under an
incremental one — which is the strongest single argument for §4.3(b).

An adjacent question the design must answer honestly: on a cancelled run there is no
final answer, so there are no sentence citations and no matcher verdict. The
evidence that survives is *"documents this run drew on"*, not *"documents that ground
this answer"* — and those are genuinely different claims (§4.6). A cancelled run's
panel must make the weaker claim, not inherit the stronger one's wording.

### 4.6 Retrieved-vs-received is real on the delegate plane, today, and unlabelled

§3.3 established two data structures. The consequence is sharper than 849's RAG case:

- RAG drops passages at **assembly** time — one cut, one decision.
- The agent plane degrades **continuously**. `AgentContextCompressor.compressToolMessages`
  (`:60-93`) strips `Excerpt:` lines (`:99-104`) from all but the most recent
  `keepLastResults` tool results, *every iteration*. A source found in iteration 2 of
  a twelve-iteration run has provably had its text removed from the prompt long
  before the answer was written — yet it appears in `done.sources` exactly like a
  source found in iteration 12.

The panel today paints no inclusion badge on delegate sources at all
(`agentEvidence.ts:51-60` leaves `contextInclusion` absent — correct, since nothing
computes it). So the honest options are: compute it, or keep saying nothing. What is
*not* honest is the current implicit reading, where a stripped source sits
indistinguishable from a live one under a heading the reader takes to mean "this
grounded the answer."

849 already built the vocabulary (`ContextInclusion`, `inclusionBadge`,
`suppressGroundingFor`) and its `dropped` label — *"Retrieved · never sent to the
model"* — describes the agent case almost exactly. Reusing it is projection, not
fork. The open question is whether the compressor can report *which* results it
stripped without becoming a second budget authority; §4.11 lists it as a derisk item.

There is also a subtler variant: `AgentContextCompressor.truncate` caps the tool
*message* at `MAX_TOOL_RESULT_CHARS` on the way in (`:32,51-57`). A search that
returned twelve hits may have had hits 9-12 truncated out of the message the model
read, while all twelve are minted as sources from the untruncated `structuredData`.
That is `partial`/`dropped` at a per-hit granularity, and it may be the more common
case than Layer 3 stripping. Measure before designing which one matters.

### 4.7 What should mint a source: a declared contract, not a tool name

`collectGroundingSources` asks one question — *does this result carry `searchResults`?*
(`AgentSession.java:265-267`). The mint authority therefore knows one tool by name.
Every future content-bearing tool needs a new hardcoded branch, and an MCP-provided
read tool (§3.1) can never mint anything, because nothing in JustSearch knows the
shape of its result.

The projection-shaped alternative: **an `Operation` declares its evidence
contribution**, and the mint site projects from the declaration. Something in the
spirit of `none` / `document-level` (identity is a path) / `chunk-precise` (identity
is `parentDocId#chunkIndex`, with a line span) — the two arms 603 D-3 already built
inside `collectGroundingSources`, lifted from a hardcoded `if` into a declared
property. `Operation` already carries policy, availability, lineage, provenance and
executor tags; an evidence facet is not a new kind of thing.

Three consequences worth theorizing about now:

- **MCP tools opt in, they are not opted in.** An external tool mints evidence only
  if its adapter declares an identity mapping. Absent a declaration, its result is
  content the model received with no addressable provenance — which is the truth,
  and the panel should be able to say so rather than pretend the run was ungrounded.
- **Listing is not evidence.** `browse-folders` returns file *names*. If naming a
  file minted a source, the panel would fill with documents the model never read a
  word of. Candidate invariant: **content received, not merely named.** This is the
  line that stops "tool reads become sources" from degenerating into "tool
  mentions become sources."
- **A read range is not a relevance span.** If a read tool ever returns lines 40-80
  of a file, that range is *what the model saw*, not *what supports a sentence*. It
  belongs on the inclusion/acquisition axes, and it must not be fed to the citation
  matcher as if it were a scored passage. The existing `DOC_LEVEL_SENTINEL = -1`
  (`AgentSession.java:233`) is the established way to say "no matcher-eligible
  location," and a read-source should use it even when a range is known — or the
  design must argue explicitly why a read range is safe to promote.

### 4.8 The honest vocabulary is an acquisition axis, not a tier

The charter asks for "the honest producer/tier for a tool-read source that was never
scored." The survey's answer is that the question contains the trap.

Three orthogonal axes already exist and none of them wants a new member:

- **producer** (`ScorerKind`: `CROSS_ENCODER` / `EMBEDDING_COSINE` / `NONE`;
  gated by `isVerifiedProducer`, `evidenceProjection.ts:410-412`) — an unscored
  source is `NONE`, which already yields no marks. Nothing to add.
- **`GroundingTier`** (`'high'|'medium'|'low'`, `evidenceProjection.ts:274-297`) —
  minted **only** from a similarity. An unscored source must simply not have one.
  Adding an `unscored` member would be precisely the cosine-panel move: a
  tier-shaped label over a fact no scorer produced. **Binding: do not.**
- **`SourceExamination`** (`'cited'|'unexamined'|'examined-uncited'`,
  `evidenceProjection.ts:683-687`) — already the "nothing scored this" axis, and
  deliberately *not* a tier.

What is genuinely missing is **how the document entered the run**. Every label in
`sourceGroundingLabel` (`:698-704`) opens with the word *"Retrieved"* — a claim that
a retriever ranked it. Applied to a file the agent opened by name, that is false.
The vocabulary is written in the retrieval idiom and silently asserts it.

Principle candidate: **a label vocabulary borrowed from one acquisition mode
silently asserts that mode.** The fix is an explicit axis — how the document was
acquired (a scorer ranked it / the agent named it / it appeared in a listing) —
distinct from whether it was examined and from whether the model received it.

Two things fall out that are worth writing down before design:

- **An opened document has *less* relevance evidence than a retrieved one, not
  more** — and the temptation runs the other way, because "the agent read it and
  then summarised it" *feels* like the strongest possible grounding. It is not
  grounding at all: it is evidence the document was **available**, not that any
  sentence came from it. A design that quietly promotes read-sources because they
  seem obviously relevant repeats the cosine-panel error in a new costume, and this
  paragraph exists to make that move visible when someone proposes it.
- **Acquisition is plausibly a set, not a scalar.** If the agent searches, finds
  `X`, then opens `X`, the `doc#path` dedup collapses them into one source with two
  acquisitions. Whether the panel should say so is a design question; that the data
  model should be able to represent it is close to settled.

### 4.9 The read tool is a separate charter — sketch as #866

(`world-state` at time of writing: highest claimed **#865**, next free **#866**.)

Out of scope here, and the reason is not only the owner's boundary. It is a
capability question with its own risk surface that has nothing to do with evidence:

- **Security and scope.** A read tool reads arbitrary paths. `AgentToolPaths` and the
  `RiskTier`/`ConfirmStrategy` lattice exist; whether an unconfirmed read of any
  local file is acceptable is a policy decision, not a projection decision.
- **Budget.** 859 §7 measured a 3840-token budget exhausted by two tool calls. Whole
  files are far larger than search excerpts; a read tool without a size discipline
  makes the budget problem categorically worse, and 859 D's gate is calibrated on
  today's tool sizes.
- **Overlap with what exists.** `search-index` accepts `path_prefix`, so
  "summarise these three files" is *partially* servable today via scoped search over
  excerpts. Whether excerpts suffice is an empirical question worth measuring before
  building a new tool — and measuring it is cheap.
- **The compressor.** Layer 2's `MAX_TOOL_RESULT_CHARS` cap would truncate a whole
  file, so a naive read tool would mostly deliver file *prefixes* while the evidence
  layer claims the document. That is a manufactured retrieved-vs-received defect —
  and it is the specific reason §4.6's work should land *before* any read tool, not
  after.

If #866 is chartered, 865's declared evidence contract (§4.7) is exactly what it
plugs into, and 865 should be able to demonstrate the seam with a test double rather
than a real read tool.

### 4.10 The durability fold-in generalises: the default-drop is the defect generator

The charter asks that evidence events and progress notes share one vocabulary. The
survey suggests the sharper statement is about the *mechanism*, not the vocabulary:

- `AgentEventPayloads.name()` switches over a **sealed interface** — the compiler
  forces a decision for every event kind.
- `AgentInteractionMapper.fromRunEvent` switches over a **String** with
  `default → Optional.empty()` (`:253`) — fourteen kinds fall through it silently,
  including `progress`, `budget_update`, `budget_gate`, `context_gate`,
  `context_compacted`.

So every event kind is born **durable on the wire and non-durable in the record**,
and nothing says so. `budget_raised` is not an oversight; it is the predictable
output of that asymmetry, and an evidence event added tomorrow inherits it.

Principle candidate: **a default-drop branch in a record projection is an undeclared
durability policy.** The shape of a fix is a per-kind decision that cannot be reached
by falling through — an explicit table, or an enum that makes the switch exhaustive,
where "not durable" is a value someone wrote rather than a branch nobody took. The
`REASONING_BOUNDARY_EXEMPT` list (§4.3) is the same idea already working on the live
side, which is mild evidence the shape is right.

This is where evidence events and progress notes actually meet: not by sharing a
payload, but by both being **run-narration items whose durability is declared rather
than defaulted**. Whether 865 fixes the general mechanism or only its own two cases
is a scoping decision for design; the tempdoc's contract is to name it either way.

### 4.11 Hidden assumptions and derisk items

- **~~Does the compressor know what it stripped?~~ RESOLVED (rev 2, review A10) — the
  mapping is statically recoverable, so the L-3 measurement gate is retired.** Four
  facts settle it: source identity is **compressor-independent** (it comes from
  `structuredData`, which compression never touches); `tool_call_id` **survives
  compression** — `compressToolMessages` copies the message map and replaces only
  `content` (`AgentContextCompressor.java:88-91`), so the link from a compressed
  message back to its tool call, and thence to the sources that call minted, is
  intact; the stripping is **deterministic** (`stripSearchExcerpts` +
  `compressToolOutput`, with `compressed.equals(content)` skipping untouched
  messages, `:85-87`); and the result is **self-evidencing** — whether a given
  message was stripped is decidable from the artifact itself. PR-3 is therefore
  **implementation, not a measurement bet**.
- **Which truncation bites — and there are THREE layers, not two** (review A10).
  Layer 1: `SearchTool`'s per-result budget, applied before the result is ever built.
  Layer 2: `truncate`'s per-result character cut (`AgentContextCompressor.java:32`).
  Layer 3: `Excerpt:` stripping on older results. **849's vocabulary covers Layer 3
  only**; Layers 1 and 2 are upstream of anything 849 modelled. The design must say
  which layer a badge reports rather than implying it reports "the truncation".
- **Is the §4.4 divergence real in a live turn?** The rules differ on paper. Confirm
  with a real run that the counts actually diverge — and by how much — before
  treating it as a defect worth a slice. It is possible the identity-less and
  duplicate cases are rare enough that the two surfaces agree in practice, which
  would change the priority (though not the fork argument).
- **Does a mid-run evidence surface change what the reader does?** A running Sources
  panel could help the reader stop a badly-grounded run early — the strongest
  argument for it — or it could just be motion. This is a genuine unknown; the
  design should not claim the benefit without saying it is a hypothesis.
- **Ordering against #533's timeline.** If evidence becomes a timeline item, it
  competes for the same vertical space as reasoning and tool cards, and 857's J/K
  landmarks would index it. That may be desirable (walk the evidence trail) or noisy
  (every search adds a row). Decide deliberately; do not let it fall out of the
  projection choice.
- **`AgentSource` has no score field** (`AgentEvent.java:156-164`, deliberate per
  559 §5). Anything the design wants to say about relative strength must come from
  the citation matcher's similarity, not from a retriever score — there is none to
  reach for. Good: it removes a temptation.
- **~~The A-slice interaction is a live-vs-record parity risk~~ — DISSOLVED (rev 2).**
  It applied only to the new-event-kind branch. Under §7.1's carrier design
  `tool_exec_completed` already projects on both paths and is already non-exempt, so
  `sv3-timeline-parity.test.ts` should be **unchanged and still green** — a diff to it
  now signals the design drifted back toward rev 1.

### 4.12 L-legs (live verification — spec, not yet scheduled)

The survey adds one leg the charter did not have, and it is the one that gates the
rest. All legs need a model (`ai_activate`). (Rev 2: 859 recorded this as SAC-blocked;
that is stale — the stack works. See §8.7.)

- **L-0 (new, prerequisite): a run that exercises the gap.** Confirm live that the
  owner's pattern produces what §3.1 predicts — that the agent has no read tool, what
  it actually does with "read these three files and summarise", and whether scoped
  `search-index` serves it. Everything downstream is calibrated on the answer.
- **L-1: unhappy-terminal evidence loss.** Cancel a run mid-way after ≥2 searches;
  confirm zero sources today, and evidence present after the change.
- **L-2: the §4.4 divergence.** One turn, count tool-card hits vs Sources-panel
  entries; record the numbers.
- **L-3: retrieved-vs-received.** A run long enough for Layer 3 to strip an early
  result; confirm the source still appears in `done.sources`, and that the badge (if
  built) reports it.
- **L-4: live/record parity.** Reload a run carrying the new evidence surface;
  `sv3-timeline-parity.test.ts` green plus a browser check that reasoning blocks land
  in the same place both ways.
- **L-5: the measured UX pass.** Presentation-authority work — an independent,
  measured (axe / contrast oracle) whole-screen audit by an auditor ≠ committer
  (`slice-execution.md`, `ux-audit-closure`). Per 860, run it in a **visible**
  window: automation evidence from an off-screen tab is invalid for anything
  rAF-gated.

### 4.13 Slicing intuition (not yet the plan)

Recorded so design has a starting shape to argue with; the plan pass settles it.

1. **Evidence that survives** — incremental minting so every terminal keeps what the
   run drew on (§4.5), plus the mid-run trail that falls out of it (§4.2/§4.3b).
   Backend-led, one authority.
2. **One answer to "what did this run draw on"** — reconcile or deliberately
   distinguish the tool card and the panel, and register the projection (§4.4).
3. **Received, not just retrieved** — the delegate-plane inclusion badge, reusing
   849's vocabulary (§4.6). Gated on the §4.11 measurement.
4. **Declared evidence contribution + acquisition axis** — the vocabulary that makes
   a future read-source expressible (§4.7/§4.8), demonstrable with a test double.
5. **Durability declared, not defaulted** — progress notes and evidence through one
   explicit per-kind decision (§4.10). Possibly folded into 1.

Slice 1 is the one with a correctness argument; 2 and 3 are honesty defects; 4 and 5
are substrate. Nothing here needs #866 to land.

### 4.14 Principle candidates (for the design pass to keep, sharpen, or drop)

- **Evidence is a projection of what the model received, not of what the system
  fetched.** 849 named it for RAG; the delegate plane has the same divergence,
  continuously rather than once, and unlabelled.
- **A default-drop branch in a record projection is an undeclared durability
  policy.** The wire vocabulary is compiler-exhaustive; the durability vocabulary is
  not. That asymmetry, not any one omission, is what produces gaps like
  `budget_raised`.
- **A label vocabulary borrowed from one acquisition mode silently asserts that
  mode.** "Retrieved · not examined" over a file the agent opened by name is a false
  provenance claim made by wording alone.
- **Content received, not merely named.** Naming a document is not evidence; this is
  the line that keeps "tool reads become sources" from degenerating.
- **Deliberate selection is not verification.** An agent choosing to open a document
  is evidence of availability, not of grounding — the cosine-panel lesson restated
  for the acquisition axis, where it will be tempting to forget.

## 5. Research pass (2026-08-25)

Two bounded questions were judged decision-relevant; the rest of the scope is
internal-correctness work where external art is irrelevant and none was sought.

**Not consulted, on evidence:** the `claude-api` skill's SKIP test was run as
mandated. JustSearch's delegate runs on a **local llama-server**
(`AgentLoopService` / `AgentStepRunner`); the single `openai` hit under
`modules/app-inference/` + `modules/ai-backend/` is a javadoc example string
(`LocalIntentTranslatorV2.java:26`, `@param backend … "llama", "stub", "openai"`).
A cloud provider's citation API is therefore not a contract 865 aligns to.

### 5.1 Is there a standard vocabulary to adopt? — No, for exactly our two axes

OpenTelemetry's GenAI semantic conventions **do** standardise tool calls
(`gen_ai.operation.name = "execute_tool"`, `gen_ai.tool.name`,
`gen_ai.tool.call.id`/`.arguments`/`.result`; span `execute_tool {name}`) — all at
**Development** stability, OTel's own "actively changing, no compatibility
guarantee" tier. Source: `open-telemetry/semantic-conventions-genai` (note the
GenAI conventions **moved** out of the main semconv repo; the old registry page is
marked deprecated).

For the two things 865 actually needs, both surveyed vocabularies have the **same
two gaps**:

| Concept 865 needs | OTel GenAI | OpenInference (Arize) |
|---|---|---|
| document acquisition mode (ranked / opened / listed) | **absent** | **absent** |
| retrieved vs. actually-in-the-prompt | **absent** | **absent** |
| documents retrieved | `gen_ai.retrieval.documents` — `id` + `score` only | `retrieval.documents`, `document.id/content/score/metadata` |

Both model a flat *"these were retrieved"* list and leave the correlation between
retrieved documents and final prompt content entirely to the instrumenter. MCP
defers semantics to OTel (it standardises trace-*context* propagation via `_meta`,
not attribute names). W3C PROV-O is stable but generic — adopting it would mean
authoring our own ontology extension on its `Entity`/`Activity` classes, not
inheriting a ready vocabulary.

**Verdict: coin our own, and namespace it away from `gen_ai.*`.** Two independent
vocabularies converging on the same shape and leaving the same two holes is mild
evidence the axes are genuinely under-modelled in the field, not that we have
missed an obvious standard. Worth stating without self-congratulation: 849 named
retrieved-vs-received before either convention did, and neither has caught up.

**Licensing (public repo, license-and-notices CI):** attribute *name strings* are
short identifiers and not copyrightable — reusing a naming convention carries no
obligation. Obligations attach only to copying spec **prose/schema tables**
verbatim (OTel semconv and OpenInference are both Apache-2.0 → retain notice, link
licence, state changes; PROV-O is under the W3C Document Licence). **This design
copies no external text**, so no NOTICE change is required. If a later pass quotes
spec prose, that changes.

### 5.2 Mid-run evidence UX — the prior art exists, and it argues for restraint

Shipping products have converged: Perplexity Deep Research, ChatGPT Deep Research
and Gemini Deep Research all accumulate evidence live in a **separate panel,
toggleable between "what it's doing" and "what it found"**. Cursor and Devin — the
closer structural analogues, since their evidence is inline in a tool-call feed —
do the same inline rather than in a panel. So the shape 865 is considering is
mainstream, not novel.

Three findings that should **change** the design rather than validate it:

- **The live trail buys perceived confidence, not verification.** NN/g's
  explainable-AI chat research found users rarely click citations even when they
  report that citations increase their trust — *"surface explanations do not build
  real trust."* A CSCW 2025 study on process transparency measured a genuine
  trust effect but also a split, with some users finding the process display
  **distracting**. (nngroup.com/articles/explainable-ai/ ; dl.acm.org/doi/full/10.1145/3715070.3749256)
- **The live-vs-batch comparison is unstudied.** The research compares
  *transparent vs. opaque*, never *live-updating vs. shown-at-the-end* — which is
  precisely 865's question. Vendor convergence is a product-market signal, not a
  UX-effectiveness proof, and should not be cited as one.
- **What converts a trail into value is an ACTION on it.** The only features found
  that turn passive reassurance into steering are Perplexity's ask-a-follow-up
  mid-run and Gemini's open-a-source mid-run.

**Consequences taken into the design.** Do not justify slice 1 on UX — its argument
is correctness (evidence is currently destroyed, §4.5), and that argument stands
alone. Keep mid-run *presentation* minimal, since the benefit is unproven and the
distraction risk is measured. And prefer wiring the accumulated evidence into a
surface the reader already **acts** on — the budget gate's decision and the halt
control (§3.8) — over adding a new panel that is merely watched. This also retires
§4.11's open question honestly: the "does it change what the reader does?" benefit
is *not* established, so the design must not claim it.

### 5.3 Cancel/error evidence retention — no precedent to follow

No vendor or independent documentation was found, for any product, describing what
happens to accumulated evidence when a run is cancelled or errors. Mistral
documents that cancelling Deep Research is irreversible but says nothing about
retained sources. The absence is itself informative: retaining partial evidence
after a cancel is not a settled convention anywhere, so 865 is **establishing**
behaviour rather than following it. That raises the bar on stating the claim
honestly (a cancelled run's evidence is "what this run drew on", never "what
grounds this answer" — §4.5) and removes any temptation to cite prior art for it.

**Research-quality caveats, recorded rather than hidden.** Several primary sources
(OpenAI and Perplexity help centres, one Mistral article) returned HTTP 403/404, so
parts of the ChatGPT/Perplexity rows rest on secondary summaries, not verbatim
primary text. Specifically **unconfirmed**: whether ChatGPT or Perplexity have
introduced precise "Searched" vs "Read" copy. Treat §5.2's "no acquisition-mode
vocabulary exists" as *not found under a real search*, not as proven absence — the
design does not depend on it either way, since our own 847/849 precedent is the
binding constraint on wording regardless.

## 6. The folded-in constraint, as chartered

The `budget_raised` / `context_gate_reapplied` durability gap is being fixed
separately (progress notes become durable). Evidence events and progress notes must
land in the **same projection vocabulary**, not fork it. §4.10 generalises this: the
shared thing is not a payload but a **declared** durability decision, and the
mechanism that produced the gap (`AgentInteractionMapper.fromRunEvent`'s
`default → Optional.empty()`, `:253`) will produce the next one unless a new event
kind is forced to choose. The design carries this at §7.7; the branch-ordering
consequence is at §8.2.

## 7. Design (2026-08-25)

### 7.0 Thesis

> **A run's evidence is minted once, as the run produces it, by one authority — and
> every surface projects from that, claiming only what the mint actually established.**

Three of the four scope items are corollaries of that sentence. Today evidence is
minted *at the reporting site* rather than where it is established — so it dies on
every path that does not report, which (rev 2, §3.8b) includes a **success**
terminal, `MAX_ITERATIONS`, not merely the error paths. It is minted by *two*
authorities that disagree (§3.8a/§4.4), and the panel's labels claim things the mint
did not establish (§7.3/§7.5). The fourth item — durability — is the mechanism that
decides whether any of it survives a reload (§7.7).

The design is deliberately **additive at the terminal**: `AgentDone` keeps its
`sources`/`citations`/`citationScorer` unchanged. Nothing about the happy path
changes shape. What changes is that the same list now exists *before* the terminal
too, and that surfaces stop overclaiming about it.

### 7.1 One authority, minting incrementally (scope item 1 + 4)

**Extend the existing minter rather than add a second one.**
`AgentSession.collectGroundingSources()` (`AgentSession.java:259-324`) already holds
the whole rule: the two identity arms, the `seen` dedup, the 603 D-3 sentinel, the
identity-less skip. It is re-run from scratch at the terminal over all of
`executedTools`.

The change is to make its accumulation **incremental and durable across the run**
rather than recomputed at the end: the `seen` set and the emitted list become session
state; each executed tool contributes whatever new sources it establishes; the
**`groundedDone`** terminals drain the accumulator instead of rebuilding it
(`ofDisposition` must **not** drain — see the A5 note below). The rule itself — which
this design does not touch — is unchanged, which is what keeps this a refactor of one
authority rather than the birth of a second.

**Emission — the carrier decision (rev 2, review finding A2).**

Rev 1 proposed a **new run event kind**. That is withdrawn. The delta is **stamped
onto the existing `ToolExecutionCompleted.structuredData`**, at the same dispatch
seam that already stamps `OutputLineage`.

The decisive observation is that §3.8c cited `OutputLineage` as the *declaration*
precedent while missing that it is equally the **carrier** precedent.
`OperationResult.withLineage` (`OperationResult.java:79-91`) is an idempotent merge
into `structuredData`, documented as *"Applied once at the dispatch seam — the single
authoritative stamp."* A `withGrounding(...)` sibling is structurally identical, and
`AgentStepRunner.java:859-864` already performs exactly this stamp two lines above
the emit.

Judged against the new-event option on every axis the design actually needs:

| | new event kind | **stamp on `structuredData`** |
|---|---|---|
| one authority | yes | yes — same Java minter |
| incremental | yes | yes |
| durable on both planes | needs a new `fromRunEvent` case | **already**: wire `AgentEventPayloads.java:308-310`, record `AgentInteractionMapper.java:152` |
| survives every terminal incl. `MAX_ITERATIONS` | yes | yes |
| descriptor / fixture regen / `gen-shape-handlers` | **required** | **none** |
| flush-carrier decision + parity risk | **required** (§4.3) | **none** — `tool_exec_completed` already projects on both paths and is already non-exempt |
| new timeline row per tool call | **yes** | **no** |
| typed `EventField` descriptor | yes | no — free-form key |

**The honest cost is the last row**, and it is a trade this repo has already made
knowingly at this exact seam: `AgentRunShape.java:90-99` declares `structuredData` as
`EventField.object("structuredData", "").asOptional()` — free-form by design — and
`OutputLineage` accepted the same looseness for the same reason. Adopting it here is
conforming to an established seam, not inventing a weaker one. Mitigation: a test
pinning the key's shape, in place of the conformance binding a descriptor would give.

Two further consequences worth stating, because they are why this is not merely
cheaper:

- **It dissolves review finding A3.** A new projecting record event would have minted
  a note row per tool call (`sv3-record.ts:317-322`) while the live side appended
  nothing — the parity oracle would have **failed as specified** — and the fix (add a
  live row) would have collided head-on with §5.2's measured finding that mid-run
  presentation should stay minimal. The carrier has no row to reconcile: evidence
  attaches to the tool card that already exists.
- **It binds evidence to its cause.** The sources a tool call established are a
  property of *that call's result*. A separate event would have re-asserted the
  linkage the carrier gets for free.

The stamp carries the sources **newly established by that call** — a delta, not a
running total, so a long run does not re-send its whole evidence set every step. Only
successful results are stamped, matching `withLineage`'s existing guard
(`AgentStepRunner.java:859`); a failed tool establishes nothing.

**Rev 3 (implementation, PR-1) — the success guard was NOT implemented, deliberately.**
The sentence above sets up a conflict with this same section's "the rule itself — which
this design does not touch — is unchanged": `collectGroundingSources` iterated EVERY
executed call regardless of success, so adding a success guard to the mint would have
been a change to the rule, and adding one to the STAMP alone would have been worse — a
failed result that established a source would sit in the accumulator and in no delta,
breaking the terminal equivalence three paragraphs down. Both branches were bad, so the
premise was checked instead of chosen between: `searchResults` has exactly one producer
(`SearchTool.java:281`) and it is the `OperationResult.success(...)` arm, and neither
`OperationResult.failure` overload carries a `structuredData` channel at all (the typed
one carries `errorDetails`, a different field). A failed tool therefore CANNOT establish
anything, which makes the guard inert rather than protective — and an inert guard whose
only reachable effect is to break an invariant is not worth having. Implemented with no
guard; the premise is pinned by `AgentLoopServiceTest
.failureResultsCannotCarryStructuredData` (review F-5), so if a failure factory ever
gains a structured payload the question reopens as a red test rather than as a silent
divergence.

Three properties this must have, and they are the design, not implementation detail:

- **Terminal equivalence.** The sources the terminal reports must be exactly the
  concatenation of the deltas, in order. This is not cosmetic:
  `AgentSentenceCite.sourceIndex` (`AgentEvent.java:171`) is a *positional* reference
  into the sources list, and `agentEvidence.ts:76` resolves it as
  `sources[cite.sourceIndex]`. If the incremental order and the terminal order ever
  diverge, every inline mark silently points at the wrong document. Because both come
  from one ordered accumulator this holds by construction — and precisely because it
  holds by construction rather than by check, it needs a test that would fail if
  someone later re-derives either side independently.
- **Emit only on change.** A tool call that establishes no new source emits nothing.
  A run event announcing an empty delta is the run narrating something that did not
  happen — the same rule `narrateBudgetRaise` already applies when a compaction
  dropped nothing (`AgentStepRunner.java:339-349`).
- **No terminal needs to carry evidence.** Because the deltas are delivered and
  journaled before any terminal, a cancelled/errored run keeps what it established
  without widening `AgentError` at ~10 construction sites (§3.8b). This is the whole
  reason the incremental shape wins.

**Wire and record — nothing to build.** Both planes already carry the field:
`toolCompletedPayload` puts `structuredData` on the wire when non-empty
(`AgentEventPayloads.java:308-310`), and `fromRunEvent`'s `tool_exec_completed` case
copies it onto the persisted tool-activity event (`AgentInteractionMapper.java:152`),
under a comment (561 #6) whose stated purpose is *"so the record render shows the
same evidence cards as the live overlay."* The record path's reason for existing is
the thing this design needs.

**The flush-carrier question does not arise.** `tool_exec_completed` already projects
a record entry and is already outside `REASONING_BOUNDARY_EXEMPT`, so live and record
already agree about it — that agreement is what `sv3-timeline-parity.test.ts` pins
today, unchanged by adding a key to a map it already carries. This is the single
largest risk rev 1 carried, and the carrier design removes it rather than managing
it.

**Rev 1's rejected alternative, kept for history:** a new event kind emitted but
projecting nothing, which would have needed an exempt-list entry to stay
parity-clean and would have been non-durable — the exact defect §7.7 exists to stop.
Rev 2 rejects the whole branch: see the table above.

**A5 — terminal equivalence, qualified.** `AgentDone.ofDisposition` must **not** be
changed to drain the accumulator. Its javadoc (`AgentEvent.java:281-286`) and the
routing comment at `AgentLoopService.java:572-576` record why it exists: the
canonical constructor takes `List` arguments, and `AgentGroundingSeamAuditTest`'s
discriminator is a `java.util.List` **signature substring**, so calling the canonical
constructor from `AgentLoopService` would trip the grounding-seam audit for a reason
unrelated to grounding. Draining the accumulator there would re-break that seam.

So the equivalence claim is scoped, explicitly: **the concatenated deltas equal the
terminal `sources` on the two `groundedDone` terminals** (`COMPLETED`,
`BUDGET_EDGE_FINALIZE`). The other terminals are **exempt by construction** — they
emit no grounded terminal, and under this design they no longer need to, because the
evidence already rode the tool events. `ofDisposition`'s hardcoded `List.of()` stops
being a loss and becomes merely *"this terminal makes no grounding claim"*, which is
true.

### 7.2 What the terminal still owns

The terminal keeps everything that only exists once there is an answer:
`finalResponse`, `citations` (per-sentence matches), and `citationScorer`. The
matcher cannot run before the answer exists, so **marks are inherently terminal-only
and this design does not try to make them incremental.**

Consequence to state plainly rather than let a reader infer it: mid-run, a source is
a document the run *drew on*; only at the terminal does any source become a document
that *grounds a sentence*. Those are different claims and §7.3 is about not
conflating them.

### 7.3 Incomplete grounding — a PRESENT-TENSE defect (rev 2, review A6)

This is the item the scope did not anticipate. Rev 1 believed slice 1 would create
it; it ships today, which makes it a **bug fix that lands ahead of PR-1** (PR-0).

`agentEvidence.ts`'s own javadoc (`:63-70`) records the rule: with an empty match
list every source lands `cited: false` and the panel renders **"Retrieved · not
cited"** — *"a verdict, on every source the matcher demonstrably DID cite."* 859 §3a
fixed that by projecting the matcher's real report.

**Rev 2 correction (review finding A6): this is PRESENT-TENSE, not created here.**
Rev 1 said slice 1 would introduce the state. It ships today.
`AgentCitationResolver.resolve` wraps the match call in `try/catch` and degrades to
`Resolved.none()` on **any** exception — including the `MATCH_TIMEOUT_MS` timeout
(`AgentCitationResolver.java:94-123`). So a **`COMPLETED`** run whose matcher timed
out already emits a full `sources` list with `citations = []`, and the panel already
renders **"Retrieved · not cited"** over sources no scorer ever judged. The
`LOG.warn` at `:118-121` even names the degradation — *"citing sources without inline
marks"* — while the surface silently converts it into a verdict.

That changes this item's status in two ways: it is a **bug fix, not a prerequisite**,
and it is **independently landable ahead of PR-1** (§8.1). Slice 1 would broaden its
blast radius, not create it.

**The precedent is `sourceGrounding`'s optional coverage parameter, not
`toAnswerEvidenceSource`.** Rev 1 cited the wrong one: absent *fields* on a source
are a different move from an absent *verdict* about it. The right model is
`sourceGrounding(sourceIndex, matches, parentDocId?, coverage?)`
(`evidenceProjection.ts:662-670`), whose own comment states the rule — *"Absent ⇒ the
state stays the established binary, so a producer that says nothing about coverage
does not get 'unexamined' assumed on its behalf."* An optional input that, when
present, unlocks a state, and when absent preserves today's behaviour exactly.

**Mechanism, specified.**

- **A fourth `SourceExamination` member — and NOT `'unexamined'`.** That member is
  already taken and means something else: `sourceGroundingLabel`'s comment
  (`:698-701`) defines it as *"nothing read it"* — a **budget/coverage** fact from
  836. A matcher timeout is the opposite situation: the source was fully eligible and
  the **scorer** failed. Reusing `'unexamined'` would file a scorer failure as a
  budget outcome and mislabel it. The new member says *the grounding pass did not
  complete*.
- **Branch before `tierGroup`**, on the same template `coverageLabel` uses
  (`evidenceProjection.ts:596-615`) — that function already distinguishes
  `'Not scored'` from `'Not grounded'` via `coverageIncomplete(honesty)`, which is
  precisely this distinction one level up. The per-source label needs the same fork.
- **A new panel heading** for the group, since these sources belong in neither
  "cited" nor "not cited".
- **Tier optionality:** decide explicitly whether a source in this state may carry a
  `GroundingTier`. Recommended **no** — the tier is minted only from a similarity
  (§4.8) and there is none.
- **`reconcileEvidence`:** state the rule for a record/live disagreement, per 847's
  *"ask the field's own question"* discipline.
- **`recordEvidenceOf` accumulate-vs-last-wins:** today it is assistant-only and
  documented last-wins (`sv3-record.ts:301-302`, `turn.evidence = evidence`). Say
  which this needs; last-wins is likely still correct for a single assistant row, but
  it must be stated rather than inherited.

**PR-1 (and this fix) are presentation-authority work** — a measured, independent
whole-screen UX audit at closure (`ux-audit-closure`, §8.7 L-5), auditor ≠ committer.

**Rev 3 (implementation, PR-0) — the SURFACE was mis-located; the defect is real.**
Both rev 1 and rev 2 named `CitationsPanel` as the surface that renders "Retrieved ·
not cited" over an unjudged source. It does not, and a probe against `main` proved
it: `CitationsPanel.render` routes to the ungraded FLAT list whenever no match
survives (`!hasCitations`, the 603 §22/U2 branch, `CitationsPanel.ts:520`), so a
timed-out delegate run showed neutral cards with no badge at all. Implementing §7.3's
panel carve-out on that premise alone would have been an unreachable seed.

The surface that **does** deliver the verdict is the READING PANE's citation header:
`sv3CitationHeader` (`sv3-citation-anchor.ts:107-129`) reads the evidence's match list
straight into `sourceGrounding`, and an empty list is `cited: false`, which
`sourceGroundingLabel` words as "Retrieved · not cited". Verified red on `main`:

```
FAIL  RED PROBE — what the reading pane says today for a timed-out delegate matcher
AssertionError: expected 'Retrieved · not cited' to be '<<< THE PANE SHOULD SAY NOTHING >>>'
Received: "Retrieved · not cited"
```

So PR-0's mechanism lands exactly as specified — the fourth member, the label, the
tier decision, the reconcile rule — and reaches BOTH surfaces through the one
`sourceGrounding` authority. The panel carve-out is kept and made reachable by one
extra clause: an empty match list the producer has EXPLAINED no longer takes the flat
branch, so those sources land under the new heading instead of in a silence that is
byte-identical to a keyword-only run. Every run that says nothing keeps the flat list.

**The DISCRIMINATOR needed no new wire field** (so no regen chain). `AgentDone.citationScorer`
already carries it: `AgentStepRunner.groundedDone:983` stamps `resolved.scorer().name()`,
which is `NONE` for every `Resolved.none()` — the `MATCH_TIMEOUT_MS` timeout included —
while the Worker stamps a real producer name on every response its matcher produced
(`CitationMatchOps.execute:263,327`). The fact was already on the wire and being dropped
at the read site.

**Boundary held, deliberately:** the *producer-rejected* case (a known non-cross-encoder
scorer whose numbers 836 §4 refuses) still reads "Retrieved · not cited". A pass DID run
there, so it is not the missing-verdict case; whether that wording is right for it is a
separate question, logged rather than folded in.

### 7.4 One answer to "what did this run draw on" (scope item 2)

The two projections (§3.8a, §4.4) are **not** merged, and the design should say why
rather than default to unification: they legitimately answer different questions.

- `toolSearchCard.ts` → a **receipt of one tool call**: what that search returned, as
  returned, in that call's own order. Every hit belongs, including ones with no
  addressable identity.
- The evidence accumulator → **the run's evidence set**: deduped across calls,
  restricted to sources that carry a document identity, positionally aligned with the
  citation indices.

Merging them would break both. So the design **declares the difference** instead of
erasing it — which is also what the AHA guidance asks (only unify what shares a
reason to change; these two have opposite reasons).

Three things follow:

1. **`toolSearchCard.ts` gets a register row** in `governance/execution-surfaces.v1.json`,
   `kind: "projection"`, with a named guard — exactly as `agent-evidence-projection`
   was registered for `agentEvidence.ts` (`:247-253`). It mints `SearchProvenance`,
   *"a positive claim about how the search ran"* (`ResultsCard.ts:126-133`), from
   run data; that is a governed projection whether or not the scan's regex can see it.
   **Guard name must satisfy the conformance regex: `test:toolSearchCard.projection`**
   (review A7).
2. **This declares the INSTANCE; the class stays open.** Rev 1 overstated the fix.
   The enforcer iterates `reg.surfaces` — it does not re-scan for the pattern — so
   adding a row closes *this file* and leaves the blind spot itself untouched: the
   next unregistered re-modeller is equally invisible. Say so rather than imply the
   gap is fixed. (Rev 1 also justified not widening the regex via
   `expectedMinPopulation`; that was wrong — the field is a **floor**, a vacuous-pass
   guard, and widening would not "move every consumer's status". The real reason to
   route a pattern change to `/governance` is that it is a governance decision, not
   that it is expensive.)
3. **A test pins the divergence as deliberate — and it MUST be multi-call**
   (review A7). Rev 1's mechanism was wrong: the worker collapses chunk hits to one
   hit per parent before results ever reach the agent
   (`SearchExecutor.collapseChunkHitsToParents`,
   `modules/worker-services/.../execute/SearchExecutor.java:1029-1051`, falling back
   to `hit.docId()` when `PARENT_DOC_ID` is absent). So **within a single call** both
   of the rules rev 1 named are inert: there are no duplicate parents to dedup, and
   no identity-less hits to drop. A single-call test would pass trivially and prove
   nothing.
   The **only live divergence mechanism is scope**: the card is a receipt of one
   call; the accumulator spans calls and dedups a document seen in call 1 and again
   in call 3. The test must therefore run ≥2 calls sharing at least one document.

What this does **not** do: reconcile the numbers on screen. Research (§5.2) found no
evidence readers audit these panels, and inventing a reconciliation UI for an
un-observed confusion would be structure for a case the problem does not include.

### 7.5 Retrieved-vs-received on the delegate plane (scope item 3 — the real half)

This is a **known defect class, already fixed once, that never reached this plane.**

849 MEDIUM-3 established the rule: *a dropped passage may not also claim it grounded
the answer.* Its mechanism was that `StreamingCitationMatcher` scores answer sentences
against chunk text it **re-fetches**, not against what the model was shown — so a
card could read "Retrieved · never sent to the model" beside "Grounds 1 sentence".
849 fixed it with `suppressGroundingFor` (`evidenceProjection.ts:929-931`), one
predicate, both surfaces.

The delegate plane reproduces every precondition and inherits none of the fix:

- The delegate matcher re-fetches identically — `agentEvidence.ts` stamps
  `textSource: 'CHUNK_LOOKUP'` and states it: *"the Worker re-fetches each chunk by
  (parentDocId, chunkIndex) — never the literal excerpt the model saw."*
- Content is removed from the prompt **continuously**, not once:
  `AgentContextCompressor` truncates each result on the way in and strips `Excerpt:`
  lines from older results every iteration (§3.3).
- Delegate sources carry **no inclusion signal at all**, so
  `suppressGroundingFor(null)` is `false` and nothing is ever suppressed.

So the design's shape is: **supply the delegate plane the inclusion input that 849's
existing predicate already knows how to act on.** It reuses `ContextInclusion`, the
`inclusionBadge` vocabulary, and `suppressGroundingFor` verbatim — no new
vocabulary, no second suppression rule, no parallel axis. That is projection, not
fork, and it is the cheapest correct answer precisely because the hard thinking was
done in 849.

**The honest constraint, and it may narrow this item to nothing:** the compressor
operates on *messages*, not on sources, and reports nothing today (§4.11). If the
mapping from "this message was compressed" back to "these source identities lost
their text" cannot be established cleanly, then **the design's answer is to keep
saying nothing** — absent, as now — rather than to compute a badge from a guess.
849's own ABSENT discipline is explicit that a consumer told nothing must say
nothing. This is gated on the §4.11 measurement and the design does not pre-commit
to shipping a badge.

**Rev 3 (implementation, PR-3) — the mapping WAS establishable and the badge ships. It
ships narrowed to ONE state, and that narrowing is the honesty constraint, not a
shortcut.**

The producer is a RECEIPT, not a re-derivation: `compressToolMessages` now returns a
`CompressionReceipt` over the message list it just wrote. A source's carriers are a SET,
not its minting call, which is what lets a document re-returned by a later search stop
being described as dropped.

**The receipt has THREE outcomes, and the two-outcome version shipped a false claim
(independent review F1, reproduced live).** Rev 3's first implementation asked one
question — "does this message still have an `Excerpt:` line?" — and partitioned every
tool message on the answer. But a vector/dense-only hit has no excerpt regions, so
`SearchTool.formatResults`'s else-branch writes its text as `Preview:`
(`SearchTool.java:451-459`), which Layer 3 never strips and that predicate could never
match. Such a message was classified stripped **with zero compression having occurred**,
and the panel rendered "Retrieved · never sent to the model" over text sitting verbatim
in the prompt. Not silence — a confident falsehood, on the exact question the producer
exists to answer. The licensing comment ("such a call minted no grounding source") was
simply false for that branch.

The fix is the honest predicate, *"the carrier no longer holds the text it had"*, which
needs both a wider reader and positive evidence of removal:

- **`textIntact`** — the message still matches `ToolResultCarrier.CARRIER_LINE`, which
  covers BOTH spellings. This wins over every other signal: if any carrier line survived,
  some of the text is there, and the one claim this producer makes is forbidden.
- **`textRemoved`** — this pass rewrote the message, or it bears the
  `[compressed-tool-output` marker a pass that did left behind.
- **NEITHER** — say nothing. A tool that never carried hit text is indistinguishable from
  one stripped by a pass this receipt did not witness, and collapsing that into "removed"
  is exactly the F1 bug.

Both signals are needed and each covers the other's blind spot: artifact-only produced
F1; rewrite-only misses every message compressed in an *earlier* pass, because
`compressToolOutput` refuses to re-compress its own output. The strip-only case (excerpts
removed, remainder under `minChars`, so no marker is written) is witnessed by exactly one
pass — which is why `AgentSession` **folds** `textRemoved` forward rather than re-deriving
it. That accumulation is sound because a message's content only ever shrinks; the
per-final-prompt property lives in the carrier set instead, and `inclusionFor` requires
**every** carrier to have lost its text before it will speak.

One structural consequence, recorded because it is the general lesson: the format coupling
now has a single owner. `ToolResultCarrier` holds the writer's `excerptLine`/`previewLine`
and both reader patterns — deliberately two patterns, since widening the *strip* to match
the *reader* would silently delete a dense-only hit's whole text from the prompt.

**What it states, and what it refuses to.** Only `dropped` (else ABSENT). Not
`included` — and the falsifier is concrete, not cautionary: Layer 1
(`SearchTool.java:439-448`) divides `MAX_TOOL_RESULT_CHARS` across the hits and clips —
or omits outright — a later hit's `Excerpt:` line, while that hit is still minted as a
source from the untruncated `structuredData` (whose excerpt is separately clamped to 320
chars, `:344`). So "this message still carries excerpt lines" cannot mean "THIS source's
excerpt reached the model", and `included` would fabricate exactly the claim 849 exists
to remove. `dropped` survives because it is **monotone across the three layers**: once
Layer 3 has taken the excerpts out of the carrier message, no upstream cut can put them
back. It is also the only state the reader acts on — `suppressGroundingFor` keys on
`dropped` alone — so the narrowing costs the surface nothing. That is §8.5 item 4
answered concretely: the badge reports Layer 3, and says nothing where Layers 1-2 would
have to be witnessed to speak.

**Carrier.** The two keys ride each element of `done.sources`, spelled exactly as
`RAGContext` spells them on a `rag.citations` entry — so the FE forwards them through the
existing shared evidence record with no new join, and the record path needs no change at
all (the mapper copies `payload.sources` verbatim). `AgentSource` gains them as
components CONSTRUCTED ABSENT with a `withInclusion` copy method — `ContextCitation`'s
own idiom, one level up — and `ContextInclusion.fromWire` is the one reverse projection,
living beside `wireName`. The per-call delta is deliberately excluded
(`OperationResult.withGrounding` still writes the eight identity keys): a tool call has
no final prompt to be a fact about, and PR-1's delta contract is untouched.

**Precedence, stated at the read site** (`agentEvidence.toAnswerEvidenceSource`):
inclusion is decided BEFORE the grounding axis and takes the whole axis with it. A source
that is dropped AND unexamined (PR-1) inside a run whose pass never completed (PR-0)
shows only "Retrieved · never sent to the model"; `citationHeader`'s existing
`suppressGroundingFor` withholds "not cited", "not examined" and "grounding check did not
complete" alike. That is right rather than merely established: every state on the
grounding axis is a claim about a matcher's relationship to text the model never saw —
"not examined" would invite "so examine it", and "grounding check did not complete" would
imply a completed check could have said something.

**Every compression site records (review F2).** `AgentLlmCaller.attemptBudgetEdgeFinalize`
compressed bare, and that pass is the one that builds the prompt
`groundedDone(BUDGET_EDGE_FINALIZE)`'s answer is written from — so the terminal under the
run's *maximal* compression pressure resolved against a one-pass-stale receipt and
withheld the badge precisely where the most text had been dropped. The seam was invisible
to every other test: mint, join and stamp all worked, and the only symptom was a run
saying nothing when it had the most to say. Now threaded, and pinned by a test that goes
red when the wrapper is removed.

**Still unrun: the live tier.** Every claim above is compile-, unit- and gate-tier. §8.7's
L-5 audit now covers PR-0, PR-1 and PR-3 — this slice adds a THIRD new sentence a reader
will act on, and no one has yet seen any of them rendered.

**Method note worth keeping.** F1 and F2 were both found by an independent reviewer rather
than by the implementing agent's own critical-analysis pass, and they are the same shape: a
producer that is correct on the path its author had in mind and silently wrong on a sibling
path — a second result-formatting branch, a second compression call site. The
generalisable check is not "re-read the diff"; it is **enumerate the writer's branches and
the producer's call sites, and ask the predicate about each one**. `ToolResultCarrier` and
the F2 threading both exist so that the next such branch is a compile-time or test-time
concern rather than a reviewer's catch.

### 7.6 The acquisition axis — vocabulary now, structure only when it has a second value

The scope asks for an acquisition axis. **The design records the vocabulary and
declines to build the structure**, and the reason is evidence, not reluctance:

Every delegate source today is minted from `searchResults`, i.e. ranked by a scorer.
The label "Retrieved" is therefore **currently true on every delegate source**. An
acquisition enum would have exactly one value, and a one-valued axis is speculative
abstraction — which this repo forbids outright, and which would also be a *second*
provenance vocabulary sitting beside `OutputLineage` with nothing yet to distinguish
(§3.8c).

What the design does instead:

- **Names the invariant** so 866 inherits it rather than re-deriving it:
  *an opened-by-name document has less relevance evidence than a retrieved one, not
  more* — nothing scored it; that the agent chose to open it establishes availability,
  not grounding.
- **Names the extension point**: the shape to follow is `OutputLineage`'s — one
  declaration site keyed on operation id, stamped once at dispatch, never re-derived
  — and explicitly *not* `OutputLineage`'s enum, since `browse-folders` is
  `CORPUS_QUOTED` yet must mint nothing (§3.8c). Evidence contribution and text
  lineage are different questions with different answers for an existing tool.
- **Names the trigger**: the axis gets built when a second acquisition mode exists —
  i.e. when 866 lands, or when an MCP tool is given a declared identity mapping.

Recorded as a deliberate scope reduction against the coordinator's item 3, not a
silent skip: item 3's *real, present-tense* content is §7.5 (inclusion) and §7.3 (the
no-answer state); its acquisition half is pre-emptive until 866 decides.

### 7.7 Durability declared, not defaulted (scope item 4)

`AgentInteractionMapper.fromRunEvent`'s `default → Optional.empty()` (`:253`) is an
undeclared durability policy: every event kind is born durable on the wire (the
`name()`/`base()` switches are over a **sealed interface**, so the compiler forces a
decision) and non-durable in the record (a **String** switch with a default, so
silence is the fallback). `budget_raised` is not an oversight; it is that asymmetry's
output, and evidence would be its next victim.

The design's position: **the fix is to make "not durable" a thing someone writes,
not a branch nobody took.** Concretely, the record projection's vocabulary should be
exhaustive over the wire vocabulary, with a non-projecting kind stated as such.

Scoping this honestly is the harder half. Fourteen kinds currently fall through, and
deciding the right durability for all of them is a much larger question than 865 has
evidence for — several may be correctly non-durable. So:

- **In scope for 865:** today's fall-throughs are written down as **explicit
  non-projecting `case` entries**, following the precedent already in the file —
  `case "reasoning_chunk" -> Optional.empty();` (`AgentInteractionMapper.java:249-252`),
  whose comment gives exactly this design's rationale: *"Stated as its own case rather
  than left to `default` so the vocabulary is legible."* The pattern is established;
  865 extends it rather than inventing it.
- **Out of scope:** changing any existing kind's durability. Writing down what the
  code does today is a no-op for behaviour and a permanent gain in legibility;
  re-deciding fourteen policies is a separate charter with its own evidence needs.

**Rev 2 scoping correction (review A8).** Rev 1 said the switch should become
"exhaustive over the wire vocabulary." That is **unimplementable as written**: the
switch folds records from more than one shape's vocabulary — `AgentRunShape`'s, plus
the `core.search-event`-shaped runs `AgentRunStore.appendSearchEvent` writes
(`AgentInteractionMapper.java:230`) — so there is no single vocabulary to be
exhaustive over, and the `default` arm cannot be removed. The scope is therefore:
**explicit non-projecting entries for the `AgentRunShape` names**, with `default`
retained for everything else. Under the §7.1 carrier design, evidence needs no new
case at all, so this item stands purely on the `budget_raised` class it was folded in
for.

**A counter-precedent to P2's absolutism, recorded rather than argued away.** PR #538
introduces a **declared, phase-level default** — a default that is itself the written
decision. That is a legitimate third option between "explicit per-kind entry" and
"silent fall-through", and it means P2 (§7.9) should be read as *"a default must be
declared"*, not *"a default is always wrong"*. The version of P2 that forbids
defaults outright would reject #538's shape, which is not the intent.

That distinction matters for review: this item must not become a fourteen-way
behaviour change smuggled in as a refactor.

**Composition with the in-flight progress-note work** (a parallel worker is adding a
`progress` case to this same switch, right now):

- The **file-level overlap is exactly one**: `AgentInteractionMapper.java`'s
  `fromRunEvent` switch — and likely adjacent lines within it, so a textual conflict
  is probable and trivial.
- The two changes are **complementary by shape, not merely compatible**: their PR
  converts one fall-through into a projecting case; this design converts the
  *fall-through mechanism itself* into declared entries. Their case becomes one of
  the explicit entries rather than being displaced.
- **Ordering: assume theirs lands first.** 865 merges `main`, absorbs
  `case "progress"` as a declared projecting entry, and does not re-litigate its
  projection choice. If 865 were to land first, their work would need the same
  absorption in the other direction — which is why the design fixes the ordering
  rather than leaving it to whoever merges second.
- **The anti-goal:** 865 must not "helpfully" also make progress notes durable. That
  is their PR's contract, and duplicating it is how two branches produce one broken
  merge.

### 7.8 What this supersedes or orphans

Stated plainly, including the honest answer that it is little:

- **Orphaned: nothing is deleted.** This design registers an existing unregistered
  projection (§7.4), extends the existing minter in place (§7.1), and reuses 849's
  existing suppression vocabulary (§7.5). No component is replaced.
- **Superseded: the terminal-only minting *pattern*.** After §7.1, "evidence is what
  `groundedDone` computed" stops being true, and any future code that reaches for the
  terminal as the place evidence comes into existence is working from a retired
  model. The javadoc on `collectGroundingSources` is the authority that must be
  rewritten in the same PR — its current text describes a terminal aggregate, and
  leaving it would be exactly the false-authority residue `retire-with-a-sweep`
  targets.
- **Superseded: `fromRunEvent`'s default as a policy.** The `default` branch's
  meaning changes from "everything unlisted is silently dropped" to "this vocabulary
  is complete." Any comment or doc describing the old behaviour goes with it.
- **A deliberate non-orphan:** `toolSearchCard.ts` survives with a register row.
  The design explicitly refuses to fold it into the evidence projection (§7.4), and
  that refusal is recorded so it is not re-proposed as cleanup.
- **`AgentGroundingSeamAuditTest` must be extended in the same PR (review A4).** Its
  discriminator is a `java.util.List` **signature substring**, matching `AgentDone`
  constructors that carry lists — a limit its own javadoc names, and the reason
  `ofDisposition` exists as a delegating factory (`AgentEvent.java:281-286`). Under
  §7.1 the mint's authoritative attach point moves onto the tool-result stamp, which
  carries no `List`-typed constructor and is therefore **invisible to the audit**,
  while the audit's javadoc goes on claiming it guards the one attach site. Left
  alone, that is textbook false authority: a green gate asserting a property it has
  stopped covering. Extend the predicate to the stamp seam **in the same PR**, per
  `retire-with-a-sweep`.

### 7.9 Composition with 863 (rev 2 — review finding A9)

863 ("the delegate turn is missing from the answer plane") is a **serial predecessor**,
not a neighbour: its own frontmatter says *"863 implements BEFORE 865, serial — both
edit `AgentInteractionMapper`'s `done` case and the evidence seam that hangs off it."*
Four questions must be answered before PR-1 is briefed.

- **Which plane is authoritative for incremental evidence on a STAMPED conversation?**
  863 Slice A extends `persistedAssistant` to carry `sources`, `citationScorer` and
  `disposition` onto the **answer/store plane** (863 §A.4, §4.3). 865 puts deltas on
  the **action plane** (the tool events). Both then describe the same run's evidence.
  Proposed rule, to be confirmed against 863's final shape: the **action plane is the
  mint**, and the store plane carries the terminal summary 863 needs for its
  store-backed consumers — one authority, one derived summary, never two mints.
- **How deltas avoid double-carrying.** `recordEvidenceOf` discriminates planes on
  `Array.isArray(a.sources)` (`sv3-record.ts:137`). Once 863 puts `sources` on the
  persisted assistant row, that predicate fires for store-plane rows too, and the same
  source set can be carried twice into one turn. The reconciliation rule must be
  written down, not left to whichever branch lands second.
- **Where evidence attaches on the store plane for an unhappy terminal.** 863 writes
  **no assistant row** there for a run that produced no answer — which is exactly
  §4.5's population. So for cancelled / `MAX_ITERATIONS` runs the store plane has no
  carrier at all, and the action-plane deltas are the *only* record. This is further
  support for §7.1's carrier choice, and it means 863's store-plane parity work does
  not subsume 865.
- **Pin the equivalence test against the `AgentDone` EVENT, not the projected thread**
  (review A9). Asserting over the thread projection would make the test sensitive to
  863's plane changes and would silently start measuring the wrong thing when 863
  lands.

### 7.10 Reach — principles, their evidence, and their retirement conditions

**P1 — Evidence is minted where it is established, not where it is reported.**
The terminal-minting pattern put the mint at the *reporting* site, so evidence
existed only on paths that reported. That is why cancelling destroys it.
*Where else this applies:* any record computed at a terminal from accumulated state —
run summaries, telemetry rollups, session receipts — has the same failure mode on
non-terminal exits. *Earns its keep if:* a future non-happy-path exit preserves its
record without anyone thinking about it. *Retire if:* the incremental shape proves to
cost more in ordering/consistency bugs than the terminal shape cost in lost records —
watch specifically for source-index misalignment (§7.1), which would be the tell.

**P2 — An UNDECLARED default branch in a projection is an undeclared policy.**
Exhaustive-by-compiler on one side and defaulted-by-string on the other guarantees
the two vocabularies drift silently in one direction. **Rev 2 qualification (review
A8): the objection is to a default that is nobody's decision, not to defaults as
such.** PR #538's declared, phase-level default is a legitimate third shape — the
default *is* the written decision — and a version of P2 that forbade it would be
wrong. What P2 rejects is silence, not fall-through.
*Where else this applies:* every `String`-keyed switch that projects one vocabulary
onto another — the wire→record fold here, and any wire→telemetry or wire→UI mapping
built the same way. *Already violated:* `fromRunEvent` today, with fourteen
fall-throughs. *Earns its keep if:* the next added event kind cannot reach production
without a recorded durability decision. *Retire if:* the explicit entries become a
rubber-stamp list nobody reads — at which point the declaration has stopped carrying
information and the compiler-level fix (a sealed vocabulary on both sides) is the
real answer.

**P3 — A label may only claim what the mint established; absence is a value.**
"Retrieved · not cited" over a run whose matcher never ran, `score: 0` over a number
nobody produced, a grounding claim over text the model never saw — one error in three
costumes. The repo has now hit it in 836, 849, 859 and again here.
*Where else this applies:* every derived label in the evidence surfaces, and any
future surface that renders a verdict from a possibly-absent input.
*Earns its keep if:* the tri-state (`unknown` ≠ `false`) keeps catching these at
review time. *Retire if:* it degenerates into absence-everywhere defensiveness that
leaves readers with blank panels — the failure mode on the other side, and 849's
"a header of five nulls is a row of empty space, not honesty" already names it.

**P4 (weaker, recorded not built) — Two surfaces answering different questions from
one input must declare the difference, not resolve it.**
The instinct on finding §4.4's divergence is to unify. That would be wrong here.
*Retire if:* the declared-difference tests become the only thing keeping two
genuinely-identical projections apart — that would mean the questions converged and
unification became correct after all.

## 8. Plan (2026-08-25)

### 8.0 Boundary

Plan only. This charter session's instruction is explicit: theorize → research →
design → plan, **do not implement**, and do not touch the dev stack. Nothing below
has been built. The live legs (§8.7) additionally need a model; rev 1 recorded that as
SAC-blocked, which is now stale — the stack works.

### 8.1 The split: four PRs

| PR | Contents | Scope items | Depends on |
|---|---|---|---|
| **PR-0** — the incomplete-grounding state | §7.3's present-tense fix: matcher-timeout runs stop rendering "Retrieved · not cited" | 3-partial | none — **independently landable ahead of PR-1** |
| **PR-1** — evidence survives | Incremental minting **stamped on `structuredData`** (§7.1) + FE accumulation both planes + the durability-vocabulary teardown + the `AgentGroundingSeamAuditTest` sweep | 1, 4 | #538 → 863 B → 863 A (§8.2) |
| **PR-2** — one answer | `toolSearchCard.ts` register row + `test:toolSearchCard.projection` guard + the **multi-call** divergence test | 2 | none — file-disjoint, parallel throughout |
| **PR-3** — received, not just retrieved | Mint an inclusion producer; stamp where `AgentCitationResolver.java:93` hardcodes `ABSENT` | 3-real | PR-1 |

**Rev 2 changes to the split:** PR-0 is new — §7.3 turned out to be a present-tense
bug rather than something PR-1 creates, so it no longer has to wait. PR-1 loses the
entire wire-vocabulary workstream (no new event kind, no descriptor, no fixture
regen, no `gen-shape-handlers`, no parity risk) under §7.1's carrier decision, and
gains the audit-predicate sweep. PR-3 is **no longer gated on a measurement** (A10)
— it is implementation.

**PR-1 is not split backend/frontend, deliberately.** An emitter shipped without a
consumer is a named failure mode in this repo (`wire-emitter-elision`,
`substrate-without-consumer-flavors`). The event and its two readers (live + record)
land together or the PR is proving nothing.

**PR-3 may correctly land as a no-op.** §7.5 gates it on whether the compressor's
message-level stripping can be mapped back to source identity. If it cannot, the
right outcome is to keep saying nothing and record why — that is a result, not a
failure, and the plan should not create pressure to ship a badge computed from a
guess.

### 8.2 Ordering — THREE neighbours on one switch (rev 2, review A11)

Rev 1 knew of one neighbour. There are three, all editing
`AgentInteractionMapper.fromRunEvent` or the evidence seam hanging off it.

**Adopted order: #538 → 863 Slice B → 863 Slice A → 865 PR-1.**
**PR-2 runs parallel throughout** (file-disjoint from all of them).
**PR-0 (§7.3) lands independently, ahead of PR-1** — it touches the FE grounding
projection, not the switch.

| Neighbour | State | Touches | 865's obligation |
|---|---|---|---|
| **#538** (progress-note durability) | **OPEN PR** — rev 1 recorded it as local-only; corrected | adds `case "progress"`; introduces a **declared phase-level default** | absorb `progress` as a declared entry; do **not** re-litigate it; treat its declared default as a legitimate third option (§7.7) |
| **863 Slice B** | designed, independently landable, lands first within 863 | FE merge rule | none directly |
| **863 Slice A** | designed; frontmatter says *"863 implements BEFORE 865, serial"* | the `done` case + `persistedAssistant` evidence parity | resolve the two-plane authority + double-carry questions (§7.9) **before** PR-1 is briefed |

- **PR-1 lands last of the four and merges `main` each time a predecessor lands.**
  Per `agent-lessons`, catch up with `git merge`, never a rebase of a pushed branch.
- **PR-1 must not also make progress notes durable, and must not pre-empt 863's
  store-plane work.** Those are #538's and 863's contracts. Duplicating either is how
  two branches produce one broken merge. Finding those cases already present is the
  expected state, not a conflict.
- **Under §7.1's carrier design the switch conflict shrinks to near-nothing** —
  evidence needs no new `fromRunEvent` case at all, so PR-1's only business in that
  file is the §7.7 explicit-entries teardown. That is a further practical argument for
  the carrier: it removes 865 from the contended seam almost entirely.

### 8.3 PR-1 — evidence survives (the correctness slice)

**Backend**

1. `AgentSession` — the `seen` set and the emitted list become session state; add a
   per-tool-call contribution entry point returning only newly-established sources;
   `collectGroundingSources()` becomes the accumulator's drain rather than a
   from-scratch recompute. The mint *rule* (two identity arms, dedup keys, 603 D-3
   sentinel, identity-less skip) is not touched.
   **Teardown, same PR:** rewrite the `collectGroundingSources` javadoc
   (`AgentSession.java:235-258`) — it currently describes a terminal aggregate and
   would become false authority (§7.8).
2. `OperationResult` — a `withGrounding(...)` sibling to `withLineage`
   (`OperationResult.java:79-91`): idempotent merge of the delta into
   `structuredData` under a new key, all other fields unchanged.
3. `AgentStepRunner` — stamp at the §3.8d locus, in the same block that already
   stamps `withLineage` (`:859-864`), before `sink.accept(ToolExecutionCompleted)`.
   Successful results only; **omit the key entirely when the delta is empty** (an
   absent key means "established nothing", consistent with `toolCompletedPayload`'s
   existing `if (!structuredData.isEmpty())` discipline).
4. **~~`AgentEvent` / `AgentEventPayloads` / `EVENT_SCHEMA` / fixture regen /
   `gen-shape-handlers`~~ — NOT REQUIRED under §7.1's carrier decision.** Struck from
   rev 1. `structuredData` is already declared
   (`AgentRunShape.java:90-99`) and already carried on both planes.
5. `AgentGroundingSeamAuditTest` — extend the predicate to cover the stamp seam, and
   correct its javadoc (§7.8 / review A4). **Same PR.**
6. `AgentInteractionMapper.fromRunEvent` — the §7.7 teardown only: `AgentRunShape`
   names written as explicit non-projecting entries, `default` retained for the
   other shapes' vocabularies. **No existing kind's durability changes**, and
   evidence adds no case. Reviewers should be able to diff this hunk and see zero
   behaviour change.

   **Rev 3 (implementation) — DEFERRED TO PR-3, and this is a routing decision, not
   a discovery.** It did not ship in PR-1 (#551). It is the one item here with no
   behavioural coupling to the mint: evidence adds no case to that switch, so the
   hunk neither enables nor is enabled by anything else in PR-1, and its whole
   value is legibility — making "everything unlisted is silently dropped" read as
   "this vocabulary is complete". Recorded rather than left to be noticed: a
   zero-behaviour-change teardown that slips out of the PR it was planned for is
   exactly the residue `retire-with-a-sweep` warns about, and naming its new home
   is what stops "a follow-up will clean it up" from being the end of the story.

   **Rev 3 — LANDED in PR-3, and it paid for itself on the first run.** Thirteen
   `AgentRunShape` kinds are now explicit non-projecting cases; `default` is retained
   and still bites for the vocabularies `AgentRunShape` does not declare (the workflow
   node journal, `search_executed`). Zero behaviour change, pinned by
   `AgentInteractionMapperTest.declaredNonProjectingKindsStayNonProjecting`. The
   legibility argument turned out to be the smaller half: the closure guard added with
   it (`AgentRunDurabilityClosureTest`, in `app-services` because that is where
   `AgentRunShape` lives) failed on its FIRST run over a kind no one had classified —
   `intent.resolution`, the composed `core.url-extractor` consumer's namespaced event,
   which is `EventDescriptor.nameOnly` and had been reaching `default` unnoticed. That
   is the exact "nobody decided, and it is indistinguishable from someone deciding no"
   shape §7.7 describes, found by writing the vocabulary down.

**Frontend**

7. `AgentSessionController` — accumulate the delta off `tool_exec_completed`'s
   `structuredData` into the run's evidence state, in tool-call order. No new event
   handler, no reasoning-boundary change: `tool_exec_completed` is already handled and
   already non-exempt.
8. Live path (`SearchV3View`) and record path (`sv3-record.ts`) both feed the
   existing `agentEvidence` projection — no new projection module.
9. The §7.3 incomplete-grounding state arrives via **PR-0**, ahead of this PR. PR-1
   consumes it — a `MAX_ITERATIONS` or cancelled run reaching the panel must land in
   that state, not in "not cited".

**Tests — the four that carry the design**

- **Terminal equivalence + index alignment.** Concatenated deltas equal the terminal
  `sources`, in order, **on the two `groundedDone` terminals only** (§7.1 A5 —
  `ofDisposition` is exempt by construction and must not be changed to drain). Pin it
  against the **`AgentDone` event**, not the projected thread (§7.9 A9). Must fail if
  either side is re-derived independently — this is the guard on
  `AgentSentenceCite.sourceIndex` (`AgentEvent.java:171`) resolving via
  `sources[cite.sourceIndex]` (`agentEvidence.ts:76`). Silent mark-misdirection is
  this PR's one catastrophic failure mode.
- **Empty delta ⇒ no key.** A tool call establishing nothing adds no key to
  `structuredData`.
- **`MAX_ITERATIONS` retains evidence.** The sharpest case (§3.8b): a run that
  exhausts iterations emits `AgentDone.ofDisposition` with `List.of()`, yet the tool
  events carry the full delta set. Also cover a cancel after ≥2 searches. Homes:
  `AgentSessionGroundingTest`, `AgentLoopServiceTest`.
- **Grounding-seam audit covers the stamp.** Reverting the stamp seam must fail
  `AgentGroundingSeamAuditTest` (review A4) — otherwise the extension is decorative.

Parity note: `sv3-timeline-parity.test.ts` should be **unchanged and still green** —
under §7.1 no new event kind exists, so a diff to that test is a signal the design
drifted back toward rev 1.

### 8.4 PR-2 — one answer to "what did this run draw on"

1. Register row for `modules/ui-web/src/shell-v0/components/chat/toolSearchCard.ts`
   in `governance/execution-surfaces.v1.json` — `kind: "projection"`, `lang: "ts"`,
   a named guard, and a note recording *why* it is a separate projection from the
   evidence accumulator (§7.4). Follow the `agent-evidence-projection` row's shape
   (`:247-253`).
2. Guard test backing the row. **The guard name must satisfy the conformance regex:
   `test:toolSearchCard.projection`** (review A7) — a non-conforming name fails
   `register-guard-resolution`.
3. **The deliberate-divergence test — MULTI-CALL, mandatory** (review A7). ≥2 search
   calls sharing at least one document, asserting the receipt shows it twice and the
   accumulator once. A single-call test passes trivially and proves nothing, because
   the worker has already collapsed hits to one per parent
   (`SearchExecutor.java:1029-1051`) — dedup and identity-filtering are **inert**
   within a call. Rev 1's version would have been exactly that vacuous test.
4. **Do not widen the scan regex** — a governance decision, route to `/governance`.
   (Rev 1's stated reason was wrong: `expectedMinPopulation` is a floor / vacuous-pass
   guard, not a status ledger.) Record in the row's note that this declares the
   **instance**, not the class: the enforcer iterates `reg.surfaces` and does not
   re-scan, so the next unregistered re-modeller stays invisible.

Verification: `node scripts/governance/run.mjs --gate execution-surface --mode gate`,
plus the ui-web gate set (the consult hook pushes the `ui-web-gates` recipe).

### 8.5 PR-3 — received, not just retrieved (implementation, not a bet)

**Rev 2: the L-3 gate is retired** (review A10 — see §4.11). The mapping is statically
recoverable, so this is implementation work:

1. **Mint an inclusion producer** on the delegate plane: for each source, decide
   `included` / `partial` / `dropped` from the deterministic compression state of the
   tool message that carried it, joined by the `tool_call_id` that survives
   compression (`AgentContextCompressor.java:88-91`).
2. **Stamp it where the resolver currently hardcodes absence** —
   `AgentCitationResolver.java:93` constructs every `ContextCitation` with
   `DocumentService.ContextInclusion.ABSENT`. That single site is the seam.
3. 849's existing `suppressGroundingFor` / `inclusionBadge` then act unchanged —
   **reusing** the vocabulary, adding no second suppression rule.
4. **Say which layer is reported.** Three truncation layers exist (§4.11); 849's
   vocabulary models **Layer 3** only. A badge that silently means "Layer 3" while
   reading as "the truncation" is the overclaim to avoid.
5. **The affected surfaces are the pane and the panel** (not "both surfaces" in rev
   1's loose sense) — `suppressGroundingFor` is consumed by `citationHeader`, which
   serves both.

### 8.6 Gates

| Edited subject | Check |
|---|---|
| `governance/execution-surfaces.v1.json` + a registered referencer | `--gate execution-surface`, `--gate register-guard-resolution` |
| new run event kind / shape fixture | `check-shape-handler-regen` + the two conformance tests |
| `modules/ui-web/src/**` | the ui-web gate set (consult-hook recipe `ui-web-gates`) |
| visible panel change | `check-ui-step-coverage` if a new RAIL surface or ui-shot step is added |

Full kernel + full suite before declaring done, not at merge (`subset-isnt-the-suite`).

### 8.7 Live legs

All need `ai_activate`. **Rev 2: SAC is unblocked and the stack works** — rev 1's
owner-blocked note was stale. Run in a **visible** window — 860's rAF/hidden-tab
artifact invalidates automation evidence for anything rAF-gated.

- **L-0 (feeds 866, no longer gates 865):** confirm §3.1 live — the agent has no read
  tool; observe what it does with "read these three files and summarise", and whether
  scoped `search-index` serves it.
- **L-1:** `MAX_ITERATIONS` and cancel, each after ≥2 searches — mint absent before,
  present after. The `MAX_ITERATIONS` half is the one rev 1 missed (§3.8b).
- **L-2:** a **multi-call** turn (≥2 searches sharing a document) — count tool-card
  hits vs panel entries. A single-call turn will show no divergence by construction
  (review A7), so a single-call L-2 would falsely retire the finding.
- **~~L-3~~ RETIRED** (review A10) — the mapping is statically recoverable; PR-3 is
  implementation, not a measurement bet.
- **L-4:** reload a run carrying the stamped deltas; record-path accumulation matches
  live, reasoning blocks unmoved.
- **L-5:** independent measured whole-screen UX audit (axe/contrast oracle,
  auditor ≠ committer) — presentation-authority closure per `slice-execution.md`.
  **Applies to PR-0 and PR-1** (§7.3 marks both as presentation-authority).

  **Rev 3 (implementation) — OPEN after PR-1 (#551), pooled for the next dev-stack
  window.** PR-1 shipped with no live tier at all: no stack was leased for it, so
  L-1..L-4 and L-5 are all unrun, and every claim in that PR is compile-, unit- and
  gate-tier. That is worth stating plainly because this slice changes what the
  SOURCES pane says about a source — "Retrieved · not examined" and "Retrieved ·
  grounding check did not complete" are new words a reader will act on, and no one
  has yet seen either rendered. The audit covers PR-0 and PR-1 together (both
  presentation-authority, both touching the same `sourceGrounding` states), which is
  why pooling them into one window is the right shape rather than a way of putting
  it off. **This is not a substitute trigger and does not license closing 865 without
  it** — `slice-execution.md`'s `ux-audit-closure` is honor-system since 563, and an
  unrun honor-system gate is the one that most needs its absence written down.

### 8.8 Delegation

- **PR-0 → one worker, `sonnet`.** Bounded FE change, but the brief must carry §7.3's
  full mechanism verbatim — especially *"not `'unexamined'`"*, which is the one way
  this fix goes subtly wrong.
- **PR-1 → one worker, `opus`.** Java + TS, and its failure mode (source-index
  misalignment) is silent. The brief must inline: §7.1's carrier decision **and why
  the new-event option was rejected** (or a worker will "helpfully" reintroduce it),
  the §7.1 A5 equivalence scoping, the §7.9 863 questions, the §8.2 ordering, the A4
  audit sweep, and a ban on changing any existing kind's durability.
- **PR-2 → one worker, `opus`** (raised from rev 1's `sonnet`, review A11). Two
  constraints are easy to get wrong and self-certifying when wrong: the guard-name
  regex, and the multi-call test — a sonnet worker writing the obvious single-call
  test produces a **green, vacuous** gate, which is worse than no test. If run on
  sonnet, both constraints must be inlined verbatim.
- **No regen steps** — struck with the wire workstream (§8.3 item 4).
- **L-legs → main loop.** Dev-stack lease acquisition and contention decisions stay
  main-loop; the measured audit needs an independent auditor.
- **Never delegated:** the merge, and the §8.2 ordering judgement across #538 / 863.

### 8.9 Effort estimate

**Rev 2 shifted the weight downward.** PR-1 loses the entire wire-vocabulary
workstream — no `AgentEvent` record, no payload cases, no `EventDescriptor`, no
fixture regen, no `gen-shape-handlers`, no parity reconciliation — leaving a
session-state refactor, one `OperationResult` sibling method, one stamp site, two FE
accumulators, the audit-predicate sweep, and four tests. That is materially smaller
than rev 1's estimate, and the removed parts were the highest-risk ones.

PR-0 is small and independent. PR-2 is small but **not trivially delegable** (the
vacuous-test trap, §8.8). PR-3 is now bounded implementation rather than an open
measurement.

Recommended sequencing: **PR-2 parallel from the start**; **PR-0 as soon as it is
written** (it fixes a live defect and unblocks nothing); **PR-1 after
#538 → 863 B → 863 A**; **PR-3 after PR-1**.


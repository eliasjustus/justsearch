---
status: design
created: 2026-07-12
updated: 2026-07-12
note: >
  Renumbered from an uncommitted 720-numbered plan file (tempdoc-number collision with
  720-memory-read-injection.md); rescued 2026-07-17 from stale agent worktree before
  teardown. Parent/router tempdoc: 720-memory-read-injection.md.
---

# 752 (slice) — Memory read-injection: takeover → design → derisk → plan

Scope note: this file covers ONE lever carved out of tempdoc 720 (not committed in this
worktree — the parent conversation supplied the relevant slice verbatim). It does not
speak to 720's other items. Design/derisk/plan only — no code changes in this pass.

## 1. TAKEOVER

### 1.1 The write-only gap, verified at source

Every production call site touching `MemoryStore` (interface:
`modules/app-agent-api/src/main/java/io/justsearch/agent/api/memory/MemoryStore.java:14-32`,
impl `modules/app-agent/src/main/java/io/justsearch/agent/FileMemoryStore.java`):

| Site | File:line | Reads or writes? |
|---|---|---|
| `RememberFactHandler.execute` | `modules/app-services/src/main/java/io/justsearch/app/services/registry/operations/handlers/RememberFactHandler.java:70` | writes (`memoryStore.remember(record)` — the `core_remember` agent tool) |
| `MemoryExtractionConsumer.onDone` | `modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/MemoryExtractionConsumer.java:57` | writes (passive chat-turn heuristic extraction) |
| `MemoryController.handleList` | `modules/ui/src/main/java/io/justsearch/ui/api/MemoryController.java:41` | reads (`memoryStore.whatItKnows()`) — **human-facing** `GET /api/memory`, not model-facing |
| `MemoryController.handleRemember` | `MemoryController.java:75` | writes |
| `MemoryController.handleForget` | `MemoryController.java:85` | deletes |
| `HeadAssembly.memoryStore()` accessor + construction | `modules/app-services/src/main/java/io/justsearch/app/services/HeadAssembly.java:636-911` | wiring only |

Grep of `whatItKnows` repo-wide returns exactly these two readers (`MemoryController`, plus
the `HeadAssembly` authored-store export closure at `HeadAssembly.java:647`, which is a
backup/export projection, not a prompt path). **No prompt-construction site reads it.**
Confirmed directly against the three named candidate sites:

- `RAGContext.inject` (`modules/app-services/.../spi/RAGContext.java:102-`) — builds context from
  `DocumentService.retrieveContextWithMeta`/`fetchBatch` only; no `memoryStore` reference.
- `ExternalContextInjector.inject` (`.../spi/ExternalContextInjector.java:40-79`) — forwards the
  FE-supplied `context` array only.
- `QueryRewriteInjector.inject` (`.../spi/QueryRewriteInjector.java:71-116`) — rewrites the
  question via `OnlineAiService`; no memory reference.
- `AgentPromptComposer.buildSystemPrompt` (`modules/app-agent/src/main/java/io/justsearch/agent/AgentPromptComposer.java:77-101`)
  — composes `DEFAULT_SYSTEM_PROMPT` + an optional root-paths preamble + an optional
  `conditionContextSupplier` string (slice 447). No memory reference; the only "recall" affordance
  is the prompt text at line 54-57 that tells the model to *write* via `core_remember`, not a
  reader of what's already known.

**Verdict: the write-only gap is real and precisely as described.** Every write path
(`RememberFactHandler`, `MemoryExtractionConsumer`) lands in the same single authority
(`FileMemoryStore`, enforced by the `operation-surfaces.v1.json` "memory single-authority" guard,
lines 39-60 — a second store re-modeling learned memory is a build failure), but nothing hands
that content back to the LLM at generation time. The `/api/memory` panel is a mirror for the
*user*, not a channel to the *model*.

### 1.2 The embedding-model reachability question — the load-bearing finding

Tempdoc 720's proposal assumes the "resident embedding model" is reachable from Head
prompt-construction code at negligible marginal cost ("we already embed per query"). This is
**false as stated** — verified by grepping every embedding call site:

```
modules/worker-core/src/main/java/io/justsearch/indexerworker/embed/EmbeddingService.java
  :227  public float[] embed(String text)
  :238  public float[] embedDocument(String text)   // prepends documentPrefix
  :248  public float[] embedQuery(String text)       // prepends queryPrefix (asymmetric E5/GTE-style)
  :382  public List<float[]> embedDocumentBatch(List<String> texts)
```

Every embedding call site (`OnnxEmbeddingEncoder`, `OnnxEmbeddingBackend`, `EmbeddingProvider`,
`EmbeddingService`) lives under `modules/worker-core` / `modules/indexer-worker` — the **Worker
(Body)** process, which owns Lucene and the ONNX runtime session. Head-side modules
(`modules/app-services`, `modules/app-agent`, `modules/ui`) have **no direct classpath access** to
`EmbeddingService` — this is the same boundary Hard Invariant #1 (head-never-touches-lucene) protects,
and it extends to the encoder that lives in the same process for the same reason (GPU/ORT session
affinity, not just index IO).

Cross-checked against the Head↔Worker contract surface: `contracts/wire/knowledge.proto` defines
only search-shaped messages (`KnowledgeSearchResponse`, `Hit`, `QueryUnderstanding`, …) — no
`EmbedText`/`EmbedRequest` RPC exists. `modules/app-api/src/main/java/io/justsearch/app/api/DocumentService.java`
(the Head-side facade Worker calls are routed through, e.g.
`modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteDocumentService.java`,
which wraps `RemoteKnowledgeClient` gRPC calls for `fetch`/`fetchBatch`/`retrieveContextWithMeta`/
`matchCitations`) has **no embed-only method**. "We already embed per query" is true only in the
sense that the Worker embeds the query as an internal step of `KnowledgeSearchEngine`'s hybrid
retrieval — that vector is never returned to Head, and there is no existing seam for Head to ask
"embed this arbitrary string for me."

This does not kill the lever — it changes its shape. The tempdoc 720 proposal ("we already embed
per query — marginal cost") is not free; embedding-based read-time scoring requires **one new,
narrow gRPC RPC** exposing `EmbeddingService.embed{Query,Document}` to Head. This is a small,
well-precedented addition (same pattern as every other `RemoteDocumentService` method), does not
touch Lucene, and does not violate Hard Invariant #1 — Head still never opens an index or a Lucene
class, it only asks Worker to run its resident encoder on a string and hand back a vector. Scope
is genuinely narrow: one RPC, one Worker-side handler method, one Head-side client method.

### 1.3 GO / NO-GO

**GO — with one addendum to the proposed design**: read-injection is buildable, but "reuse the
resident embedding model at negligible marginal cost" requires a new Head↔Worker RPC, not a free
lunch. The rest of tempdoc 720's frame (relevance-gated, hard-capped, no per-turn LLM extraction)
matches existing patterns closely enough that the risk is almost entirely in that one new surface
(§3 derisks it) and in an embedding-cache placement that must NOT create a second memory authority
(the `operation-surfaces.v1.json` guard at lines 39-60 fails the build on exactly that shape of
mistake, so the design must be explicit that a Head-side vector cache is a volatile, in-memory,
non-authoritative *projection*, never persisted, never a store of record).

**Confidence: 7/10.** High confidence in the write-only diagnosis (grep-verified, unambiguous) and
in the injector-composition pattern (three closely analogous precedents exist:
`ExternalContextInjector`, `RAGContext`, and the `AgentPromptComposer` `conditionContextSupplier`
pattern from slice 447). Medium confidence on the new-RPC sizing (haven't read the Worker-side
gRPC service impl class or `RemoteKnowledgeClient`'s stub-wiring boilerplate end to end, so "one
new RPC" could turn out to be two files or six depending on how much ceremony the existing
gRPC-client scaffolding requires per method — see §3 R1). Low residual risk on privacy: `forget`/
`clear`/lock-state already flow correctly through `whatItKnows()` (locked → empty cache,
`FileMemoryStore.java:101-105`; forgotten id → absent from `byId`, `:83-87`), so a read-time-only
consumer of `whatItKnows()` inherits those guarantees for free — nothing new to build there.

## 2. DESIGN

### 2.1 Two distinct injection sites, not one

Tempdoc 720 names `AgentPromptComposer` / `RAGAskShape` / `FreeChatShape` together, but they are
**two different prompt-construction mechanisms** and need two different hookups:

**(a) Substrate `ContextInjector` SPI** — used by `RAGAskShape` and `FreeChatShape` (both
substrate-driven conversation shapes). Injectors are registered once at boot into a
`ContextInjectorRegistry` and referenced by id from each `ConversationShape`'s
`contextInjectorIds` list:

- Registration site: `modules/ui/src/main/java/io/justsearch/ui/api/ConversationApiAssembly.java:174-185`
  (`ContextInjectorRegistry.of(List.of(new DocAccess(docs), new BatchDocAccess(docs),
  new RAGContext(docs), UserPromptInjector.INSTANCE, ExternalContextInjector.INSTANCE,
  new QueryRewriteInjector(onlineAiSupplier), new SelectionContextInjector(docs)))`).
- Declaration site: `RAGAskShape.definition()` —
  `List.of(ExternalContextInjector.ID, QueryRewriteInjector.ID, RAGContext.ID)`
  (`modules/app-services/.../shapes/RAGAskShape.java:65`); `FreeChatShape.definition()` —
  `List.of(ExternalContextInjector.ID, "core.user-prompt")`
  (`modules/app-services/.../shapes/FreeChatShape.java:62`).
- Contract: `ContextInjector.inject(ConversationContext ctx)` returns an `InjectorResult`
  (messages to prepend + SSE events + optional terminal error) — factory methods `empty()`,
  `messagesOnly(List)`, `of(...)`, `terminalError(...)`
  (`modules/app-agent-api/.../conversation/InjectorResult.java:39-62`). Multiple injectors on one
  shape compose by declaration order, concatenated before the user message
  (`ContextInjector.java:33-34`).

A new **`MemoryContext` injector** slots in here: constructed with `HeadAssembly.memoryStore()`
(same accessor `MemoryExtractionConsumer` already uses at `ConversationApiAssembly.java:158`),
registered in the same list, and added to both shapes' `contextInjectorIds`. It should run early
(before `RAGContext`/`QueryRewriteInjector`, since it doesn't depend on retrieval output) —
ordering matches `ExternalContextInjector`'s position (first) in both shapes today.

**(b) Agent-loop `AgentPromptComposer` Supplier pattern** — used by the tool-iterating agent loop
(`core_search_index` / `core_remember` / etc., NOT a substrate `ConversationShape`). This composer
already has exactly the right extension point: `conditionContextSupplier`
(`AgentPromptComposer.java:67`, wired via `setConditionContextSupplier` at `:127-129`, invoked at
`buildSystemPrompt()` time via `appendConditionContext` at `:108-120`). It is
failure-tolerant-by-construction (`try`/catch around the supplier call, `:112-117`) and wired from
`modules/app-services/.../bootstrap/phases/AgentLoopWiring.java:58,93`. The natural move is a
**second** supplier slot (`memoryContextSupplier`, same shape) rather than overloading the existing
one — the condition-recovery text and the memory-recall text are different concerns with different
producers, and conflating them into one string makes the boot-time failure-tolerance harder to
reason about (a memory-store hiccup shouldn't silently also suppress condition-recovery text, or
vice versa). `AgentLoopWiring` gains one more constructor parameter alongside
`conditionContextSupplier` at line 58.

Both paths ultimately call the same scoring/formatting helper (§2.3) — a small shared class, not
duplicated logic.

### 2.2 What the model-visible block looks like

Mirrors `RAGContext`'s and `ExternalContextInjector`'s style: a single labeled section, not raw
JSON. Precedent: `RAGContext` emits retrieved chunks as `"[From: <filename>]\n<content>"` sections
joined by `DocumentService.SECTION_SEPARATOR` (`RAGContext.java` / `DocumentService.java:21`); the
agent system prompt's condition-recovery block is a plain "Currently asserted conditions and
recommended recoveries" text section appended after a blank line
(`AgentPromptComposer.java:119` — `basePrompt + "\n\n" + context`). The memory block should follow
the same convention: a short labeled preamble ("What you already know about this user, from past
conversations:") followed by newest-first bullet lines of surviving records' `content()` fields
(never raw `MemoryRecord` JSON — the model should see prose, matching how `whatItKnows()` is
already rendered to the human panel).

### 2.3 Scoring + capping (the core of the lever)

1. **Embed the live turn once.** For `RAGAskShape`, the same `question` (or the
   `QueryRewriteInjector`-decontextualized standalone form when present — but the memory injector
   runs *before* that stage per §2.1 ordering, so it uses the raw question; this is an acceptable
   trade, not a defect, since memory relevance is coarser-grained than retrieval precision). For
   `FreeChatShape`, the current user message. For the agent loop, the latest user turn at prompt
   time.
2. **Embed each surviving memory record's `content()`.** Call `whatItKnows()` fresh on every turn
   (already lock/forget-safe per §1.3) — do NOT cache the *list*, only cache *vectors* keyed by
   `(id, content-hash)` so a `remember()` overwrite (same id, new content — `FileMemoryStore.java:70`,
   idempotent-on-id) naturally invalidates the stale vector without extra bookkeeping. This cache is
   **purely in-process, ephemeral, and rebuildable from `whatItKnows()` at any time** — it must be
   documented as a derived projection, not a second authority (the `operation-surfaces.v1.json`
   guard at lines 39-60 explicitly calls out a hypothetical `VectorMemoryBank` as the kind of fork
   that fails the build; this cache must never be framed or persisted as one).
3. **Score via cosine similarity** on the two embedding families the resident encoder already
   defines: `embedDocument()` (asymmetric task-prefix) for memory content — same treatment the
   corpus gets at index time — and `embedQuery()` for the live turn, mirroring exactly how search
   already treats the corpus/query asymmetry (`EmbeddingService.java:238-250`). This is a direct
   reuse of the existing multilingual, locale-invariant model convention (ADR-0043 / Hard Invariant
   #6) — no new per-language logic anywhere in this lever.
4. **Cap hard, in this order**: (a) top-N by score (config default N=5, tempdoc-configurable), (b)
   drop anything below a minimum-similarity floor (avoid injecting irrelevant facts just to fill N —
   mirrors the CRAG-style `QualitySignals.bestChunkScore`/`scoreGap` calibration precedent in
   `DocumentService.java:190-198`, though memory doesn't need the full QualitySignals record, just a
   floor), (c) token-budget truncate the assembled block via the existing
   `modules/core/src/main/java/io/justsearch/core/util/TokenEstimation.java` helper — same utility
   `ExternalContextInjector` (`MAX_CONTEXT_TOKENS = 1000`, `:30`) and `RAGContext`
   (`computeSafeInputBudgetTokens`, `:191`) already use, budget default meaningfully smaller than
   `ExternalContextInjector`'s (memory facts are short; 200-400 tokens is plenty for N=5).

### 2.4 Config knobs

New config surface (small): `memory.injection.enabled` (default true — but see §3 derisk on
opt-out), `memory.injection.topN` (default 5), `memory.injection.minSimilarity` (default TBD via
calibration, not a guess — see §4 test plan), `memory.injection.maxTokens` (default ~300). Follows
the existing settings-surface pattern (`serviceOut.settings()` threaded through
`HeadAssembly.java:607` — the exact settings-record location wasn't fully traced in this pass;
implementer should locate the settings schema/record before wiring, not invent a parallel config
path).

### 2.5 Privacy scoping — what's already handled vs. what's new

Already handled for free by reading `whatItKnows()` at call time rather than caching the list:
- **Encrypt-at-rest / lock**: `FileMemoryStore.onKeyLocked()` clears `byId` to empty
  (`FileMemoryStore.java:61-63`); `whatItKnows()` on a locked store returns `List.of()`
  (`:75-80` operating on an empty map) — the injector degrades to "no memory block" automatically,
  no new locked-state handling needed.
- **User forget**: `forget(id)` removes from `byId` (`:83-87`) — next turn's `whatItKnows()` simply
  omits it; the *embedding cache* (§2.3.2) must also evict on the same id so a forgotten fact's stale
  vector doesn't linger in the in-process cache after a `clear()`/`forget()` call. This is new
  wiring the injector must do (subscribe to or re-derive from `whatItKnows()`'s current id set each
  turn, dropping cache entries for ids no longer present) — cheap (a set-difference), but must not be
  skipped, since the whole point of `forget` is user-visible, immediate removal.

### 2.6 What this design does NOT touch / supersede

- `MemoryExtractionConsumer` (writer heuristic) — unchanged, per tempdoc 720's own instruction (no
  per-turn LLM extraction; `MemoryExtractionConsumer.java:16-27`'s cost-realism citation of 559 §15
  stands).
- `MemoryController` (`/api/memory` human panel) — unchanged; it remains the *user*-facing
  projection, the new injector is the *model*-facing one. Two different consumers of the same one
  authority — exactly the "projection, not fork" shape the register wants.
- `RememberFactHandler` — unchanged.

## 3. DERISK

| # | Risk | Detail | Confidence impact | Mitigation |
|---|---|---|---|---|
| R1 | New-RPC sizing unknown | Haven't read the Worker-side gRPC service impl (`GrpcSearchServiceImpl` or similar) or `RemoteKnowledgeClient`'s per-method boilerplate end-to-end; "one new RPC" could be 1 file or up to ~4-5 (proto message + Worker handler + stub-client method + Head-side wrapper + test doubles) | Medium — sizing risk, not feasibility risk | Before implementation, read one existing RPC (`fetchDocuments` or `matchCitations`) top-to-bottom as the template; budget effort against that, not against a guess |
| R2 | Context-budget blowup | An unbounded or misconfigured `topN`/`maxTokens` silently grows every prompt on every turn, forever, as the store grows | Design already caps in 3 dimensions (§2.3.4); the residual risk is a bad *default*, not a missing cap | Calibrate `minSimilarity` and `maxTokens` empirically (§4), don't ship an unvalidated guess |
| R3 | Read-time scoring latency | Cold cache: embedding N memory records + 1 query on the first turn after boot/unlock adds N+1 Worker round-trips (or 1 batched call if `embedDocumentBatch`-equivalent RPC batches it — `embedDocumentBatch` already exists Worker-side at `EmbeddingService.java:382`, so batching the cold-fill is straightforward) before the LLM call starts | Medium | Warm the cache once per store size (bounded — memory stores are small, tens to low hundreds of facts per §1.3), not per-turn; only the 1 live-query embed is on the turn's critical path after warm-up |
| R4 | Head↔Worker boundary during Worker restart/unavailability | The new RPC can fail (Worker mid-restart, mid-migration-switch, etc.) — must degrade like every other Worker call | Low-medium | Same failure-tolerant pattern as `AgentPromptComposer.appendConditionContext` (`:112-117`, try/catch, log+return-base-on-throw) and `QueryRewriteInjector` (AI-unavailable → `InjectorResult.empty()`, `:81-83`) — a memory-embed failure must never abort the turn, only degrade to no-memory-block |
| R5 | Empty-store degradation | New user / cleared store / all facts below `minSimilarity` floor | Low | `InjectorResult.empty()` — same no-op path `ExternalContextInjector` already uses when its input list is empty (`:42-44`) |
| R6 | Stale/contradictory facts | Two memories can directly contradict (user changes their mind; `remember()` is idempotent only on exact-same id, and both the agent-tool and the passive-chat producer mint fresh ids for new statements — nothing merges "I use VS Code" with a later "I switched to IntelliJ") | Medium — this is a data-quality problem tempdoc 720's design doesn't solve and this design shouldn't pretend to either | Out of scope for this lever; log as a follow-up (a recency-weighted score term, or a dedup/supersession pass on write) rather than silently degrading trust in the read side. Do not conflate "read-injection works" with "memory contents are internally consistent." |
| R7 | Second-authority drift | A poorly-scoped embedding cache (e.g., persisted to disk, or given its own file) trips the `operation-surfaces.v1.json` memory single-authority guard (lines 39-60) and fails the build | Low if built as designed (in-process, ephemeral, keyed by id+content-hash, never persisted) | Register the new injector as a `memory-consumer` entry in `governance/operation-surfaces.v1.json` (alongside `memory-producer-agent-tool`, `memory-producer-passive-chat`, `memory-controller` at lines 361-385) explicitly noting the cache is volatile/non-authoritative, so the gate has the declaration it expects instead of discovering an undeclared new referencer |
| R8 | Ordering interaction with `QueryRewriteInjector` | Per §2.1, `MemoryContext` scores against the raw (not decontextualized) question since it must run before `QueryRewriteInjector` (no dependency the other way) | Low | Acceptable per §2.3.1 — memory relevance is coarse-grained; document the trade rather than adding an ordering dependency that doesn't otherwise exist |

**Implementation confidence: 6/10.** The composition pattern (§2.1) is high-confidence — it's a
near-exact structural copy of three things that already exist and work
(`ExternalContextInjector`'s registration/declaration mechanics, `AgentPromptComposer`'s supplier
pattern, `RAGContext`'s token-budget-then-cap style). What pulls confidence down from 8 to 6 is R1
(unread RPC boilerplate — a half-day of implementation could become a day and a half) and R6 (the
lever's *value* depends on memory contents being reasonably clean, which this lever doesn't control
and tempdoc 720 doesn't address).

**Recommended model/effort**: Sonnet-tier implementation (per CLAUDE.md's delegation-economics
rule — this is bounded, verifiable, closely-patterned work, not novel architecture), chunked into
at least 3 delegable pieces: (1) the new Worker↔Head embed RPC + Worker handler + Head client
method, verified with a unit test round-trip; (2) the `MemoryContext` `ContextInjector` +
`memoryContextSupplier` agent-loop wiring + scoring/capping helper, verified with unit tests
mocking the embed client; (3) the acceptance eval (§4). Estimate: 1.5-2.5 days total including the
governance-register update (R7) and calibration runs (§4), assuming R1 doesn't surprise.

## 4. PLAN

### 4.1 Files/classes to touch (new unless noted)

**Worker↔Head embedding RPC** (new, narrow):
- `contracts/wire/knowledge.proto` — add `EmbedTextsRequest { repeated string texts = 1; bool as_query = 2; }` /
  `EmbedTextsResponse { repeated FloatVector vectors = 1; }` (or reuse an existing repeated-float
  wire shape if one exists — check before adding a new message type) + the RPC itself on whichever
  service `fetchDocuments`/`matchCitations` live on (read `RemoteKnowledgeClient` first, per R1).
- Worker-side handler (wherever `GrpcSearchServiceImpl`/equivalent lives) — delegates to
  `EmbeddingService.embedDocumentBatch` (already exists, `:382`) for `as_query=false`, or a small
  per-item `embedQuery` loop for `as_query=true` (query batches are size-1 in this lever's actual
  usage, so no batched-query method is needed).
- `RemoteKnowledgeClient` — new `embedTexts(List<String>, boolean asQuery)` method, same shape as
  its existing `fetchDocuments`/`matchCitations` methods.
- `RemoteDocumentService` (or a new narrow `EmbeddingClient`-style interface if `DocumentService`
  is the wrong home — judgment call for the implementer after reading `DocumentService`'s full
  interface, only ~200 of which were read in this pass) — exposes the capability Head-side code
  calls.

**Scoring/formatting** (new):
- A small shared class, e.g. `modules/app-services/.../conversation/spi/MemorySalienceScorer.java`
  — takes the live-turn text + `List<MemoryRecord>` + the embed client, returns the capped,
  formatted block (or `Optional.empty()`). Owns the id+content-hash vector cache (§2.3.2) and the
  cache-eviction-on-forget logic (§2.5). Unit-testable in isolation (mock embed client → known
  vectors → assert cap/order/format).

**Substrate injector** (new):
- `modules/app-services/.../conversation/spi/MemoryContext.java` implementing `ContextInjector`
  (mirrors `RAGContext`'s / `ExternalContextInjector`'s shape), constructed with
  `HeadAssembly.memoryStore()` + the new embed client + `MemorySalienceScorer`.
- `ConversationApiAssembly.java:174-185` — add `new MemoryContext(...)` to the
  `ContextInjectorRegistry.of(List.of(...))` call, guarded by `b.HeadAssembly != null` like
  `MemoryExtractionConsumer` is at `:155-159`.
- `RAGAskShape.java:65` — add `MemoryContext.ID` to `contextInjectorIds` (before `RAGContext.ID`
  per §2.1 ordering).
- `FreeChatShape.java:62` — add `MemoryContext.ID` to `contextInjectorIds`.

**Agent-loop supplier** (new):
- `AgentPromptComposer.java` — new `memoryContextSupplier` field (mirrors `conditionContextSupplier`
  at `:67`), a `setMemoryContextSupplier` setter (mirrors `:127-129`), and its own failure-tolerant
  append step in `buildSystemPrompt()` (mirrors `appendConditionContext`, `:108-120`) — composed
  after the condition-recovery text, i.e. base prompt → root-paths preamble → condition-recovery →
  memory block (append-only, order is a minor judgment call, not load-bearing).
- `AgentLoopWiring.java:58,93` — new constructor parameter `Supplier<String> memoryContextSupplier`,
  wired the same way as `conditionContextSupplier`.
- Wiring site upstream of `AgentLoopWiring` (wherever it's constructed — trace from `:58`'s caller)
  — builds the supplier from the same `MemorySalienceScorer` instance the substrate injector uses
  (one scorer, two callers — not two copies of the scoring logic).

**Config**:
- Wherever the settings record lives (trace from `HeadAssembly.java:607`'s `serviceOut.settings()`
  before inventing a new config path) — add `memory.injection.{enabled,topN,minSimilarity,maxTokens}`.

**Governance**:
- `governance/operation-surfaces.v1.json` — new `memory-consumer`-style entry near lines 361-385
  (alongside `memory-producer-agent-tool`/`memory-producer-passive-chat`/`memory-controller`),
  declaring the new injector + its ephemeral embedding cache as a read projection, not a second
  authority (closes R7 before the gate finds it undeclared).

### 4.2 Tests

- `MemorySalienceScorer` unit tests: cap ordering (topN, minSimilarity floor, token truncation each
  independently verified — not just "some cap happened"), cache invalidation on content-hash change
  (simulates a `remember()` overwrite) and on id-removal (simulates `forget()`/`clear()`).
- New RPC round-trip test (Worker handler → client, mirrors existing `GrpcSearchServiceMatchCitationsTest`-style
  test at `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/GrpcSearchServiceMatchCitationsTest.java`
  as the structural template).
- `MemoryContext` injector unit test: empty store → `InjectorResult.empty()`; populated store below
  floor → empty; populated store above floor → formatted block, order matches decreasing score;
  Worker-unavailable → degrades to empty, does not throw (mirrors `QueryRewriteInjector`'s
  AI-down test pattern).
- `AgentPromptComposer` test extension: `memoryContextSupplier` throwing → base prompt unaffected
  (mirrors the existing `conditionContextSupplier` failure-tolerance test, if one exists — check
  `AgentPromptComposerTest` before assuming coverage gaps).
- Per CLAUDE.md's `audit-driven-fixes-need-test` rule: the acceptance eval below is the regression
  test that actually exercises "memory read-injection works," not just that the plumbing compiles.

### 4.3 Acceptance eval (PrefEval-style) + guardrail

**Task shape**: session 1 states a preference/fact via a real chat turn (exercising the existing
write path — either the `core_remember` tool or the passive `MemoryExtractionConsumer` heuristic,
matching how a real user would actually create the memory, not a hand-seeded fixture that bypasses
the producer entirely — `unreachable-seed-green` from `agent-lessons.md` applies here: seed via the
real write path, not a shortcut that wouldn't mirror production). Session 2 (a fresh conversation,
after the store persists across the session boundary) asks a question whose ideal answer depends on
that stated preference/fact. Score: does the session-2 answer reflect the stated preference,
compared against a no-injection baseline (injector disabled via the `memory.injection.enabled`
config knob) run on the identical two-session transcript pair.

**Harness**: follows the existing Inspect-AI-based eval pattern in `scripts/jseval/jseval/`
(`agent_utility_run.py`/`agent_utility_inspect.py` are the closest existing structural precedent —
per-turn agent scoring against a live backend) rather than inventing new eval plumbing. Requires
`ai_activate` (live LLM, not just plumbing — per CLAUDE.md's `use-every-verification-tier` /
`ai-offline-isnt-a-wall` rules, this eval is exactly the "only an end-to-end test with a running
model verifies feature correctness" case) and a running Worker (for the new embed RPC).

**Guardrails, run alongside the acceptance metric, not as an afterthought**:
- **Latency non-inferiority**: p50/p95 turn latency with injection enabled vs. disabled, on the
  SAME question set, must not regress beyond a stated tolerance (proposed: p95 +150ms warm-cache,
  since R3's cold-fill cost is a one-time-per-boot cost, not a per-turn cost).
  Interrogate the result per CLAUDE.md's `interrogate-results` rule — don't just report the delta,
  confirm the cause (warm cache hit vs. a cold-fill accidentally re-triggering every turn would be a
  bug, not a cost, and the number alone wouldn't distinguish them).
- **Context non-inferiority**: on questions where no stored memory is relevant (`minSimilarity`
  floor should reject everything), verify the answer is unchanged vs. baseline — the block must be
  genuinely absent (`InjectorResult.empty()`), not present-but-irrelevant noise diluting the prompt.
- **`minSimilarity` calibration**: run the floor sweep (e.g. 0.3/0.4/0.5/0.6 cosine) against a small
  held-out set of (relevant fact, question) and (irrelevant fact, question) pairs before picking the
  shipped default — per CLAUDE.md's `interrogate-results` rule, don't ship a guessed threshold
  without the sweep that justifies it.

### 4.4 Closure

Per `slice-execution.md`'s independent-reviewer guidance (honor-system, not gate-enforced since the
`independent-review` gate was retired — tempdoc 563): a second agent (≠ implementer) should
static-review the new RPC boundary + the governance-register entry (R7) before this lever is
declared done, given it's genuinely new substrate (a new Head↔Worker capability, not a refactor).

---
number: 848
title: Reasoning persistence and record-driven rendering
status: implementing (slices 0-6 in this PR)
created: 2026-08-19
updated: 2026-08-19
supersedes: tempdoc 835 §9e first bullet ("Reasoning is NOT persisted — deliberate")
charter: B (reasoning/thinking persistence + rendering)
---

# 848 — Reasoning is turn data, not stream decoration

## 0. What this reverses, and why the original decision was unsound

Tempdoc 835 §9e (`docs/tempdocs/835-thinking-mode-design.md:1005-1013`) recorded:

> **Reasoning is NOT persisted to the conversation record — deliberate.** … This **matches the
> shipped window**, which does not even keep reasoning on screen past the turn.

The owner has reversed this. The reversal is not a change of taste — the recorded rationale contains
a factual error and a category error:

1. **Factual error.** "does not even keep reasoning on screen past the turn" was already false at the
   time of writing. `SearchV3View.ts:1383-1384` finalizes the reasoning controller at *every*
   terminal (including a halt) and writes the blocks onto the turn via `setTurnReasoning`
   (`sv3-sessions.ts:376-381`), and `Sv3Main.ts:1164-1171` renders them from the settled turn.
   `NavigateView.ts:135-141` and `SummarizeView.ts:249-253` render completed blocks at the top level
   of their `render()`, unguarded by the streaming flag — those also survive the turn. Only
   `UnifiedChatView` loses them, and only *after* `done`: it renders them throughout the stream and
   drops them at commit, via a render-guard bug (§1.4), not via a decision.
2. **Category error.** §9e inferred a **persistence** decision from an observed **rendering** gap.
   That inversion is the generalizable defect this tempdoc names in §8.

The measured half of §9e's evidence stands and is reused here: B4 measured a turn streaming 445
`reasoning_chunk` frames whose persisted assistant message came back with keys
`[role, content, id, hash, ts]`. That is still true on `main` today (§1.1), and it is the gap this
design closes.

---

## 1. Verified current state

Every claim below was re-read against `main` at `F:\justsearch-public` on 2026-08-19. Line numbers
are from that read.

### 1.1 Backend, answer plane — reasoning is forwarded and dropped

- `modules/app-services/src/main/java/io/justsearch/app/services/conversation/ConversationEngine.java:578-584`
  — the `StreamSink` reasoning callback forwards each chunk to the SSE sink as
  `new SseEvent("reasoning_chunk", Map.of("text", reasoning))` and **accumulates nothing**. Contrast
  the content callback at `:562-577`, which appends to `fullText` (`:553`, returned at `:626`).
- `ConversationEngine.java:846-865` — `persistedAssistant(assistantMsg, mergedDoneEntries)` copies
  exactly three keys off the done payload: `citations`, `calibration`, `claimMatches`. Nothing else.
- `ConversationEngine.java:487-493` — persistence sits **inside** the iteration loop, so a
  `WITHIN_TURN_ITERATION` shape writes one assistant message per iteration. A `ONE_SHOT` ask writes
  once. (Relevant to the shape choice in §3.1.)
- `ConversationEngine.java:432, 461-473` — `usageRef` (an `AtomicReference` out-param) is the
  established pattern for pulling a per-call fact out of `streamLlm`.
- The store is schemaless passthrough:
  `FileConversationStore.appendMessage` (`:192-211`) serializes the caller's map verbatim after
  `enrichMessage` (`:213-226`, adds `id`/`hash`/`ts` only); `computeHash` (`:228+`) hashes
  **role+content only**, so an added field does not perturb existing hashes.
  `loadHistory`/`loadOwnMessages` (`:70-174`) return whatever was written.
  **Consequence: no store schema change is needed, and `GET /api/chat/conversations/{id}/history`
  (`ChatController.java:260-310`) surfaces a new message field for free.**
- **The same schemaless passthrough is a hazard on the LLM-input side** (adversarial review A-1,
  verified): `ConversationEngine.java:324` seeds `initialMessages` from
  `conversationStore.loadEffectiveContext(sessionId)`, which returns the **raw stored maps** —
  `FileConversationStore.java:553-592` filters/trims by id but never projects fields, and its source
  `loadOwnMessages` (`:127-174`) returns each parsed line whole. `buildLlmInput` (`:769-780`) strips
  nothing: `out.addAll(contextMessages)`. `OnlineModeOps.java:732` then does
  `body.put("messages", messages)` straight into the OpenAI-compat request body.
  **Today's re-fed assistant maps therefore already carry `[role, content, id, hash, ts]`** — a
  latent, harmless leak because those keys are inert. `reasoning` would be the **first payload key
  ever re-fed**, it sits in the OpenAI *reasoning* namespace (which this stack tracks explicitly —
  `ServerPropsOps.java:104-107` `supports_preserve_reasoning`), and it is an **array** where a
  server may expect a string. See §2.2b.
- **Blast radius of A-1 is exactly one shape.** `sessionIdFor` (`:794-798`) returns non-null only for
  `PersistenceMode.PERSISTENT`, and only that path seeds `initialMessages` (`:317-325`). Grep for
  `PersistenceMode.PERSISTENT` under `conversation/` yields `FreeChatShape.java:60`,
  `AgentRunShape.java`, and the engine itself — and `AgentRunShape` is `SHAPE_DRIVEN`, dispatched via
  `dispatchShapeDriven` (`:246-260`) to a `ShapeRunner`, never through this loop. **`core.free-chat`
  is the only shape that re-feeds its own persisted assistant messages to the model.**

### 1.2 Backend, thread projection

- `modules/ui/src/main/java/io/justsearch/ui/api/InteractionThreadController.java:246-289` —
  `chatTurn` projects a store message into an `InteractionEvent`, lifting `citations` (`:267`),
  `calibration` (`:268`), `claimMatches` (`:269`) into `attributes` under conditional guards
  (`:271-280`). **This is the exact insertion pattern for `reasoning`.**
- `InteractionThreadController.java:291-301` — `toWireRow` puts `attributes` on the wire as an open
  map. No allow-list.

### 1.3 Backend, agent plane

- `modules/app-agent/src/main/java/io/justsearch/agent/AgentLlmCaller.java:178, 192-195` — a
  `reasoningBuilder` accumulates the full reasoning for the step and emits
  `AgentEvent.ReasoningChunk` per chunk. At `:259-264` the completed builder is used **only for a
  DEBUG log line** and then discarded.
- `AgentLoopService.java:817-837` (`wrapEventConsumer`) → `runStore.appendEvent(sessionId, enriched)`
  at `:833`; `AgentRunStore.appendEvent` (`:292-304`) journals **every** event, so each
  `reasoning_chunk` is its own durable `events.ndjson` record.
- `modules/app-agent/src/main/java/io/justsearch/agent/AgentInteractionMapper.java:47-232` — the
  `switch` has cases for `done`, `tool_call_proposed`, `tool_call_pending`, `tool_exec_started`,
  `tool_exec_completed`, `tool_call_rejected`, `error`, `handoff_executed`, `node_started`,
  `node_completed`, `node_output`, `search_executed`. **No `reasoning_chunk` case** →
  `default -> Optional.empty()` at `:231`. Reasoning never reaches the unified thread.
- `AgentRunQueryService.java:355-357` — the mapper's only production caller, a per-record loop over
  `runStore.readEvents(runId)`. A stateful fold is possible here (§3.3).

### 1.4 Frontend — `UnifiedChatView` (the shipped window)

- `ReasoningController` (`modules/ui-web/src/shell-v0/controllers/ReasoningController.ts`) is the ONE
  reasoning model: `reasoningText` (`:8`), `isThinking` (`:9`), `reasoningBlocks: ReasoningBlock[]`
  (`:10`); `endThinking()` (`:31-44`) pushes `{text, durationMs}`; `finalize()` (`:46-48`) delegates
  to it; `reset()` (`:50-56`) clears everything.
- `UnifiedChatView.ts:708` holds one; `:5917` resets it at `send()`; `:5930-5932` feeds it;
  `:5934` ends thinking on the first content chunk; `:5948` finalizes at `done`.
- **The bug — precisely scoped (corrected by adversarial review A-2).** The only render site is
  `renderStreamingBlock()` (`:5670-5719`), guarded at `:5671` by
  `if (!this.streamingText && !this.isStreaming && !this.reasoning.isThinking) return nothing;`.
  `onDone` clears `streamingText` (`:5994`) and `isStreaming` (`:5995`), and `finalize()` (`:5948`)
  already set `isThinking=false` — so **after `done` all three are falsy and the whole block
  unmounts**. The loss is real, but it is a *post-commit* loss only.

  **The completed-blocks branch at `:5693-5699` is NOT dead code.** `onChunk` (`:5933-5945`) calls
  `endThinking()` at `:5934` on the **first content chunk**, which sets `isThinking=false` and pushes
  the block — and then appends to `streamingText` at `:5944`, keeping the `:5671` guard satisfied.
  So from the first answer token until `done`, `reasoningBlocks.length === 1 && !isThinking` holds
  and `:5693-5699` is exactly what renders the collapsed block **throughout the entire
  answer-streaming phase**. `:5690-5692` covers only the pre-content thinking phase. The two
  branches are a live hand-off, not a redundancy.

  Consequence for the design: `renderMessage` covers post-commit, `:5693-5699` covers mid-stream.
  **Neither is deleted.** (An earlier draft of this tempdoc called `:5693-5699` "structurally
  unreachable" and scheduled it for teardown — that claim was wrong and the teardown would have
  blanked the reasoning block for the whole streaming phase.)
- The committed `ThreadMessage` built at `:5969-5990` copies `durationMs`, `citations`, `sources`,
  `claims`, `coverage`, `sourceCoverage`, `ragMeta`, `standaloneQuestion` — **no reasoning**. The type
  (`views/unifiedChatRequest.ts:33-66`) has no reasoning field.
- The record path: `:5163-5282`. `attributes.live` short-circuits to the live message (`:5170-5171`);
  the agent-record branch is `:5176-5228`; the RAG/chat record branch builds `enriched` at
  `:5254-5280` (record-first for `claims`/`citations`/`coverage`/`sourceCoverage`) and renders through
  `renderMessage` (`:5486-5609`). `renderMessage` emits the shape tag at `:5572` and the answer block
  at `:5582-5596` — **the insertion point for static reasoning blocks is between them.**

### 1.5 Frontend — Search v3

- `Sv3Turn.reasoning: readonly Sv3TurnReasoning[]` already exists
  (`sv3-sessions.ts:84-91` for the type; the field on the turn record).
- `SearchV3View.ts:1383-1384` — at every ask terminal, `askReasoning.finalize()` then
  `setTurnReasoning(this.sessions, ref, [...blocks])`. In-session persistence: real.
- `sv3-sessions.ts:376-381` — `setTurnReasoning`, a no-op for an empty list.
- `sv3-sessions.ts:644-684` — `applySv3Record`. Line **`:668`** already reads
  `reasoning: prior.reasoning.length > 0 ? prior.reasoning : recorded.reasoning`. **The merge rule is
  already correct and needs no change** — it just never receives a non-empty `recorded.reasoning`.
- `sv3-record.ts:166-174` — the record projection seeds `reasoning: []` with a comment stating "The
  record carries none of the four … seeded EMPTY rather than guessed". **This is the one line that
  makes reload lossy.** Note it builds a turn from *multiple* assistant items
  (`:129-132`, `turn.answers.push`), so hydration must collect across them.
- `Sv3Main.ts:1155-1172` — `reasoningBlocks(turn, streaming)` renders live-from-controller while
  streaming, else static-from-turn. Correct and complete.
- `Sv3Main.ts:1111-1116` — **the `agent`-kind branch never calls `reasoningBlocks`.** An agent turn
  renders no reasoning, live or recorded.
- `AgentSessionController.ts:403, 471, 699` constructs a `ReasoningController`; `:770-775` and `:1373`
  feed it; `:1021` finalizes it; `:1550`/`:1662` reset it. **Nothing renders it.** Grep confirms the
  only `.reasoning` readers in `shell-v0` are `sv3-sessions.ts:668`, `Sv3Main.ts:1164-1165`, and each
  view's own controller. Agent-plane reasoning is invisible live *and* on reload.
- The wire is already forward-tolerant: `unifiedThreadClient.ts:66` types attributes as
  `z.record(z.string(), z.unknown())`, `:148-152` passes an unknown object through;
  `unifiedThreadProjection.ts:45, 150, 327` carries `attributes` opaquely. **No client-side schema
  change is needed.**

### 1.6 The rendering component is already fit for purpose

`components/chat/ReasoningBlock.ts` accepts EITHER a live `.controller` OR static `.text` /
`.durationMs` (`:10-14`, `:30-50`), defaults collapsed (`:20`) and re-collapses at stream end
(`:52-58`). **Record-driven rendering needs no component change.**

### 1.7 Non-goal — shapes that suppress thinking (claim corrected, A-8)

`SamplingParams.DETERMINISTIC` (`modules/app-api/.../SamplingParams.java:121`) and `VDU`/`VDU_PROBE`
(`:129`, `:139`) carry `enableThinking=false`, which emits
`chat_template_kwargs={"enable_thinking":false}` (`:24`). Such a turn normally produces no
`reasoning_content`, therefore no `reasoning_chunk` callback, therefore no accumulation and no
persisted key. **The design needs no suppression branch.** But two earlier phrasings were too strong
and are withdrawn:

1. **"by construction" is wrong — it is conditional.** `ConversationEngine.applySchemaConstraint`
   (`:910-932`) falls back to `DETERMINISTIC` **only when `sampling == null`** (`:925`), and
   `parseSamplingParams` (`:885-891`) returns a **non-null** `SamplingParams` whenever the request
   body carries `enableThinking` — in which case the caller's value survives and the schema merely
   adds a `response_format`. Search v3 *does* send it (`sv3-ask.ts:140-150`:
   `quick → {enableThinking:false,…}`, `thorough → {enableThinking:true,…}`). Extract is safe **today**
   only because `UnifiedChatView` happens not to send `enableThinking`. That is a coincidence of the
   current caller set, not a structural guarantee — and the 850 brief lists extract-tier porting,
   which would break it. **A `schema` + `enableThinking:true` request will persist reasoning, and
   that is the correct behaviour** (the caller asked to think). §6.1 test 5 pins this intent
   explicitly so a future reader does not "fix" it.
2. **`enable_thinking:false` does not guarantee no reasoning callback.** On a think-tag-leaking
   build, `OnlineModeOps.java:710-722` wires a `ThinkTagStreamFilter` that reroutes inline
   `<think>…</think>` markup from the content stream into the reasoning sink
   (`ThinkTagStreamFilter.java:32-44`). A model that emits tags despite the kwarg still produces
   `reasoning_chunk` — correctly, since the text really was thinking. The persisted key follows what
   was *received*, never what was *requested*, which is the honest rule.

Mechanical service calls (`QueryRewriteInjector`, `QueryUnderstandingService`,
`FilterNormalizationService`, `ContextSufficiencyService`, `KnowledgeSearchEngine`,
`HierarchicalShapeRunner.java:355`) are unaffected: they do not persist assistant turns at all.

### 1.7b Scope honesty — SHAPE_DRIVEN shapes persist nothing here

`AgentRunShape` and `core.workflow-run` are `SHAPE_DRIVEN`: `dispatchShapeDriven` (`:246-260`) hands
the request to a registered `ShapeRunner` (`WorkflowShapeRunner.java:280` and the agent runner),
which forwards `reasoning_chunk` **live** to the sink and reaches neither `streamLlm` nor
`persistedAssistant`. Their durable reasoning arrives instead via the agent journal fold (§2.4) for
the agent path; **the workflow path persists no reasoning and is out of scope for 848.** Stated
rather than left implicit.

### 1.8 Current-state summary table

| Path | Live render | Persisted | Reload render |
|---|---|---|---|
| UnifiedChatView chat/RAG | yes for the whole stream (thinking phase `:5690-5692`, answer phase `:5693-5699`); unmounts at `done` | no | no |
| Search v3 ask | yes, survives the turn | no | no |
| Search v3 agent | no (controller fed, nothing renders) | no | no |
| UnifiedChatView agent run | no | no | no |
| SummarizeView / NavigateView | yes, survives the turn | n/a (no reload path) | n/a |
| `core.extract` and mechanical calls | none produced | none | none |

---

## 2. Design

### 2.1 Shape of the persisted fact

**Chosen: an ordered array of blocks on the assistant message.**

```jsonc
{ "role": "assistant", "content": "…",
  "reasoning": [ { "text": "…", "durationMs": 1840 } ] }
```

- **Array, not a concatenated string.** Both FE models are already lists
  (`ReasoningController.reasoningBlocks`, `Sv3Turn.reasoning`), and `ReasoningBlock` already renders
  one element per block. A concatenated string would force every consumer to synthesize a fake
  single block and would destroy the per-block duration. This is the "don't mint a second
  representation of an existing concept" rule applied literally: the persisted shape **is** the FE's
  existing shape.
- **Absent when empty.** Conditional put, mirroring `citations`/`calibration`/`claimMatches`
  (`ConversationEngine.java:849-863`). "No key" means "the model did not think", which is the honest
  reading for a `DETERMINISTIC`-preset turn.

**`durationMs` — ONE semantic across both producers (A-5).** An earlier draft let the two planes
define it differently (answer plane: first-reasoning → first-content; agent plane: last-chunk-ts −
first-chunk-ts, which is **0 for a single-chunk block** and measures the wrong interval). Both
producers now use:

> **`durationMs` = from the FIRST reasoning token of the block to the FIRST non-reasoning output that
> follows it** (content token, tool event, or stream end, whichever comes first).

This is exactly what `ReasoningController` measures live (`:24` start on first chunk, `:31-40` stop in
`endThinking`, called from `UnifiedChatView.ts:5934` on the first content chunk), so live and record
describe the same interval. Answer plane: wall-clock inside `streamLlm`. Agent plane: derived from the
journal's record `timestamp`s — first `reasoning_chunk` record's ts to the ts of the **next
non-`reasoning_chunk` record**, not the last reasoning chunk's own ts. A single-chunk block therefore
gets a real duration, not 0.

**Size cap: none — but the earlier justification's baseline was wrong (D-9).** The rejected comparison
was "`claimMatches` is routinely larger". That is true per-message but irrelevant per-conversation:
`claimMatches` is written by `StreamingCitationMatcher`, a consumer of the **EPHEMERAL** RAG shape, so
it lands on thread-keyed records — whereas `reasoning` lands on **every turn of a PERSISTENT
`core.free-chat` conversation**, which is also the one record `loadOwnMessages` (`:133`,
`Files.readAllLines`) full-reads on **every** turn, recursively through branch parents (`:98`).
`FreeChatShape.java:63` confirms the point: its only consumer is `MemoryExtractionConsumer` — no
citation consumer ever writes `claimMatches` to a persistent conversation.

The decision stands (no cap), on a restated basis: the binding cost is the **per-conversation
aggregate and its per-turn re-read**, not per-message size. At 512-3072 reasoning tokens ≈ 2-12 KB
per turn, a 100-turn conversation is ~0.2-1.2 MB re-read per turn — real but well inside what a local
file read absorbs, and §2.2b's LLM-input strip keeps it out of the *prompt*, which is the cost that
would actually matter. **Triggers that would introduce a cap** (either suffices): a conversation's
`messages.jsonl` exceeding a few MB in normal use, or a measurable per-turn latency contribution from
`loadOwnMessages`. A persisted block materially exceeding the shape's declared reasoning budget is a
*different* defect — the budget is not binding at the inference layer, to be fixed at `LlamaServerOps`
(`--reasoning-budget`), never masked by a store-side truncation.

### 2.2 Where it enters persistence (answer plane)

Accumulate in `ConversationEngine.streamLlm`, mirroring `fullText` — **not** in the shape layer.
Reasoning is a property of an LLM call, and `streamLlm` is the one place an LLM call happens; a
shape-layer accumulator would need one copy per shape and would drift.

- Add a `StringBuilder` + start/end timestamps local to `streamLlm` (`:552-557` region).
- The reasoning callback (`:578-584`) appends and stamps the start on the first chunk — the exact
  mirror of `ReasoningController.handleReasoningChunk:20-29`.
- The content callback (`:562-577`) stamps the end on the first content chunk — the exact mirror of
  `UnifiedChatView.ts:5934` calling `endThinking()` in `onChunk`. Stream end is the fallback.
- Surface it via an out-param mirroring `usageOut` (`ConversationEngine.java:548, 432, 461`), the
  method's established idiom. *(Alternative considered: change `streamLlm`'s return type to a record
  `LlmTurnResult(String text, ReasoningTrace trace)`. Cleaner signature, but it is a private method
  with one call site and `usageOut` already sets the precedent; either is defensible. Recorded so the
  implementer may pick the record form if the parameter list offends — it is already 8 wide.)
- Feed it to `persistedAssistant` as an **explicit third argument**, not through `mergedDoneEntries`.
  Rationale: `mergedDoneEntries` is also what `emitDone` ships on the `done` SSE payload
  (`:507, :520, :941+`) — routing reasoning through it would re-send on the wire what already
  streamed chunk-by-chunk, and would oblige every shape's `done` payload vocabulary to declare it.
  The explicit argument keeps "persisted-only" legible, matching the existing comment at `:842-845`
  ("Evidence is on this persisted copy only, never on the LLM-context copy").
- Per-iteration is automatically correct: the accumulator is per-`streamLlm` call, and persistence is
  per-iteration (`:487-493`), so an ITERATING shape's iteration-3 message carries iteration-3's
  block. No cross-iteration bleed.

### 2.2b Strip persistence-only keys at the LLM-input boundary (BLOCKING, A-1)

Without this, the feature ships a bug: on `core.free-chat` turn *N+1*, every earlier assistant turn's
persisted `reasoning` array is re-sent to llama-server inside the OpenAI-compat `messages` body (chain
verified in §1.1). The failure modes are (a) silent prompt-budget inflation proportional to
conversation length, in a shape whose whole point is a long-lived thread; (b) a reasoning-namespace
key the server may interpret (`supports_preserve_reasoning`) or reject on type (array vs string).

**The fix belongs at the boundary, not at the write.** Persisting the fact is right; re-feeding it is
wrong. Add a projection in `ConversationEngine.buildLlmInput` (`:769-780`) that reduces every context
message to the keys the model contract actually defines — `role` + `content` (plus `tool_calls` /
`name` / `tool_call_id` if any path introduces them; none does today) — and drop everything else.

This is a **root-cause fix, not a special case for `reasoning`**: it also removes the pre-existing
`id`/`hash`/`ts` leak (§1.1), which is inert only by luck. Placing it in `buildLlmInput` means the
guarantee holds for every future persisted field — including 847's citations work and the four facts
named in §8.3 — so no later charter has to rediscover this. Any alternative placement (stripping in
`loadEffectiveContext`, or omitting `reasoning` from the persisted map) either breaks the store's
"return what was written" contract or defeats the feature.

**Required test (§6.1 test 6):** after two turns of a PERSISTENT shape, assert the second call's
captured `llmInput` assistant entries have key set exactly `{role, content}`. This is the test that
makes the invariant durable rather than a comment.

### 2.3 History projection and thread attribute

- `/api/chat/conversations/{id}/history` — **zero code change** (§1.1: schemaless passthrough).
- `InteractionThreadController.chatTurn` — one conditional lift, alongside `claimMatches`
  (`:269`, `:277-280`):
  `Object reasoning = msg.get("reasoning"); if (reasoning instanceof List<?> r && !r.isEmpty()) attributes.put("reasoning", reasoning);`

### 2.4 Agent plane — read-time fold, no new event type

The agent journal already holds every `reasoning_chunk` durably (§1.3). Three options were weighed:

| Option | Cost | Verdict |
|---|---|---|
| **A. New `AgentEvent.ReasoningBlock` terminal event per LLM step** | `AgentEvent` sealed hierarchy + `AgentEventPayloads` + `AgentEventTracing` + `AgentEventSseTranslator` + `AgentRunShape.EVENT_SCHEMA` + shape-handler regen + `AgentEventSchemaConformanceTest` / `AgentEventPayloadConformanceTest` + FE dispatch table | **Rejected** for this slice. It writes a second durable representation of data the journal already holds — the fork shape the `operation-surfaces` register exists to prevent. |
| **B. Per-record mapper case emitting one thread event per chunk** | trivial | **Rejected.** 445 chunks → 445 thread events. Absurd, and the projection is per-turn by contract. |
| **C. Stateful fold at projection time** | ~25 lines in `AgentInteractionMapper` + one call-site swap | **Chosen.** |

**Option C, concretely.** Add `AgentInteractionMapper.fromRunEvents(List<Map<String,Object>> records,
String conversationId) -> List<InteractionEvent>`: it walks the records, delegating each to the
existing `fromRunEvent`, while accumulating `reasoning_chunk` runs into `{text, durationMs}` blocks
using the record `timestamp`s `AgentInteractionMapper.parseTs` (`:293-305`) already parses, with the
§2.1 duration semantic. Accumulated blocks attach to the **next** `ASSISTANT_MESSAGE` the walk
produces (the `done` / `node_output` case) under `attributes["reasoning"]`.

`AgentRunQueryService.java:355-357` swaps its per-record loop for one `fromRunEvents` call.
`fromRunEvent` stays public and unchanged for `AgentLifecycleConsistencyTest` and
`AgentInteractionMapperTest`. **`reasoning_chunk` gets an explicit `case … -> Optional.empty()` with
a comment** (rather than falling into `default`) so the vocabulary is legible and a future reader is
told it is folded, not dropped.

**A-3 — attributes must be written by RECONSTRUCTION, not mutation.** `InteractionEvent`'s compact
constructor does `attributes = attributes == null ? Map.of() : Map.copyOf(attributes)`
(`InteractionEvent.java:44-52`) — the map is **immutable**, and the `node_output` case passes literal
`Map.of()` (`AgentInteractionMapper.java:210`). The fold therefore cannot decorate the event the
delegate returned. Specify: build a merged `LinkedHashMap` from `ev.attributes()` plus `reasoning`,
then emit a **new** `InteractionEvent(ev.id(), ev.conversationId(), ev.occurredAt(), ev.kind(),
ev.originator(), ev.content(), merged)`. An implementation that calls `put` on the returned map
throws `UnsupportedOperationException` at runtime and would pass any test that never exercises a run
*with* reasoning — so §6.2 test 7 must assert on a run that has some.

**A-4 — block boundaries key on the LLM step, not on bare contiguity.** Naive "flush on any
non-`reasoning_chunk` record" is unsound because reasoning and text share one stream: `AgentLlmCaller`
(`:186-195`) dispatches `TextChunk` and `ReasoningChunk` from the same `StreamCallbacks`, and on a
think-tag-leaking build `OnlineModeOps` (`:710-722`) wires a `ThinkTagStreamFilter`
(`ThinkTagStreamFilter.java:32-44`) that reroutes inline `<think>` markup into the reasoning sink
mid-stream. A model emitting more than one think region per response — or a chunk straddling a tag
boundary — yields reasoning runs separated by `chunk` records, which naive contiguity would shatter
into several blocks for what is **one** LLM step. The result would differ between build families for
identical model behaviour, which is precisely what 835 §5.3 wired the filter to prevent.

**Rule:** treat `"chunk"` (the journal name for `TextChunk` —
`AgentEventPayloads.java:37`) as *transparent*: a reasoning run separated from the next only by
`chunk` records is the **same** block and coalesces, with the intervening text excluded from the block
text. Any other event type (`tool_exec_*`, `tool_call_*`, `node_*`, `budget_update`, `done`, `error`)
**cuts** the block — those are genuine step boundaries. §6.2 test 7 covers an interleaved
`reasoning, chunk, reasoning` sequence yielding **one** block.

**D-7 — halted and errored runs keep their reasoning.** An earlier draft dropped any run whose
trailing reasoning had no following `ASSISTANT_MESSAGE`. That contradicts the precedent this repo
already set on the ask path: `SearchV3View.settle()` (`:1376-1403`) finalizes and records the blocks
at **all four** terminals — `onDone→complete` (`:1428`), `onRefused` (`:1440`), `onHalted` (`:1445`),
`onFailed` (`:1446`) — with the stated reason that "what the model thought before the reader stopped
it was really produced" (`:1380-1382`). Dropping it on the agent plane would make the two planes
disagree about the same question. **Specify:** at end of walk, attach any unflushed blocks to the
run's terminal event — the `ERROR` event if the run produced one, else the last emitted event of the
run. Only a run with *no* emitted events at all discards them, which is unreachable in practice.

**Delivered asymmetrically — named, not hidden (independent review F1b, 2026-08-19).** D-7 holds for
the AGENT plane, where the journal already recorded the chunks before the run died. It does **not**
hold for the ANSWER plane: `streamLlm` rethrows as `LlmStreamException` (`ConversationEngine.java`
error check, before the region flush), and the caller `emitError`s and returns *before*
`persistedAssistant` — so a failed ask persists no assistant record at all, and its reasoning survives
only in the live window (which does keep it: `SearchV3View.settle()` records blocks at all four
terminals, and `UnifiedChatView` keeps the committed turn's blocks in-session). Closing this would
mean persisting a **partial assistant turn on error** — a change to what a persisted turn *means*,
touching every consumer that reads the record as "a completed answer". That is a turn-semantics
decision, not a reasoning-persistence one, and it is **out of 848's scope**. Both the agent fold's
javadoc and the FE error-branch consumers (§2.6/§2.7) state the limit where a reader meets it, and it
is logged to the observations inbox so it has a home rather than living only here.

**Both windows CONSUME the terminal blocks (review F1a).** The fold writing to the ERROR event is only
half the fix: `UnifiedChatView`'s `error` case and `sv3-record.ts` now read `attributes.reasoning`
through the shared `reasoningBlocksFromRecord` (sv3 reads it off *every* item kind, not just the
assistant arm), so a failed run's thinking renders instead of being written to a reader that does not
exist.

Honest limit (unchanged): a multi-step agent run collapses all its steps' blocks onto one answer
bubble — the record has one bubble per run, and inventing per-step bubbles would be a rendering change
beyond this charter. The blocks stay ordered and individually timed, so nothing is lost but adjacency.

**No new `InteractionEventKind`.** `check-thread-event-kinds` (`scripts/ci/check-thread-event-kinds.mjs`)
stays green without touching `unifiedThreadClient.ts`'s `KNOWN_EVENT_KINDS`. Reasoning is an
*attribute of a turn*, not a thread event — the same call `claimMatches` and `calibration` make.

### 2.5 Frontend — one parser, two windows

Add to `controllers/ReasoningController.ts` (which already owns the `ReasoningBlock` interface at
`:2-5`):

```ts
export function reasoningBlocksFromRecord(value: unknown): ReasoningBlock[]
```

— validating `{text: string, durationMs: number}` per element, dropping malformed entries. **One
parser.** `sv3-record.ts` imports it (it already imports from `controllers/` for `ToolCall`), as does
`UnifiedChatView.ts`. Two independent `typeof x.text === 'string'` walks in two windows is exactly the
drift this repo's register discipline exists to stop.

**A-7 — make `Sv3TurnReasoning` an alias.** `sv3-sessions.ts:83-91` declares `Sv3TurnReasoning`
structurally ("deliberately decoupled … the shape is `controllers/ReasoningController.ts:2-5`"). It is
byte-identical to `ReasoningBlock`. Replace the duplicate declaration with a type alias
(`export type Sv3TurnReasoning = ReasoningBlock;`, type-only import). One line, it keeps the exported
name every consumer uses, it removes a hand-maintained copy of a shape whose *comment already admits*
it is a copy, and it pre-pays the shared-record brick the 850 charter will want. The original
decoupling rationale ("this module stays free of the controller") is satisfied by a `import type` —
no runtime edge is created.

### 2.6 Frontend — `UnifiedChatView` (charter question 4)

**Render from the committed/record message, never by keeping the streaming block mounted.**

1. `views/unifiedChatRequest.ts:33-66` — add `reasoning?: …[]` to `ThreadMessage`. **Declare the
   element type structurally here (D-10b), do not import `ReasoningBlock`.** The module's header
   (`:4`) states it is "Pure (no element/runtime dependencies)"; `ReasoningController.ts` is a runtime
   class module (it calls `window.setInterval` at `:69`), and even a type-only import would sit
   against that stated contract while every other evidence type it names comes from a pure `…types.ts`
   module (`:12-15`). Follow the `sv3-sessions.ts:83-91` precedent instead: a local
   `{ readonly text: string; readonly durationMs: number }`, with a comment naming
   `controllers/ReasoningController.ts:2-5` as the shape's source. Note this file is a **registered
   execution-surfaces consumer** (`evidence-fe-unified-chat-request`,
   `governance/execution-surfaces.v1.json:414-420`) — no re-registration is needed (§5), but the entry
   is the reason to keep its declaration style consistent.
   *(Asymmetry with A-7 is deliberate: `sv3-sessions.ts` may alias because `sv3-record.ts` already
   imports from `controllers/`; `unifiedChatRequest.ts` carries an explicit purity contract that
   `sv3-sessions.ts` does not.)*
2. `UnifiedChatView.ts:5969-5990` (`onDone`) — after the existing `finalize()` at `:5948`, copy:
   `if (this.reasoning.reasoningBlocks.length > 0) msg.reasoning = [...this.reasoning.reasoningBlocks];`
   Same conditional-copy idiom as `citations`/`sources`/`claims` at `:5980-5982`.
3. `UnifiedChatView.ts:5569-5582` (`renderMessage`) — render static blocks between the shape tag
   (`:5572`) and the rewrite note (`:5573`), preserving the position they occupy in
   `renderStreamingBlock` (after the tag, before the answer) so the block does not move as the turn
   settles:
   `${(m.reasoning ?? []).map((b) => html\`<jf-reasoning-block .text=${b.text} .durationMs=${b.durationMs}></jf-reasoning-block>\`)}`
4. `UnifiedChatView.ts:5258-5280` (record branch `enriched`) — record-first, live fallback:
   `reasoning: reasoningBlocksFromRecord(it.attributes.reasoning) ?? base.reasoning`. (Record wins
   when present; on reload `base` has none, in-session the `:5170` live short-circuit means this path
   is effectively reload-only — same structure as `claimsFromRecord` at `:5270`.)
5. `UnifiedChatView.ts:5209-5227` (agent record branch) — render the same static blocks from
   `it.attributes.reasoning`, positioned before `<jf-markdown-block>` at `:5216`.
6. **NO teardown here (corrected, A-2).** `:5693-5699` is **kept**. It is the live renderer for the
   whole answer-streaming phase (§1.4), not dead code: `renderMessage` covers post-commit,
   `:5693-5699` covers mid-stream, `:5690-5692` covers pre-content. Deleting it — as an earlier draft
   of this tempdoc instructed — would blank the reasoning block from the first answer token until
   `done`, trading a reload-time loss for a live regression. The `retire-with-a-sweep` rule does not
   apply because nothing here is orphaned; the render sites are complementary phases of one turn.
   **Implementer: if you find yourself deleting `:5693-5699`, stop — re-read §1.4.**
7. **Add a `data-testid`** to the new static block in `renderMessage` (e.g.
   `data-testid="chat-turn-reasoning"`). `UnifiedChatView` already uses testids for assertion targets
   (29 occurrences, e.g. `:2466`), and §6.3 test 9 needs a selector that distinguishes the committed
   block from the streaming one — `jf-reasoning-block` alone matches both.

### 2.7 Frontend — Search v3 (charter question 3)

1. `sv3-record.ts` — hydrate. Add `reasoning: ReasoningBlock[]` to the `Building` interface
   (`:68-76`), push `...reasoningBlocksFromRecord(item.attributes.reasoning)` in the `assistant` arm
   (`:129-132`) so blocks from *all* of a turn's assistant items accumulate in record order, and
   replace `reasoning: []` at `:171` with `reasoning: turn.reasoning`. Update the `:166-169` comment,
   which will otherwise be a false statement about the record.
2. `sv3-sessions.ts` — **no change.** `:668` already merges correctly.
3. `SearchV3View.ts` — **no change** to the ask path. `:1383-1384` already writes live blocks.
4. `Sv3Main.ts:1111-1116` — **yes, the agent branch gets reasoning in this slice.** Leaving one turn
   kind reasoning-less would rebuild, inside the same window, exactly the live/record asymmetry this
   charter exists to remove. Call `this.reasoningBlocks(turn, …)` immediately before the
   `recordedActivity`/`runBody` choice.

   **Guard, corrected (A-6).** An earlier draft proposed `streaming && turn.kind === 'ask'`. That is
   wrong in both directions:
   - **Redundant** for the stated reason. `SearchV3View.streaming` is written at exactly three sites
     — `:589` (init `false`), `:1359` (ask dispatch), `:1400` (ask terminal) — all on the ask path.
     During an agent run it is already `false`, so `:2137` already yields `null` and the live branch
     already cannot render the ask controller.
   - **Insufficient** for the real hazard. `reasoningBlocks`' live branch keys on the `streaming`
     argument, which `Sv3Main:1096` derives **per turn** from `turn.status === 'streaming'`, and
     `syncRunPresence` (`:1655-1679`) adopts externally-dispatched runs into the list without
     coordinating with the ask path. Two turns in `streaming` status are therefore reachable, and a
     `kind` check does not close it — both could be the same kind.

   **Use a turnId match instead**, mirroring the run-binding already at `Sv3Main:1103`
   (`live?.turnId === turn.id`): the live controller renders only for the turn that actually owns the
   live stream. That is the same identity discipline the run body already applies, so the window gains
   no second rule.

### 2.8 Frontend — the dead `AgentSessionController.reasoning` (sweep)

`AgentSessionController.reasoning` is fed at `:770-775`/`:1373`, finalized at `:1021`, reset at
`:1550`/`:1662`, and read by nothing. Under §2.7(4) an agent turn gains *recorded* reasoning but its
**live** run still shows none. Two honest exits, and residue is not one of them:

- **Chosen: wire it.** `SearchV3View.ts:2137` currently passes
  `.reasoning=${this.streaming ? this.askReasoning : null}`. Extend to hand `Sv3Main` the *agent*
  controller's reasoning while a delegated run is live, so `Sv3Main.reasoningBlocks` streaming arm
  serves both tiers. Small, and it closes the live half of the same asymmetry.
- Rejected: delete the controller. It would have to be re-added the moment agent reasoning is wanted
  live, and the data is already flowing.

This is the smallest slice item and is separable if the live-run wiring proves fiddlier than it
looks; the recorded half (§2.7(4)) stands on its own.

### 2.9 `SummarizeView` / `NavigateView` (charter question 4, scope)

**Out of scope, and correctly so — the brief's premise does not hold for them.** Verified: their
completed-blocks branches (`SummarizeView.ts:249-253`, `NavigateView.ts:135-141`) sit at the top
level of `render()`, guarded only by `reasoningBlocks.length > 0 && !isThinking`, *not* by a
streaming flag. They already survive the turn. Neither view has a history-reload path (they hold a
single streaming answer plus receipts), so there is nothing to rehydrate. **No change, and nothing to
log** — their behaviour is already the target behaviour for a non-persistent surface.

### 2.10 Reload semantics (charter question 5)

Both windows rehydrate from the same durable record:

- `UnifiedChatView` → `refreshUnifiedThread()` (called at `:6000`) → `GET /api/thread/{id}` →
  `chatTurn` attributes → §2.6(4).
- Search v3 → `SearchV3View.ts:784-796` → `fetchUnifiedThread` + `projectSv3RecordTurns` →
  `applySv3Record` → §2.7(1).

**Merge with charter 847 (citations rehydration) — table corrected.** *(847's slice contents are
coordinator-supplied; 847 is in flight in a worktree not visible from this checkout. The 848-side line
numbers are source-verified.)*

| File / region | 848 touches | 847 touches | Conflict |
|---|---|---|---|
| **`UnifiedChatView.ts:5257-5280`** (the `enriched` literal) | §2.6(4) adds `reasoning:` **beside** `claims:` / `citations:` (`:5270-5271`) | **847-S1 rewrites those exact lines** to delegate to its extracted module | **REAL same-hunk conflict.** The one to plan for. |
| `sv3-record.ts:149-175` | `:171` `reasoning: []` | `:158` `evidence: null` | **None in practice** — 13 lines apart, git auto-merges. *(Listed as a conflict in the previous draft; that was over-cautious.)* |
| `sv3-record.ts:68-76` (`Building`) | adds `reasoning` | evidence accumulator | Auto-mergeable. |
| `sv3-sessions.ts:644-684` (`applySv3Record`) | none | rewrites `:658` (847-S3) | None from 848's side — see the regression-net argument below. |
| `ConversationEngine.persistedAssistant:846-865` | new param + one put | **not touched by 847** | None. *(Previous draft listed this; withdrawn.)* |
| `InteractionThreadController.chatTurn:264-280` | adds one `if` | `citations` already lifted at `:271`, unchanged | Adjacent, trivial. |

**Preferred order remains 848-first, on corrected grounds.** The previous draft's reason ("fewer lines
in the shared region") does not survive the table above. The three real arguments:

1. **847-S4 is chained behind charter 846**, so 847 cannot complete before 848 could.
2. **848's §6.3 test 11 pins `applySv3Record:668`** — the very line 847-S3 rewrites. Landing 848 first
   gives 847's refactor a regression net over the reasoning-merge rule it would otherwise be free to
   break silently.
3. **Rebase direction is asymmetric.** One additive field line rebases trivially onto 847's extracted
   module; 847's whole-literal refactor rebasing over 848's added line is the harder direction.

**Rebase instruction if 847-S1 lands first (the expected case — it is already implementing).** Do
**not** apply §2.6(4) as written against `:5270-5271`; those lines will not exist. Instead:
- Locate 847's extracted record→`ThreadMessage` hydration module (the new home of `claimsFromRecord` /
  `matchesFromRecord`) and add the `reasoning` field **inside that module's call site**, alongside how
  it now produces `claims`/`citations`, using `reasoningBlocksFromRecord(it.attributes.reasoning)`.
- The *semantics* are unchanged and non-negotiable: **record-first, live fallback**
  (`?? base.reasoning`). If 847's extraction made the record unconditionally authoritative, follow it
  — that is the same rule.
- Everything else in §2.6 (the `ThreadMessage` field, the `onDone` copy, the `renderMessage` render,
  the agent-record branch, the testid) is untouched by 847 and applies verbatim.
- §2.7 and §2.10's sv3 rows need no rebase; git resolves them.

---

## 3. What this design supersedes or orphans

| Thing | Disposition | Where |
|---|---|---|
| 835 §9e bullet 1 ("Reasoning is NOT persisted — deliberate") | **Superseded.** Amend 835 in place with a pointer to 848; do not delete the measured B4 evidence. | `docs/tempdocs/835-thinking-mode-design.md:1005-1013` |
| `UnifiedChatView` completed-blocks branch | **KEPT — not orphaned.** It is the mid-stream renderer (§1.4 / §2.6(6)); an earlier draft wrongly scheduled it for deletion. | `UnifiedChatView.ts:5693-5699` |
| `Sv3TurnReasoning` duplicate declaration | **Replaced by a type alias** to `ReasoningBlock` (A-7) | `sv3-sessions.ts:83-91` |
| Unprojected LLM-input maps (`id`/`hash`/`ts` leak) | **Closed** by §2.2b's boundary strip — pre-existing, made load-bearing by this change | `ConversationEngine.java:769-780` |
| `sv3-record.ts` "The record carries none of the four" comment | **Amended** — it becomes false for `reasoning` | `sv3-record.ts:166-169` |
| `AgentSessionController.reasoning` dead state | **Wired** (§2.8) | `AgentSessionController.ts:471, 699, 770-775, 1021` |
| `AgentLlmCaller`'s discard-after-DEBUG-log of `reasoningBuilder` | **Left as-is** — Option C folds from the journal, so the builder stays a per-step log aid. Explicitly *not* a second accumulator. | `AgentLlmCaller.java:259-264` |

---

## 4. Slice plan

Each slice is independently green (`./gradlew.bat build -x test` + its module tests).

### Slice 0 — LLM-input boundary strip (BLOCKING prerequisite, §2.2b)
- `ConversationEngine.java:769-780` — project context messages to `{role, content}` in
  `buildLlmInput`.
- Test §6.1(6). **Land this before or with slice 1** — slice 1 without it ships the A-1 bug.

### Slice 1 — Backend answer-plane persistence
- `ConversationEngine.java:552-557` accumulator + timing; `:562-577` end-stamp; `:578-584` append +
  start-stamp; `:541-550` out-param; `:487-493` pass through; `:846-865` `persistedAssistant` third
  arg + conditional put. Duration semantic per §2.1.
- Tests (`modules/app-services`): §6.1.

### Slice 2 — Thread projection
- `InteractionThreadController.java:264-280` — lift `reasoning` into `attributes`.
- Test: `InteractionThreadControllerTest` (registered guard for this file in
  `governance/operation-surfaces.v1.json:305-306`).

### Slice 3 — Agent-plane fold
- `AgentInteractionMapper.java` — explicit `case "reasoning_chunk" -> Optional.empty()`; new
  `fromRunEvents` fold with (a) **event reconstruction**, never `attributes().put()` (A-3);
  (b) `"chunk"`-transparent coalescing (A-4); (c) terminal attachment for halted/errored runs (D-7);
  (d) the §2.1 duration semantic.
- `AgentRunQueryService.java:355-357` — call it.
- Tests: `AgentInteractionMapperTest` (registered guard, `operation-surfaces.v1.json:323-324`).

### Slice 4 — FE parser + `UnifiedChatView`
- `controllers/ReasoningController.ts` — `reasoningBlocksFromRecord`.
- `views/unifiedChatRequest.ts:33-66` — `ThreadMessage.reasoning`, **structurally declared** (D-10b).
- `UnifiedChatView.ts` — `:5969-5990` copy; `:5569-5582` render + testid; `:5258-5280` record-first
  (**see §2.10's rebase instruction if 847-S1 landed first**); `:5209-5227` agent-record render.
  **`:5693-5699` is KEPT** (A-2).

### Slice 5 — FE Search v3
- `sv3-record.ts:68-76, 129-132, 166-174` — hydrate + correct the comment.
- `sv3-sessions.ts:83-91` — `Sv3TurnReasoning` → alias (A-7).
- `Sv3Main.ts:1111-1116` — agent-branch reasoning, **turnId-matched** live guard (A-6).
- `SearchV3View.ts:2137` — live agent-run controller (§2.8).

### Slice 6 — Docs + sweep
- Amend `835-thinking-mode-design.md:1005-1013` with the reversal pointer.
- `git grep -n "reasoning" docs/` for any canonical doc asserting non-persistence.
- Re-grep the FE for "record carries no…" comments touching reasoning.

---

## 5. Gates, regen, and contracts (charter question 2)

**The HTTP JSON surface is not in `contracts/`.** `contracts/registry.v1.json` declares one category,
`wire`, `format: "protobuf"`, `specDir: contracts/wire` — head↔worker gRPC only
(`capabilities/contract_events/health/knowledge/metrics/operation_history/runtime/status/stream.proto`).
**`--gate wire` does not fire for this change.** Neither does the OpenAPI/`RouteResponseSchemas`
path: `RouteResponseSchemas.java:27-35` maps seven routes, none of them a chat/history/thread route.

| Trigger | Action |
|---|---|
| `contracts/**` | **not touched** → `--gate wire` N/A |
| new/changed SSE event in a shape `EVENT_SCHEMA` | **not touched** (`reasoning_chunk` is already declared by every relevant shape — `RAGAskShape.java:46-51` and siblings) → **`gen-shape-handlers.mjs` regen NOT required** |
| `InteractionEventKind` | **not touched** → `check-thread-event-kinds` green unchanged (run it anyway; `shell-v0/views/**` is edited) |
| **three** registered operation surfaces are engaged | `node scripts/governance/run.mjs --gate operation-surface --mode gate`. The entries: `InteractionThreadController` (`operation-surfaces.v1.json:305-306`, guard `test:InteractionThreadControllerTest`), `AgentInteractionMapper` (`:323-324`, guard `test:AgentInteractionMapperTest`), and — added after review — **`agent-read-query-service`** (`:419-426`, path `AgentRunQueryService.java`, guard **`gate:operation-surface`**, `consumesProjection: agent-thread-projection-producer`). Slice 3 edits all three; the third is gate-guarded rather than test-guarded, so the gate must actually be run, not inferred from a green unit test. |
| `governance/execution-surfaces.v1.json` | **no new registration — for a mechanical reason, not a judgment call (review correction).** The gate is an **identifier grep**: `scripts/governance/gates/execution-surface/enforcer.mjs:215` scans for `scan.tsRefPattern ?? 'SearchTrace'`. It is **field-blind** — adding a field to an already-registered file cannot trip it. Both files 848 touches are already registered: `unifiedChatRequest.ts` as `evidence-fe-unified-chat-request` (`:414-420`) and `UnifiedChatView.ts` (`:408-412`), plus `sv3-sessions.ts` as `sv3-turn-evidence` (`:263-268`). *(The previous draft argued "projection not fork", which is true but is not why the gate stays quiet. Say the mechanical reason in the PR body; the design judgment belongs in §8.)* |
| `modules/ui-web/src/**` | full ui-web gate set — authority is the `ui-web-gates` recipe in `governance/consult-register.v1.json` (pushed by the consult hook on edit). Includes `check-run-renderers` (both `UnifiedChatView.ts` and `sv3-record.ts` are registered render sites, `governance/run-renderers.v1.json:21-23`) and, for `shell-v0/views/**`, `check-surface-task-state-retention` + `check-thread-event-kinds`. |
| Java edits | `./gradlew.bat spotlessApply` first (PMD + Spotless are build-failing) |
| new tempdoc number | `node scripts/ci/check-tempdoc-numbers.mjs` — **note: it currently reports a PRE-EXISTING collision on #840** (`840-model-download-restructure.md` vs `840-retire-model-registry-mirror.md` in a sibling worktree), not caused by this work. 848 is free in this checkout; sibling worktrees may hold 846/847 for the adjacent charters. |
| `docs/tempdocs/**`-only edits | ride along with the code PR (`docs-ride-along`), no standalone PR |

---

## 6. Test strategy (charter question 6)

### 6.1 Backend — `modules/app-services`
Home: `SubstrateDrivenEngineTest` (its `ScriptedAi` stubs already implement
`OnlineAiService.stream(StreamRequest, StreamSink)` at `:1262`, `:1566`, `:1604`, `:1647` — extend one
to drive the reasoning callback).

1. **Round-trip.** A `ONE_SHOT` persistent shape whose stub emits reasoning chunks then content →
   the persisted assistant message carries `reasoning` as a one-element list with the concatenated
   text and a non-negative `durationMs`.
2. **Absent when silent.** A stub that emits no reasoning → the persisted message has **no**
   `reasoning` key (not an empty array). Guards the honest-absence contract.
3. **Per-iteration isolation.** A `WITHIN_TURN_ITERATION` shape over two iterations with distinct
   reasoning per call → message *i* carries only iteration *i*'s block. This is the test that would
   catch an accumulator hoisted out of `streamLlm`.
4. **Not on the wire.** The `done` SSE payload carries **no** `reasoning` key — asserts the §2.2
   decision to bypass `mergedDoneEntries`. (Precedent for done-payload assertions:
   `donePayloadCarriesCompletionTokens`, `SubstrateDrivenEngineTest.java:95-112`.)
5. **Non-goal, stated as two tests with explicit intent (A-8).** Assert on the `samplingCalls` capture
   the suite already uses (`:89-92`):
   (a) `schema` present, **no** `enableThinking` in the body → sampling resolves to `DETERMINISTIC`
   (`enableThinking=false`) → no reasoning → no persisted key. *(The extract case today.)*
   (b) `schema` present **and** `enableThinking:true` in the body → the caller's value **survives**
   (`parseSamplingParams:885-891` returns non-null so `applySchemaConstraint:925` does not substitute
   `DETERMINISTIC`) → reasoning **is** produced and **is** persisted.
   Test (b)'s DisplayName must state the intent — *the caller's explicit request to think wins over
   the schema's default; this is behaviour, not a leak* — so a future reader porting extract to the v3
   effort rungs (the 850 brief) does not "fix" it into a suppression branch.
6. **LLM-input boundary (BLOCKING, §2.2b).** Two turns of a PERSISTENT shape with reasoning on turn 1;
   capture the second call's `llmInput` and assert every assistant entry's key set is **exactly**
   `{role, content}`. Also covers the pre-existing `id`/`hash`/`ts` leak. Without this test the A-1
   bug is invisible to the whole suite.

### 6.2 Backend — projection
7. `InteractionThreadControllerTest` — a store message with `reasoning` projects it onto
   `attributes.reasoning`; a message without it yields no key; a malformed (non-list) value is
   dropped, mirroring the `claimMatches` guard shape.
8. `AgentInteractionMapperTest` — `fromRunEvents`, five cases, each pinning one review finding:
   (a) **base** — `[reasoning_chunk, reasoning_chunk, tool_exec_completed, reasoning_chunk, done]`
   → **two** blocks on the `done` `ASSISTANT_MESSAGE`, `durationMs` from record timestamps per §2.1
   (block 1 measured to the `tool_exec_completed` ts, not to its own last chunk);
   (b) **A-3** — the same run must not throw: a run *with* reasoning is mandatory here, because a
   `Map.copyOf` violation only surfaces when something is actually written
   (`InteractionEvent.java:44-52`);
   (c) **A-4 coalescing** — `[reasoning_chunk, chunk, reasoning_chunk, done]` → **one** block, with
   the intervening text excluded from the block's text;
   (d) **D-7** — `[reasoning_chunk, error]` → the blocks land on the `ERROR` event, not dropped;
   (e) **null case** — a run with no reasoning yields no `reasoning` attribute at all.
   Plus: bare `fromRunEvent("reasoning_chunk", …)` still returns `Optional.empty()`.

### 6.3 Frontend — `modules/ui-web` (vitest)
9. `ReasoningController` unit — `reasoningBlocksFromRecord` accepts well-formed input, drops
   malformed elements, returns `[]` for non-arrays.
10. `UnifiedChatView.test.ts` — three legs, the first two being the phase split A-2 established:
    (a) **mid-stream** — after the first content chunk and *before* `done`, a reasoning block is
    rendered (pins `:5693-5699` against a future deletion — the regression the review caught);
    (b) **post-commit** — after `done`, the settled message still shows a reasoning block, asserted on
    the **new `data-testid`** from §2.6(7), not on the `jf-reasoning-block` tag (which matches both
    the streaming and committed blocks and would pass for the wrong reason);
    (c) **reload** — a thread record whose assistant event carries `attributes.reasoning`, with no live
    thread entry, renders the block. Fixture precedent: `UnifiedChatView.test.ts:1997-2005`.
11. `sv3-record.test.ts` — a record with assistant `attributes.reasoning` projects a turn with
    populated `reasoning`; blocks from two assistant items in one turn concatenate in record order.
12. `sv3-sessions` — a cold-load merge (`prior.reasoning` empty) takes the record's blocks; an
    in-session merge keeps the live blocks. **Pins `applySv3Record:668`, which 847-S3 rewrites** —
    this is argument 2 of the 848-first ordering (§2.10), so it is not optional.
13. `SearchV3View.honesty.test.ts` C9 block (`:652-682`) — extend: after the terminal, an
    `applySv3Record` refresh does not blank the turn's reasoning.
14. `Sv3Main` — an `agent`-kind settled turn with `reasoning` renders the block (**selector note,
    D-10a: `sv3-turn-reasoning` is a `data-testid` on `jf-reasoning-block`, `Sv3Main.ts:1160`/`:1167`,
    not an element name** — assert via the testid attribute); and a turn whose id does **not** match
    the live run's `turnId` renders no live-controller block even while another turn is `streaming`
    (the A-6 guard, which a `kind`-based check would not cover).

### 6.4 Live stack — required, not optional
Per `use-every-verification-tier` / `ai-offline-isnt-a-wall`, static + unit green is **not**
sufficient here: the whole feature is "what the model actually emitted survives a reload".

- `ai_activate` (compact chat profile is adequate — this is plumbing/feature-shape, not quality),
  then: ask a thinking-enabled question in the shipped window; confirm the block renders **after**
  the answer settles; **hard-reload**; confirm it renders from the record.
- Repeat in Search v3 with the **Thorough** rung (`{enableThinking:true, maxTokens:3072}`,
  835 §9d table) — the rung most likely to produce multi-KB reasoning.
- One agent run in v3 → confirm the settled turn shows reasoning after a reload.
- One `core.extract` turn → confirm **no** `reasoning` key on the persisted message (§1.7(a) asserted
  live, not only in a unit test).
- **A-1 live check (the one a unit test can only approximate).** Run three `core.free-chat` turns with
  thinking on, then read the request llama-server actually received — via `tail_log` on the inference
  side or `promptTokens` on the `done` payload across turns. Turn 3's prompt must not carry turns 1-2's
  reasoning. A prompt-token curve growing faster than the visible transcript is the symptom.
- Inspect `<dataDir>/conversations/<id>/messages.jsonl` directly for the persisted shape — confirm the
  `reasoning` array is present on the record while absent from the prompt.
- `jseval ui-shot` for the settled + reloaded states (`check-ui-step-coverage` if a new RAIL step is
  added; none is expected here).

### 6.5 Independent review
Presentation-authority work (`slice-execution.md` `ux-audit-closure`) — the reviewer must not be the
implementer, and the reload leg must be *live*-verified, not inferred from a green unit test.

---

## 7. Open questions

1. **Reasoning-only turn.** `UnifiedChatView.ts:5991` gates the commit on
   `if (this.streamingText.trim())`. A turn that produced reasoning but no answer (the 835 §9f "B3"
   empty-answer failure) would commit nothing and lose its reasoning. **Lean: leave the gate alone** —
   B3 is an error condition with its own structural remedy in 835 §9f, and committing an empty
   assistant bubble to show a thinking trace would be a worse surface. Flagging it so the choice is
   deliberate rather than accidental.

   **STALE as written (independent review, 2026-08-19) — the prediction no longer holds end to end.**
   The *live* half is still true: the commit gate drops the in-session message. But the BACKEND now
   persists that turn (a stream that thought and emitted no content flushes its trailing region at
   stream end and writes an assistant record with empty `content` + a `reasoning` array), so on the
   next thread refresh or reload the record path renders it — an empty-content assistant bubble
   carrying a reasoning block. That is arguably the honest surface, and it is certainly not what §7.1
   predicted. **Flagged for the UX audit** rather than patched blind: what an answerless-but-thought
   turn should look like is a presentation decision, and the record now has the data either way.
2. **`durationMs` vantage-point delta.** Both planes now measure the same *interval* (§2.1), but the
   record measures it server-side and the live controller client-side, so they differ by transport.
   Accepted and documented. A cheap alternative — have `refreshUnifiedThread` overwrite the committed
   message's duration from the record — is **not** recommended: the live number is the one the user
   watched tick, and rewriting it mid-session is a worse surprise than a sub-second discrepancy across
   a reload.
3. **`streamLlm` signature.** Out-param (consistent with `usageOut`, 9th parameter) vs a returned
   record (cleaner, one call site). Implementer's call; both stated in §2.2.
4. **Agent multi-step collapse.** All of a run's steps' blocks land on one bubble (§2.4 honest limit).
   If per-step adjacency turns out to matter, the remedy is a `reasoning` variant of `Sv3RunFeedItem`
   in the interleaved activity list — a *rendering* charter, not a persistence one. Not now.
5. **§2.2b's strip is a behaviour change beyond reasoning.** It also stops re-feeding `id`/`hash`/`ts`,
   which have been in every free-chat prompt for as long as the store has stamped them. They are inert
   keys, so no behaviour *should* change — but a model that had been conditioned by their presence
   would now see a marginally different prompt. Called out so a post-merge quality wobble on free-chat
   is attributed correctly rather than hunted elsewhere. Not a reason to defer the strip: shipping
   `reasoning` into the prompt is the larger and less reversible harm.

### 7b. Review provenance

This design was independently adversarially reviewed after the first draft
(**APPROVE-WITH-AMENDMENTS**). The thesis, persisted shape, accumulation point, explicit-third-arg,
read-time fold and 848-first ordering survived unchanged. Four defects and several doc errors were
found, all re-verified against source here before adoption:

| Finding | Severity | Where addressed | First draft was… |
|---|---|---|---|
| A-1 persisted `reasoning` re-fed to llama-server | HIGH | §1.1, **§2.2b**, slice 0, test 6 | silent on it — would have shipped the bug |
| A-2 `:5693-5699` is the mid-stream renderer | HIGH | §1.4, §2.6(6), §3 | **wrong** ("structurally unreachable"); teardown would have caused a live regression |
| A-3 `InteractionEvent.attributes` is immutable | MED-HIGH | §2.4, test 8(b) | under-specified — runtime throw |
| A-6 `turn.kind==='ask'` guard | MED | §2.7(4), test 14 | wrong both ways (redundant *and* insufficient) |
| A-4 contiguity vs think-tag interleaving | doc | §2.4, test 8(c) | unsound heuristic |
| A-5 two duration semantics | doc | §2.1 | inconsistent across planes |
| A-8 "by construction" | doc | §1.7 | overclaimed |
| D-7 halted/errored runs | doc | §2.4, test 8(d) | dropped reasoning, contradicting the ask-path precedent |
| D-9 cap baseline | doc | §2.1 | right conclusion, wrong reason |
| D-10 testid / purity | doc | §2.6(7), §2.5, test 14 | two small errors |
| §2.10 merge table | doc | §2.10 | listed two non-conflicts, **missed the real one** |
| operation-surfaces third entry; execution-surface grep basis | doc | §5 | incomplete / right-answer-wrong-reason |

Recorded rather than silently folded in, because the §8.2 corollary applies to this document too: two
of these were places where the first draft reasoned from what the code *appeared* to do instead of
from what it *does*. `independent-review-required` earned its keep here.

---

## 8. Reach — the principle this instantiates

### 8.1 The principle

> **A fact the run produced belongs on the record; a fact the reader produced belongs to the window.**

This line already exists in the codebase, drawn correctly and explicitly: `sv3-sessions.ts:23-28`
argues that `pinned` and the unread bit "are reader PREFERENCES about a row in THIS window" and must
*not* be persisted to the conversation store, because that would mint a second authority. The same
module, twelve lines later, holds `reasoning` — a fact the **run** produced — in window-local state
only. Reasoning was simply filed on the wrong side of a line the repo had already drawn.

### 8.2 The corollary, which is where the failure actually happened

> **A rendering gap is not evidence for a persistence decision.**

835 §9e reasoned: the shipped window does not show reasoning past the turn ⇒ do not persist it. The
premise was a `UnifiedChatView` render-guard bug (`:5671`) and the conclusion locked a data decision
to it. The inference direction is backwards in general: what a surface currently *draws* is the most
volatile fact in the system, and the cheapest thing to change. Persistence decisions should be made
from **who produced the fact**, never from **who currently displays it**.

### 8.3 Where else this already applies — four live instances, all self-documented

These are not speculative; each is a comment in shipped code stating the gap:

| Fact | Evidence of the gap | Status |
|---|---|---|
| `rag.standaloneQuestion` | `UnifiedChatView.ts:5243-5253` — "delivered LIVE … but is NOT persisted on the assistant record … Wired now so it lights up the day the record carries it" | Same class. Backend one-liner in `persistedAssistant`. |
| per-message `shapeId` | `UnifiedChatView.ts:5260-5265` — "per-message shape is not persisted — the documented backend gap", forcing a reloaded Document-Q&A turn to be mislabelled "Chat" | Same class, **user-visibly wrong today**. |
| per-turn `durationMs` | `unifiedChatRequest.ts:62-65` + `UnifiedChatView.ts:5197-5206` — "never persisted server-side, so a reloaded/record turn simply lacks it"; the record branch approximates it from `ts` deltas | Same class. |
| `modelLabel` | `UnifiedChatView.ts:5198-5201` — "a reloaded past turn may show a since-changed model" | Same class; Search v3 **already got this right** (`SearchV3View.ts:1391-1393` records the model *as of the terminal*), which is the proof the fix is cheap. |

**Do not build the generalized structure now.** There is no "turn-facts envelope" abstraction to
mint — each of these is a one-line conditional put next to `citations`, and AHA says unify only what
shares a reason to change. What this tempdoc contributes is the **name** and the **list**, so the
next charter that touches `persistedAssistant` sweeps rather than adds one more.

### 8.4 Evidence it earns its keep, and its retirement condition

- **Earning its keep:** the count of FE comments of the form "delivered live but NOT persisted /
  the record carries none" should monotonically decrease. Four today (§8.3), three after 848. A
  cheap standing check: `git grep -n "NOT persisted\|record carries no" modules/ui-web/src/shell-v0`.
- **Retirement condition:** retire the principle if a case appears where a run-produced fact
  *should* stay window-local — i.e. where persisting it would be wrong, not merely unbuilt. The
  reader-preference side of the line (`pinned`, unread, sidebar width) does not count; those are
  reader-produced and the principle already excludes them by construction. If no such counter-case
  appears within the next several charters, the principle has hardened into a rule and belongs in a
  gate on `persistedAssistant`'s key set rather than in prose — which is itself the honest signal
  that the prose has done its job.

---

## 9. Live round (2026-08-19) — the legs no unit test spans

Run against **this branch's build** on the shared dev stack (`distFrom` = this worktree; API
`127.0.0.1:56062`, FE `localhost:5173`, data dir `modules/ui-web/.dev-data`). Inference: shared
cuda12 llama-server, `Qwen_Qwen3.5-9B-Q4_K_M.gguf`, launched by **this** stack —
`…/agent-a859c51c95d9e6241/modules/ui/native-bin/llama-server/variants/cuda12/llama-server.exe -m …
--jinja --reasoning-format deepseek --reasoning-budget 512 --host 127.0.0.1 --port 8082 -c 4096
-ngl 99` (PID 8956, sole llama-server on the machine; the launch line was read from the process, not
assumed — the constant-8081 adoption hazard checked rather than trusted).

**Worktree note (environment, not product):** the backend resolves `variantsRoot` from the repo root
at construction, so a worktree with no `modules/ui/native-bin/` reports `RUNTIME_VARIANT_NOT_INSTALLED`
even though the dev-runner resolves the shared exe into `JUSTSEARCH_SERVER_EXE`. Junctioning the
worktree's `native-bin/llama-server` at the main checkout's (gitignored path, zero copy) and
restarting the backend resolves it.

### 9.1 Leg 1 — prompt purity across turns (§2.2b) — **PASS**

Two turns of one PERSISTENT `core.free-chat` conversation (`live-848-purity`), `enableThinking:true`:

| Fact | Turn 1 | Turn 2 |
|---|---|---|
| `reasoning_chunk` frames / chars | 217 / 788 | 244 / 833 |
| `done.promptTokens` | 22 | **52** |
| `done` carries a `reasoning` key | no | no |

Turn 1's reasoning tokenizes (llama-server `/tokenize`, same model) to **217 tokens**; its answer to
7 and turn 2's question to 13. Observed prompt growth is **30 tokens** — the answer + the question +
chat-template markers. Had the persisted `reasoning` been re-fed, turn 2's prompt would have been
~269. The record meanwhile *does* carry it (§9.5), so this is persistence without re-feeding, which
is the whole of §2.2b.

### 9.2 Leg 2 — reload renders from the record — **PASS**

A thinking turn driven through the real composer in the shipped chat window (not the API): the
settled turn rendered exactly one `[data-testid="chat-turn-reasoning"]` block (collapsed, "Thought
for 7s", 2123 chars, `durationMs` 7433) and **zero** streaming blocks — the 835 §9e post-`done` loss,
closed. After a full page reload the live thread came back with `reasoning.length === 0` on the
assistant message (as designed — `loadConversation` rebuilds role/content only) and the transcript
still rendered **1** block with the identical text length and duration: it can only have come from
`attributes.reasoning` via `reasoningBlocksFromRecord`.

*Observed en route:* the window reopens on the **Search** tier after a reload, which renders no
transcript; the block appears once the reader is back on a conversation tier. Tier restore is
out of this charter's scope — logged to the inbox, not fixed here.

### 9.3 Leg 3 — think → text → think — **PASS (via the filter path, not the model's own second block)**

Three prompt attempts to make the model open a *second native* thinking region failed, and the
reason is structural, not luck: `--reasoning-budget 512` is spent inside region 1 every time (the
model's thinking is long), and once spent llama-server routes everything to content. Recorded
honestly rather than retried further.

The multi-region case was then reproduced through the **other** producer this design names — the one
A-4 and F2 were written for: `OnlineModeOps` wires `ThinkTagStreamFilter` unconditionally, so inline
`<think>…</think>` appearing in the CONTENT stream is rerouted into the reasoning sink mid-stream. A
turn whose answer carried literal think tags produced **5 interleaved regions live**, and the
persisted record carried **5 blocks** — each with its own duration:

```
live regions: 5   persisted blocks: 5
[0] 195 chars / 712 ms  "Thinking Process: 1. **Analyze the Req…"   (native region)
[1..4] 32 chars / ~101 ms each  "rechecking the capital of France"      (rerouted <think> regions)
```

Pre-F2 this same turn would have persisted **one** block carrying the concatenated text and only the
first region's duration. This is the F2 claim, measured live on a real stream.

### 9.4 Leg 4 — a halted/errored agent run keeps its thinking (D-7 + F1a) — **PASS in both windows**

An agent run (`live-848-agent-halt`) was driven until it had streamed 89 `reasoning_chunk` frames
(453 chars), then llama-server was killed mid-step — a genuine crash-shaped failure, not a mock. The
run emitted `error` (`LLM_TRANSIENT`, "LLM call failed after retries").

- **Record:** `GET /api/thread/live-848-agent-halt` returns the `ERROR` event carrying
  `attributes.reasoning = [{ chars: 453, durationMs: 2413 }]` — the fold's terminal attachment.
- **Chat window:** renders `[LLM_TRANSIENT] LLM call failed after retries…` **and** one
  `chat-turn-reasoning` block ("Thought for 2s", 453 chars, 2413 ms).
- **Search v3:** the same conversation projects a turn `kind: agent, status: failed` with
  `reasoning.length === 1`, rendering one `sv3-turn-reasoning` block with the same numbers — which
  also live-proves §2.7(4) (an agent-kind turn renders reasoning at all).

### 9.5 Opportunistic — persisted shape and the extract non-goal — **PASS**

`messages.jsonl` for `live-848-purity`, read directly:

```
{"role":"assistant","content":"The capital of France is Paris.",
 "reasoning":[{"text":"Thinking Process:…" (788 chars),"durationMs":3097}], "id":…,"hash":…,"ts":…}
```

Element keys are exactly `["text","durationMs"]`, and the array sits beside the ordinary message
fields — the §2.1 shape, on disk. A `core.extract` turn with a `schema` and no `enableThinking`
(`live-848-extract`) produced **zero** `reasoning_chunk` events and a persisted assistant record whose
keys are `[role, content, id, hash, ts]` — **no** `reasoning` key, and valid schema-shaped JSON
(§1.7(a), live).

### 9.6 What the live round did not cover

The `enableThinking:true`-with-`schema` case (§6.1 test 5b) stayed unit-level; the ask plane's
drop-on-error limit (§2.4) is unchanged and was not exercised live. Teardown after the round:
stack stopped, no `llama-server`/backend survivors, dev ports closed, GPU back to its 478 MiB /
1% baseline.

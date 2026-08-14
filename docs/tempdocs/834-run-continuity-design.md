# 834 — Run continuity: one observation substrate, two execution semantics

```
status: DESIGN — implementation-ready
created: 2026-08-14
updated: 2026-08-14
revision: 3 — rev 1 was returned NEEDS REDESIGN (§1 placement, §7-S1 foundation
  slice); rev 2 redid both and folded in 19 corrections; rev 3 answers probe P1
  (POST-managed SSE is available), reverses the endpoint split on auth grounds,
  scopes the atomic-subscribe fix honestly, and adds the implementation handoff
  (§11). Deltas in §10.
related: 833 (theorization — W1/W2/W3 are the subject of this doc), 436 (the
  universal SSE envelope substrate this design projects from), 577 (§2.14 Root I
  — the agent run as an observed entity), 585 (§D — cursor reconnect +
  StateSnapshot primer), 604 (heartbeat + FE watchdog), 564 (record → JSON
  Schema → TS/Zod wire projection), 561 (agent lifecycle projection)
```

Design for tempdoc 833's W1 (generalize run observation to all conversational shapes),
W2 (live-run enumeration + startup reconciliation) and W3 (StateSnapshot enrichment).

Every `file:line` was verified against the tree this doc was written from.

---

## 0. The thesis

**One shared OBSERVATION substrate; two EXECUTION semantics.**

Shared, for every run of every shape: monotonic sequence, bounded replay, listener
fan-out, observer count, heartbeat, cursor reattach, disconnect detection.

Different, per shape family:

| | agent / workflow (stepped loop) | ask / summarize / dispatch (one-shot pipeline) |
|---|---|---|
| control points | approval / budget / context gates, steer, autonomy | none |
| zero observers | may **PARK** (posture-graded, `AgentSession.java:695-701`) | **keep going and persist** — never park |
| durable ledger | `AgentRunStore` meta + `RunEventStore` events.ndjson | `ConversationStore` messages |
| recovery after restart | resume from checkpoint | re-ask |

The failure to guard against is a unification that flattens column two into column one.
Rev 1 guarded this with a javadoc ("empty by construction") on a uniform `setPark`; that is
the flattening failure wearing a comment. §3.4 makes it structural.

---

## 1. Placement

Rev 1 specified a new hub, registry, sequence authority, ring buffer, subscription handle
and heartbeat in `app-agent-api`. **All six already exist**, two packages over,
production-proven. This section is the inventory rev 1 should have opened with, and the
projection-vs-fork decision that follows.

### 1.1 The substrate that already exists

`modules/app-observability/.../stream/` — slice 436's universal SSE substrate:

| capability rev 1 proposed to build | already exists |
|---|---|
| hub-assigned monotonic seq | `SseStreamChannel.nextEnvelope:100-106` (via `StreamSequenceTracker`) |
| bounded ring, oldest-eviction | `FrameHistoryRingBuffer`, `DEFAULT_CAPACITY = 9000` (`:35`) |
| cursor replay | `SseStreamChannel.framesSince:112-114` |
| fallen-off-cursor detection | `oldestRetainedSeq:120-122` + `SseEnvelopeWriter.attemptResume:131-155` (three miss cases) with a **reset + snapshot** answer |
| listener fan-out, evict-on-throw | `SseStreamChannel.publish:83-91` (`listeners.removeIf`) |
| `Subscription` handle | `SseStreamChannel.Subscription:131-134` |
| resume token | `ResumeTokenCodec` (+ property test) |

`modules/ui/.../SseEnvelopeWriter.java` — the per-connection orchestrator over Javalin's
**managed `SseClient`**: `connected` → `?since=` resume-attempt → snapshot → subscribe →
heartbeat scheduling (`:208-210`) → **`client.onClose` unsubscribe + heartbeat cancel**
(`:212-217`) → `keepAlive()`. Plus `forceSseHeaders:281-287`.

Topology maturity: **18 production `app.sse` routes** — `ResourceApiModule.java:401,404,407,416,420,424,466,476,479,482,485,493,497,502,506,512`, `routes/InfraRoutes.java:43`,
`routes/RuntimeApiRoutes.java:72`. Javalin 6.7.0 (`gradle/libs.versions.toml:34`).

### 1.2 The three genuine mismatches

1. **Subscribe is not atomic.** `SseStreamChannel.subscribe:125-129` only registers; replay
   is a *separate* caller-side `framesSince` call. `SseEnvelopeWriter` documents the
   consequence as a known limitation (`:49-53`). `RunEventHub` has the property and states
   it (`:25-29`): an event reaches a new subscriber *"either via the replay or via the live
   fan-out, never both, never neither."* Load-bearing for runs, not for catalogs: a catalog
   self-corrects at the next snapshot; a run stream that drops a `chunk` yields a
   permanently corrupted answer. Scope of the fix: §1.3.1.
2. **`StreamId` has no run kind.** `StreamId.java:24` pins
   `^(registry|surface|system):[a-z][a-z0-9-]*$`. Needs a `run` alternative; run ids must be
   letter-initial.
3. **Lifetime and cardinality.** The 18 channels are process-lifetime singletons, one per
   catalog. A run channel is per-instance, N-at-a-time, and must be created, bounded and
   destroyed. No `close()`, no registry. New work either way.

### 1.3 Decision: PROJECTION, not fork

**Run observation is built on `SseStreamChannel` + managed `SseClient`. `RunEventHub` is
deleted, not generalized.**

1. **Atomic subscribe — fixed, and honestly scoped.** `SseEnvelopeWriter.attach:181-220` has
   **two** subscribe paths, and only one is fixable without lock inversion:
   - the **resume path** (`?since=` non-blank → `attemptResume` → `framesSince` → `subscribe`),
     which is pure channel state, and
   - the **no-cursor path** (`:198-204`: `sendReset` then
     `sendSnapshot(snapshotExtras.get())` then subscribe), taken by 17 of the 18 catalog
     routes on every fresh connect. Closing *that* race means invoking a **caller-supplied
     supplier under a channel monitor** — lock inversion across 18 controllers, each of which
     may take its own locks inside `snapshotExtras.get()`.

   So the new `subscribeAndReplay(listener, sinceSeq)` closes the race for the **resume path
   only**. The 17 no-cursor catalog connects keep today's documented behaviour, and this doc
   does not claim a fleet-wide fix. Run streams get the property unconditionally because
   §1.6 makes "absent cursor ⇒ replay from 0" a protocol requirement — a run stream never
   takes the no-cursor path.

   **Lock cost, named.** The channel is lock-free today: listeners are
   `ConcurrentHashMap.newKeySet()` (`SseStreamChannel.java:44`) and only
   `FrameHistoryRingBuffer` synchronizes, per method (`:56,61,78,96`). Some streams run at
   ~30 fps. So `subscribeAndReplay` uses a per-channel `ReentrantReadWriteLock`: `publish`
   takes the **read** lock (uncontended read-lock acquisition, once per frame, on top of the
   ring's existing monitor), `subscribeAndReplay` takes the **write** lock once per
   connection and holds it only while snapshotting the frames to replay — never across
   socket writes (§2, two-phase handoff). If P8 shows the read-lock acquire is measurable at
   30 fps, the fallback is a publish-generation counter with a retry loop, which needs no
   lock on the publish path.
2. **Typed `E`.** The journal carries wire-projected frames — the `(name, payload)` pair from
   the single authority `AgentEventPayloads`. Publishing raw `AgentEvent` into a
   Jackson-serialized envelope would bypass it and re-create the three-way drift that class
   exists to prevent (`AgentEventPayloads.java:11-17`). AG-UI therefore gains a map-input
   overload — see §6.5, which does not pretend this is free.
3. **Per-run vs per-catalog.** The only structural difference is lifetime and cardinality
   (§1.2.3), a *registry* concern. A registry around the existing channel is strictly less
   code than one around a clone.

**Consequence: `RunEventHub` is deleted.** `AgentSession` loses `eventHub` (`:74`) and
`eventHub()` (`:671-673`); it keeps `observerCount()` (`:676-678`) re-pointed at an injected
`IntSupplier` and gains an injected `Consumer<AgentEvent>` publish sink. Both JDK types, so
**`app-agent` gains no new module dependency** and stops owning journaling. Full sweep list
in §7-S3b.

### 1.4 Where it lives

**`modules/app-observability`, package `io.justsearch.app.observability.stream.run`.**

Beside `SseStreamChannel`. Dependencies check out: `app-observability` depends on
`app-agent-api` (`:16`) and `app-api` (`:21`) but **not** `app-agent`; both `app-services`
(`:22`) and `ui` (`:70`) already depend on it. Acyclic, and **no new edge at all** because
§1.3 removes app-agent from the journaling path.

`app-agent-api` is rejected: its build file declares it a *"pure annotation jar. No databind,
no core"* (`modules/app-agent-api/build.gradle.kts:9-12`). A thread-owning, clock-holding
registry there is a module-posture change rev 1 costed at zero.

### 1.5 The interface

```java
package io.justsearch.app.observability.stream.run;

public record RunId(String value) { public StreamId streamId(); }   // run:<value>, letter-initial
public record RunDescriptor(String shapeId, String conversationId, long startedAtEpochMs) {}
public record RunChannelPolicy(int maxFrames, long maxBytes, boolean parkable) {}

public final class RunChannelRegistry {
  public RunChannel open(RunId id, RunDescriptor d, RunChannelPolicy policy);
  public Optional<RunChannel> find(RunId id);
  public List<RunChannel> live();
  /** Terminal: refuse publishes, keep the ring readable for `linger`, then drop. */
  public void retire(RunId id, Duration linger);
}

public sealed interface RunChannel permits SteppedRunChannel, OneShotRunChannel {
  RunId id(); RunDescriptor descriptor(); SseStreamChannel channel();
  int observerCount();
  Optional<ParkState> park();                 // OneShotRunChannel: empty, structurally
  Optional<RunStateSnapshot> snapshot();
  SseStreamChannel.Subscription observe(Consumer<SseEnvelope> listener, long sinceSeq);
}
/** Only a stepped run can park. There is no setPark on the one-shot type. */
public non-sealed interface SteppedRunChannel extends RunChannel {
  void setPark(ParkState park);
  void setSnapshotSupplier(Supplier<RunStateSnapshot> supplier);
}
public non-sealed interface OneShotRunChannel extends RunChannel {}

public record ParkState(Kind kind, long sinceEpochMs, String detail) {
  public enum Kind { APPROVAL, BUDGET, CONTEXT, UNOBSERVED }
}
```

### 1.6 Endpoint topology — reversed on auth grounds

**Probe P1 is answered, and it reverses rev 2's decision.**

`io.javalin.http.sse.SseHandler` has a **public** constructor
`SseHandler(Consumer<SseClient>)` and `implements io.javalin.http.Handler` (javap against
the pinned `javalin-6.7.0.jar`). So `app.post(path, new SseHandler(consumer))` yields a fully
managed `SseClient` — with `onClose` — **on a POST route carrying a body**. Rev 2 asserted
managed SSE was GET-only, inferred from all 18 call sites using `app.sse(...)`, and built a
start/observe endpoint split on that inference. The split was therefore a **choice presented
as a constraint**. Re-decided on merits:

**Auth settles it.** `ApiSecurityFilters` enforces the session token only for
`TOKEN_REQUIRED_METHODS = Set.of("POST", "PUT", "DELETE")` (`:44`) and returns early for
`OPTIONS` and `GET` (`:396-402`, comment: *"Always allow OPTIONS (CORS preflight) and GET
(read-only)"*). A GET run-stream family would therefore ship **unauthenticated**: the journal
carries prompts, answers, retrieved passage text and tool arguments, and a GET
`/api/chat/runs/live` would dispense the very runIds needed to fetch them. Loopback-only
(Hard Invariant #2) is not a trust boundary here — the session token exists precisely because
other local processes are not trusted.

Rev 2's three merits for the split, re-weighed:

| merit | survives under POST? |
|---|---|
| uniform attach path | **yes** — creating stream and reattach are both POST managed SSE through one writer |
| runId known before/independent of the stream | **mostly** — becomes an out-of-band `run_started` **lifecycle** frame (sequenced, not retained, no sealed permit — §3.2), not a JSON body |
| `EventSource`-compatible GET | **no — and it was never reachable.** `EventSource` cannot set headers, so a GET stream is either unauthenticated or unusable by `EventSource`. The merit was illusory once auth is designed in. |

The split also *created* a problem the single-route shape does not have: a window in which a
run exists with no observer and no record (rev 2's own blocking item — a reattach to a
retired conversational runId would find nothing, because §4 keeps the journal ephemeral).

**Decision: no split. All streaming is POST managed SSE.**

```
POST /api/chat/runs                       → managed SSE; creates the run AND observes it
POST /api/chat/runs/{runId}/observe       → managed SSE; additional / reattaching observer
GET  /api/chat/runs/live                  → enumeration (§5) — REQUIRES a filter change
```

Two protocol requirements, not passing notes:

- **Absent cursor means replay from 0, never snapshot-only.** `SseEnvelopeWriter.attach:196-204`
  calls `attemptResume` only when the token is non-blank and otherwise sends a snapshot.
  For run streams that would silently discard everything published before the observer
  connected — the exact loss the journal exists to prevent. `RunStreamWriter` always replays
  from `sinceSeq` (default 0). This is also what keeps run streams on the atomic path (§1.3.1).
- **`POST /observe` on an unknown or retired runId** answers **404 with a typed body**
  `{runId, reason: "unknown" | "retired", recordHint}`, not a 200 with an empty stream.
  `recordHint` names where the record lives: for agent runs `"/api/chat/sessions/{id}"`
  (backed by `events.ndjson`), for conversational runs `"/api/chat/conversations/{id}"` (the
  persisted transcript — the journal is ephemeral by §4, so there is no event record and the
  client must be told that rather than shown an empty stream). A 200 + `replay_truncated` is
  rejected: it says "you missed part" when the truth is "this run is over, read the record."

**`GET /api/chat/runs/live` needs an authentication change** and does not ship without it:
add a path-scoped token requirement for `/api/chat/runs/**` to `ApiSecurityFilters`'
`app.before` (`:395-410`), before the method check. The FE can comply — `consumeShapeStream`
already sends `SESSION_TOKEN_HEADER` on a `fetch` (`streams.ts:557-560`) and the enumeration
is a `fetch`, not an `EventSource`. **The filter change is a required S4 item, listed as
such.** The alternative — making the enumeration a POST — is rejected: a read should be a GET,
and the filter needs a path-scoped mechanism eventually regardless.

The wire *vocabulary* is unchanged: `SseEnvelopeWriter` emits envelope frames (constant event
name `"frame"`); run streams keep today's bare `event:`/`data:` frames. A sibling
`RunStreamWriter` reuses the same channel, managed client, heartbeat and `onClose` pattern in
run vocabulary — the relationship `MultiplexedSseWriter` already has to the shared channel.
Migrating runs onto the envelope format is a real option (one SSE family; the FE
re-establishment law at `EnvelopeStream.ts:15-21` for free) deliberately not bundled.

**Cursor grammar — one door, not two.** Envelope streams take `?since=<ResumeTokenCodec token>`
(`SseEnvelopeWriter:194`, `attemptResume:131-140` decodes and validates the stream id). A raw
integer under the same parameter name would be a silent grammar fork. Run streams use
**`?sinceSeq=<long>`** — a different parameter name for a different grammar. And run streams
**do not emit `id:`**, so `Last-Event-ID` cannot become a second, unvalidated resume channel
alongside `?sinceSeq=`. One resume input, one grammar. (This retires rev 1/rev 2's
`Last-Event-ID` story entirely; P7 confirms nothing produces it today.)

Existing POST routes (`/api/chat/ask`, `/api/chat/dispatch`, `/api/chat/agent`,
`/api/chat/agent/{id}/attach`) remain during migration — they are the old journal, which is
why S3 can split (§7).

### 1.7 Execution stays on the initiating thread

With the creating call *being* the first stream (§1.6), `engine.run` stays synchronous on the
Jetty handler thread, exactly as today (`AgentController:182-192`, `ChatController:165`). A
run survives its client disconnecting because the handler thread keeps running and write
failures are absorbed.

This is a direct saving from reversing the split. Rev 2 required an executor move (and
mis-attributed it to S4 when its own staging put the substrate in S3), which dragged in pool
sizing, error propagation to a not-yet-attached client, and re-ordering the 423 locked-store
gate (`ChatController:152-161`). **All of that is now out of scope.** The cost that remains is
one Jetty thread per live run, including while parked — pre-existing and unchanged.

An executor becomes *required* only if "start a run without observing it" (a background ask)
is ever wanted. Named as the trigger, not built.

---

## 2. Memory: bounds that cohere

Rev 1's numbers did not: at an honest ~200 B retained per small frame, `maxFrames = 4000` is
unreachable under 1 MiB (the frame axis becomes decoration), and one `rag.citations` frame
carrying passage text can evict most of a narrative ring, making truncation the norm for RAG.

**Two-tier retention.**

- **Narrative ring** — `chunk` / `reasoning_chunk` / progress. `maxFrames = 4000`,
  `maxBytes = 2 MiB` (≈800 KB at 200 B/frame, so both axes are reachable and bytes bind only
  on unusual narrative).
- **Evidence slot** — large replace-only frames (`rag.meta`, `rag.citations`,
  `rag.citation_matches`, tool results with `structuredData`) in a small **keyed latest-wins
  map**, own 4 MiB budget. These are state, not narrative: a reattacher needs the *latest*
  citations, never their history. Segregation stops one 500 KB frame evicting 2500 narrative
  frames.
- Replay order: evidence slot first in original seq order, then the narrative ring. Cursor
  stays coherent.
- **Agent runs**: `maxFrames = 1000` (today's value, `AgentSession.java:74`),
  `maxBytes = 4 MiB`, same segregation for tool results.

`FrameHistoryRingBuffer` already takes a capacity (`:45`); the byte bound and evidence slot
are new, beside it. The sizer measures *retained* bytes (fixed per-frame overhead + payload
string content), not wire size; P2 supplies the constant.

**Truncation stays honest.** When `sinceSeq < oldestRetainedSeq()`, `RunStreamWriter` emits
`replay_truncated {sinceSeq, oldestRetainedSeq}` then the snapshot then the partial window —
the run-vocabulary form of what `attemptResume:144-150` + reset already do for envelopes.

**Replay backpressure.** `RunEventHub.subscribe:73-82` replays *inside* the monitor while
calling the observer, whose terminal action is a blocking socket write; a slow-but-alive
reattacher replaying 4000 frames stalls the generating thread. Designed out via **two-phase
handoff**: under the write lock, snapshot the frames to replay and register the listener in a
*buffering* state; outside the lock, drain the snapshot to the socket, then drain frames
buffered during the drain, then flip to pass-through (flip under the lock, buffer empty).
Preserves never-both-never-neither, which the per-observer-queue alternative only preserves
with extra care.

**Cardinality.** `AgentSessionRegistry.java:48-51` records the assumption in code: *"For a
desktop deployment with 0–1 concurrent sessions this is irrelevant."* Cap total channels at
**32**; drop retired-and-lingering oldest-first; **never drop a live run** — the 33rd
concurrent live run is refused with a typed error. P6 re-derives whether 32 is generous by
two orders of magnitude.

**Terminal linger is two sites, not "one line each."** `AgentLoopService.java:575` removes
from the registry and `:579` immediately closes the hub, while `attachToRun:195-197` refuses
removed sessions. A 60 s linger moves both and must distinguish *retired-but-readable* from
*gone* (§1.6's 404 reasons). `RunChannelRegistry.retire(id, linger)` owns the whole
transition, which is why it is a registry method.

---

## 3. Identity, vocabulary, and the ask-survival guard

### 3.1 Today

| id | minted by | scope | authority |
|---|---|---|---|
| agent `sessionId` | backend at run start | one agent run | `AgentSessionRegistry.java:31`, run directory, `/api/chat/agent/*` |
| conversation `sessionId` | request body, PERSISTENT shapes | a whole conversation | `ConversationEngine.java:273-282` |
| `conversationId` | **frontend** | a whole conversation | thread write key, `ConversationEngine.java:290,412-415`; `sv3-ask.ts:280` |

"sessionId" means *run* on one side and *conversation* on the other; a conversation holds many
runs. Keying runs by `conversationId` breaks on the second question.

### 3.2 Decision

**One namespace, `RunId`, backend-minted.** Agent runs alias it to the existing `sessionId`
(no mapping table — a table is a second authority that drifts; aliasing preserves every URL
and the run directory name). Conversational runs mint `run-<uuid>`; the prefix also satisfies
`StreamId`'s letter-initial slug rule.

**`runId` is delivered as a `run_started` lifecycle frame, first on every run stream.**
Lifecycle frames are sequenced-but-not-retained — exactly the distinction the substrate
already draws (`SseStreamChannel.publish` appends to the ring only for `UPDATE`, `:80-82`;
`nextEnvelope:100-106` consumes a seq without retaining; `SseEnvelopeWriter` sends every
lifecycle frame through it, `:296-298`). **It is not an `AgentEvent`, so it adds no sealed
permit** and avoids the 7+ site cascade rev 1's version would have caused
(`AgentEventPayloads.name()`/`.base()`, `AgentEventTracing:83-84,139`, `AgUiEventTranslator`,
the two conformance `ALL_VARIANTS` lists and their permit-parity assertions,
`AgentRunShape.eventSchema`, regenerated FE handler types).

### 3.3 `session_started` cannot be retired

It is emitted by the workflow shape too (`WorkflowShapeRunner.java:163`, declared in
`WorkflowRunShape.java:49`, special-cased in its swallow list at `:285`), and it is **pinned
in the durable ledger** — asserted in `AgentRunStoreTest.java:52,383`, read by the history
indexer, and present in every existing `events.ndjson` forever. A persisted vocabulary is not
retirable by a wire change.

**Re-scoped:** the FE stops *depending* on it for run identity (it reads `runId` from
`run_started`). The event keeps being emitted and keeps being read from persisted ledgers —
**dual-read, wire-deprecated, never deleted** — documented as such rather than left as an
unexplained survivor.

### 3.4 The ask-survival guard is structural

`RunChannel` is sealed; `setPark` exists only on `SteppedRunChannel` (§1.5). A one-shot run's
handle has no method to park it — the mistake is a compile error, not a review catch.
`RunChannelPolicy.parkable` selects the subtype at `open`.

Plus a regression test stating the law: **an ask run whose observer count reaches zero
mid-generation still reaches `done` and still persists its assistant message.**

### 3.5 N concurrent runs per conversation

Nothing serializes two dispatches on one `conversationId`. `GET /api/chat/runs/live?conversationId=X`
may legitimately return N > 1. **Contract:** a list, ordered by `startedAtEpochMs` descending,
never collapsed. The backend does not serialize runs — turn-taking is a product decision that
belongs in the FE composer where the user's intent lives, not silently in the run substrate.
The FE seam brief must state that "is this conversation answering?" is `runs.length > 0`, and
that presenting N as one is a rendering choice it owns.

---

## 4. Persistence interplay

**Decision: the journal is ephemeral; persistence is unchanged.**

### 4.1 For ONE_SHOT shapes the durability gap is already closed

For `core.rag-ask` (`ONE_SHOT` × `EPHEMERAL`, `RAGAskShape.java:63-65`), `ConversationEngine`
persists the clean user turn *before* the LLM call (`:329-334`, after injectors at `:318`,
before the loop at `:356`), runs to completion regardless of observers, and persists the
complete assistant message with evidence after `onDone` (`:409-415`). A dropped ask loses
nothing; the gap is observability.

### 4.2 Why journaling to disk would be wrong

A durable shape-agnostic ledger already exists — `RunEventStore` (`:38`), used by the agent
path (`AgentRunStore.java:511-518`) and the workflow shape; durable conversational events
belong *there*, and conflating the two forks the ledger. Cost: ~600 appends per answer, each
through the `StoreCipher` seal when encryption is on (`RunEventStore.java:59-63`), for content
already persisted once, complete. And restoring a half-generated answer presents an
interrupted generation as a complete one.

### 4.3 Iterating shapes get a different answer

The "crash leaves an honest gap" property is **ONE_SHOT-only**. The persistence at
`ConversationEngine.java:409-415` sits *inside* the iteration loop (`:356-438`), so a
`WITHIN_TURN_ITERATION` shape — `ExtractShape.java:63`, `HierarchicalSummarizeShape.java:50` —
persists an assistant turn **per iteration**. A crash at iteration 3 of 8 leaves three
persisted turns reading as a finished answer. The same loop runs
`MemoryExtractionConsumer.onDone` (`:90-91`) every iteration, committing memory writes a crash
cannot roll back.

**Turn-open marker**, one field on the conversation record, set before iteration 0. **Cleared
in a `finally`, not at selected exits.** `dispatchSubstrateDriven` has **eight** exits and rev
2 cleared on two — which would have rendered every cleanly-errored run "interrupted", the
inverse dishonesty:

| exit | line |
|---|---|
| injector terminated | `:319-321` |
| AI unavailable | `:347-350` |
| `LlmStreamException` | `:363-366` |
| consumer `onDone` threw | `:376-380` |
| controller `next` threw | `:421-425` |
| `STOP_SUCCESS` | `:428-431` |
| `STOP_ERROR` | `:432-435` |
| iteration hard cap | `:440-442` |

A `finally` around the dispatch body clears it on all eight; only a process death leaves it
set, which is exactly the condition it encodes. (Per-iteration clearing is the alternative and
is weaker — it would mark a run complete after iteration 1 of 8.)

Not in scope: rolling back mid-run memory writes. Recorded as a known limitation with its
`file:line`, because the honest statement is "memory extraction is not transactional across a
crash", not silence.

---

## 5. W2 — enumeration + startup reconciliation

### 5.1 The endpoint

`GET /api/chat/runs/live`, optional `conversationId` / `shapeId` filters. **Ships only with
the `ApiSecurityFilters` path-scoped token change (§1.6).**

```java
// modules/app-api/src/main/java/io/justsearch/app/api/run/LiveRunsResponse.java
public record LiveRunsResponse(List<LiveRunSummary> runs) {}
public record LiveRunSummary(
    String runId, String shapeId, String conversationId,
    String state, ParkSummary park,
    long startedAtEpochMs, long updatedAtEpochMs,
    int observerCount, RunStateSnapshotView snapshot) {}
public record ParkSummary(String kind, long sinceEpochMs, String detail) {}
public record RunStateSnapshotView(
    Integer iteration, Integer budgetRemaining, Integer toolCallsExecuted,
    Integer messageCount, String activeAgentId,
    List<PendingApprovalView> pendingApprovals, String autonomyLevel, ParkSummary park) {}
public record PendingApprovalView(
    String callId, String toolName, String argumentsJson, String risk, String gateBehavior) {}
```

Typed, not `Map<String,Object>` — the 564 chain retired that fail-open hole
(`WireRecordSchemaGenTest.java:107-109`: *"record-backed so the FE retires its fail-open
`.loose()` hand-Zod"*). `arguments` rides as a JSON string for the same reason.

`app-api` cannot depend back on `app-agent-api` (`AgentSessionController.java:90-92`), so the
records are plain and the controller projects with `MAPPER.convertValue`, as
`handleListSessions` already does (`:93-97`). Wire chain: record →
`WireRecordSchemaGenTest.captureOrVerify` (agent precedent `:110-114`) →
`SSOT/schemas/live-runs-response.v1.json` → `gen-wire-schema-types.mjs` → TS + Zod.

No `interrupted` field: an interrupted run is a *persisted* run and never appears in a live
enumeration. Interruption surfaces on the persisted-sessions record (§5.3).

### 5.2 Startup reconciliation

**The problem.** `AgentRunStore.startRun` writes `resumable: true` (`:155`) and `checkpoint`
recomputes it from state (`:204`, via `isResumableState:516-520`). Nothing runs at Head start
to notice the owning process is gone. `LifecycleState`'s javadoc (`:10-11`) claims *"no
orphan-RUNNING / worker-drain hazard"* — true within a process, false across a restart.

**The rule.** For every persisted run whose state is non-terminal
(`!LifecycleState.parse(state).isTerminal()`) and absent from `RunChannelRegistry`, stamp
`interruptedAt`. Idempotent — skip runs already carrying it.

**No `INTERRUPTED` state; do not clear `resumable`.** `state` is the *resume seed*
(`handleResumeSessionStream` replays from the checkpoint it names); overwriting it destroys
what makes the run resumable in order to record that it is not running — two facts, two
fields. `resumable` is genuinely true for these runs. And a new enum constant is a downgrade
hazard: `LifecycleState.parse` maps unknown values to `READY_FOR_LLM` (`:44-52`).

**Four presentation cases.** `WAITING_BUDGET` and `WAITING_CONTEXT` (`LifecycleState.java:23,30`)
are non-terminal but **outside** `isResumableState` (`:516-520`):

| state at crash | `resumable` | honest presentation |
|---|---|---|
| terminal | false | finished; no marker |
| `READY_FOR_LLM` / `AFTER_TOOL_RESULT` | true | "Interrupted when the app closed. Resume." |
| `WAITING_APPROVAL` | true | "Interrupted while waiting for your approval. Resume." |
| `WAITING_BUDGET` / `WAITING_CONTEXT` | **false** | "Interrupted while waiting for your decision about tokens/context. **Cannot be resumed — start a new run from this transcript**" (the fork endpoint, `AgentRoutes.java:77`) |

The fourth row is a real product gap the reconciliation surfaces: those gates are in-memory
futures and no checkpoint records them. Extending `isResumableState` to cover them needs a
checkpoint that can re-park — a separate, larger change, named as the follow-on.

**Where it runs, and the unlock seam that must be built.** Boot: `HeadAssembly` right after
`AgentRunStore` construction (`:424-429`). But with encryption on and the store locked, reads
return empty and writes refuse (`RunEventStore.java:59-63`), so a boot-only pass is a silent
no-op on exactly the encrypted installs. Rev 1 asserted an unlock-listener seam; it does not
exist and must be built on
`DataKeyManager.addListener(BiConsumer<State,State>)` (`modules/app-services/.../encryption/DataKeyManager.java:91-93`).
Two constraints from source:

- `fire(before, state())` runs inside `synchronized unlock()` (`:126-130`; same for
  `setup:109-121`, `recover:134-138`), so **listeners execute under the key monitor**. A
  directory scan there blocks the key lifecycle.
- Therefore the listener only hands off to a single-thread executor; the scan runs
  off-monitor. It must not throw — `fire` swallows (`:100-105`), so a throw would be invisible
  rather than loud.
- Idempotency (above) makes boot + unlock + re-unlock all safe.

### 5.3 Presenting interruption

`GET /api/chat/sessions` → `AgentSessionSummary` gains `interruptedAt` additively, alongside
the `resumable` the panel already reads (`RetrospectivePanel.ts:187-193,629-632`). The four
rows are derivable from `(state, resumable, interruptedAt)` with no inference.

---

## 6. W3 — StateSnapshot enrichment

### 6.1 Why snapshot-not-ring is the recovery law

The ring evicts oldest. A run parked at an approval gate emits nothing while parked — the loop
blocks in `gate.get(APPROVAL_TIMEOUT_SECONDS, ...)` (`AgentToolDispatcher.java:265-271`) — so a
long run reaches that gate having emitted thousands of frames and the `tool_call_pending`
frame carrying the `callId` can be evicted. A reattacher then sees a stopped run with no gate
to answer.

The snapshot is pushed *before* replay (`AgentSessionRegistry.java:199-209`;
`SseEnvelopeWriter.attach:202-206` for envelopes), so it is the one frame guaranteed to arrive.

**The law: every fact required to *act* on a run lives in the snapshot; the ring carries
narrative only.** Falsifiable — any affordance that disappears after the ring wraps violates it.

Corollary: the snapshot is emitted at *every* subscribe, including the creating observer's.

### 6.2 The record

```java
record StateSnapshot(
    int iteration, int budgetRemaining, int toolCallsExecuted, int messageCount,
    String activeAgentId,
    List<PendingApproval> pendingApprovals,  // NEW — empty list, never null
    String autonomyLevel,                    // NEW — AutonomyLevel.name(), never null
    ParkSnapshot park,                       // NEW — nullable: absent = not parked
    TraceContext trace) implements AgentEvent { ... }

record PendingApproval(String callId, String toolName, Map<String,Object> arguments,
                       String risk, String gateBehavior) {}
record ParkSnapshot(String kind, long sinceEpochMs, String detail) {}
```

Sources: `autonomyLevel` is a session field (`AgentSession.java:127-131`); park derives from
`hasBudgetGate():374`, `hasContextGate():426`, non-empty `approvalGates:35`,
`zeroObserverPolicy():695-701`. `pendingApprovals` needs a change: `approvalGates` is
`Map<String, CompletableFuture<Boolean>>` (`:35`) with no tool detail, but the detail exists
one statement earlier — `AgentToolDispatcher.java:261-263` emits
`ToolCallPendingApproval(call.id(), call.toolName(), call.arguments(), risk, gateBehavior)`
immediately before `createApprovalGate(call.id())` at `:265`. So `createApprovalGate` takes the
detail and the map becomes `Map<String, PendingGate>`; `approve`/`reject` (`:315-331`) and the
two bulk `complete(false)` sweeps (`:182`, `:631-632`) follow mechanically.

### 6.3 Compat — it is not Jackson

Both wire and persistence go through hand-written switches: `AgentEventPayloads.name():33-58`
and `.base():66-...` (StateSnapshot arm `:204-211`), with `AgentRunStore` delegating (`:511-518`).

1. **Adding components does not change the wire until `base()` is edited**, and a missing
   *field* is silent (unlike a missing *variant*). Hence a record-component coverage test —
   with three constraints the naive version gets wrong:
   - **exclude the `trace` component** — `base()` deliberately omits it, `withTrace` appends
     it (`:62-64,222-230`); a blanket assertion fails on every variant;
   - **per-variant, not generic** — `ToolCallProposed` expands `call` into three keys, so
     component-name-to-key is not one-to-one across the sealed set. Scope to flat-mapping
     variants, `StateSnapshot` first;
   - key it off a variant list whose permit-parity is already asserted (§6.5).
2. **`Map.of` rejects nulls and caps at 10 pairs.** The arm already null-guards
   `activeAgentId` (`:210`). Rewrite as a `LinkedHashMap` so `park` can be *absent*.
3. **Old `events.ndjson` lacks the new keys.** Absent `pendingApprovals` = **unknown**, never
   **none**; conflating them renders "no approvals pending" for a run that had one.
4. **Arity-change sites** (positional constructor): `AgentEventTracing.java:83-84`,
   `AgentSessionRegistry.java:203-209`, `AgUiEventTranslator.java:58-64` (whose
   `STATE_SNAPSHOT` map also wants the new fields), `AgUiEventTranslatorConformanceTest.java:61`,
   `AgentEventSchemaConformanceTest.java:56`, `AgentRunStoreTest.java:356`. The convenience
   overload (`AgentEvent.java:443-450`) keeps old-arity callers compiling.
5. Naming: `io.justsearch.agent.api.registry.StateSnapshot` (`StateSnapshot.java:29`) is
   unrelated; the new types nest under `AgentEvent`.

### 6.4 Conversational snapshots

`OneShotRunChannel.snapshot()` is empty. A one-shot pipeline has no fact a user can act on. A
phase label is presentation, needs engine hooks, and would be substrate without a consumer.

### 6.5 The AG-UI equivalence gate — and its honest cost

The map-input `AgUiEventTranslator` overload (§1.3.2) is **a second hand-written switch**, not
a mechanical projection: the typed switch renames fields on the way out (e.g.
`ToolExecutionCompleted.result().message()` → `"content"`, `AgUiEventTranslator.java:54-57`),
so the map-input version re-derives each mapping from the payload keys. That is a real drift
surface, which is exactly what makes the equivalence gate load-bearing rather than ceremonial.

**Gate:** for every variant, `translateFromMap(name(e), base(e))` equals `translate(e)`.

Anchor it on **`AgUiEventTranslatorConformanceTest.ALL_VARIANTS:37-61`** with its
`coversEveryPermit:64-71` assertion — **not** `AgentEventSchemaConformanceTest.ALL_VARIANTS:45-56`,
which rev 2 cited in error: that list is built from null/zero-field variants
(`new AgentEvent.AgentProgress(null, null, 0, 0)`, `new AgentEvent.SessionStarted((String) null)`),
so an equivalence test over it would NPE on `ToolCallProposed`/`ToolExecutionCompleted` or pass
vacuously. The AG-UI list carries real payloads (`new ToolCallRequest("id","tool","args")`,
`OperationResult.success("ok")`).

**Add one traced instance.** Every entry in that list uses the convenience constructor, so all
carry `TraceContext.none()` — the trace/runId half of the projection would be untested. Add at
least one variant constructed with a populated `TraceContext`.

---

## 7. Staging

**S1** snapshot enrichment → **S2** reconciliation + unlock seam → **S3a** substrate hardening
→ **S3b** run substrate + endpoints + hub deletion → **S4** enumeration → **S5** FE sweep.

### S1 — StateSnapshot enrichment (W3) · independent

Three components on `AgentEvent.StateSnapshot`; detail-bearing `approvalGates`; `base()`'s arm
becomes a `LinkedHashMap`; the six arity sites (§6.3.4). Additive on the wire, no substrate
dependency.

*Verification:* unit — per-variant coverage test with the trace exclusion; absent-key
tri-state; existing permit-parity assertions. Live — attach to a run parked at an approval
gate; the snapshot alone must carry enough to render and answer the gate.

### S2 — Reconciliation + the unlock seam (W2, persistence half)

`interruptedAt` on `AgentRunStore` meta and `AgentSessionSummary`; the boot pass; the
`DataKeyManager` listener with off-monitor hand-off; the four presentation cases as data; the
iterating-shape turn-open marker with its `finally` (§4.3).

*Verification:* unit — idempotency; **the adverse precondition** (store locked ⇒ boot pass is a
no-op *and* the unlock listener completes it); the listener must not block the key monitor and
must not throw; the marker is cleared on **all eight** exits (a parameterised test over the
eight, not a spot check). Live — start a run, kill the Head, restart, read the sessions list;
repeat with encryption enabled and locked at boot.

### S3a — Substrate hardening · zero run code, ships under the 18 routes

`subscribeAndReplay(listener, sinceSeq)` with the read/write lock and two-phase handoff
(§1.3.1, §2), scoped to the resume path; the `run` kind on `StreamId`; the byte bound and
evidence slot on the retention layer. No run code, no new endpoints, independently revertable.

Rev 2 refused to split S3, arguing that splitting leaves two journals coexisting. **That
objection does not survive rev 2's own §1.6**, which keeps the existing POST routes during
migration — those *are* the old journal. Two journals coexist during the migration either way;
splitting merely makes the substrate change revertable on its own. The reviewer's position is
adopted.

*Verification:* the existing `SseEnvelopeContractTest`, `FrameHistoryRingBufferTest`,
`ResumeTokenCodecPropertyTest` stay green; new concurrency tests for
never-both-never-neither and for publisher non-stall under a slow subscriber; a benchmark on
the ~30 fps read-lock cost (P8).

### S3b — Run substrate + endpoints + hub deletion

`RunChannelRegistry`, sealed `RunChannel`, `RunChannelPolicy`, `ParkState`; `RunStreamWriter`
over managed `SseClient` via `new SseHandler(...)`; `POST /api/chat/runs` +
`POST /api/chat/runs/{id}/observe` with the §1.6 protocol requirements; `run_started` lifecycle
frame; the map-input `AgUiEventTranslator` overload + equivalence gate (§6.5);
**`ChatController` error paths routed through the sink** — today `sseError:123-130` and the
three catch arms `:170-179` write to `ctx` directly, so under a journal a failing run would
terminate invisibly for every non-creating observer.

**`RunEventHub` deletion sweep** — every site, so it is not discovered mid-implementation:

| site | what changes |
|---|---|
| `RunEventHub.java` | deleted |
| `AgentSession.java:74,671-673,676-678` | field + accessor removed; `observerCount()` → injected `IntSupplier`; publish via injected `Consumer<AgentEvent>` |
| `AgentSessionRegistry.java:185-232` | `attachToRun` (both arities) reimplemented on the channel |
| `AgentLoopService.java:639-645` | the two `attachToRun` overrides |
| `AgentLoopService.java:575,579` | registry remove + hub close → `registry.retire(id, linger)` |
| `AgentService.java:121-131` (app-agent-api) | the two default `attachToRun` methods — **public interface**, so this is a contract change |
| `AgentController.java:478,513` | native + AG-UI attach call sites |
| `AgentSseWriter.java:94-149` | the `writeOrEvict` / `evictIfGone` / `SseObserverGoneException` eviction seam — obsolete once `onClose` owns disconnect |
| `SseWriter.SseWriteOutcome.CLIENT_GONE` | its only eviction consumer disappears; keep the enum (`SseWriterTest:18` pins the serialization-vs-disconnect distinction) but re-document |
| `RunEventHubTest.java` | whole file |
| `AgentControllerSseEvictionTest.java` | whole file (`:25,32`) |
| `AgentLoopServiceTest.java:634-709,760` | attach + eviction cases retargeted at the channel |

*Verification:* unit — the §3.4 ask-survival test; the AG-UI equivalence gate; 404 contracts
for unknown vs retired runIds; absent-cursor ⇒ replay-from-0. Live — two concurrent observers
on one ask; reload mid-answer and rejoin; `onClose` fires on tab close; a run retired past its
linger answers 404 with the right `recordHint`.

### S4 — Enumeration (W2, read half)

`GET /api/chat/runs/live`; the typed records; SSOT schema and generated TS/Zod; **the
`ApiSecurityFilters` path-scoped token change (§1.6) — a required item of this slice, not a
follow-up.**

*Verification:* unit — projection fidelity; N>1 runs on one conversation; **a request without
the token header is rejected** (the adverse precondition for the auth change). Live — an ask
and an agent run both enumerate with correct `observerCount`; closing a tab drops the count.

### S5 — FE sweep

Retire `activeRunPointer.ts` as discovery authority; run identity from `run_started`;
`session_started` dual-read and wire-deprecated, **not deleted** (§3.3); label every remaining
reference.

### Verification topology

With managed `SseClient.onClose`, disconnect detection no longer depends on the run producing
output, so a dev-proxy run is not structurally blind. `docs/observations.md:1777` remains true
about the raw writer and about proxy-delayed close notification, so: **before S3b, direct
topology only; after S3b, tests asserting disconnect-detection *latency* still run direct,
tests asserting that detection *happens* may run through the proxy** — subject to P4.

---

## 8. Non-goals, seams, risks

**Non-goals.** Multi-device sync · durable chunk-level journaling · resuming a conversational
run's generation after a crash · transactional rollback of mid-run memory writes (§4.3, named
limitation) · migrating run streams to the envelope wire format (§1.6, a real option,
unbundled) · moving execution off the handler thread (§1.7, trigger named) · closing the
no-cursor subscribe race for the 17 catalog routes (§1.3.1, scoped out with reasons) · park
semantics for one-shot pipelines · frontend design.

**Frontend seams** (named, not designed).

| authority | change | slice |
|---|---|---|
| `shell-v0/controllers/activeRunPointer.ts` | retired as discovery authority | S5 |
| `shell-v0/controllers/AgentSessionController.ts` (`attachToRun:1714`, `onStateSnapshot:1122`) | enriched snapshot; `runId` from `run_started` | S1/S3b |
| `views/search-v3/SearchV3View.ts` (`reattachLiveRun:791-797`) | reattach an *ask* run from the enumeration | S4/S5 |
| `api/streams.ts` (`consumeShapeStream:545`) | `?sinceSeq=`; `replay_truncated`; 404 `recordHint` handling | S3b |
| `api/generated/schema-types/live-runs-response.ts` | new generated type + Zod | S4 |
| `components/RetrospectivePanel.ts:187-193,629-632` | the four interruption rows | S2 |

**Risks.**

- **R1 — S3a lands under 18 production routes.** Mitigated by splitting it out, by the
  resume-only scope, and by the existing contract tests.
- **R2 — the read-lock on `publish`** adds a per-frame acquire to a lock-free path at ~30 fps
  (P8). Fallback: publish-generation counter with retry, no publish-path lock.
- **R3 — the map-input AG-UI translator is a second hand-written switch** (§6.5). The
  equivalence gate is the whole mitigation; if it is weakened, the drift returns.
- **R4 — a coverage gap, not a broken park.** Rev 1 claimed the zero-observer park was
  substantially unreachable. **Wrong for the dominant case:** an actively-streaming run's
  disconnect evicts within milliseconds (chunk publish → `writeOrEvict` →
  `SseObserverGoneException` → `RunEventHub.deliver:99-106` → park at `AgentStepRunner.java:217`),
  and `AgentLoopServiceTest.java:713-780` tests exactly that — its dead socket throws on the
  first delivery, so **the test passes for precisely the reason it claims. No existing park
  test is unsound; none should be rewritten.** The real gap is narrower: a run blocked
  *silent* — approval gate `get(300s)`, a long tool call, first-token wait — publishes nothing
  and cannot notice a disconnect for the whole window. That is a **missing test**, not a wrong
  one. S3b's `onClose` closes the gap; the test guards it.
- **R5 — reconciliation under encryption.** Getting the off-monitor hand-off wrong is
  invisible precisely on encrypted installs. Test the adverse precondition.
- **R6 — the auth change is load-bearing.** If S4 ships without the `ApiSecurityFilters`
  change, the enumeration leaks runIds unauthenticated. Its adverse-precondition test is what
  keeps this from being a comment.
- **R7 — `LifecycleState.parse` maps unknown to `READY_FOR_LLM`** (`:44-52`). Not triggered
  here; it is why the enum route stays rejected.

**On the heartbeat.** Rev 1 proposed switching `AgentController.withHeartbeat` to
`writeOrEvict`. That is broken three ways: the heartbeat task is **not a hub observer** (it
writes from a scheduler thread, outside `RunEventHub.deliver:96-107`, so its throw never
reaches the code that removes a subscriber and `observerCount()` would not drop);
`scheduleAtFixedRate` **suppresses all subsequent executions** once the task throws, silently
killing the heartbeat; and it contradicts `AgentController.java:126-128`, which deliberately
keeps heartbeats out of the replay buffer. Resolved by §3.2's rule — heartbeats are
sequenced-but-not-retained lifecycle frames, so they never occupy a ring slot even at 15 s
cadence on a parked run — and by making disconnect detection `client.onClose`, not a write
result. There is no heartbeat slice.

---

## 9. Probes

**P1 — is managed SSE POST-capable? ANSWERED: yes.** `SseHandler(Consumer<SseClient>)` is
public and `SseHandler implements Handler` (javap, pinned `javalin-6.7.0.jar`), so
`app.post(path, new SseHandler(c))` is managed with `onClose`. Residual to confirm at
implementation: that `SseHandler.handle` gates only on `Accept: text/event-stream` and applies
no method check.

**P2 — retained bytes and frame counts per answer** (sizes §2). Per effort tier: frame counts,
the retained-heap per-frame constant, the largest single frame (expected `rag.citations`).

**P3 — the silent-run disconnect window** (guards R4). With a run blocked at an approval gate,
time-to-detection under the raw writer (expected: never, until the gate times out) versus
managed `onClose` (expected: container-prompt). The delta is S3b's justification in one number.

**P4 — does `onClose` fire through the Vite dev proxy, and with what delay?** Sizes the §7
topology relaxation; if it never fires, the direct-only rule survives S3b.

**P5 — envelope format for run streams.** Deferred by §1.6, not rejected. `EnvelopeStream`
wraps `EventSource` (`:106,112,138`) — GET-only, and §1.6 shows GET is unauthenticated, so
this is now blocked on the auth design too, not just framing. What this design *does* adopt
from that family is its re-establishment law (`:15-21`).

**P6 — observed concurrent-run maximum.** If it is 2, the 32-channel cap and the byte-budget
machinery are theatre and §2 collapses to a frame count.

**P7 — `Last-Event-ID` producers.** Static answer: none — `consumeShapeStream` sets only
content-type and the session token (`streams.ts:557-560`); `attachToRun` passes no headers
(`AgentSessionController.ts:1727-1731`); `AgentController.parseLastEventId:472` always sees
null. §1.6 closes the door by not emitting `id:` at all. Confirm no non-browser client (the
AG-UI route, `AgentRoutes.java:57`) round-trips it.

**P8 — read-lock cost on `publish` at 30 fps** (R2). Benchmark before/after on the busiest
existing channel; if measurable, take the generation-counter fallback.

---

## 10. Delta

### Rev 2 → rev 3

| # | rev 2 | rev 3 |
|---|---|---|
| 1 | Start/observe split, justified as forced by GET-only managed SSE | **P1 answered** — `SseHandler` is POST-capable. Split re-decided on merits and **reversed**: auth is decisive (GET is token-exempt, `ApiSecurityFilters:44,396-402`), and the `EventSource` merit was unreachable once auth is designed in. All streaming is POST managed SSE |
| 2 | Executor move required; §1.7 mis-attributed it to S4 | **Dropped** — the creating call is the first stream, so execution stays on the handler thread. R2 (error propagation) and the 423 reorder go with it |
| 3 | GET endpoints unauthenticated by omission | **Auth designed in**: streams are POST (covered today); `GET /runs/live` requires a path-scoped `ApiSecurityFilters` change, a required S4 item with an adverse-precondition test |
| 4 | "Atomic subscribe fixes all 18 routes" | **Scoped honestly** to the resume path; the no-cursor path would need caller suppliers under a channel monitor (lock inversion across 18 controllers). Lock cost named (channel is lock-free today: `:44`, ring-only `synchronized` at `:56,61,78,96`) with a no-lock fallback |
| 5 | Retired/unknown runId unspecified; absent-cursor a passing note | **Protocol requirements**: 404 + typed `{reason, recordHint}`; absent cursor ⇒ replay from 0 (which also keeps runs on the atomic path) |
| 6 | Turn-open marker cleared on 2 of 8 exits | **`finally`** — all eight enumerated with line numbers; the 2-of-8 version would have marked every cleanly-errored run interrupted |
| 7 | `?since=` reused for a raw int | **`?sinceSeq=`**, and no `id:` emitted — one resume grammar, one door |
| 8 | Equivalence gate on `AgentEventSchemaConformanceTest` | **`AgUiEventTranslatorConformanceTest:37-61,64-71`** — the cited list has null/zero variants and would NPE or pass vacuously. Plus one traced instance (all entries carry `TraceContext.none()`) |
| 9 | Map-input overload described as free | Named as **a second hand-written switch** (`message()` → `"output"` rename), which is what makes the gate load-bearing |
| 10 | S3 monolithic ("splitting leaves two journals") | **S3a / S3b** — the objection does not survive rev 2's own "existing POST routes remain during migration"; those are the old journal |
| 11 | Hub deletion sweep unlisted | **12-row sweep table** in S3b, incl. the `AgentService:121-131` public-interface contract change |
| 12 | — | §11 implementation handoff for S1 and S2 |

### Rev 1 → rev 2 (retained for the record)

Projection onto `SseStreamChannel` instead of a new hub in `app-agent-api`; managed SSE
adopted; the heartbeat slice shown mechanically broken; R4 corrected from "park is broken" to
"a missing silent-run test"; `run_started` demoted out of the sealed permit set;
`session_started` shown unretirable; `ChatController` error paths routed through the sink;
replay backpressure designed out; §4.3 scoped to ONE_SHOT; memory rebuilt as ring + evidence
slot; coverage test constrained (trace excluded, per-variant, arity sites); unlock seam built
on `DataKeyManager:91-93`; the fourth interruption case; linger ownership; `snapshot` typed;
enumeration reordered after the substrate; sealed `RunChannel`; N-runs-per-conversation.

---

## 11. Implementation handoff — S1 and S2

Line map for a fresh implementer. Verify each anchor before editing; line numbers drift.

### S1 — StateSnapshot enrichment

**Edit, in order:**

1. `modules/app-agent-api/.../AgentEvent.java:435-451` — add `pendingApprovals`,
   `autonomyLevel`, `park` before `trace`; add nested `PendingApproval` and `ParkSnapshot`
   records; keep the 5-arg convenience overload (`:443-450`) delegating with
   `List.of()`, `AutonomyLevel.DEFAULT.name()`, `null`.
2. `modules/app-agent-api/.../AgentEventPayloads.java:204-211` — rewrite the arm as a
   `LinkedHashMap`; omit `park` when null; `pendingApprovals` as a list of maps.
3. `modules/app-agent/.../AgentSession.java:35` — `approvalGates` becomes
   `Map<String, PendingGate>`; add a nested `PendingGate(PendingApproval detail,
   CompletableFuture<Boolean> future)`. Follow through `:182`, `:307-312` (signature takes the
   detail), `:315-331`, `:631-632`. Add `pendingApprovals()` returning the details.
4. `modules/app-agent/.../AgentToolDispatcher.java:261-265` — pass the same five values already
   being emitted at `:262-263` into `createApprovalGate`.
5. Park derivation — a private helper on `AgentSession` over `hasBudgetGate():374`,
   `hasContextGate():426`, non-empty `approvalGates`, `zeroObserverPolicy():695-701`.
6. Arity sites: `AgentEventTracing.java:83-84`, `AgentSessionRegistry.java:203-209`
   (populate from the session), `AgUiEventTranslator.java:58-64` (extend the `STATE_SNAPSHOT`
   map), `AgUiEventTranslatorConformanceTest.java:61`,
   `AgentEventSchemaConformanceTest.java:56`, `AgentRunStoreTest.java:356`.

**New test** — `AgentEventPayloadsCoverageTest`: for `StateSnapshot` (and other flat-mapping
variants), reflect `RecordComponent[]`, **skip `trace`**, assert every remaining component name
appears as a key in `base(instance)`.

**Done when:** `./gradlew.bat :modules:app-agent:test :modules:app-agent-api:test
:modules:app-services:test :modules:ui:test` is green and a live attach to a gate-parked run
shows the three new fields.

### S2 — Reconciliation + unlock seam

**Edit, in order:**

1. `modules/app-agent/.../AgentRunStore.java` — `markInterrupted(sessionId)` writing
   `interruptedAt` via the existing `readMeta`/`writeMeta` pair (pattern at `:195-210`); add
   `interruptedAt` to `toSessionSummary:466-478`.
2. `modules/app-api/.../agent/AgentSessionSummary.java` — add the field; re-run
   `WireRecordSchemaGenTest` to regenerate `SSOT/schemas/agent-sessions-response.v1.json`;
   then `node scripts/codegen/gen-wire-schema-types.mjs`.
3. New `AgentRunReconciler` (app-agent) — over `listSessions`, select
   `!LifecycleState.parse(state).isTerminal() && interruptedAt == null`, call
   `markInterrupted`. Pure and idempotent; no registry dependency at this slice (nothing is
   live at boot, and S3b can add the liveness check later).
4. `modules/app-services/.../HeadAssembly.java:424-429` — run it once after `AgentRunStore`
   construction; register a `DataKeyManager.addListener` (`:91-93`) whose body **only**
   submits to a single-thread executor and **cannot throw** (`fire` swallows, `:100-105`).
5. Turn-open marker — `ConversationEngine.dispatchSubstrateDriven`: set before the loop
   (`:356`), clear in a `finally` around the body; the eight exits are tabulated in §4.3.

**New tests:** idempotency (run twice, one stamp); **locked-store adverse precondition** (boot
pass is a no-op, unlock listener completes it); the listener does not block the key monitor
and does not throw; a parameterised test driving **all eight** engine exits and asserting the
marker is clear in every one.

**Done when:** the above are green, and a live kill-restart cycle shows `interruptedAt` on the
sessions list — repeated with encryption enabled and the store locked at boot.

---

## 12. Implementation log — S1 and S2

Landed 2026-08-14 on branch `run-continuity-s1-s2`, based on `origin/main` at `781b1a53`. Scope was
§11's S1 and S2 only; **nothing from S3a/S3b/S4/S5 is in this branch** — no `RunChannelRegistry`, no
`RunStreamWriter`, no endpoint changes, no `RunEventHub` deletion, no `ApiSecurityFilters` change.

### 12.1 S1 — as built

| §11 item | landed | note |
|---|---|---|
| 1. `AgentEvent.StateSnapshot` + nested records | `AgentEvent.java:435-506` | 9 components; `PendingApproval` / `ParkSnapshot` nested but **not** implementing `AgentEvent`, so `getPermittedSubclasses().length` stays 22 and `AgentEventSealedTest:134` needed no bump |
| 2. `base()` arm as `LinkedHashMap` | `AgentEventPayloads.java:204-222` | plus `approvalMap` / `parkMap` helpers at `:226-247` |
| 3. `approvalGates` → `Map<String, PendingGate>` | `AgentSession.java:36-46,192,318-390` | `pendingApprovals()` at `:352`; the two bulk sweeps at `:192` and `:700` follow |
| 4. dispatcher passes the detail | `AgentToolDispatcher.java:265-276` | the same five values the `ToolCallPendingApproval` at `:261-263` already carries |
| 5. park derivation | `AgentSession.parkSnapshot():363` | budget → context → approval → zero-observer, in §11's order |
| 6. arity sites | see 12.2 | |
| new coverage test | `AgentEventPayloadsCoverageTest.java` | 4 tests |

### 12.2 Anchors that had drifted, and one design/handoff conflict

Per §11's own instruction to verify each anchor:

- **`hasBudgetGate()` / `hasContextGate()` do not exist.** The real methods are
  `AgentSession.budgetGateHeld():432` and `contextGateHeld():484`. Used those.
- **`AgentEventSchemaConformanceTest.java:53`** (§11 said `:56`) needed **no edit**: §11 item 1 also
  mandates keeping the 5-arg convenience overload, which is what that list uses, so it compiles
  unchanged. Same for `AgentRunStoreTest.java:356`, which switches on type only. Two of the six
  named arity sites were therefore no-ops — recorded rather than silently skipped.
- **A 6-arg trace-carrying overload was also required.** `AgentEventTracing` and the payload
  conformance test both construct `StateSnapshot(…, TraceContext)`; §11 named only the 5-arg one.
- **`PendingApproval.arguments` is `String`, not `Map<String,Object>`.** §6.2's sketch says
  `Map<String,Object>`, but it is contradicted by the two places that pin the actual value: §6.2's
  own source citation (`call.arguments()`, a `String`) and §11 item 4 ("the same five values already
  being emitted"), plus §5.1's `PendingApprovalView.argumentsJson`. Resolved in favour of the
  source-verified `String`; a `Map` would have required parsing JSON the emitter never parses.
- **`sinceEpochMs` needed a source.** Nothing recorded when a park began, so `PendingGate` carries a
  creation stamp and the budget/context gates each got a `…SinceEpochMs` field. The zero-observer
  park is derived from an observer *count*, not a transition, so it honestly reports `0` ("start
  unknown") rather than inventing `now()`.

### 12.3 Two chains §11 did not mention but the build enforces

Both are real gates, discovered by running them, not by reading:

1. **`AgentRunShape`'s `state_snapshot` `EventDescriptor`** (`AgentRunShape.java:168-186`).
   `AgentEventPayloadConformanceTest.everyEmittedFieldIsDeclared` fails on any emitted-but-undeclared
   key. The three new fields are declared `.asOptional()` — `pendingApprovals` and `autonomyLevel`
   **not** because the producer elides them (it always writes them) but because a legacy
   `events.ndjson` record predates them, which is precisely what makes §6.3.3's absent-means-unknown
   representable on the generated FE type (`pendingApprovals?: PendingApproval[]`).
   Chain run: `-Dupdate.shapes.fixture=true` → `scripts/codegen/shapes.fixture.json` →
   `node scripts/codegen/gen-shape-handlers.mjs` → `core-agent-run.ts`, plus the two hand-written
   leaf interfaces in `shape-handlers/shared.ts`. `check-shape-handler-regen` passes.
2. **`AgentSessionSummary` → JSON Schema → TS/Zod** for S2's `interruptedAt`. The generated Zod is a
   `z.strictObject`, so shipping the field on the wire *without* the regen would have made the FE
   reject every sessions response. Chain run: `:modules:app-api:updateSchemas` →
   `SSOT/schemas/agent-sessions-response.v1.json` → `node scripts/codegen/gen-wire-schema-types.mjs`.

### 12.4 S2 — as built

| §11 item | landed |
|---|---|
| 1. `markInterrupted` + summary field | `AgentRunStore.java:387-421`, `toSessionSummary:512-514` |
| 2. `AgentSessionSummary.interruptedAt` + regen | `AgentSessionSummary.java:24-29`; schema + TS/Zod regenerated |
| 3. `AgentRunReconciler` | new file, `modules/app-agent/.../AgentRunReconciler.java` |
| 4. boot pass + unlock listener | `HeadAssembly.java:498-508`, via `UnlockDeferredScan` |
| 5. turn-open marker with its `finally` | `ConversationEngine.java:256-280` (wrapper + finally), `:377-389` (open), `ConversationStore.setTurnOpen/isTurnOpen`, `FileConversationStore.java:435-462` |
| four presentation cases as data | new `InterruptedRunPresentation` (app-api) |

Two deliberate shapes:

- **`UnlockDeferredScan`** (`app-services/.../encryption/`) exists as a named class rather than an
  inline lambda in `HeadAssembly` because its two properties are only testable if there is something
  to hold. `UnlockDeferredScanTest` asserts that `unlock()` **returns while the scan is still
  running** (a latch proves the key monitor was released — an inline scan would have deadlocked the
  test) and that a **throwing scan** breaks neither `unlock()` nor the *next* scan. Both matter
  because `DataKeyManager.fire` runs listeners inside `synchronized unlock()` and swallows their
  throws, so getting this wrong is invisible exactly where it hurts.
- **The turn-open marker is scoped to iterating shapes.** §4.1 says a ONE_SHOT ask's crash gap is
  already closed (it persists once, complete), and §4.3 scopes the marker to
  `WITHIN_TURN_ITERATION`. `onlyIteratingShapesOpenTheMarker` pins both halves.

One test was rewritten mid-implementation after it failed for a reason that invalidated it: a
"process death" falsifier that threw an `Error` inside the dispatch body. A `finally` runs on a
throw too, so that scenario proved nothing about a killed process. Replaced with
`theMarkerIsSetDuringGeneration`, which observes the marker from inside `StreamConsumer.onDone` — the
moment of maximum exposure — and is the honest falsifier for the eight-exit test's "always cleared".

### 12.5 Verification

- `./gradlew.bat spotlessApply` then `build -x test -PskipWebBuild=true` — **BUILD SUCCESSFUL**.
- `:modules:{app-agent-api,app-agent,app-api,app-services,ui}:test` — **all green**, including the
  four named conformance tests (`AgentEventSealedTest`, `AgentEventPayloadConformanceTest`,
  `AgentEventSchemaConformanceTest`, `AgUiEventTranslatorConformanceTest`) and
  `AgentWireProjectionTest`.
- FE untouched by hand, but proven: `npm run typecheck` clean; `npm run test:unit:run` —
  **421 files / 5140 tests passed**.
- `node scripts/ci/check-shape-handler-regen.mjs` — passes.

**Mutation probe on the new coverage test.** Deleted `snapshot.put("autonomyLevel", …)` from
`AgentEventPayloads.base()`. `AgentEventPayloadsCoverageTest` went **2 failed / 4** —
`everyComponentIsCarried` (the reflective assertion, `:89`) and `unknownIsNotNone` (`:149`). It fails
on the reflective assertion, i.e. for the right reason: a component with no payload key. Restored;
green again. Note that `AgentEventSchemaConformanceTest` and `AgentEventPayloadConformanceTest`
**stayed green** under the mutation — they pin names and declared-superset, so a *dropped* field is
invisible to them. That is the gap §6.3.1 named and this test closes.

### 12.6 Deferred — NOT done in this branch

Both of §11's "done when" clauses have a live leg that needs a running stack; no dev stack was used:

- **S1 live leg** — attach to a run parked at an approval gate and confirm the snapshot alone
  carries enough to render and answer the gate. **PENDING.**
- **S2 live leg** — start a run, kill the Head, restart, read the sessions list; then repeat with
  encryption enabled and the store locked at boot. **PENDING.** The locked-store branch is covered
  at unit tier by `AgentRunReconcilerTest.lockedStoreIsANoOpUntilUnlock`, which is the adverse
  precondition, but that is not the same as a real kill-restart.

Also untouched by design (S5's job): no FE consumer reads the three new snapshot fields or
`interruptedAt` yet — `RetrospectivePanel`'s four interruption rows are §8's S2 row but sit on the FE
seam, and the generated types are the substrate they will bind to.

### 12.7 Critical-analysis pass — two findings, both fixed

Run after the two commits, walking the diff with "what would catch what the tests missed?":

1. **Asymmetric guard on the turn-open marker.** The clearing side (in the `finally`) caught
   `RuntimeException`; the opening side did not. `FileConversationStore.writeMetaAtomic` throws
   `UncheckedIOException`, and sealing throws `KeyLockedException` on a locked store — so a store
   fault would have killed the *run* over a diagnostic marker. Guarded symmetrically
   (`ConversationEngine.java:383-395`), and `openTurnKey` is armed only after the write succeeds, so
   the `finally` never chases a mark that was never made (meta.json is written atomically, so there
   is no partial state to clean up).
2. **`UnlockDeferredScan.awaitQuiescence` was dead code in main.** `UnreferencedCodeTest` (app-launcher
   ArchUnit) failed: the method was referenced only from its test. Fixed at the root rather than
   suppressed — `close()` now drains through it before `shutdownNow()`, so a scan already writing
   finishes instead of being interrupted. Better behaviour AND a real main-code reference.

Also re-checked, no change needed: `AgentRunReconciler` catches `RuntimeException` around
`listSessions`, which covers `CorruptDurableStoreException` (it extends `IllegalStateException`) and
the upcaster's `UnsupportedOperationException` — so one corrupt run directory degrades the pass to a
logged no-op instead of breaking Head boot. `createApprovalGate` has exactly one call site
(`AgentToolDispatcher:268`) and every `approvalGates` access is inside `AgentSession`, so the
`Map<String, PendingGate>` change has no missed consumer.

Full unit suite (`./gradlew.bat test -PskipWebBuild=true`): **BUILD SUCCESSFUL**.

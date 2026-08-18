---
title: Streaming producer wedge — bounded await, lock scope, read deadline
status: implemented
created: 2026-08-18
updated: 2026-08-18
supersedes_note: closes the "still unfixed and still unexplained" item 836 §9.9 / IMPL.8 left open
---

# 843 — The streaming producer wedge

## 1. What this is

836 §9.9 recorded a hard blocker: every `core.summarize` + `body.selection` dispatch emitted
`rag.citations` and then **stalled** — no `chunk`, no `done`, for 90–180 s, across three runs on two
stack instances. 836 declared it out of scope and IMPL.8 left it "still unfixed and still
unexplained". This tempdoc resolves it: a live reproduction attempt (§2), a thread dump that pins the
mechanism (§3), and the structural fix (§4).

The inherited diagnosis (static, re-verified against `main` this week) was a process-wide one-shot
wedge:

1. ALL streaming chats run on one `Executors.newSingleThreadExecutor` thread, `VDU-Background`
   (`OnlineModeOps.java:74-80`, submitted at `:767`).
2. The process-global `onlineRequestLock` (`:73`) was held from `:597` to `:764` — across the entire
   body read, **including every `onChunk`/`onReasoning` callback** (`:708`, `:716`), i.e. across SSE
   writes and citation matching.
3. `ConversationEngine.java:531,564` awaited a `CountDownLatch` with **no timeout**.

So one stream task that never returns parks the only LLM thread forever; every later dispatch runs
its injectors, emits `rag.citations`, and then queues — which is exactly the observed presentation.

Two candidates were open for *why* run 1 parked: **A** — the body read never terminates because the
budget-0-era response carried no content deltas; **B** — a cross-thread SSE write parks
(`SseWriter.java:103-122`, called from `VDU-Background`).

## 2. Phase 1 — live reproduction: NOT REPRODUCIBLE (both budgets)

Stack: this branch's dist (`distFrom`), own lease, pinned API port 57943, `clean: none`, the
822-window dev-data (883 indexed docs), `Qwen_Qwen3.5-9B-Q4_K_M` on the shared cuda12 runtime,
`llama-server` build b8571. Request: 836 §9.8.1's exact probe — `POST /api/chat/dispatch`,
`shapeId: core.summarize`, `body.selection` text-range over chars **[15000, 17500)** of
`docs/explanation/18-adapters-lucene-deep-dive.md` (22,867 bytes).

**Run 1 — current default (`--reasoning-budget 512`, post-#464):**

```
    52ms  rag.citations #1
   519ms  reasoning_chunk #1
  8872ms  chunk #1
  9125ms  rag.citation_delta #1
 13793ms  rag.citation_matches #1 | done #1
 13795ms  BODY END
 counts: rag.citations 1 | reasoning_chunk 512 | chunk 288 | rag.citation_delta 2
         rag.citation_matches 1 | done 1
```

**Run 2 — the original configuration restaged (`--reasoning-budget 0`, verified on the
`llama-server` command line):**

```
    97ms  rag.citations #1
   715ms  chunk #1
  2652ms  rag.citation_delta #1
  5471ms  rag.citation_matches #1 | done #1
  5475ms  BODY END
 counts: rag.citations 1 | chunk 277 | rag.citation_delta 1 | rag.citation_matches 1 | done 1
```

**Verdict: NOT REPRODUCIBLE.** Candidate A's premise — that a budget-0 response carries no content
deltas and never terminates — does not hold on b8571 with this model: budget 0 simply skips the
reasoning channel and answers immediately. Candidate B did not appear either. The stall 836 observed
was therefore a *trigger* that this environment no longer produces; the structure that turned that
trigger into a 90–180 s process-wide stall is unchanged and is what §4 fixes.

## 3. The wedge mechanism IS confirmed live

Three dispatches fired concurrently against the same stack (all three emit their injector frame
immediately, then wait their turn):

```
par1  63ms rag.citations · 238ms first chunk ·  6200ms BODY END
par2  55ms rag.citations · 6173ms first chunk · 13784ms BODY END
par3  57ms rag.citations · 13015ms first chunk · 23281ms BODY END
```

Each stream's first content chunk arrives only as the previous one ends: streaming is strictly
serialized, and a queued dispatch has already emitted `rag.citations` before it starts waiting —
"emits `rag.citations` then stalls" is what queueing looks like from the client.

`jcmd <headPid> Thread.print` taken during that window (excerpts):

```
"VDU-Background" #97 daemon ... waiting on condition
   java.lang.Thread.State: WAITING (parking)
        at java.util.concurrent.ArrayBlockingQueue.take(...)
        at jdk.internal.net.http.ResponseSubscribers$HttpResponseInputStream.read(...)
        at java.io.BufferedReader.readLine(...)
        at java.util.Iterator.forEachRemaining(...)
        at io.justsearch.app.inference.OnlineModeOps.lambda$streamChatWithTools$2(OnlineModeOps.java:671)

"JettyServerThreadPool-57" / "-68" / "-73"  (three of them)
   java.lang.Thread.State: WAITING (parking)
        at java.util.concurrent.CountDownLatch.await(CountDownLatch.java:230)
        at io.justsearch.app.services.conversation.ConversationEngine.streamLlm(ConversationEngine.java:564)
```

One reader thread inside the body read (holding `onlineRequestLock`), three request threads parked on
an unbounded latch. The only difference between this healthy picture and the 836 stall is whether the
body read ever returns — and nothing bounded it.

## 4. Phase 2 — the fix

Scoped to what this incident proves; each piece is independent of A-vs-B.

### 4.1 Bounded, idle-based await (`ConversationEngine.streamLlm`)

`latch.await()` → a poll loop bounded by `llmStallDeadline` (3 min, package-private for tests) that
measures **idle time**, not total time: a producing stream keeps its turn for as long as it needs, but
a producer that never calls back at all — the wedge shape, where this dispatch is only queued behind a
parked thread — ends the turn with `LlmStreamException("LLM stream stalled…", "LLM_TIMEOUT")`. The
existing catch at `:414-417` emits the `error` SSE through the sink and returns, so the request thread
is released. An `abandoned` flag makes late `chunk`/`reasoning_chunk` callbacks from the orphaned
producer no-ops, so nothing writes to the sink after the error event.

3 minutes is deliberately longer than the transport's own read deadline (§4.3, 2 min), so a stalled
*body* is reported by the transport and this stays a backstop. Honest limit: a dispatch legitimately
queued behind >3 minutes of other streams also errors — which is the better of the two failures.

### 4.2 The lock no longer spans callback dispatch (`OnlineModeOps`)

What `onlineRequestLock` protects (from its own comment and the vision path's `tryLock` deadline at
`:228`): **one llama-server exchange at a time**, chat with priority over VDU. It is *not* a guard on
consumer state. Releasing it before the body read would break that invariant — the read IS the
exchange — so the fix takes the callbacks out of the locked section instead:
`StreamCallbackPump` queues every `onChunk`/`onReasoning`/`onUsage`/`onToolCallDelta` and runs them,
in order, on a separate thread while the read loop keeps draining. The lock now covers exactly the
llama-server exchange, so a blocked consumer (SSE write to a gone client, citation scoring) can no
longer hold the one lock every chat, VDU and stream request needs.

Failure semantics are preserved, not swallowed: a callback that throws stops further dispatch and
records the throwable; the read loop rethrows it on its next line, and the terminal decision folds in
any failure discovered during the final drain. `streamChat_cancellationRoutesToOnError` (a consumer
throwing `CancellationException` must reach `onError`, never `onComplete`) still passes unchanged.

The terminal callback fires only after `awaitDrain()`, so no chunk can arrive after `done`/`error`.

### 4.3 A read deadline on the body loop (`OnlineModeOps`)

`HttpRequest.timeout` (`HTTP_TIMEOUT`, 2 min) bounds only response-header arrival; the body read had
no deadline of its own — the defect that let candidate A be a candidate at all. Both stream methods
now consume the body through `consumeStreamBody`, which reads via `BodyHandlers.ofInputStream()` under
a `StreamIdleWatchdog`: touched on every line, and on `streamIdleDeadline` (2 min, package-private for
tests) of silence it closes the response body, unblocking the reader. An abandoned read reports
`StreamStalledException` rather than being mistaken for a clean end — a stalled stream can no longer
be reported as a lenient-mode success.

With this in place the trigger class is closed by construction: whatever makes a body stop producing
(empty-content, no terminating chunk, a silent server), the stream task always returns, so the shared
executor and the lock always free up.

### 4.4 Tests

| Test | Proves |
|---|---|
| `streamChat_stalledBodyFiresStreamStalledException` | a server that answers 200, writes one chunk and then never writes or closes ends as `StreamStalledException` on the read deadline instead of parking |
| `streamChat_stalledStreamDoesNotWedgeTheNextDispatch` | the wedge regression: after a non-returning stream, the *next* dispatch on the same single-thread executor and the same lock still runs and streams normally |
| `streamChat_slowCallbackDoesNotHoldTheOnlineRequestLock` | a consumer callback blocked mid-stream no longer blocks `chatCompletion`, which needs the same lock |
| `stalledProducerEndsTheTurnLoudly` (app-services) | a producer that never calls back ends the turn with an `LLM_TIMEOUT` error event and no `done`, and the dispatch returns |

### 4.5 Post-fix live verification

Same stack, rebuilt from this branch, same probe. Three concurrent dispatches and a single dispatch
all streamed to completion with every frame kind and the same ordering as before the change:

```
single   rag.citations 1 | reasoning_chunk 512 | chunk 291 | rag.citation_delta 3
         rag.citation_matches 1 | done 1                                (19.0 s)
par1/2/3 all reached done, each with reasoning + content + citation frames
```

**Throughput was not comparable in this environment and no performance claim is made from it**:
`nvidia-smi` showed a game (`League of Legends.exe`) holding the GPU alongside `llama-server` and
the Worker's embedding backfill (queue ~840) during the post-fix runs, and two identical post-fix
dispatches measured 19 s and 90 s. The verification here is functional — frames, ordering, terminal
event — not a benchmark.

## 5. Residuals (named, not fixed here)

- **Streaming is still one thread for the whole process.** `vduExecutor` is
  `newSingleThreadExecutor`, so concurrent chats still queue (§3's 6 s / 13 s waits are the normal
  case, not a bug being hidden). Moving streaming off a single executor is a larger redesign; every
  wait is now bounded, which is what this incident proves is needed.
- **A consumer blocked forever still parks the streaming thread** — the terminal drain waits for it
  (in order), so the next stream still queues. The client no longer waits: §4.1's bound frees the
  request thread and reports the error.
- **`QueryRewriteInjector.java:92`** calls `chatCompletion` on `core.rag-ask` and takes the same lock
  from the injector phase. Not worsened by this change — the lock is now held for strictly less time —
  but the latent cross-request shape stands.
- The stall's original *trigger* remains unexplained (§2): it did not reproduce on b8571 at either
  reasoning budget. If it returns, it now surfaces as a `StreamStalledException` /
  `LLM_TIMEOUT` error with a timestamp instead of a silent 90–180 s stall.

## 6. Teardown

Stack stopped via the dev tool: `portsClosed: true`, `devRunnerOk: true`; verified afterwards —
`quick_health` `running: false`, no `llama-server.exe`, ports 57943 / 5173 / 8081 all closed.

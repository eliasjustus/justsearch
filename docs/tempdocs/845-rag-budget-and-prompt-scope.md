---
tempdoc: 845
title: Honest RAG context budgeting (live window, real reserve) + retrieval prompt scoping
status: IMPLEMENTING
created: 2026-08-18
updated: 2026-08-18
supersedes: none
related: 835 (thinking mode / shared completion ceiling), 836 (citation verification, S2S3 coverage honesty)
---

# 845 — Two probe-confirmed RAG defects

Both defects were probe-confirmed live before this slice opened; the diagnosis was handed over
pinned. This document records the primary-source evidence chain, the arithmetic decision, and the
implementation log. It is **not** a re-derivation of the diagnosis.

## Defect 1 — the context budgeter overcommits ~2x (causes the Thorough 400)

### The two call sites

Both in `modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/RAGContext.java`:

| Site | Method | What the budget does there |
|---|---|---|
| `:251` | `inject` | Truncates the **assembled** context string after retrieval (`TokenEstimation.truncateIfNeeded`) |
| `:348` | `tryOpenRetrieval` | Passed as `maxContextTokens` **into** `RetrieveContextParams`, so the Worker returns fewer passages |

Both called `TokenEstimation.computeSafeInputBudgetTokens(8192, 1024)` — two hardcoded lies.

The third retrieval path, `tryRetrieveContext` (`:333`), deliberately passes `maxContextTokens=0`
(scoped path keeps its char-budget behaviour, tempdoc 610 §J.3). Out of scope, unchanged.

### Lie 1 — the window is not 8192

`InferenceConfig` defaults `contextSize` to **4096** (`InferenceConfig.java:569`, javadoc `:30`,
env doc `:96`), and the live value flows from `rc.ai().contextSize()` (`InferenceConfig.java:131`)
into the `-c` launch argument (`LlamaServerOps.java:279`).

Hardcoding `4096` would repeat the same mistake in a smaller font. The **authoritative live**
source already exists:

```java
/** Returns the last observed llama-server context size (n_ctx), or null if unknown. */
public Integer lastKnownContextTokens() {          // InferenceLifecycleManager.java:1101
  return runner.view().lastKnownContextTokens();
}

/** Returns the configured llama-server context size (-c), even if actual differs. */
public int configuredContextTokens() {             // InferenceLifecycleManager.java:1106
  return config.contextSize();
}
```

This is a genuine tri-state: `lastKnownContextTokens()` is observed from `/props` and is **null
until a server has been started or adopted**. Per slice-execution discipline (don't conflate
"unknown" with "healthy"), unknown falls back to the **configured** window — never to 8192.

### Lie 2 — the reserve ignores the request's own maxTokens

The engine parses `maxTokens` from the body, default **1024**:

```java
private static int parseMaxTokens(Map<String, Object> body) {   // ConversationEngine.java:869-875
  Object raw = body.get("maxTokens");
  if (raw instanceof Number n) { int v = n.intValue(); return v > 0 ? v : DEFAULT_MAX_TOKENS; }
  return DEFAULT_MAX_TOKENS;
}
```

Thorough sends `maxTokens: 3072`. Reserving a flat 1024 under-reserves by 2048 tokens.

### The arithmetic decision — reasoning is spent WITHIN the completion budget

This is the load-bearing judgment, so it carries two independent primary sources.

**Source 1 — tempdoc 835 §, line 420 (design evidence, code-anchored):**

> **Reasoning tokens and answer tokens share one ceiling.** The engine parses `maxTokens` from the
> body with a default of 1024 (`ConversationEngine.java:65`, `:771-778`) and passes it as the
> request's `max_tokens`; llama-server spends it on reasoning first and answer second.

**Source 2 — 835 probe B3 (empirical, 4/4 reproduction, lines 735-745):** with `maxTokens`
unset (engine default 1024) and `--reasoning-budget -1`, every rep recorded **1024
`reasoning_chunk` frames, 0 `chunk` frames, 0 answer chars, totalTokens 1072 = 48 prompt + 1024
completion**. Reasoning consumed the entire completion budget and the answer got nothing. If
reasoning were additive to `max_tokens`, completion would have exceeded 1024.

**Source 3 — shipped code, independent of the tempdoc** (`ResolvedConfigBuilder.java:86-92`):

> The conversation engine's default completion ceiling (`ConversationEngine.DEFAULT_MAX_TOKENS`),
> **which reasoning and answer tokens share.**

**Therefore: reserve = effective `maxTokens` alone.** `maxTokens + reasoningBudget` would
double-count — the reasoning budget is a *sub-allocation* of `max_tokens`, not a sibling of it.
`--reasoning-budget` (`LlamaServerOps.java:242-254`) caps how much of that shared ceiling
reasoning may take; it never raises the ceiling.

### Lie 3 (found while implementing) — the MIN_BUDGET floor can itself overflow the window

```java
public static int computeSafeInputBudgetTokens(int nCtx, int outputMaxTokens) {  // TokenEstimation.java:101
  int ctx = Math.max(MIN_CONTEXT, nCtx);
  int out = Math.max(0, outputMaxTokens);
  double raw = (ctx - out - FULL_COVERAGE_OVERHEAD_TOKENS - FULL_COVERAGE_SAFETY_TOKENS) * 0.9;
  return (int) Math.floor(Math.max(MIN_BUDGET, raw));   // MIN_BUDGET = 256
}
```

`Math.max(MIN_BUDGET, raw)` applies the 256-token floor **after** the headroom subtraction, so
when `out` approaches `ctx` the function returns 256 regardless of whether 256 tokens fit. At
`(4096, 4000)` headroom is negative and it still returns 256 — input 256 + reserve 4000 = 4256 >
4096. Fixing the two call sites without fixing this would leave the same overflow reachable from a
large-`maxTokens` request. The floor is now clamped by the real headroom.

Old constants, for the record: `(8192, 1024)` → `(8192-1024-512)*0.9 = 5990`. Against a real 4096
window with a 3072 reserve, the honest budget is `(4096-3072-512)*0.9 = 460`. The old call
overcommitted by ~13x the true headroom and ~1.5x the entire window — hence the server-side 400.

### Coverage honesty (836 §S2S3-A.1)

The S2/S3 fields are shipped: `DocumentService.SourceCoverage(sourceIndex, windowsConsidered,
windowsScored)` (`DocumentService.java:487-502`) with `starved()` = `considered > 0 && scored == 0`
and the `starvedSources()` / `textCoverageComplete()` derived predicates (`:460-470`), populated
from the Worker's `MatchCitationsResponse` (`RemoteDocumentService.java:557-562`) and projected to
the wire by `StreamingCitationMatcher.java:334-338`.

Those describe the **citation matcher's window admission**, a different axis from RAGContext's
prompt budget, and this change does not touch them. What it *does* touch:

- The `:348` path pushes the budget **into retrieval**, so a trim returns fewer whole chunks with
  their citations — coverage stays truthful by construction (fewer sources, each honestly counted).
- The `:251` path truncates the assembled string while **keeping every citation** (RAGContext
  javadoc `:39-43` admits this: "citations are NOT filtered down to the sections the truncation
  actually kept"). Pre-existing, and a section-aware redesign is explicitly out of scope.
- **But** `rag.meta.context_truncated` (`:191`) was sourced *only* from the Worker's
  `retrieval.contextTruncated()` (`:184`), computed before the local truncation at `:252`. An
  honest budget makes local truncation actually fire, so leaving that flag Worker-only would ship a
  new silent lie: a trimmed answer reported as untrimmed. The flag is now the OR of both.

### Design — one arithmetic site, honest inputs

```
ConversationEngine (:350)  --[ATTR_COMPLETION_RESERVE_TOKENS = effective maxTokens]-->  ctx.attributes()
HeadAssembly.effectiveContextWindowTokens()  --[IntSupplier]-->  RAGContext (composition root)
                                        |
                                        v
                        RAGContext.inputBudgetTokens(ctx)   <-- the ONE arithmetic site
                                        |
                        +---------------+---------------+
                        v                               v
                  :251 truncateIfNeeded          :348 RetrieveContextParams
```

Why the reserve travels as an attribute rather than being re-parsed: the reserve must equal
**exactly** what the engine sends as `max_tokens`. The engine publishes the same `int` variable it
later hands to `streamLlm` (`:426`), so the two cannot drift. This mirrors the existing
`ATTR_EXCLUDED_SOURCES` seeding two lines above (`:344-347`) — same object, same mechanism,
established precedent.

Why the window travels as an `IntSupplier`: it must be read at request time, not at composition
time (the server may not have been started when the injector registry is built, and the observed
value changes when a server is adopted or restarted).

## Defect 2 — thinking-mode denies file access (prompt scoping)

`RAGQAStyle.java:27-31` said:

> "You are a helpful assistant that answers questions based on provided documents. Only answer
> based on the document content. If the answer is not in the documents, say so. …"

Nothing in that names *whose* documents or where they came from. A reasoning pass fills the gap
wrongly — the probe caught the verbatim chain-of-thought "I don't have access to actual indexed
files… only system documentation" — and prepends a false denial. Non-thinking never deliberates,
so the same prompt behaves fine there; that asymmetry is why this surfaced with thinking on.

The say-so clause is also unscoped: "if the answer is not in the documents, say so" reads as
licence to comment on *access*, not just on answer content.

Fix (wording minimal, honesty-first, no over-claiming):

- State plainly that the excerpts are passages retrieved from the user's own indexed files.
- Scope the say-so clause to **answer content** — the files don't appear to cover it — rather than
  to access.
- No claim that the excerpts are complete or that the corpus was exhaustively searched (they are
  a top-K retrieval, and after Defect 1's fix may additionally be trimmed).

### Sibling styles — checked, deliberately not changed

`SummarizationStyle.java:30-34` is the only adjacent style with comparable phrasing ("ONLY
summarize what is explicitly stated in the provided text"). It carries **no** say-so/access clause
and its path always supplies the text directly, so the "do I even have access?" inference has
nothing to latch onto. Changing it would be an unprobed prompt edit to a different shape. Logged,
not changed. A repo-wide grep for `provided documents` / `not in the documents` returns only
`RAGQAStyle` and its test.

## Implementation log

| # | Change | File |
|---|---|---|
| 1 | Clamp the MIN_BUDGET floor by real headroom; never return a budget that overflows the window | `TokenEstimation.java` |
| 2 | `effectiveContextWindowTokens()` — live n_ctx, else configured | `HeadAssembly.java` |
| 3 | Publish `ATTR_COMPLETION_RESERVE_TOKENS` (the exact `max_tokens` sent) | `ConversationEngine.java` |
| 4 | `inputBudgetTokens(ctx)` single arithmetic site; both call sites use it; window supplier ctor | `RAGContext.java` |
| 5 | `rag.meta.context_truncated` = Worker truncation OR local truncation | `RAGContext.java` |
| 6 | Wire the live window supplier into the injector registry | `ConversationApiAssembly.java` |
| 7 | Scope the retrieval prompt to the user's indexed files; scope say-so to answer content | `RAGQAStyle.java` |

## Verification

### Static

- `./gradlew.bat spotlessApply`, then `build -x test -PskipWebBuild=true`.
- Module tests: `:modules:core`, `:modules:app-services`, `:modules:app-inference`, `:modules:ui`.
- Frontend untouched — `npm run typecheck` + `npm run test:unit:run` prove it.

### Live leg — RUN 2026-08-18. Defect 1 VERIFIED. Defect 2 PARTIAL (see §Live results).

### Live leg — as scripted (superseded by §Live results below)

The shared dev stack was held by another session for the whole of this slice, so the live round was
scripted, not run. It must be run under the standard supervision rules (SHARED, one at a time;
`quick_health` `running:false` **and** a process/GPU check first; whoever starts it stops it and
verifies teardown; `distFrom` the worktree that owns the branch; pin `JUSTSEARCH_SERVER_PORT`;
model provisioning per prior campaigns).

Preconditions: `ai_activate {chatProfile:"standard"}` (quality-sensitive — the compact profile does
not satisfy prompt-format verification), a corpus with indexed user files, thinking ON.

| Arm | Payload | Expected after this change |
|---|---|---|
| A1 — Thorough | `maxTokens: 3072`, `topK: 12`, thinking on | **A working answer, not a 400.** `rag.meta.context_truncated: true` when the trim fires; fewer passages, all inside the 4096 window |
| A2 — Standard | `maxTokens: 1024`, default topK, thinking on | Unchanged: answers normally, no regression from the smaller honest budget |
| A3 — Standard, meta question (owner's Q3-style) | e.g. "can you see my files?" / "what files do you have access to?" | **No false denial.** No "I don't have access to indexed files"; the answer speaks to what the excerpts cover, not to whether access exists |

Record per arm: HTTP status, `rag.meta` (`context_truncated`, `chunks_used`, `chunks_found`),
`completionTokens` if surfaced, the reasoning trace's opening sentences (A3's discriminator), and
whether any `error` event fired.

Falsifier for A1: if a 400 still occurs, the budget is still overcommitted somewhere the two call
sites don't cover — check the non-RAG prompt contributors and history seeding, which this slice
does not budget.

## Outcome record

Static verification, all green:

- `./gradlew.bat build -x test -PskipWebBuild=true` — BUILD SUCCESSFUL.
- Full JVM unit suite — **7929 tests, 0 failures**, 26 skipped.
- `modules/ui-web` — `npm run typecheck` clean; `npm run test:unit:run` **422 files / 5235 tests**
  passed. (FE is untouched by this slice; this is the proof, not a change.)
- Diff contains no unintended non-ASCII (32 added non-ASCII lines, all intentional em-dashes in
  comments — no cp1252 mojibake).

### Mutation probe — both call sites independently covered

Required by the acceptance criteria, and it earned its keep:

| Probe | Result |
|---|---|
| Revert `tryOpenRetrieval` (`:494`) to `(8192, 1024)` | **3 named tests fail** — `open retrieval sends a budget sized by the LIVE window`, `budget tracks the request's own reserve`, `unobserved window falls back to the CONFIGURED one` |
| Revert the truncation site (`:377`) to `(8192, 1024)` | **1 named test fails** — `845: an over-budget context is TRIMMED and reported` |

**The second probe initially PASSED** — i.e. the test was not actually covering that call site. The
fixture context was ~39,000 estimated tokens, large enough to truncate under the old 5990 budget as
well as the new 460, so it could not tell the two apart. Resizing it to ~2900 tokens — above the
honest budget, below the old one — made it discriminate. This is exactly the `audit-without-test` /
test-precision failure mode: a green test that passes for the wrong reason. Recorded because the
probe is the only thing that caught it.

### Critical-analysis pass

- **Wrong-gate check.** Verified at the set-site, not by symbol existence:
  `ConversationEngine.java:357` seeds `ATTR_COMPLETION_RESERVE_TOKENS`, `:369` runs the injectors —
  correct ordering. `RAGContext.ID` is referenced by exactly one shape (`RAGAskShape.java:68`), which
  dispatches through `dispatchSubstrateDrivenBody`, the same method that seeds the attribute. No
  second dispatch path reaches RAGContext with the attribute unset.
- **Event ordering.** `rag.meta` emission moved after truncation. The terminal-error returns between
  its construction and its emission all return `InjectorResult.terminalError(...)`, which discards
  `events` — so those paths never emitted `rag.meta` before either. Behaviour unchanged; pinned by
  `ragMetaStillPrecedesCitations`.
- **Subagent claims re-derived.** The delegated audit was right about the seam
  (`OnlineAiService.llmContextTokens()` already exists, with the `AgentLoopService:446-449`
  precedent) but wrong on three arithmetic values it reported (`5985`/`2299`/`461` vs the actual
  `5990`/`2304`/`460`). All budget constants in the tests were computed by hand from the source
  formula rather than taken from the report.

### Design deltas from the original brief

1. **Window source.** The brief suggested `HeadAssembly` / `ServerPropsOps`. The real seam already
   existed: `OnlineAiService.llmContextTokens()` (`OnlineAiService.java:408`) → `OnlineAiServiceImpl`
   → `InferenceLifecycleManager.lastKnownContextTokens()`, with `configuredContextTokens()` behind
   it and an established consumption pattern at `AgentLoopService.java:446-449`. No new accessor was
   added — `explore-before-implementing` applied.
2. **A third lie, found while implementing** (§Lie 3): the budgeter's own `MIN_BUDGET` floor could
   exceed the window. Fixing only the call sites would have left the overflow reachable.
3. **`retrieval_coverage` is affected.** The `:494` budget is the Worker's `maxContextTokens`, which
   is the *denominator* of `retrievalCoverage` (`RagContextOps.java:645-646`) — a persisted,
   user-visible calibration field. A smaller truthful denominator raises reported coverage for the
   same retrieved text. More honest than dividing by a window that does not exist, but it is a
   semantic change to an evidence field and is called out in the PR rather than slipped in.

### Logged, not fixed (out of scope)

- `SummarizationStyle:30` — comparable phrasing, no say-so/access clause; unprobed different shape.
- `TokenEstimation.truncateIfNeeded:124` — `cap = max(MIN_BUDGET, maxContextTokens)` still
  re-inflates a sub-256 budget to 256, so the impossible-request case retains a small residual
  overflow at the *truncation* layer even though the *budgeter* is now exact.
- `AgentLoopService.java:446` — unboxes `configuredContextTokens()` without a null check; both
  accessors are nullable `Integer` defaults, so a non-Online runtime NPEs there.

## Live results — 2026-08-18

Stack driven by this session end-to-end: `quick_health` `running:false` plus an independent
process/port/GPU scan before start (only a stale browser tab was retrying the dead port); started
from **this worktree's** dist (`distFrom`, `skipBuild` after verifying the Head dist jar actually
contained the new code — `llm.completionReserveTokens` present in
`modules/ui/build/install/ui/lib/app-services-0.2.0.jar`); stopped by this session and verified.

**Environment.** Chat model `Qwen_Qwen3.5-9B-Q4_K_M.gguf` (standard, not the compact dev default —
set via `POST /api/settings/v2`), cuda12 GPU runtime, thinking ON, corpus `docs/explanation`
(30 docs). Raw SSE captures: `tmp/845-live/*.sse` (gitignored).

**The premise, confirmed live.** `/api/inference/status` reported
`"llmContextTokens": 4096, "configuredContextTokens": 4096` — that is the exact accessor the fix
reads, observed from `/props`. The old call sites assumed **8192**, i.e. a window twice the real
one, and asked for **5990** input tokens out of a 4096-token window.

| Arm | reserve | budget (computed) | retrieved | prompt | completion | total | chunks used/found | `context_truncated` | HTTP |
|---|---|---|---|---|---|---|---|---|---|
| A1 quick | 512 | 2764 | 2286 | 2430 | 512 | 2942 | 5 / 60 | false | 200 |
| A2 standard | 1024 | 2304 | 2286 | 2430 | 1024 | 3454 | 5 / 60 | false | 200 |
| **A3 thorough** | **3072** | **460** | **480** | **585** | **3072** | **3657** | **1 / 146** | **true** | **200** |
| A4 meta (direct "can you access my files?") | 1024 | 2304 | 1606 | 1587 | 667 | 2254 | 5 / 66 | false | 200 |
| A5 meta (spontaneous, probe's original shape) | 1024 | 2304 | — | — | — | — | — | false | 200 |

### Verdict 1 — Defect 1 VERIFIED FIXED

- **A3 returns 200, not 400.** The probe's exact payload (`maxTokens 3072`, `topK 12`) now
  completes with a real answer.
- **Honest degradation is visible in the numbers**: retrieval found **146** chunks and the budget
  admitted **1**. Fewer passages, all inside the window — exactly the designed behaviour.
- **`context_truncated: true` on A3 only.** The OR-ed flag fires precisely where the trim happened
  and stays false on the arms that fit — the honesty half works and is not stuck on.
- **The invariant holds on every arm**: `prompt + completion` = 2942 / 3454 / **3657** / 2254, all
  ≤ 4096. A3's 3657 leaves 439 tokens of headroom against a window the old code overshot outright.
- **The budget binds where the arithmetic says.** A1/A2 retrieved 2286 against budgets of
  2764/2304; A3 retrieved 480 against 460. Under the old constants every arm would have been handed
  5990 — A3's prompt would then have been ≈5990 + 178 system + question ≈ 6.2k, against a 4096
  window, with 3072 more reserved for completion. That is the 400, arithmetically reconstructed
  from live numbers.

### Verdict 2 — Defect 2 PARTIAL. Reported, not silently patched.

The deployed jar was confirmed to carry the new prompt (`user's own files`,
`do not say you lack access` present; `not in the documents` absent), and `RAGQAStyle.ID` is wired
into `RAGAskShape.definition()`'s contributor list — so this is a real behavioural result, not a
stale build.

- **A5 — FIXED.** On the probe's original shape (a normal question, no access framing) the answer
  is *"The provided excerpts do not appear to cover the specific content of your indexed files"* —
  the prescribed coverage framing, verbatim, with **no denial of access**. The reasoning trace shows
  the model restating the constraint (*"Do not say I lack access to user files"*) before complying.
  The spontaneous false-denial move this defect was filed for is gone.
- **A4 — NOT fixed.** Asked directly *"Can you access my files?"*, the model still answers
  *"I cannot directly access your files… I cannot access, read, or browse your actual indexed
  files."* The instruction is present and acknowledged, and overridden anyway.
- **Confound worth naming before anyone re-words the prompt**: the corpus here is JustSearch's own
  product documentation, so the retrieved text itself invites a *product-vs-assistant* split
  ("JustSearch can search your files" / "but I only got excerpts"). A4's reasoning takes exactly
  that split. Whether A4 is a prompt-wording problem, a corpus artifact, or genuinely out of scope
  for a prompt fix is **not** settled by this round, and guessing at new wording without a probe
  that separates those three is how the next round gets wasted.

### Incidental findings (not caused by this change)

- **A1 quick + thinking returns an EMPTY answer**: 512 completion tokens, ~1954 chars of reasoning,
  **0 answer characters**. This is tempdoc 835's B3 regression reproduced at the Quick rung — and
  it is independent, direct empirical confirmation of this slice's arithmetic decision: reasoning
  is spent inside the completion reserve, so a small `maxTokens` with thinking on can leave nothing
  for the answer. Logged.
- **A3's `retrieved` (480) slightly exceeds the computed budget (460)** — ~4%. The Head budgets with
  `TokenEstimation`'s heuristic while the done payload's `contextBreakdown` accounts separately, so
  small divergence is expected; the binding constraint (`prompt + completion ≤ window`) held with
  margin on every arm. Logged rather than tuned.
- **A2 reports `starvedSources: [1,2,3,4]`** — the 836 S2/S3 starved state (citation-matcher window
  admission), reporting honestly. Untouched by this change; A4/A5 show full coverage.

### Teardown — verified

`stop` killed `[32560, 17648, 28048]`, `portsClosed: true`; `quick_health` `running:false`;
independent scan: no `llama-server`, **no LISTENING sockets** on 62967/62708/5173 (only `TIME_WAIT`
and a stale browser tab's failing `SYN_SENT` retries), GPU back to **1210 MiB** vs the **1205 MiB**
pre-run baseline — inference VRAM fully released. The one surviving `java` PID (15704) is the
Gradle daemon (`--add-opens`/`--add-exports`, temurin25), not a stack process.

Staging note: cuda12 was **copied** (1 GB) into this worktree rather than junctioned. A junction
into the *shared* main-checkout runtime would let any recursive worktree cleanup delete through
into the copy every other worktree depends on.

### Status

Defect 1 is live-verified. Defect 2 is half-verified and the remainder is characterised, not
guessed at. PR **#482** stays a **draft** pending a decision on A4.

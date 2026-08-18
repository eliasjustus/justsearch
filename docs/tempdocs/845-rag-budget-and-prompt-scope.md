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

### Live leg — REQUIRED before the PR leaves draft, currently PENDING

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

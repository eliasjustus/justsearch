---
title: "Tempdoc 499 — Resolution and recovery architecture"
type: tempdocs
status: done
created: 2026-05-16
source-id: G120
category: architecture / intent substrate / error recovery
authority: |
  Canonical home for the intent resolution layer design, its implementation
  status, and the recovery-policy extension architecture. Absorbs the prior
  499-intent-resolution-layer.md (deleted and replaced).
related:
  - slices/486-consumer-feature-discovery.md R3.B (G120 entry)
  - slices/487-intent-substrate.md §A.x (deferral record)
  - slices/489-url-addressable-shell.md (URL routing substrate)
  - slices/492-intent-realizer-substrate-completion.md (IntentHandler tier)
---

# Tempdoc 499 — Resolution and recovery architecture

## 1. Thesis

The intent resolution layer (shipped) answers the question "does this
target exist, and if not, what's close?" The **recovery policy layer**
(this tempdoc's design contribution) answers the follow-up question:
"given a resolution failure, what should this particular consumer do
about it?"

Today, every consumer of the resolution layer makes its own ad-hoc
recovery decision:

| Consumer | Resolution failure behavior |
|----------|---------------------------|
| FE IntentRouter | Fire `unresolved` DispatchOutcome; skip handler |
| Backend URLExtractor | Emit `intent.resolution` SSE event; skip dispatch |
| AgentLoopService | Hard abort: `emitError(UNKNOWN_TOOL)` → terminate session |
| McpProtocolHandler | Plain string: `"Unknown tool: X"` |
| URL bar (URLSource) | Silent no-op |
| Settings / shape refs | Silent fallback to defaults |

These are six independent implementations of the same decision: "I
resolved an ID and got `Unresolved` — now what?" The correct design
extracts this decision into a policy layer, following the codebase's
existing pattern.

## 2. The existing policy pattern

The codebase already has a clean three-layer pattern for context-driven
decisions:

### 2.1 TrustEvaluator — the template

```
Context evaluation:   (SourceTier, RiskTier) → GateBehavior
Decision type:        GateBehavior { AUTO, INLINE_CONFIRM, TYPED_CONFIRM, DENY }
Policy-driven action: enforceTrustLattice switches on GateBehavior → proceed / throw
```

`TrustEvaluator` is a pure function injected into `OperationExecutorImpl`.
The executor calls it, pattern-matches on the result, and acts. The
evaluator doesn't know about executors; the executor doesn't know about
trust rules. Clean separation.

### 2.2 ConfirmStrategy — rendering hint

```java
sealed interface ConfirmStrategy permits None, Inline, Typed { ... }
```

Carried on `OperationPolicy.confirm()`. The strategy declares *what
confirmation looks like*; `GateBehavior` decides *whether to require it*.
Two concerns, two types.

### 2.3 AgentRetryPolicy — the static case

```
AgentRetryPolicy.forCode(errorCode) → RetryDecision { action, maxRetries, backoff }
```

A static decision table, not an interface. Package-private to
`app-agent`. Works because agent-loop retry decisions are fixed policy,
not pluggable. The contrast with `TrustEvaluator` is intentional:
trust evaluation is pluggable (the evaluator is injected); retry policy
is static (hardcoded per error code).

### 2.4 OperationPolicy — the bundle

```java
record OperationPolicy(RiskTier risk, ConfirmStrategy confirm,
    AuditPolicy audit, RetryPolicy retry, ...)
```

Bundles multiple policy axes per entity. Every consumer reads from
`op.policy()`. This is the per-entity pattern; resolution recovery is
per-consumer, not per-entity (see §3.1).

## 3. Design: RecoveryAction

### 3.1 Why per-consumer, not per-entity

The correct recovery for a resolution failure depends on *who is doing
the lookup*, not *what is being looked up*:

- The agent loop should inject a correction hint and let the model retry
  (because the model can self-correct)
- The MCP handler should include suggestions in the error response
  (because the MCP client can self-correct)
- The FE IntentRouter should show suggestion chips
  (because the user can click)
- A high-confidence single match should auto-correct
  (because the user would always click the only option)

The same operation (`core.restart-worker`) looked up from the agent
loop vs the URL bar should produce different recovery actions for the
same `Unresolved` result. This means the policy is a property of the
lookup context, not of the catalog entry.

### 3.2 RecoveryAction sealed type

The recovery layer produces a `RecoveryAction` — a sealed type that
tells the consumer what to do:

```java
public sealed interface RecoveryAction<T> {
    record Proceed<T>(T entry)
        implements RecoveryAction<T> {}
    record AutoCorrect<T>(T entry, String originalId, String correctedId)
        implements RecoveryAction<T> {}
    record SuggestToUser<T>(String attemptedId,
        List<Suggestion<T>> alternatives)
        implements RecoveryAction<T> {}
    record InjectHint<T>(String attemptedId, String hintMessage,
        List<Suggestion<T>> alternatives)
        implements RecoveryAction<T> {}
    record Abort<T>(String attemptedId, String reason)
        implements RecoveryAction<T> {}
}
```

**Proceed** — resolution succeeded (Resolved or Redirected). Pass the
entry to the consumer. This is the happy path and the only variant where
dispatch continues.

**AutoCorrect** — resolution failed but exactly one alternative is an
obvious correction. Gate: DL distance ≤ 2 AND confidence ≥ 0.65 AND
no second candidate within DL ≤ (DL+2). The consumer dispatches to the
corrected target and shows a brief "Corrected X → Y" label. No user
interaction required. See §6.3 for the empirical threshold derivation.

**SuggestToUser** — resolution failed with alternatives. The consumer
renders suggestions and waits for user action (click a chip, dismiss).
This is the "did you mean?" path.

**InjectHint** — resolution failed with alternatives, and the consumer
is an LLM agent that can self-correct. The consumer injects a
`role: tool` message with the correction hint and continues the
iteration loop. No user interaction; the model retries.

**Abort** — resolution failed with no alternatives, or the policy
decides not to recover. The consumer terminates the operation.

### 3.3 The policy function

```java
interface ResolutionRecoveryPolicy<T> {
    RecoveryAction<T> decide(ResolutionResult<T> result);
}
```

Pure function. Each consumer constructs or is injected with the policy
appropriate for its context. The policy inspects the `ResolutionResult`
and produces a `RecoveryAction`.

### 3.4 Concrete policies

**AgentToolPolicy** — for `AgentLoopService` tool dispatch:
- `Resolved` → `Proceed`
- `Redirected` → `Proceed` (use canonical entry)
- `Unresolved` with alternatives → `InjectHint` (compose tool-result
  message with "Did you mean: ..." text)
- `Unresolved` without alternatives → `Abort`

**InteractivePolicy** — for FE IntentRouter and URL bar:
- `Resolved` → `Proceed`
- `Redirected` → `AutoCorrect` (navigate to canonical, show label)
- `Unresolved` with 1 alternative, DL ≤ 2, confidence ≥ 0.65,
  no second candidate within DL ≤ (DL+2) → `AutoCorrect`
- `Unresolved` with alternatives → `SuggestToUser`
- `Unresolved` without alternatives → `Abort`

**McpPolicy** — for `McpProtocolHandler`:
- `Resolved` → `Proceed`
- `Redirected` → `Proceed`
- `Unresolved` with alternatives → `SuggestToUser` (encode suggestions
  in the MCP error content map; the MCP client decides what to do)
- `Unresolved` without alternatives → `Abort`

**StrictPolicy** — for validation/test contexts:
- `Resolved` → `Proceed`
- Everything else → `Abort`

### 3.5 How consumers use it

The consumer pattern is:

```java
ResolutionResult<Operation> resolution = catalog.resolve(ref);
RecoveryAction<Operation> action = recoveryPolicy.decide(resolution);
switch (action) {
    case Proceed(var op)        -> dispatch(op);
    case AutoCorrect(var op, ..) -> dispatch(op); logCorrection(..);
    case SuggestToUser(..)      -> emitSuggestions(..);
    case InjectHint(..)         -> injectToolResult(..); continue;
    case Abort(..)              -> emitError(..); return;
}
```

This replaces the current ad-hoc switch on `ResolutionResult` in each
consumer with a policy-driven switch on `RecoveryAction`. The consumer
doesn't decide what to do with an `Unresolved` result — the policy
decides, and the consumer executes.

## 4. How extensions map to this architecture

### E1. Agent self-correction → `AgentToolPolicy` producing `InjectHint`

`AgentLoopService.java` line 1132 currently calls
`operationCatalog.findByWireName()` → null → hard abort. With the
recovery architecture:

1. Call `operationCatalog.resolve(wireNameRef)` instead of `findByWireName()`
2. Pass result to `AgentToolPolicy.decide(result)`
3. On `InjectHint`: compose `role: tool` message with alternatives, `continue` the inner loop
4. On `Abort`: existing hard-abort path

The existing "inject and continue" pattern (tool-loop-guard at lines
1200–1213, user-rejection at lines 1171–1185) is the mechanical
template for the `InjectHint` case. No new loop machinery needed.

### E2. URL-bar toast → `InteractivePolicy` producing `SuggestToUser`

`IntentRouter.dispatch()` already calls `resolveSurface()` and handles
`unresolved`. A `router.subscribe()` listener catches `SuggestToUser`
outcomes from `URL_BAR`/`URL_DEEPLINK` transports and renders a toast
via `AdvisoryToastHost`.

### E3. MCP suggestions → `McpPolicy` producing `SuggestToUser`

`McpProtocolHandler.java:232` gets a `SuggestToUser` action with
alternatives. The handler includes them in the MCP error content map:
`{ "error": "Unknown tool: X", "suggestions": ["Y", "Z"] }`.

### E4. Auto-correction → `InteractivePolicy` producing `AutoCorrect`

The `InteractivePolicy` uses a two-tier gate derived from empirical
confidence analysis (see §6.3):

**Typo gate:** DL ≤ 2 AND confidence ≥ 0.65 AND exactly 1 candidate
within DL ≤ (DL+2). Covers single-char deletion/insertion/substitution/
transposition typos. Confidence range: 0.695–0.800 for typical IDs.

**Truncation gate:** tokenJaccard ≥ 0.60 AND prefixScore ≥ 0.55 AND
confidence ≥ 0.55. Covers missing-suffix cases like `core.library` →
`core.library-surface` (DL=8, confidence=0.620).

If either gate passes, produces `AutoCorrect`. The `IntentRouter`
dispatches to the corrected target and fires
`{ status: 'redirected', reason: 'auto-corrected' }` DispatchOutcome.

### E5. Synonyms → resolution layer, not recovery layer

Synonym pre-filtering ("restart" → "reboot") belongs in the resolution
layer, not the recovery layer. It transforms the query before matching
— between alias lookup (step 2) and fuzzy matching (step 3) in the
`resolve()` pipeline. The recovery policy doesn't need to know about
synonyms.

### E6. Telemetry → `router.subscribe()` consuming `RecoveryAction`

A telemetry subscriber counts `RecoveryAction` variants by
`(actionType, transport)` dimensions. The existing zero-subscriber
`IntentRouter.subscribe()` is the hook. `RecoveryAction` carries
richer data than `DispatchOutcome` alone — `InjectHint` vs
`SuggestToUser` vs `AutoCorrect` vs `Abort` distinguishes the four
recovery paths.

## 5. Implementation status

### Shipped (resolution layer)

The base resolution layer is merged to `main` (3 commits). It provides:

- `ResolutionResult<T>` sealed type (Resolved/Redirected/Unresolved)
- `CatalogMatcher` (DL distance + token overlap + prefix scoring)
- `AliasRegistry` (cross-catalog redirects, `SSOT/aliases.v1.json`)
- `PrimitiveCatalog.resolve()` and `SurfaceCatalog.resolve()`
- FE `resolveAgainstCatalog()` + `CatalogResolver`
- `IntentRouter` with mandatory resolution
- `NavigationReceipt` with `'unresolved'` outcome + suggestion chips
- `DispatchOutcome` as structured discriminated union
- `intent.resolution` SSE event (all three outcomes)
- `URLExtractor` pre-resolution for Operation intents
- `onIntentResolution` handler in `AgentSessionController` + `NavigateView`
- `NavigationHandler` defense-in-depth assertion

### Shipped (recovery policy layer)

- `RecoveryAction<T>` sealed type (Proceed/AutoCorrect/SuggestToUser/InjectHint/Abort)
- `ResolutionRecoveryPolicy<T>` interface with 4 static factories (strict/mcp/agentTool/interactive)
- E1: Agent loop self-correction (inject hint + continue on unknown tool with alternatives)
- E2: URL-bar unresolved subscriber (console.warn with suggestions for URL_BAR/URL_DEEPLINK transports)
- E3: MCP error suggestion enrichment (wire-name fuzzy match in McpProtocolHandler)
- E4: Auto-correction via gap-based two-tier gate in InteractivePolicy (live-verified: gap < 0.15 → suggest, gap ≥ 0.15 → auto-correct)
- E5: Synonym pre-filter in resolve() pipeline (SynonymMap between alias and fuzzy steps)
- E6: Telemetry subscriber (localStorage 7-day rolling window, wired in Shell.ts)
- FE `RecoveryPolicy` type + `interactivePolicy` / `strictPolicy` implementations
- IntentRouter uses `recoveryPolicy` (default: interactivePolicy) for all dispatch decisions

## 6. Critical analysis

### 6.1 Is a policy layer necessary, or is ad-hoc switching enough?

**Argument against:** There are only 4 consumers (agent loop, MCP,
IntentRouter, URL bar). Each has a short switch on `ResolutionResult`
status. A policy abstraction adds a type and an interface for what
amounts to ~10 lines of switching per consumer. YAGNI.

**Argument for:** The switching logic is not just "what status?" — it's
"what status, at what confidence, with how many alternatives, in what
transport context?" The `InteractivePolicy`'s auto-correction threshold
(DL ≤ 2, confidence ≥ 0.65, no second candidate within DL ≤ DL+2) is a
three-condition check that would be copy-pasted across IntentRouter and
URL-bar code if not extracted. The `AgentToolPolicy` carries the
"inject hint and continue" decision which is genuinely different from
"show chips and wait." These are different decisions, not different
renderings of the same decision.

**Verdict:** The policy layer is warranted. The five `RecoveryAction`
variants map to five genuinely different execution paths. Without the
policy, each consumer implements its own version of the same 5-way
decision, and the conditions for each branch drift independently.

### 6.2 Should RecoveryAction be on ResolutionResult directly?

An alternative: `ResolutionResult` carries the recovery action as a
computed field, determined at resolution time based on injected policy.
Then consumers don't need a separate `decide()` call.

**Why this is wrong:** Resolution is context-free (the catalog doesn't
know who's asking). Recovery is context-specific (the agent loop wants
`InjectHint`; the FE wants `SuggestToUser`). Coupling them on the same
type forces the catalog to know about consumers. The separation is the
`TrustEvaluator` lesson applied: the evaluator doesn't know about
executors.

### 6.3 Wire-name resolution: no gap (investigated 2026-05-16)

`findByWireName()` never constructs an `OperationRef` from a wire name.
It iterates existing catalog entries and forward-transliterates each via
`toWireName(op.id())` to compare. `CatalogMatcher.findAlternatives()`
can do the same by passing `op -> toWireName(op.id())` as the
`idExtractor`. The agent self-correction path works with wire names
directly using the existing API. No new method or reverse transliteration
is needed.

### 6.4 Auto-correction threshold: empirical derivation

Computed confidence scores for real typo scenarios against the live
14-entry surface catalog:

| Typo | Best match | DL | Confidence | Gap to 2nd |
|------|-----------|-----|-----------|------------|
| `core.libary-surface` (deletion) | `core.library-surface` | 1 | 0.705 | 0.255 |
| `core.lirbary-surface` (transposition) | `core.library-surface` | 1 | 0.695 | 0.245 |
| `core.search-surfce` (deletion) | `core.search-surface` | 1 | 0.792 | 0.342 |
| `core.brain-surfaec` (transposition) | `core.brain-surface` | 1 | 0.800 | 0.350 |
| `core.library` (truncation) | `core.library-surface` | 8 | 0.620 | 0.320 |

A single-char typo in a ~20-char ID produces confidence 0.70–0.80, never
0.90. The original threshold of confidence > 0.9 (tempdoc v1) was wrong
and would have made auto-correction dead code.

**Corrected two-tier gate:**
- **Typo:** DL ≤ 2 AND confidence ≥ 0.65 AND exactly 1 candidate
  within DL ≤ (DL+2)
- **Truncation:** tokenJaccard ≥ 0.60 AND prefixScore ≥ 0.55 AND
  confidence ≥ 0.55

Both tiers require the gap to the second-best candidate to be ≥ 0.15
(all tested cases exceed 0.24). This prevents false corrections when
two catalog entries are equidistant from the query.

### 6.4 Scope boundary: parser-level structural mismatches

The resolution layer covers well-formed IDs (pass `NamespacedId` regex)
that don't match a catalog entry. Structurally invalid IDs (`search`,
`core.Library`, `core.library_surface`) fail at parse time and never
reach resolution. This boundary is documented and intentional. A future
parser-leniency enhancement (normalize before constructing the ref)
could bridge this gap but is orthogonal.

### 6.5 Command palette — deferred

A command palette is the largest natural consumer of both the resolution
and recovery layers, but it requires a new UI component (~1-2 weeks)
and deserves its own tempdoc. See 486 catalogue F1. The resolution
layer is ready to serve it.

## 7. Future work (post-implementation research, 2026-05-16)

Six directions identified through codebase investigation and industry
research. Ordered by impact.

### ~~F1. Resolve stale saved bookmarks~~ — ALREADY HANDLED

**Investigated 2026-05-16.** `BookmarksPopover.handleClick` fires
`bookmark-navigate` → `Shell.handleBookmarkNavigate` calls
`parseUrl(url)` → `this.intentRouter.dispatch(intent)`. The IntentRouter
has mandatory resolution. Stale bookmarks already go through the full
resolve → recovery policy → suggest/auto-correct/abort pipeline.

No new code needed. The resolution layer already covers this case
because saved view restore was correctly routed through the IntentRouter
(not `activateSurface()` directly). Same applies to NavigationJournal
back/forward (dispatches via `intentRouter.dispatch` at lines 232, 258).

### F2. Learn from corrections → promoted aliases

**The pattern.** When a user clicks a "Did you mean?" suggestion chip,
that's a signal: "this typo maps to this target." Raycast and Algolia
both promote frequently-corrected queries into permanent aliases.

**The mechanism.** On suggestion acceptance, write a new entry to the
`AliasRegistry` (or a parallel `PromotedAliasStore`). Next time the
same typo occurs, the alias lookup (step 2 of `resolve()`) catches it
before fuzzy matching runs — instant redirect instead of "Did you mean?"

User corrections become permanent improvements. The alias file
(`SSOT/aliases.v1.json` or a local `promoted-aliases.json`) is
auditable and reversible.

### F3. Embedding-based semantic fallback — DEFERRED (architecture conflict)

**Investigated 2026-05-16.** All embedding inference is Worker-side via
ONNX. There is no HTTP endpoint, no gRPC method, and no Head-side
encoder model. The architecture explicitly isolates embedding from the
LLM to prevent resource contention. Building this requires either a new
gRPC service + Head proxy (cross-process bridge) or a second encoder
model load in the Head (violates resource isolation).

The synonym pre-filter (E5) covers the most common semantic gaps
("restart" → "reboot", "find" → "search") without embedding inference.
F3 is deferred until the architecture gains a general-purpose embedding
API — likely post-Worker-refactor or via a dedicated micro-service.

### F4. Undo for auto-correction

**The problem.** When auto-correction fires and navigates to the
corrected surface, there's no way for the user to say "that's not what
I meant." The system assumed the correction was right.

**The pattern.** Git's `help.autocorrect` shows a countdown before
executing. Chrome's "did you mean?" keeps the original query clickable.
The combination: display the corrected form + original side-by-side in
the `ResolutionToast`, execute after a 1.5s grace window, and keep the
original token clickable to re-resolve without auto-correction for that
session.

**Implementation.** Extend `ResolutionToast` with a "Not what you meant?
[original query]" link that dispatches the original intent with
`recoveryPolicy: strictPolicy` (bypasses auto-correction, shows the
`Abort` path instead).

**Investigated 2026-05-16.** Adding `recoveryPolicyOverride?:
RecoveryPolicy` to `DispatchOptions` is a minimal change — 1 optional
field, 0 existing callers need updating. The router reads it and
shadows the config-level policy for that one dispatch. The undo link
dispatches with `{ recoveryPolicyOverride: strictPolicy }` → strict
aborts instead of auto-correcting → user sees "did you mean?"
suggestions.

### F5. Resolution quality metrics panel

**The pattern.** Algolia's analytics dashboard tracks: no-results rate,
correction rate (fraction of queries auto-corrected), and top failed
queries. These are the direct quality signals for resolution tuning.

**Implementation.** The telemetry subscriber (E6) already counts
`(status, transport)` tuples in localStorage. Add a panel in the
Health surface that reads this data and renders:
- Pie chart: resolved / auto-corrected / suggested / aborted
- Top 5 failed `attemptedId` strings (candidates for alias promotion)
- Correction rate over time (sliding 7-day window)

This makes resolution quality observable and guides future tuning.

### F6. Plugin resolution SPI

**The pattern.** Plugins that contribute surfaces and operations should
also contribute aliases and synonyms for those entries. When a plugin
registers `vendor.acme.dashboard`, it should be able to register
aliases (`dashboard`, `acme-dash`) and synonyms (`metrics` →
`dashboard`).

**Implementation.** The `PluginRegistry.install()` hook already calls
plugin's `register()` which returns a `PluginContribution`. Extend the
contribution type to include `aliases: AliasEntry[]` and `synonyms:
SynonymEntry[]`. On install, merge into the resolution layer's
`AliasRegistry` and `SynonymMap`. On uninstall, remove.

The `AliasRegistry` and `SynonymMap` already accept arbitrary entries —
the SPI just needs a contribution hook in the plugin lifecycle.

### Deferred

- **Command palette** — see §6.5 and 486 catalogue F1.
- **Intent prediction** (context-aware ranking based on navigation
  sequences) — requires telemetry data accumulation before the ranking
  model can be trained. Prerequisite: F5 (metrics panel) + F2 (learning
  from corrections) to build the data foundation.
- **F3 (embedding fallback)** — architecture conflict; see §F3 note.

### Implementation status (2026-05-16)

| Item | Status | Verification |
|------|--------|-------------|
| ~~F1~~ | Already handled | Bookmarks dispatch through IntentRouter — resolution covers stale refs |
| **F2** | **Shipped** | `promotedAliases.ts` stores corrections in localStorage; Shell intercepts `suggestion-click` and promotes aliases; `refreshPromotedAliases()` wired at boot. Live-verified. |
| ~~F3~~ | Deferred | Architecture conflict — no FE→embedding API path |
| **F4** | **Shipped** | `DispatchOptions.recoveryPolicyOverride` (1 optional field); `ResolutionToast.showAutoCorrection()` with undo link; Shell wires `undo-auto-correct` → re-dispatch with `strictPolicy`. Live-verified: toast renders with undo link. |
| **F5** | **Shipped** | `ResolutionStats` Lit component reads telemetry localStorage; renders outcome breakdown, correction rate, failure rate, top failed IDs. Live-verified: shows real session data (59 dispatched, 0% failure). |
| **F6** | **Shipped** | `PluginContribution.resolutionAliases` + `resolutionSynonyms` fields; `PluginRegistry` merges aliases on install via `setSurfaceAliases`. Typecheck verified. |

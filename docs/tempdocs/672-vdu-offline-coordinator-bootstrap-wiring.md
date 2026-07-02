---
title: "VDU offline processing never runs: the offline coordinator is value-captured at Head bootstrap from a not-yet-connected Worker client and never re-bound, so both offline-trigger paths (REST + operation) stay null on every launch"
type: tempdocs
status: "implemented and validated (2026-07-02). Root cause was NOT what 671's inbox note guessed (it is the Worker/Knowledge client that is null at ServicePhase, not the inference manager); scope was all launch paths, not just dev-stack. Fix: the Worker client is threaded as a live Supplier<RemoteKnowledgeClient> (the way ServicePhase.Input already threads indexingServiceSupplier) instead of being value-captured. Live-verified end-to-end against the real datasets/golden/synth-scan-v1 corpus on the dev stack and in the browser UI. See §Implementation and validation and §Status."
created: 2026-07-02
updated: 2026-07-02
author: agent investigation (spun out of tempdoc 671's explicitly-declared out-of-scope follow-on, itself discovered as a byproduct of tempdoc 624's eval-corpus materialization work)
category: head-bootstrap / service-composition / vdu / worker-services / lifecycle-wiring
related:
  - 671-tika-ocr-skip-routing-misclassification   # origin -- §Follow-up + §Status pinpoint this exact bug and explicitly decline to fix it there
  - 624-agentic-retrieval-eval-rebuild             # the blocked consumer -- synth-scan-v1's nDCG@10=0.0000 traces to this bug, blocking §M.8 item 1 for the OCR corpus
  - 607-vdu-ocr-extraction-logic-analysis          # adjacent, NOT this tempdoc's owner -- 607 governs extraction ROUTING (which path a document takes); this tempdoc governs whether the VDU SERVICE is wired at Head startup at all, regardless of routing
  - 519-head-composition-graph                     # general Head-bootstrap/composition-graph background (dated 2026-05-18, different specific subject -- mega-class decomposition of AppFacadeBootstrap -- but the surrounding subsystem)
  - 374-app-packaging-and-distribution              # owns VDU vision-model *selection*/pinning; explicitly NOT this tempdoc's concern -- see §Explicit non-goals
principle: "a capability that is lazily constructed because a dependency isn't ready at construction time needs an explicit late-bind bridge to the dependency's own readiness signal -- otherwise 'the object was created' silently substitutes for 'the object's state will ever be updated' (standalone-capability-stays-stuck, agent-postmortems.md §10, tempdoc 521 merge T2.5). This tempdoc is a candidate second instance of that named pattern, not yet confirmed as one -- confirming or refuting that match against the real ServicePhase ordering is this tempdoc's first job."
---

# 672 -- VDU offline-coordinator bootstrap wiring gap

> NOTE: Opened 2026-07-02, spun out of tempdoc 671's own explicit deferral (its status line: "The separate
> VDU/retrieval-quality follow-on (offline-processing trigger wiring gap...) remains open and explicitly out
> of scope"). Before opening this as a new document, a register check ruled out folding it into an existing
> one -- see §Why this tempdoc exists. No design or implementation has started yet.

## Why this tempdoc exists

Tempdoc 624's live re-verification of the scan/OCR corpus (`golden/synth-scan-v1`), after tempdoc 671's real,
committed fix to the OCR-skip-reason misclassification, found the corpus's nDCG@10 is still **0.0000** --
not because the label is wrong anymore (671 fixed that), but because **no real text ever reaches the index
for these documents at all**: `vdu_status: PENDING` on 360 of 361 docs. Tempdoc 671's own investigation
(§Follow-up, §Status) traced this to a specific, narrow cause: triggering VDU (`POST
/api/offline/process`) fails because `BrainRuntimeServiceImpl.offlineProcessingTrigger` is `null` -- it would
only be non-null if `OfflineCoordinatorBuilder.build()` succeeded at Head bootstrap, which in that launch
path it apparently doesn't (`OfflineCoordinatorBuilder.java:35-38`, `ServicePhase.java:149-167`). 671 stopped
there deliberately: its own scope was the diagnostic-label bug, and it named this a "separate, retrieval-
quality follow-on."

**A register check was run before opening a new tempdoc, per this repo's own explore-before-implementing
discipline (applied to tempdoc ownership, not code):**
- **Tempdoc 607** (`status: active`, "Document extraction routing authority (Tika/OCR/VDU)") looks like the
  obvious first candidate -- it's the canonical owner of the Tika/OCR/VDU subsystem. But its actual scope is
  *routing*: given a document, which extraction path does it take. Grepped 607 in full for
  `offlineProcessingTrigger` / `OfflineCoordinatorBuilder` / `ServicePhase` / `bootstrap`: zero matches. The
  wiring gap is a different layer entirely -- whether the VDU service is even constructed and reachable at
  Head startup, independent of what routing would eventually send to it. Folding this into 607 would blur a
  currently-clean single authority with an unrelated lifecycle concern.
- **Tempdoc 671** already explicitly declined this scope in its own status line (quoted above). Continuing
  inside 671 would contradict its own stated boundary.
- **Tempdoc 519** ("Head composition root: declarative phase-typed wiring graph", status: open, dated
  2026-05-18) is the closest thing to a general Head-bootstrap-wiring owner, but it's stale (150+ newer
  tempdocs exist since) and about a specific, different subject: decomposing the `AppFacadeBootstrap`
  mega-class, not wiring-completeness bugs for lazily-constructed capabilities.

No existing tempdoc owns "a lazily-constructed capability isn't correctly wired at Head bootstrap." That's
a real gap, not an oversight -- hence this new document.

## Overarching goal

VDU (vision-document-understanding fallback OCR) is a real, already-implemented, already-staged production
capability (`mmproj-F16.gguf` staged and hash-verified per 671's own live-verification pass; VDU readiness
reports `READY`) that currently **never actually runs** in the dev-stack launch path, because the trigger
that would invoke it is never wired up. The broader goal this tempdoc serves: make VDU a genuinely working
fallback for degraded/scanned documents, not a configured-but-inert capability -- which matters beyond
tempdoc 624's own eval corpus. The immediate, concrete forcing function is narrower: tempdoc 624's §M.8
credibility bar cannot assess the scan corpus at all while this is broken, because every re-run just
re-measures the same "text never arrived" failure regardless of seed count or LLM spend.

## This tempdoc's main goal (scope)

1. **Diagnose precisely why `OfflineCoordinatorBuilder.build()` doesn't complete (or doesn't run) at Head
   bootstrap in the dev-stack launch path**, reading the real current `ServicePhase` ordering and
   `OfflineCoordinatorBuilder` construction site -- not assuming 671's file:line citations are still exactly
   current without re-verifying them against HEAD.
2. **Determine whether this is a genuine instance of the `standalone-capability-stays-stuck` pattern**
   (a lazily-constructed capability needing a late-bind `addListener` bridge to its dependency's readiness
   signal, per `HeadAssembly.connectKnowledgeServer`'s pre-519 precedent) or a different failure shape
   (e.g. an ordering bug, a swallowed exception, a config-gate that's off by default in this launch path).
   Confirm before applying the precedent's fix shape -- do not assume the match is exact just because the
   surface symptom (a capability that "was created" but never becomes usable) matches.
3. **Ship the fix and live-verify a real VDU resolution end-to-end**: `POST /api/offline/process` succeeds,
   and `vdu_status` transitions off `PENDING` for at least the previously-stuck sample documents from 671's
   own live-verification pass. Static analysis alone does not close this tempdoc -- per this repo's
   audit-driven-fixes-need-test discipline, a real run against the real corpus is the bar.
4. **Report back into tempdoc 624** once verified, so its own §M.8 item 1 (fidelity) can be re-assessed
   for the scan corpus with real data, not left permanently excluded.

## Explicit non-goals

- **Extraction routing logic** (which path a document takes: Tika-direct vs OCR vs VDU) -- that's tempdoc
  607's authority; do not touch `ExtractionOutcomeClassifier`/`OcrOutcomeClassifier` routing decisions here.
- **Vision-model selection or pinning** (which VDU model to use, e.g. `Qwen3-VL-8B-Thinking`) -- tempdoc 374's
  domain, already explicitly deferred by 671.
- **Tesseract preprocessing quality or OCR accuracy itself** once VDU actually runs -- a downstream question,
  only assessable after this tempdoc's fix lands.
- **Re-running tempdoc 624's paid eval** -- that's 624's own spend decision, gated on this tempdoc's fix
  landing first for the scan corpus specifically; not this tempdoc's job to authorize or execute.

## What's already known (inherited from tempdoc 671 -- verify, don't re-derive from scratch)

- `mmproj-F16.gguf` is staged in the shared models root and VDU readiness reports `READY` -- the model side
  is not the blocker.
- Triggering VDU via `POST /api/offline/process` fails with `BrainRuntimeServiceImpl.offlineProcessingTrigger`
  being `null`.
- That field would only be non-null if `OfflineCoordinatorBuilder.build()` succeeded at Head bootstrap, which
  in the observed launch path it apparently didn't -- cited sites: `OfflineCoordinatorBuilder.java:35-38`,
  `ServicePhase.java:149-167`.
- 671's own confidence rating for this specific follow-on: **3/10** (vs. 8/10 for its own shipped, scoped
  fix) -- signaling real, acknowledged uncertainty about the true root cause, not a rubber-stamped diagnosis.
  Treat the file:line citations as a strong lead, not a confirmed conclusion.
- 671 logged this to the observations inbox rather than fixing it (`docs/observations.d/`) -- check there for
  any additional detail before starting fresh investigation.

## Relevant precedent: `standalone-capability-stays-stuck`

From `docs/reference/contributing/agent-postmortems.md` §10 (tempdoc 521 merge T2.5): *"When a class
lazily creates a Capability because a dependency isn't ready yet, and that dependency later holds its own
Capability instance, the late-bind step must bridge them via `addListener` (mirror initial state
synchronously, then forward transitions). 'I created the right object' ≠ 'the right object's state will
be updated.' Pattern at `HeadAssembly.connectKnowledgeServer` (`AppFacadeBootstrap` pre-519)."* This is named
in `.claude/rules/agent-lessons.md` too, as a citable handle. If the real investigation confirms this is the
same shape, the fix is a known recipe, not a fresh design. If it isn't (e.g. the real cause is an ordering
bug or a config default), say so plainly rather than forcing the precedent to fit.

## Recommended model/effort for the design pass

**Sonnet-5, high effort** for the diagnose-and-design step (not necessarily for the mechanical parts of the
implementation once the design is settled). Reasoning: the root cause is already narrowed to specific
file:line sites and a named precedent pattern exists, which would normally argue for a lighter pass -- but
this is the **third** recurrence this session of "a fix was built without confirming it's wired into the
real execution path" (the original eval-corpus materialization bug; the watched-roots leak check; `
build_revision()`'s zero production callers). Given that track record, the design pass specifically earns
extra effort to independently re-verify the `ServicePhase` ordering and confirm the precedent match against
current HEAD, rather than accepting 671's 3/10-confidence citation at face value.

## Suggested acceptance criteria

- A live, end-to-end VDU resolution observed against the real dev-stack: `POST /api/offline/process` returns
  success, and at least the sample documents 671 already identified as stuck show `vdu_status` transition
  off `PENDING`.
- A regression test exercising the fixed bootstrap-wiring path (per this repo's audit-driven-fixes-need-test
  discipline) -- not just a static code read confirming the fix "should" work.
- Tempdoc 624 updated with the real, re-measured scan-corpus result once this lands (whether it clears
  §M.8 item 1 or surfaces a further, different blocker -- report honestly either way).

## Investigation findings (2026-07-02, diagnosis pass -- no design/impl yet)

Static trace against **current HEAD** of the 624 worktree branch, cross-checked against the dev-stack head
logs. All cited file:line are verified against HEAD (they matched 671's citations). **Diagnosis is now
confirmed with high confidence; the precedent match is exact -- but the specific null dependency 671/the
inbox guessed is WRONG.** Detail below so the design pass does not inherit the wrong dependency.

### Confirmed causal chain (bootstrap → permanent null trigger)

1. `HeadlessApp` constructs the Head **unconditionally** with a null Knowledge Server:
   `new HeadAssembly(telemetry, new ConfigManagerBootstrap(), null, settingsStore, sharedWorkerCapability)`
   (`HeadlessApp.java:347-349`) -- the very next log line is *"HeadAssembly started (degraded -- Worker
   connecting in background)."* (`:350`). The Worker is connected **later**, asynchronously
   (`connectWorker` → `bootstrap.connectKnowledgeServer(knowledgeServer)`, `HeadlessApp.java:417-428`).
2. Inside HeadAssembly construction, `knowledgeServer == null` ⟹ `this.knowledgeClient = null`
   (`HeadAssembly.java:313-317`).
3. `ServicePhase` runs during that same construction and is handed the **direct** (null) client, *not* the
   `() -> this.knowledgeClient` supplier that most other consumers get:
   `OfflineCoordinatorBuilder.build(in.inferenceManager(), onlineAiService, in.knowledgeClient(), ...)`
   (`HeadAssembly.java:381-395` passes `this.knowledgeClient` as the 2nd Input arg; `ServicePhase.java:152-154`).
4. `OfflineCoordinatorBuilder.build(...)` hits `if (manager == null || client == null) return null;`
   (`OfflineCoordinatorBuilder.java:35-38`) and returns null **because `client` is null** -- *not* because
   `manager` is null. ⟹ `offlineCoordinator == null` ⟹ `offlineProcessingTrigger == null`
   (`ServicePhase.java:166-167`).
5. That null trigger is captured **by value** into **two** long-lived consumers:
   - `BrainRuntimeServiceImpl(onlineAiService, settingsStore, enterprisePolicy, offlineProcessingTrigger)`
     -- a `final Runnable` field (`ServicePhase.java:239-240`, `BrainRuntimeServiceImpl.java:24-34`).
   - `LocalApiServer.builder(...).offlineProcessingTrigger(offlineTrigger)` where
     `offlineTrigger = coordinator != null ? coordinator::startOfflineProcessing : null` and `coordinator`
     is read once at bootstrap from `headInfra.offlineCoordinator()` (`HeadlessApp.java:352-355, 372`).
6. `connectKnowledgeServer(ks)` (`HeadAssembly.java:970-1057`) later late-binds the real client for
   **search / indexing / documents / agent-tools** (`:977-1012`, incl. the careful `localCap.addListener`
   agent-tool re-registration bridge at `:1025-1034`) and reassembles the service graph -- **but it reuses
   the old inference sub-graph** (`this.services.inference().onlineAi()`, `:1009`) and **never re-runs
   ServicePhase, never rebuilds the VDU `offlineCoordinator`, and never refreshes either captured trigger.**
   So both triggers stay null for the process lifetime.
7. `POST /api/offline/process` → `BrainRuntimeServiceImpl.triggerOfflineProcessing()` →
   `if (offlineProcessingTrigger == null) throw new UnsupportedOperationException("Offline processing not
   available")` (`BrainRuntimeServiceImpl.java:62-65`) → the observed `SERVICE_UNAVAILABLE`.

### Empirical corroboration (dev-stack head logs, 5 boots)

Root log level is INFO (`modules/app-launcher/src/main/resources/logback.xml:55`). Across every captured
boot in `build/headless-data/logs/headless-backend.log` and `tmp/headless-eval-data/logs/headless-backend.log`:
- **Present:** `"Creating InferenceLifecycleManager with port 8082 ..."` (INFO,
  `InferenceDecision`/`BootstrapInferenceFactory.java:90`) ⟹ **`inferenceManager` is non-null**. No
  `"Failed to create InferenceLifecycleManager"` WARN. The inbox already independently confirms AI worked
  live (`ai_activate` succeeded, `readiness.visualDocumentUnderstanding=READY`).
- **Absent:** `"OfflineCoordinator created for VDU batch processing"` (INFO success,
  `OfflineCoordinatorBuilder.java:69`) **and** `"Failed to create OfflineCoordinator"` (WARN,
  `:72`). The only build() exit that logs at neither INFO nor WARN is the `manager==null || client==null`
  early return, which logs at **DEBUG** (`:36`, suppressed at INFO root). By elimination (manager proven
  non-null), that early return fired **on the client==null arm**. This is decisive, not merely suggestive.

### Verdict on the tempdoc's stated hypotheses

- **§scope item 2 -- `standalone-capability-stays-stuck`: CONFIRMED, and at the *exact* named site.** The
  postmortem (§10) literally names `HeadAssembly.connectKnowledgeServer` as the pattern locus. The
  offlineCoordinator is the lazily-constructed capability; its missing dependency (the Knowledge client)
  becomes available at `connectKnowledgeServer`, and the late-bind step bridges search/indexing/agent-tools
  but **omits** the VDU coordinator. This is a genuine *second instance* of the pattern (principle §15 of the
  frontmatter), not a forced fit.
- **Inbox / 671's stated cause -- "`inferenceManager` was null at bootstrap": REFUTED.** The
  `manager` is non-null (BYO contract: `BootstrapInferenceFactory.java:78-100` constructs the ILM even when
  the model/server files are absent; only `JUSTSEARCH_AI_DISABLED`/`JUSTSEARCH_LITE_MODE` or a config-load
  throw yields null, and the dev-runner sets neither). The null dependency is **`knowledgeClient`**, not
  `inferenceManager`. The inbox author explicitly hedged ("Root cause not diagnosed") -- correctly, it turns
  out. Any design that "makes inferenceManager available earlier" would be fixing the wrong thing.

### Scope correction: this is NOT dev-stack-specific

The tempdoc title and 671's inbox both frame this as "the dev-stack launch path." The trace shows the
null-Knowledge-Server bootstrap (`HeadlessApp.java:349` + the "degraded -- Worker connecting in background"
log) is the **universal** Head boot sequence, dev and prod alike -- the Worker is *always* connected
asynchronously after HeadAssembly is built. So VDU offline processing has almost certainly **never worked in
any normal launch**; it only works in unit tests that construct `OfflineCoordinator` directly with a non-null
stub client (`OfflineCoordinatorTest`, `VduConcurrentTriggerTest` via `StubInferenceLifecycleManager`),
which bypass the bootstrap entirely and therefore never exercised this gap. That is why no regression caught
it -- and it sharpens the tempdoc's own acceptance criterion (a regression test must exercise the *real*
bootstrap → connect ordering, not a direct coordinator construction).

### Candidate fix directions (for the design pass -- NOT a chosen design)

Two shapes are on the table; the precedent's literal `addListener`-to-a-Capability recipe does **not** map
cleanly, because the missing dependency here is a `RemoteKnowledgeClient` handle, not a `Capability`'s
state. Recording both so the design pass starts from the right menu, not a mis-transplanted recipe:

1. **Rebuild-on-connect (mirror the existing search/indexing rebuild).** In `connectKnowledgeServer`, after
   `this.knowledgeClient` is set, rebuild the `offlineCoordinator` and re-thread its trigger. *Complication:*
   both consumers (§5 above) hold the trigger by value, so this is insufficient alone -- they must read it
   through an indirection (a `Supplier<Runnable>` / mutable holder) or themselves be rebuilt. This is the
   more invasive but most "complete-the-existing-pattern" option.
2. **Supplier-indirection at construction (make VDU late-bound like everything else).** Pass
   `() -> this.knowledgeClient` into the coordinator build path instead of the direct null value, and have
   `OfflineCoordinator`/`VduBatchProcessor` resolve the client lazily at trigger time. Then the coordinator
   is always built (manager is non-null) and picks up the live client when offline processing runs.
   *Open question the design pass must answer:* do `OfflineCoordinator`/`VduBatchProcessor` use the client at
   **construction** or only at **run**? If construction, this option needs those classes refactored to defer
   client use. (Not yet read -- deliberately out of scope for this diagnosis pass.)

Both options must also decide whether the `LocalApiServer` direct-trigger consumer (`HeadlessApp.java:372`)
is still needed at all or is redundant with the `BrainRuntimeService` route path -- the two-consumer
duplication is itself worth flagging.

## Theorization: framings, solution directions, tradeoffs, and the broader shape (2026-07-02)

Exploratory, pre-design. Records directions and their tradeoffs so the design pass starts from a full menu;
no option is chosen here.

### Reframing the defect surface

The natural first frame is "the VDU coordinator needs the Knowledge client and it wasn't ready." But reading
the VDU object graph shows a sharper frame: **the entire graph touches the client only at *run* time, never
at construction.** `OfflineCoordinator` stores the client in a field and first calls it inside
`startOfflineProcessing()` (`recoverVduProcessing`, `countPendingVdu`, …); `VduBatchProcessor` likewise only
stores it and calls it inside `processPendingFiles()`. Construction of the whole VDU graph is client-free.

Therefore the actual defect surface is **`OfflineCoordinatorBuilder`'s eager guard**
`if (manager == null || client == null) return null` (`OfflineCoordinatorBuilder.java:35`). That guard
conflates two distinct states: *"no Worker will ever exist"* (a permanent, structural absence) and *"the
Worker just hasn't connected yet"* (a transient bootstrap-timing condition that is **always** true at Head
construction, since the Head is built `knowledgeServer=null` by construction). Collapsing "absent" into
"not-yet" is the same tri-state hazard the slice-execution discipline warns about ("don't conflate unknown
with healthy"). The object it refuses to build wouldn't have used the missing dependency until much later
anyway.

### Solution directions (with tradeoffs)

**D1 — Supplier / live-reference indirection (make VDU late-bound like its neighbours).**
Thread the client into the coordinator as a *live reference* (`Supplier<RemoteKnowledgeClient>` resolving to
the composition root's own `this.knowledgeClient`), matching the `() -> this.knowledgeClient` idiom already
used pervasively for search, indexing, and documents. Build the coordinator whenever the inference manager
exists; resolve the client at run time, degrading gracefully if the Worker still isn't ready.
- *Pros:* **reconnect-safe for free** — because it always reads the live field, a Worker crash→restart→
  reconnect needs no extra handling (the field is already "re-assigned on every worker (re)connect"). Adds
  **no new late-binding holder**, so it stays within the deliberate `MAX_LATE_BINDINGS` cap
  (`CompositionRootGuardrailsTest` 4c). Fixes both consumer paths at the single coordinator source.
- *Cons:* changes the field types of `OfflineCoordinator`/`VduBatchProcessor` (client → supplier) or wraps
  them; needs a run-time "Worker not ready yet, skip" branch so a too-early trigger degrades instead of
  NPEs.

**D2 — Rebuild-on-connect (extend the existing `connectKnowledgeServer` rebuild).**
`connectKnowledgeServer` already rebuilds search / indexing / documents / agent-tools once the real client
arrives; add the VDU coordinator to that rebuild and refresh the trigger held by each consumer. Direct
precedent exists: `AiInstallService` was made a volatile field with a setter that `lateBindKnowledgeServer`
calls.
- *Pros:* keeps the coordinator's internals untouched; follows an established connect-time pattern.
- *Cons:* must be **idempotent across repeated reconnects** (not just first connect); must reach **both**
  independent trigger consumers (see below), or they silently diverge; risks adding a mutable holder that
  presses on the `MAX_LATE_BINDINGS` cap the maintainers set intentionally.

**D3 — Minimal guard relaxation.**
Drop the `client == null` arm of the builder guard and hold a nullable client, null-checked at run.
- *Pros:* smallest diff.
- *Cons:* loses the "a Worker is required" intent unless re-expressed at run time; least self-documenting.
  Effectively D1 without the indirection's reconnect-safety, so D1 dominates it.

### The two-consumer duplication is real (and both paths are live)

The null trigger reaches **two independent, both-live consumers** from the same bootstrap-null coordinator:
1. **REST** `POST /api/offline/process` → `InferenceHandlers.handleTriggerOfflineProcessing` → its own
   `offlineProcessingTrigger` field, threaded `HeadlessApp` → `LocalApiServer.Builder` → `CoreApiAssembly`.
   (This is the path the origin observation actually exercised; its 503 came from here, though it was
   attributed to the other class.)
2. **Operation registry** `triggerOfflineProcessing` operation → `TriggerOfflineProcessingHandler` →
   `BrainRuntimeService.triggerOfflineProcessing()` → `BrainRuntimeServiceImpl.offlineProcessingTrigger`,
   threaded through `ServicePhase`.

Because the two triggers are threaded by **separate** paths, a fix that patches only one leaves the other
broken. This is the strongest single argument for locating the fix at the **coordinator source** (a live
reference both paths read) rather than at either threading path — and it invites a secondary question the
design should answer: should these two entry points be collapsed to one, or is the REST/operation split
intentional?

### Broader shape / candidate invariant

The specific bug generalizes to a composition-root invariant worth naming:

> At an **asynchronous composition root** — where a dependency is null at construction and bound later — that
> dependency must be consumed through an **indirection that reads the live reference** (supplier / late-bound
> holder), never **captured by value**. Value-capture silently freezes whatever the reference was at
> construction (here, null), and no later binding can reach the frozen copy. The supplier form is
> additionally **reconnect-safe**; a mutable holder is acceptable but must be idempotent across rebinds and
> must reach *every* downstream consumer that captured the value.

The Head's Worker (Knowledge) client is the canonical such dependency: it is *structurally* always null at
HeadAssembly construction and bound asynchronously at `connectKnowledgeServer`. Every worker-dependent
service already lives on the correct side of this line (supplier-threaded or connect-time-refreshed) **except
the VDU coordinator**, which was value-captured. So this is not a novel failure — it is the one node that was
missed when the surrounding neighbourhood was converted to late-binding. It is a second concrete instance of
the previously-named `standalone-capability-stays-stuck` pattern, and it sits alongside the existing
`BootstrapLateBindings` infrastructure as the thing that infrastructure exists to prevent.

### Enforcement question (raise, don't design)

Given the repo's preference for turning a proven bug-class into a structural guard, the design pass could
weigh whether this class is *detectable* rather than only fixable:
- A **post-connect witness**: once the Worker reaches READY, assert that each worker-dependent capability
  (VDU trigger included) is non-null / rebound. This fits the existing live-witness / registry-snapshot style
  and would have caught this at boot rather than in an eval months later.
- An **ordering-fidelity test** generalized from this tempdoc's own acceptance criterion: build the Head with
  a null Knowledge Server, run `connectKnowledgeServer`, and assert the trigger is now live — a shape any
  future worker-dependent service could reuse.
- Tension to respect: the `MAX_LATE_BINDINGS` cap signals the maintainers want to *limit* late-binding
  holders, not multiply them. That argues an enforcement mechanism should push toward the **supplier** form
  (no new holder) as the default, and treat each new mutable holder as a reviewed exception.

Any enforcement work is strictly optional relative to this tempdoc's stated scope (fix VDU + test + report to
624); it is recorded as a direction, not a commitment.

### Risks & hidden assumptions the design must not skip

- **Reconnect, not just first-connect.** `connectKnowledgeServer` can fire again on Worker crash-recovery
  (the Worker bootstrap field is re-assigned on every reconnect). Any fix must survive a second connect. D1
  gets this for free; D2 must be explicitly idempotent.
- **"Manager present" ≠ "LLM online."** VDU needs the vision model actually loaded, which the coordinator
  already handles at run time (`switchToOnlineMode`, `VduCapabilityState.block(REASON_AI_OFFLINE)`). The
  wiring fix should *not* try to also solve model-loading; that stays run-time.
- **Test-fidelity trap.** The existing `OfflineCoordinatorTest` / `VduConcurrentTriggerTest` construct the
  coordinator directly with a stub client and pass — which is exactly why they never caught this. A
  regression test that repeats that shape would be green-for-the-wrong-reason. The test must drive the *real*
  ordering (null-KS construction → connect → assert live trigger), per this repo's audit-driven-fixes-need-
  test and unreachable-seed-green cautions.
- **Keep the actual consumer need in view.** The downstream need (tempdoc 624's scan corpus) is "VDU actually
  runs against the corpus," which this trigger enables but is not the only conceivable route to; the fix
  should re-verify end-to-end that text reaches the index, not merely that the trigger stopped throwing.

## Long-term design (2026-07-02) -- settled direction, general (not implementation-level)

The theorization section above lays out the menu; this section commits to the direction and states why its
scope is exactly right for the problem the tempdoc has -- no smaller, no larger.

### The design: conform to the composition root's existing live-supplier seam

The correct long-term shape is **not a new mechanism**. The Head composition root already has the exact seam
this problem needs, and VDU is the one worker-dependent service that failed to use it:

- `ServicePhase.Input` already carries a **live supplier** for a worker-dependent dependency:
  `Supplier<IndexingService> indexingServiceSupplier`, wired at the composition root as
  `() -> this.knowledgeClient`. That supplier reflects the asynchronous `connectKnowledgeServer` rebind (and
  any later Worker reconnect) because it dereferences the live field at use-time.
- The same `Input` also carries the **direct** `RemoteKnowledgeClient knowledgeClient` value -- and *that* is
  the field the VDU builder was handed. At Head construction the Worker is, by construction, not yet
  connected (`HeadlessApp` builds the Head `knowledgeServer=null`), so the direct value is null, and the
  coordinator is frozen to that null for the process lifetime.

The design is therefore to **thread the Worker client into the VDU build path as a live client-typed
supplier, parallel to the existing `indexingServiceSupplier`**, and to have the VDU graph resolve it at run
time (which is the only time it uses the client -- both `OfflineCoordinator` and `VduBatchProcessor` merely
store the handle at construction and first call it inside `startOfflineProcessing()` / `processPendingFiles()`).
The builder's structural precondition stays (build only when the inference manager exists); the *timing*
precondition (`client != null right now`) is removed, since it conflated "no Worker will ever exist" with
"the Worker hasn't connected yet."

This single change:
- Fixes **both** offline-trigger consumer paths at their shared source (the REST path through
  `InferenceHandlers` and the operation-registry path through `BrainRuntimeService`), because both read the
  one coordinator -- no per-path patching, no risk of the two diverging.
- Is **reconnect-safe by construction**: whether the Worker restart budget (tempdoc 627: up to 3 supervised
  restarts) reuses the client object or reassigns the field, a live supplier always reads the current
  reference. A value-capture or a one-shot rebuild-at-first-connect would not survive a field reassignment.
- Adds **no new late-binding holder**, so it stays within the deliberate `MAX_LATE_BINDINGS` cap
  (`CompositionRootGuardrailsTest` 4c) -- it conforms to that guardrail's intent rather than pressing on it.

### Prior art (this is known craft, not a bespoke invention)

The "consume a not-yet-ready dependency through a live provider instead of capturing its value at
construction" shape is a standard, long-settled dependency-injection idiom -- e.g. provider/lazy injection
(`Provider<T>` / `Lazy<T>` in common DI containers, `ObjectProvider` / `@Lazy` in others), and the
value-capture failure it avoids is the classic "temporal coupling in the constructor" anti-pattern. This
codebase does its composition by hand rather than via a DI container, so the design is not adopting any
external library -- it is applying that well-understood idiom using the repo's own live-supplier seam
(`() -> this.knowledgeClient`). Recorded so an external reader recognizes the shape as ordinary craft; no
external code or text is copied, so there is nothing to attribute. (No internet-research pass was run for
this design: the wiring pattern is stable, established engineering; the only genuinely fast-moving
adjacent topic -- multimodal/vision document understanding itself -- is explicitly out of scope, owned by
tempdocs 374/607.)

### Why not the other directions (scope justification)

- **Rebuild-on-connect (D2)** would add connect-time orchestration and a mutable trigger holder that must be
  made idempotent across the real restart budget *and* must reach both consumer paths independently. That is
  more structure than the problem requires, and it pushes on the late-binding cap. Rejected as the primary
  shape (though `connectKnowledgeServer` is where a witness-style assertion could later live -- see reach).
- **Adding VDU to `BootstrapLateBindings`** would misuse that seam. `BootstrapLateBindings` is for
  *forward-in-bootstrap* controller back-refs published once by a synchronous producer (`LocalApiServer`
  after it builds controllers). The Worker client is a different late-bind axis -- it is already a live
  volatile field with a live supplier. Reuse that supplier; do not route VDU through the controller-back-ref
  holder.
- **The §10 `addListener` bridge recipe** does not apply here (see reach) -- that recipe is for the
  stateful-Capability variant, and the client is a plain handle.

### Scope boundaries (deliberately excluded)

- **Not** collapsing the two offline-trigger entry points (REST vs operation registry) into one. Both are
  live and both are fixed by the shared-source design; whether they *should* be unified is a separate
  cleanup, recorded as an open question, not required here.
- **Not** building a generalized worker-dependent-service registry or a boot-time enforcement gate. That is
  a reach candidate (below), recorded not built.
- **Not** touching model-loading / online-mode (already handled at run time by the coordinator) or
  extraction routing (tempdoc 607's authority).

### Acceptance-test shape (general)

Drive the **real ordering**, not a direct construction: build the Head composition with a null Knowledge
Server, run the Worker-connect step, then assert the offline trigger is live and invokable via **both**
consumer paths. The existing direct-construction VDU tests (`OfflineCoordinatorTest`,
`VduConcurrentTriggerTest`) must not be the acceptance witness -- they pass today precisely because they
bypass the bootstrap ordering that harbours the bug (`unreachable-seed-green`). End-to-end, re-verify that
text actually reaches the index for the previously-stuck documents, not merely that the trigger stopped
throwing.

### Reach -- principle, refinement, and candidate scope (recognize now, do not build now)

**This design conforms to an existing seam and instantiates an existing named principle**, so it is not a
parallel invention:

- **Seam it conforms to:** the composition root's live-supplier threading (`() -> this.knowledgeClient`),
  already used for search, indexing, documents, agent-tools, `AiInstallService`, and diagnostics.
- **Principle it instantiates:** `standalone-capability-stays-stuck` (agent-postmortems §10, tempdoc 521).
  That case and this one share the *same site and same trigger* -- a Head built `knowledgeServer=null` on
  the async path, with a dependency bound only at `connectKnowledgeServer`.

**Refinement worth recording** (the one thing this case adds to the named principle): the fix *shape* depends
on **what the late-bound dependency is**:
- **A Capability with its own state machine** (521's `WorkerCapability`): bridge the two instances via
  `addListener` -- mirror initial state synchronously, then forward transitions.
- **A plain handle/reference with no state** (this tempdoc's `RemoteKnowledgeClient`): consume it through a
  **live supplier** that reads the field the async connect updates. There is only one reference, so there is
  nothing to "bridge" -- there is a value not to *capture*.

Both are the same invariant, stated plainly and generally:

> **At an asynchronous composition root, a dependency that is null at construction and bound later must be
> consumed through a live indirection that reads the reference the connect step updates -- never captured by
> value at construction.** ("I created the right object" is not "the right object's reference will be
> re-read.")

**Candidate scope / where else it applies / does code already violate it:**
- Every worker-dependent service in the Head is subject to this invariant. All the known ones already
  conform; **VDU is the lone known violator** (this tempdoc).
- The **latent hazard surface** is precise and greppable: `ServicePhase.Input` exposes *both* a direct
  `knowledgeClient` and a supplier form. Any consumer that reads the **direct** `in.knowledgeClient()` at
  construction (rather than a supplier) and retains it is a candidate violator. A one-time audit of that call
  set is the way to confirm no third instance hides -- exactly the same "one `git grep` suffices" discipline
  as `wire-emitter-elision` (§8).
- **Recognized, not built:** the structural way to make this bug-class *unrepresentable* would be to remove
  the direct `knowledgeClient` from `ServicePhase.Input` entirely, leaving only the supplier form -- then
  value-capture is impossible by construction. That edit is within this problem's own surface and is worth
  weighing during implementation, but it is an option to consider, not a mandate, and building a broader
  enforcement mechanism (e.g. a boot-time witness asserting each worker-dependent capability is live once the
  Worker reaches READY) is explicitly deferred: the present problem does not require it, and the deliberate
  `MAX_LATE_BINDINGS` cap signals the maintainers want fewer late-binding constructs, not a new registry of
  them.

## Pre-implementation de-risking (2026-07-02) -- read-only investigation + existing tests, no code changed

Ran a confidence-building pass over the design's implementation-time assumptions before writing any code.
Findings (each closes a specific pre-identified uncertainty):

- **Composition-root guardrails do not reject the threading (A).** `CompositionRootGuardrailsTest` rule **4a**
  caps `MAX_OUTPUT_FIELDS=26` on phase **Output** records and `ServicePhase.Output` is *already pinned at 26*
  -- so the fix must add its supplier to `ServicePhase.Input` (no cap) and **must not add a field to
  `Output`**. The rule that would police a `Supplier<T>` in a phase Input (**4f**, "no Supplier escape
  outside late-binding holders") is `@Disabled`, and its own comment names "phase Input records" as an
  allowlisted supplier origin. **4c** (late-binding holder cap) counts only `BootstrapLateBindings` fields --
  untouched, since the design adds no holder. **4g** requires `*ServiceImpl` construction inside
  `bootstrap.phases..`; `OfflineCoordinator` is not a `*ServiceImpl` and its builder already lives there.
  `AppServicesWorkerGuardrailsTest` only bars env/sysprop reads + `MappedByteBuffer` -- neither is added.
- **One fix repairs both trigger paths (B) -- proven, not assumed.** `HeadInfraRegistry` is built from
  `this.offlineCoordinator`, which is exactly `serviceOut.offlineCoordinator()` (`HeadAssembly.java:405,
  1127-1128`); the field is assigned during construction and read by `HeadlessApp` afterwards. So a coordinator
  that is non-null at bootstrap makes the REST-path trigger (via `HeadInfraRegistry` → `LocalApiServer` →
  `InferenceHandlers`) and the operation-path trigger (via `ServicePhase` → `BrainRuntimeService`) both live
  from the single source.
- **Downstream path is live, not a second dead-end (C).** `OfflineCoordinator.startOfflineProcessing` →
  `VduBatchProcessor.processPendingFiles` performs real Worker gRPC work (`countPendingVdu`,
  `queryPendingVduDocIds`, `markVduProcessing`, per-doc processing) behind VRAM / mmproj / circuit-breaker
  guards. The docs 624 cares about are `vdu_status=PENDING`, i.e. exactly what `queryPendingVduDocIds` returns.
  Residual end-to-end risk is *run-time preconditions*, not wiring: the vision model must actually be online
  when the trigger fires (model-selection is tempdoc 374's domain) -- to be confirmed in the fix phase's live
  run, not by this wiring change.
- **Blast radius is small and contained (D).** Production sites: `OfflineCoordinatorBuilder.build` (the sole
  constructor of `OfflineCoordinator` + `VduBatchProcessor`) and its one caller `ServicePhase.java:153`.
  Tests touching the surface: `OfflineCoordinatorTest` (direct construction, `:83`) and `VduConcurrentTriggerTest`.
  A `client → Supplier<RemoteKnowledgeClient>` change stays within ~3 production classes + `ServicePhase.Input`
  + the `HeadAssembly` threading site, plus those 2 tests. **Baseline of all touched tests is GREEN** (ran
  `:modules:app-services:test` filtered to the VDU/composition classes; BUILD SUCCESSFUL, tests executed).
- **Degradation vocabulary + reconnect (E, F).** `VduCapabilityState` has reason codes for AI-offline /
  VRAM / mmproj / circuit-open but **none for "Worker not connected yet"** -- so a run-time null-guard-skip in
  `startOfflineProcessing` is the proportionate degradation (a new reason code is optional polish, not
  required). `connectKnowledgeServer` has exactly **one** production caller (`HeadlessApp.java:428`), invoked
  once at initial Worker connect; a Worker *restart* is handled beneath a persistent bootstrap/client, so the
  live supplier `() -> this.knowledgeClient` is reconnect-safe by construction.

Net: no surprises surfaced; the design is guardrail-safe, single-source-correct for both consumer paths,
downstream-live, and low-blast-radius on a green baseline. The one residual (vision model actually online at
trigger time) is a fix-phase live-verification item, not a wiring unknown.

## Implementation and validation (2026-07-02)

Shipped the design exactly as settled: `RemoteKnowledgeClient` threaded as a `Supplier<RemoteKnowledgeClient>`
through `OfflineCoordinator`, `VduBatchProcessor`, `OfflineCoordinatorBuilder`, and `ServicePhase.Input`
(new field, `Output` untouched — respects the guardrail's `MAX_OUTPUT_FIELDS=26` pin), wired at `HeadAssembly`
as `() -> this.knowledgeClient` (the same live reference already used for `indexingServiceSupplier`). The
`client == null` bootstrap-timing guard is gone; the coordinator now builds whenever the inference manager is
non-null and resolves the client lazily at every use site, with a safe no-op/zero fallback when it's not yet
connected.

**Test changes**: the one direct-construction test (`OfflineCoordinatorTest`) updated to pass a supplier. A
new regression test (`HeadAssemblyTest.offlineCoordinatorBuildsAtBootstrapAndResolvesClientAfterConnect`)
drives the real bootstrap → connect ordering (not a direct construction, which would bypass the bug per
`unreachable-seed-green`): builds the Head with `knowledgeServer=null`, asserts the coordinator is non-null at
bootstrap, connects a mocked Worker client, and verifies `startOfflineProcessing` resolves and calls the
live (post-connect) client.

**Verification, in order:**
1. `spotlessApply` + `build -x test` — clean compile, no other call sites broke (only one production
   `ServicePhase.Input` construction site existed).
2. Targeted unit tests (`OfflineCoordinator*`, `VduConcurrentTrigger*`, `HeadAssembly*`,
   `CompositionRootGuardrails*`, `AppServicesWorkerGuardrails*`, `BrainAssembly*`) — green, including the new
   regression test (confirmed executed via the JUnit XML, not just "0 failures" from a skip).
3. Full `:modules:app-services:test` and full `./gradlew.bat build` (all modules) — green, no regressions.
4. **Live dev-stack verification** (this worktree's own fresh dist, `distFrom` pointed at this worktree per
   the known stale-jar pitfall): `POST /api/offline/process` returned `{"success":true,"message":"Offline
   processing started"}` (previously permanent 503). Backend logs showed the full real chain fire for the
   first time: `"OfflineCoordinator created for VDU batch processing"` (the INFO success line that was
   previously absent on every boot) → `"Starting offline processing"` → `"Pending work: 438 VDU files, 5
   embeddings"` → llama-server switched online with the vision model (`Qwen3.5-9B`, mmproj loaded) →
   `"VDU completed (1/100): pellker298.png"`, `"VDU completed (2/100): pellker44.png"`. The
   `visualDocumentUnderstanding` readiness component transitioned to `READY`; `ai.state` to `READY`.
5. **Live browser verification** (real UI, `localhost:5173`, dev-stack running): searched `pellker298` — the
   result now shows real VDU-extracted body content ("The image provided is too blurry and pixelated to read
   any text...") instead of a zero-content filename-only match, with the connection-status bar showing
   "Online — Qwen Qwen3.5-9B". This is exactly the corpus from tempdocs 671/624
   (`datasets/golden/synth-scan-v1/corpus-dir`) — the fix was verified against the real blocked corpus, not a
   synthetic stand-in.
6. Stopped the dev stack deliberately after confirming real completions, rather than running the full
   ~438-file / ~90-minute batch to completion on the shared stack. A full corpus re-run and re-measured
   nDCG@10 for `synth-scan-v1` is 624's own spend decision (672's own non-goals), not required to validate
   this wiring fix.
7. Reported back into tempdoc 624 (its own addendum, 2026-07-02): the structural blocker is resolved; the
   scan corpus's §M.8 bar remains open only pending the willful decision to spend on a full re-run, not on
   any remaining mechanism defect.

## Named-sample-document confirmation (2026-07-02) -- closing the literal acceptance-criterion gap

A critical review of the work above found one honest gap: the acceptance criteria (§Suggested acceptance
criteria, goal item 3) ask specifically for `vdu_status` to transition off `PENDING` for "at least the
previously-stuck sample documents from 671's own live-verification pass" -- named there as `olmby1.png` and
`rellgrove4.png`. The initial live verification above confirmed the mechanism generally (via `pellker298.png`
/ `pellker44.png`, whichever documents the shuffled corpus queue surfaced first), not those exact two
documents.

**Closed directly against the named documents.** Running the fix against the shared 450-doc corpus in queue
order to reach these two specific documents would have cost 60-90+ minutes of unpredictable, unbounded batch
processing (the Worker's pending-VDU query is an unsorted Lucene `TermQuery` over a corpus the generator
explicitly shuffles -- no way to predict their position). Instead: started a second, fully isolated dev-stack
instance against a fresh, empty data directory, ingested only `olmby1.png` and `rellgrove4.png` (the exact
same files, via `POST /api/knowledge/ingest`), and triggered the same production `POST /api/offline/process`
path against that 2-document pending set. This exercises the identical fixed code path with no per-document
special-casing anywhere in the diff -- the isolation only removes the other ~440 unrelated documents from the
search space, it does not change the mechanism under test.

**Result, both API and browser confirmed:**
- `olmby1.png`: `content_preview` = *"The text in the image is too blurry and distorted to be read accurately.
  It appears to be a block of dense, small print, possibly from a book or document, but the characters are
  indistinct and cannot be deciphered."* -- confirmed via `POST /api/knowledge/search` and live in the real
  search UI (`localhost:5173`).
- `rellgrove4.png`: `content_preview` = *"The image provided is too blurry and pixelated to read any text
  clearly. It appears to be a low-resolution or heavily compressed image of a document, possibly containing
  paragraphs of text, but the characters are indistinct and cannot be..."* -- same dual confirmation.

Both transitioned off `PENDING` (`visualTextNeededCount` 2 → 0) within roughly a minute of triggering, using
the real production trigger, closing the tempdoc's literal acceptance criterion. The isolated verification
data directory was scratch-only (gitignored `tmp/`) and has been removed; no production code or test changed
in this pass.

**Side finding, logged and out of scope:** while investigating queue throughput, found that
`InferenceLifecycleManager`/`VduBatchProcessor` restarts `llama-server` (full stop → start → health-wait,
~10-12s) for **every individual document** during a VDU batch, not once per batch -- observed 1:1 in the
logs (70 restart events for 67 completions in one run), driving effective throughput down to ~3 docs/min
regardless of actual inference cost. This is a real, pre-existing inefficiency, unrelated to this tempdoc's
wiring fix and out of its scope (model-loading/performance is tempdoc 374/640-L's domain) -- logged to the
observations inbox, not fixed here.

## Status

**Implemented and validated** (2026-07-02). Full arc: diagnosis confirmed the root cause as
`knowledgeClient==null` at `ServicePhase` time (async Worker connect) — a confirmed
`standalone-capability-stays-stuck` instance at `HeadAssembly.connectKnowledgeServer`; the inbox's
"`inferenceManager` null" guess was refuted; scope widened to all launch paths, not just dev-stack.
Theorization recorded three solution directions, the two-live-consumer duplication, and the enforcement
question. Long-term design settled on conforming to the composition root's existing live-supplier seam
(thread the Worker client the way `ServicePhase.Input` already threads `indexingServiceSupplier`) —
instantiating the `standalone-capability-stays-stuck` principle's handle-variant (live supplier, not the
Capability-variant `addListener` bridge). Pre-implementation de-risking confirmed the guardrails were safe,
the single fix repairs both trigger paths, the downstream path is live, blast radius is small, and the design
is reconnect-safe. **Implementation shipped and live-verified end-to-end** (§Implementation and validation
above): the fix compiles, all existing + new tests are green with no regressions, `POST
/api/offline/process` now succeeds instead of the permanent 503, VDU genuinely runs against the real blocked
corpus (`datasets/golden/synth-scan-v1`), extracted text lands in the index, and the result is visibly
searchable in the real UI. **The literal acceptance criterion is closed** (§Named-sample-document
confirmation above): the exact two documents 671's own live-verification pass named — `olmby1.png` and
`rellgrove4.png` — were confirmed, via the real production trigger and the real browser UI, to transition
off `PENDING` with genuine VDU-extracted content. Reported back into tempdoc 624. Remaining work — a full
corpus re-run and re-measured nDCG@10 — is 624's own spend decision, explicitly out of this tempdoc's scope
(see §Explicit non-goals). No open items remain within this tempdoc's own stated scope.

## Post-implementation research: polish, extension, and UX ideas (2026-07-02)

With the wiring fix shipped and closed, this section is exploratory follow-on research -- not a plan, not
scoped work, nothing here is committed. It asks: now that VDU offline processing actually runs, what's worth
doing next, purely as ideas for a future tempdoc/decision. Findings below are ranked by how concretely they
were verified, not by importance.

### Finding 1 (confirmed, high value): a second, independent instance of the same bug class

Tempdoc 672's own "Reach" section recommended a one-time audit of `ServicePhase.Input`'s direct
`in.knowledgeClient()` call sites, since any of them retaining a value captured at bootstrap (rather than
reading a live supplier) is a candidate repeat of the exact bug this tempdoc fixed. Running that audit found:

- `AgentToolFactory.build(...)` (`ServicePhase.java:174`) also reads `in.knowledgeClient()` directly and binds
  a method reference (`knowledgeClient::updateDocumentPaths`) at construction. **Not a live bug** -- verified
  that `HeadAssembly.connectKnowledgeServer` already rebuilds agent tools correctly via a *different*, also-
  valid mechanism: `AgentToolHandlers.registerLateBound(...)` is called at connect time with the live client
  and constructs fresh `SearchTool`/`BrowseTool`/`FileOperationsTool` instances internally, superseding the
  stale bootstrap-time ones. This is the D2 (rebuild-on-connect) shape from this tempdoc's own design menu,
  applied correctly elsewhere in the codebase -- a second data point that D2 is a legitimate alternative to
  D1, not just a rejected option.
- **`WorkerFeatureCache workerFeatureCache` (`ServicePhase.java:200-201`) is a confirmed live bug of the
  identical shape.** `in.knowledgeClient() != null ? in.knowledgeClient()::getLastKnownOnnxModels : List::of`
  is evaluated once at bootstrap (client is null, per this tempdoc's whole diagnosis) and permanently becomes
  `List::of` -- an empty list, forever, for the life of the process. Unlike `AgentToolFactory`, nothing rebuilds
  it at `connectKnowledgeServer` (confirmed by grep: the field is threaded once, `HeadlessApp.java:377`, and
  never reassigned). **Real user-facing impact, traced:** `RuntimeActivationService.resolveSessionActive` and
  its ONNX-model-discovery logic (`RuntimeActivationService.java:268-287`) read
  `workerFeatureCache.getOnnxModels()` as one of three sources for "does the Worker report this ONNX model
  (reranker/embedding/SPLADE/NER) as actively loaded" -- a signal presumably surfaced during the AI-install /
  model-status flow. Because the cache is always empty, that source silently contributes nothing, every
  launch, for the entire life of this feature so far. This wasn't found by looking for it -- it's a second,
  independent confirmation that the invariant this tempdoc named is a live, recurring hazard in this specific
  corner of the composition root, not a one-off. Worth its own small follow-up tempdoc (same shape as this
  one: thread `WorkerFeatureCache` as `() -> this.knowledgeClient != null ? ... : List.of()` instead of a
  bound value) -- not fixed here, since this pass is research-only.

### Finding 2 (confirmed, high value): VDU mode is entered/exited per-document, not per-batch

Already logged as an out-of-scope observation during live verification; restated here with the root cause
now understood precisely. `VduProcessor.process(Path)` calls `lifecycleControl.enterVduMode()` at its own
start and `exitVduMode()` in its own `finally` (`VduProcessor.java:169,264`) -- and this method is called
**once per document** from `VduBatchProcessor.processPendingFiles()`'s loop. `enterVduMode()`/`exitVduMode()`
each force a full `llama-server` restart (`RestartPolicy.RESTART_ALWAYS`,
`InferenceLifecycleManager.java:509-538`) to apply/remove vision-safe flags (`-np 1, --cache-ram 0`) -- a
~10-12s stop→start→health-wait cycle, confirmed 1:1 with completions in the logs (70 restarts for 67
completions in one run).

Two things worth separating, since only one is a real cost:
- The *flags themselves* are very likely a genuine, correct safety measure -- `-np 1`/`--cache-ram 0` look
  like they reduce parallel-slot and prompt-cache memory pressure specifically for vision inference, which is
  more VRAM-hungry than text. A brief check of llama.cpp's own docs
  ([multimodal.md](https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md),
  [server README](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)) didn't surface a
  way to toggle `--cache-ram`/`-np` on a running server without a restart -- these are server-startup CLI
  flags, not per-request parameters, in current llama.cpp. So *some* restart when entering/leaving VDU work is
  plausibly unavoidable with this backend.
- **The cost is multiplied by document count for no reason.** Since the batch loop is entirely inside
  `VduBatchProcessor.processPendingFiles()`, and VDU mode entry/exit lives inside the per-document
  `VduProcessor.process()` call, moving the `enterVduMode()`/`exitVduMode()` calls to wrap the *whole batch
  loop* instead (called once in `processPendingFiles()` before the `for` loop, once after) would collapse
  N restarts into 1, independent of the vision-safety-flag question above. At the observed ~10-12s/restart,
  this is roughly the entire gap between the ~3 docs/min measured and whatever the real per-image inference
  cost is (plausibly several times faster). This is the single highest-leverage, most concretely evidenced
  performance idea in this research pass -- and it's a small, contained change (move two method calls one
  level up the call stack), not a redesign. Not fixed here (out of 672's scope; would belong to whichever
  tempdoc owns VDU throughput, e.g. adjacent to 374/640-L).

### Finding 3 (confirmed, highest practicality impact): nothing in the product currently triggers VDU for a real user

This is the most important finding for "is this fix practical for a user" specifically. Traced every call
site of `startOfflineProcessing()` and the `TRIGGER_OFFLINE_PROCESSING` operation:
- **No automatic/idle trigger exists anywhere in production code.** Searched for idle-detection, scheduled
  tasks, or any periodic invocation of offline processing -- none found. The only `scheduleAtFixedRate`-style
  jobs in the codebase are for unrelated telemetry/GPU-sampling/index-commit concerns.
- **The frontend has a dead API binding and no caller.** `modules/ui-web/src/api/domains/inference.ts` defines
  `triggerOfflineProcess(...)`, but grepping the entire `ui-web` tree for both that function name and the
  operation ID `TRIGGER_OFFLINE_PROCESSING` found **zero UI components that call it**. The REST endpoint and
  the operation-registry entry both exist and both now work correctly (this tempdoc's fix) -- but there is no
  button, no menu item, no automatic condition anywhere in the shipped product that a real end user (not an
  agent, not a developer with `curl`/MCP tools) could use to make VDU actually run.
- **Consequence:** this tempdoc's own "Overarching goal" states the broader intent is "make VDU a genuinely
  working fallback for degraded/scanned documents, not a configured-but-inert capability." The wiring fix
  makes the *mechanism* correct. It does **not**, by itself, make VDU non-inert for a real alpha user, because
  nothing in the product invokes the now-working mechanism outside of an explicit agent/API call. A real
  user's scanned documents would sit at `vdu_status: PENDING` indefinitely today, exactly as they did before
  this fix, unless something -- an idle trigger, a button, a scheduled job -- is added to actually call it.

### Idea sketch: closing Finding 3 (exploratory only, no design committed)

A brief look at general UX patterns for background/idle AI processing in local-first and async-workflow tools
(not JustSearch-specific research; general grounding only) suggests a fairly conventional, low-risk shape:
- **Idle-triggered auto-run**: the app already tracks user activity elsewhere (mentioned in existing docs);
  wiring `startOfflineProcessing()` to fire after N minutes of idle (mirroring the coordinator's own javadoc,
  which already says "Called when user goes idle or manually triggers 'Process Now'" -- suggesting this was
  the *original* intent, just never wired up) is the most direct way to make the fix practically useful without
  requiring any user action at all.
- **A manual "Process Now" affordance**, wiring the already-defined (but currently orphaned)
  `triggerOfflineProcess` binding to a real button -- likely near wherever indexing/AI status is already
  surfaced (`readinessNotice.ts` already models VDU readiness state, so a natural home exists).
  General pattern grounding: [LogRocket's UI patterns for async
  workflows](https://blog.logrocket.com/ux-design/ui-patterns-for-async-workflows-background-jobs-and-data-pipelines/)
  and [NN/g on designing for waits and
  interruptions](https://www.nngroup.com/articles/designing-for-waits-and-interruptions/) both converge on: a
  persistent, low-attention status indicator during the run; a salient, discoverable completion signal (users
  don't watch background work); and an explicit cancel/abort control so a long unattended run doesn't feel
  like it locked the user out of control. `OfflineCoordinator.isProcessing()` and `vduCapabilityState()`
  already exist as the data this would need -- no new backend surface required, only frontend wiring.
- **Progress visibility during a run**: currently a triggered batch is entirely invisible to the UI beyond the
  generic `visualTextExtraction` readiness component. A small, honest progress affordance ("processing N of M
  scanned documents") would directly address the "salient during, salient at completion" pattern above, and
  would also make Finding 2's restart-per-file cost *visible* rather than silently slow -- which independently
  strengthens the case for fixing Finding 2 first (a visible "1 doc per 12 seconds" counter is a much more
  obvious problem than a silent one).

None of Findings 1-3's follow-ups are required by, or block, this tempdoc's own closed scope. They're recorded
here as the concrete, evidenced starting point for whoever picks up VDU practicality next.

**Sources consulted** (external, general grounding only — none copied verbatim, no license/attribution concern):
[llama.cpp multimodal docs](https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md),
[llama.cpp server README](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md),
[LogRocket — UI patterns for async workflows](https://blog.logrocket.com/ux-design/ui-patterns-for-async-workflows-background-jobs-and-data-pipelines/),
[NN/g — Designing for Long Waits and Interruptions](https://www.nngroup.com/articles/designing-for-waits-and-interruptions/).

## Long-term design theorization for Findings 1-3 (2026-07-02) — still research, nothing committed

The prior section recorded *what's wrong*; this section asks *what the correct long-term shape looks like*,
per finding, and investigates whether the codebase already has a usable design to extend before proposing
anything new. All three conclusions below turn out to be extensions of designs that already exist in this
codebase — none require new structure. Still purely exploratory: no code changes in this pass, no scope added
to this tempdoc's own (already closed) deliverable.

### Finding 1 (`WorkerFeatureCache`) — no new design needed

This isn't a design question. The correct shape is the exact fix this tempdoc already shipped for VDU: thread
`WorkerFeatureCache` through `ServicePhase.Input` as a live supplier (`() -> this.knowledgeClient != null ?
this.knowledgeClient::getLastKnownOnnxModels : List.of()`), not a value bound once at bootstrap. Recorded here
only to close the loop; the design is 672's own, already-settled design, applied to a second site.

### Finding 2 (VDU restarts `llama-server` per document) — conform to the sibling phase's own pattern

**Investigated first, per the instruction to prefer extending an existing usable design:** does this class
already have a correct precedent for "batch-scoped, not item-scoped, mode transition"? Yes — in the *same*
class. `OfflineCoordinator.processEmbeddingPhase()` calls `inferenceManager.switchToIndexingMode()` **once**
per phase invocation, then lets the Worker's own autonomous backfill loop process the whole batch under that
one mode. `processVduPhase()` was meant to follow the identical shape (call the VDU-equivalent mode-entry
once, delegate the whole batch, mode-exit once) but the entry/exit calls ended up one layer too deep — inside
`VduProcessor.process(Path)`, which runs once per document — so the restart fires 1:1 with document count
instead of 1:1 with batch invocations.

The correct long-term design is exactly this: move `enterVduMode()`/`exitVduMode()` out of
`VduProcessor.process(Path)` and into `VduBatchProcessor.processPendingFiles()`, wrapping the whole
document loop — mirroring `processEmbeddingPhase()`'s own shape one method away. The scope is small and
bounded on purpose: this is not a redesign of the VDU pipeline, and does not touch whether/why vision-safe
server flags (`-np 1, --cache-ram 0`) are needed at all (that remains a llama.cpp-server-lifecycle question,
separately reasonable) — only *where in the call stack* the enter/exit calls live. The exception-safety shape
already exists to copy too: `startOfflineProcessing()`'s outer `try/finally` is what makes
`processing.set(false)` unconditional today; the moved `enterVduMode()`/`exitVduMode()` pair needs the same
treatment at the batch level (try/finally around the whole loop, not per-file) so a single document's failure
mid-batch still restores normal server config.

### Finding 3 (nothing triggers VDU for real users) — this is tempdoc 630's own scope, previously unreachable

**Investigated first:** does an idle/background-work-arbitration design already exist? Yes, and it is mature,
merged, and load-bearing today — `LoopPacingPolicy` + `WorkerSignalBus` (worker-services/worker-core), built
by tempdoc 630 ("OS energy/activity-aware background work"), already autonomously gates the Worker's own
embedding/NER/SPLADE backfill loop on exactly three signals:
1. **User activity** — `WorkerSignalBus.isUserActive()`, fed by `MainSignalBus.writeActivity()`, which the
   Head already calls on every real search/suggest/folder-listing request
   (`KnowledgeSearchController.java:249,739,777,821` — genuine, already-shipped, request-driven activity
   tracking, not something to invent).
2. **OS energy intent** — `EnergyState` (tempdoc 630), Head-native (`app-util`), already wired into
   `LoopPacingPolicy.shouldRunBackfill`'s `energyReduced` parameter and already surfaced via
   `PowerStatusView`.
3. **GPU/LLM exclusivity** — `WorkerSignalBus.isMainGpuActive()`, so the Worker's own GPU-touching backfill
   yields when the Head's LLM (Online mode) has the GPU.

**Tempdoc 630 §A ("Power-aware background work") already explicitly named VDU as a member of the workload
family this arbitration is meant to cover** — its own words: *"Defer the heavy GPU/OCR/embedding/VDU backfill
on battery, run full-speed on AC... the same way it already gates on `isMainGpuActive()`."* That could not be
built for VDU when 630 was written, for a reason 630's author had no way to know: `OfflineCoordinatorBuilder`
never actually built the VDU coordinator at all (this tempdoc's own root cause), so there was nothing to gate
— VDU was unreachable, not merely ungated. **This tempdoc's fix is what makes 630's own already-stated scope
for VDU finally buildable**, not a new idea layered on top of it.

The correct long-term design for a VDU auto-trigger is therefore: **a Head-side counterpart to
`LoopPacingPolicy`'s three-signal shape, gating a call to `OfflineCoordinator.startOfflineProcessing()`**, not
a new detection mechanism:
- User-activity: the Head already writes an activity timestamp (`MainSignalBus.writeActivity()`) on every
  request; a Head-side "time since last activity" read of that same timestamp is the only new plumbing this
  needs — reading back a value the Head already produces, not sensing a new one.
- Energy intent: `EnergyState` is already Head-native and needs no new wiring — direct reuse.
- GPU/LLM exclusivity: simpler on the Head than on the Worker, because there's no cross-process signal to
  read at all — it's just "is `inferenceManager` currently mid-interactive-use in Online mode," in-process.
- `LoopPacingPolicy` itself is `worker-services`-module-scoped (imports `EmbeddingProvider`/
  `DocumentFieldOps`, Lucene-adjacent) and cannot be imported by `app-services` across the Head/Worker module
  boundary (Hard Invariant: Head never touches Lucene) — so this is correctly a **sibling policy with the
  same three-signal decision shape**, not a shared class. The seam to conform to is the *arbitration model*
  630 established, not a literal code import.

**The manual "Process Now" affordance and progress-visibility idea from the prior research pass are the
complementary piece, not a competing design** — the embedding backfill already demonstrates both halves
coexisting (autonomous idle-time operation, with `OfflineCoordinator.isProcessing()`/`hasPendingWork()`
already existing as exactly the introspection a manual button + progress UI would need). Both belong in the
same eventual design, mirroring the pattern this codebase already uses elsewhere for background work: runs
on its own when conditions allow, observable and forceable when a user wants it now.

## Reach: is this a new principle, or an existing one extended?

**Findings 1 and 3 are not new principles.** Finding 1 is a second instance of `standalone-capability-stays-
stuck` (already named, agent-postmortems §10) — no new naming needed. Finding 3 is not a new idea at all; it
is tempdoc 630's own "OS energy/activity-aware background work" principle, whose stated scope already
included VDU, previously blocked by the exact bug this tempdoc fixed and now unblocked. Both should conform
to the seam that already exists rather than growing a parallel one.

**Finding 2 does surface a nameable principle worth recording** (not building infrastructure for — per the
instruction to separate recognizing a principle from building general structure):

> **Restart/mode-transition scope must match the unit of work that needs the mode, not the items processed
> within it.** When an expensive setup/teardown (a server restart, a mode switch, any operation whose cost is
> ~fixed per invocation regardless of what's done inside it) is triggered from inside a per-item loop instead
> of wrapping the loop once, its cost multiplies by item count for no benefit — and the failure is easy to
> miss precisely because each individual call is "correct" in isolation; only the *nesting depth* is wrong.

**Candidate scope beyond this instance:** any place in the codebase that does real work "per document" inside
a batch loop is a candidate to check for this same nesting mistake — worth a quick audit the same
"one-grep-suffices" way Finding 1's audit worked, but not undertaken here (out of this research pass's own
reasonable bound; the instruction is to name the principle and its candidate scope, not exhaustively hunt for
every instance). **Does existing code already violate it elsewhere?** Not confirmed beyond VDU — the
embedding phase in the very same class is the demonstrated *correct* sibling, which is itself informative:
this is not a codebase-wide pattern of carelessness, it looks like a one-off slip in a single method, caught
here because VDU was investigated closely for an unrelated reason (its throughput happened to matter while
verifying this tempdoc's own fix). A cheap, honest way to raise confidence later: grep for other
`RestartPolicy.RESTART_ALWAYS`-triggering calls and check whether each sits inside a per-item loop or wraps
one — a small, bounded check, not a new gate or generalized enforcement mechanism, consistent with this
tempdoc's own guardrail-respecting caution about not building structure the present problem doesn't need.

## Pre-implementation de-risking for Findings 1-3 (2026-07-02) — still no code changed

A confidence-building pass over the design theorization above, read-only investigation only. All seven
identified uncertainties closed with concrete evidence; one correction to the earlier theorization surfaced.

**Finding 1 (`WorkerFeatureCache`) — low risk, confirmed.** `getOnnxModels()` has exactly one production
consumer (`RuntimeActivationService`), and that class is genuinely live — wired into `AiRuntimeController`
(`CoreApiAssembly.java:365`), a real controller, not a dead or test-only path. No other consumer found.

**Finding 2 (batch-scoped VDU restart) — low risk, confirmed clean.**
- `VduProcessor.process(Path)` has exactly one production caller: `VduBatchProcessor.processPendingFiles()`'s
  loop. No second caller that could rely on today's per-call mode granularity.
- Read the full method body: the VDU-mode `try/finally` is cleanly independent of the per-file temp-image
  cleanup (a separate try-with-resources on `PdfImageRenderer`) and the per-pass telemetry timing blocks —
  both of which are correctly per-file and must *stay* per-file. Moving only the mode-transition calls up a
  level is a clean, surgical lift, not an entangled refactor.
- Zero existing tests reference `enterVduMode`/`exitVduMode` at all (`VduBatchProcessorTest`,
  `VduPoisonPillTest` mock at a higher level) — no test fallout risk.
- **Bonus, not just perf:** today, if `enterVduMode()` fails for the first document in a batch, the same
  failure likely repeats for every subsequent document (each pays the same failed-restart cost independently).
  Batch-scoping the entry turns that into a single failure for the whole batch — a correctness/UX
  improvement bundled with the performance fix, not a separate concern.

**Finding 3 (Head-side idle/energy VDU auto-trigger) — medium risk, and one real correction to the earlier
theorization.**
- **Correction:** the MMF signal bus is explicitly documented as one-directional —
  `MainSignalBus.java:24`: *"Last User Activity (Epoch Millis) — **Main writes; Worker reads**."* The Head
  cannot read back its own write. The earlier design section's framing ("reading a value the Head already
  produces, not sensing a new one") was directionally right but imprecise: the Head needs a small **local**
  mirror of its own last-activity timestamp (set at the same call sites that already call
  `signalUserActivity()`) — real, small, well-understood new plumbing, not zero plumbing.
- **Scheduling precedent found and it's an excellent fit:** `GpuSaturationSampler`
  (`modules/ui/.../observability/GpuSaturationSampler.java`) is a Head-side, single-thread
  `ScheduledExecutorService`, fixed 15s cadence, documented to short-circuit gracefully when its dependency is
  unavailable and to survive transient exceptions without killing the schedule. This is the exact shape a
  Head-side idle/VDU-trigger sampler should copy — same primitive, same defensive posture, proven in
  production already.
- **Race-condition precedent found:** `EmbeddingBackfillOps.checkInterrupt()` shows the Worker doesn't
  prevent the "user became active mid-run" race with locking — it uses a **cooperative checkpoint**: check
  the interrupt condition once before starting a batch, and the batch loop is structured so the check can run
  again between units of work. This maps directly onto `VduBatchProcessor.processPendingFiles()`'s existing
  per-document loop (the same loop Finding 2 touches) — a future auto-trigger's abort-on-activity check would
  live in the same place as Finding 2's batch-scoping fix, not a separate mechanism. These two follow-ups are
  naturally sequenced, not independent.

**Net effect on confidence:** Findings 1 and 2 are now verified low-risk with no open questions. Finding 3's
core signals and scheduling shape are all confirmed to already exist and fit cleanly; the one gap (Head-side
last-activity tracking needs a small local field, not a free read-back) is fully understood and small, not a
surprise that would derail implementation.

### Confidence rating and implementation guidance

**Confidence: 8/10** for implementing Findings 1-3 as theorized. Findings 1 and 2 are close to 10/10 — small,
mechanical, fully precedented, zero test fallout, no open questions. Finding 3 is the one pulling the average
down: it's still a small amount of genuinely new code (a Head-side last-activity field + a sampler class +
the three-signal decision function), and while every piece has a strong precedent, first-time integration of
a new cross-cutting concern (idle detection feeding into VDU) always carries more risk than pattern-matching a
fix that's already shipped twice (Finding 1) or a pure code-motion (Finding 2).

**Difficulty and model/effort recommendation, per finding:**
- **Finding 1:** trivial — a single-file change mirroring 672's own already-shipped pattern. **Sonnet, low
  effort.**
- **Finding 2:** low-to-moderate — a contained, well-understood code-move within one class, verified
  test-fallout-free. **Sonnet, low-to-medium effort**, medium only because moving exception-handling scope
  deserves a careful re-read of the moved try/finally semantics, not because the change itself is large.
- **Finding 3:** moderate — genuinely new (small) code across two-three files (a Head-side activity tracker,
  a new sampler class modeled on `GpuSaturationSampler`, and the decision function itself), plus the "Process
  Now" UI affordance and progress surface from the earlier research pass if bundled in. **Sonnet, medium
  effort** for the backend wiring (the precedents are strong enough that this doesn't need architectural
  judgment, just careful assembly); if the UI/UX polish (progress panel, completion notification) is bundled
  into the same pass, that part specifically benefits from **high effort** or a UX-focused pass, since it's
  the one sub-piece without a ready-made precedent to copy and needs actual design judgment (button
  placement, wording, what "processing N of M" looks like) rather than pattern-matching. Opus is not
  warranted for any of the three — the architecture is settled and every open question in this de-risking
  pass resolved to "an existing, provable precedent," not a novel design decision.

## Findings 1-3 implementation + validation (2026-07-02)

Implemented per the approved plan (`ticklish-riding-boole.md`). All three findings shipped, tested, and
live-verified against a running dev stack; the manual-trigger UI piece was additionally verified through the
real browser.

### Finding 1 — `WorkerFeatureCache` live supplier

`ServicePhase.Input` gained `Supplier<RemoteKnowledgeClient> knowledgeClientSupplier` (already threaded for
the VDU coordinator fix) reused for the `WorkerFeatureCache` lambda in `ServicePhase.java`, replacing the
bound-value `in.knowledgeClient()::getLastKnownOnnxModels` with a live re-read at call time. Mirrors the
original fix exactly — same bug class, same fix shape, no new plumbing. `HeadAssembly.java` threads
`() -> this.knowledgeClient` at the `ServicePhase.Input` construction site.

### Finding 2 — batch-scoped VDU mode transitions

`VduProcessor.enterVduMode()`/`exitVduMode()` are now called once around the whole
`VduBatchProcessor.processPendingFiles()` loop (try/finally), not once per document inside
`VduProcessor.process()`. A failure to enter VDU mode now fails the whole batch immediately
(`vduCapabilityState.block(REASON_AI_OFFLINE)`, batch returns 0) instead of retrying the same failed restart
per document. New regression test `VduBatchProcessorModeScopingTest` (against the real production classes,
not the pre-existing `TestableVduBatchProcessor` duplicate used by `VduBatchProcessorTest`/`VduPoisonPillTest`
— see `unreachable-seed-green`, tempdoc 618 §10b) pins: (a) `enterVduMode`/`exitVduMode` each called exactly
once for a multi-document batch, (b) an enter-failure skips the whole batch without a retry loop.

**Live-verified**: triggering VDU on an isolated 3-document corpus produced exactly one "Entering VDU mode" /
"Exiting VDU mode" pair in the Head log for the whole batch (previously: one pair per document). All 3
documents processed successfully (`VDU batch complete: 3 processed, 0 failed`).

### Finding 3 — idle/energy auto-trigger, manual trigger, progress signal

- **3a.** `KnowledgeServerBootstrap` gained `lastUserActivityEpochMs` (mirrors the existing
  `lastResumeEpochMs` pattern) + `msSinceLastUserActivity(long nowEpochMs)`, updated at the existing
  `signalUserActivity()` call site. `energyState()` already existed (tempdoc 630).
- **3b.** New `VduPacingPolicy` (Head-side sibling to `LoopPacingPolicy`, `app-services` — cannot import
  `worker-services` directly, Hard Invariant). `shouldTrigger(msSinceActivity, energyReduced, llmOnline)`
  mirrors `LoopPacingPolicy`'s energy-first ordering. `DEFAULT_IDLE_THRESHOLD_MS = 5 min`.
- **3c.** New `VduOfflineTriggerSampler` (Head-side, modeled on `GpuSaturationSampler`: single daemon thread,
  30s cadence, defensive against exceptions). Gates on `coordinator.getPendingVduCount() > 0` specifically
  (not the broader `hasPendingWork()`, since embedding backfill already has its own Worker-side autonomous
  trigger). Wired into `CoreApiAssembly.java` (construction) and `LocalApiServer.java`
  (`start()`/`stop()`, alongside `gpuSaturationSampler`'s own lifecycle calls).
- **3d.** Cooperative-checkpoint interrupt inside `VduBatchProcessor.processPendingFiles()`'s per-document
  loop (same loop Finding 2 touches), mirroring `EmbeddingBackfillOps.checkInterrupt()`'s shape. Composed
  once in `ServicePhase.java` as a single `BooleanSupplier` and threaded through
  `OfflineCoordinatorBuilder.build()` into a new `VduBatchProcessor` constructor overload (the old 5-arg
  constructor delegates to it with `() -> false`, preserving existing test/call-site compatibility).
- **3e.** Manual "Process Now" trigger: `<jf-operation context="button"
  operation-id="core.trigger-offline-processing">` added to `LibrarySurface.ts` next to the existing
  `core.reindex` button — same catalog-driven component, no new button system. The operation already existed
  in `CoreOperationCatalog.java` with a working handler; it was simply never surfaced in the UI.
- **3f.** Progress signal: `VisualExtractionView` (wire record, `app-api`) gained a `vduProcessing` boolean
  field, sourced from `coordinator.isProcessing()` via a new `StatusLifecycleHandler.vduProcessingSupplier`,
  merged in `overlayVduCapability()` independently of the existing blocked-reason snapshot (a healthy,
  actively-processing batch has no blocked reason, so the old blocked-reason-only guard would have skipped
  this fact). `visualTextNeededCount` (pre-existing field) already reports remaining work; `vduProcessing`
  adds the missing "is it happening right now" bit. Required updating `contracts/wire/status.proto` (a
  genuine wire-contract conformance test — `StatusWireContractConformanceTest` — caught the initially-missing
  proto field) and regenerating schemas/fixtures (`:modules:app-api:updateSchemas`).
  **Scope decision**: a live "processing N of M" UI widget was **not** built this pass. `LibrarySurface.ts`
  has no existing subscription to status/readiness data, and `readinessNotice.ts` is a pure projector fed by
  a single upstream verdict authority (the frontend presentation-kernel invariant, ADR-0032 / tempdoc
  579/620's `consult-doc-hint`, explicitly warns against a parallel, ad-hoc status display). The wire field is
  shipped, tested, and live-confirmed on the wire (`"vduProcessing":false` on `GET /api/status`,
  `enrichment.visualExtraction`); a future UI integration through the proper verdict pipeline can consume it
  directly. The "Process Now" button's own `op-success` feedback ("Offline processing started") already
  covers the immediate action-confirmation need.

### A real bug caught by live validation

The first version of `VduPacingPolicy.shouldInterrupt` reused `shouldTrigger`'s exact three-signal
composition, including `inferenceManager.isOnline()`. This is correct for `shouldTrigger` (checked *before*
starting a batch — "someone else already has the LLM"), but wrong for `shouldInterrupt`: once a VDU batch is
running, the LLM is legitimately Online *because the batch itself put it there*
(`enterVduMode()` requires `Mode.ONLINE` as a precondition and only flips a `vduMode` flag within it, not
`currentMode()` itself) — so `isOnline()` is unconditionally true for the entire batch duration and
self-interrupted on the very first checkpoint, every time. Live evidence: `"VDU batch interrupted (user
active or energy-reduced), leaving 3 docs PENDING"` logged immediately after "Entering VDU mode," before a
single document processed (`VDU batch complete: 0 processed, 0 failed`). Fixed by dropping the `llmOnline`
parameter from `shouldInterrupt` entirely (activity + energy only); re-verified live afterward — the same
3-document batch completed cleanly (`VDU batch complete: 3 processed, 0 failed`, `visualTextNeededCount`
0→3→0 confirmed via the wire). This is exactly the audit-without-test / static-green-≠-live-working failure
mode CLAUDE.md names — the code compiled, all 12 new unit tests passed, and the bug was invisible until a
real batch actually ran end-to-end.

### Verification performed

- `spotlessApply`, `./gradlew.bat build -x test` (twice — after initial implementation and after the
  `shouldInterrupt` bug fix), both clean.
- `./gradlew.bat test` (full suite, twice) — all modules green both times.
- 12 new backend unit tests: `VduPacingPolicyTest` (5), `VduOfflineTriggerSamplerTest` (5),
  `VduBatchProcessorModeScopingTest` (3, includes the new interrupt test) — 0 failures.
- `contracts/wire/status.proto` updated + `:modules:app-api:updateSchemas` regenerated schemas/fixtures;
  `StatusWireContractConformanceTest` (previously failing after the field addition) passes.
- Frontend: `npm run test:unit:run` — 359 files, 3509 tests, all passed. (`npm run typecheck` hit a
  pre-existing, unrelated `tsconfig.json` TS5101 config error — logged to the observations inbox, not caused
  by this work; confirmed via clean `git status`/`git log` on `tsconfig.json`.)
- Live dev stack, isolated 3-document corpus: Finding 2 (one restart per batch) and Finding 3d (no premature
  interrupt after the fix) both confirmed via Head log inspection; Finding 3f's wire field confirmed via
  `GET /api/status`.
- Browser validation (required for the user-visible piece): navigated to the real Library surface,
  confirmed the "Run Offline Processing" button renders next to "Reindex" (audience gate passed with no
  extra configuration needed), clicked it, confirmed `POST /api/operations/core.trigger-offline-processing/invoke`
  returned `200` via network inspection.

### Status

Findings 1, 2, and 3 (all sub-parts 3a-3f) are implemented, tested, and live-verified. No PR opened yet per
standing instruction.

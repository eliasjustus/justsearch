# 837 — Reason-code completeness (833 W6): design

```
status: DESIGN
created: 2026-08-14
updated: 2026-08-14
related: 833 (W6 + finding 6, the theorization this designs), 830 (the Search v3
  degradation banner that renders these codes), 600 (the closed vocabulary + its
  gate), 656 (the last "precise reasons never reached the banner" fix), 627
  (worker supervision codes), 628 (corruption rebuild), 737 (RuntimeStatus /
  runtime-state register, whose "Phase 3 join" this touches), 805 §G.2
  (consequence classification), 804 §D1 (cause scoping)
```

Design only. No implementation in this pass. Every load-bearing claim below was
re-verified against source in this worktree; `file:line` citations are the
evidence, not decoration.

---

## 0. The unifying diagnosis (what W6 actually is)

833 finding 6 lists four gaps. Investigation says three of them are the *same*
defect wearing three costumes, and the fourth is a different one:

> **The capability `reason` slot is dual-typed.** `Capability.pendingReason()`
> (`modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/Capability.java:20`)
> returns a `String` that is *sometimes* a `LifecycleReasonCode.code()` and
> *sometimes* free prose. Two consumers filter with
> `LifecycleReasonCode.isKnown(reason)` and silently substitute a generic code;
> **three others publish the raw string**, unfiltered, to a wire surface. Prose in
> the code slot is not a wording bug — it is **information deletion at a type
> boundary the compiler cannot see**, plus an untyped string on three public
> surfaces.

The filter: `StatusLifecycleHandler.resolveInferenceReasonCode`
(`modules/ui/src/main/java/io/justsearch/ui/api/StatusLifecycleHandler.java:1051-1055`)

```java
String reason = inferenceCapability.pendingReason();
return LifecycleReasonCode.isKnown(reason) ? reason : LifecycleReasonCode.INFERENCE_OFFLINE.code();
```

and its worker twin, which is the same idea hand-inlined for exactly one code
(`StatusLifecycleHandler.java:1109-1113`):

```java
case DEGRADED -> new LifecycleSnapshotV1.Component(
    LifecycleState.LIFECYCLE_STATE_ERROR,
    LifecycleReasonCode.WORKER_RESTART_EXHAUSTED.code().equals(workerCapability.pendingReason())
        ? LifecycleReasonCode.WORKER_RESTART_EXHAUSTED.code()
        : LifecycleReasonCode.WORKER_SPAWN_FAILED.code());
```

### 0.1 The producer inventory (22 transition sites + 3 defaults, not 9)

Everything written into the reason slot, verified by grepping every
`transition(` call and every field/constructor default:

| Producer site | Written reason | What `/api/status` says |
|---|---|---|
| `InferenceCapability.java:21` (field default) | `"Inference not yet activated"` | `inference.offline` |
| `InferenceCapability.java:30` (ctor, `!configured`) | `"Inference not configured"` | `inference.offline` |
| `InferenceCapabilityWiring.java:114` | `RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND` | `inference.offline` |
| `InferenceCapabilityWiring.java:118` | `"Inference offline"` | `inference.offline` |
| `InferenceCapabilityWiring.java:120` | `"Inference transitioning"` | `inference.offline` |
| `InferenceCapabilityWiring.java:122` | `"GPU allocated to indexing"` | `inference.offline` |
| `RuntimeActivationService.java:1208` | a real code | passes through |
| `InferenceHandlers.java:433` | a real code | passes through |
| `WorkerCapability.java:26` (field default) | `"Worker not yet connected"` | `worker.spawn.failed`¹ |
| `KnowledgeServerBootstrap.java:160` | `"Worker starting"` | (PENDING ⇒ `worker.starting`) |
| `KnowledgeServerBootstrap.java:205` | supervisor's `reason` string | (RECOVERING ⇒ `worker.recovering`) |
| `KnowledgeServerBootstrap.java:211` | a real code | passes through |
| `KnowledgeServerBootstrap.java:254` | `"Health check failed after …ms"` | `worker.spawn.failed` |
| `KnowledgeServerBootstrap.java:263-264` | `"Start failed: …"` | `worker.spawn.failed` |
| `KnowledgeServerBootstrap.java:303-304` | `"Start failed: …"` | `worker.spawn.failed` |
| `KnowledgeServerBootstrap.java:614` (was READY) | `"Health check failed"` | `worker.spawn.failed` |
| `KnowledgeServerBootstrap.java:666` | `"Worker shut down"` | (OFFLINE ⇒ `worker.not_configured`) |
| `KnowledgeServerBootstrap.java:588-596` (`workerDownReason`) | the corrupt-index remedy paragraph | `worker.spawn.failed` |
| `KnowledgeServerHealthMonitor.java:111` (was READY) | `"Health monitor tick exception"` | `worker.spawn.failed` |
| `CoreApiAssembly.java:531` | `"Worker not configured"` | (OFFLINE ⇒ `worker.not_configured`) |
| `CoreApiAssembly.java:537` | `"Worker spawn failed: " + error` | `worker.spawn.failed` |
| `HeadlessApp.java:449-452` | `"Worker spawn failed: " + error` | `worker.spawn.failed` |
| `HeadlessApp.java:455-457` | `"Worker not configured"` | (OFFLINE ⇒ `worker.not_configured`) |

¹ Only if a consumer reads it while health is still `PENDING` **and** the PENDING
arm's `isStartingUp` branch is not taken; on the worker side `PENDING` maps to
`worker.starting` (`StatusLifecycleHandler.java:1121-1122`) so the default is
mostly latent — but it is the *published* string on the manifest path (§0.2) from
process start until the first transition.

Note `InferenceCapabilityWiring.java:114`: the engine is **healthy and running**
and the UI says the model is offline. And `KnowledgeServerBootstrap.java:588-596`:
the one place that knows the user's index is corrupt *and* knows the exact config
change that fixes it throws that sentence away and says "failed to start".

Three of these sites are outside the two files 833 named — `CoreApiAssembly.java:537`
and `HeadlessApp.java:449-452` write prose on a **DEGRADED worker** transition,
which is what makes §3.2's "after the sweep the fallback is unreachable" false
unless they are swept too. They are, in this design.

### 0.2 The consumer inventory (5 sites; 3 unfiltered)

| Consumer | Treatment | Surface |
|---|---|---|
| `StatusLifecycleHandler.java:1051-1055` (`resolveInferenceReasonCode`) | filtered — `isKnown` or generic | `/api/status` `components.inference.reason_code` |
| `StatusLifecycleHandler.java:1109-1113` (worker arm) | filtered — one hardcoded code | `/api/status` `components.worker.reason_code` |
| `RuntimeManifestListenerWiring.java:90-97, :155-162` (`publishAi`) | **raw** | `/api/runtime/manifest` `ai.pendingReason` |
| `RuntimeManifestListenerWiring.java:137-142` (`publishWorkerFailed`) | **raw** | `/api/runtime/manifest` worker failure reason |
| `ApiSecurityFilters.java:452` | **raw** | the 503 body's `"reason"` on a capability-gated route |
| `CapabilityHealthBridge.java:58,73,86,91` (`pushCondition`) | **raw**, as the Condition *message* | conditions store / Health events |

This changes the design in two ways.

**(i) It is an observable wire change, and it has a reader.** `scripts/dev/doctor.mjs:207`
reads `manifest?.ai?.pendingReason ?? status.components?.inference?.reason_code`
— i.e. it already treats the two as interchangeable and *prefers* the manifest
field. Today it prints prose; after the sweep it prints a code. That is an
improvement (the 656 onramp assumes a code), but it is a **contract change that
must be stated in each slice's acceptance criteria**, not discovered by the
doctor. Same for `ai.pendingReason` in `RuntimeManifest.java:200`.

**(ii) It settles D-1 (§8 probe 2) toward the typed slot.** Consumers 5 and 6 want
prose and are *right* to: a Condition's `message` field and a 503 debug body are
exactly where a human sentence belongs. So the fix is **not** "delete the prose"
— it is "stop putting prose in the *code* slot". That requires a second field
(`pendingDetail()`), which is the typed design, not the disciplined-string one.
A disciplined-string sweep alone would silently degrade the Health-event message
from `"Health check failed after 4200ms"` to `"worker.lost"`.

Three consequences follow that shape the design:

1. **Fixes (a) and (c) are one fix applied twice** — stop writing prose into the
   code slot, and let the consumer pass a known code through instead of
   hardcoding a fallback. Most of the "new codes" needed for (a) require **no new
   signal at all**: `Mode.INDEXING` and `chatEnabled=false` are already
   distinguishable at the switch. Only `INFERENCE_CRASHED` genuinely needs
   `TransitionReason` threaded. The sweep is wider than 833 implied: it needs
   codes for the *lifecycle-boundary* states too — `"Worker shut down"`
   (`:666`), `"Worker not yet connected"` / `"Inference not yet activated"`
   (the field defaults), `"Worker not configured"` — either as members
   (`WORKER_SHUT_DOWN`; `WORKER_NOT_CONFIGURED` already exists and fits
   `CoreApiAssembly.java:531` / `HeadlessApp.java:457`) or by making the
   pre-transition default **null + a detail string**, which is the cleaner answer
   for "we have not observed anything yet" (see §3.2).
2. **Fix (b) is not a two-authorities problem — it is a dead code path.** See §2.
   `index.rebuilding` is produced and can never render.
3. **Fix (d) is the phantom class**, and the probe (§5) says it is *exactly* four
   codes, all `ORT_CUDA_*`.

### The prose→code sweep is the spine

Every sub-fix below is a slice of one move: **make the reason slot a code, not a
string.** The end state is `Capability.pendingReason()` returning only codes,
plus a separate `pendingDetail()` carrying the human sentence that
`CapabilityHealthBridge` and `ApiSecurityFilters` legitimately want (§0.2).
Whether we take the typed step (a `LifecycleReasonCode`-typed field + a
`String detail`) or the disciplined step (still `String`, every call site passes
`X.code()`) is **Decision D-1** (§8, probe 2) — but §0.2(ii) already argues the
typed step, because the disciplined step alone silently degrades the Health-event
message, and because the string sweep was **already attempted incompletely**:
tempdoc 656 fixed the consumer and two producers and left ~15 prose sites and
three raw consumers standing. That is the empirical case for making the defect
unrepresentable rather than sweeping it again.

---

## 1. Fix (a) — inference: crash ≠ user-off ≠ GPU-yielded ≠ background-up

### 1.1 What is actually collapsed, and what each case needs

`InferenceCapabilityWiring.deriveAndApply`
(`modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/InferenceCapabilityWiring.java:106-124`)
switches on `Mode` only. Four distinct user situations reach one wording:

| Situation | Distinguishable today? | Needs |
|---|---|---|
| GPU handed to indexing (`Mode.INDEXING`) | **Yes** — it is its own `Mode` arm (`:121-122`) | a code, nothing else |
| Engine up for background work (`ONLINE` + `chatEnabled=false`) | **Yes** — the arm already exists (`:113-115`) | a code, nothing else |
| Engine transitioning (`TRANSITIONING`) | **Yes** (`:119-120`) | reuse `INFERENCE_STARTING` |
| Crash recovery vs user deactivate (both `OFFLINE`) | **No** | `TransitionReason` threaded |

So three of the four collapses are fixed by replacing a string literal with
`X.code()`. Only the fourth needs plumbing. This matters for sequencing: the
cheap 80% ships without touching `app-inference`.

### 1.2 Threading `TransitionReason` (the one plumbing change)

`ModeChangeListener`
(`modules/app-api/src/main/java/io/justsearch/app/api/ModeChangeListener.java:42-45`)
is `@FunctionalInterface void onModeChange(Mode from, Mode to)`. The reason
exists at the notify site — `TransitionRunner` already carries a
`TransitionReason` through `run(...)` / `runForceOffline(...)`
(`modules/app-inference/src/main/java/io/justsearch/app/inference/TransitionRunner.java:305,403,520-527`)
— and crash recovery specifically calls
`runner.runForceOffline(TransitionReason.CRASH_RECOVERY, …)`
(`InferenceLifecycleManager.java:210,226`).

**Module direction check (verified):** `app-inference` depends on `app-api`
(`modules/app-inference/build.gradle.kts:10`, `api(project(":modules:app-api"))`),
not the reverse. `TransitionReason` lives in
`modules/app-inference/src/main/java/io/justsearch/app/inference/telemetry/TransitionReason.java:9-40`.
So the listener in `app-api` cannot name it where it is. **Move
`TransitionReason` to `io.justsearch.app.api`**, mirroring the exact precedent
this codebase already set for `Mode` + `ModeChangeListener` (tempdoc 518 P4,
recorded at `InferenceLifecycleManager.java:75-80`). Re-export is not needed; it
is an enum with a stable `wireValue()` used by telemetry, and the telemetry
call sites are a mechanical import change.

> **Name-collision warning:** `modules/ort-common/src/main/java/io/justsearch/ort/telemetry/TransitionReason.java:18`
> is an *unrelated* sealed interface with the same simple name (ORT session
> lifecycle). Two `TransitionReason` types already coexist; moving one does not
> create the collision but does make fully-qualified imports more important.
> Do not "unify" them — they share a name, not a reason to change (AHA).

Signature change, backwards-compatible by default method:

```java
@FunctionalInterface
public interface ModeChangeListener {
  void onModeChange(Mode from, Mode to);

  /** Reason-bearing overload; the runner calls THIS one. Default preserves existing listeners. */
  default void onModeChange(Mode from, Mode to, TransitionReason reason) {
    onModeChange(from, to);
  }
}
```

`TransitionRunner`'s notifier (`TransitionRunner.java:74-76,247-254`) invokes the
3-arg form with the reason it already holds; the two other listeners
(`InferenceWiring.wireGpuStatusBroadcast`, `RuntimeReconciler`'s attached
listener at `RuntimeReconciler.java:141,199`) keep compiling unchanged because
they never override it.

**Second consumer, free:** `RuntimeStatus.deriveEngine`
(`modules/app-services/src/main/java/io/justsearch/app/services/runtimestate/RuntimeStatus.java:112-115`)
documents its own missing refinement in a comment: *"`Recovering` is a Phase-2
refinement gated on a `TransitionReason == CRASH_RECOVERY` signal that the bare
`ModeChangeListener` does not carry."* The same one-parameter change unblocks it.
Whether to spend it here is **Decision D-2** (§7 non-goals).

### 1.3 The new switch

```java
private static void deriveAndApply(
    InferenceCapability cap, Mode mode, boolean chatEnabledSpec, TransitionReason reason) {
  switch (mode) {
    case ONLINE -> {
      if (chatEnabledSpec) {
        cap.transition(CapabilityHealth.READY, null);
      } else {
        cap.transition(CapabilityHealth.DEGRADED,
            LifecycleReasonCode.INFERENCE_UP_FOR_BACKGROUND.code());
      }
    }
    case OFFLINE -> cap.transition(CapabilityHealth.OFFLINE, offlineCode(reason).code());
    case TRANSITIONING ->
        cap.transition(CapabilityHealth.RECOVERING, LifecycleReasonCode.INFERENCE_STARTING.code());
    case INDEXING -> cap.transition(CapabilityHealth.DEGRADED,
        LifecycleReasonCode.INFERENCE_GPU_YIELDED_TO_INDEXING.code());
  }
}

private static LifecycleReasonCode offlineCode(TransitionReason reason) {
  if (reason == null) return LifecycleReasonCode.INFERENCE_OFFLINE;
  return switch (reason) {
    case CRASH_RECOVERY -> LifecycleReasonCode.INFERENCE_CRASHED;
    case USER_SWITCH, ADMIN_TRIGGERED -> LifecycleReasonCode.INFERENCE_DEACTIVATED;
    // AUTO_START / CONFIG_APPLY / VDU_ENTER / VDU_EXIT / EXTERNAL_DETACH / SHUTDOWN / UNKNOWN:
    // an OFFLINE landing under these is either a transient restart step or app teardown; the
    // generic code is the honest answer and the FE already words it well.
    default -> LifecycleReasonCode.INFERENCE_OFFLINE;
  };
}
```

The initial mirror call (`InferenceCapabilityWiring.java:92`) and the
spec-change re-derivation (`:98-101`) have no transition in hand — they pass
`TransitionReason.UNKNOWN`, which the `default` arm maps to the generic code.
That is correct: they are *observations of a standing state*, not transitions.

**Verdict on the argued codes** (833 W6 asked for exactly this argument):

- **`INFERENCE_CRASHED` — yes.** Different truth (nobody chose this), different
  remedy (reload / check logs), different emotional register. Non-negotiable.
- **`INFERENCE_DEACTIVATED` — yes.** Its absence is worse than generic: the user
  turned chat off, and the banner tells them something is broken. It is also the
  most *frequent* of the four, so it is the one that trains alarm-blindness.
- **`INFERENCE_GPU_YIELDED_TO_INDEXING` — yes, and it is the cheapest of all.**
  No new signal, and the honest wording is genuinely reassuring ("chat resumes
  when indexing finishes") where the current one is alarming. `info` severity, so
  it drops out of the banner entirely and lives in Health — which is the right
  place for a self-clearing scheduled state.
- **`INFERENCE_UP_FOR_BACKGROUND` — yes, on correctness grounds.** The current
  wording is *false*, not merely vague: it says the model is offline while the
  model is up. This one also closes half of the 737 "Phase-3 join": the internal
  string `RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND`
  (`RuntimeStatus.java:97`) becomes a real member of the closed vocabulary, and
  the two surfaces that were deliberately built to agree
  (`InferenceCapabilityWiring.java:60-63`) go on agreeing.

Four new codes. If the owner wants a smaller batch, the drop order is
`INFERENCE_UP_FOR_BACKGROUND` last (it is a correctness fix, not an enrichment)
and `INFERENCE_DEACTIVATED` first (it can ship in the same slice as the
`TransitionReason` plumbing or wait).

### 1.4 The ordering hazard nobody has hit yet (found while designing)

`RuntimeActivationService.reportToCapability`
(`modules/app-services/src/main/java/io/justsearch/app/services/ai/runtime/RuntimeActivationService.java:1200-1209`)
writes a *precise* code (`inference.model_not_found`, …) onto the same
capability. If the manager subsequently fires an `OFFLINE` mode change — which a
failed activation attempt plausibly does — `deriveAndApply` overwrites it with
the generic code. Last writer wins, and the specific cause the 656 slice worked
to surface is lost. This is live today (prose overwrites the code) and would
remain live after this fix (generic code overwrites specific code).

**This rule is load-bearing for S5, not a nicety.** After S5, the spec-change
re-derivation (`InferenceCapabilityWiring.java:98-101`) fires on *any*
`chatEnabled` toggle with `TransitionReason.UNKNOWN` in hand, which maps to the
generic code (§1.3's `default` arm). Without a precedence rule, a user toggling a
setting after a crash **overwrites `INFERENCE_CRASHED` with `inference.offline`**
— S5 would ship the very collapse it exists to fix, and the happy-path test
would pass.

**Design: a precedence rule, not an ordering assumption.**

`transition(health, code)` **always applies the new health**; it retains the
*held reason* when all three hold:

1. the incoming code is the capability's declared **generic fallback**
   (`inference.offline` for inference; `worker.spawn.failed` for worker), and
2. the held reason is a **different known code** (never prose — prose has no
   precedence and is always overwritten), and
3. the new health is **not `READY`**.

`READY` clears the reason outright (`pendingReason()` already returns null at
READY, `InferenceCapability.java:40-42`), so no stale cause survives a recovery —
that is the anti-staleness bound, and it is why the rule needs no timer.

Spec details that must be in the test, not left implicit:

- Health still updates while the reason is retained (a `DEGRADED → OFFLINE`
  transition with a generic code keeps the specific reason but *does* go OFFLINE).
- Listener firing follows the *effective* change: if health changed, fire; if
  only the incoming reason was rejected, **do not** fire (the 656 reason-only
  widening at `InferenceCapability.java:71-75` must not turn a rejected write into
  a spurious manifest publish).
- Two orderings, both asserted: activation-failure-then-mode-change, and
  mode-change-then-activation-failure.

**The same rule is required on `WorkerCapability`, and there it is stronger than
precedence — it is a latch.** `WorkerFatalReasonMarker.readAndClear`
(`modules/ipc/.../WorkerFatalReasonMarker.java:55-67`) **deletes the marker file
as it reads it** (`Files.deleteIfExists(p)`, `:65`). So the corrupt-index cause is
readable exactly once: whichever `workerDownReason` call wins the race gets it,
and any later generic overwrite destroys it **permanently** — a restart will not
re-derive it, because the marker is gone. `WORKER_INDEX_CORRUPT` must therefore be
retained against generic overwrites for the lifetime of the capability's non-READY
state (cleared on READY, like the rest). Without the latch, §3.3's highest-value
code is a race.

### 1.5 FE rows (`readinessNotice.ts` `CAUSE_ROWS`)

Insert after the existing `inference.*` block (`readinessNotice.ts:104-148`).
Row shape is `{ code, wording, remedy?, severity? }` (`:66-75`).

```ts
{
  code: 'inference.crashed',
  wording: 'The local AI model stopped unexpectedly',
  remedy: { kind: 'operation', operationId: 'core.reload-inference' },
  severity: 'warn',
},
{
  code: 'inference.deactivated',
  wording: 'The local AI model is turned off',
  remedy: { kind: 'operation', operationId: 'core.reload-inference' },
  severity: 'info',
},
{
  code: 'inference.gpu_yielded_to_indexing',
  wording: 'The GPU is indexing your files — chat resumes when it finishes',
  severity: 'info',
},
{
  code: 'inference.up_for_background',
  wording: 'Chat is turned off; the AI engine is running background document processing',
  severity: 'info',
},
```

**Mandatory companion edit — this is the trap.** A `warn`-severity `inference.*`
code that gets only a `CAUSE_ROWS` row is classified `cosmetic` by
`classifyConsequence` (`readinessNotice.ts:500-516`: recognized row, in none of
the sets ⇒ falls through to `cosmetic`), and the banner then claims *"An optional
capability is unavailable; results are still fully semantic"* for a **crashed AI
model**. So **`inference.crashed` must join `AI_MODEL_UNAVAILABLE_CODES`**
(`readinessNotice.ts:466-472`), beside its sibling `inference.offline`.

**The other three must NOT join it**, and the reason is written in that set's own
doc comment (`readinessNotice.ts:462-464`): it excludes `inference.starting`
(transient) and `inference.policy_*` ("policy-disabled never *comes online*").
`inference.deactivated` **is** the policy/user-choice case and
`inference.up_for_background` describes an engine that is *up*; adding them would
contradict the comment in the same edit that cites it. Excluding them is also
harmless, because `readinessNotice` short-circuits on `verdict.severity === 'info'`
at `:619` — **before** `classifyConsequence` runs at `:648` — so for an
info-only verdict the set membership is never consulted. It matters only when an
info code rides alongside a warn cause, and there the calmer class must not be
inferred from a code that is not evidence for it. Record this as a decision, not
an omission.

**`severity` is not a label — correct consumer list:**
`warrantsSearchDegradationBanner` (`readinessNotice.ts:696-699`, which withholds
info-only verdicts from the banner), `severityForCodes` →
`computeVerdict`'s degraded arm (`readinessNotice.ts:561-573`, `verdict.ts:248`),
which in turn drives `verdictBody` (`verdict.ts:347-352`), `verdictTone`, and the
affordance caveats in `state/availability.ts:51,197`.
(`state/messageRouting.ts` was cited in the first draft and is **wrong** — it
contains zero `severity` references; it gates on `readinessNotice(verdict) !== null`
at `:153-159`, which severity affects only indirectly.)

**UR-4 — a `warn` `inference.crashed` makes a second surface lie.** With severity
`warn` and no reindex cause, `verdictBody` returns *"Retrieval is degraded. See
recent events for detail."* (`verdict.ts:347-352`) — false for a chat-only
outage, and it contradicts the banner, which (thanks to the
`AI_MODEL_UNAVAILABLE_CODES` membership above) correctly says *"Search is fully
working … Chat and answer features are unavailable"* (`readinessNotice.ts:658-665`).
Two surfaces, one verdict, opposite claims — the exact class the
`consequence-classification` gate exists for. Options:

- **(recommended)** teach `verdictBody`'s degraded case to consult
  `classifyConsequence` — one `if (classifyConsequence(v.reasons) === 'ai-unavailable')`
  arm returning an AI-scoped sentence, mirroring its existing `isReindexCause`
  arm at `:348-350`. Note this **adds a consumer** to
  `governance/consequence-classification.v1.json`, whose `consumers` list today is
  exactly `["modules/ui-web/src/shell-v0/state/availability.ts"]` — the register
  row is part of the change, and the gate will demand it.
- ship `inference.crashed` at `info`, which dodges the sentence but understates a
  crash. Rejected unless the owner prefers it.
- accept the contradiction. Rejected — it is the defect 805 §G.2 was written for.

Decide before S5 is briefed; do not let it be settled by whichever file gets
edited first.

---

## 2. Fix (b) — reindex sources, and the authority that is actually dead

### 2.1 The finding that changes the shape of this fix

833 called this "two authorities for one degradation". Investigation says it is
worse and simpler: **Authority A is unreachable.**

- **Authority A** — `StatusLifecycleHandler.compatBlockedReason`
  (`StatusLifecycleHandler.java:1254-1264`) emits `index.rebuilding` iff
  `migrationSource == "corrupt_index_rebuild"` **and**
  `migrationState ∈ {MIGRATING, SWITCHING}`.
- **Authority B** — `computeStability` (`modules/ui-web/src/shell-v0/state/verdict.ts:124-130`)
  maps `migrationState == 'MIGRATING'` → `{provisional, cause:'rebuilding'}` and
  `'SWITCHING'` → `'generation-switch'`.
- `computeVerdict` turns *any* provisional stability into
  `kind: 'transitioning'` (`verdict.ts:206-232`) — degraded is reachable only
  from the settled branch (`:247-248`).
- `readinessNotice` returns `null` unless `verdict.kind === 'degraded'`
  (`readinessNotice.ts:600`).

Compose them: the exact window in which `index.rebuilding` is emitted is the
exact window in which the verdict is forced to `transitioning`, so the notice
projection returns `null` and **no surface ever words the code**. It is on the
wire and nowhere else. The `RETRIEVAL_IMPAIRING_CODES` membership written for it
(`readinessNotice.ts:414-415`) is dead too.

That reframes the question. It is not "which of two authorities survives"; it is
"the reason-code authority already lost, silently — do we resurrect it or retire
it?"

### 2.2 Recommendation: the stability/verdict path is the surviving authority

Rationale, in the codebase's own terms:

- The two axes are genuinely different and the 595 model already separates them:
  `readinessNotice.CAUSE_ROWS` words **degradations**; `verdictHeadline` /
  `verdictBody` (`verdict.ts:254-357`) word **transitions**. A generation rebuild
  in progress is a transition (it self-clears, has progress, and has no user
  action). The in-place *embedding* rebuild is a degradation and correctly keeps
  its own reachable code (`index.embedding_rebuilding`,
  `StatusLifecycleHandler.java:1301-1308`) because it does not move the
  generation and so leaves stability settled.
- Authority B can express things A cannot and that users need: the stuck-rebuild
  escalation from the backend's own `migrationPaused` / age-vs-max-duration
  (`verdict.ts:213-222`), and one ordered precedence against worker-down /
  channel-stale so the FE never reconciles two signals.
- Authority A can express exactly one thing B cannot: **why** the rebuild is
  happening. That is the fact worth saving — and it is a *facet of the
  transition*, not a second verdict.

**So: retire `LifecycleReasonCode.INDEX_REBUILDING`** (produce-or-delete applied
to a code that has a producer but no reachable consumer — the phantom class one
level subtler than §4's), and **give the transition its source facet.**

### 2.3 What replaces it

**Backend, and this is the real work: close the `migrationSource` vocabulary.**
It is currently open — free text flows straight through:

| Value | Set-site | Condition |
|---|---|---|
| `""` | `MigrationGenerationView.java:36` | not migrating / no manifest source |
| `corrupt_index_rebuild` | `KnowledgeServer.java:523` | recovery marker + rebuild-from-source policy |
| `embedding_model_change` | `KnowledgeServer.java:568` | fingerprint change under `blue_green_migrate` |
| `schema_mismatch` | `KnowledgeServer.java:602` | `SCHEMA_MISMATCH` under `blue_green_migrate` |
| `manual` | `MigrationControlOps.java:50` | gRPC start with blank reason |
| `"Operation invocation: core.rebuild-index"` | `RebuildIndexHandler.java:66` (verified verbatim) | operation-catalog rebuild |
| `"Operation invocation: core.bulk-reindex"` | `BulkReindexHandler.java:71` | operation-catalog bulk reindex |
| **anything the caller sends** | `IndexingController.java:401,410` | `POST /api/indexing/migration/start` reads `body.reason` and passes it straight to `startMigration(reason)` |
| `system_test`, `pause_resume_test`, `system_test_rollback`, `system_test_switching`, `test-rebuild`, `rebuild-1/2` | system + worker-core tests | test drivers |

The field is not merely under-specified, it is **caller-controlled**: the REST
boundary (`IndexingController.java:401`) forwards an arbitrary request-body string
into the persisted manifest. Two consequences the first draft missed:

**(i) The vocabulary must be enforced at the boundary AND mapped on read — not
enum-typed on write.** The source is persisted in the on-disk generation manifest
and read back through `IndexGenerationManager.readGenerationSourceBestEffort`
(`IndexStatusOps.java:586-594`). Two facts follow: an index built by an older
build (or by a test, or by a hand-written manifest) will hand back a string
outside any vocabulary we define **forever**, and a strict write-side enum would
break six existing test drivers that write `system_test` / `pause_resume_test` /
`rebuild-1`. So:

- **Write side:** `IndexingController.handleMigrationStart` maps/validates the
  caller string to a vocabulary member and rejects or coerces the rest (this is
  the security-relevant boundary — the only one where an outside caller writes
  the field); the two operation handlers pass a member directly.
- **Read side — where the vocabulary actually lives:** a total mapping
  `String → MigrationSource` with an explicit `UNKNOWN` fallback, applied where
  the Head projects the worker view (`StatusLifecycleHandler`, beside the
  now-removed `compatBlockedReason` branch). Unknown is a first-class member, not
  an error: it renders the existing generic "The index is being rebuilt" wording.
  Tests keep writing free strings and land on `UNKNOWN` — no test churn, no lie.

Vocabulary: `corrupt_index_rebuild`, `embedding_model_change`, `schema_mismatch`,
`user_requested_rebuild`, `user_requested_bulk_reindex`, `manual`, `unknown`.

**(ii) FE carrier — the facet, and NOT a flat token.** The first draft offered
`rebuilding-corrupt` / `rebuilding-schema` / … as an alternative. **Struck**: two
live sites gate on the cause string by equality, so a new flat token compiles
clean and silently disables both —

- `verdict.ts:213` — `if (cause === 'rebuilding' || cause === 'generation-switch')`
  guards the stuck-rebuild escalation (paused / overdue). A renamed cause means a
  wedged rebuild stops escalating to `warn`.
- `HealthSurface.ts:818` — `if (stability.cause !== 'rebuilding' && stability.cause !== 'generation-switch') return nothing;`
  guards the rebuild progress bar. A renamed cause blanks it.

Both failures are invisible to `tsc`. So: **`cause` stays exactly `'rebuilding'` /
`'generation-switch'`**, and the source travels as an additive facet.

**The carrier chain, named precisely** (the first draft said "consumed by
`verdictHeadline`/`verdictBody`", which cannot work as written — both take a
`SystemHealthVerdict`, not a `Stability`, so a `Stability` field alone never
reaches them):

1. `Stability` gains an optional `source?: MigrationSource` **alongside** the
   unchanged `cause` (additive; Q4 confirmed no FE surface switches exhaustively
   on `stability.cause` — all uses are equality narrowing).
2. `computeVerdict`'s provisional arm appends the source as an extra token to
   `verdict.reasons`, e.g. `reasons: [cause, 'source:corrupt_index_rebuild']`.
   **This is an existing pattern, not an invention** — `verdict.ts:218,221`
   already append `'paused'` / `'overdue'` exactly this way.
3. `verdictBody` reads it with `.includes()` **before** its
   `switch (v.reasons[0])` (`verdict.ts:323-345`), mirroring how the
   `paused`/`overdue` checks at `:323-328` already sit ahead of that switch.
   `reasons[0]` stays the cause, so nothing downstream reorders.

The corruption wording currently stranded in `CAUSE_ROWS`
(`readinessNotice.ts:326-331`) moves into that branch, preserved word-for-word so
no wording regression rides along.

**One code or several?** Neither — that framing presumes the reason-code
authority survives. The answer is *one transition cause with a source facet*, in
the authority that is actually rendered. Had we kept the code path, the answer
would have been "several" (the remedies differ: corrupt = wait; embedding change
= wait; user-requested = wait but was expected), but they all collapse onto the
same transition, which is the tell.

### 2.4 Retiring the loser without breaking consumers

`INDEX_REBUILDING` sweep (`retire-with-a-sweep`), all verified:

1. `LifecycleReasonCode.java:46` — remove the member and its 628-Stage-C comment.
2. `StatusLifecycleHandler.java:1258-1264` — remove the migration branch from
   `compatBlockedReason`, restoring it to a pure compat-state function (which its
   own javadoc at `:1243-1253` already describes).
3. `readinessNotice.ts:326-331` — remove the `index.rebuilding` row **after** its
   wording lands in the transition wording.
4. `readinessNotice.ts:414-415` — remove from `RETRIEVAL_IMPAIRING_CODES`.
5. Tests referencing the code (`StatusLifecycleHandlerTest`, `readinessNotice`
   FE tests) — update, do not delete: convert them into assertions that the
   *transition* now carries the source.
6. Re-run `node scripts/ci/check-readiness-reason-codes.mjs`.

Consumers of Authority B that must keep working (each verified as reading
`stability`, not the code): `aiStateStore.ts:794-818` (the sole wiring point) and
`:984-999` (`stampSettledIndex`, holds doc counts steady during a rebuild),
`HealthSurface.ts:530,816-821` (the blue/green progress row),
`folderStatus.ts:66,175`. Adding a facet to the cause is additive for all of
them; none switch exhaustively on the cause string in a way a new value breaks —
**verify that claim with `tsc` + the FE suite before landing**, it is the one
place this design would fail loudly.

### 2.5 Out of scope but found here

`MigrationStatusGroup` (top-level `StatusResponse.migration`,
`StatusResponse.java:70`, built at `StatusLifecycleHandler.java:566`) has **zero**
FE consumers while `worker.migration` has all of them. A produced-and-unread wire
group is retirement bait, but it is not this tempdoc's subject. Logged to the
observations inbox; do not fold it into (b).

---

## 3. Fix (c) — worker lost ≠ worker never started

### 3.1 Where the distinction already lives

No new state is required. Each call site already knows which case it is, because
each one either runs before first-ready or explicitly checks `current == READY`:

| Site | Case | Code to pass |
|---|---|---|
| `KnowledgeServerBootstrap.java:254` (health budget elapsed at start) | never started | `WORKER_SPAWN_FAILED` |
| `KnowledgeServerBootstrap.java:263-264` (start threw) | never started | `WORKER_SPAWN_FAILED` |
| `KnowledgeServerBootstrap.java:303-304` (retry budget exhausted) | never started | `WORKER_SPAWN_FAILED` |
| `KnowledgeServerBootstrap.java:613-614` (`!healthy && current == READY`) | **lost** | `WORKER_LOST` |
| `KnowledgeServerHealthMonitor.java:110-111` (tick threw, `health() == READY`) | **lost** | `WORKER_LOST` |
| `CoreApiAssembly.java:537` (`createFailedWorkerCapability`) | never started | `WORKER_SPAWN_FAILED` |
| `HeadlessApp.java:449-452` (start error at boot) | never started | `WORKER_SPAWN_FAILED` |
| `CoreApiAssembly.java:531`, `HeadlessApp.java:455-457` | not configured | `WORKER_NOT_CONFIGURED` (exists) |
| `KnowledgeServerBootstrap.java:666` | orderly shutdown | `WORKER_SHUT_DOWN` (new) or null+detail |
| `KnowledgeServerBootstrap.java:160` | starting | `WORKER_STARTING` (exists) |

**Corruption is an orthogonal axis, not a sixth row.** The first draft listed
`workerDownReason` as its own call site; it is not — it is a *shared helper*
(`KnowledgeServerBootstrap.java:588-596`) invoked by four of the rows above
(`:254`, `:263-264`, `:303-304`, `:614`). So the real shape is **5 sites × 2
axes** (never-started / lost × normal / corrupt-index), and the corruption axis is
resolved inside the helper, renamed:

```java
// Was: String workerDownReason(String genericProse)
private String workerDownCode(LifecycleReasonCode generic) {
  String fatal = WorkerFatalReasonMarker.readAndClear(config.dataDir());
  return WorkerFatalReasonMarker.INDEX_CORRUPT.equals(fatal)
      ? LifecycleReasonCode.WORKER_INDEX_CORRUPT
      : generic;
}
```

Each of the four callers passes its own axis-1 code (`WORKER_SPAWN_FAILED` at
`:254/:263/:303`, `WORKER_LOST` at `:614`) and the helper overrides on corruption.
The remedy paragraph the old helper returned becomes the `detail` string (§0.2),
so it still reaches the Health-event message and the 503 body — it stops being
the *code*, it does not stop existing.

**And it must be latched** — see §1.4: `readAndClear` deletes the marker, so the
corrupt cause is readable exactly once and a later generic overwrite destroys it
permanently. The precedence/latch rule is a prerequisite of this fix, not an
adjacent nicety.

Do **not** try to derive this from `WorkerCapability.generation()`
(`WorkerCapability.java:55-61,72-79`): `generation` is set to 1 on the first
transition out of `PENDING` **regardless of whether that transition was to
READY** (`:74-75`), so `PENDING → DEGRADED` at boot already reports
`generation == 1`. Using it would be a textbook `wrong-gate`. The call sites are
the honest source.

### 3.2 The consumer side

Replace the inlined one-code special case (`StatusLifecycleHandler.java:1109-1113`)
with a `resolveWorkerReasonCode(workerCapability)` mirroring
`resolveInferenceReasonCode` (`:1051-1055`) — pass a known code through,
fall back to `WORKER_SPAWN_FAILED`.

**Retraction (review finding).** The first draft claimed the fallback becomes
"unreachable in production" after the sweep. That was false as written: it
silently assumed the sweep covered only `KnowledgeServerBootstrap` +
`KnowledgeServerHealthMonitor`, while `CoreApiAssembly.java:537` and
`HeadlessApp.java:449-452` also write prose on a **DEGRADED** worker transition
and would keep the fallback live. The claim is now **made true rather than
retracted**: both sites are in the sweep table above. State the invariant as a
consequence of the enumerated sweep — "all **13 reason-bearing** non-test worker
`transition(` sites pass a code" (15 worker sites total: `KnowledgeServerBootstrap`
×10, `KnowledgeServerHealthMonitor` ×1, `CoreApiAssembly` ×2, `HeadlessApp` ×2;
two of the ten set `READY` with a null reason and are not in scope) — never as an
unbacked assertion, and let the §5 producer gate be what keeps it true.

`LIFECYCLE_STATE` mapping: `worker.lost` and `worker.index_corrupt` are
`LIFECYCLE_STATE_ERROR` like the current DEGRADED arm; `worker.recovering` keeps
its calmer `LIFECYCLE_STATE_DEGRADED` (`:1117-1118`).

### 3.3 New codes and rows

- **`WORKER_LOST("worker.lost")`** — the gap between "it stopped answering" and
  the supervisor's `worker.recovering` / terminal `worker.restart_exhausted`
  (both already exist, `LifecycleReasonCode.java:33,37`). Remedy: none (a restart
  is already being attempted); severity `error`.
  Wording: `'The knowledge server stopped responding'`.
- **`WORKER_INDEX_CORRUPT("worker.index_corrupt")`** — the highest-value new code
  in this whole tempdoc, because a concrete remedy sentence currently exists in
  the code and is deleted before the user sees it
  (`KnowledgeServerBootstrap.java:588-595`). Severity `error`. Wording:
  `'The search index is corrupt and could not be repaired automatically'`,
  remedy: Open Health (there is no one-click operation for
  `index.recovery.policy=BACKUP_REBUILD` today — do not point at an operation
  that does not exist; that is the `vdu.missing_mmproj` precedent at
  `readinessNotice.ts:109-112`). The config sentence belongs on the Health
  surface as detail, which is the §0 `detail`-field argument in miniature.
- `worker.spawn.failed` keeps its row and wording — it becomes *true* for the
  first time, since it will now only fire when the worker actually failed to
  start.

Both new codes join `RETRIEVAL_IMPAIRING_CODES` (`readinessNotice.ts:406-425`)
alongside their siblings `worker.spawn.failed` / `worker.restart_exhausted` — a
non-serving knowledge server genuinely impairs retrieval, and omitting them would
let the banner claim search is fully working while nothing is serving.

### 3.4 The third silent seam (do not skip)

`LifecycleSnapshotTap.MAPPING_TABLE` (`LifecycleSnapshotTap.java:109-119`) maps
`(ReadinessDimension, state, reasonCode)` → a Condition; the lookup is a plain
`MAPPING_TABLE.get(...)` (`:380`) and an unmapped key produces **no Condition**,
only a once-per-startup WARN (`:305-311`, `warnedKeys`). So a new code without a
tap row is silently absent from the conditions store and Health-event stream even
though the banner words it fine. Every code added by this tempdoc needs a tap row
decision — add one, or record why the dimension does not carry conditions.

### 3.5 Tests that assert the prose verbatim (part of the sweep, not collateral)

Six existing assertions pin the exact strings this design removes. They are
**correct today** and must be *converted*, not deleted — each becomes the
assertion that the right *code* is now set (`fix-root-causes-not-symptoms`: the
test is right, the code under it is what changes):

| Test | Asserts |
|---|---|
| `WorkerCapabilityBridgeTest.java:47` | `"Worker not yet connected"` (the field default, `WorkerCapability.java:26`) |
| `InferenceCapabilityWiringTest.java:77` | `"Inference offline"` |
| `InferenceCapabilityWiringTest.java:89` | `"GPU allocated to indexing"` |
| `InferenceCapabilityWiringTest.java:101` | `"Inference transitioning"` |
| `InferenceCapabilityWiringTest.java:65,143` | `RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND` |

The last is the useful one: it asserts the *shared constant*, so once S4 promotes
that constant to a real code it goes on asserting the same cross-surface
agreement it was written to protect (`InferenceCapabilityWiring.java:60-63`).
Whichever slice touches a producer converts its tests in the same commit — a red
suite between slices is how a partial sweep becomes permanent.

---

## 4. Fix (d) — the four `ORT_CUDA_*` codes: DELETE, with evidence

**Evidence.**

- Zero references to `ORT_CUDA_NOT_CONFIGURED` / `ORT_CUDA_READY` /
  `ORT_CUDA_MISSING_DLLS` / `ORT_CUDA_PROVIDER_FAILED` anywhere in `modules/`
  outside their own declarations (`LifecycleReasonCode.java:93-96`) — verified
  independently with `git grep -F "ORT_CUDA_"`, empty result. No producer, no
  consumer, no test.
- Provenance is the in-file marker `// --- F1: ORT CUDA (GPU reranking) ---`
  (`LifecycleReasonCode.java:92`). The same "F1" label appears on
  `CrossEncoderReranker.getOrtCudaStatus` and `OrtCudaStatus`, whose javadoc
  states the intent: surface GPU-reranking status via `/api/status` "to help
  users understand why GPU reranking may not be working." Git history cannot
  narrow it further — ADR-0045 squash-merging means `git log -S` resolves only to
  the initial public-release squash `29579e51`.
- **The concept already has a live, richer authority.**
  `OrtCudaStatus` (`modules/ort-common/.../OrtCudaStatus.java`) → `OrtCudaView`
  (`modules/app-api/.../status/OrtCudaView.java`) → `GpuDiagnosticsView`
  (six per-encoder slots: reranker / splade / embed / ner / citation / bgeM3) →
  `WorkerOperationalView.gpu`. It carries `variantId`, `nativePath`, a free-text
  `failureReason` and `missingDlls[]` **per encoder**, and it is already in the
  generated FE wire schema (`modules/ui-web/src/api/generated/schema-types/status-response.ts:48,329-340`).
- Producing the codes today is not a missing wire, it is unsolved design: the
  status is **per-encoder** (six independent GPU sessions) while a
  `LifecycleReasonCode` is one global readiness slot. And
  `OrtCudaStatus.missingDlls(...)` is called only from test code —
  `NativeSessionHandle.java:658-668` collapses every failure into
  `providerFailed`, so `ort_cuda.missing_dlls` could not be produced faithfully
  even if we wired it.
- Producing them would also add rows that can never reach the banner: GPU
  acceleration falling back to CPU is "slower, not broken" ⇒ `info`, and
  `warrantsSearchDegradationBanner` (`readinessNotice.ts:696-699`) withholds
  info-only verdicts by design (round-14 finding 9 — a permanent optional gap
  holding banner space trains alarm-blindness).

**Verdict: delete all four.** Sweep, all verified as the complete reference set:

1. `LifecycleReasonCode.java:92-96` — the four members and the F1 comment.
2. `governance/readiness-reason-codes.v1.json:21-22` — the two `noWordingExempt`
   entries.
3. `readinessNotice.ts:211-221` — the two `CAUSE_ROWS` rows (`ort_cuda.missing_dlls`,
   `ort_cuda.provider_failed`) and their shared comment. These currently satisfy
   the gate's FORWARD direction while corresponding to nothing.
4. Re-run `check-readiness-reason-codes`.

Nothing else references them. If GPU-acceleration health should be user-visible,
the honest route is a Health-surface consumer of `GpuDiagnosticsView` (which has
six real values to show), not a one-bit collapse into the readiness vocabulary —
and that is a separate product decision, explicitly not this tempdoc's.

---

## 5. The gate upgrade — from name-pairing to emit-site existence

### 5.1 What today's gate does and does not do

`scripts/ci/check-readiness-reason-codes.mjs` (124 lines) checks two directions,
both purely nominal: FORWARD (every non-exempt enum code has a `CAUSE_ROWS` row)
and BACKWARD (every row is a real member or declared `feDerived`). It never asks
whether anything *emits* the code. That is how four phantom codes survived, and
how two of them acquired user-facing wording for a state that cannot occur.

**Also, and this matters more than the missing direction: the gate does not run
in CI.** Grepped every workflow — `check-readiness-reason-codes.mjs` appears in
`CLAUDE.md`'s pre-merge table, this register, the canonical contract doc, and
three tempdocs; **no `.github/workflows/*.yml` invokes it**, and its sibling
`check-readiness-reason-codes.test.mjs` is likewise unwired (the known
`scripts/ci/**/*.test.mjs` discovery gap already in the observations store). An
emit-site gate that nobody runs is prose, not enforcement
(`before-appending-to-rules`: a load-bearing must belongs in a gate, ~100%
adherence, not more prose at ~70%). **Wiring both into `ci.yml` is part of this
deliverable, not a follow-up.** The pattern already used there is a pair of steps
(`node scripts/ci/X.test.mjs` then `node scripts/ci/X.mjs`), e.g. `ci.yml:205,208`.

### 5.2 The probe, run

Before designing the check, run the check by hand — the phantom-ID pre-flight
from `slice-execution.md`. Scanned all **1,550** `modules/**/src/main/**/*.java`
files except the enum's own, **with comments stripped**, matching
`LifecycleReasonCode.<NAME>` or the quoted string `"<code>"`. (The first draft ran
this comment-*blind*; re-run per §5.3 because a comment-blind probe cannot
distinguish a producer from a javadoc mention — the numbers below are the
comment-stripped ones and are the ones to trust.) Result:

- **44 of 48 have ≥1 real producer reference in `src/main`.**
- **4 have zero references anywhere: exactly the four `ORT_CUDA_*`.**
- **0 are string-literal-only** — every code with a literal reference also has an
  enum-name reference. This is the measurement behind the corrected
  string-matching rationale two bullets below.
- The result is *unchanged* by comment stripping, which is itself worth
  recording: no code in this vocabulary is propped up by a comment today.

So after §4's deletion, the new direction passes **with an empty exemption
list**. That is the strongest possible evidence for the design: the check is not
aspirational, the codebase is one deletion away from satisfying it.

The probe also mapped every code's real emit site, which is how §1–§3 were
grounded. Two structural facts fell out worth recording:

- Codes are sometimes defined as **string literals** far from the enum —
  `TikaOcrRuntime.java:18-20` (`"ocr.disabled"`, …) and
  `VduCapabilityState.java:8-11` (`"vdu.*"`) declare them worker/service side,
  with `StatusLifecycleHandler.java:1607-1614` mapping them back onto the enum.
  **Correction to the first draft:** matching the string value is *not* required
  to avoid false failures — every one of those codes also carries an enum-name
  reference on the mapping line (`case "ocr.disabled" -> LifecycleReasonCode.OCR_DISABLED.code()`,
  `:1607`), so a name-only check finds all 44 today. String matching stays in the
  design as **defense in depth** for the plausible future producer that emits the
  literal without ever naming the enum — which is precisely the shape
  `TikaOcrRuntime` / `VduCapabilityState` already have on the *producing* side.
  Cheap, and it fails safe.
- `LifecycleSnapshotTap.java:116-262` references many codes as **map keys**, not
  emissions. A reference is not proof of emission (§5.4).

### 5.3 The check

Extend the existing script — one register, one gate, no sibling to keep in sync
(the current file already exports pure functions and is unit-tested, so the
extension is testable in the same style):

```js
/**
 * PRODUCER (tempdoc 837) — every non-feDerived enum code must be referenced by at least one
 * `modules/**​/src/main/**` source outside the enum's own file, by enum NAME or by its quoted
 * code string. A code nothing can emit is a phantom: it cannot degrade anything, its wording
 * row is unreachable UI, and it makes the vocabulary lie about what this system can report.
 */
export function checkProducers({ enumRows, mainSources, feDerived }) { … }
```

- **Input**: `enumRows` (name + code, from the existing `extractEnumCodes`, widened
  to return both), and the text of every `modules/*/src/main/**/*.java` file
  except `LifecycleReasonCode.java` (walk the tree; do not shell out to `git
  grep`, so the check runs the same in CI and on a dirty worktree).
- **Match**: `LifecycleReasonCode.<NAME>` **or** `"<code>"`, **after stripping
  comments**. A plain substring scan counts javadoc mentions as producers — and
  this codebase is dense with them: `LifecycleReasonCode.java:68` names
  `ai.pendingReason`, `StatusLifecycleHandler.java:1044-1049` names
  `INFERENCE_OFFLINE` in prose, `RuntimeStatus.java:112-115` names
  `TransitionReason`. A code whose only "producer" is a comment describing it as
  unimplemented is **exactly the phantom this gate exists to catch**, so a
  comment-blind check would certify the bug it was built for. Strip `//` and
  `/* … */` (a small line-oriented pass is sufficient for this corpus; note in
  the header that it is heuristic, since a `//` inside a string literal is
  possible in principle and harmless here).
- **Exemption**: the existing `feDerived` list only. `noWordingExempt` does
  **not** exempt from this direction — a code can legitimately never reach the
  banner and still need a producer (`worker.unavailable` has one, `:1418`); the
  two lists answer different questions and conflating them would re-open the hole.
- **No new allowlist.** If a future code genuinely needs one, that is a design
  conversation, and the failure message should say so rather than invite a
  one-line exemption.

Failure message (the gate's real product — it must name the decision, not the
symptom):

```
✗ readiness-reason-codes gate FAILED (producer direction, tempdoc 837):
  - producer: reason code `ort_cuda.missing_dlls` (ORT_CUDA_MISSING_DLLS) has NO emit site —
    no file under modules/*/src/main references `LifecycleReasonCode.ORT_CUDA_MISSING_DLLS`
    or the literal "ort_cuda.missing_dlls". A code nothing emits is a phantom: its CAUSE_ROWS
    wording is unreachable UI and the vocabulary claims a state this system cannot report.
    PRODUCE it (add the emit site) or DELETE it (enum member + CAUSE_ROWS row + any
    governance/readiness-reason-codes.v1.json entry — the full sweep). If it is FE-only,
    declare it in feDerived with a one-line rationale.
```

### 5.4 Honest limits (state them in the script, as the existing gate does)

- **Reference ≠ emission.** `WORKER_RESTART_EXHAUSTED.code().equals(...)`
  (`StatusLifecycleHandler.java:1111`) is a *consumer* reference and would satisfy
  the check. Syntactic emit-shape detection was considered and rejected as
  brittle; the check catches the *zero*-reference class, which is the class that
  actually occurred (4/4 of the real phantoms). Say this in the header comment so
  nobody later mistakes the gate for stronger than it is.
- **Tap coverage is a separate seam** (§3.4). A code can have an emit site, a
  wording row, and still produce no Condition. Adding a third direction for
  `LifecycleSnapshotTap` needs a `(dimension, state, code)` triple register and is
  a bigger artifact than this batch justifies — **name it in the register note as
  a known uncovered seam** rather than half-building it.
- **Wording quality stays prose-tier.** The gate can prove a row exists; it
  cannot prove the row is true. §7 says what carries that.

---

## 6. Shipping shape

Each slice is independently shippable, with its own tests and its own sweep. In
recommended order.

**Acceptance criterion shared by S3, S4 and S5 (from §0.2 — do not let a slice
close without it):** the slice's new codes must be asserted on **both** wire
surfaces, not just `/api/status`. `/api/runtime/manifest`'s `ai.pendingReason`
and worker-failure reason are published raw
(`RuntimeManifestListenerWiring.java:90-97,137-142,155-162`) and read by
`scripts/dev/doctor.mjs:207`. Each slice states the before/after string for that
field in its PR description — it is a public contract change, and "the doctor
started printing a code" must be an intended, recorded outcome rather than a
surprise. Where a slice converts a prose site, it also states what moved into the
`detail` field so the Condition message (`CapabilityHealthBridge.java:58,73,86,91`)
does not silently degrade to a bare code.

**S1 — Fix (d), delete the phantoms.** Pure deletion, ~4 files. Test: the
existing gate stays green with two fewer exempt entries. No live verification.
*First because it makes S2's empty-exemption claim true.*

**S2 — The gate upgrade + CI wiring.** Extend the script, extend
`check-readiness-reason-codes.test.mjs` (unit-test `checkProducers` with a
synthetic phantom and a string-literal-only producer, both directions), wire the
check **and** its sibling test into `ci.yml`. Test: gate green on `main` as it
stands after S1; gate red when a phantom is reintroduced (assert the red — an
unreachable-green gate is `unreachable-seed-green`). No live verification.
*Second so every later slice's new codes are enforced on arrival.*

**S3 — Fix (c), worker lost vs never-started.** Call-site codes at **all 13
reason-bearing** non-test worker `transition(` sites (§3.1/§3.2 —
`KnowledgeServerBootstrap` ×8 of 10, `KnowledgeServerHealthMonitor` ×1,
`CoreApiAssembly` ×2, `HeadlessApp` ×2), the
`workerDownCode(generic)` helper rename (§3.3), the `resolveWorkerReasonCode`
consumer, 2-3 new codes, FE rows, `RETRIEVAL_IMPAIRING_CODES` membership, tap
rows, and the `WorkerCapabilityBridgeTest.java:47` conversion (§3.5). Tests: unit
tests driving each call site and asserting the component's `reason_code`; the
corrupt-marker latch test (§1.4 — assert the corrupt code survives a subsequent
generic transition, since `readAndClear` makes the loss permanent); an FE test
that the new codes word and classify correctly.
*Live scenario (needs the shared dev-stack lease):* **kill the Worker process
while the stack is READY** and confirm `/api/status` reports `worker.lost` (not
`worker.spawn.failed`), then that the supervisor's restart flips it to
`worker.recovering` → READY. Second scenario: **start the Head with a
deliberately unstartable worker** (bad worker path) and confirm
`worker.spawn.failed`. Both are `/api/debug/state` + `/api/status` reads, no UI
needed; ~10 minutes of lease.

**S4 — Fix (a) part 1, the no-new-signal codes.** `INFERENCE_GPU_YIELDED_TO_INDEXING`,
`INFERENCE_UP_FOR_BACKGROUND`, `INFERENCE_STARTING` reuse for `TRANSITIONING`.
Three switch arms, three rows, `AI_MODEL_UNAVAILABLE_CODES` membership. Unit
tests on `deriveAndApply` per arm.
*Live scenario:* **trigger indexing while the engine is online** and confirm the
banner/Health say "GPU is indexing" rather than "model is offline"; **toggle
chat off while a VDU procedure holds the engine up** for the background case (the
harder of the two to stage — if staging it costs more than an hour, unit-test the
derivation and record the live check as deferred, explicitly, rather than
claiming it).

**S5 — Fix (a) part 2, `TransitionReason` threading.** Move the enum to
`app-api`, add the 3-arg default method, call it from `TransitionRunner`, add
`INFERENCE_CRASHED` / `INFERENCE_DEACTIVATED` + rows, add the §1.4 precedence
rule, settle UR-4 (§1.5) and — if the recommended option is taken — add
`verdict.ts` to `governance/consequence-classification.v1.json`'s `consumers`.
Tests: a unit test per reason→code mapping; **the precedence test with both
orderings** (activation-failure-then-mode-change and the reverse); and **the
spec-toggle regression** — set `INFERENCE_CRASHED`, then fire the
`chatEnabled` spec-change re-derivation (`InferenceCapabilityWiring.java:98-101`,
which passes `UNKNOWN`) and assert the crash cause survives. That third test is
the point of the slice: without the precedence rule it fails, and without the
test the rule silently rots.
*Live scenario:* **kill `llama-server.exe` while chat is online** and confirm
`inference.crashed`, then **deactivate the runtime from the UI** and confirm
`inference.deactivated`. These two back-to-back are the highest-value live
window in the batch (~15 minutes), because the distinction they prove is
invisible to every static check.

**S6 — Fix (b), the largest.** The `MigrationSource` vocabulary as a **read-side
total mapping with an `UNKNOWN` fallback** plus write-side enforcement at the
`IndexingController.java:401` REST boundary (§2.3(i) — not a strict write-side
enum, which would break six test drivers and every pre-existing on-disk
manifest), the FE facet + `verdict.reasons` token + wording move (§2.3(ii)), and
the `INDEX_REBUILDING` retirement sweep. Tests: read-side mapping tests including
an unmapped legacy string landing on `UNKNOWN`; FE `verdict.test.ts` cases per
source; **regression tests that the stuck-rebuild escalation (`verdict.ts:213`)
and the progress row (`HealthSurface.ts:818`) still fire** — the two things the
struck flat-token design would have silently disabled; and a test that the
corruption wording still appears (moved, not lost).
*Live scenario:* **trigger `core.rebuild-index` from the UI** and confirm the
transitioning wording names the user-requested source; the corrupt-index source
is hard to stage honestly (it needs a genuinely corrupt index) — **use the
fixtures path rather than faking a marker**, and if that is not available, say so
in the closure note instead of asserting coverage.

Orchestrator note for lease windows: S3 and S5 are the two that genuinely need a
live stack, ~10 and ~15 minutes, and both are process-kill scenarios that should
not overlap with another agent's measurement run. S4's second scenario and S6's
corruption scenario are the two where "we could not stage it" is an acceptable,
*recorded* outcome — but only if recorded.

---

## 7. Risks and non-goals

**Wording is presentation-authority, and this batch adds six user-facing
sentences.** The 830 banner renders them to users, and `slice-execution.md`'s
`ux-audit-closure` expects an independent, *measured* whole-screen audit for
presentation-authority work (honor-system since 563, not gate-enforced). The bar
for each new row: (1) it must be **true** in the state that emits it — the
current `inference.offline`-for-a-running-engine is the counterexample this
tempdoc exists to fix, so shipping a new false row would be self-defeating;
(2) severity must be chosen against its **three** consumers (banner
inclusion, chat message routing, affordance caveat), not as a label; (3) the
consequence-set memberships (`AI_MODEL_UNAVAILABLE_CODES`,
`RETRIEVAL_IMPAIRING_CODES`) must be decided explicitly, because omission is not
neutral — it silently selects `cosmetic` (§1.5) or `impairing` (§3.3) for you.

**Risk: severity inflation.** Four of the new codes are `info` precisely so they
do not enter the banner. If a reviewer "upgrades" them to `warn` for visibility,
the batch makes the banner *worse* than the collapsed state it replaced — and
UR-4 (§1.5) shows the second-order cost: a `warn` non-retrieval code also makes
`verdictBody` assert "Retrieval is degraded" on a different surface. Name this in
the PR description.

**Risk: the observable wire change goes unannounced.** `/api/runtime/manifest`'s
`ai.pendingReason` flips from prose to a code for a reader that already exists
(`scripts/dev/doctor.mjs:207`). It is an improvement and it is still a contract
change; §6's shared acceptance criterion exists so no slice lands it silently.

**Risk: S6 breaks a stability consumer.** The cause-facet change touches the one
wiring point every FE surface reads (`aiStateStore.ts:794-818`). Mitigation:
`npm run typecheck && npm run test:unit:run` is not optional for that slice, and
the four consumers in §2.4 get a read-through before the diff is written.

**Risk: the `TransitionReason` move is wider than it looks — and may be
avoidable entirely.** It is an `app-inference` → `app-api` package move with ~20
import sites and a same-named sibling type in `ort-common`. Probe 3 option 3 (a
reason-bearing listener declared *inside* `app-inference`, which `app-services`
already depends on) removes the move altogether; probe it before accepting this
risk at all.

**Non-goal — the RuntimeStatus Phase-3 join (D-2): OUT of scope, with one
exception.** `RuntimeStatus`'s internal reason strings
(`RuntimeStatus.java:87-103`) are explicitly documented as "Phase-1/2 strings;
not `LifecycleReasonCode` members yet" (`:19-21`). Joining all of them means
paired FE rows for `convergence-held-flap-suspected`, `procedure-active`,
`lease-holder`, etc. — a different vocabulary about a different observer, and
folding it in would double this batch and dilute its evidence. The **one**
exception is `REASON_ENGINE_UP_FOR_BACKGROUND`, which S4 promotes to a real code
because the *same fact* is already being mis-worded on the capability path
(`InferenceCapabilityWiring.java:60-63` says the two surfaces are meant to
agree). Everything else in `RuntimeStatus` stays internal, and `:19-21`'s comment
should be updated to say "except `engine-up-for-background-processing`, joined by
837" rather than left to drift.

**Non-goal — `RuntimeStatus.deriveEngine`'s Recovering refinement (also D-2).**
S5 makes it *possible* (the signal arrives); actually refining the ENGINE axis is
a runtime-state-register change with its own consumers. Record it as unblocked;
do not do it here.

**Non-goal — GPU diagnostics as readiness.** §4 deletes the codes; it does not
build the Health consumer for `GpuDiagnosticsView`. Separate product call.

**Non-goal — `MigrationStatusGroup` retirement** (§2.5). Logged, not folded in.

---

## 8. Probes before commitment (ranked)

1. **Does the emit-site check pass with an empty exemption list after the
   ORT_CUDA deletion?** — *Already run* (§5.2): 44/48 have producers, the 4
   phantoms are exactly the ORT_CUDA set. This is the probe that validated the
   whole gate design; re-run it as the first step of S2 against the
   post-S1 tree to confirm nothing drifted.
2. **D-1: typed reason slot, or disciplined string? — the independent review
   concurs with the typed slot; this probe now sizes it, it does not decide it.**
   The first draft set its threshold ("take it under ~20 sites") against an
   undercount of 17. True blast radius is **~26-28 touched sites**: 22 non-test
   `transition(` call sites across 7 files (counted:
   `grep -rn "\.transition(" --include=*.java modules/ | grep -v /build/ | grep -v /src/test/`
   → 22 after excluding the one comment hit in `WorkerCapability.java:82`;
   includes the `CoreApiAssembly` and `HeadlessApp` sites the first draft
   missed), 3 field/ctor initializers, 5
   prose-asserting tests (§3.5), and the manifest consumer (§0.2). Re-decide on
   that number — and note it argues *for* the typed slot: 26 sites is exactly the
   scale at which "we swept it by hand" stops being credible, which is what 656
   demonstrated when it swept a subset and left three raw consumers standing.
3. **How wide is the `TransitionReason` move?** `git grep -c TransitionReason`
   per module, plus a compile. **Three options, and option 3 is strictly smaller
   than the package move the first draft assumed:**
   1. move the enum to `app-api` (~20 import sites; the 518 P4 precedent);
   2. a narrow `app-api`-local reason enum that `TransitionRunner` maps onto — no
      move, but a second vocabulary to keep in sync (the fork risk);
   3. **a reason-bearing listener interface declared inside `app-inference`**,
      subscribed to by `app-services`. `app-services` already depends on
      `app-inference` (`InferenceCapabilityWiring.java:6` imports
      `io.justsearch.app.inference.InferenceLifecycleManager` directly), so the
      single consumer that needs the reason takes it where it already lives, and
      `app-api`'s `ModeChangeListener` is not touched at all. Zero moves, zero
      duplicate vocabulary. **Probe this one first** — if it holds, options 1-2
      are moot and §7's "the move is wider than it looks" risk evaporates.
4. ~~**Does any FE consumer switch exhaustively on the stability cause?**~~
   **ANSWERED (independent review): no.** Every use of `stability.cause` is
   equality narrowing, so §2.3's facet is genuinely additive. Kept here as the
   record of *why* it is safe — and the equality sites the question turned up
   (`verdict.ts:213`, `HealthSurface.ts:818`) are what killed the flat-token
   alternative.
5. **Is the corrupt-index rebuild stageable at all?** If `IndexRecoveryMarker` +
   the rebuild policy can be driven from fixtures, S6 gets a real live check; if
   not, S6 closes with an explicitly recorded coverage gap. Answer this *before*
   S6 is briefed, so the acceptance criteria are honest from the start.
6. **Does `/api/status` currently show a live example of the collapse?** Start the
   stack, put the engine in INDEXING mode, and read
   `readiness.composites.*.reasonCodes`. A before/after pair on one real capture
   is the most persuasive artifact this batch can produce, and it costs one lease
   window shared with S4's live check.

---

## Appendix A — verified source map

Every claim in this design traces to one of these, all re-read in this worktree
on 2026-08-14.

| Subject | Location |
|---|---|
| The 48-member vocabulary | `modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/LifecycleReasonCode.java:17-110` |
| `ORT_CUDA_*` members (no producer) | `LifecycleReasonCode.java:92-96` |
| Prose reasons written into the code slot | `modules/app-services/.../bootstrap/phases/InferenceCapabilityWiring.java:106-124` |
| The `isKnown` filter that deletes them | `modules/ui/src/main/java/io/justsearch/ui/api/StatusLifecycleHandler.java:1051-1055` |
| Worker DEGRADED two-branch special case | `StatusLifecycleHandler.java:1109-1113` |
| Inference component rollup | `StatusLifecycleHandler.java:1131-1140` |
| Worker lost (was READY) | `modules/app-services/.../worker/KnowledgeServerBootstrap.java:609-616` |
| Worker never started | `KnowledgeServerBootstrap.java:244-269`, `:300-309` |
| Corrupt-index remedy prose, discarded | `KnowledgeServerBootstrap.java:588-596` |
| Health-monitor tick exception path | `modules/app-services/.../worker/KnowledgeServerHealthMonitor.java:107-113` |
| `generation` is NOT "was ever READY" | `modules/app-services/.../lifecycle/WorkerCapability.java:72-79` |
| `TransitionReason` enum (10 members) | `modules/app-inference/.../telemetry/TransitionReason.java:9-40` |
| Crash recovery carries it | `modules/app-inference/.../InferenceLifecycleManager.java:210,226` |
| `ModeChangeListener` (2-arg) | `modules/app-api/src/main/java/io/justsearch/app/api/ModeChangeListener.java:42-45` |
| Module direction (`app-inference` → `app-api`) | `modules/app-inference/build.gradle.kts:10` |
| RuntimeStatus's own missing-signal note | `modules/app-services/.../runtimestate/RuntimeStatus.java:112-115` |
| RuntimeStatus Phase-3 join comment | `RuntimeStatus.java:19-21`, `:87-103` |
| Activation-failure precise codes | `modules/app-services/.../ai/runtime/RuntimeActivationService.java:1200-1225` |
| `INDEX_REBUILDING` sole emit site | `StatusLifecycleHandler.java:1254-1264` |
| Stability: MIGRATING/SWITCHING → provisional | `modules/ui-web/src/shell-v0/state/verdict.ts:124-130` |
| Provisional → `transitioning` (never degraded) | `verdict.ts:206-232`, degraded only at `:247-248` |
| Notice returns null unless degraded | `modules/ui-web/src/shell-v0/state/readinessNotice.ts:600` |
| `index.rebuilding` row (unreachable) | `readinessNotice.ts:326-331`, set membership `:414-415` |
| `migrationSource` free text | `modules/app-services/.../registry/operations/handlers/RebuildIndexHandler.java:66` |
| `CAUSE_ROWS` row shape | `readinessNotice.ts:66-75` |
| Consequence classification (the `cosmetic` trap) | `readinessNotice.ts:500-516`, sets at `:406-425,435-438,466-472` |
| Banner tier gate (info withheld) | `readinessNotice.ts:696-699` |
| Tap mapping table + silent unmapped keys | `modules/app-services/.../observability/health/LifecycleSnapshotTap.java:109-119,305-311,380` |
| **Raw** manifest consumers (`publishAi` / `publishWorkerFailed`) | `modules/ui/src/main/java/io/justsearch/ui/runtime/RuntimeManifestListenerWiring.java:90-97,137-142,155-162` |
| **Raw** 503-body consumer | `modules/ui/src/main/java/io/justsearch/ui/api/ApiSecurityFilters.java:452` |
| **Raw** Condition-message consumer | `modules/app-services/.../bootstrap/phases/CapabilityHealthBridge.java:58,73,86,91` |
| The manifest field's reader | `scripts/dev/doctor.mjs:207` (`manifest?.ai?.pendingReason ?? …reason_code`) |
| Prose producers outside the two named files | `modules/ui/src/main/java/io/justsearch/ui/api/CoreApiAssembly.java:531,537`; `modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java:449-452,455-457` |
| Field/ctor prose defaults | `WorkerCapability.java:26`; `InferenceCapability.java:21,30` |
| `workerDownReason` is a shared helper (4 callers) | `KnowledgeServerBootstrap.java:588-596` ← `:254,:263,:303,:614` |
| `readAndClear` DELETES the marker | `modules/ipc/.../WorkerFatalReasonMarker.java:55-67` (`Files.deleteIfExists`, `:65`) |
| Caller-controlled `migrationSource` | `modules/ui/src/main/java/io/justsearch/ui/api/IndexingController.java:401,410` |
| `migrationSource` read back from disk | `IndexStatusOps.java:586-594` (`readGenerationSourceBestEffort`) |
| Test-written migration sources | `MigrationControlE2ETest.java:98`, `PauseResumeMigrationE2ETest.java:80`, `RollbackE2ETest.java:85`, `SwitchingFenceBufferingE2ETest.java:92`, `IndexGenerationManagerRestartTest.java:42,67,75` |
| Equality gates that kill a flat cause token | `verdict.ts:213`; `HealthSurface.ts:815-818` |
| `paused`/`overdue` extra-token precedent | `verdict.ts:218,221` consumed at `:323-328` |
| `verdictBody`'s "Retrieval is degraded" (UR-4) | `verdict.ts:347-352` |
| `AI_MODEL_UNAVAILABLE_CODES` exclusion doctrine | `readinessNotice.ts:457-465` |
| Info branch short-circuits before classification | `readinessNotice.ts:619` (vs `classifyConsequence` at `:648`) |
| `consequence-classification` registered consumers | `governance/consequence-classification.v1.json` → `["…/state/availability.ts"]` |
| Prose-asserting tests | `WorkerCapabilityBridgeTest.java:47`; `InferenceCapabilityWiringTest.java:65,77,89,101,143` |
| Existing gate (name pairing only) | `scripts/ci/check-readiness-reason-codes.mjs:55-84` |
| Register + its two allow-lists | `governance/readiness-reason-codes.v1.json:12-29` |
| Gate is not wired into any workflow | no `.github/workflows/*.yml` match for `check-readiness-reason-codes` |

---

## Appendix B — implementation log: S1 + S2 (2026-08-14)

Branch `reason-codes-s1-s2`, based on `origin/main` @ `5da33b22`. Scope was exactly
S1 and S2 from §6; S3–S6 are untouched (no new codes, no `TransitionReason`
threading, no `migrationSource` work).

### B.1 — S1, the phantom deletion

| Change | Site |
|---|---|
| Four `ORT_CUDA_*` members + the `// --- F1: ORT CUDA (GPU reranking) ---` header deleted | `modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/LifecycleReasonCode.java:92-96` (pre-edit numbering) |
| Two unreachable `CAUSE_ROWS` rows + their shared comment deleted | `modules/ui-web/src/shell-v0/state/readinessNotice.ts:211-221` (pre-edit numbering) |
| Two `noWordingExempt` entries deleted | `governance/readiness-reason-codes.v1.json:21-22` (pre-edit numbering) |

**One sweep site beyond the design's list.** §4 enumerated three files. A fourth
reference existed: `LifecycleReasonCode.java:69`, a prose comment citing
"`VDU_MISSING_MMPROJ` / `ORT_CUDA_MISSING_DLLS` precedent for 'a required artifact is
absent'". Deleting the member would have left that sentence pointing at nothing — the
`retire-with-a-sweep` residue class. Narrowed to name only the surviving
`VDU_MISSING_MMPROJ`.

**Post-deletion sweep, re-verified (not inherited from the design's probe):**
`git grep -nF "ORT_CUDA_"` and `git grep -nF "ort_cuda."` over the tree excluding
`docs/tempdocs/` both return **empty** (exit 1). The surviving case-insensitive
`ort_cuda` hits are the unrelated per-encoder `GpuDiagnosticsView` slots
(`contracts/wire/status.proto:304-315`,
`modules/ipc-common/src/main/proto/indexing.proto:848-861`,
`modules/shell/src-tauri/src/lib.rs`, `IndexStatusOpsGpuDiagnosticsTest.java`) — the
"live, richer authority" §4 explicitly preserves. Tempdocs 600 and 656 mention the
codes as dated history and were left alone per `tempdocs-are-dated-history`.

### B.2 — S2, the producer direction

Extended the existing script rather than adding a sibling, per §5.3.
In `scripts/ci/check-readiness-reason-codes.mjs`:

- `extractEnumRows` (new export) returns `{name, code}` rows; `extractEnumCodes` is now
  a projection of it, so there is one extraction authority and the existing
  FORWARD/BACKWARD callers are unchanged.
- `stripJavaComments` (new export) blanks line and block comments **while preserving
  string, char and text-block literals**. The design allowed a line-oriented heuristic
  and flagged a `//` inside a string literal as a known-harmless caveat; a
  literal-aware single pass removes the caveat for the same effort, and it matters
  because a real line such as `log("see http://x " + LifecycleReasonCode.X.code())`
  would otherwise be truncated into a **false phantom**. Comments are replaced with
  spaces rather than deleted, so offsets stay stable and nothing can be spliced
  together across a removed comment.
- `checkProducers({enumRows, mainSources, feDerived})` matches
  `LifecycleReasonCode.<NAME>` (primary) or the quoted code string (defense in depth),
  after stripping. Exemption is `feDerived` only — `noWordingExempt` deliberately does
  not exempt. Failure message is the §5.3 text.

Two hardening changes came out of the post-implementation critical-analysis pass, both
closing a *false-pass* class the design did not name:

- **Name matching is word-boundary anchored**, not `includes`. A plain substring test
  would let a longer sibling satisfy a shorter code — a future
  `LifecycleReasonCode.WORKER_LOST_PERMANENTLY` reference would mark `WORKER_LOST` as
  produced. There are **0 prefix pairs among the 44 names today** (measured), so this is
  pre-emptive; the count stayed 44/44 after tightening, which confirms no existing code
  was passing only via a prefix match. Covered by a test.
- **The "N exempt" figure in the success line is computed**, not hardcoded to 0.
  Hardcoding would have made the gate's own output lie the moment someone added an enum
  member to `feDerived` — the gate would still be correct but would report otherwise.
- `collectMainSources` walks the tree directly (no `git grep`), skipping `build/`,
  `node_modules/`, `.gradle/` and the enum's own file. A guard fails the gate if the
  corpus comes back empty, so a tree-layout move cannot pass this direction vacuously.
- The register note now documents the PRODUCER direction, records that
  `noWordingExempt` does not exempt from it, and names the §5.4 uncovered seam
  (reference is not emission; `LifecycleSnapshotTap`'s dimension/state/code rows are
  unregistered).

**Empty exemption, measured on this tree** — not inherited from the design:

```
readiness-reason-codes gate OK — producer<->CAUSE_ROWS correspond (44 emittable codes,
40 worded rows); no raw code can reach the degradation banner. Producer direction OK —
all 44 codes have >=1 emit-site reference across 1551 modules/**/src/main sources; 0 exempt.
```

44 = 48 − 4, matching §5.2's probe exactly. No enum member appears in `feDerived` (all
four `feDerived` codes are FE-only, not enum members), so the effective exemption for
this direction is genuinely empty — asserted as a test, not merely observed.

### B.3 — CI wiring

`.github/workflows/ci.yml`: added the `.test.mjs`-then-`.mjs` step pair after the
`check-install-api-contract` steps (the pattern at `ci.yml:204-208`), with a comment
recording *why* — §5.1's finding that the gate had never run in CI since 600, which is
how the phantoms survived. `node scripts/ci/check-workflow-triggers.mjs` green.

### B.4 — Mutation probes (the gate is reachable-red)

`unreachable-seed-green` says a gate never observed failing is not known to work.
Three probes, all reverted:

1. **Real emit site removed.** Replaced the sole `TELEMETRY_UNAVAILABLE` emit site
   (`modules/ui/src/main/java/io/justsearch/ui/api/TelemetryHealthController.java:52`)
   with a literal. Gate went **RED** with the designed producer message; the `.test.mjs`
   sibling went **RED** on its live-repo assertion. *Second finding from this probe:*
   `telemetry.unavailable` **is** in `noWordingExempt` and still failed — direct
   evidence that the two allow-lists are not conflated, which §5.3 requires.
2. **Phantom reintroduced, bare.** Re-added `ORT_CUDA_READY` to the enum. Gate went
   **RED**, but on the *FORWARD* direction (no `CAUSE_ROWS` row), which short-circuits
   before the producer direction runs — so this probe does not actually exercise S2.
3. **Phantom reintroduced with its wording row** — the exact pre-S1 shape, enum member
   *and* `CAUSE_ROWS` row. FORWARD and BACKWARD both pass; the gate went **RED** on the
   producer direction naming `ort_cuda.missing_dlls`. This is the decisive probe: the
   gate reproduces and catches the precise bug it was built for, and probe 2 shows why
   the weaker version of the probe would have proved nothing.

The working tree was restored after each probe and `git status` verified clean of probe
residue.

### B.5 — Verification

| Check | Result |
|---|---|
| `./gradlew.bat spotlessApply` then `build -x test -PskipWebBuild=true` | BUILD SUCCESSFUL (run bare — no piped exit masking) |
| `:modules:app-api:test` + `:modules:ui:test` | BUILD SUCCESSFUL |
| `cd modules/ui-web && npm run typecheck` | clean |
| `npm run test:unit:run` | **421 files / 5109 tests passed** |
| `check-readiness-reason-codes.mjs` | green (44 codes, 0 exempt) |
| `check-readiness-reason-codes.test.mjs` | green, **19 assertions** (was 6) |
| `check-workflow-triggers.mjs` | green |
| ui-web gate set (32 gates) + 6 ui-web kernel gates | 4 pre-existing reds, see below; all others green |
| Full governance kernel (`run.mjs --mode gate`) | 73 findings, **0 touching any file in this diff** (SARIF-scanned) |
| UTF-8 check | every non-ASCII added line is intentional typography matching file conventions; no mojibake. Zero-width-space escapes were removed by rewording. |

New test coverage in `check-readiness-reason-codes.test.mjs`: phantom detection,
name-matching producer, string-literal-only producer, javadoc-only mention rejected,
line-comment-only mention rejected, longer-sibling name rejected (word boundary),
`feDerived` exemption, `//`-inside-string-literal preservation, block-comment offset
stability, live-repo producer closure with an empty exemption, and an S1 regression
asserting all three files are free of `ort_cuda`.

**Pre-existing reds, confirmed not caused by this change.** Four ui-web gates fail:
`check-theme-token-closure` and `check-accent-as-text` (both listed in
`expected-state.v1.json` as known-red on main), plus `check-controls-a11y`
(`UnifiedChatView.ts:2096`) and `strip-token-fallbacks --check` (`ActionLedgerView.ts`,
`RecentsMenu.ts`), which are **not** listed. Every finding is confined to
`UnifiedChatView.ts`, `ActionLedgerView.ts` and `RecentsMenu.ts`, and
`git diff origin/main --stat` on those three files is **empty** — they are byte-identical
to main, so the failures are definitionally pre-existing. Logged to the observations
shard, not fixed (out of scope). Also logged: `LambdaMartBenchmarkTest`'s p50-latency
assertion is load-sensitive (failed at 6.54ms against a 5ms threshold under concurrent
build load, passed in isolation on re-run).

### B.6 — Contract-change note

S1/S2 change no wire surface. The `/api/runtime/manifest` `ai.pendingReason`
before/after statement §6 requires applies to S3–S5, which convert prose sites; these
two slices delete codes nothing emitted and add a build-time check, so
`scripts/dev/doctor.mjs:207` sees no change.

### B.7 — Carried forward

- §5.4's tap-coverage seam is **named in the register, not built** — a code can have an
  emit site, a wording row, and still produce no Condition.
- The producer direction proves *reference*, not *emission*; a consumer-only reference
  satisfies it. Stated in the script header and in the register so nobody later
  mistakes the gate for stronger than it is.

---

## Appendix C — implementation log: S3 + S4 (2026-08-18)

Branch `reason-codes-s3-s4`, based on `origin/main` @ `010d59f8`. Scope was exactly S3
and S4 from §6. S5 (`TransitionReason` threading, `INFERENCE_CRASHED` /
`INFERENCE_DEACTIVATED`, the general §1.4 precedence rule, UR-4) and S6
(`migrationSource`) are untouched.

### C.1 — The typed slot (D-1, taken)

§0.2(ii) argued the typed slot over a disciplined string, because a string-only sweep
degrades the Health-event message from `"Health check failed after 4200ms"` to
`"worker.lost"`. Implemented as the minimal version of that: `pendingReason()` keeps its
`String` type but is now **only** a code, and a sibling
`Capability.pendingDetail()` (default `null`,
`modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/Capability.java:16-41`)
carries the sentence.

| Consumer | Before | After |
|---|---|---|
| `CapabilityHealthBridge.pushCondition` (`:107-150`) | Condition `message` = the reason slot (prose by accident) | `message` = `pendingDetail()`, falling back to the code when there is no sentence |
| `ApiSecurityFilters` 503 body (`:447-458`) | `"reason"` = prose or code | `"reason"` = code, **new** `"detail"` = sentence (additive; the body is an ad-hoc map, not a schema'd contract) |

`WorkerCapability` implements the pair; `InferenceCapability` inherits the interface
default (`null`) — S5 gives it a detail when it gains the reason signal.

### C.2 — S3: all 13 reason-bearing worker `transition(` sites

The §3.2 invariant is now a fact of the enumerated sweep, not an assertion: 15 worker
`transition(` sites in non-test sources, two of which set `READY` with a null reason,
leaving 13 that carry a reason — every one passes a `LifecycleReasonCode`.

| Site | Code | Detail |
|---|---|---|
| `KnowledgeServerBootstrap.java:160` | `WORKER_STARTING` | "Worker starting" |
| `KnowledgeServerBootstrap.java:208` (`onRecovering`) | `WORKER_RECOVERING` | the supervisor's sentence |
| `KnowledgeServerBootstrap.java:216` (`onGaveUp`) | `WORKER_RESTART_EXHAUSTED` | the supervisor's sentence (was dropped on the floor) |
| `KnowledgeServerBootstrap.java:263` (health budget elapsed) | `WORKER_SPAWN_FAILED` via `workerDownCode` | "Health check failed after …ms" |
| `KnowledgeServerBootstrap.java:274` (start threw) | `WORKER_SPAWN_FAILED` via `workerDownCode` | "Start failed: …" |
| `KnowledgeServerBootstrap.java:314` (retry budget) | `WORKER_SPAWN_FAILED` via `workerDownCode` | "Start failed: …" |
| `KnowledgeServerBootstrap.java:652` (`!healthy && current == READY`) | **`WORKER_LOST`** via `workerDownCode` | "Health check failed" |
| `KnowledgeServerBootstrap.java:704` (`closeForUpgrade`) | **`WORKER_SHUT_DOWN`** | "Worker shut down" |
| `KnowledgeServerHealthMonitor.java:111` (tick threw while READY) | **`WORKER_LOST`** | "Health monitor tick exception: …" |
| `CoreApiAssembly.java:533` / `:542` | `WORKER_NOT_CONFIGURED` / `WORKER_SPAWN_FAILED` | the prose that was the reason |
| `HeadlessApp.java:452` / `:459` | `WORKER_SPAWN_FAILED` / `WORKER_NOT_CONFIGURED` | same |
| `WorkerCapability.java:30` (field default) | **`WORKER_NOT_CONNECTED`** | none |

`workerDownReason(String)` → `workerDownCode(LifecycleReasonCode, String)` returning a
`WorkerDown(code, detail)` pair (`KnowledgeServerBootstrap.java:593-632`), applied by
`transitionWorkerDown`. §3.1's shape holds exactly: **5 sites × 2 axes**, axis 1 decided by
the call site, axis 2 (corruption) inside the shared helper. The corrupt-index remedy
paragraph is now `INDEX_CORRUPT_DETAIL` — it moved, it was not deleted.

**Consumer.** `resolveWorkerReasonCode(cap, fallback)`
(`StatusLifecycleHandler.java:1056-1071`) replaces the inlined one-code special case;
the DEGRADED arm falls back to `WORKER_SPAWN_FAILED`, the OFFLINE arm to
`WORKER_NOT_CONFIGURED`. `worker.spawn.failed` is TRUE for the first time.

### C.3 — The latch (the load-bearing part), and why it is narrower than §1.4's rule

§6 assigns the **general** precedence rule to S5 and asks S3 for "the corrupt-marker latch
test". That split is deliberate here, and the reason is a wrong-gate §1.4's literal wording
would have shipped: rule 2 retains the held reason whenever it is "a different known code",
and S3 converts `KnowledgeServerBootstrap.java:160` from prose to `WORKER_STARTING` — so
under the literal rule, the very next `PENDING → DEGRADED(worker.spawn.failed)` would
retain **`worker.starting`** and report a starting worker as the cause of a spawn failure.

Implemented instead exactly what §3.1's sentence asks for and nothing more
(`WorkerCapability.java:83-121`): while health is non-READY, a held `WORKER_INDEX_CORRUPT`
is retained against any incoming reason; `READY` clears it. That is the "retained … for the
lifetime of the capability's non-READY state (cleared on READY)" bound, and it is what makes
the fix *work* — the real corruption sequence is `worker.lost` → supervisor
`worker.recovering` → `worker.restart_exhausted`, and both of the latter are downstream
symptoms that would otherwise bury the cause with the marker already deleted. S5 still owns
the general rule; it should re-derive rule 2 against the `worker.starting` case above.

Listener firing follows the *effective* change, per §1.4: health changes always notify; a
rejected reason with no health change does not (asserted).

### C.4 — S4: the no-signal inference codes

| Arm | Was | Now |
|---|---|---|
| `ONLINE` + `chatEnabled=false` (`InferenceCapabilityWiring.java:117-120`) | `RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND` (internal string) | the same constant, whose **value** is now `INFERENCE_UP_FOR_BACKGROUND.code()` |
| `INDEXING` (`:129-133`) | `"GPU allocated to indexing"` | `INFERENCE_GPU_YIELDED_TO_INDEXING` |
| `TRANSITIONING` (`:124-126`) | `"Inference transitioning"` | `INFERENCE_STARTING` (reuse, per §6) |
| `OFFLINE` (`:121-123`) | `"Inference offline"` | `INFERENCE_OFFLINE` |

The `OFFLINE` arm is not on §6's S4 list; it is included because it needs no new signal and
no new code (the generic code carries exactly the information the prose did), and leaving it
would keep prose on `ai.pendingReason` for the most common non-READY state — defeating MF-1
for the slice. S5 replaces it with `offlineCode(reason)` as designed.

`RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND` (`RuntimeStatus.java:97-108`) is now defined
*as* the enum code, so §1.3's "the two surfaces go on agreeing" is true by construction and
`InferenceCapabilityWiringTest:65,143` keep asserting the shared constant, exactly as §3.5
predicted. Checked before changing it: no FE or contract consumer compares that value
(`grep` across `*.ts`/`*.json` found only `RuntimeStatus.java` and one test comment). The
**neighbouring** constant `REASON_GPU_YIELDED_TO_INDEXING` **is** equality-compared in the FE
(`aiStateStore.ts:489`, `aiVerdict.ts:224`) and was deliberately left alone.

**`AI_MODEL_UNAVAILABLE_CODES` reconciliation (§1.5).** Neither new code joins the set, and
the decision is recorded in the set's own doc comment: `inference.up_for_background`
describes an engine that is UP, and `inference.gpu_yielded_to_indexing` is the scheduled,
self-clearing sibling of the excluded policy cases. Both ship at `info`, and `readinessNotice`
short-circuits on an info-only verdict before `classifyConsequence` runs, so membership is
only ever consulted when one rides alongside a `warn` cause — where the calmer class must not
be inferred from a code that is not evidence for it. (`inference.crashed` joining the set is
S5's job, and remains required.)

### C.5 — FE rows and set memberships

Six new `CAUSE_ROWS` rows (`readinessNotice.ts`), each worded to be true in the state that
emits it:

| Code | Wording | Severity |
|---|---|---|
| `worker.lost` | The knowledge server stopped responding | `error` |
| `worker.index_corrupt` | The search index is corrupt and could not be repaired automatically | `error` |
| `worker.shut_down` | The knowledge server has shut down | `info` |
| `worker.not_connected` | The knowledge server has not connected yet | `info` |
| `inference.gpu_yielded_to_indexing` | The GPU is indexing your files — chat resumes when it finishes | `info` |
| `inference.up_for_background` | Chat is turned off; the AI engine is running background document processing | `info` |

All four worker codes join `RETRIEVAL_IMPAIRING_CODES` — §3.3's rationale ("a non-serving
knowledge server genuinely impairs retrieval") applies identically to the shutdown and
not-yet-connected states, which are the exact siblings of the already-listed
`worker.starting`. `worker.index_corrupt` declares **no** remedy, so the banner supplies the
Open-Health reference (the `vdu.missing_mmproj` precedent); pointing at a `core.rebuild-index`
operation would be pointing at something that does not implement
`index.recovery.policy=BACKUP_REBUILD`.

### C.6 — §3.4 tap rows (the third silent seam)

An unmapped `(dim, state, code)` key emits **no Condition**, only a once-per-startup WARN — so
every new code that can reach the readiness envelope needed a row or a recorded reason.

| Key | Mapping | Why |
|---|---|---|
| `WORKER_CONTROL_PLANE / NOT_READY / worker.lost` | `index.start-error`, worker, ERROR | the same Condition this state produced when it was collapsed onto `worker.spawn.failed` — behaviour-preserving; only the Condition's `reason` (`WorkerLost`) gets more precise |
| `WORKER_CONTROL_PLANE / NOT_READY / worker.index_corrupt` | `index.start-error`, worker, ERROR | same |
| `INDEX_SERVING / NOT_CONFIGURED / worker.shut_down` | `index.unavailable`, worker, WARNING | mirrors `worker.not_configured`, which is the verdict this state used to produce |

Two recorded non-rows: **`worker.not_connected`** cannot reach the envelope (the PENDING arm
publishes `worker.starting` unconditionally, `StatusLifecycleHandler.java:1140-1141`); and
`WORKER_CONTROL_PLANE / DEGRADED / worker.shut_down` has no row **because its predecessor
`worker.not_configured` has none either** — an OFFLINE worker produced no control-plane
Condition before this change and produces none after. Residual, honestly stated: the
conditionId `index.start-error` now also covers a worker that started fine and died. Renaming
it or minting a new catalog id is a health-event **catalog** change (`§A.2` + the canonical ID
list in `HealthEventEmitCoverageTest`) and is out of S3's scope; the id is a machine key, the
`reason` field carries the truth, and the alternative (`index.unavailable` on
`WORKER_CONTROL_PLANE`) would collide with `INDEX_SERVING`'s own `index.unavailable/worker`
key and flap the two dimensions against each other every tick — which is exactly why the
table's comment keeps `WORKER_CONTROL_PLANE` minimal.

### C.7 — MF-1: the observable wire change

| Surface | Before | After |
|---|---|---|
| `/api/runtime/manifest` `ai.pendingReason` (`RuntimeManifestListenerWiring.java:90-97,155-162`) | `"Inference offline"` / `"GPU allocated to indexing"` / `"Inference transitioning"` / `engine-up-for-background-processing` | `inference.offline` / `inference.gpu_yielded_to_indexing` / `inference.starting` / `inference.up_for_background` |
| `/api/runtime/manifest` worker-failure reason (`:137-142`) | `"Health check failed after 4200ms"` / `"Start failed: …"` / `"Worker shut down"` / `"Worker not configured"` | `worker.lost` / `worker.spawn.failed` / `worker.index_corrupt` / `worker.shut_down` / `worker.not_configured` |
| `/api/status` + `/api/health` `components.worker.reason_code` | `worker.spawn.failed` for every DEGRADED cause but one; `worker.not_configured` for every OFFLINE cause | the specific cause |
| `/api/health` `lifecycle.reason_code` | as above (it forwards the worker component, `StatusLifecycleHandler.java:1202-1214`) | the specific cause |
| `/api/status` `inference.engineReason` (soft-off only) | `engine-up-for-background-processing` | `inference.up_for_background` |
| capability-gated 503 body | `"reason"` prose | `"reason"` code + new `"detail"` prose |
| Health Condition `message` | the sentence (because the sentence was the reason) | the sentence (explicitly, via `pendingDetail()`) |

**`scripts/dev/doctor.mjs:207` re-read, not assumed.** It computes
`manifest?.ai?.pendingReason ?? status.components?.inference?.reason_code ?? null` and prints
it verbatim (`ai=${report.live.aiReason}`) — no parsing, no branching on the value. Behaviour
is therefore unchanged; the printed string becomes a code, which is what the 656 onramp
already assumed.

**Inference-side prose that S3/S4 do NOT convert** (stated so it is not mistaken for done):
`InferenceCapability.java:21,30`'s two defaults — `"Inference not yet activated"` and
`"Inference not configured"` — are still prose on `ai.pendingReason` in the PENDING /
unconfigured window. They are not on §6's S4 list and picking a code for them
(`INFERENCE_MODEL_NOT_CONFIGURED` is about a missing *model*, not an unconfigured capability)
is a vocabulary decision, not a mechanical sweep. Carried to S5.

### C.8 — Converted tests (§3.5), not deleted

| Test | Now asserts |
|---|---|
| `WorkerCapabilityBridgeTest.java:47` | `WORKER_NOT_CONNECTED.code()` + a null detail |
| `InferenceCapabilityWiringTest.java:77` | `INFERENCE_OFFLINE.code()` |
| `InferenceCapabilityWiringTest.java:89` | `INFERENCE_GPU_YIELDED_TO_INDEXING.code()` |
| `InferenceCapabilityWiringTest.java:101` | `INFERENCE_STARTING.code()` |
| `InferenceCapabilityWiringTest.java:65,143` | unchanged — they assert the shared constant, which is now a real code (§3.5's prediction, confirmed) |

New: `WorkerCapabilityCorruptLatchTest` (5 tests: both orderings, READY clears, rejected-write
fires no listener, prose never latches), `KnowledgeServerWorkerDownCodeTest` (5 tests: the two
axis-1 codes, the corruption override + marker deletion, a non-corruption fatal reason, and the
end-to-end latch across a restart sequence), `StatusLifecycleWorkerReasonTest` (8 tests: the
wire `reason_code` per producer state), 3 `LifecycleSnapshotTapTest` rows, 6
`readinessNotice.test.ts` cases, and one `StatusLifecycleHandlerTest` case pinning that an
orderly shutdown reaches `INDEX_SERVING` as `NOT_CONFIGURED` (C.9's second probe).

### C.9 — Mutation probe: the latch is reachable-red

`unreachable-seed-green`. Disabled the latch (`newHealth != READY` → `false`,
`WorkerCapability.java:103-105`) and re-ran the two suites:

```
WorkerCapabilityCorruptLatchTest > ordering 1: corrupt observed first …            FAILED
WorkerCapabilityCorruptLatchTest > a rejected reason write … fires no listener     FAILED
KnowledgeServerWorkerDownCodeTest > end-to-end latch: … restart-then-give-up       FAILED
17 tests completed, 3 failed
```

Ordering 2 (`corrupt overwrites an earlier generic cause`) correctly stayed **green** — it
asserts the non-latched direction, so a latch-only mutation must not move it. That asymmetry
is the test-precision signal: the suite fails for the right reason, not for any reason. Latch
restored and the full suite re-run green.

**Second probe — the `worker.shut_down` branch (found by the critical-analysis pass).** The
`INDEX_SERVING` `NOT_CONFIGURED` branch keys on the worker reason code, and S3 changes what an
orderly teardown puts there; if the branch had not been widened, a shutdown would fall through
to the ERROR-shaped branches and C.6's tap row would be **dead on arrival** — a wrong-gate the
tap test alone could not catch, because it asserts the mapping given the key, not that the key
is produced. Removed the `|| WORKER_SHUT_DOWN` clause
(`StatusLifecycleHandler.java:1395-1396`): `StatusLifecycleHandlerTest > tempdoc 837: an
orderly shutdown reaches INDEX_SERVING as NOT_CONFIGURED, not an error` went **RED**, and it is
the only test that moved. Restored; green.

### C.10 — Verification

| Check | Result |
|---|---|
| `./gradlew.bat spotlessApply` then `build -x test -PskipWebBuild=true` | BUILD SUCCESSFUL (run bare — no piped exit masking) |
| `./gradlew.bat test` (full unit suite, all modules) | BUILD SUCCESSFUL |
| `:modules:app-services:test` + `:modules:ui:test` + `:modules:app-api:test` | BUILD SUCCESSFUL |
| `check-readiness-reason-codes.mjs` | green — **50 emittable codes, 46 worded rows, 0 exempt**; producer direction green with all six new codes having real emit sites |
| `check-readiness-reason-codes.test.mjs` | green, 19 assertions |
| `cd modules/ui-web && npm run typecheck` | clean |
| `npm run test:unit:run` | **421 files / 5163 tests passed** |
| ui-web gate set (27 scripts) + 6 kernel gates | 3 pre-existing reds (below); all others green |
| UTF-8 check | every added non-ASCII character is intentional typography (em-dash, `§`, `→`, `≥`) matching file conventions; zero mojibake sequences in the diff |

**Pre-existing reds, confirmed not caused by this change** — `check-theme-token-closure`,
`check-accent-as-text` (both in `expected-state.v1.json`), `check-controls-a11y`
(`UnifiedChatView.ts:2096`) and `strip-token-fallbacks --check` (`ActionLedgerView.ts`,
`RecentsMenu.ts`). Every finding is confined to files this diff does not touch — the only
`modules/ui-web` files changed are `readinessNotice.ts` and its test. Identical to the set
S1/S2 recorded in §B.5.

### C.11 — Governed-region consults (no doc change needed)

- **`ApiSecurityFilters.java`** (threat-model region): the edit adds a `"detail"` field to an
  existing 503 diagnostic body on an already loopback-only surface. No CSP, Host/Origin, token
  filter, or egress behaviour changes, so
  `docs/reference/security/threat-model.md` needs no revision. The one thing worth a reviewer's
  eye is that `detail` can carry an exception message — the same class of content the Condition
  `message` and the log already carry on the same loopback surface.
- **`modules/ui-web/src/shell-v0/**`** (Lit / presentation-kernel region): rows added to an
  existing `CAUSE_ROWS` table and codes added to existing sets. No new presentation authority,
  no new component, no React — ADR-0032 and
  `docs/explanation/27-frontend-presentation-kernel.md` are unaffected.

### C.12 — Not verified (live scenarios), with the scripted procedure

§6 names S3's live check as a lease-window item; no dev stack was taken in this session, so
**both S3 scenarios and both S4 scenarios are PENDING, not passed.** The procedure, ready to
run inside one ~10-minute lease:

1. `quick_health`; if free, `justsearch_dev_start` with `leaseDurationSec: 900`.
2. Wait for worker READY: `fetch_api_json /api/health` until
   `components.worker.state == LIFECYCLE_STATE_READY`.
3. **S3 scenario 1 (lost):** kill the Worker process
   (`Get-Process java | Where-Object { $_.CommandLine -match 'IndexerWorker' }` → `Stop-Process`),
   then poll `/api/health` and assert `components.worker.reason_code == "worker.lost"` —
   **not** `worker.spawn.failed`, which is what this scenario reported before S3. Keep polling:
   the supervisor's restart must flip it to `worker.recovering`, then back to READY with a null
   reason.
4. **S3 scenario 2 (never started):** restart the stack with a deliberately unresolvable worker
   path and assert `worker.spawn.failed`.
5. **S4 scenario 1:** `ai_activate`, then `ingest` a corpus to drive `Mode.INDEXING`, and assert
   `components.inference.reason_code == "inference.gpu_yielded_to_indexing"` and that the banner
   does **not** claim the model is offline.
6. **S4 scenario 2 (the harder one, §6 permits recording it as deferred):** hold the engine ONLINE
   under a VDU procedure with `chatEnabled=false` and assert `inference.up_for_background` on both
   `components.inference.reason_code` and `inference.engineReason` — the cross-surface agreement
   §1.3 is about.
7. `justsearch_dev_stop`.

The corrupt-index path is covered by `KnowledgeServerWorkerDownCodeTest` writing a real
`WorkerFatalReasonMarker` and asserting the Head reads, clears and latches it — the marker
contract end-to-end minus the dying worker itself.

### C.13 — Carried forward to S5

- The general §1.4 precedence rule, with the `worker.starting` wrong-gate in C.3 re-derived
  before it is written.
- `INFERENCE_CRASHED` must join `AI_MODEL_UNAVAILABLE_CODES` (§1.5's mandatory companion edit) —
  S4 touched that set's doc comment but added nothing to it.
- UR-4 (`verdictBody`'s "Retrieval is degraded" for a chat-only outage) is untouched and still
  needs deciding before S5 ships `inference.crashed` at `warn`.
- `InferenceCapability`'s two prose defaults (C.7).
- The live scenarios in C.12.

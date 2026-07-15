---
title: AI-runtime lifecycle — why local fixes come out wrong
type: tempdocs
status: "open — takeover investigation complete (§8, 2026-07-15): §4 probe found a CLASS (4 of 5 InferenceOnline-gated ops are circular) — subject is the capability algebra, not inference modes. §3 claims verified (3a corrected). Verdict: GO for /design → /derisk → /plan, after 0.2.0's minimal unblock ships. No design or implementation done."
created: 2026-07-15
updated: 2026-07-15
author: "agent (opened from the 0.2.0 release round; owner-directed 2026-07-15)"
related: [734, 726, 730]
---

# 737 — AI-runtime lifecycle: why local fixes come out wrong

## 1. Purpose

The AI-runtime lifecycle is the area of this codebase where a correct-looking local
fix is most likely to be wrong. **Establish why, and remove the cause** — so that a
reader of any one surface can tell what state the system is in and whether an action
will succeed, without cross-referencing three modules, and so that the wrong answer
becomes hard to express rather than merely currently-absent.

The purpose is deliberately held at that altitude. The known defects span four
different layers (§3). A purpose naming any one of them — "fix the mode enum", "fix
the capability", "rewrite `BrainSurface`" — pre-decides the diagnosis, and **the
diagnosis is the deliverable**. This doc's own scope is one of its findings: if the
inverted capability (§3b) turns out to be a class rather than an instance, the
subject was never inference modes.

## 2. Done criterion (falsifiable — do not soften this into a discussion)

A fresh agent, given a state and a proposed user action, can predict **from one
place** whether that action succeeds — and a change that breaks that property is
caught by a gate, not by a reviewer noticing.

Today this fails. That is not a hypothesis; see §3e.

## 3. Evidence

These are the **symptoms the purpose exists to explain**, not the work items. If the
analysis is right, most should fall out as consequences of one cause rather than
needing individual fixes. Verify every line below against source before building on
it — several were verified during the 0.2.0 round, but this doc is dated history the
moment it is written.

### 3a. There is no `offline` mode on the wire

Runtime modes are `online | indexing | starting | transitioning`; the operation
accepts `{"mode":"online"|"indexing"}` (`CoreOperationCatalog.java:183`, verified
round 3). The UI has spent its life naming a state the domain model does not contain.
So "Shut Down AI" is not a shutdown — it is `switchInference('indexing')`, a cold
start of the embedding backend that happens to stop chat.

**Hypothesis worth testing first (not a conclusion):** "inference mode" may be
conflating two orthogonal axes — chat engine up/down, and embedding backend
warm/cold — into one linear enum. That would explain why "Shut Down" had to be
spelled `indexing`. If true, the subsystem is unfixable in place for a boring
reason: it models one dimension where there are two.

### 3b. The capability algebra is inverted

`core.switch-inference-mode` requires `RequiredCapability.InferenceOnline`
(`CoreOperationCatalog.java:803`), which resolves to
`capabilities.inference().available()` (`HeadAssembly.java:1211`). **The operation
requires the postcondition it exists to establish.** Every path through it is dead:
Simple mode's "Resume Chat AI", Advanced mode's "Online" button
(`BrainSurface.ts:1911`), and — pre-existing — offline → "Start AI"
(`BrainSurface.ts:1085`), the last long masked by auto-activation after Install AI.
Users see `Required capability unavailable: inference-online`
(`OperationExecutorImpl.java:317`).

A typo produces one wrong symbol. This reads like requirements were assigned by
pattern-matching the operation's *name* rather than reasoning about its pre- and
postconditions — which is why §4 probes for a class.

### 3c. Presentation had two authorities for one fact

`statusConfig` in `BrainSurface.ts` was a hand-maintained fork of
`aiEngineHeadline`/`aiEngineTone` (`state/aiVerdict.ts:239-295`) and had drifted:
`indexing` rendered "AI Online" in green while health reported degraded. Torn out in
`75bebc99` — but the fork existed at all, which is the datum.

### 3d. A user can be put there with no click

`VduOfflineTriggerSampler` can drive a user into the state with no interaction.
Combined with §3b, that is an inescapable state entered involuntarily.

### 3e. Two agents fixed this wrong — the observation this doc must explain

This is the primary evidence, and it is stronger than the defect count.

1. **A surface fix that could not work.** `75bebc99` gave `indexing` its own branch
   returning "Resume Chat AI" → `switchInference('online')`, tore out the §3c fork,
   and shipped with five unit tests, four proven red by revert. It was **still dead** —
   the cause was §3b, two layers down. The tests asserted the branch *returned* the
   right action, never that it could *execute* (`wrong-gate`). The implementer even
   observed the exact error live and rationalised it as a local environment gap.
2. **A rescue fix that did the opposite of its own javadoc.** PR #185's
   `maybeAutoStartRebuildForLegacyUnattestedVectors` stated "we deliberately do not
   back-stamp… the only safe rescue is a real re-embed", then transitioned to
   REBUILDING without re-marking — so nothing was queued, certification fired on the
   first read, and the fingerprint was stamped over vectors nobody re-embedded. Its
   test passed because it **hand-wrote the missing re-embed** and fed
   `checkRebuildCompletion` hardcoded zeros (`unreachable-seed-green`). Repaired in
   the 0.2.0 merge (`c1dbc34c`) by extracting the ordering into
   `EmbeddingRecoveryOps.rescueBlockedLegacyIndex` and forcing callers through it with
   two ArchUnit rules.

Both were careful readers. Both were confidently wrong. **A subsystem that produces
confidently-wrong fixes from careful readers is telling you something about itself,
and that is what this document is trying to hear.**

## 4. First move — the capability probe (do this before framing anything else)

Walk the whole operation catalog and ask of every operation: **does it require a
capability that it itself establishes?**

- If `core.switch-inference-mode` is alone → this is an inference-lifecycle tempdoc.
- If there are others → the subject is the **capability algebra**, not inference
  modes, and the scope changes completely.

This is cheap, mechanical, and decides what we are actually writing about. Run it
before committing to a frame. If it finds a class, consider whether the invariant
belongs in a gate (the pattern is mechanically checkable: an operation's
`RequiredCapability` set must not contain a capability the operation's own success
establishes).

## 5. Then: what are the actual states?

Not the UI's vocabulary, not the current enum — the states the system **genuinely
occupies** (no model installed; model installed, engine down; engine starting; engine
up; embedding backend cold; embedding backend warm; …), and which transitions a user
may legitimately request from each. Name them from source and from live observation,
not from either existing model.

## 6. Explicitly NOT in scope

- **"Rewrite" is not in the purpose.** It is a conclusion the analysis either earns
  or does not. Naming it up front means every finding gets read as evidence for a
  verdict already reached. If §5 shows two axes wearing one enum, the rewrite writes
  itself and needs no advocate.
- **The 0.2.0 release blocker is not this doc's job.** §3b needs a minimal correct
  unblock shipped separately, so the release does not wait on the thinking and the
  thinking is not compressed by the release. Track it on the release branch / 734.
- **This is not a findings list.** §3 is evidence, not a work queue. Do not open this
  doc by fixing F-6.
- **Do not re-derive urgency.** Per `structural-defects-no-repeat`, one documented
  silent bug proves a bug-class; here there are two independent wrong fixes. Critique
  this doc's substance — wrong diagnosis, wrong mechanism, wrong scope — not its
  timing.

## 7. Handling note for whoever takes this over

The agent that opened this doc **was wrong about this subsystem repeatedly** on
2026-07-15 — it "corrected" the owner's framing of the Simple-mode trap twice, from
confident code reads, and was wrong both times. Every claim in §3 is therefore worth
re-verifying from primary sources rather than inherited. That is not modesty; it is
the same property the doc exists to explain, observed from the inside.

Suggested route: `/theorize` on §3e (why do careful fixes come out wrong here?) →
the §4 probe → `/research` the real states (§5) → `/design` → `/derisk` → `/plan`.
Stop at plan; do not implement from this doc without the owner's word.

---

## 8. Takeover findings (2026-07-15 — investigation only; no design, no implementation)

Method: worktree `737-ai-runtime` based on `worktree-release-asset-set` @ `9745563b`
(this tempdoc's introducing commit; NOT main — main carries the pre-`c1dbc34c`
back-stamping bug in the embedding rescue). Every §3 claim re-verified against
source on this branch; §4 probe run over every catalog; §5 state map built from
source by three independent read-only research passes, with every load-bearing
chain (catalog → resolver → capability → mode listener; sampler → coordinator →
manager) additionally verified first-hand by the orchestrating session. All
`file:line` refs are to this branch.

### 8a. §4 probe result — it is a class; the subject is the capability algebra

Exactly two catalogs declare `RequiredCapability` anywhere:
`CoreOperationCatalog.java` (29 ops) and `AgentToolsOperationCatalog.java`
(6 ops, all `Set.of()`). Five core ops require `InferenceOnline`; **four of the
five are circular**:

| Operation | Verdict | Why |
|---|---|---|
| `core.reload-inference` | OK | `RESTART_IF_ONLINE` — genuinely presupposes a running session (`CoreOperationCatalog.java:781`) |
| `core.switch-inference-mode` | **CIRCULAR** | its purpose is online↔indexing; the "go online" direction requires the postcondition (`:803`) |
| `core.trigger-offline-processing` | **CIRCULAR** | its own Phase A brings inference online when it isn't (`OfflineCoordinator.java:113-124`); requiring `InferenceOnline` blocks exactly that case (`:823-825`) |
| `core.activate-runtime-variant` | **CIRCULAR** | activation self-tests on an isolated port, then `applyRuntimeOverrides(..., RESTART_ALWAYS)` — designed to work from cold/OFFLINE; success **ends** in ONLINE (`RuntimeActivationService.java:502,594-604`; `InferenceLifecycleManager.applyConfig` restart path → `TransitionOutcome.success(Mode.ONLINE)`) (`:847`) |
| `core.deactivate-runtime-variant` | **CIRCULAR / WRONG-CAP** | the designed escape hatch for a GPU variant that failed to come online is gated on being online (`:867`; `RuntimeActivationService.runDeactivate` `:520-556` also `RESTART_ALWAYS`, no up-front engine needed) |

Per §4's own decision rule: **the subject is the capability algebra, not
inference modes.** Supporting the §3b hypothesis that requirements were assigned
by pattern-matching operation *names*: the ops named "inference"/"runtime-variant"
all got `InferenceOnline`; the one for which it is semantically right
(`reload`) is the exception, and `core.start-ai-install` — which also ends by
establishing inference online via its post-install self-test
(`AiInstallService.java:975-978`) — correctly requires nothing, showing the
correct pattern was expressible and used elsewhere in the same file's era.

Adjacent algebra defects found by the probe (same class, different species):

- **Dead vocabulary with lying resolver arms**: `IndexedRoot` and `GpuAvailable`
  are required by no operation anywhere; their resolver arms are wrong anyway —
  `IndexedRoot → worker().available()` (alias of WorkerOnline, not "a root is
  indexed") and `GpuAvailable → true` hardcoded, `minVendor` ignored
  (`HeadAssembly.java:1206-1217`). Harmless today only because unreachable.
- **Agent-surface double inversion**: capability-derived availability
  (`CapabilityAvailability.java:48-55` → `OperationCatalogComposition.java:104-118`)
  hides `switch-inference-mode` / `trigger-offline-processing` from the agent tool
  palette (`ExecutorTag.AGENT`, `AgentOperationEmitter`) precisely when
  `inference.capability` is unhealthy — the recovery tools vanish at the moment of
  need. The human UI does NOT consume the derived availability
  (`operationButton.ts:61` marks it `elided`; `BrainSurface.ts:1058-1136`
  hand-computes availability from busy+policy only) — so the button renders,
  the click dispatches, and the executor denies (`OperationExecutorImpl.java:317`).
- **Dual dispatch authorities for one action**: `POST /api/ai/runtime/activate`
  (`AiRoutes.java:115` → `AiRuntimeController.handleActivate:42-98`) calls
  `service.startActivate` with **no capability check** (route manifest:
  `requiredCapabilities: []`), while the same action as
  `core.activate-runtime-variant` is circularly gated. The shell-v0 UI uses the
  gated operation (`BrainSurface.ts:838`); the legacy client
  (`api/domains/inference.ts:135`) uses the ungated route.
- **MISSING-CAP**: `core.ingest-files` (AgentTools) writes into the worker-backed
  index with neither a `RequiredCapability` nor an `OperationAvailability`
  expression — logged to the observations inbox (session shard), out of scope here.

Gate feasibility (honest): **no machine-readable postcondition exists** on the
`Operation` shape (`OperationPolicy` carries preconditions only;
`OperationLineage.affects` names `ResourceRef`s, wrong vocabulary). The §4 gate
("must not require what it establishes") therefore needs either (a) a new
hand-maintained register (op-id → establishes-set) in the existing
`execution-surfaces`/`logic-seams` pattern — which re-imports the same trust
problem: an author can mis-declare `establishes` exactly as they mis-declared
`requiredCapabilities`; or (b) call-graph analysis of handler bodies, which no
existing gate infrastructure here performs. Nearest-fit convention:
`LiveWitness`/`RegistrySnapshotExporter` (runtime-observed evidence
cross-validating a static declaration). Decision belongs to `/design`.

### 8b. §3 verification results

- **§3a — PARTIALLY WRONG as written.** The domain enum `io.justsearch.app.api.Mode`
  is `ONLINE | INDEXING | TRANSITIONING | OFFLINE` (`Mode.java:22-27`) — **OFFLINE
  exists in the domain model**, and there is no `starting` (the wire `starting`
  field is a redundant alias: `isStartingUp() ≡ mode==TRANSITIONING`,
  `OnlineAiServiceImpl.java:354-357`). What IS true: the *operation* accepts only
  `online|indexing` (`CoreOperationCatalog.java:795`), so OFFLINE is unreachable
  through the user/agent operation surface, and "Shut Down AI" is indeed spelled
  `switchInference('indexing')` (`BrainSurface.ts:1131`). The UI's `offline` names
  a real domain state; the frontend then adds its own pseudo-states
  (`'starting' | 'unknown'`, `aiStateStore.ts:111,400-429`) and `aiVerdict.ts`
  expands to 8 engine kinds. The corrected datum for §1: not "UI names a state the
  domain lacks" but "the operation surface cannot express two of the four domain
  states (OFFLINE as target, and TRANSITIONING is only ever implicit)".
- **§3b — CONFIRMED end-to-end**: catalog `:803` → resolver
  (`HeadAssembly.java:1211`) → `available() ≡ health()==READY`
  (`Capability.java:16-18`) → READY only in Mode ONLINE
  (`InferenceCapabilityWiring.java:55-68`: INDEXING→DEGRADED, OFFLINE→OFFLINE,
  TRANSITIONING→RECOVERING). The only executable transition of
  `switch-inference-mode` is ONLINE→INDEXING. Denial surfaces at
  `OperationExecutorImpl.java:317`; live-observed in 734:718.
- **§3c — CONFIRMED**: `git show 75bebc99` shows pre-fix `statusConfig` with
  `indexing: { dot: 'online', label: 'AI Online', … }` and no `indexing` case in
  `brainDotTone` → rendered green while capability reported DEGRADED. Fix rewired
  labels to `aiEngineHeadline`/`aiEngineTone` (now `aiVerdict.ts:241/:274`).
- **§3d — CONFIRMED and sharpened**: `VduOfflineTriggerSampler.checkOnce`
  (30 s cadence, fires when idle ≥ 5 min, not energy-reduced, LLM not online,
  pending VDU > 0; `VduPacingPolicy.java:41-49`) →
  `OfflineCoordinator.startOfflineProcessing()`: Phase A calls
  `inferenceManager.switchToOnlineMode()` **directly** (`OfflineCoordinator.java:118`),
  Phase B calls `switchToIndexingMode()` (`:138`) **and never switches back** —
  the run ends leaving the system in INDEXING. Composed with §3b: the machine
  parks you in a state whose only user-visible exits are operations the machine
  itself has made un-dispatchable. Internal code paths bypass the operation
  executor entirely; the capability gate binds only users and agents.
- **§3e-1 — CONFIRMED**: `75bebc99` added the `indexing` branch
  ("Resume Chat AI" → `switchInference('online')`, now `BrainSurface.ts:1118`) with
  5 unit tests whose `invokeOperation` stub only records the invocation and
  resolves `{structuredData: null}` — asserting the branch *returns* the right
  action, never that it can *execute* (`BrainSurface.indexing-escape.test.ts`).
  All current `switchInference` call sites: `:1085` "Start AI"/online (offline
  case), `:1097` "Cancel"/indexing (starting case), `:1118` "Resume Chat
  AI"/online (indexing case), `:1131` "Shut Down AI"/indexing (online case),
  `:1911`/"Online", `:1919`/"Indexing" (Advanced), plus
  `IndexingOverlay.ts:322` "Go Online" — of these, **only the two 'indexing'-arg
  buttons can ever dispatch** (§8b/§3b).
- **§3e-2 — CONFIRMED, one precision correction**: the inverted rescue
  (`maybeAutoStartRebuildForLegacyUnattestedVectors`, introduced `7a46ca1b`,
  PR #185 = `88a4305b`) had the quoted javadoc and no re-marking step; its test
  hand-wrote the re-embed (`indexSingle` call, "models the re-embed write") and
  called `checkRebuildCompletion(0, 0)` hardcoded. The repair **seam was authored
  in `73bd843b`** (`EmbeddingRecoveryOps.rescueBlockedLegacyIndex` + ArchUnit rules
  `embeddingAutoRescueMustGoThroughEmbeddingRecoveryOps`
  (`IndexerWorkerGuardrailsTest.java:210`) and
  `forcedReindexTriggerMustNotBypassTheRescueSeam` (`:228`)); `c1dbc34c` is the
  merge that made it final and deleted main's duplicates. "Repaired in c1dbc34c"
  is accurate as merged-state, imprecise as authorship.

### 8c. §5 — the actual state space (and the two-axes hypothesis)

The two-axes hypothesis is **confirmed, and understated**. There is no Head-side
"embedding backend warm/cold" axis to model at all:

- ONLINE→INDEXING **starts nothing** — it stops llama-server, waits 2 s for VRAM
  flush, done (`InferenceLifecycleManager.java:447-508`). INDEXING→ONLINE is a
  cold start **of the chat model only** (`:327-445`).
- The Worker's embedding backend is Worker-owned and continuously alive
  (`EmbeddingProviderLifecycle`); on the Head-ONLINE rising edge it releases only
  its **GPU session**, keeping CPU query-embedding alive (tempdoc 598 R4,
  `EmbeddingProviderLifecycle.java:168-226`).
- The **only** cross-process signal the Head sends is one boolean:
  `mainGpuActive ≡ (Mode==ONLINE)` (`InferenceWiring.java:38-40`, MMF byte 24,
  `MainSignalBus.java:166-180`). Therefore **INDEXING, OFFLINE, and
  mid-TRANSITIONING are indistinguishable to the Worker.** `Mode.INDEXING` vs
  `Mode.OFFLINE` is a Head-local provenance distinction ("why is the engine
  down") with zero externally observable behavioral difference.
- TRANSITIONING **is** wire-observable (unguarded volatile read feeds
  `/api/status` via `BootstrapProjections.projectInferenceSnapshot:41`;
  `InferenceRuntimeView.java:17` documents it), despite `Mode.java`'s javadoc
  claiming it is suppressed — that claim is true only of the typed
  `onTransition` telemetry path (`TransitionRunner.java:520-534`).
- Raw inventory: **≥9 independently-set backend variables** answer "can the user
  chat / can indexing embed" (mode FSM, external-adoption flag, `mainGpuActive`,
  `energyReduced`, embedder `isUsingGpu`, GPU-session-held, rebuild state,
  install status×step, activation status×step, `InferenceCapability`,
  `VduCapabilityState`) plus two frontend-only projections (`AiRuntime.mode`
  6 values; `AiEngineKind` 8 values × stability). Only `mainGpuActive` crosses
  the process boundary. Full table in the takeover session transcript; the
  variables and set-sites are all named above or in §8a/§8b citations.
- Additional states the enum cannot express, found at transition entry points
  (`InferenceLifecycleManager.java:327/447/527/545/601/842`;
  `TransitionRunner.java:403`): VDU-mode (config save/restore around ONLINE),
  external-server adoption, activation-in-flight, install-in-flight.

### 8d. The one cause (§1's deliverable): five vocabularies, zero derivation

Why do careful readers produce confidently-wrong fixes here? Every layer
re-declares what it believes about the runtime in its own hand-maintained
vocabulary, and **nothing derives any of them from anything, and no check
relates them**:

1. The operation catalog hand-assigns preconditions (by name-pattern, §8a).
2. The capability resolver hand-maps capability→predicate (two arms are lies, §8a).
3. The mode enum hand-declares a state space that contains one distinction with
   no behavioral referent (INDEXING vs OFFLINE, §8c) and omits four real ones
   (VDU, external, activation, install — §8c).
4. The wire hand-adds a redundant alias (`starting` ≡ TRANSITIONING, §8b).
5. The frontend hand-derives two more projections (and until `75bebc99`, a third
   — the §3c fork).

And the gate that these declarations feed binds **only the user/agent surface**:
internal paths (`OfflineCoordinator`, `RuntimeActivationService`,
auto-activation, crash recovery) call the lifecycle manager directly (§3d/§8a).
A careful reader at any single layer sees a locally-coherent model, fixes their
layer correctly *in its own vocabulary*, and is wrong in the composed system —
75bebc99 fixed presentation over a dead operation; PR #185's author wrote a
correct javadoc over an inverted mechanism and a test that couldn't see the
inversion because the test, too, hand-declared its own seed. This is the
projection-vs-fork failure (tempdoc 553's class) applied to lifecycle state
instead of search traces: five forks, no canonical source, no register.

The done criterion (§2) restated in these terms: there must be **one place**
where "state × requested action → outcome" is answerable, with the other
surfaces as checked projections of it — and a gate that fails the build when a
new fork or a circular precondition appears. Whether that one place is a
capability register with declared postconditions, a canonical state machine
whose projections are generated, or something smaller, is `/design`'s question,
not this section's.

### 8e. Scope, displacement, collisions

- **Scope per §4's rule**: capability algebra + state-model unification.
  "Inference modes" is one consumer of it.
- **0.2.0 collision boundary**: the minimal §3b unblock is owned by the release
  session (734 / release branch). **Gap found**: as of this base, no unblock plan
  exists in 734 — its Round-3 log records the live denial (734:718) but files it
  as a missing-model gap. Flag for the owner: the probe shows the minimal unblock
  has a shape question — unfixing only `switch-inference-mode` leaves the three
  sibling circular ops (§8a), two of which (`activate`/`deactivate-runtime-variant`)
  gate first-run GPU-pack setup and its failure recovery.
- **Displaces/duplicates nothing found**: no other tempdoc or register models
  runtime state or capability requirements (730 is Worker-side fingerprint/lease
  integrity; 726 is release assets; 518 decomposed the lifecycle manager but kept
  the enum; 656 added capability *reasons*, not algebra). The fix direction is
  *consistent with* existing patterns rather than duplicating them:
  execution-surfaces register (553), LiveWitness cross-validation, ArchUnit
  caller-forcing seams (73bd843b).
- Not investigated (bounded): MCP/workflow operation projections beyond the two
  catalogs; the `maybeAutoSelectCuda12Variant` boot path (named in
  `RuntimeActivationService.java:80-85` comments; the "auto-activation after
  Install AI" masking mechanism in §3b is a pointer, not verified — the install
  service itself contains **no** auto-activation call, verified end-to-end).
  Internet research was deliberately skipped: every question here is
  intra-codebase; no external prior art changes the diagnosis.

### 8f. Verdict — GO (diagnosis validated; proceed to /design → /derisk → /plan)

**Should this be done at all?** Yes. The §4 probe — the doc's own falsifier —
found a class (4 circular ops, 2 lying resolver arms, 1 missing-cap, dual
dispatch authorities, agent-palette double-inversion). The defect is user-facing
(dead "Start AI"/"Resume Chat AI"/"Online" buttons), entered involuntarily
(§3d), inescapable through the operation surface (§8b), and has already produced
two documented confidently-wrong fixes by careful readers (§3e). Per
`structural-defects-no-repeat`, urgency is settled; this section critiques
substance only, and the substance held up — of the brief's claims, only §3a
needed correction (and the correction *strengthens* the thesis: the operation
surface can't even express the domain's own states).

**Now?** Yes, with one sequencing constraint: do not touch the capability
requirements of `core.switch-inference-mode` (or siblings) until the 0.2.0
minimal unblock has landed, to avoid colliding with the release session. The
thinking (design/derisk/plan) has no collision surface and should proceed.

**Cheapest validating evidence, and does it exist?** It was the §4 probe; it now
exists (§8a). The class-vs-instance question the doc staked its scope on is
answered. The remaining cheap-evidence item for `/design` is a one-day spike:
draft the establishes-mapping for the 35 existing ops and see whether it can be
derived from handler signatures (e.g. `RestartPolicy` constants, `switchTo*`
call sites) rather than hand-declared — that decides register-vs-callgraph.

**What it displaces**: nothing (§8e). **What would make this a no-go**: none
found — the honest limits are the two unverified pointers in §8e (boot-path
auto-activation mechanism; MCP projections), neither of which is load-bearing
for the diagnosis.

Route from here (per §7, unchanged): `/theorize` is substantially discharged by
§8d; next is `/design` (canonical-source choice + gate mechanism + minimal-fix
interim), then `/derisk`, then `/plan`. Stop at plan; owner's word before
implementation.

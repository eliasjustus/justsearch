---
title: "AI-runtime lifecycle — why local fixes come out wrong, and the spec/status model that replaces the mode enum"
type: tempdocs
status: "open — takeover (§8), rewrite panel (§9), theorization (§10), research (§11), and DESIGN (§12-13, 2026-07-15) complete: one Head-side runtime authority (desired-state spec + Condition-shaped status + GPU lease arbiter + single-writer reconciler), five existing vocabularies become derived projections, orphan list named. Next: /derisk → /plan. No implementation."
created: 2026-07-15
updated: 2026-07-15
author: "agent (opened from the 0.2.0 release round; owner-directed 2026-07-15)"
related: [734, 735, 725, 730, 726, 553]
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

---

## 9. Rewrite panel (2026-07-15, owner-directed): is a full rewrite best long-term?

Owner context: the inference-switch concept predates the current docs — it likely
dates to the project's first (no-longer-existing) documents. Question posed:
regardless of feasibility, is a full rewrite the best *long-term* outcome? Four
independent analysts (opus), deliberately opposed lenses, each required to
disclose evidence against its own position, all read-only against this branch.

### 9a. The four verdicts

| Lens | Verdict | Conf. | Core of the case |
|---|---|---|---|
| Prosecution (rewrite advocate) | REWRITE the ontology | 8/10 | The real state space already leaked out of the 4-value enum into a **10-value `TransitionReason` telemetry tag** (`telemetry/TransitionReason.java:9-29`: VDU_ENTER/EXIT, EXTERNAL_DETACH, CRASH_RECOVERY, AUTO_START…) and into nullable side-fields (`preVduConfig` `InferenceLifecycleManager.java:111`, external flag `:1170` — whose missing hand-clear was a real silent bug pre-518, `:472-475`). The capability algebra is name-pattern with no semantics (the same file's install/pack ops correctly require nothing while every "inference/runtime"-named op got `InferenceOnline`). Every in-place repair is an edit inside one of the five forks and inherits the locally-right/globally-wrong property that produced 75bebc99 and PR #185. Disclosed counter: if the transition to one-canonical-source can be staged continuously, "rewrite vs repair" is a labeling choice and confidence falls to ~6. |
| Defense (repair advocate) | HYBRID, lean REPAIR | 7/10 | The 518 decomposition already produced sound machinery (transition envelope w/ rollback, immutable view snapshots, listener fan-out). Dense inventory of **operational Chesterton fences** a rewrite risks silently dropping: VRAM flush ordering (`:114,468-470`), Windows `taskkill` hung-process reap (`LlamaServerOps.java:354-356`), external-adoption identity validation + health-only-adoption override (`:78,88,440-474`), crash counters/caps, isolated-port GPU self-test with INCONCLUSIVE handling (`RuntimeActivationService.java:456-464,101-102`), config rollback distinguishing set-vs-baseline. A staged path (fix 4 requirement sets → add `establishes` + gate → derive availability → split enum behind alias → retire alias) reaches the identical end state, each step shippable. Sharpest point: **the clean two-axis rewrite target is already refuted by §8c** (the real space is >2-dimensional: VDU, adoption, in-flight procedures) — a confident rewrite risks being the third confidently-wrong careful-reader artifact. Conceded: the circular algebra, five-forks-no-derivation, and internal-bypass are real and indefensible. Disclosed counter: a stalled incremental migration leaves TWO authorities coexisting — per the 553 class, worse than either endpoint. |
| Greenfield architect | Model: REWRITE. Subsystem: HYBRID ("rewrite the model, keep the machinery") | 8/10 | Extracted the real requirements from source: 6 orthogonal variables (install / engineProcess / adoption / gpuGrant / procedure-overlay / policy) + one resolver with declared pre- AND postconditions; the §4 invariant becomes "required ∩ establishes = ∅", mechanically gateable. Distance table: large SURVIVES-AS-IS bucket (envelope, process ops, MMF bus, install/activation trackers) but the model layer is dominated by CONTRADICTS (linear enum as canonical axis, capability-as-precondition, surface-only gate, lying resolver arms, dual authorities) + MISSING (postcondition vocabulary, single resolver, backend orthogonal variables, fork gate). Decisive observation: **`aiVerdict.ts:44-67` already implements the target decomposition** — 8 kinds separating install/process/indexing + `AiStability` as procedure-overlay — in the wrong layer, derived from raw snapshots instead of being canonical. INDEXING-vs-OFFLINE searched for a behavioral consumer: none exists; it is presentation-provenance, belongs as a down-*reason* field, not a peer mode. Highest-value experiment: if `establishes` can be **derived from handler signatures** (`RestartPolicy` constants, `switchTo*` call sites), the canonical resolver can be code-generated and the model-verdict tips from REWRITE to additive-REPAIR. |
| Coupling analyst | HYBRID (bounded rewrite of the derivation core) | 7/10 | The census refutes both extremes. Blast radius is small: `Mode.X` in main src = **2 modules** (app-inference 9 files, app-services 2); `InferenceOnline` in 6 files total. **The wire does NOT freeze the vocabulary**: `phase`/`mode` are proto `string` fields (`status.proto:327`), the `wire` gate is buf-breaking with additive classifications — old values are pinned by fixtures/consumers, not structure. **The Worker is 1-bit-decoupled**: the only mode-derived cross-process signal is `main_gpu_active` MMF byte [24]; no gRPC field carries Head-mode (knowledge.proto's `effective_mode` is SearchTrace vocabulary; `backfill_mode` is worker-loop vocabulary). Zero leakage into search/jobs/settings/telemetry/reason-code registries. Test entrenchment: 11 files pin Mode semantics, of which two sets are **pure fossils pinning documented-bug behavior** (`CapabilityAvailabilityTest.java:43-53` pins the circular-denial derivation; `BrainSurface.indexing-escape.test.ts`'s 5 tests pin the dead escape branch) — replaced under either path. Whichever path is chosen, the old vocabulary is fully retirable; neither path touches the Worker. |

### 9b. Convergence — what all four agree on

1. **A full *subsystem* rewrite is wrong** (unanimous, including the prosecution).
   The machinery beneath the model — transition envelope, `LlamaServerOps`
   process/adoption/recovery, immutable view snapshots, MMF bus,
   install/activation procedure trackers — is correct, dense with accreted
   incident-fixes, and would be re-derived identically at maximum regression risk.
2. **The conceptual model must be replaced, not evolved** (unanimous; the defense
   concedes it). The linear mode line + hand-assigned capability symbols is the
   bug-generator; keeping it as the canonical axis reproduces the fork mess
   regardless of how many instances are repaired.
3. **The end state is the same under every lens**: one canonical authority
   (orthogonal state variables + a single `state × action → outcome` resolver
   with declared pre/postconditions), consulted by internal callers AND the
   operation surface, with wire/UI/agent-palette as derived, gate-checked
   projections, and the old five vocabularies retired.
4. **The replacement is structurally cheap**: 2-module blast radius, 1-bit
   process boundary, string-soft wire, zero cross-subsystem leakage, and the
   target decomposition already prototyped in `aiVerdict.ts`.

### 9c. Divergence — the one real disagreement, surfaced not averaged

Sequencing risk. The prosecution argues any repair-framed path keeps authors
inside the forks (the documented failure mode); the defense argues a big-bang
commitment to a target ontology repeats the confident-commitment failure mode at
maximum scale (and shows the naive two-axis target is already wrong), while a
stalled staged path risks a permanent two-authority state (553's class). These
are the same underlying observation — **this subsystem punishes confident
commitment** — pointed at opposite plans. The panel's implicit resolution, which
§Design should adopt as a constraint: the new authority must land as a
*replacement of authority, not a bolt-on* (prosecution's requirement), staged in
independently-verifiable steps that never leave two live authorities without a
build-failing fork gate and a consumer-retirement deadline (defense's
requirement), with the ontology derived from observed behavior rather than
declared up front where possible (both requirements at once — hence the spike).

### 9d. Panel answer to the owner's question

**Is a full rewrite best long-term? For the concept, yes; for the code, no.**
The inherited inference-switch idea — one linear mode you switch along — is dead
as a model: its own telemetry needed 10 states where the enum has 4, its two
"down" states are behaviorally identical, its capability grammar is
name-pattern, and it demonstrably converts careful maintainers into confident
bug-authors. It should be replaced by the orthogonal-variables + single-resolver
model (§9a greenfield row), which the frontend already discovered independently.
But the *code* mass of the subsystem — process management, transitions,
recovery, signaling — is not what's wrong, and no lens found long-term value in
rewriting it. Bounded ontology rewrite, machinery kept: this is what `/design`
should design.

Decision input for `/design`, sharpened by the panel: run the §8f spike first —
whether `establishes` sets are derivable from handler signatures decides
code-generated-resolver-over-existing-machinery (cheapest) vs hand-declared
register (needs LiveWitness-style cross-validation to be trustworthy). The two
fossil test sets (`CapabilityAvailabilityTest.java:43-53`,
`BrainSurface.indexing-escape.test.ts`) are replaced under any path and should
not be treated as behavior to preserve.

---

## 10. Theorization (2026-07-15) — the ontology question the panel did not settle

§9 settled *that* one canonical authority must replace the five vocabularies,
and *that* the machinery stays. It did not settle **what kind of thing the
authority is**. That choice is prior to `/design` and changes everything
downstream — the wire shape, the gate, the UI verbs, and which of the §3
symptoms become impossible by construction versus merely fixed. Three candidate
ontologies, each with embryonic evidence already in the code.

### 10a. Candidate A — imperative transitions + resolver (the panel's implicit frame)

Operations are actions with declared pre/postconditions; one resolver answers
`state × action → Allowed | Denied(reason)`; the §4 invariant
(`required ∩ establishes = ∅`) is the gate. This is §9a's greenfield sketch.

- **Fixes by construction:** the circular class (§8a), the five-fork drift
  (projections derive from the resolver's state).
- **Does NOT fix by construction:** §3d. An imperative model has no memory of
  what the user *wanted*, so an autonomous transition (the sampler parking you
  in INDEXING) still ends wherever the last writer left it; the fix for
  never-switch-back remains a convention ("controllers must restore prior
  state"), i.e. another prose rule.
- Embryonic evidence: the operation catalog itself; the transition envelope.

### 10b. Candidate B — declarative desired-state + reconciler

The load-bearing observation: **this system is already half a reconciler, and
its worst bugs are reconciler bugs.** Multiple autonomous controllers each push
the runtime toward their own implicit goal — `VduOfflineTriggerSampler` (toward
INDEXING when work pends), crash recovery (toward last mode), watchdogs (toward
process-up), boot auto-select/auto-start, `OfflineCoordinator` Phase A (toward
ONLINE for VDU). No declared desired state exists, so conflicts resolve by
last-writer-wins, and "what should the system return to?" is unanswerable. The
codebase has already grown *three ad-hoc desired-state memories* to compensate:

- `preVduConfig` (`InferenceLifecycleManager.java:111,539-554`) — a hand-rolled
  "remember what the user wanted, restore it after the procedure" for exactly
  one procedure (VDU). The sampler's Phase B has no such memory — hence §3d.
- `JUSTSEARCH_AI_AUTOSTART_ENABLED` (`BootstrapInferenceFactory.java:149-162`) —
  boot-time desired state as an env var, with a documented history of confusing
  even its own log line ("AI auto-start disabled" read as feature-off; the
  alpha.20 comment records a user seeing "AI offline" with no hint why).
- The admin policy layer (`onlineAiEnabled`) — a standing "may chat ever be up"
  bit that every path re-checks by hand.

In this model the user's controls stop being transition commands and become
intent writes: "Chat AI: on/off" is a *preference*, not an action; the
reconciler drives actual state toward `desired ∧ policy ∧ resources`, and
"Resume Chat AI" can never be dead because writing an intent has no
precondition. §3b and §3d both become **inexpressible**, not fixed. The
mode-switch operation dissolves rather than getting corrected requirements.

- **Cost/risk:** hidden control loops are hard to debug; every autonomous
  transition must carry a user-legible reason ("chat paused: embedding backlog,
  ~4 min") or the system feels haunted. Prior art for that exists in-repo
  (`LifecycleReasonCode` + `readinessNotice.ts` and their drift gate). Also,
  reconcilers invert testing: you test convergence properties, not transitions —
  a bigger test-model change than Candidate A.

### 10c. Candidate C — the GPU as a leased resource (arbiter model)

§8c's deepest fact — the only cross-process truth is `mainGpuActive` — suggests
the whole "mode" idea is a shadow of a **resource-arbitration** problem: two
clients (chat engine, embedding backfill) contend for one grant (VRAM). ONLINE
≡ chat holds the lease; INDEXING ≡ worker holds it; OFFLINE ≡ nobody does.
Model it as an arbiter with holders, waiters, and a contention policy, and the
enum vanishes; the MMF boolean becomes the lease's cross-process projection
(unchanged); "Shut Down AI" becomes "release chat's lease".

- **Why it earns theorizing:** it is the only frame that survives the hidden
  hardware assumption (§10d-1) breaking, and this repo already operates a
  lease/ownership model with contention verdicts elsewhere (the shared
  dev-stack lease). It composes with B rather than competing: desired state
  says *who should want the lease*; the arbiter says *who holds it*.

### 10d. Hidden assumptions any design must decide on explicitly

1. **"Chat and embeddings can never share the GPU"** is policy fossilized as
   ontology. On ≥16 GB cards both stacks plausibly fit; the binary grant (and
   therefore the entire mode concept) encodes a worst-case-hardware decision as
   the domain model. A lease with a *size* survives this; an enum does not.
2. **"One engine"**: vision/summary already ride llama-server; a second resident
   model (e.g. a dedicated embedder or reranker on the Head side) breaks the
   linear mode permanently. The enum is a 1-engine assumption.
3. **"Lifecycle operations need capability gating at all."** What concrete harm
   does dispatching `switch to online` from OFFLINE prevent? The handler
   validates internally (config, VRAM, policy). The likely honest answer is that
   requirements were filled in because the field existed — the framework
   invited a declaration where none was needed (empty-set + internal validation
   is what the install ops correctly do, §8a). "Every declaration slot will be
   filled, correctly or not" is a design smell worth naming.
4. **"The user must manage this at all."** If B is right, Simple mode may need
   zero runtime controls — only the preference toggle and honest status. The
   Advanced-mode transition buttons exist partly because the model made users
   responsible for driving a machine the system already drives autonomously.

### 10e. Risks of the panel's converged direction (self-critique)

- Candidate A is the *least* disruptive and the panel drifted to it partly
  because it resembles the existing catalog — availability bias, the same
  force that produced name-pattern requirements. The §2 done-criterion is
  satisfiable by A, B, or C; A should win only if B's explainability cost and
  C's generality are genuinely not needed, not because A is nearest.
- All three candidates still need the fork gate; none removes the "author can
  mis-declare" trust problem (§8a). B shrinks the declaration surface the most
  (intents are few; transitions are internal), which may be the strongest
  gate-adjacent argument for it.
- Over-engineering risk: the 4 requirement-set fixes are one line each and the
  0.2.0 unblock may already ship them. The ladder is: (rung 1) fix instances →
  (rung 2) A's resolver+gate → (rung 3) B/C re-ontology. `/design` should price
  rungs separately and let the owner choose how far up to go; §9 only
  establishes that rung 1 alone leaves the generator running.

### 10f. The recurring shape (broader principle, not yet a rule)

This is the third occurrence of the same repo-wide shape: a state space grew
multiple hand-synced representations until a gate + canonical source collapsed
them — search execution (SearchTrace / execution-surfaces register), SSOT
catalogs (dual-copy sync gate), now runtime lifecycle. The generalization worth
considering *when the third instance ships* (not before): "any state consumed
by >1 surface needs a declared canonical source and a fork gate" as a
first-class register pattern, with this tempdoc as the naming instance. A
second, smaller shape also recurs: **declared metadata must be validated
against behavior** (LiveWitness; the §8f spike; the `establishes` trust
problem) — declarations without a witness are how both §3b and §3e-2 happened.

### 10g. Parked ideas (useful later, not load-bearing now)

- Denial-as-schedule: if an action is blocked by a transient condition, offer
  "do it when possible" (write the intent, reconcile later) instead of a dead
  error — turns every remaining denial into UX.
- The executor's existing `undo` becomes meaningful under B (undo = restore
  prior desired state) where under the current model it is undefined for
  lifecycle ops.
- The fossil tests (§9d) invert cleanly into acceptance tests for any candidate:
  "from INDEXING, the user-visible escape action succeeds" is the regression
  test all three ontologies must pass — write it once, model-agnostically,
  before choosing.
- Interim-fix compatibility: whatever the 0.2.0 unblock ships, prefer
  empty-requirements + internal validation (the install-ops pattern) over
  corrected-but-still-declared requirements, so rung 1 doesn't need re-doing
  under any of A/B/C.

---

## 11. Research pass (2026-07-15) — external prior art for the §10 candidates

Three questions researched (reconciler canon; local-LLM GPU arbitration; MCP
dynamic tool availability), each mapping to a §10 candidate or an §8a defect.
Concept research only — no external code copied or adapted; short quotes below
are attributed (K8s docs CC-BY-4.0; Ollama and llama.cpp are MIT; quotes are
fair-use-scale). Citations inline; fast-moving items carry dates.

### 11a. Reconciler canon (Candidate B) — the pattern is mature and its pitfalls are documented

- **Spec/status separation** is the load-bearing convention: desired state
  (`spec`) owned by the user, observed state (`status`) owned by controllers
  (k8s api-conventions). The **`Condition`** shape — `type / status /
  reason / message / observedGeneration / lastTransitionTime`, with *"use of
  the `Reason` field is required"* — is a directly importable explainability
  contract, and KEP-1623's standard condition vocabulary
  (`Ready / Progressing / Available / Degraded`) maps almost 1:1 onto our
  `CapabilityHealth` values — evidence the existing health enum survives as the
  status vocabulary of a B-model. `observedGeneration` answers "is this status
  about the *current* desired state or a stale one" — the tri-state trap
  (`slice-execution`'s "don't conflate unknown with healthy") solved as data.
- **Fighting controllers** (our five §10b controllers) is the named failure
  mode; canon fix is **field ownership** — one writer per field. K8s enforces
  it with Server-Side Apply machinery; in-process, ownership-by-architecture
  (one controller class per state field) suffices — the distributed machinery
  does NOT transfer, the discipline does.
- **Level-triggered + idempotent reconcile** ("wake, re-read, diff, converge")
  beats edge-triggered event replay for exactly our failure shape: missed
  events, restarts, autonomous transitions. K8s v1.36 (2026-04-28,
  kubernetes.io blog "Staleness Mitigation") frames stale observed-state reads
  as the root of "reconciliation storms and oscillating behavior."
- **HCI literature** (smart-home explainability, arXiv 2412.09813): unexplained
  autonomous action is the trust-killer; explainability + predictability are
  the levers. Confirms §10b's "haunted system" risk as a hard requirement:
  every controller-initiated transition carries a rendered `reason`. In-repo
  prior art: `LifecycleReasonCode` + `readinessNotice.ts` + their drift gate.
- **Statecharts** (statecharts.dev; Harel): orthogonal regions grow linearly
  where a flat enum grows as the product of axes — the formal version of §8c.
  Practical guidance: in-flight procedures that are scoped to one state and
  cancel on exit = `invoke`-style nested; procedures independent of another
  axis's transitions = parallel region. Chat-engine axis vs GPU-work axis is
  the textbook two-region case. Desktop prior art for desired-state exists
  (systemd unit files, PowerShell DSC's Get/Test/Set = observe/diff/converge)
  but none of it surfaces *reasons* — the Condition import is the differentiator.

### 11b. Local-LLM GPU arbitration (Candidate C) — the ecosystem consensus IS the lease arbiter

- **Ollama's scheduler** (`server/sched.go`, MIT) is the closest production
  analogue of Candidate C: loaded models are holders with tracked VRAM sizes,
  admission = "does the incoming model fit alongside residents," eviction =
  LRU-idle, `keep_alive` TTLs, and **embedding models are special-cased**
  (loaded with `parallel=1`) — i.e. chat + embeddings co-resident *when the
  arithmetic fits*, not a mode toggle. A 2025-09-23 rewrite moved from
  estimated to exact memory accounting (ollama.com/blog/new-model-scheduling);
  its open VRAM-accounting issues (#13250, #12922, #10359) are a cost warning,
  not a direction warning.
- **llama.cpp's own server** grew router mode with `--models-max` (default 4)
  + LRU eviction (HF blog 2025-12-11); **llama-swap** (MIT) models *groups* —
  some exclusive-swap, some co-resident (`swap: false`) — a lease policy
  language. **KoboldCpp** runs chat + embeddings + draft models concurrently
  via per-role GPU-layer flags. **No surveyed tool models a binary mode.**
- **The 10d-1 assumption resolves precisely**: "chat and embeddings can never
  share the GPU" is false as a rule, true as the common *outcome* on 8 GB
  cards. Ecosystem answer per tier: 8 GB → arbiter that in practice grants
  exclusive-sized leases; 12 GB → small embedder + KV-quantized chat model
  genuinely co-resident; 16 GB+ → multi-grant routine. Only a lease-with-size
  model expresses all three tiers with one concept.
- **Windows-specific hard requirement**: since driver 536.40, NVIDIA's
  "sysmem fallback" means VRAM oversubscription on consumer GeForce does NOT
  fail — it silently spills to system RAM and collapses throughput (the
  driver default). **The arbiter must do its own size-aware admission
  control; the OS will not fail loudly for us.** This also retroactively
  justifies the existing 2 s VRAM-flush conservatism.
- **Swap-cost framing**: 4-8B GGUF cold load is ~10-45 s on NVMe (ecosystem
  reports; llama-swap's docs treat >60 s as expected for large models). So the
  arbiter's long-term value is not shaving our 2 s flush — it is *avoiding
  unnecessary full evictions* (seconds-to-a-minute each) that the current
  boolean forces, e.g. killing the chat model to run an embedding backfill a
  2 GB grant could have satisfied. Relevant local caveat for any co-residency
  design: ORT's CUDA arena does not return freed memory to the OS
  (onnxruntime docs; issues #22146/#26610 on multi-session fragility) — our
  `ort-common` sessions and llama.cpp's allocator are two independent CUDA
  consumers, so co-residency admission must budget the arena high-water mark,
  not current usage.

### 11c. MCP dynamic tool availability (§8a agent-palette inversion) — hide is wrong per spec AND practice

- Spec status (checked 2026-07-15): current revision **2025-11-25**; the
  2026-07-28 RC (locked 2026-05-21) adds a stateless core, Tasks, MCP Apps —
  and **no tool-availability state**. A `Tool` is listed or absent; the four
  annotations (`readOnlyHint` etc.) describe behavior, not availability.
- The 2025-11-25 spec *affirmatively directs* the alternative: tool-execution
  errors "contain actionable feedback that language models can use to
  self-correct"; clients "SHOULD provide tool execution errors to language
  models." The canonical example is remedy-bearing ("must be in the future.
  Current date is 08/08/2025"), not a bare denial.
- The one spec-track precedent for hiding (SEP-1881, scope-filtered discovery,
  2025-11-24, no maintainer engagement) targets **permanent authorization**
  unavailability. Ours is the opposite: transient, health-based, and the
  hidden tool is *the lever that fixes the precondition*.
- **`notifications/tools/list_changed` is unreliable in real clients**: Claude
  Code shipped without a handler for it for an extended period (issue #13646,
  closed as dup of #4118; reportedly fixed ~2.1.0), Cursor and VS Code have
  open gaps. A recovery tool revealed only by list mutation can stay invisible
  after the subsystem heals. Independent, empirical argument against
  hide-and-reveal regardless of spec position.
- Practitioner convergence (Anthropic "writing effective tools for agents"
  2025-09-11; AWS MCP tool-design 2026-07-09; arXiv 2603.13417's SERF
  `suggested_actions` shape): errors name what's wrong + the correcting action
  ("call `list_projects` first" pattern).
- **Concrete verdict for our defect**: recovery operations stay **always
  listed** on the agent surface; the derived-availability hiding
  (`OperationCatalogComposition` → `AgentOperationEmitter`, §8a) is replaced
  by remedy-bearing execution errors —
  `"inference is offline; call core.switch-inference-mode {mode:'online'} to
  restore it, then retry"` — structured (SERF-style) inside the error content
  if we want more than prose. This holds under ANY §10 candidate and is
  probably the most immediately actionable research result.

### 11d. What the research changes about §10

1. **B and C compose into a known, named architecture**: desired state (spec)
   + Condition-shaped status with mandatory reasons + a size-aware resource
   arbiter is exactly the control-plane shape the two strongest external
   ecosystems converged on independently (K8s; Ollama). Candidate A gains no
   external support beyond generic precondition modeling — the research
   strengthens the B+C composition relative to A.
2. **The lease needs a size, not just a holder** (11b) — §10c as originally
   sketched (binary holders) under-specifies; grant-size admission control is
   what unlocks the 12 GB+ tiers and defends against the Windows silent-thrash
   cliff.
3. **The Condition import is the explainability answer** §10b demanded, and
   the existing `CapabilityHealth` values map onto KEP-1623's standard
   vocabulary — more evidence the health enum is a keeper (projection), while
   the Mode enum is not.
4. **The agent-surface fix (11c) is candidate-independent and cheap** — it can
   ride rung 1 (the interim fix) rather than waiting for the ontology
   decision: stop hiding, add remedy-bearing denial text.
5. New risk registered: ORT arena high-water-mark budgeting (11b) — any
   co-residency admission logic must account for it; this is a
   JustSearch-specific constraint no external scheduler shares.

---

## 12. Design (2026-07-15) — one runtime authority, five projections, named orphans

General-level design; implementation detail belongs to `/plan`. Scope is matched
to the problem §8 proved: the five-vocabulary fork structure and the
unprincipled capability algebra. It deliberately does NOT include structure for
problems not yet present (sized co-residency implementation, multi-model
scheduling, wire v2) — but it refuses ontology that would *preclude* them
(§10d-1's lesson).

### 12a. The canonical model (new; Head-side)

One authority answering "state × requested action → outcome", with four parts:

- **`RuntimeSpec` (desired state; user/policy-owned, persisted).** Minimal:
  `chatEnabled` (what "Start AI" / "Shut Down AI" actually mean) plus the
  already-persisted variant/model selection it references. Subsumes
  `JUSTSEARCH_AI_AUTOSTART_ENABLED` (boot = first reconcile toward spec) and
  `preVduConfig` (procedures return to spec, not to a hand-saved copy).
- **`RuntimeStatus` (observed state; controller-owned).** Condition-shaped
  entries (§11a) — per-axis `status + reason + message + observed-spec-version`
  — for: engine process (`Down(reason) | Starting | Healthy | Recovering`),
  adoption (own-process | external), in-flight procedure overlay
  (install / activation / VDU-batch, each with progress), GPU lease state.
  Reason codes join the existing `LifecycleReasonCode` +
  `check-readiness-reason-codes` mechanism — same register, same drift gate,
  NOT a parallel one. The existing `CapabilityHealth` values (≈ KEP-1623
  vocabulary, §11a) survive as this layer's status vocabulary.
- **GPU lease arbiter.** Replaces the mode concept: chat engine and
  embedding work are *holders* of a GPU grant; ONLINE ≡ chat holds it,
  INDEXING ≡ worker may use it, OFFLINE ≡ nobody. Binary grants now; the
  interface admits a grant *size* later (§11b: capacity-gated co-residency is
  the ecosystem norm and Windows will not police oversubscription for us —
  but implementing sized admission is future work, not this tempdoc's). The
  MMF boolean stays exactly as-is, re-derived as the lease's cross-process
  projection (§8c already showed it is the one honest artifact).
- **One reconciler; single-writer field ownership.** Converges actual toward
  `spec ∧ policy ∧ pending-work`. The five autonomous controllers stop calling
  `switchTo*` directly and become *inputs*: the idle sampler contributes a
  pending-work signal; crash recovery lives inside the engine axis; the VDU
  coordinator requests a procedure; boot auto-start dissolves into "reconcile
  at boot". Enforcement is the proven caller-forcing ArchUnit seam
  (`embeddingAutoRescueMustGoThroughEmbeddingRecoveryOps` pattern): direct
  mode/engine mutation outside the reconciler fails the build. This is what
  makes §3d's never-switch-back *inexpressible* — a procedure ends and the
  reconciler returns the system to spec, because spec is data, not a
  convention.

The 518 machinery (transition envelope, `LlamaServerOps`, immutable views,
listener fan-out, MMF bus, install/activation procedure trackers) is retained
under the authority — §9's "rewrite the model, keep the machinery."

### 12b. The operation surface

- **Lifecycle operations carry empty `RequiredCapability` sets** and validate
  internally — conforming to the pattern the same catalog already uses
  correctly for install/pack ops (§8a). The circular class is thereby
  *inexpressible* for lifecycle ops rather than merely corrected.
- **`core.switch-inference-mode` is superseded by a spec write** ("set chat
  enabled true/false") with no preconditions — an intent cannot be "denied
  because the thing it asks for is off". The old operation remains temporarily
  as an alias (`online`→enabled, `indexing`→disabled) and is retired once the
  FE migrates (§9a coupling: wire is string-soft; consumers, not contracts,
  pin the old values).
- **Denials become remedy-bearing** in 725's already-shipped actionable-errors
  shape (conform, don't invent): any remaining denial names the unblocking
  action. Lifecycle recovery operations are **always listed** on the agent
  surface — the derived-availability hiding is removed for them (§11c:
  spec-directed, and `list_changed` is unreliable in real clients).
- **One dispatch authority per action**: `POST /api/ai/runtime/activate` and
  `core.activate-runtime-variant` converge on the same internal entry point
  with the same gating (today: gated-circular vs ungated — §8a's dual
  authority).

### 12c. Projections and gates (the fork-killer)

The five §8d vocabularies become derived projections of the authority:

1. `Mode` enum → derived during migration, then **deleted**. INDEXING/OFFLINE
   collapse into `Down(reason)` + lease state; TRANSITIONING into the
   procedure overlay (its wire leak and the redundant `starting` alias retire
   with it).
2. `InferenceCapability` → re-keyed to the engine axis (mechanism kept).
3. Wire (`/api/status`) → exposes spec + status additively; old `phase` /
   `starting` maintained as derived aliases, then retired.
4. FE (`aiVerdict.ts` / `aiStateStore.ts`) → thin renderers of backend
   spec+status+reason (aiVerdict already has the right shape — §9a; it stops
   re-deriving and starts consuming).
5. Agent palette → renderer of the same status (12b).

Two gates, both extensions of existing mechanisms (not new machinery):

- **Fork gate**: a `runtime-state` register in the `execution-surfaces`
  pattern (553; 735's medicine) — canonical source + registered projections;
  an unregistered referencer of runtime state fails the build. Per 735, the
  register names the incident class it prevents (§3c/§8d) and its dissolution
  condition (projections become generated).
- **Circularity gate**: the declaration that produced §3b becomes either
  inexpressible (empty sets for lifecycle ops, 12b) or build-failing (a
  register row asserting lifecycle ops must not require `InferenceOnline`).
  The §8f spike (derive establishes-sets from handler signatures) decides at
  `/plan` time whether the stronger general form is cheap; the design does not
  depend on it.

### 12d. What this design orphans (deletion belongs to THIS tempdoc's work)

| Orphan | Fate |
|---|---|
| `Mode` enum as authority (`app-api/Mode.java`) | derived projection during migration → deleted |
| Wire `starting` alias; TRANSITIONING wire leak | retired with the old wire fields |
| `RequiredCapability.InferenceOnline` on the 4 circular ops | removed (12b) |
| `RequiredCapability.IndexedRoot` + `GpuAvailable` variants + lying resolver arms (`HeadAssembly.java:1213-1215`) | deleted now — dead vocabulary, zero users (§8a) |
| `preVduConfig` save/restore | subsumed by spec-return |
| `JUSTSEARCH_AI_AUTOSTART_ENABLED` semantics | subsumed by persisted spec (env at most seeds it) |
| Direct `switchTo*` authority of `OfflineCoordinator` / samplers / boot paths | rerouted through the reconciler; ArchUnit-forced |
| Derived-availability hiding of lifecycle ops on the agent surface | removed (12b) |
| Ungated `POST /api/ai/runtime/activate` as second dispatch authority | unified (12b) |
| Fossil tests: `CapabilityAvailabilityTest.java:43-53` circular pin; `BrainSurface.indexing-escape.test.ts` (5) | replaced by model-agnostic acceptance tests, headlined by: *"from every reachable state, the user-visible escape action succeeds"* (the §3b+§3d regression) |

### 12e. Staging constraint (from §9c) and sequencing with 0.2.0

The authority lands first *as the single writer* (ArchUnit-forced from its
first commit); projections migrate one at a time; each fork's deletion is this
tempdoc's work, not a later sweep; the fork register + gate arrive WITH the
authority, so no step leaves two live authorities ungated. Do not touch the
four ops' requirement sets until 0.2.0's minimal unblock has shipped
(unlanded on the release branch as of this writing); the interim unblock
should prefer the empty-requirements form (§10g) so rung 1 survives this
design unchanged.

## 13. Reach — principles, where else they bind, and when they retire

This design is deliberately assembled from seams the repo already has —
Condition-reasons join `LifecycleReasonCode`'s register+gate; the fork gate
extends the 553/735 register pattern; single-writer enforcement reuses the
ArchUnit caller-forcing seam; actionable denials conform to 725's shipped
shape; wire evolution rides the additive-`v1` convention. The genuinely new
things are exactly two: the spec/status split with a reconciler, and the GPU
lease abstraction.

- **P1 — "An operation must not require what its success establishes."**
  Instance of the wider "declared metadata must be validated against behavior"
  (LiveWitness's principle). Binds: every operation catalog, now and future.
  Earning its keep: the gate (or the inexpressibility construction) catches a
  circular declaration before review at least once, or no new instance appears
  while it stands. Retirement: when requirement sets are *derived* from the
  model instead of hand-declared, the rule is enforced by construction and the
  gate dissolves (735's dissolution shape).
- **P2 — "State consumed by more than one surface needs a declared canonical
  source and a fork gate."** Not new — this is 553's principle, named by 735;
  runtime lifecycle state is its third confirmed instance (SearchTrace, agent
  response furniture, now this). This design *conforms* rather than
  generalizes. Already-known candidate instances stay with their owners (735).
- **P3 — "Autonomous controllers require a declared desired state and
  single-writer field ownership."** New, this tempdoc's own principle. The
  system had five controllers converging on shared state with no spec — §3d is
  the incident class. Candidate scope beyond AI runtime: any future background
  maintenance loop; a possible existing tension worth a one-line audit someday
  is watchdog-restart vs user-stop intent in the dev runner. Earning its keep:
  the §12d acceptance test stays green through future lifecycle changes, and
  "the system changed state and the user can't tell why" stops appearing in
  observations. Retirement: if the runtime ever becomes single-controller
  (no autonomy), spec/status is apparatus — collapse it back to direct
  commands.
- **P4 — "Resource exclusivity is policy, not ontology."** The lease carries
  the policy; the type admits sizes without carrying an implementation.
  Earning its keep: the first co-residency request (12 GB tier, or any second
  GPU consumer such as a Head-side reranker) lands as a policy change, not a
  remodel. Retirement: a permanent product commitment to single-GPU-consumer
  deletes the size dimension.

Public-claims note: nothing in this design feeds public-facing quantitative or
compliance claims; if any future README/business text cites the lease model or
"predictable AI runtime", it must wait for the shipped, measured behavior.

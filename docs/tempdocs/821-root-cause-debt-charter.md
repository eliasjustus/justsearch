---
title: "821 — Root-cause debt charter: verified debt census and class map for the post-installer risky phase"
type: tempdocs
status: "CHARTER + DERISKED + WAVE 1 EXECUTED (2026-08-12) — census complete (§2), classes mapped (§3, three exemplars corrected in place per §L.1), derisked (§L), and the first implementation wave is DONE on six unpushed worktree branches + the pre-staged 792 rescue (§M): C1 FE+BE truthfulness, C2 watched-root scan arm, rerank count-drop (F-045), facets→MCP relay, demo-blemish batch — each independently reviewed and re-fixed, all suites green, NO PRs per owner hold. Still open: §7 owner decisions (D1-D6), §M residuals 1-6 (measured UX audit, live A/B, needs-live lane, RAG wire tier, store cleanup per-occurrence rule — §6.1 wholesale deletion stays retracted, 33% refutation in the deletion stratum), and §2c tempdoc items remain UNVERIFIED candidates."
created: 2026-08-12
author: agent session 776e10cd-eef9-4873-a027-1fc2887a334d (Fable orchestration; 14-worker read-only sweep)
category: structural / debt / planning
related:
  - 799-structural-health-theorization.md   # the worked example this charter's house style follows
  - 787-post-arc-platform-hygiene-sweep.md  # prior enumerated-debt bundle
  - 749 / 734 / 754 / 742                   # named debt classes this census re-confirms
  - 792-stack-currency-audit-round-3.md     # §5's most urgent rescue
  - docs/observations.md                    # the conditions store this census drains
---

## §0 Purpose and frame

Owner direction (2026-08-12): after the first working installer exists (a frozen demo
artifact for testers/investors — no users, no compat constraints), the project enters a
riskier phase. Two directions were chartered to separate agents: **root-cause debt**
(this document) and **core differentiators** (eval-driven search quality + MCP surface,
owned elsewhere). Frontend is explicitly out of scope for this direction (a separate
agent owns a frontend rework). The governing principle from that conversation: each
rewrite must be justified by a known defect or a measurable goal — not by the
newly-available freedom to refactor.

This document is a **census and class map, not a design**. It exists so the risky phase
starts from verified facts instead of folklore: which recorded debt is still real on
current main, what root-cause classes it collapses into, and what decisions only the
owner can make.

## §1 Method and provenance

14 read-only workers ran 2026-08-12 against main (working tree at `d0b2e97b`+dirty):

- **8 defect-verification workers**: every open `kind: defect` condition in
  `docs/observations.md` (300 total) verified against current code with required
  file:line evidence. Verdicts: STILL-TRUE / FIXED / STALE / NEEDS-LIVE / UNCLEAR,
  plus class (product/drift/tooling/governance) and demo-relevance.
- **3 light-triage workers**: the 156 open non-defect conditions
  (environment/lesson/follow-up) — staleness check + routing proposal only, per the
  store's own routing rules.
- **2 tempdoc miners**: tempdocs #700–#818 + `docs/reference/issues/*` registers +
  postmortem cases — extracted CANDIDATE open items (dated history, NOT verified).
- **1 stranded-branch auditor**: all 56 ahead-of-main local branches, verified by
  CONTENT (`git diff <branch> main -- <paths>` + sampled `git log -S` probes), never
  ancestry — squash-merge makes ancestry meaningless here
  (`squash-merge-verify-content-not-ancestry`).

Verification standard: a STILL-TRUE or FIXED verdict required a checked file:line.
Worker findings are a starting point (`audit-without-test`); the per-item evidence is
in §Appendix A and the full JSON verdicts lived in the session scratchpad (ephemeral —
this document is the durable record).

## §2 The census

### §2a Conditions store (docs/observations.md — 456 open conditions)

| population | count | outcome |
|---|---|---|
| defect conditions verified | 300 | **145 STILL-TRUE** (46 product / 36 drift / 41 tooling / 22 governance; **22 demo-relevant**) |
| — already fixed or stale | 111 | delete at next maintainer pass (§6; Appendix C) |
| — needs a live stack | 28 | §7 D3 verification lane (Appendix B) |
| — unclear | 16 | re-examine during class work |
| non-defect conditions triaged | 156 | 102 likely-current / 41 likely-stale / 13 unclear; routing proposed: 53 register, 35 park, 30 retire, 24 lessons, 8 expected-state, 6 tempdoc |

Headline: **~25% of the store's recorded defect debt is already paid** (77 fixed — many
via explicit tempdoc-cited root-cause fixes) and was never deleted, because the
maintainer triage pass the store's design assumes has never run at scale (450 of 456
open conditions still carry unconfirmed fold-proposed kinds).

### §2b Stranded branches

56 local branches ahead of main; **52 landed by content** (every branch whose worktree
directory was already removed had landed — a clean signal). One superseded
(`lane-782-investigation`). **Three genuinely unlanded** → §5.

### §2c Tempdoc mining (UNVERIFIED candidates)

193 candidate open items from #700–#818 (127 flagged root-cause), heaviest in search
(38), governance (28), indexing (26), installer (19), ui (18). The seven standing
`docs/reference/issues/*` registers are dormant-to-dead (most stamped 2026-02;
`backend-tech-debt.md` is the one still worth mining — 15 open BKD entries, several
matching failure shapes the 2026-08 validation rounds independently rediscovered).
Postmortem handle index lists 20 of 27 actual cases — it has drifted.

## §3 The root-cause classes

Every class below is confirmed by at least two independent sources (store verification,
tempdoc mining, or branch audit). Exemplars are code-verified file:line on 2026-08-12
main unless marked (candidate).

### C1 — State-truthfulness: surfaces asserting states they don't know

The largest and most demo-dangerous class. One mechanism recurs: **a surface renders a
definite claim derived from an optimistic, partial, or stale signal, with no
reachability/staleness gate** — and several surfaces per screen answer the same
question from different authorities.

- ~~Status bar keeps claiming "Restarting…" for retained `indexState=UNAVAILABLE` when
  the backend is dead (`verdict.ts:121-123`)~~ — **REFUTED at §L.1 (2026-08-12): FIXED.**
  807 §E.4's lost-contact guard sits *above* all six retained-cause branches and
  `verdict.test.ts:181-208` pins each of them. Residual holes remain the class members:
  `reachableViaContact === undefined` falls through ungated, and contact-alive-but-
  data-ancient (streams heartbeating while the poll snapshot is minutes old) projects
  six retained fields present-tense with no age check — plus `HealthSurface.ts:888,1032`
  reads retained fields raw while sibling lines gate on `snapshotLive`.
- Every provisional folder cause renders as "Rebuilding…" regardless of actual cause
  (lost-contact, channel-stale, worker-restart) (`folderStatus.ts:203-213`).
- A locked/restarted chat session's transcript reads as a false empty (404 "No events")
  instead of "locked" — the javadoc itself says this is an open defect
  (`RunEventStore.java:168-199`).
- ~~Action-ledger dead backend renders "No activity yet." indistinguishable from
  empty~~ — **REFUTED at §L.1 (2026-08-12): FIXED** at a different layer (804 §B9/F9:
  `ledger-unreadable` banner from the snapshot-GET catch, `ActionLedgerView.ts:487-494`,
  regression-tested). The residual class member: the STREAM half still swallows
  `isConnected` (`ActionLedgerClient.ts:484-498` destructures the snapshot and drops
  it), so stream-only degradation remains undistinguishable — hardening, not the
  original headline.
- (candidates, tempdoc 734/810/817) "done" derived from no-work-outstanding rather than
  coverage-complete; dead backend showing green CONN + 4/4 capabilities; locked-state
  dispatch returning 200 and discarding the question.

Root-cause response shape (not per-symptom patches): a single reachability/staleness
authority that every status-rendering surface must consult, plus the count/scope rule
in C2. Tempdocs 734 ("surfaces that report success for work they didn't do") and 810/817
(progress truthfulness) already name this class; nobody has built the class-level fix.

### C2 — Scope fidelity: filters and labels accepted but not applied

A parameter the user (or agent) supplies is accepted, displayed — and silently ignored
somewhere down the pipeline. The result is answers that are wrong about *which corpus*
they describe.

- RAG context builder never applies collection scope: ASK answers can pull context
  across collections (`modules/worker-services/.../RagContextOps.java:895-941`,
  `buildRagFilters`). **Corrected at §L.1 (2026-08-12):** the `.docIds(...)` half of
  the original claim was wrong — docIds ARE honored via a side channel
  (`effectiveDocIds`, `RagContextOps.java:260-289`); **collection is the real drop**,
  and §L.3 sizes it as wire-contract (never plumbed end-to-end: proto,
  `RetrieveContextParams`, all three entry points, FE chat all lack the field).
- Watched-root initial scans pass a literal `null` collection — documents admitted
  untagged while the API reports the collection label
  (`RemoteKnowledgeClient.java:270-273`; pinned as a known-gap baseline by
  `WatchedRootScanCollectionBaselineTest`, tracked nowhere).
- A labelled corpus cannot be searched by its own label from the UI
  (`knowledgesearchrequest`); Library's "Process pending enrichment" shows no count.
- `/api/knowledge/search` facets silently return empty; field-term queries cap at 100
  with no error — an agent reads false per-value counts (`knowledgesearchcontroller-error`).
- Same query returns different result counts run-to-run: cross-encoder rerank gating
  reads a lazily-populated `avgContentLengthChars`
  (`KnowledgeSearchEngine.java:884-893` / `WorkerStatusCache.java:153`).
- (candidates, 770-818 mining) `totalHits` computed over a different population than
  rendered results; `matchCount` lexical-only vs 3-leg fusion; "Top 50 of 288" over
  "Show all 20" — one class: a number true of a narrow scope presented as the whole.

### C3 — Enrichment-pipeline completeness: silent sub-population loss, no self-repair

Tempdoc 749 named this class with four documented instances (F-032, 712, 717, 749) and
recommended a class-level response that was never built. Still-true members:

- UI force-reindex never reaches re-chunk — installed bases cannot self-repair a bad
  chunk population (candidate, 749/tempdoc-mined; partially verified via 718 exposure
  item still open).
- Backfill retry gap: FAILED chunks are fetched per-chunk and never retried
  (`embeddingbackfillops`, verified).
- The long-document floor (817 head-of-line blocking, unpersisted window progress,
  starved SPLADE/NER; 785 throughput never profiled; 784 dead sparse leg at 512
  tokens) — candidates owned by the differentiators direction where they concern
  quality, but the *silent-loss* mechanism is this class.

### C4 — Lifecycle notification gaps: arrival/shutdown events not plumbed

- `EmbeddingProviderLifecycle.setEmbeddingProvider` never notifies listeners on
  arrival: a mid-session AI-model install can leave the worker's embedding capability
  stuck UNAVAILABLE — in the onboarding demo path
  (`EmbeddingProviderLifecycle.java:118-121`).
- `ResourceApiModule.shutdown()` never calls `intentStreamController::shutdown` —
  heartbeat scheduler thread leak (`ResourceApiModule.java:532-556`).
- Embedding-fingerprint boot race: remediation code exists post-observation
  (`EmbeddingProviderLifecycle` persistence hardening, 730/819 work) but only a live
  restart confirms the symptom gone — NEEDS-LIVE, coordinate with the 819 worktree
  before touching.

### C5 — Dark ships: confirmed fixes that exist but are OFF, unmerged, or unlanded

The cheapest real value in the census — the diagnosis AND the fix already exist.

- **Dependency currency is silently dead on main**: `worktree-792-stack-currency`
  (unlanded, §5) removes the dead GitHub Packages resolution repo that has killed every
  Gradle library/dependabot update (`settings.gradle.kts:20-33` still broken on main;
  zero Java libraries ever bumped, 32 behind, 6 across majors, llama.cpp ~4 months
  stale).
- SPLADE truncation fix shipped default-OFF with its validating A/B on hold (712).
- Model Capability Contract merged but fail-fast OFF, orphan list unswept (710).
- EvidenceSpan canonical record flag-off with RAG/CE/MCP consumers unconformed (775).
- CUDA llama.cpp download has no sha256 pin — the CPU path has one
  (`unanchored-general-90`, verified).

### C6 — Representation forks: two authorities answering one question

The 553/816 class, still accreting members:

- FE `CorePlugin.ts:176,218` says `core.health-surface`/`core.activity-surface` are
  OPERATOR; Java `CoreSurfaceCatalog.java:663,728` says USER — affects who sees them.
- Installer size: README says 853 MB, installer skill says ~260 MB — a downloading
  tester sees both (verified, widened not closed).
- Browser-origin gated agent actions hardcoded as `InvocationProvenance.mcp()` — can
  double-queue approval dialogs (`AuthorizationController.java:285` +
  `pendingAuthorizationBridge.ts:79-108`).
- (candidates, 801/811/816/818) fourth affordance→shape map; two independent ingest
  surfaces; width literals beside the role register; three parallel conversation models.

### C7 — Measurement-apparatus integrity

Every published quality number rests on instruments with known defects: the harness
scored a different ranking than the engine delivered (800), the re-baseline is blocked
on a parked SPLADE-enrichment bug (802/803), citation cross-encoder entirely
un-instrumented against a Feb-2026 ~33-50% precision baseline, sandbox harness had 13
false-green mustWatch channels (808). Mostly owned by the differentiators direction —
listed here because five postmortem cases are structural measurement debt and any C1-C3
fix that cites a metric inherits this risk.

### C8 — Governance decay: the debt system itself

- The store's triage pass has never run (this census is its first execution; §6
  finishes it).
- Seven `docs/reference/issues/*` registers are dead authority — retire-with-a-sweep
  or revive (owner call, §7 D5).
- Retired-to-prose gates (`ux-audit-closure`, `independent-review`) haven't run in
  months; a11y suite not CI-wired; postmortem handle index drifted (20/27).
- `/publish` skill still instructs delegating merge/publish, contradicting CLAUDE.md's
  never-delegate rule (`unanchored-general-56`, verified live).

## §4 Demo-blemish lane (small, verified, high visibility)

Not root-cause work, but exactly what a tester/investor sees first. All verified
file:line; most are single-edit scale; bundle as one or two sweep PRs:

1. ~~Dark-variant app icon near-white on transparent~~ — **REFUTED at §L.1
   (2026-08-12): FIXED** 2026-08-07 (#394): `brand/generate.mjs:630-649` plates every
   .ico/.icns/PNG on an opaque near-black `iconFrame()`; shipped `32x32.png`
   pixel-probed opaque.
2. Packaged Tauri CSP blocks Google Fonts that `index.html:24,26` still links — the
   shipped app silently drops its display font (`tauri.conf.json:77`). Fix = vendor the
   font (loopback-only posture anyway), not widen CSP.
3. "Copy URL (Ctrl+L)" tooltip vs `mod+l` actually bound to focus-composer
   (`Shell.ts:936-937` vs `:2254`).
4. Installer size 853 MB vs ~260 MB across surfaces (C6 member).
5. "New chat" button hidden on fresh/empty chat (`UnifiedChatView.ts:2317-2328`).
6. Sessions list renders blank timestamps (FE reads `startedAtEpochMs`; backend emits
   different fields) (`agentsessioncontroller`).
7. Agent-mode three-zone layout overflows 104px — code admits "KNOWN RESIDUAL"
   (`unifiedChatRequest.ts:153-156`).
8. Agent/MCP-driven ingest shows no live progress (second `KnowledgeHttpApiAdapter`
   constructed without progress registry/ledger — `AgentToolFactory.java:60-61`).
9. Settings model-swap silently nulls the vision/VDU projector — user loses image/PDF
   vision with no explanation (`InferenceConfig.java:160-168`).
10. Gated-action dialog: no focus-trap/aria-live (`AuthorizationHost.ts`); plus the
    SourcesPane nested-interactive axe violation (pre-existing, surfaced by 814).

NOTE: several touch `modules/ui-web` — the frontend-rework agent owns that tree's
future; coordinate so these land either in their rework or as pre-rework patches, not
as conflicts. Backend-side items (2 via tauri.conf, 8, 9) are unaffected.

## §5 Rescue lane: unlanded branches

| branch | what it is | action |
|---|---|---|
| `worktree-792-stack-currency` (9 ahead, 2026-07-29) | Removes dead GitHub Packages repo (kills ALL dependency updates on main); retires Revapi; sole copy of tempdocs 793+794 | **Rescue first** — dependency lane + security posture; small, already-reviewed-shaped |
| `worktree-795-measurement-batching` (10 ahead) | Campaign-agnostic `jseval/campaign_preflight.py` + 802 lines of tests + tempdoc 795 — none of it on main | Rescue; hand to differentiators direction (their instrument) |
| `worktree-help-content-accuracy` (1 ahead) | Corrects in-app help vs live UI (keyboard-shortcuts doc) | Re-verify against post-818 search-v2 bindings, then land or fold into FE rework |

52 landed branches + their worktree remnants: cleanup at the next maintenance window
(verify-then-delete per branch-safety; NOT this charter's scope to execute).

## §6 Store maintenance pass (finish what this census started)

The census produced everything the store's own §Resolving flow needs:

1. ~~Delete the 111 fixed/stale defect conditions wholesale~~ — **RETRACTED at §L.1
   (2026-08-12): the adversarial audit refuted 4 of 12 sampled FIXED/STALE verdicts
   (33%)**, recurring mode: a multi-occurrence condition marked FIXED when only its
   headline occurrence was fixed. Deletion requires a per-OCCURRENCE re-verification
   pass (delegable, but every occurrence line checked, not just the headline), or
   deletion only of conditions the audit itself upheld (7 of 12 sampled) plus
   single-occurrence conditions whose one fix is cited.
2. Execute the non-defect routing (53 register / 30 retire / 35 park / 24 lessons /
   8 expected-state / 6 tempdoc) — the store is a buffer, not a home.
3. Confirm kinds on the 145 surviving defects as they route into class work.
4. Route the two escalated untracked defects (null-collection scans → C2 owner;
   intent-stream leak → C4 owner) into `backend-tech-debt.md` or successor register
   per D5.

## §7 Owner decision points

- **D1 — Class priority.** Recommendation: C5-792 rescue immediately (dependency lane
  is silently dead — security posture for a public artifact); then C1+C2 as the
  flagship root-cause work of the risky phase (each ends in a gate/authority, not just
  fixes); C3/C4 next; C6 opportunistic alongside; C7 owned by differentiators; C8 §6
  executes most of it. Falsifier for C1/C2 done-ness: a class-level gate exists and a
  new member cannot ship green.
- **D2 — Store cleanup execution** (§6): one delegated maintenance PR, or batched with
  class work?
- **D3 — Needs-live lane**: 28 conditions need a dev-stack session (Appendix B).
  Single supervised campaign, or verify opportunistically per class?
- **D4 — Demo-blemish timing**: before the installer freeze (they're what testers see)
  or into the risky phase? Items 1/2/4 arguably belong IN the first installer.
- **D5 — Dead registers**: ~~retire or revive?~~ **EXECUTED (2026-08-12, owner-authorized
  default, branch `worktree-agent-aa70c939fd4d37e5b` commit e576e570):** all eight
  `docs/reference/issues/*` files retired with a 14-reference sweep (regens + link
  checks green). 21 live entries + 1 consolidated decisions stub routed into the
  observations store (BKD-012 tagged with class C3/C4 — promote from the store at the
  next fold if wanted as a first-class item); 24 entries verified dead, 5 of those
  verified FIXED in source (BKD-011/021, INS-002/003/006). Non-defect census items
  routed "to register" now route to the store + the two domain registers. OPEN
  residue for the owner: the decisions.md rationales with no other home (GPU-012
  DLL thresholds, EXC-002 methvin revisit trigger, UIX-015 first-run-help-docs fact,
  UIX-010/011/012 as expected-state rows) — listed in the retirement worker's report.
- **D6 — Class ownership overlaps**: C3-long-doc and C7 straddle this direction and
  differentiators; C1/C4 FE surfaces straddle the frontend rework. Proposed split
  drawn above; confirm or redraw.

## §8 What was NOT verified

- The 193 tempdoc-mined items are candidates from dated history — anything acted on
  needs source-verbatim re-verification first (`tempdocs-are-dated-history`).
- 28 NEEDS-LIVE + 16 UNCLEAR store conditions (Appendices B, inline notes).
- Worker verdicts are static-analysis truth, not runtime truth: a STILL-TRUE with
  file:line proves the code shape exists, not the symptom's runtime magnitude
  (`static-green ≠ live-working` applies in both directions).
- The three unlanded-branch verdicts sampled 30 lines/branch — re-diff before merging.

## §L Derisking record (2026-08-12, same session; plan approved by owner)

Eight agents: 3 adversarial verdict refuters (opus, independent of the census
workers), 2 C1 authority-map explorers, 2 C2 blast-radius probes, 1 isolated-worktree
792 merge dry-run. Findings by phase:

### §L.1 Adversarial verdict audit (55-item stratified sample)

| stratum | n | upheld | weakened | refuted |
|---|---|---|---|---|
| load-bearing exemplars (§3/§4) | 28 | 22 | 3 | 3 |
| random other STILL-TRUE | 15 | 13 | 2 | 0 |
| random FIXED/STALE (deletion safety) | 12 | 7 | 1 | **4** |

- STILL-TRUE verdicts are trustworthy (~7% refutation), and every refutation was in
  the *optimistic* direction (the defect was actually fixed): `verdict`, `mark-dark`,
  `actionledgerview` — corrected in place in §3/§4 above.
- FIXED/STALE verdicts are NOT deletion-grade (33% refuted): `cli`, `release-v1`,
  `agent-utility-inspect-error`, `read-corpus` all still carry live occurrences.
  Recurring failure mode: headline occurrence fixed, bundled occurrences not
  re-checked. §6.1 retracted accordingly.
- Weakened-but-live corrections: `knowledgesearchengine` nondeterminism survives via
  the `EXPANSION_BUDGET_MS` race (`KnowledgeSearchEngine.java:783-816`), NOT the
  tombstoned rerank gate (default 0 since 774 §J.2); `inferenceconfig` is a
  *misattribution* defect (a `vdu.missing_mmproj` notice exists but blames a missing
  file instead of the user's `llm.modelPath` override); `unanchored-general-56` is
  tracked in-repo — a one-line fix, not a blast radius. Upheld-but-stronger:
  `coreplugin` (FE OPERATOR *overwrites* the wire's USER at merge,
  `SurfaceCatalogClient.ts:501`); `unanchored-general-83` (the tier-register's named
  enforcing test contains no Lucene rule; the real rule lives in
  `app-api/ArchitectureRulesTest.java:19-23`); `unanchored-general-90` (`sha256Hex`
  defined but never called — no hash check on any CUDA download path).

### §L.2 C1 authority maps — verdict: distribution + enforcement, NOT new design

- **FE**: the reachability authority exists and is governed — `originContact.ts` →
  `connection.reachable` → `isSnapshotLive` (`aiStateStore.ts:573-575`), registered as
  the `connection` domain in `governance/inflight-liveness-projections.v1.json:30-38`
  with gate `check-inflight-liveness.mjs` — but that domain declares ONE render site;
  every defect surface sits outside it. Smallest change-shape (4 moves): (1) make
  `snapshotLive` a REQUIRED param of pure derivation seams (`FolderStatusContext`,
  passing the cause-bearing `Stability` value instead of a 1-bit `provisional` —
  fixes the cause-collapse for free; precedent: `selectIndexingProgress`,
  `indexingProgress.ts:544`); (2) close verdict.ts residual holes (undefined
  fall-through; contact-alive/data-ancient); (3) thread `isConnected` through
  `openActionLedgerStream`'s callback type (+ `isLanding()` consulting
  `unifiedThreadRefreshFailed`); (4) widen the register's render sites so the gate
  covers all status-asserting surfaces. Moves 1-2 have structural teeth; move 4 is
  early-warning (import-substring scan, register + discipline).
- **BE**: the staleness contract already exists and is unpopulated —
  `ReadinessComponentView.{observedAt,stale,stalenessMs}` shipped in schema + generated
  FE types, hardcoded `false, 0` at `StatusLifecycleHandler.java:1544-1547`;
  `StatusMeta.workerRpcStale` (the Head's real fallback fact, `:398-421`) has ZERO FE
  consumers; `SignalBusView` MMF heartbeat timestamps are debug-tier only;
  `CapabilityHealth` has no timestamp; `SupervisionDecision` (`:66-91`) is the only
  formal dead-vs-stale distinction. Fork-vs-projection is ALREADY ADJUDICATED: 637
  rejects any freshness god-endpoint; 807 rejects per-surface `if (unreachable)`
  patches; new BE↔FE thresholds must go through `gen-stream-liveness-constants.mjs`.
  Read first: tempdocs 807 Part A, 649, 637, 595, 806, 333; canonical
  `27-frontend-presentation-kernel.md`.

### §L.3 C2 blast-radius probes

| fix | difficulty | key facts |
|---|---|---|
| RAG collection scoping | **wire-contract + cross-module (5 modules)** | Never plumbed: `RetrieveContextRequest` (ipc proto, buf-gated), `RetrieveContextParams`, HTTP/MCP/chat entry points, FE all lack the field. Preserve: empty collection = default agent-history EXCLUSION (`QueryFilterBuilder.addCollectionScope:107-119`, 811 D-1), explicit `['agent-history']` = positive include. Trap: naive `hasFilters` inclusion reroutes through the doc-level parent pre-filter path. Needs a product decision (what selects the scope) before implementation. |
| Watched-root null collection | **local (scan arm)** — wire already done | `ScanRootRequest.collection` exists and Worker threads it to the index write; the drop is the Java-internal `ScanRootFn` signature (`RootLifecycleOps.java:100-106`). BUT: complete fix is cross-module — Head never persists root→collection (`WatchedRootsStore`/`State` lack the field; `getWatchedRoots():136-139` hardcodes null; restart loses the label on the watcher arm too via `reindexPersistedRoots:443`); the periodic-sync arm IS wire-blocked (`SyncDirectoryRequest` has no collection field); migration is real — plain re-scan skips UNCHANGED files before the collection write, and `SCAN_MODE_FORCE_REINDEX` is accepted but never consulted (inert — itself a new C1-class finding). Retag route that works today: `SubmitBatch(target_collection, force_reindex)`. Baseline test inverts to a positive pin (its own class comment plans for this). |
| Facets truthfulness | **tiered: local → cross-module → wire-contract** | SIX distinct empty-facet mechanisms (not one): non-facetable field → silent empty map (only 29 keyword+docValues fields can ever facet — most likely reported cause); swallowed RuntimeException → facets key vanishes; engine IOException → `truncated=false` lie; maxDocsScanned breaks the OUTER segment loop (flagged but segment-biased); page-2+ never facets (silent); multi-leg null facetQuery. Bonus defect: multi-leg facets + `matchCount` rebuild the query with hardcoded SIMPLE syntax (`SearchResponseBuilder.java:237,286`) — skewed vs LUCENE-syntax results. Highest truth-per-effort: MCP relay of `facetsTruncated` (local; MCP requests facets every call, instructs the agent to trust counts, and is the ONLY consumer not receiving the flag — `McpToolSurface.java:818-826` vs `:1007`). The 100-cap is Worker-side (`SearchPlanner.java:37,194-197`), unsignaled, undocumented (contrast MCP's honest "max 50"). |
| Rerank/count nondeterminism | **local + cross-module** | The charted `avgContentLengthChars` gate is INERT at default (774 flipped 16000→0; tombstone at `KnowledgeSearchEngine.java:172-179`) — fires only under operator override; cache warmed ONLY by `/api/knowledge/status`. The LIVE bugs: (a) query-expansion `EXPANSION_BUDGET_MS` race (`:783-816`) varies totalHits run-to-run; (b) default rerank branch DROPS candidates when the worker returns fewer sorted indices than topK (`:1002-1013`) — the non-default judge-blend branch documents and defends against exactly this. Deterministic gate source exists Worker-side (`IndexCountOps.getOrComputeCorpusProfile`, version-keyed, already read per-search at `SearchInputCapture.java:134`; units are tokens not chars). Eval baselines were ALL measured with the gate off ⇒ determinism fix at default 0 is baseline-neutral; only restoring a non-zero default would shift them (separate register-gated decision). Count-drop fix needs a same-session A/B. |

### §L.4 792 rescue dry-run — verdict: TRIVIAL, pre-staged

Merge of `worktree-792-stack-currency` into a fresh main-based worktree: ONE conflict
(an observations-shard modify/delete; resolved data-preservingly — its 3 entries are
genuinely unfolded), zero code conflicts, `BUILD SUCCESSFUL` (251 tasks, dependency
verification still closed, no GitHub Packages resolution). Defect confirmed still
live on main (`settings.gradle.kts` still carries the credentialed block). Caveats:
a local green build proves no-regression, NOT the fix (the block was CI-only-active
behind `GITHUB_ACTOR`/`GITHUB_TOKEN`) — decisive proof is the next Dependabot run
producing a real `[libraries]` bump PR; re-verify the shard conflict on any re-merge.
Merged worktree preserved at `.claude/worktrees/agent-ae5ff73d418e280c3`
(branch `worktree-agent-ae5ff73d418e280c3`, merge commit `3fb43908`).

### §L.5 Confidence and routing (derisk conclusion)

Per-lane implementation confidence (0-10): 792 rescue **9**; demo-blemish lane **9**
(minus the already-fixed icon); C1 **8** (terrain fully mapped, precedent + gate
exist; residual subtleties in verdict.ts precedence and the BE contract fill);
store-maintenance **7** (process clear, cost raised by per-occurrence rule); C2
watched-root **7** (scan arm easy; persistence + migration + 811 C-2 disposition need
one design pass); C2 RAG-collection + facets wire tier **6** (blast radius mapped but
product decisions and changeset discipline pending). Overall **7.5**.

Model routing recommendation for implementation: opus for C1 move-2/BE-fill, both C2
designs, and the count-drop fix (subtle precedence/contract judgment; A/B
interpretation); sonnet for enumerable mechanical batches (C1 moves 1/3/4 threading
after the pattern is set, blemish lane, store cleanup under the per-occurrence
protocol, 792 re-merge); effort high on C2 cores, medium elsewhere. Every C2 fix
lands with its register/changeset obligations (`api-record` skill for
`KnowledgeSearchResponse` fields; wire changesets for `contracts/wire`; buf gate for
ipc proto).

## §M Execution log (2026-08-12, same session; owner directive: work outside main, no PRs yet)

Six implementation lanes + the pre-staged rescue, each in its own worktree branch,
each implemented by a worker, adversarially reviewed by an independent agent
(reviewer ≠ implementer), and re-fixed by the original worker where the review
demanded. All branches green, committed, UNPUSHED, no PRs (owner hold).

| lane | branch (`.claude/worktrees/…`) | state |
|---|---|---|
| 792 rescue (C5) | `agent-ae5ff73d418e280c3` (merge commit 3fb43908) | Pre-staged: merge of `worktree-792-stack-currency` into fresh main base; 1 shard conflict resolved data-preservingly; BUILD SUCCESSFUL; defect confirmed live on main. Decisive proof remains CI-side (next Dependabot run must produce a `[libraries]` PR). |
| C1 FE truthfulness | `agent-a7925a2dea08c067b` (12 commits since base, latest 71bd7482 — incl. review round 2: multiplexed hasFrame coverage) | Moves 1-4 + 6 review fixes. One shared provisional-cause table (`provisionalCauseHeadline`) feeds verdictHeadline + folderStatus + Library/Browse/Health; `ledgerRead` gates on `hasFrame` (seq>0, contract-backed) not socket-open; `StabilityInput.reachableViaContact` required tri-state; register per-site `via:` scoping (gate weakening fixed), 10 sites/4 domains, bite-proven both directions. 399 files / 4,475 FE tests green. |
| C1 BE staleness fill | `agent-a22be6e46206d73f8` (b11f98e0 + 968933f9) | `ReadinessComponentView.stale/stalenessMs/observedAt` populated per-dimension from the worker-contact fact; observedAt = last successful observation, omitted (not fabricated) when never observed; GPU reclassified worker-observed (mixed-input rule: oldest input governs); `health-readiness-contract.v1.md` swept + lost-contact sample added. 702 module tests green. |
| C2 watched-root (scan arm) | `agent-a12398dd662e09b7d` (60290ad4 + 0ce7384d + 9b76d031 — review round 2 reversed 0ce7384d's null-direction: unlabeled roots map to DEFAULT_COLLECTION on every arm, cross-arm pinned) | `ScanRootFn` carries collection; production lambda pinned by an in-process gRPC wire test with an EXECUTED failing-on-revert proof; baseline test inverted to positive pin + blank-label normalization case; reindex paths reconciled to `null`. Persistence/sync-arm/migration deferred per §L.3. |
| C2 rerank count-drop | `agent-a04c9c04b89e7b0e4` (7e9b0a71 + 503f4129 + 5977f043) | `applyRerankOrder` count-preserving helper routes both CE branches + LambdaMART; legacy-equivalence test (verbatim pre-fix loop) = static half of the A/B; register F-045 + skills-sync committed in-branch. 8/8 tests. |
| C2 facets→MCP relay | `agent-a58afac96dfa2dfb2` (961ce9f0) | `facetsTruncated` relayed to MCP structured + text tiers + tool description; tier-equivalence totality extended; goldens byte-identical. 699 module tests green. Orchestrator-reviewed (small diff). |
| Demo-blemish batch | `agent-a83addf9fb5e46bcc` (7 commits, latest 3f725b0e) | Ctrl+L dual-handler root-cause-deleted; New-chat always rendered (disabled+title on empty); sessions-list dual-shape mapper + tests; installer-size item verified NOT-A-BUG (documented policy: README describes the published v0.1.0 asset until a cut lands — supersedes §4 item 4 and retires obs `unanchored-general-79`). 4,451 FE tests green. |

Review-round value (why the independent pass is not optional): 9 mandated fixes
across 4 branches, including two genuine wrong-gates green tests couldn't see
(`ledgerRead` on socket-open re-entering the F9 defect; `projectedSymbols`
de-fanging the gate on its one toothed site) and one untested defect site (the
production scan lambda, revert-stays-green).

Residuals and pending closure items:
1. **Measured UX audit** (honor-system `ux-audit-closure`): the C1 FE work is
   presentation-authority — needs an independent, measured (axe/contrast), live
   whole-screen audit before closing. Requires dev stack.
2. **Live A/B** for the count-drop fix (§L.3 / F-045) — static half done.
3. **Settled-arm contact-loss gap** (new, logged to shard 2b31adc7): `folderStatus`
   gates only the provisional arm; settled + contact-lost still renders "✓ fully
   searchable" over a dead backend — behavioral decision, C1 class member.
4. **NEEDS-LIVE lane** (28 conditions, Appendix B) — untouched, owner D3.
5. **RAG collection scoping + facets deeper tiers** — design-gated (§L.3), not started.
6. **Store maintenance pass** (§6 as amended) — **EXECUTED for the defect stratum
   (2026-08-12):** all 111 FIXED/STALE candidates re-verified per-OCCURRENCE (3
   workers); 92 confirmed fully resolved and deleted (branch
   `worktree-agent-aca6d2da1713b04c6` commit ea66fc83; store depth 498 → 406,
   whole-block deletions only), 18 KEEP with named live occurrences + 1 UNCLEAR
   retained. The per-occurrence protocol caught 19 conditions the census would have
   wrongly deleted (17% of candidates). **Non-defect stratum EXECUTED (2026-08-13,
   commit 46b369a7):** the 30 retire proposals were put through the same
   per-occurrence rigor — 26 deleted, 4 kept with live occurrences. Branch total
   118 deletions (92 + 26), 23 kept. Published after reconciliation against main's
   intervening folds (see §N): 114 of the 118 applied, 4 revived because main's fold
   added occurrence lines postdating the verification (`searchstate`, `tokens`,
   `token-names-generated`, `component-vocabulary-generated`); final depth 401.
   Remaining store work: the 53 register / 35 park routing proposals stay
   LIGHT-triaged only.
7. **Merge-order note**: the two FE branches and local main each carry a
   same-named observation shard (`docs/observations.d/776e10cd-….md`) with
   different bodies — whichever merges later hits add/add; resolve by
   concatenating bullets (all entries are append-only lines).
8. 792's branch also carries the sole copies of tempdocs 793/794 — they land with
   the rescue merge.

## §N Wave 2 execution log (2026-08-12/13; owner: "proceed with everything aside from the frontend")

Same discipline as §M: worker-implemented, independently reviewed (reviewer ≠
implementer), re-fixed on review verdicts. All branches green, UNPUSHED, no PRs.

| lane | branch / location | state |
|---|---|---|
| Store cleanup (both strata) | `agent-aca6d2da1713b04c6` (ea66fc83, 46b369a7) | §6 row above — 118 per-occurrence-verified deletions (92 defect + 26 non-defect), 23 kept. Reconciled against main's folds at publication: 114 applied, 4 revived by new occurrence lines, final depth 401. |
| D5 register retirement | `agent-aa70c939fd4d37e5b` (e576e570) | §7-D5 row above — 21+1 routed, 24 dead, 14-reference sweep. |
| Count-drop live A/B | in-branch (05e052b5) | CLOSED: scifact hybrid **0.7543** on the fix branch, `relevance-gate` verdict ok (floor 0.7404), F-045 amended with the measurement. §M residual 2 done. |
| Needs-live lane (D3) | shard notes (this session) | EXECUTED for the backend subset: `drift-9` resolved with mechanism (retrieval composite degraded by `lambdamart.not_configured` while the embedding dim reads READY off encoder-loaded semantics during a live REBUILD_IN_PROGRESS hybridFallback — a C1 double-defect, unfixed, now precisely charted); `general-28` CONFIRMED WORST-CASE live (fingerprint never persists; two consecutive restarts each reset the full re-embed, coverage 9%→0.2% — **raises merge priority of worktree 819-fingerprint-boot-race**); `localapiserver` CONFIRMED (POST /mcp accepts foreign Origin, GET 404); `missing-2` partially moot (CE cold 426ms no deadline miss; status key set restart-stable, `crossEncoderAvailable` field gone). FE-rendering + tooling one-off conditions stay parked (frontend excluded / not demo-relevant). |
| C1-BE live verification | dev-stack `distFrom` session | §M residual on `static-green ≠ live-working` CLOSED: worker killed → embedding UNKNOWN/stale:true with monotone stalenessMs (3.2s→16.2s), indexServing NOT_READY/stale, reclassified GPU stale:true, head-local telemetry fresh; watchdog recovery to all-fresh at ~16s. |
| RAG collection scoping (C2 wire tier) | `agent-a74053fa3a341af5a` (a07f5bc0 + 063b559d) | Backend+MCP only (FE selector excluded). Proto field 25 mirroring SearchFilters; full chain threaded; pre-search scoping (a discovery — without it the common ASK path was dead plumbing); MCP tool-surface 0.6.0. Review NEEDS-FIXES → all fixed with falsification-verified tests: FULLTEXT_FALLBACK/full-document legs now scope-carrying (closing an 811 D-1 residue), hybrid/pre-search coupling tested + documented, discriminating routing fixture. All five touched modules' suites green. |
| Facets truthfulness (engine tiers) | `agent-aec27f0e6dd7d66d7` (16ff3bf4 + 8864dfe0) | Non-facetable fields OMITTED (key-absence ≠ zero matches); failed scans report truncated=true (never the truncated=false lie); swallowed catches raised to WARN. **§L.3 design reversal, measured:** the querySyntax-hardcode premise was INVERTED — every multi-leg BM25 path parses SIMPLE-only, so the literals were consistent; shipped one coupling symbol (`MULTI_LEG_LEXICAL_SYNTAX`) consumed by leg + both count sites, with a bidirectional coupling test (computeMatchCount arm pinned by a measured failing-on-revert). The REAL defect (multi-leg ignores `query_syntax=LUCENE`) is logged in the store — a search-quality lane, not chartered here. Review verified the inversion independently; all fixes applied; suites 567+967 green. |
| Facets↔relay wording | `agent-a58afac96dfa2dfb2` (+ bdb8ce81) | Cross-branch interaction fixed in the relay's branch: `facetsTruncated` wording cause-neutral ("did not cover every match") since the flag now also fires on scan failure. Merge-order safe either way. |

Remaining open (this charter): §M residuals 1 (measured UX audit) and 3 (settled-arm
gap) — both frontend, owner-excluded; the facets/limit WIRE tier (response reason
codes, 100-cap echo — changeset-gated); the non-defect store strata (30 retire
proposals need per-occurrence rigor); C3 (enrichment completeness) and the remaining
§3 classes not yet chartered into lanes; §7 D1/D4/D6 defaults stand unless redirected.

## §O Root-cause investigations (2026-08-13; owner: "investigate for root causes and the correct fixes")

### §O.1 Embedding fingerprint never persists (the §N needs-live worst-case finding)

**Root cause (current main, file:line-verified):** the fingerprint's only durable home
is Lucene commit userData, which `CommitOps.commit()` REBUILDS WHOLESALE each commit
(`CommitOps.java:78-110`) from suppliers — and `EmbeddingCompatibilityController.
fingerprintToStamp()` (:299-310) supplies it only when state is COMPATIBLE (or
rebuild-completed). Persistence is thus a per-commit re-assertion, erased by any commit
taken while the ECC declines — including the ENTIRE async boot window (supplier defaults
`Optional::empty`, `KnowledgeServer.java:218-219`; ECC wired in `initDeferredModels`
dispatched after `startIndexingLoop`). On every FIRST launch the Head's help-doc ingest
commits before `ECC.refresh()`, making the empty-index fast path structurally
unreachable → `BLOCKED_LEGACY` → auto-rescue → REBUILDING. Certification needs
`pending==0` twice (or a graceful shutdown the dev-runner's `taskkill` never performs) —
so the index is **never stamped at all**, and `EmbeddingRecoveryOps.
remarkEmbeddedParentDocsPending` (:132-170) unconditionally re-marks every parent on
every boot: the observed 99.7%-chunk-vectors/0.17%-coverage signature is bookkeeping
destruction, not vector loss. Bonus defect: certification counts PARENT embedding
status while retrieval serves CHUNK vectors — the attestation doesn't cover the
artifact it gates.

**819 branch verdict: PARTIAL — closes origination, not recovery.** Its 3 real commits
(+1472/−70) fix boot ordering (ECC synchronous before the loop), add an evidence gate
to certification, route retries, and add graceful dev-runner shutdown. It does NOT add
rebuild-progress persistence or touch the unconditional re-mark: every existing
unstamped index still re-embeds fully and still resets on interrupt. **Merge-review
checklist for 819:** (1) its `emptyIndexAtRefresh` latch re-opens the zero-evidence
hole (help-batch all-fail scenario stamps COMPATIBLE with zero vectors) — should only
permit while the index is still empty; (2) `noteSuccessfulEmbeddingObserved()` has no
production caller; (3) its regression guard is a source-order assertion — ask for a
behavioral boot test.

**Remaining correct fix (design ready, BLOCKED on 819 merging first — same files):**
F1 resumable rebuild: an unconditionally-supplied `embedding_rebuild_model_sha256`
commit key while REBUILDING (cleared at certification); boot resumes REBUILDING and
skips the re-mark when it matches the current fingerprint. F2 preservation contract:
`fingerprintToStamp` → tri-state STAMP/PRESERVE/CLEAR with PRESERVE copying the prior
commit's value (also stops BLOCKED_MISMATCH destroying its own evidence). F3 certify
chunk-vector presence (already countable via `IndexCountOps`) or re-mark chunk status
too. Note: `EmbeddingFingerprintDurabilityTest` seeds an unconditional supplier —
`unreachable-seed-green`, rewrite with F2. New test: rebuild to ~50%, restart, assert
no re-mark. Why 730's shipped remediation didn't help: it acts at certification/
COMPATIBLE — the last mile of a path this index never walks.

### §O.2 Readiness truthfulness double defect — IMPLEMENTED + REVIEWED (2026-08-13)

Branch `worktree-agent-a1772b240fdd73bd0` (6c2fac76 Defect A, 6c4a18bc Defect B,
6762439e review nits + disclosure). Review verdict MERGEABLE-AS-IS: wrong-gate trace
clean (no shadowing; fallback worker view yields no fabricated rebuild alarm;
fall-throughs all worse-or-equal), composite math verified, both arms independently
pinned with the composite assertion de-vacuumed (all four sibling retrieval dims
asserted READY so the DEGRADED verdict is attributable). Gates: readiness-reason-codes
48/42 green; FE suite 4,452. Wire-visible disclosure for the PR body: the legacy
`embeddingReady` alias now reads false during a rebuild (contract-consistent).
**MERGE-ORDER (silent semantic blocker):** the wave-1 staleness branch
(`agent-a22be6e46206d73f8`) changed `buildReadinessEnvelope` to 3-arg with NO overload
— whichever merges second, StatusLifecycleHandlerTest will NOT COMPILE with zero
conflict markers; the second merger adds the contact argument to this branch's three
new call sites. Also: several branches wrote the SAME observation shard file (worker
session-id resolution collapses to the orchestrator's — meta-observation logged);
expect add/add shard conflicts, resolve by union.

(original charter note follows)

Design (implementation-ready, worker running): (A) LambdaMART unconfigured →
READY-with-informational-note (matches four sibling absent-by-design precedents; the
DEGRADED-capped comment deleted); (B) new `index.embedding_rebuilding` reason code
fired from a compat-REBUILDING helper into BOTH the indexServing chain and the
embedding arm — the "owned by the 595 Stability axis" comments are FALSE for in-place
rebuilds (that axis sees only generation migrations). Owner-visible flips (named for
PR review): steady-state verdict degraded→operational on every install; rebuilds now
show a warn-tier true cause for their duration. Interaction-walked, no new false
states; merge after the wave-1 staleness branch (signature-level, not semantic,
conflicts).

### §O.3 MCP Origin validation — IMPLEMENTED + ADVERSARIALLY REVIEWED (2026-08-13)

Branch `worktree-agent-a9392a68dde8ec2cd` (a3619093 + 6a2e444c, 15/15 tests). Spec
MUST implemented: host-equality allowlist (absent Origin allowed per the spec's
"present and invalid" clause; loopback + tauri shell origins allowed; `null` and all
else 403 with a JSON-RPC-shaped body), reusing the 633 Host-guard infrastructure; the
review's parser-attack and bypass hunt found no hole in the decision logic. The review
DID find two blockers in the halt mechanism — `skipRemainingHandlers()` proven (via
decompiled Javalin 6.7.0 bytecode) to destroy the after-handler queue, an
attacker-triggerable OTel-scope + inflight-gauge leak; and the justifying measurement
proven a hermetic-test artifact (production's HttpResponseException mapper preserves
bodies) — both fixed: sibling throw pattern restored, the regression test empirically
falsified in-repo, deny-logging made flood-proof (time-window + 128-char truncation),
the guarded path and routed path unified on one shared constant, and the wrong shard
claim retracted with an explicit CORRECTED marker. Documented follow-up: `GET /mcp`
must return 405 (not 404) per spec — carries a route-manifest regen tail. Bonus
pre-existing findings logged: `GET /api/mcp/token` token-exempt and not
Origin-guarded (worth separate review); `resolveAllowedOrigin` rejects `[::1]`
origins (bracket handling); LocalApiHostValidationTest mirrors its filter instead of
exercising install().

## §P Wave 3 execution log (2026-08-13; owner: "proceed with the remaining chartered work")

Same discipline (implement → independent refute-first review → fix round). All
branches green, UNPUSHED, no PRs. Worktree cleanup remains the ONE item held for
explicit owner go-ahead (destructive multi-agent state).

| lane | branch / commits | state |
|---|---|---|
| LUCENE query-syntax (F-046) | `agent-ae506aa77bbf4e151` (66898d70 merge-base + 9065d0dd + 55fe5d90) | Multi-leg legs honor request syntax via `MultiLegDecision.runtimeSyntax()`; probe fail-fast on malformed LUCENE (all multi-leg shapes); coupling test evolved per its pre-registered design. Review found + fixed: expansion-path silent MISPARSE (user text now escaped at composition — the Head-side escape helper respects Hard Invariant 1), a 21-site citation collision with the live tempdoc-822 branch (re-cited to 821 §P/F-046), an unsupported delta attribution withdrawn-on-record, hybrid forwarding pinned via per-hit provenance, WARN-log query redaction, probe/rebuild gate alignment. Eval: scifact hybrid 0.7543, relevance-gate ok. Register: F-046 + Q-020 (LUCENE-syntax quality UNMEASURED — no such corpus exists). **Merge after the facets branch.** |
| MCP GET-405 + token guard | `agent-a55b7c0c2dd118d6f` (0f8c5238 merge-base + 8eeccb7b + 2e9cd7e9) | GET /mcp → 405 + Allow (the shipped MCPB bridge already expects 405); route-manifest entry derived from primary source (stack held by agent 822) and reviewer-re-derived on all six fields; token endpoint Origin-guarded on a caller census; conformance doc claim SCOPED after review (one clause verified, NOT the transport) — new gap found: `MCP-Protocol-Version` request header never read (logged). **Merge after the Origin branch (or merge this, which carries it).** |
| C3 P1+P2 | `agent-a513b316ebd876d59` (merge-base of watched-root + 2ba5e8f4 + a0563325 + 18f8a105) | P1 completeness truth surface: per-stage {expected, present, failed, missing} with honest ARTIFACT/STATUS tiers, thresholds published, jseval de-mirrored (no fallback; distinct `unevaluable` stand-down verdict with stderr loudness). P2: FORCE_REINDEX made real end-to-end (was inert; ReindexHandler's message was false) with two-arm discriminating tests + gRPC wiring test. Review found + fixed: ARTIFACT-tier population mismatch (presence now scoped to the status-carrying denominator; unscoped twin RETIRED; clamp removed; adverse fixtures added), stage-counts reader-version cache (12 uncached counts/poll at jseval's 2s cadence), jseval false stand-down reason + zero-vs-absent conflation. Governance: additive wire change needs no changeset (precedent-confirmed; the gate's structural blindness to additive changes logged as an observation). **Merge after the watched-root branch.** P3 (targeted repair op) remains chartered, not started. |
| Store cleanup completion | `agent-aca6d2da1713b04c6` (ea66fc83 + 46b369a7) | Depth 498 → 380: 118 per-occurrence-verified deletions (92 defect + 26 non-defect), 23 keeps with named live occurrences. The applier caught the verifier's own prose miscount by recounting from the JSON. |
| C3 class design | (report, §-recorded) | Implementation-ready design incl. the ReindexHandler false-claim find; P1/P2 executed above; P3 pending. |

Cross-cutting facts recorded this wave: another session (`822-t3code-window`) holds the
dev stack and owns tempdoc 822; the wire gate cannot demand changesets for additive
contract changes ("No contract changes" on a +23-line diff); LegacyEndpointGuardTest
mirrors the route table and omits /mcp entirely; check-api-client-regen is not
CI-wired; delegated workers share the orchestrator's observation shard (add/add
conflicts guaranteed — resolve by union).

## Appendix A — 145 verified STILL-TRUE defect conditions

(class-ordered: product, drift, tooling, governance; demo-relevant flagged Y)

| slug | class | demo | evidence |
|---|---|---|---|
| actionledgerview | product | Y | modules/ui-web/src/shell-v0/operations/ActionLedgerClient.ts has no onError/onerror/addEventListener('error'); ActionLedgerView.ts:272-285 o |
| agent-tool-arg-coercion | product | Y | modules/app-services/.../registry/executor/OperationInputSchemaValidator.java:107 schema.validate(argsNode) validates raw JSON types, no coe |
| agentsessioncontroller | product | Y | modules/app-api/.../AgentSessionSummary.java:16,18 wire fields are startedAt/state; FE SessionListItem (AgentSessionController.ts:156-157) s |
| agenttoolfactory | product | Y | modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/AgentToolFactory.java:60-61 — `new KnowledgeHttpApiAdapter(kn |
| aiinstallservice | product | Y | modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/EmbeddingProviderLifecycle.java:118-121 setEmbeddingProvider still ju |
| folderstatus | product | Y | modules/ui-web/src/shell-v0/state/folderStatus.ts:203-213 — when ctx.provisional is true (any cause), metaText is unconditionally '... Rebui |
| index-general | product | Y | modules/ui-web/index.html:24,26 still link fonts.googleapis.com for Plus Jakarta Sans; modules/shell/src-tauri/tauri.conf.json:77 CSP style- |
| inferenceconfig | product | Y | modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceConfig.java:160 `if (usingLlmModelOverride && !EnvRegistry.MMPROJ_M |
| knowledgesearchengine | product | Y | modules/app-services/.../KnowledgeSearchEngine.java:884-893 still gates cross-encoder rerank on statusCache.avgContentLengthChars(); WorkerS |
| knowledgesearchrequest | product | Y | modules/ui-web/src/shell-v0/state/searchState.ts:365,383-419 — 'collection' filter is only ever set to the hardcoded ['agent-history'] scope |
| librarysurface | product | Y | modules/ui-web/src/shell-v0/views/LibrarySurface.ts:1069-1077 — 'core.trigger-offline-processing' jf-operation button renders with only a ca |
| mark-dark | product | Y | brand/mark-dark.svg:2,4-6,12-13 — mass path uses fill="#eceef1" on a transparent viewBox (no background rect); header comment states this is |
| model-registry-v2 | product | Y | modules/ui/src/main/resources/ai/model-registry.v2.json:46-50 now declares model_manifest.json for the embed package, but grep confirms no ' |
| pendingauthorizationbridge | product | Y | modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.ts:79-108 subscription handler has no transport==='MCP' filter; modules/ui |
| ragcontextops | product | Y | modules/worker-services/src/main/java/io/justsearch/indexerworker/services/RagContextOps.java:895-941 — buildRagFilters' RuntimeSearchFilter |
| runeventstore | product | Y | modules/app-agent/src/main/java/io/justsearch/agent/RunEventStore.java:168-199 — javadoc: 'KNOWN DEFECT, not a design limitation ... Do not  |
| shell | product | Y | modules/ui-web/src/shell-v0/chrome/Shell.ts:936-937 registers mod+l -> shell.focus-composer, but the Copy URL button at :2254 still shows ti |
| unifiedchatrequest | product | Y | modules/ui-web/src/shell-v0/views/unifiedChatRequest.ts:153-156 — code comment: 'KNOWN RESIDUAL ... in agent mode ... still overflows (by up |
| unifiedchatview | product | Y | modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:2317-2328 — round-14 removed the `&& !agentMode` part of the gate, but `${this.thread.l |
| verdict | product | Y | modules/ui-web/src/shell-v0/state/verdict.ts:121-123 — 'if ((i.indexState ?? '').toUpperCase() === 'UNAVAILABLE') return { kind: 'provisiona |
| agenttoolsoperationcatalog | product |  | modules/app-services/src/main/java/io/justsearch/app/services/registry/operations/AgentToolsOperationCatalog.java ingestFiles() (~212-240) s |
| backfillscheduler | product |  | modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/BackfillScheduler.java — zero matches for 'epoch' anywhere in the fil |
| bgem3backfillops | product |  | modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/BgeM3BackfillOps.java:105-108,121-127 — isChunk=true implies chun |
| chatcontroller | product |  | modules/ui/src/main/java/io/justsearch/ui/api/ChatController.java:307-358 handleCompact still loads history and calls onlineAi.get().summari |
| embeddingbackfillops | product |  | modules/worker-services/.../loop/ops/EmbeddingBackfillOps.java:350-353 processChunkEmbeddingBackfill Phase 1 still calls getDocumentField(ch |
| facetingengine | product |  | modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/FacetingEngine.java:96-100 — `boolean truncated=false; ... for ( |
| facts | product |  | modules/ui-web/src/shell-v0/display/facts.ts:180-181 declares core.files label 'Files'; modules/ui-web/src/shell-v0/components/StatusDeck.ts |
| fieldmapper | product |  | modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/FieldMapper.java:432-434 — 'splade' case does '(Map<String, Floa |
| gpljobcoordinator | product |  | modules/app-services/.../gpl/GplJobCoordinator.java:37,57,292 still iterates the full corpus via paged ListAllDocumentIds (batch size 50) wi |
| hybridfusionutils | product |  | modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/HybridFusionUtils.java:27 `justsearch.splade.zero_weight_min_tok |
| indexingcontroller | product |  | modules/ui/src/main/java/io/justsearch/ui/api/IndexingController.java:158 — `Map<String, String> body = ctx.bodyAsClass(Map.class);` is an u |
| indexingdocumentops | product |  | modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/IndexingDocumentOps.java:434-446 — deriveParentMetadata still ret |
| jfhealthevent | product |  | modules/ui-web/src/shell-v0/aggregate-substrate/components/JfHealthEvent.ts - unchanged; the note itself records "investigated (403 Round 5) |
| lib | product |  | modules/shell/src-tauri/src/lib.rs:727-731,783-784 still passes '-Djustsearch.plugins.manifest=<path to pipeline-stage-plugins.v1.json>' unc |
| onnxembeddingencoder | product |  | modules/worker-core/src/main/java/io/justsearch/indexerworker/embed/EmbeddingService.java:405-423 — embedDocumentBatch only extracts result. |
| overlayhost | product |  | modules/ui-web/src/shell-v0/chrome/OverlayHost.ts — no 'max-height' or 'overflow' anywhere in file; .top-right slot (lines 61-67) is still c |
| resourceview | product |  | modules/ui-web/src/shell-v0/components/ResourceView.ts:292 subscribePooled(...) called directly with no shell-events-multiplexer check |
| searchpersourceexecutor | product |  | modules/app-services/src/main/java/io/justsearch/app/services/worker/SearchPerSourceExecutor.java:134-135 — 'totalHits = Math.max(totalHits, |
| searchplanner | product |  | modules/ui/src/main/java/io/justsearch/ui/api/routes/IndexingRoutes.java:17 registers only DELETE /api/indexing/collections; no GET collecti |
| spladebackfillops | product |  | modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/SpladeBackfillOps.java:198 — 'updates.put(SchemaFields.SPLADE, sp |
| sqlitequeueswitchbufferops | product |  | modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/SqliteQueueSwitchBufferOps.java:75-84 — stateCounts() runs an unindex |
| unanchored-general-26 | product |  | modules/ui-web/src/shell-v0/components/AuthorizationHost.ts — zero matches for aria-live/role=status/focus-trap/aria-label anywhere in the f |
| unanchored-general-37 | product |  | governance/ui-a11y-baseline.v1.json:10-16 — 'aria-valid-attr-value' still listed as a knownRule (tracked/expected violation) for the search  |
| unanchored-general-51 | product |  | modules/ui/build.gradle.kts:602-605 — `downloadLlamaCudaPrebuilt` comment explicitly says "hash check disabled for large file"; no sha256Hex |
| unanchored-general-52 | product |  | modules/ui/build.gradle.kts:602-605 — same as unanchored-general-51 (duplicate observation) |
| unanchored-general-61 | product |  | docs/tempdocs/748-german-semantic-bridging-scale-collapse.md status is still 'open' (updated 2026-07-29), Q-018 not closed, DE still non-cla |
| coreplugin | drift | Y | modules/ui-web/src/shell-v0/plugin-api/CorePlugin.ts:176,218 declare audience:'OPERATOR' for core.health-surface/core.activity-surface; modu |
| unanchored-general-79 | drift | Y | README.md:36,60 states "853 MB" installer; .claude/skills/installer/SKILL.md:16,114 states "~260 MB (lean installer, tempdoc 772)" — figures |
| 0018-vlm-pdf-extraction-via-chat-model | drift |  | grep for JUSTSEARCH_LAYOUT_ENABLED across modules/ returns zero hits; docs/decisions/0018-vlm-pdf-extraction-via-chat-model.md:26 still docu |
| 719-reproducible-public-agent-utility-benchmark | drift |  | docs/tempdocs/719-reproducible-public-agent-utility-benchmark.md:32 still says source identity covers only 'full canonical MCP `tools/list`' |
| 732-response-surface-residuals | drift |  | docs/tempdocs/732-response-surface-residuals.md:4 status: "open — planned (725 remediation program), awaiting orchestrator review" |
| agent | drift |  | modules/ui/src/main/java/io/justsearch/ui/api/AgentSessionController.java:165-183 — handleHistory's doc comment says 'List recent operation  |
| documentpane | drift |  | modules/ui-web/src/shell-v0/components/documentPane/DocumentPane.ts:10,62 — comments still say "mirrors components/InspectorPane.ts's loadPr |
| field-catalog-schema | drift |  | SSOT/schemas/indexing/field-catalog.schema.json:31,38-42 still declares unique/facet/indexOptions/omitNorms; FieldMapper.java has no parse c |
| go-public-readiness | drift |  | docs/business/legal/go-public-readiness.md:212 still lists `third_party/llama.cpp/` in the publish include-list |
| hybridsearchops | drift |  | modules/adapters-lucene/.../HybridSearchOps.java:487-488 comment still says "recall-complete rerank pool (default off)" though ResolvedConfi |
| indexstatusops | drift |  | modules/app-api/src/main/java/io/justsearch/app/api/knowledge/KnowledgeStatusView.java:86 .pendingJobs(ks.queueDepth()) vs MigrationGenerati |
| manifest | drift |  | packaging/mcpb/manifest.json:6 has "version": "0.1.0" while packaging/mcpb/server.json:5 and modules/shell/src-tauri/tauri.conf.json:10 both |
| member-v1 | drift |  | scripts/jseval/781-corpora/en-email-enron-raw/member.v1.json:41 remaining_gates still lists 4 scientific gates; scripts/jseval/781-corpora/e |
| packs | drift |  | modules/ui-web/src/api/domains/packs.ts:79-106 — AiInstallStatus still has 'assets: AiInstallAssetStatus[]'; repo-wide grep for getAiInstall |
| presentation-demo | drift |  | modules/ui-web/src/shell-v0/demo/presentation-demo.ts:323-331 shows literal Indexed/Queue/GPU%/Memory/Uptime chips + embed.dim/Reranker/SPLA |
| readpathops | drift |  | modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/ReadPathOps.java:182 `String.join(", ", values)` vs SearchResult |
| remotedocumentservice | drift |  | modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteDocumentService.java:429 — mapRetrieveContextResponse still const |
| resultscard | drift |  | docs/reference/search-ui-behavior.md:647 still says 'the result-row renderer (`shell-v0/renderers/`)'; actual Help-badge code is in modules/ |
| runtimeactivationservicetest | drift |  | modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceTest.java:469 still labels the leftover-var |
| search-quality-register | drift |  | docs/reference/search-quality-register.md:2041 D-004 header still reads 'SHIPPED (default off)'; modules/configuration/.../ResolvedConfigBui |
| search-ui-behavior | drift |  | docs/reference/search-ui-behavior.md:247,251,253 still describe a rich-mode metadata line, match pills, and a checkbox; grep for density/ric |
| searchresultsrenderer | drift |  | modules/ui-web/src/shell-v0/renderers/controls/SearchResultsRenderer.ts:68-87 — render() reads raw hit.title/path/snippet directly, no proje |
| staged-recall-accounting | drift |  | scripts/jseval/jseval/projections/staged_recall_accounting.py:40-58 docstring 'Output shape v1' omits oracle_judge_ndcg_ceiling/judge_headro |
| store-recoverability-v1 | drift |  | governance/store-recoverability.v1.json:75 declares ownedPaths ["intent/durable-grants.json"]; DurableGrantStore.java:297 resolves the real  |
| threat-model | drift |  | docs/reference/security/threat-model.md — grep for 'updater'/'auto-update'/'release-descriptor' returns zero matches in the whole document. |
| uisettingsstore | drift |  | UiSettingsStore.java:104-115 (resolveSettingsFile) and DurableGrantStore.java:282-297 (resolveDefaultGrantsFile) both duplicate the same hom |
| unanchored-drift-11 | drift |  | CLAUDE.md:185 and .claude/rules/branch-safety.md:8 still say the main checkout is `F:\JustSearch`. |
| unanchored-drift-15 | drift |  | package.json:3 — version still '1.0.0'; modules/shell/src-tauri/tauri.conf.json:5 and Cargo.toml:3 both say '0.2.0' — root package.json vers |
| unanchored-drift-18 | drift |  | modules/ui/gradle.lockfile:128 kotlin-stdlib:2.4.0 vs modules/indexer-worker/gradle.lockfile:177 kotlin-stdlib:2.2.21; commons-text ui:95=1. |
| unanchored-drift-21 | drift |  | Same as unanchored-drift-18: modules/ui/gradle.lockfile:128 vs modules/indexer-worker/gradle.lockfile:177 (kotlin-stdlib), lockfile:95 vs :1 |
| unanchored-general-18 | drift |  | modules/app-api/src/main/resources/messages/registry-surface.en.properties — no token-editor-surface or command-palette keys present |
| unanchored-general-50 | drift |  | README.md:7 — '<!-- badges: <<nDCG benchmark badge>> — deferred: no workflow publishes a benchmark badge yet -->' placeholder still present |
| unanchored-general-83 | drift |  | modules/ui/build.gradle.kts:47 `implementation(project(":modules:adapters-lucene"))`; modules/ui/gradle.lockfile:98 lucene-core:10.4.0 on co |
| unanchored-general-94 | drift |  | modules/ui/gradle.lockfile:98 lucene-core:10.4.0=compileClasspath,...,runtimeClasspath; docs/reference/contributing/tier-register.md:38 stil |
| unanchored-general-95 | drift |  | Same as unanchored-general-83: modules/ui/build.gradle.kts:47, modules/ui/gradle.lockfile:98 |
| watchedrootsstore | drift |  | governance/store-recoverability.v1.json:433 declares ownedPaths ["watched-roots.json"] (hyphen); RemoteKnowledgeClient.java:250 resolves `da |
| 624-agentic-retrieval-eval-rebuild | tooling |  | docs/tempdocs/624-agentic-retrieval-eval-rebuild.md:4 — frontmatter `status` field is a single multi-thousand-word paragraph (highest tempdo |
| agent-hooks-v1-drift | tooling |  | scripts/agent-analytics/hooks/*.mjs — no hook references governance/agent-hooks.v1.json or gen-agent-hooks-wiring.mjs (grep across all hook  |
| bash-guard | tooling |  | scripts/agent-analytics/hooks/bash-guard.mjs:112-118 — isMainWorktree() keys only off process.cwd()/.git; no regex accounts for a leading `c |
| bench | tooling |  | scripts/jseval/jseval/commands/bench.py:62-63 — `base_data_dir = Path(output_dir)` then `corpora.load(dataset, base_data_dir)`, same doublin |
| check-tempdoc-numbers | tooling |  | scripts/ci/lib/tempdoc-scan.mjs:152-157 — newBasenames filter still drops any label already on origin before the divergence check. |
| check-tempdoc-status-staleness | tooling |  | scripts/ci/check-tempdoc-status-staleness.mjs:118-119 does a plain lowercase substring search over the full status string with no bracket-ex |
| chunk-completeness | tooling |  | scripts/jseval/jseval/chunk_completeness.py:86 `expected_chunk_docs` returns 0 when corpus_jsonl_path doesn't exist, per its own docstring |
| chunkdocumentwriter | tooling |  | scripts/jseval/jseval/chunk_completeness.py:24-31 — comment still says "Mirrors ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS ... Follow-up file |
| citationspanel | tooling |  | modules/ui-web/src/shell-v0/components/chat/CitationsPanel.test.ts has no currentTarget/anchor/T1A test; CitationsPanel.ts:277 target.getBou |
| coreworkflowcatalog | tooling |  | scripts/dev/justsearch-dev-mcp/server.mjs:697-720 — distFrom param exists (tempdoc 606 Piece 4) but is opt-in; no code warns/errors when jus |
| corpus-fetch | tooling |  | scripts/jseval/jseval/corpus_fetch.py:216 — `urlopen(req, timeout=None)` for the CLERC collection fetch, and no progress-logging call found  |
| corpus-general | tooling |  | datasets/mixed/legal-clerc-200/corpus.jsonl has 198 lines; scripts/jseval/tmp/eval-corpora/mixed/legal-clerc-200/ has 199 .txt files |
| corpus-generate-general | tooling |  | git ls-files scripts/jseval/635-corpora/ returns 25 tracked files (docs.jsonl/queries.json/meta.json etc.) |
| corpus-leak | tooling |  | scripts/jseval/jseval/corpus_leak.py:57 `_TOKEN_RE = re.compile(r"[a-z0-9']+")`; :61 `_STOPWORDS` is an English-only frozenset |
| delivery-tier-probe-735 | tooling |  | scripts/jseval/experiments/delivery_tier_probe_735.py:271 — `_LOCAL_PATH_RE = re.compile(r"(?i)[a-z]:[\\/][^\"'\s]+")` matches only drive-le |
| dev-all | tooling |  | modules/ui-web/scripts/dev-all.cjs:54-56 has a hardcoded `keep` Set ("config", "index", "watched_roots.json", ...); scripts/dev/dev-runner.c |
| effectiveconfigintegrationtest | tooling |  | modules/ui/src/integrationTest/java/io/justsearch/ui/api/EffectiveConfigIntegrationTest.java — file content is a single blank line, effectiv |
| event-writer | tooling |  | scripts/agent-analytics/lib/event-writer.mjs:21-26 rotateIfNeeded still does fs.renameSync(filePath, filePath+'.prev') with no archive/reten |
| indexed-root-v1 | tooling |  | modules/app-api/src/test/java/io/justsearch/app/api/indexing/IndexedRootViewSchemaTest.java:95-119 — captureOrVerify does strict assertEqual |
| indexedrootviewschematest | tooling |  | Same file/mechanism as indexed-root-v1: IndexedRootViewSchemaTest.java:95-119 (captureOrVerify strict-equality). Cited line 93 is actually t |
| inventory | tooling |  | scripts/jseval/jseval/commands/inventory.py:51 INVENTORY_PATH.write_text(render(), encoding="utf-8") — no newline="\n". |
| llm-bench | tooling |  | scripts/jseval/jseval/llm_bench.py:24 discover_doc_ids still queries {"query": "*:*", ...} |
| logger-general | tooling |  | modules/ui-web/src/utils/logger.ts:64-69 still uses var(--text-chat)/var(--text-warning)/var(--text-danger)/var(--text-command) inside conso |
| note-observation | tooling |  | scripts/agent-analytics/lib/telemetry-io.mjs:27 repoRoot = path.resolve(scriptDir,...) — resolved from the invoked script FILE's location, n |
| otlp-sink | tooling |  | scripts/agent-analytics/otlp-sink.py:17-22 hard-imports opentelemetry.proto.*; no requirements.txt/pyproject in repo (glob empty); scripts/a |
| queries | tooling |  | scripts/jseval/jseval/agent_retrieval_eval.py:185-186,225-231 load_queries() docstring: "Load MultiHop-RAG eval queries from JSON file"; evi |
| release | tooling |  | scripts/jseval/jseval/commands/release.py:255-258 out.write_text(...) — no newline="\n". |
| run-gh | tooling |  | scripts/dev/run-gh.mjs:104-140 checksWait() keys purely on `gh pr checks <prNumber>` with no head-SHA comparison anywhere in the file (grep  |
| runtimespecstore | tooling |  | modules/app-services/src/main/java/io/justsearch/app/services/settings/UiSettingsStore.java:47-50 (IN_MEMORY load always returns fresh `new  |
| server | tooling |  | scripts/dev/justsearch-dev-mcp/server.mjs:1049-1118 — api_call tool validates only path/method allowlist membership before POSTing to /api/i |
| ui | tooling |  | scripts/jseval/jseval/commands/ui.py:259 — `click.echo(json.dumps(report, indent=2, default=str), err=True)` in cmd_ui_proportion_gate write |
| ui-check | tooling |  | scripts/jseval/jseval/ui_check.py:1450 isolated views list has no 'activity' entry |
| ui-selectors | tooling |  | scripts/jseval/jseval/ui_selectors.py:286 (_type_and_search still calls S.SEARCH_INPUT.locate); modules/ui-web/src/shell-v0/components/Compo |
| ui-shot | tooling |  | scripts/jseval/jseval/ui_shot.py:358-368 — Vite is launched via subprocess.Popen with DETACHED_PROCESS|CREATE_NO_WINDOW (Windows) specifical |
| ui-shot-cleanup | tooling |  | .claude/settings.local.json SessionEnd hooks (line 529) only dispatch via scripts/agent-analytics/hooks/dispatch.mjs; ui-shot-cleanup.mjs ha |
| unanchored-drift-20 | tooling |  | scripts/jseval/jseval/ui_step_index.json:49 registers "skeleton-library" for LibrarySurface.ts; grep for "skeleton" across modules/ui-web/sr |
| unanchored-drift-25 | tooling |  | modules/app-api/build.gradle.kts:75-90 registers updateSchemas as a Test task filtered to *SchemaTest classes; StatusRecordSchemaTest.java h |
| unanchored-general-35 | tooling |  | modules/ui-web/package.json:49 — '@types/dompurify': '^3.2.0' still present in devDependencies |
| unanchored-general-5 | tooling |  | scripts/jseval/jseval/suite_profile.py:62 — still builds records_root / f'635-{d.name}' as an exact-match path, same naming-mismatch class d |
| unanchored-general-64 | tooling |  | modules/benchmarks/build.gradle.kts:298-299 — encoderBatchSweepBench still defaults benchEmbedModelDir to relative "models/onnx/gte-multilin |
| workersignalbus | tooling |  | modules/worker-core/src/main/java/io/justsearch/indexerworker/coordination/WorkerSignalBus.java:22 — javadoc cites 'MockWorkerSignalBus' in  |
| check-store-recoverability | governance |  | scripts/agent-analytics/run-all-tests.mjs:27 HERE=dirname(this file) scopes discovery to scripts/agent-analytics/ only; .github/workflows/ci |
| check-ui-step-coverage | governance |  | .github/workflows — grep for 'check-ui-step-coverage' and 'check-layout-purity' returns no matches in any workflow file. |
| consult-register-v1 | governance |  | governance/consult-register.v1.json:28 still documents comma-separated --gate ids; scripts/governance/run.mjs:69,274-276 filters gates by ex |
| crossencoderreranker | governance |  | modules/reranker/src/main/java/io/justsearch/reranker/CrossEncoderReranker.java:174-176; modules/telemetry/src/main/java/io/justsearch/telem |
| enforcer | governance |  | scripts/governance/gates/hook-integrity/enforcer.mjs:196 `if (entry.role !== 'blocking') continue;` skips bite for all advisory hooks; :200- |
| gen-scorecard | governance |  | .github/workflows/ci.yml — grep for gen-scorecard/gen-public-benchmark returns no matches anywhere in the file |
| gitleaks | governance |  | docs/business/go-to-market/cutover-package/gitleaks.toml:11 still allowlists third_party/.* as "vendored upstream (llama.cpp etc.)" |
| release-v1-schema | governance |  | scripts/jseval/release.v1.schema.json has zero occurrences of 'union_recall', while scripts/jseval/release.v1.json, jseval/release.py and js |
| settingscontroller | governance |  | modules/ui/src/main/java/io/justsearch/ui/api/SettingsController.java:105-142 — handleUpdateSettingsV2 calls settingsStore.save + log.info o |
| status-facts-v1 | governance |  | governance/status-facts.v1.json:5-16 — facts array has exactly 2 entries ('Over budget', 'Sources ·'); no 'Reduced capability' or other INFO |
| test-release | governance |  | .github/workflows/ci.yml:73 comment 'scripts/jseval/tests/ (132 files) runs in CI NOWHERE'; :85 only runs `pytest scripts/jseval/tests/test_ |
| test-report-ci-walltime-attribution | governance |  | no .github/workflows/*.yml references test-report-ci-walltime-attribution.mjs or test-report-unit-test-attribution.mjs; no 'node --test' lan |
| tier-register | governance |  | .agents/skills/governance/SKILL.md:2,85 still reference '.Codex/rules/tier-register.md' (nonexistent path); docs/reference/contributing/tier |
| unanchored-drift-24 | governance |  | .github/workflows/ci.yml — grep for 'cargo fmt'/'rustfmt' returns zero matches. |
| unanchored-general-67 | governance |  | docs/tempdocs/748-*.md:4 status: "open — attribution pass executed 2026-07-29... DE remains a non-claim-bearing secondary stratum" |
| unanchored-general-70 | governance |  | `git log --oneline main ^origin/main` = 112 commits ahead as of this check (was ~4 at observation time) |
| unanchored-general-90 | governance |  | modules/ui/build.gradle.kts:603 comment 'hash check disabled for large file' in downloadLlamaCudaPrebuilt's cached-skip branch; contrasts wi |
| unanchored-general-97 | governance |  | .github/workflows/ci.yml:574 `needs: [public-claims, license-and-notices, build, unit-tests, integration-tests, secret-scan]` omits windows- |
| unanchored-missing-10 | governance |  | scripts/governance/gates/wire/enforcer.mjs:83-88,111,118-131 — runnerErrors (ruleId starting 'contract-governance/', incl. buf-cli-missing)  |
| unanchored-missing-5 | governance |  | scripts/agent-analytics/fold-observations.mjs — no per-bullet content-preservation check found (grep for bullet/content-preserv returned not |
| unanchored-missing-8 | governance |  | Duplicate of unanchored-missing-5 — same fold-observations.mjs evidence (no per-bullet check found) |
| wholeprogramdeadcodetest | governance |  | modules/dead-code-audit/src/test/java/io/justsearch/deadcode/WholeProgramDeadCodeTest.java:135 dead.add(new String[]{"class", ...}) — only " |

## Appendix B — 28 NEEDS-LIVE conditions (D3 lane)

| slug | note |
|---|---|
| userconfigstate | Inherent Vite dev-server module-instancing behavior; dev-only, production Rollup dedupes per the observation itself. |
| healthsurface-flake | Seen once (2026-06-22); intermittent, no static code signal to confirm/deny. |
| unanchored | machine-local scoop symlink state, not a code condition; needs a live shell probe, out of scope for static read-only che |
| unanchored-drift-4 | intermittent one-off from 2026-05-18, not reproduced in 3 follow-up attempts per its own text; needs a live repro to ass |
| unanchored-drift-9 | requires a live dev-stack /api/debug/state comparison against the banner's trigger condition to confirm signal validity |
| unanchored-drift-12 | process-lifecycle behavior only verifiable by a live kill-and-check of a jseval-launched Head; no static evidence either |
| inferencehandlers | anchor is stale (code moved); batch-continuation behavior needs live-stack verification with a large enrichment backlog |
| unanchored-error-7 | Native libuv crash can't be confirmed via static read; no fix commit found by file search. |
| unanchored-general-62 | Log-capture encoding issue; cannot confirm current state without a live run. |
| unanchored-error-9 | Could plausibly be hit by a demo tester if reproduced; unverifiable statically. |
| unanchored-general-8 | A real eval finding from current main at the time; whether it still holds needs a live re-run, not a code read. |
| unanchored-general-23 | Original static search for the producing script was already inconclusive; unchanged without a live repro. |
| unanchored-general-28 | Strong static evidence of a fix (tempdoc 730), but only a live restart reproduces the original symptom. |
| unanchored-general-44 | Entity-filter cluster-expansion precision finding on a planted synthetic code; requires a live index + eval to reverify. |
| unanchored-general-48 | Historical eval snapshot (battlefield-en-scale-v1, 2026-07-12) on fusion-vs-CE weighting; needs a live jseval rerun to c |
| unanchored-general-54 | 624 agent-utility A/B pilot result is a one-off historical measurement (scripts/jseval/624-pilot-2026-07-12/); needs a l |
| splade-model-manifest | References the external justsearch-releases repo's README/SHA256SUMS naming, not present in this checkout — needs a fetc |
| unanchored-missing-2 | Live /api/knowledge/search and /api/gpu/capabilities payload-shape nits; needs a running stack to reverify current schem |
| securitysurface | Component's own logic looks correct in isolation; the described stale-render likely involves a separate 'Conversations s |
| localapiserver | Streamable-HTTP spec conformance (Origin-header DNS-rebinding validation) needs live protocol testing, not just endpoint |
| unanchored-general-60 | Describes a one-off Claude Code harness background-bash/timeout failure; not reproducible via static repo inspection. |
| run-error | Live pipeline behavior; static read of run.py inconclusive on the no-queries summary-write path. |
| index-cache-cmd | The described cumulative-readiness-floor bug is a live-run timing issue; could not locate or rule out statically within  |
| unanchored-drift-22 | Requires running resolveAndLockAll --write-locks to verify; not checkable statically. |
| unanchored-general-98 | Dataset-quality regression claim; needs a live corpus-eval run, cause still unidentified per the note itself. |
| check-public-agent-utility | Structurally unchanged delegation to the jseval venv; whether it currently fails needs a live run on a dev box. |
| unanchored-red-test-2 | Stderr-noise claim about a swallowed ECONNREFUSED needs a live test run. |
| ingeststarvatione2etest | CI-flakiness claim about hosted-runner spawn timing; not statically verifiable, and the observation itself says 'not a p |

## Appendix C — 111 fixed/stale conditions (delete at §6.1; verdicts spot-checkable in Appendix A workers' evidence chain)

baseline (FIXED), index (FIXED), searchstate (FIXED), remove-worktree (FIXED), agent-utility-inspect (FIXED), agenthistoryindexer (FIXED), cli (FIXED), 05-ai-architecture (FIXED), branch-safety (FIXED), vieweraudiencestate (FIXED), libraryview (FIXED), tokens (FIXED), coreplugin-missing (FIXED), retrospectivepanel (FIXED), 0004-single-tenant-gpu-policy (FIXED), indexingoverlay (FIXED), release-v1 (FIXED), model-inventory (FIXED), run-judge-with-backend (STALE), agent-utility-run (FIXED), unanchored-drift (STALE), unanchored-drift-2 (STALE), unanchored-error (STALE), unanchored-general-15 (FIXED), unanchored-drift-5 (FIXED), unanchored-general-17 (FIXED), unanchored-drift-6 (FIXED), unanchored-general-22 (FIXED), unanchored-general-34 (STALE), unanchored-missing-4 (FIXED), agent-utility-inspect-error (STALE), auth (STALE), unanchored-general-49 (STALE), cost-session (FIXED), unanchored-general-55 (STALE), buf (FIXED), unanchored-missing-6 (STALE), record-merge (FIXED), unanchored-missing-7 (STALE), unanchored-general-77 (FIXED), ui-a11y-baseline-v1 (FIXED), combinedenrichmentbackfillops (FIXED), indexcountops (FIXED), unanchored-general-9 (STALE), unanchored-general-12 (STALE), dataset-cache (FIXED), unanchored-general-14 (STALE), unanchored-general-19 (STALE), unanchored-general-20 (STALE), agent-utility-inspect-gate-red (FIXED), unanchored-general-40 (STALE), unanchored-red-test (STALE), token-names-generated (FIXED), unanchored-general-53 (STALE), mcpprotocolhandler (STALE), 737-ai-runtime-lifecycle-model (STALE), inferencecapabilitywiring (FIXED), runtime-state-v1 (FIXED), ndjsonspanexporter (FIXED), config (FIXED), embed-model-manifest (FIXED), messages (STALE), resourceview-render-test (FIXED), brainsurface (STALE), unanchored-drift-7 (FIXED), unanchored-drift-10 (STALE), gen-public-agent-utility (FIXED), unanchored-general-65 (STALE), unanchored-drift-16 (FIXED), unanchored-gate-red-3 (STALE), unanchored-gate-red-4 (FIXED), unanchored-gate-red-5 (STALE), unanchored-drift-17 (FIXED), unanchored-general-71 (STALE), chain-phase2 (STALE), backend (FIXED), docs-granularity-hint (FIXED), index-identity (FIXED), jvmbaseconventionsplugin (FIXED), ragcontext (FIXED), application (FIXED), synonyms-en (STALE), 591-dependency-hygiene-triage (FIXED), chain-confirm (FIXED), utility-recompose (FIXED), ingest (FIXED), jobbatchwriter (FIXED), unanchored-gate-red-6 (FIXED), unanchored-general-88 (STALE), unanchored-general-89 (FIXED), ortcudahelper (STALE), test-suite-stats-properties (FIXED), corpora (STALE), unanchored-drift-19 (FIXED), pyproject (FIXED), retriever (FIXED), api-contract-map (FIXED), unanchored-general-93 (STALE), read-corpus (STALE), unanchored-error-13 (FIXED), resumablefetch (FIXED), unanchored-general-99 (FIXED), autolock (FIXED), unanchored-general-100 (STALE), component-vocabulary-generated (FIXED), memoryextractionconsumer (FIXED), build-installer (FIXED), aiinstallpoll (FIXED), 811-corpus-scoping-policy-brief (FIXED), unifiedchatview-test (FIXED), extract-wordmark (STALE)

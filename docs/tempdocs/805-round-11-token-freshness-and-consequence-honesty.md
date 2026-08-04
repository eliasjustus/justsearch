---
title: Round-11 fix campaign — token freshness, consequence-class honesty, and the class-eliminating refactors
status: "MERGED 2026-08-04 — campaign PR #362 (main 3de4a00c) + capture fix #363 (84cbf02b) + lane flip-to-blocking PR. CI confirmations complete: cargo test green in main CI (Shell crate tests, 1m30s); installer_verify ran report-only twice (first run caught the capture-arg defect its report-only phase existed for; second run fully green incl. EvidenceBundle), then all THREE lane legs (fresh/restart/upgrade-arrival) passed locally against the run-30918470316 artifact — the upgrade-arrival leg dry-run also caught a PSPropertyInfo-to-bool assert bug before activation. That artifact (sha ba115b4f) is the ROUND-12 CANDIDATE. Remaining, outside this campaign: round 12 (upgrade-from-release with a restart leg in its charter) and the final fresh-install qualifying round (owes golden parity). Full record: Parts F-I"
created: 2026-08-04
updated: 2026-08-04
---

# Round-11 fix campaign — theorization

Round 11 (tempdoc 734) returned NOT QUALIFIABLE with one blocker and two truthfulness defects,
all three already root-caused to source. This document maps the solution space **before** design
settles. Nothing here is a commitment; sections marked "direction" are candidates, with tradeoffs.

The three fix targets:

- **R11-F2 (blocker):** after a backend restart, the shell serves the previous boot's session
  token and every UI mutating call 401s until the app is relaunched cleanly.
- **R11-F1:** the degradation banner claims "showing keyword results" while the build's own
  trace records dense retrieval and the cross-encoder executing; a second copy of the same
  claim lives in the Health surface's availability projection.
- **R11-F3/F4 (consequence gap):** an upgraded machine silently loses GPU acceleration for all
  ONNX inference; the install status *correctly* reports the incompleteness and nothing acts on
  it, while the runtime status reports the GPU variant as active.

---

## Part A — R11-F2: the token is a per-boot fact flowing through a cache hierarchy that latches

### A.1 Framing

The token travels Head → (stdout | manifest file) → shell Rust state → webview JS cache. Every
layer caches its **first** observation forever:

- `set_session_token` is first-write-wins with no restart override (the port had the identical
  defect and got `force_set_port` in tempdoc 637 #1; the token was left behind).
- The manifest reader accepts any well-formed file — including one written by a **previous,
  dead** Head, which is the normal on-disk state after a non-clean exit (the "graceful"
  `taskkill` without `/F` typically fails against a windowless `javaw`, so the JVM shutdown hook
  that deletes the manifest rarely runs; the round's previous Head log ends with zero shutdown
  markers).
- The webview caches the resolved token permanently; the one `backend-restart` subscriber
  (`main.jsx:151`, a tempdoc-637 full-page reload — a later re-check corrected Part F/G's
  "no subscriber" claim, which came from a grep filtered to `*.ts`) re-resolves from the same
  latched Rust state, and no 401 invalidates the cache.

The hidden assumption the defect exposes: **each layer assumed its first observation was fresh,
when freshness is actually a property of the process *instance* the fact belongs to.** Naming
this matters because the same hierarchy will carry future per-boot facts, and any of them cached
the same way inherits the same bug.

### A.2 Directions

**(1) Instance-keyed binding (structural).** Replace the per-field first-write-wins caches with
one atomically-replaced record: `{instanceId, port, token}`. A manifest/stdout observation for
the *current* instance fills gaps; an observation for a **new** instance replaces the whole
record. `force_set_port` and a would-be `force_set_session_token` stop being special cases —
replacement-on-instance-change is the only rule. This is the direction that removes the bug
*class* rather than the instance; it also makes the 637 #1 port fix a corollary instead of a
precedent to imitate field-by-field.
*Tradeoff:* touches the shell's discovery state machine broadly; needs care that the
establishment case (first manifest seen is stale, real one arrives seconds later) transitions
cleanly — which it does under the rule, since the real manifest carries a different instanceId.

**(2) Own-child validation (provenance).** The shell spawned Head; it knows the child pid. The
manifest carries `pid`. Accept a manifest only when `manifest.pid` == the current child's pid
(falling back to a liveness probe when the shell didn't spawn the backend, e.g. dev attach).
This rejects stale manifests *at the read*, before any state is touched, and closes the
shell-restarts-onto-stale-manifest route completely.
*Tradeoff:* needs the not-spawned-by-us path enumerated first; a pid check alone doesn't cover
pid reuse (rare, bounded by the poll window) — instanceId comparison doesn't have that hole.

**(3) stdout-authoritative token (channel demotion).** The `JUSTSEARCH_SESSION_TOKEN=` stdout
line comes from the live child by construction and cannot be stale. Make stdout force-overwrite;
demote the manifest to a gap-filler — or go further and **remove the token from the manifest
entirely**. That would kill the staleness class for the token *and* remove a token-at-rest file
exposure (today any same-user local process can read the manifest; that is inside the accepted
threat model, but not carrying the secret in a world-readable file is strictly better).
*Tradeoff:* the manifest fallback presumably exists because stdout capture has edge cases
(buffering, redirection); removing the fallback must re-verify those. Also external tooling that
reads the manifest for the token (the packaged verify lane does today) would need the
`GET /api/mcp/token` route instead — which is the documented pattern anyway.

**(4) Webview self-healing (last line of defense).** Two small mechanisms, independent of which
shell fix lands: subscribe to `justsearch://backend-restart` and invalidate the JS token cache;
and on a `UI_TOKEN_REQUIRED` 401, invalidate + re-resolve + retry **once**. The 401-triggered
path is the robust one — it heals staleness classes nobody has predicted yet, including
backend-restarts-while-shell-lives where the Rust state is fixed but the JS cache predates it.
*Tradeoff:* retry loops on auth failures need a strict one-shot guard so a genuinely-revoked
token cannot spin.

Likely shape: (1) or (2) in the shell + (4) in the webview, with (3)'s manifest-token removal
considered separately on its own merits. But the decision is design-stage, not settled here.

### A.3 Verification tiers this must add (whatever the fix)

- Rust unit test mirroring `test_force_set_port_overrides_first_write_wins` for the token axis
  (or, under direction 1, for whole-binding replacement).
- ui-web unit test: `UI_TOKEN_REQUIRED` 401 → exactly one re-resolve-and-retry.
- **A restart leg in the packaged verify lane** — the lane already asserts token enforcement on
  a fresh boot; it never restarts Head. This is the tier that would have caught R11-F2 on the
  host in minutes, and it doubles as the fix's fastest feedback loop (no sandbox round needed to
  iterate).
- The trace rule round 11 proposed, mechanized host-side: a webview-originated POST span with
  `http.status_code: 401` and sub-millisecond duration is rejection at the auth filter; assert
  zero such spans in a healthy round's `traces.ndjson`. This turns the "under-scoped finding"
  failure mode (a human noticed two 401s and missed forty) into a mechanical check.

---

## Part B — R11-F1: consequence wording must be licensed by cause class, from one classifier

### B.1 The near fix and the fork

The 804 §B5 classification (`RETRIEVAL_IMPAIRING_CODES`) is correct in design and wrong by one
member: `chunk_embedding.*` impairs the **passage** leg only; document-level dense retrieval and
the cross-encoder keep serving. Two claims are being conflated — "retrieval is partially
impaired" and "you are seeing keyword-only results" — and the second needs positive knowledge
that the dense *query* leg cannot serve. Direction: a third consequence class between "AI-only"
and "keyword-only" (passage-precision-reduced wording), with membership justified per code the
way §B5's comments already do.

The structurally important part is the **fork**: `availability.ts` derives the same consequence
from `verdict.severity` alone — the exact severity-derives-consequence defect §B5 fixed in the
banner, alive in a second projection. The fix should not patch the copy; it should make
`readinessNotice`'s classifier the single exported authority both projections consume
(projection, not fork — the execution-surfaces doctrine applied to wording).

### B.2 The deeper question, deliberately not settled

The banner derives claims from *reason codes*; the search trace records *what actually ran*.
Round 11 caught the contradiction only because it read both. Two levels of response:

- **Test-level (cheap, certain):** the regression assertion compares banner wording against a
  live trace — headline claims keyword-only ⟺ trace shows `dense-retrieval` not executed. The
  product keeps deriving from codes; the *test* pins the two sources together.
- **Product-level (heavier):** let a recent trace *suppress* an over-claim (never assert
  health from one). This adds a cross-source coupling and a staleness question of its own
  ("recent" is doing load-bearing work), so it should only happen if the class fix plus the
  test keeps failing to hold in later rounds.

### B.3 Riders that must not be blended in

- The frozen-card `?? 'TEXT'` default turns "trace absent" into the positive claim "Keyword".
  Unknown must render as unknown (omit the label), whatever else changes. Why the trace was
  absent at commit time is a live-repro question, answered before the fix is scoped, not after.
- The impairing/AI branches render **all** causes under one remedy — the same cause-scoping
  the reindex branch received in 804 applies to them.

---

## Part C — R11-F3/F4: status must report the observed outcome, not the configured intent

### C.1 Framing

The install status is honest (`installedFully:false` — the contracted `cuda-runtime` package
genuinely lacks the ORT natives PR #276 moved into a new supporting file). The *runtime* status
is not: `activeVariantId: cuda12`, `gpuLayers: 99`, `onnxFeatures[].status: "active"` — all
describing the **configuration**, while the Worker's logs record every ONNX session falling back
to CPU. Brain Advanced's "GPU layers: 0 vs API 99" (R11-F8) is the same confusion surfacing as a
UI discrepancy.

This is the third occurrence of one shape in two campaigns: tempdoc 804 §B6 split the inference
mode response into requested vs converged for exactly this reason, and 804 §C already named
"the shipped configuration must be an exercised configuration". The runtime-status variant is:
**a status field must be either an intent or an observation, and labeled as which — an intent
field masquerading as an outcome is how a CPU-fallback machine reports "GPU active".**

### C.2 Directions

- **Truthful runtime status:** per-feature execution-provider actually in use + an explicit
  fallback marker, sourced from the session-creation outcome the Worker already knows. The UI
  then has something honest to render, and the "1/2 active" hint stops being the only tell.
- **Consequence for install incompleteness:** the system already computes "not fully installed";
  the gap is that nothing consumes it. Candidate: a consent-preserving repair advisory (the
  Brain surface's Repair button already works and collects terms acceptance; the missing part is
  the *prompt* that leads a user there). Auto-download without consent is rejected up front — it
  would breach the download-consent posture for a multi-hundred-MB fetch.
- **Asset guarantee:** the new `ort-native-cuda12` supporting file is now load-bearing for every
  GPU install and nothing verifies its contents. The check belongs where the asset is produced
  (publish-time verification of the four DLLs), with the registry's existing SHA pin making the
  verified bytes the only accepted bytes. A sandbox `must-watch` covers the residual ("EP fell
  back to CPU while status reports a GPU variant") until the truthful-status work makes it an
  API assertion.

### C.3 Sequencing consideration (not a decision)

R11-F2's fastest verification is the packaged verify lane's restart leg (host, minutes), not a
sandbox round — so the campaign can iterate on the blocker without burning rounds. A passing
upgrade-mode round is still owed (none has passed; rounds 9-11 all NOT QUALIFIABLE), and the
final qualifying round must be fresh-install per the mode policy. So the minimum remaining
schedule is: fix campaign → round 12 upgrade-from-release **with a restart leg in its charter**
(verifies F2, F1 wording, and the F3 repair journey on a genuinely upgraded machine) → final
fresh-install qualifying round (owes golden parity and inherits the asset check).

---

## Part D — principles surfaced, with earn-keep evidence and retirement conditions

1. **Per-boot facts need instance-anchored freshness.** Any fact minted per process boot
   (port, token, future manifest fields) that is cached across a boot boundary must be keyed to
   the instance that minted it or carry an invalidation path; "first observation wins" is only
   correct within one instance's lifetime. *Existing violations:* the token (this campaign);
   audit whether anything else reads the manifest with first-write-wins semantics. *Earns its
   keep if:* no further stale-per-boot-fact defect class appears in later rounds. *Retire when:*
   the binding becomes one instance-keyed structure (direction A.2-1), at which point the
   principle is embodied and the prose is redundant.

2. **Consequence wording is licensed by cause class, from a single exported classifier.**
   Severity ranks; class licenses claims. Projections consume the classifier; they do not
   re-derive. *Existing violations:* `availability.ts` (this campaign). *Earns its keep if:*
   no new banner-vs-trace contradiction finding in rounds 12+. *Retire when:* every consequence
   site consumes the one classifier and a check enforces that no new site re-derives from
   severity — then the rule lives in the check.

3. **A status field is an intent or an observation — never an intent presented as an outcome.**
   The requested/converged split (804 §B6) generalized. *Existing violations:*
   `onnxFeatures[].status`, `gpuLayers`, `activeVariantId` under CPU fallback; Brain Advanced's
   configured-vs-effective GPU layers. *Earns its keep if:* the next silent-capability-loss
   defect is caught by an API assertion instead of a log read. *Retire when:* the runtime-status
   surfaces carry explicit observed-state fields and the sandbox must-watch converts to an API
   assertion — the shape then being enforced where it matters.

These are recorded as principles with scope, **not** built as generalized structure now; each
fix in this campaign instantiates them only where the present defects require.

---

## Part E — harness batch (rides along, no design needed)

Staged-doc corrections (the `prod=false` line in the environment doc; the MCP `execute:true`
"MANDATORY" claim, refuted in-round by a successful GUI approval; Memory-surface reachability),
the gaps-file generator diffing documented-vs-staged instead of defaulting to "None", staging the
three scripts every upgrade round otherwise rewrites (`chat-ask`, `oracle`, `redact`), the
`snap.ps1`/`crop.ps1`/`click.ps1` paper cuts, `Set-AppWindowRect`, moving charter item 8 into
`collect-evidence.ps1` Step 0, registering the two invented coverage items (chunk-embedding
continuity across upgrade; EP-fallback-vs-status contradiction), and the charter-writing rule
that a BROKEN criterion names the *class*, not one instance ("any shipped-UI control whose
mutating call 401s", not "401 on search").

---

## Part F — Cross-round root-cause analysis (rounds 4-11) and the refactor/rewrite judgment

Written 2026-08-04, after round 11's analysis, against the full round history in tempdoc 734
(rounds 4-11) and tempdoc 798 (round 7's mechanism detail). The question: what actually recurs
across rounds, and is any of it better served by refactoring/rewriting the causal code than by
another per-finding fix campaign.

### F.1 The five recurring classes

**Class 1 — recorded claims nothing verifies against reality ("truthfulness debt").** The
largest class by count, and already named once: tempdoc 798 §D0 found seven of round 7's twelve
findings were this one shape (`embedding_status=COMPLETED` with no vector; NER `COMPLETED`
though never run; "reconnecting…" with no actuator; a register asserting reachability for
unreachable shapes; an ADR asserting bundling that never built; `qualityKnown=false` hardcoded;
a parity gate asserting comparability its own calibration never sampled). It kept recurring
after 798's fixes because each fix addressed an *instance*: round 10 F1 (banner "keyword-only"
while dense executed), round 11 F1 (same claim, second code path), round 11 F3's status side
(`cuda12`/`active`/`gpuLayers: 99` while every ONNX session runs on CPU), round 11 F8 (activity
PENDING(0) beside a live modal; GPU layers 0-vs-99). The structural signature: **a projection
derives a user-facing claim from configuration, severity, or a heuristic — not from an
observation — and no single authority owns the axis.** Where the codebase built a single
verdict authority with anti-fork gates (595's system-health verdict, the canonical
`SearchTrace`), findings stopped; the axes still producing findings are exactly the ones
without one (runtime/EP status, install-state consequence, consequence wording until 804/805).

**Class 2 — unexercised shipped configuration.** Every blocker in rounds 9-11: round 9 F1 (the
packaged shell could not boot at all — updater config existed only in the tag overlay, and no
tier ever booted the shipped shell), round 10 F7/F3 (the `prod=true` flip armed two latent
defects — dev runs `prod=false`, so no tier had ever run the UI against token enforcement or
prod-mode settings), round 11 F2 (the restart lifecycle of the packaged build — never
exercised anywhere), round 11 F3 (the upgrade arrival state — tempdoc 772's payload trimming
was validated for fresh installs only). The packaged verify lane itself turned out to run in
**no** workflow until this campaign ran it by hand and found three rot defects. The signature:
**verification tiers get added reactively, one blocker behind, because the axes of the shipped
configuration — prod flag, packaged-vs-dev, boot count, arrival state — were never enumerated
as a matrix anyone must cover.**

**Class 3 — state machines that assume the first lifetime.** Round 4's dead capability gate;
round 7's livelock (an idle loop whose continue-condition measured writes, not progress, plus
two repair lanes each correctly undoing the other — the second *ingest* was the unexercised
event); tempdoc 637's stale port; round 11 F2's stale token (same cache hierarchy, next field
over); the backend-restart event no one subscribes to. The signature: **per-boot or
per-operation state modeled as global-forever state; "first observation wins" applied across an
instance boundary.**

**Class 4 — upgrade/migration blindness.** All three upgrade rounds found arrival-state
defects no fresh-install round could see: settings interplay (round 10 F3), the retained
runtime pack vs the trimmed jar (round 11 F3), chunk-embedding survival True→False, contract
granularity (round 11 F4). Registry, payload composition, and contract all evolve per release;
nothing installs version N-1, upgrades, and asserts capability parity.

**Class 5 — fail-open measurement.** `snap.ps1`'s silent capture failure recurred across
rounds 9, 10, and — in the round's own hand-written wrapper — 11; PowerShell 5.1's discarded
error bodies produced the same false "empty 400 body" conclusion in rounds 10 and 11;
filename-credited coverage was defeated by mislabeled captures in round 5 (which is what
created the evidence-review reader gate). The harness has been converging on fail-closed
design one tool at a time; the class is real but its trend is the right direction already.

### F.2 What rewriting has already proven here

Two data points inside this same round history argue that **small, class-eliminating rewrites
of state machines pay off, and instance-level patching does not**:

- The 737 single-writer runtime reconciler was a rewrite of exactly a class-3 defect (round
  4's dead gate). Zero recurrence across rounds 5-11; round 11's charter item 7 (the tri-state
  mode response) passed on top of it.
- The port got an instance-level patch (`force_set_port`, 637) instead of a class fix — and
  the identical defect resurfaced one field over as round 11's blocker.

### F.3 Judgment: what to rewrite, what to refactor, what to leave

**Rewrite (small, class-eliminating): the shell's backend-discovery state.** `BackendState`'s
discovery core plus the three delivery channels (stdout parse, manifest poll, updater reset) is
~350 lines including its accreted special cases: per-field first-write-wins, a force override
for one field but not the other, a reset that only the updater path calls, and a manifest
reader that trusts any well-formed file. The 805 Part A direction (one atomically-replaced
`{instanceId, port, token}` binding; observations for a new instance replace the record,
observations for the current one fill gaps) deletes all four special cases and makes the 637
port fix a corollary instead of a precedent to imitate per field. This is the 737 shape at
1/10th the size. Pair it with the webview-side binding refactor (module-level token/port caches
→ one invalidatable binding with the 401 one-shot heal), since the JS cache is the same class
one layer up.

**Refactor, not rewrite: install completeness.** `AiInstallService` is large and has accreted
(one-shot recompute flags, resumable-bytes recovery, packs), but its download machinery
demonstrably works — the failures (round 10 F2, round 11 F3/F4) are both in the
*classification* of completeness. Extract that decision into a pure, file-level diff module
(registry requirements × disk state × contract entries → {satisfied, real-gap,
registry-addition} per file) with the service as its consumer. The contract already records
per-file `installedFiles`; the data model needs no migration, only the comparison granularity.

**Conform, not rewrite: the truthfulness axes.** Class 1 does not need new architecture — it
needs the architecture the codebase already validated (single verdict authority + anti-fork
gate, per 595/SearchTrace) extended to the two axes still producing findings: consequence
wording (one exported classifier consumed by banner and availability; Part B) and runtime/EP
status (observed execution-provider fields beside the intent fields; Part C). Where an axis
got its authority, its findings stopped; that is the strongest empirical pattern in the whole
round history.

**Leave alone.** The Worker's search pipeline (no recurring defect concentration; the dense-leg
dev↔sandbox divergence is a measured environment property under an owner decision, not a
defect), the ingest/backfill loop (798 B1's termination redesign has not recurred), and the
shell-v0 frontend at large (round findings concentrate in projections, not in the component
architecture).

**The highest-leverage investment is not product code: it is the verification matrix.** Every
round-9/10/11 blocker was findable on the host in minutes once someone looked — boot the
packaged shell once (round 9), run the UI tier against `prod=true` (round 10), restart the
packaged backend and re-assert (round 11 F2), boot against an N-1-shaped data dir (rounds 10/11
F3). The matrix is small and enumerable: {dev, prod} × {fresh boot, restart, upgrade-arrival}
× {in-process, packaged}. The sandbox should be the *fidelity* tier that confirms, not the
*discovery* tier that finds — three consecutive NOT QUALIFIABLE rounds, each burning ~3-4h of
wall clock plus a 10 GB download, is the measured cost of covering that matrix reactively. The
concrete increments, in value order: wire the existing verify lane into CI (it currently runs
nowhere), add its restart leg (Part A.3), add an upgrade-arrival leg (the
`ProdModeSettingsPersistenceIntegrationTest` seeding pattern already exists for the in-process
tier; the lane needs the packaged equivalent), and adopt round 11's trace rule (zero sub-ms
401 POST spans) as a standing lane assertion.

### F.4 Sequencing implication for this campaign

The shell-binding rewrite **is** the R11-F2 fix — Part A direction 1 done properly rather than
`force_set_session_token` as one more special case. It should not be deferred to a separate
"refactor later" item; doing it now costs marginally more than the patch and retires the class.
The install-completeness extraction can ride with the F3/F4 consequence work for the same
reason. The truthfulness-axis conformance (Part B/C) is this campaign's scope already. The
verification-matrix work is independent of all product fixes and can proceed in parallel; its
first two increments (CI wiring + restart leg) gate round 12's usefulness anyway.

### F.5 Correction on re-examination: Part F.3 under-called the list (2026-08-04, same day)

F.3 was anchored on blocker recurrence. Re-sweeping the full finding corpus at **any** severity
— the honest method, since today's LOW class is the next round's blocker — adds three warranted
items and puts one deliberately-deferred invariant on the table. F.3's "rewrite the discovery
state" call stands; it was incomplete, not wrong.

**(a) Shell shutdown path — refactor, same file as the discovery rewrite.** The normal quit
path (`kill_child`, `lib.rs:163-202`) is taskkill-without-`/F` (which typically fails against a
windowless `javaw`), a 2 s sleep, then force kill — so in the shipped product, **a normal quit
is a crash from the JVM's point of view** and Head's shutdown hooks are effectively dead code.
That is *why* stale manifests exist at all: `RuntimeManifestPublisher.close()` deletes the
manifest on clean shutdown, and clean shutdown never happens. Meanwhile a complete orderly
protocol already exists one subsystem over — the updater's prepare/commit handshake with a
shutdown nonce and a validated receipt (`updater.rs`), which "never terminates the child" and
waits for Head's own ordered exit. The refactor: normal quit attempts the orderly path
(bounded), falls back to kill. This is defense-in-depth for R11-F2 (the stale manifest stops
being the *normal* on-disk state), and it un-deadens every other shutdown hook the Head
registers. Evidence class: 3 (first-lifetime assumptions) — the quit path is the *producer* of
the stale state the discovery rewrite defends against.

**(b) Install-state model consolidation — a real refactor, larger than F.3's "extract a diff
module".** F.3 scoped this to completeness classification. The subsystem actually holds **six
overlapping records of "what is installed / what should run"**: the model registry (current
truth), the install contract (history), `InstalledPacksStore` (the packs subsystem's own
ledger — round 10 found `/api/ai/packs/installed` empty post-upgrade and established nothing
reads it for chat resolution), `settings.llmModelPath` (what activation reads),
`inference-model-id.txt` (what `InferenceLifecycleManager` reads), and the `AiInstallStatus`
ledger. The finding trail across the rounds is patches bridging pairs of these: round 10 F3's
fix added a contract-fallback into activation (contract ↔ settings); B8 recomputes the ledger
from disk (ledger ↔ disk); round 7 B5's destructive cancel and hardcoded consent copy are the
same subsystem's lifecycle edges. This is the representation-fork class (tempdoc 553) *inside
one subsystem*: pick one authority for "installed" (the contract, at file granularity), derive
the rest as projections, and — per retire-with-a-sweep — delete or explicitly re-purpose the
vestigial records rather than leaving them as false authorities. The download machinery still
does not need rewriting.

**(c) Escalation-rung reachability — make it declared, not emergent.** Four findings across
three rounds, all one class: round 5 finding 6 (`agent-run`/`free-chat` "not found" — the entry
points existed but were hidden behind composer state), round 7 B2a/B2b (`workflow-run`
unreachable by ANY user; `free-chat` reachable only via an unsurfaced deep link), round 11
retrospective item 9 (the Delegate rung reachable only from the empty landing state; ~8 min
lost re-finding it). Reachability of a rung is currently an emergent property of scattered
conditionals (`deriveAffordance`, `resolveShape`, `renderLanding` state) inside a 4,000+-line
view. The register side already exists (`CoreConversationShapeCatalog` + the
`check-intent-tier-coverage` gate + the coverage register's `reach` fields); what is missing is
the FE half: each rung's entry conditions declared as data, rendered from the declaration, and
testable as "for composer state S, rung R is reachable" — so an unreachable rung fails a unit
test instead of a sandbox round. Medium-size refactor of the entry-point layer only, not of
UnifiedChatView at large.

**(d) On the table, deliberately not asserted: artifact-truthful status derivation
(tempdoc 717 §TH-6).** The invariant behind round 7's livelock — status columns recorded
independently of the artifacts they describe (`embedding_status=COMPLETED` with no vector) —
was named by 717 and deferred. The shipped fix treated the lying writes and the loop's
termination; the invariant itself (derive status from artifact presence, don't record it
separately) remains unbuilt, and B8's recompute-from-disk is a partial instantiation of exactly
it. Per AHA this stays deferred until the class recurs — recorded here so a recurrence is
recognized as the second incident of a named class, not a novel finding.

**(e) Minor, batch when touched:** the GUI harness's accumulated paper cuts (missing
`Set-AppWindowRect`, printed-vs-written dimensions, `-ProcName` error reporting, `crop.ps1`
silent defaults) — one small consolidation pass over `JustSearchGui.psm1`, already itemized in
Part E.

**Unchanged from F.3:** the search pipeline, the Worker, the post-798 backfill loop, and
shell-v0's component architecture stay off the list — the corpus shows no structural defect
concentration there. And the ranking also stands: the verification matrix remains the
highest-leverage single investment; (a) and the discovery rewrite are one work item in one
file; (b) rides with the F3/F4 consequence work; (c) is independent and can wait for its own
campaign if round 12 must ship first.

---

## Part G — Settled design (2026-08-04)

Design pass over the full scope. Each workstream names what it builds, what existing structure
it extends, and what it **orphans** — orphan removal belongs to this campaign, not a later
sweep. Kept general; implementation details are the plan's job.

### G.1 W-BINDING — the shell's backend binding, and dying cleanly

**Design.** One atomically-replaced record `{instanceId, port, token}` behind a single lock,
with a provenance rule instead of per-field policies:

- An observation carries provenance: **own-child stdout** (always the live instance) or
  **manifest** (trusted only when its `instanceId` matches the current binding *or* announces a
  new instance — and, when the shell spawned the child, only when `manifest.pid` matches that
  child; a manifest failing both is stale residue and is ignored).
- Same instance → fill gaps. New instance → **replace the whole record** and emit the existing
  `justsearch://backend-restart` event. That single rule subsumes first-write-wins (correct
  within one instance), the restart override (replacement), and the updater reset (replacement
  with an empty record).

The webview mirrors the shape one layer up: the module-level token/port caches in `api/http.ts`
become one binding object that (a) subscribes to `backend-restart` and invalidates, and (b) on
a `UI_TOKEN_REQUIRED` 401, re-resolves and retries **exactly once**. The Tauri commands
(`api_port`, `session_token`) read the binding; their bounded-wait behavior is unchanged.

**Dying cleanly.** Normal quit gains an orderly leg: ask Head to run its ordered shutdown, wait
bounded on child exit, then force-kill as the fallback. Head already has exactly one ordered-
shutdown routine — the one `POST /api/upgrade/commit-shutdown` (tempdoc 617) drives — so the
design is **one routine, two callers**: a minimal lifecycle-shutdown entry point (token-guarded,
loopback) that invokes the same routine without the upgrade bookkeeping (no preparation id, no
handoff intent). 617's endpoints and semantics are not modified — that protocol is active work
and stays authoritative for the update path. Clean shutdown deletes the manifest
(`RuntimeManifestPublisher.close()` already does), so the stale manifest stops being the
*normal* on-disk state; the binding's provenance rule remains the defense for the crash case.

**Orphans (removed in this campaign):** `set_port`/`set_session_token` first-write-wins and
`force_set_port` (subsumed — `test_force_set_port_overrides_first_write_wins` is rewritten as a
binding-replacement test, not deleted); `reset_for_restart`'s per-field clears; the
taskkill-without-`/F` + 2 s sleep sequence in `kill_child` (it never worked against `javaw` —
replaced by the orderly leg); `cachedSessionToken`/`sessionTokenResolved`/`sessionTokenInFlight`
module state in `api/http.ts` (subsumed by the binding object).

**Verification.** Rust unit tests on the binding rule (gap-fill within instance; replacement on
new instance; stale-manifest rejection by pid and by instanceId); ui-web test for the one-shot
401 heal; the verify lane's restart leg (G.4) as the packaged-tier proof. The shell tier itself
(Tauri process) is not CI-drivable — that boundary is covered by the Rust unit tests plus round
12's charter, and is named honestly rather than papered over.

### G.2 W-CONSEQUENCE — one classifier licenses every degradation claim

**Design.** `readinessNotice.ts` exports a single consequence classifier: given the verdict's
reason codes, it returns one of `retrieval-impaired` (dense query leg positively cannot serve),
`passage-reduced` (NEW class — passage vectors absent/partial; document-level dense and
reranking still serve), `ai-unavailable`, `cosmetic`, or `unknown` (conservative). Membership
moves: `chunk_embedding.*` from `RETRIEVAL_IMPAIRING_CODES` into the passage class, with
wording that states the measured truth ("passage-level precision is reduced; results are still
ranked semantically"). Unknown codes stay conservative, as today.

Both projections consume the classifier: the banner branches on it, and
`availability.ts`'s docs-affordance caveat derives from it instead of from `verdict.severity`
— the caveat literal at `availability.ts:141` is the orphan. Cause lists in the impairing and
AI branches scope to the codes of their own class (the same scoping the reindex branch received
in 804). Enforcement follows the codebase's proven pattern: the existing `verdict-derivation`
gate (595 §4.2's single-derivation rule) is extended so the keyword-fallback claim wording may
appear only in the classifier's module — a re-derivation elsewhere fails the build, which is
what makes this a class fix rather than the third instance-patch.

The frozen-card default (`?? 'TEXT'` in `commitLiveSearch`) becomes unknown-renders-as-nothing:
a missing trace yields no retrieval-mode label, never the positive claim "Keyword". Why the
trace was absent at commit time is answered by live repro during implementation **before** this
fix is scoped — if a real trace-loss path exists upstream, it is its own item.

**Verification.** Classifier unit tests per class; the banner precision test asserts
passage-reduced wording never contains the keyword-fallback claim; a trace-vs-banner assertion
in the ui-shot/RAIL tier (headline claims keyword-only ⟺ trace lacks an executed dense stage).

### G.3 W-TRUTH — observed outcomes in runtime status; install completeness at file granularity

**Design, runtime status.** Each ONNX feature's session-creation outcome (the Worker already
knows it — it logs the CPU fallback) propagates into `/api/ai/runtime/status` as observed
fields beside the intent fields: per-feature requested vs. active execution provider and an
explicit fallback marker. This is 804 §B6's requested/converged split applied to the runtime
axis; no field changes meaning, the observation is *added* and the UI renders it (Brain
Advanced shows observed EP; Brain Simple gains a repair hint only when a fallback coincides
with a repairable install gap). The sandbox must-watch for the EP contradiction is registered
now and **converts to an API assertion** once these fields ship — its retirement is designed
in, not left as permanent apparatus.

**Design, install completeness.** A pure decision module computes, per file:
registry requirement × disk presence × contract membership →
`satisfied | missing-contracted (real gap) | missing-uncontracted (registry addition)`.
`AiInstallService` consumes it for `installedFully` and `pendingRegistryAdditions` (file-level,
fixing the round-11 misclassification where a contracted package's *new* file read as a real
gap). Consequence is wired, consent-preserving: real gaps surface as a repair-needed signal on
the install status that Brain renders as an advisory routing to the existing Repair flow
(which already collects terms acceptance). No auto-download.

**Authorities, stated once:** the registry is current requirement; disk is reality; the
contract (per-file) is install history; `settings.llmModelPath` is activation input with the
804 §B2 contract heal. `inference-model-id.txt` (runtime model-state marker) and
`InstalledPacksStore` (pack-import provenance) are audited during implementation: each consumer
either names the axis it legitimately owns or its read is swept (retire-with-a-sweep, this
campaign). The packs *feature* is not in question — only vestigial reads of its store as an
install authority.

**Asset guarantee.** The `ort-native-cuda12` supporting file is sha-pinned in the registry
already; what is missing is a content check at the only place contents change — asset
(re)publish. A release-tooling script asserts the four ORT DLLs are present in the zip before
the sha is pinned; the registry pin then makes the verified bytes the only accepted bytes.

### G.4 W-MATRIX — the verify lane becomes the discovery tier

**Design.** The packaged verify lane (`verify-installer-nsis-win.ps1`) is wired into
`build-installer.yml` as a post-build job on the built artifact — the `-SkipVerify` rationale
comment is stale (the lane is self-contained; proven by three local runs this session) and is
the orphan. First run report-only to establish runner fitness, then blocking.

Three legs, one script:

1. **Fresh boot** (exists): readiness + token enforcement (401/200/401).
2. **Restart** (new): kill Head, relaunch the payload against the same data dir, assert the
   manifest's `instanceId` changed, re-fetch the token, and assert a mutating call succeeds
   with the new token and 401s with the old one. This is the R11-F2 catcher at the payload
   tier.
3. **Upgrade-arrival** (new): boot against a checked-in fixture data dir shaped like the
   previous release left it (v0.1.0-form contract, retained 5-entry runtime pack marker, no
   settings file — no real model bytes; the in-process
   `ProdModeSettingsPersistenceIntegrationTest` already models this shape). Assert the install
   status reports the repair-needed state and activation resolves via the contract fallback.

The sandbox side gains the mechanical token-health assertion: a check over `traces.ndjson`
failing on any webview-originated POST span with status 401 and sub-millisecond duration —
round 11's discriminator, promoted from prose to gate. With these, the sandbox's role shifts
from discovery to confirmation; the matrix cells {prod × restart × packaged} and
{prod × upgrade-arrival × packaged} stop being sandbox-only.

Deliberately **not** built now: a `prod=true` dev-stack mode (the in-process integration tests
plus the packaged lane cover that cell; a dev-stack mode is structure for a case the problem
does not currently include).

### G.5 W-HARNESS — the batch

As itemized in Part E and F.5(e), unchanged in scope: the three staged-doc corrections (the
`prod=false` claim, the MCP `execute:true` MANDATORY claim — the register's `validateHow` text
is the authority to edit — and Memory reachability), the gaps-file generator diffing
documented-vs-staged, staging `chat-ask.ps1`/`oracle.ps1`/`redact.ps1`, the `JustSearchGui.psm1`
consolidation (`Set-AppWindowRect`, printed-vs-written dimensions, `-ProcName` echo, `crop.ps1`
fail-loud), charter item 8 moved into `collect-evidence.ps1` Step 0, the two new coverage-
register entries (chunk-embedding continuity across upgrade; EP-fallback-vs-status
contradiction), and the charter-writing rule that a BROKEN criterion names the class.

### G.6 Explicitly out of scope

Rung-reachability declarativization (F.5c — its own campaign; four findings justify it but it
shares no files or risk surface with this one), artifact-truthful status derivation (F.5d —
parked per AHA until a second incident), the physical-schema fingerprint redesign, FAIL_CLOSED
parity enforcement, and any change to 617's upgrade protocol beyond sharing its ordered-
shutdown routine.

### G.7 Reach judgment

**Conformances (extending proven seams, not creating parallels):** the consequence classifier
extends 595's single-verdict-authority + `verdict-derivation` gate pattern — the strongest
empirical pattern in the round history (axes with an authority stopped producing findings).
The EP-status split conforms to 804 §B6's requested/converged shape. The binding rewrite
reuses 637's `backend-restart` event as the invalidation signal instead of inventing a new
one (implementation found the event already had a page-reload subscriber in `main.jsx` —
the rewrite replaces it with invalidate-then-reload rather than stacking a second listener). The install-completeness module applies 553's projection-vs-fork discipline inside one
subsystem. The shutdown leg reuses 617's ordered-shutdown routine — one routine, two callers —
rather than a second shutdown path.

**One new principle beyond Part D's three:** *normal termination must traverse the ordered
shutdown path; force-kill is the fallback, not the design.* A product whose normal quit is a
force-kill silently converts every shutdown hook into dead code and makes crash-residue the
normal on-disk state — R11-F2's precondition was manufactured by the quit path, not by a
crash. Candidate scope: any state whose cleanup lives in a JVM shutdown hook (the manifest
today; audit what else registers one). Earns its keep if stale-residue defects stop appearing
in restart/upgrade rounds; retire when a test asserts clean-quit residue is empty and the
orderly leg is the only quit path left to describe.

---

## Part H — Derisk addendum (2026-08-04)

Ten uncertainties probed before implementation; every verdict carries a source pointer. Two
produced design amendments (U3, U5) — the rest confirmed Part G's mechanics, several more
favorably than designed.

### Verdicts

**U1 [was HIGH] — CONFIRMED, better than designed.** The one-routine-two-callers structure
already exists: `HeadShutdownCoordinator` implements the upgrade's `UpgradeShutdownAction` by
delegating to its own `shutdownNormally()` (`HeadShutdownCoordinator.java:43,57-61`), and the
JVM shutdown hook calls the same `shutdownNormally()` (`HeadlessApp.java:778-786`).
`performOrderedShutdown` closes the manifest publisher (`HeadlessApp.java:829,862`), which
deletes the manifest. W-BINDING's shutdown leg reduces to: expose `shutdownNormally()` over
HTTP (the late-bound bridge pattern `UpgradeShutdownBridge` already solves the
construction-order and package-visibility question) and call it from `kill_child` before the
force-kill fallback. No extraction work.

**U2 [was HIGH] — CONFIRMED; the modeling already exists, only the plumbing is missing.**
*SUPERSEDED IN PART during implementation (W3): no proto change was needed at all — the
per-encoder observed state already crosses Worker→Head as `StatusResponse.gpu.*OrtCuda` →
`WorkerStatusMapper.mapOrtCudaProbe` → `OrtCudaView` (`RemoteKnowledgeClient.java:1309+`), and
the fix conformed to the `EncoderRuntimeExplainer` authority instead of extending the health
proto. The paragraph below records the derisk-time understanding.*
`OrtCudaStatus` already models round 11's exact failure modes as named states —
`missingDlls(variantId, path, missing)` and `providerFailed(variantId, path, reason)`
(`OrtCudaStatus.java:89,104`). The Worker→Head health proto already carries per-model observed
state (`OnnxDiscoveredModel.sessionActive`, populated from registered suppliers in
`GrpcHealthService.java:263-270`) — but it is a boolean "a session exists", which is TRUE on a
CPU-fallback session; that is mechanically why the status lies. W-TRUTH's runtime-status half
is therefore: add the EP-outcome (provider actually in use + fallback reason) beside
`sessionActive` in the proto (a `contracts/**` change — wire gate applies), populate it from
the same supplier seam, and map it through the Head's status assembly. Moderate, with all
semantics pre-modeled.

**U3 [was HIGH] — AMENDED; three design refinements.** `ModelPackage.selectVariant` returns
null for a variantless package (`ModelPackage.java:145-152`, `cuda-runtime` has
`variants: []` in v0.1.0 AND 0.2.0 registries — verified at the `v0.1.0` tag), so the contract
writer records `cuda-runtime` as **`skipped("No variant")` with no installedFiles** in both
versions. Consequences: (1) round 11's `installedFully:false` came from `containsKey` matching
a *skipped* entry — B8's package-level check has an entry-kind blindness on top of its
granularity problem, so the diff module must treat skipped-kind entries as
not-contracted-for-files; (2) on real upgraded machines the missing ORT natives therefore
classify as registry additions, so **the repair-needed signal must key on
"required-by-current-registry-for-this-profile AND missing", independent of the
contracted-vs-addition classification** — contractedness decides only the `installedFully`
truth claim and the additions list; (3) going forward the contract writer records variantless
packages that had files planned as installed-with-files, so the contract regains per-file
authority for the package class that caused round 11. Also confirmed: `PlannedDownload` is
per-file with `packageId`/`targetPath`/`isModelVariant` (`InstallPlan.java:72-79`) — the diff
module's input exists.

**U4 [was MED] — CONFIRMED, and W-CONSEQUENCE is more conformant than designed.** The
derivation-gate family is an established pattern (verdict-derivation, ai-verdict-derivation,
folder-status-derivation, capability-availability — each a `governance/*.v1.json` register +
`scripts/ci/check-*.mjs`). Better: `availability.ts` is **already a governed projection
surface** under `governance/capability-availability-surfaces.v1.json`, whose note names
exactly the fork class W-CONSEQUENCE fixes. The classifier gate is a sibling register+check
with `check-capability-availability.mjs` as the template.

**U5 [was MED] — ANSWERED by code-read; no live repro needed; one design refinement.** The
quick pass **is** keyword-only by design: `buildSearchIntent` pins `body.mode = 'text'` +
the cheap pipeline for `stage === 'quick'` (`searchState.ts:330-332`). A commit-on-open during
the quick window freezes an honestly-TEXT trace (or a trace-less state) that the refined
HYBRID pass supersedes on the live card but never on the frozen copy. So the frozen-card fix
is not only the `?? 'TEXT'` default: **the frozen card's retrieval-mode identity must come
from the refined pass of that query, or render as unknown** — a quick-pass mode must never
masquerade as the search's identity. There is no upstream trace-loss bug to chase.

**U6 [was MED] — CONFIRMED.** The shell's Rust tests run in CI: `cargo test --lib --locked`
(`ci.yml:481`). W-BINDING's primary regression home is a real CI tier.

**U7 [was MED] — CONFIRMED.** ui-web already has the lazy Tauri event-listener pattern
(`router/tauriBridge.ts:41`, `TauriDeepLinkSource.ts:62` — dynamic `@tauri-apps/api/event`
import, no-op outside Tauri). The `backend-restart` subscription copies it.

**U8 [was LOW] — CONFIRMED.** The v0.1.0 `RuntimeManifestPublisher` already writes
`instanceId` (11 references at the tag), so the binding rule's no-instanceId branch is
defensive-only; every manifest the shipped product can encounter carries one.

**U9 [was LOW] — CONFIRMED with the known residual.** The lane discovers the port from the
stdout sentinel (not a fixed 8080), asserts the payload's own bundled JRE
(`verify-installer-nsis-win.ps1:408,444`), and needs no GPU. Residual: runner wall-time/RAM —
absorbed by the designed report-only first run.

**U10 [was LOW] — CLEAN.** No consumer of `RETRIEVAL_IMPAIRING_CODES`/`isRetrievalImpairing`
outside `readinessNotice.ts`; no test file pins `chunk_embedding.*` to the impairing branch,
so the reclassification re-pins nothing beyond the classifier's own new tests.

### Confidence and difficulty

Per workstream (0-10): W-BINDING **8.5** (all seams verified; the rewrite is ~350 lines with
an existing CI test tier); W-CONSEQUENCE **8.5** (register/gate template exists;
classification semantics settled); W-TRUTH **7** (the most moving parts: proto change + wire
gate + Worker plumbing + the U3 entry-kind rules); W-MATRIX **7.5** (mechanics verified;
runner-fitness residual); W-HARNESS **9** (mechanical). Overall: **8/10** — the two genuine
unknowns that could have invalidated the design (U1, U2) both resolved as
already-built-just-unplumbed, and the two surprises (U3, U5) were absorbed as amendments
rather than redesigns.

---

## Part I — Implementation record (2026-08-04, running)

Five worker bundles per the approved plan; wave 1 = W1/W2/W4/W5 in parallel (W1 sole Gradle
owner), wave 2 = W3. Every bundle left changes uncommitted for orchestrator review; every new
test carries a recorded bite proof (break → fail → restore → pass). This section records
acceptance verdicts and the deltas between plan and reality.

### W1 — shell binding + orderly quit (ACCEPTED)

Landed as designed with the binding rule extracted into a new pure `binding.rs` module —
worker-initiated deviation, accepted because the local `cargo test` tier is blocked by Smart
App Control on this machine (documented pitfall) and a pure-std module runs under a bare
`rustc --test` harness: the provenance rule got 7 real tests + 5 bite proofs locally
(including a deliberate re-introduction of the literal R11-F2 latch — `binding.token.or(token)`
— which the replacement test caught). Java: `POST /api/lifecycle/shutdown` via a new
`LifecycleApiModule` + `LifecycleShutdownBridge`, wired to a new
`HeadShutdownCoordinator.shutdownAndExit()` — a worker CORRECTION to Part G.1, which named
`shutdownNormally()`: that routine alone never exits the JVM (HeadlessApp blocks on a latch),
so the shell's bounded wait would always have timed out into force-kill; `shutdownAndExit()`
reuses `shutdownNormally()` + the existing exit CAS. Webview: one `sessionBinding` object,
`invalidateSessionToken()`, one-shot 401 heal in `authorizedFetch`, and the `backend-restart`
bridge REPLACING a pre-existing listener (see corrections below). 662 `:modules:ui` tests, 3933
FE tests, 13 bite proofs total. Residual: the lib.rs lock/notify wiring's tests execute only in
CI (`cargo test --lib --locked`, ci.yml:481) — watch the first CI run.

**Two Part-G/H claims corrected by implementation, verified at source by the orchestrator:**
(1) the consumer-side stdout parse no longer exists (tempdoc 501 Phase 8 deleted it; the
`JUSTSEARCH_SESSION_TOKEN=` handling at lib.rs:844 is only a redaction guard) — the manifest is
the sole observation channel, so the binding rule's stdout arm would have been dead code and
was not built; (2) `main.jsx:151` already subscribed to `backend-restart` (tempdoc 637
full-page reload) — the earlier "no subscriber" claim came from a grep filtered to `*.ts`,
missing the `.jsx` file; immaterial to the root cause (the reload re-resolved from the latched
Rust state) but corrected in 734's round-11 record and this document's Part A/G.

### W2 — consequence classifier + gate (ACCEPTED)

As designed: `classifyConsequence` with precedence impaired > unknown > passage > ai >
cosmetic (empty list = unknown — every other class is a calmer claim), `chunk_embedding.*`
moved to `PASSAGE_REDUCED_CODES`, the three caveat literals exported from `readinessNotice`
and imported by `availability.ts`, per-class cause/remedy scoping, the frozen-card mode
recorded only from a refined-pass snapshot (else `'UNKNOWN'`, which renders no label), and the
new `consequence-classification` register + check wired into the ui-web gate recipe. 3924 FE
tests green; 5 bite proofs including both gate halves. Notable finding: the sibling
`check-capability-availability` gate is wired ONLY via the consult-recipe (not the governance
runner) — the new gate replicates exactly that, no more. Two recorded judgment calls: the
impairing branch keeps its own causes when no impairing code is present (empty-cause list
would be worse), and `chunk_embedding.in_progress` alone (severity info) still takes the calm
info branch — pre-existing behavior, logged to the observations inbox rather than silently
changed. `searchTraceExplain.ts`'s "keyword results only" line is allowlisted by path: it
reports the trace's own recorded outcome, not a derived claim.

### W4 — verification matrix (ACCEPTED)

Restart leg live by default (`-SkipRestartLeg` to skip): instanceId-change assertion + fresh
token 200 + replayed old token 401 — the R11-F2 catcher at the payload tier. Upgrade-arrival
leg landed DORMANT behind `-IncludeUpgradeArrival` with the v0.1.0-shaped fixture
(`scripts/ci/fixtures/upgrade-arrival-v010/`), to be activated in the cross-cutting phase once
W3's `repairNeeded` exists. CI: `installer_verify` job in build-installer.yml (report-only
first run), stale `-SkipVerify` rationale deleted; the worker caught that the lane's
evidence-bundle validator needs `npm ci` on the runner and added it. `check_token_health.py`:
sub-5ms mutating-401 detector with the `/mcp` probe-pair allowlist — **against round 11's real
trace it reports 68 violations (exit 1)**, the mechanical confirmation of R11-F2's full scope;
healthy fixture passes; 17 unit tests auto-discovered by ci.yml's sandbox test step. The
worker also fixed a leak its own refactor introduced (orphaned java.exe on port-timeout throw)
— caught in its own review pass.

### W5 — harness batch (ACCEPTED)

All ten items: the false `prod=false` environment claim corrected; the refuted MANDATORY
`execute:true` MCP claim rewritten (either path works, with round-11's GUI-dispatch evidence);
Memory-surface reach corrected; staging-gaps generator now diffs documented-vs-staged (with
new unit tests); `chat-ask.ps1`/`oracle.ps1`/`redact.ps1` adopted from round 11's in-sandbox
scripts (already manifest-driven; headers added) and wired into the launcher's staging list;
`Set-AppWindowRect` + the three GUI paper cuts fixed (the `crop.ps1` fix required empirical
work — PowerShell `-File` silently drops unrecognized named parameters, detected via `$args`);
snap-fail-loud became `collect-evidence.ps1` Step 0.5 with a real bite proof; the two new
coverage entries registered; the charter-class rule added to `cut-a-release.md`. The worker
caught and fixed its own em-dash slips via a final non-ASCII diff scan.

### W3 — EP truth + install completeness (ACCEPTED, wave 2; resumed once after a transient API error)

Landed with the derisk-corrected scope: **no proto change** — observed EP conforms to the
`EncoderRuntimeExplainer` authority (tempdoc 422), whose policy-x-probe correlation moved into
an `explainAll` method now consumed by BOTH `/api/inference/encoders` and the runtime status
(the controller's private copy deleted — no fork). New Head-side seam:
`EncoderRuntimeCache`/`WorkerEncoderRuntimeCache` (2 s TTL, last-known-good).
`OnnxFeatureStatus` gains additive `executionProvider`/`gpuFallback`/`fallbackReason`;
`gpuFallback` is policy-aware (a CPU-by-design feature like the citation scorer never reads as
a fallback). `InstallCompleteness` (pure, entry-kind aware per derisk U3) replaces the
package-level `containsKey`; `repairNeeded` keys on required-and-missing independent of
contractedness; `buildContract` forward-fixed for variantless packages; the ort-native asset
content check parses its DLL set from `OrtCudaHelper` (no second list). Registered
`install-completeness` as a logic seam with a PIT-measured 100% strength floor (worker
initiative, accepted). Schema regen: response schema + generated Zod (strict-object — regen was
mandatory). 10 bite proofs; one attempted bite honestly reported as non-biting and replaced
with a test that pins the entry-kind rule independently. **Self-caught defect in its own
critical pass:** a `Long.MIN_VALUE` TTL sentinel overflowed so the cache never fetched —
observed EP would have been permanently "unknown" (the exact silent-wrong-value class this
campaign closes); fixed with an explicit first-fetch flag + injectable clock + 3 tests. Also
fixed a pre-existing FE wire drift: the `onnxFeatures` TS type declared `feature`/
`modelDescription` fields that never existed on the wire (Advanced rows rendered blank names).

### Cross-cutting verification (orchestrator, 2026-08-04)

1. `spotlessApply` clean; `build -x test` BUILD SUCCESSFUL.
2. **Forced full suite** `test --rerun-tasks --no-build-cache`: **BUILD SUCCESSFUL in 4m 45s,
   190/190 tasks executed** (no cache).
3. ui-web: typecheck clean; **3,942 tests / 379 files green** (W3's final run, all bundles
   present); gate set green except the two known-red-on-main entries (foreign files, confirmed
   by the expected-state hook).
4. `--gate wire`: pass (W3 run). New consequence gate + sibling shell-v0 gates green (W2 run).
5. `cargo test --lib` locally blocked by Smart App Control (documented pitfall) — the binding
   rule has 7 tests + 5 bite proofs via a standalone `rustc --test` harness over the verbatim
   `binding.rs`; the lib.rs wiring tests run in CI (`ci.yml:481`). **Watch the first CI run.**
6. **Verify lane run end-to-end** against the round-11 CI-built installer: fresh leg PASS,
   **new restart leg PASS** ("manifest instanceId changed across restart, fresh token accepted,
   stale pre-restart token rejected") — the leg's mechanics proven on a real payload. The
   payload predates this campaign, which is exactly why it can prove the leg: the Head-side
   token semantics are unchanged; the SHELL-side fix is covered by the Rust tests and by round
   12. Upgrade-arrival leg stays dormant until a post-campaign installer exists (CI).
7. **Browser validation** (dev stack served from THIS worktree's dist; the stale-dist trap
   fired once — `build -x test` does not refresh `installDist` — caught by the
   changed-line-serves-first check and fixed with an explicit `installDist` + restart):
   - Banner: "1 cause — AI features unavailable." live (AI-offline dev state; the classifier's
     AI branch, not a keyword claim).
   - Frozen card: committed hybrid search renders "meaning + words" (refined-pass identity) —
     the surface round 11 caught claiming "Keyword".
   - Brain Simple: "Installed — repair available / A required component is missing — use
     Repair in Advanced." live (the dev data dir genuinely has `repairNeeded: true`).
   - Brain Advanced: "Search reranking CUDA" / "Citation scoring CPU" — observed EP labels
     live, CPU-by-design not flagged.
   - Honest tier split for the rest: passage-reduced banner wording, commit-during-quick
     UNKNOWN, and the gpuFallback warning render are not reproducible against a healthy dev
     stack (they need degraded states / sub-second timing) — covered at the unit tier with
     bite proofs, per green-masked-destructive stated plainly rather than claimed as
     browser-proven.
8. Registers: inference-runtime register gained **D-009** (observed EP + install truth axes +
   asset guarantee); search-quality register checked — no update needed (wording/status
   surfaces only, no analysis change).

### Remaining before this campaign can close (tracked)

- CI must confirm: `cargo test --lib --locked` (shell), the report-only `installer_verify` job
  on a fresh build, then flip it to blocking and activate `-IncludeUpgradeArrival`.
- Round 12 (upgrade-from-release, WITH a restart leg in its charter) re-verifies R11-F2/F1/F3
  on the packaged product; the final fresh-install qualifying round owes golden parity.

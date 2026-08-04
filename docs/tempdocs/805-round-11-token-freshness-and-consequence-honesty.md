---
title: Round-11 fix campaign — token freshness across process instances, consequence-class honesty
status: "theorizing — problem space and solution directions mapped from round 11's evidence (tempdoc 734, R11-F1/F2/F3/F4); design not settled, no implementation started"
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
- The webview caches the resolved token permanently; nothing subscribes to the
  `justsearch://backend-restart` event the shell already emits, and no 401 invalidates the cache.

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

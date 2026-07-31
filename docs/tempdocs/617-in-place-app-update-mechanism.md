---
title: "Full auto-update (374 G1 deep-dive): decisions before design"
type: tempdocs
status: implemented; release qualification pending
created: 2026-06-20
updated: 2026-07-31
author: agent analysis (production-readiness pass), filed by agent
category: production-readiness / packaging / distribution / desktop-shell / migration
parent: 374
related:
  - 374-app-packaging-and-distribution
  - 409-releases-repo-audit-and-rework
  - 381-model-distribution-architecture
  - 290-upgrade-migration-safety
  - 542-operation-scoped-lease-taxonomy
  - 0024-app-packaging-nsis-per-user-download
---

# 617 - Full auto-update: decisions before design (374 G1 deep-dive)

> **Implementation update (2026-07-31).** The decision-scoping text below is
> retained as dated history. The implemented design supersedes its earlier
> assumption that `tauri-plugin-updater` belongs only to a future silent tier.
> JustSearch now uses the plugin as an authenticated download verifier while
> keeping apply user-consented, coordinating Head/Worker shutdown itself, and
> launching the verified NSIS installer through a witnessed Windows process.
> See Â§7 onward for the implemented contract and remaining release gates.

> What this document is. The **decision-scoping** doc for full auto-update — the deep-dive
> child of tempdoc **374 G1** ("Auto-updater", Tier 3 / GA). 374 deferred G1 with the
> rationale *"useless with a single-digit user count who downloads fresh each time —
> revisit after there's real adoption."* **That rationale is now lifted: the product is
> ready for these considerations.** This doc does NOT re-derive what 374 already settled;
> it (a) records what's already decided, (b) maps the adjacent concerns to their owners,
> (c) states the one genuinely-new risk thesis (migration asymmetry), and (d) — the point
> of this doc — enumerates **the load-bearing decisions that must be made before any design
> begins.** Verify dated claims against `main`.
>
> **Headline conclusion (2026-06-20):** full (silent) auto-update is **gated on code signing**.
> The *signing-independent spine* (detection + forward-only migration + feed/channel plumbing)
> proceeds now; the silent *apply* step is a thin cap that lights up when the Authenticode cert
> lands. See §4.0 for the gating model and the locked decisions.

## 1. What 374 already settled (do not re-derive)

374's gap register did real research here. Treat these as **inputs**, not open questions:

- **Mechanism leaning**: `tauri-plugin-updater`. 374 G1/Tier-3: *"auto-updater with hosted
  manifest + Ed25519 keypair. `tauri-plugin-updater` ties the signing cert from G4 to updates."*
- **Dependency chain**: **G4 (code signing) → G1 (updater).** 374 calls the signing pipeline
  *"already drop-in ready (2026-04-24 dry-run) — add the real PFX env var the day it's bought."*
  The Ed25519 *update* keypair is distinct from the Authenticode cert; G1 needs both.
- **Channels (G2)**: researched and considered low-risk — *"`tauri-plugin-updater` does
  SemVer-aware version comparison"* and alpha/beta/rc/stable prerelease channels *"already
  Just Work with `tauri-plugin-updater`'s default"* (beta users get betas; stable users only
  get stable).
- **Version SoT**: `gradle.properties` → propagated by `scripts/ci/sync-version.ps1`
  (`-RequireReleaseSemver` enforces release SemVer). Drift is the manual-sync risk.
- **Release feed**: `build-installer.yml` attaches the installer to a GitHub Release on a
  `v*` tag (mechanism landed 2026-04-24; no release cut yet; `/releases/latest` undefined).
- **Distribution-format decisions already closed**: NSIS chosen; **MSIX / Microsoft Store
  rejected** (ADR-0024, *"too much friction for an indie project at this stage"*); MSI is
  G10/Tier-4; macOS/Linux is G8/Tier-4.

## 2. Adjacency map — who owns each related concern

The "is this already solved?" audit (2026-06-20), so nothing gets silently re-scoped:

| Concern | Status | Owner |
|---|---|---|
| Auto-updater | designed, deferred (now un-deferred) | **374 G1** (this doc) |
| Code signing / SmartScreen | designed, drop-in ready, **not bought** | 374 G4 |
| Release channels (alpha/beta/stable) | researched | 374 G2 |
| Versioning / SoT | shipped (manual sync) | 374 G2 / `sync-version.ps1` |
| Release feed / "latest" pointer | mechanism shipped, no cut | 374 G3 / tempdoc 409 |
| Uninstall / data-cleanup | partly shipped | 374 G5 |
| Component-independent *versioning* | shipped (models/llama/ORT) | 381 / ADR-0024 |
| SQLite state migration | shipped, mature | `SqliteQueueMigrationOps` |
| Don't-interrupt-critical-work | concept exists, not wired to update | 542 (op-lease) |
| Upgrade-safe config defaults | shipped | 290 |
| **Staged rollout / kill-switch** | **absent** | — |
| **Product telemetry / version analytics** | **absent** (dev-harness ≠ product) | — |
| **Crash reporting (upload)** | **absent** (local dumps only) | — |
| **Index / settings / snapshot migration** | **absent / unversioned** | — (G26 = snapshot bug) |
| **Backup / restore of user data** | **absent** (factory-reset is one-way) | — |
| **Component-independent *update path*** | **absent** | — |
| Feature flags / remote config | absent | — |
| SBOM / changelog automation | absent / manual | — |

## 3. The one genuinely-new thesis: migration asymmetry is the real risk

374 G1 framed the updater as *delivery* plumbing (manifest, keypair, rollback, rollout UX).
The acquire/apply path is well-trodden and `tauri-plugin-updater` covers most of it. The
**under-weighted** risk is downstream of delivery: **the *migrate* layer is mature for
exactly 1 of 5 persisted state stores and absent for the other 4.**

| Store | Versioned? | Migration on version bump |
|---|---|---|
| SQLite job queue (`jobs.db`) | yes (`user_version` V1–V7) | automatic, transactional, backed-up |
| Lucene index (`data/lucene`) | no app-level pin | relies on Lucene internal codec compat |
| UI settings | no | none |
| Worker config snapshot | no | **stale-config bug — 374 G26** |
| Downloaded models / `model-registry.v2.json` | manifest-versioned | immutable per release tag (409) |

A full-auto updater that nails delivery but ships a UI-settings rename or a Lucene major bump
**silently corrupts user state on the first real upgrade**. Per `audit-driven-fixes-need-test`,
"the update is safe" is a hypothesis until an N→N+1 round-trip on real artifacts passes. This
is where the design effort should concentrate — not the manifest plumbing 374 already mapped.

## 4. The decisions to make BEFORE designing

These are the load-bearing forks. Each changes the architecture, not just a parameter. Listed
with what it gates and a recommendation lean (the user decides). **D1–D5 are blocking; D6–D7
scope the first increment.**

### 4.0 Locked decisions + the gating model

**Decisions locked (2026-06-20):**
- **D4 — Signing: stays DEFERRED.** No Authenticode cert purchase now.
- **D5 — Migration contract: FORWARD-ONLY.** Version-stamp every store; an older binary
  refuses to open *newer* state (fail-safe) rather than reverse-migrating. No downgrade support.

**The gating model — auto-update is split into two apply tiers at the code-signing line:**

| | **Tier A — consent-apply (now)** | **Tier B — silent auto-apply (gated)** |
|---|---|---|
| Apply step | app detects + downloads; **user clicks "update," runs the installer** | app applies + relaunches **with no human present** |
| Signing needed | none — the user clicks through SmartScreen, as in today's manual flow | **Authenticode cert** (else SmartScreen/SAC can *block* the silent `.exe` launch — no human to approve) + free Ed25519 update key |
| Ships on | the signing-independent spine (detection + migration + feed) | the spine **+ code signing (D4) + the D6 safety loop** |

So **"full / silent auto-update is gated on code signing"** is the operative constraint, *not*
because signing is legally required but because an unsigned installer auto-launched with no user
present can be blocked by SmartScreen/SAC — which defeats "silent." Two clarifications this rests on:
- The **free Ed25519 update key** (update-payload authenticity) is *not* the deferred-cost item;
  the deferred item is the **paid OV Authenticode cert** (SmartScreen reputation). The paid cert
  is what gates Tier B.
- Tier A is **not** gated — it is genuine "update instead of reinstall," one human click, and it
  ships on work that is needed for Tier B anyway. The detection + forward-only migration + feed
  plumbing is the durable, signing-independent **spine**; Tier B is a thin cap over it.

**Consequence for this doc:** the design target for *now* is Tier A on the spine. Tier B (and its
D6 safety loop) is sequenced behind the signing decision (374 G4), not designed against yet. The
decisions below are read through this lens.

### D1. Mechanism: commit to `tauri-plugin-updater`, or not?
374 leans this way but never *committed*. The alternative is a custom updater (more control,
more code) or notify-only-with-manual-installer (cheapest). **This is the root decision** —
it fixes the manifest format, the signing scheme (Ed25519), the channel model, and the
NSIS-vs-replace apply semantics. *Lean (under the §4.0 gating model): for Tier A now, a thin
notify + consent-apply layer (version check + banner + user runs the NSIS installer) — it needs
no signing and reuses the existing installer.* `tauri-plugin-updater` is oriented to the silent
Tier B path (it requires the Ed25519 key and auto-launches); adopt it when Tier B unblocks, and
verify its NSIS support is first-class then (historically MSI/AppImage were better-trodden).

### D2. Granularity: monolithic install-replacement, or component-level updates?
**The biggest architectural fork.** "The app" = Tauri shell + 2 JVM jars + `llama-server.exe`
(pinned `b8571`) + ORT/CUDA natives + ~9 GB models + UI bundle, each independently *versioned*
today but with no independent *update path*. Options:
- **(a) Monolithic** — every update is a full installer that replaces the ~748 MB app tree;
  **models are reused in place, never re-downloaded** (must be an explicit invariant). Simple;
  `tauri-plugin-updater`-native.
- **(b) Component-level** — patch just the inference binary, just the UI bundle, etc. Powerful
  (a llama.cpp CVE doesn't need a 748 MB push) but `tauri-plugin-updater` doesn't do this; it
  implies a custom layer (conflicts with D1).

*Lean: (a) monolithic for v1, with "app update never touches models" as a hard invariant.*
Component-level is a strong future capability but shouldn't gate v1.

### D3. Autonomy: silent auto-apply, or notify-and-consent?
Per §4.0, this is the Tier A / Tier B split. Silent auto-apply (Tier B) is **gated on D4 (signing)**
and on the D6 feedback loop. *Decision (follows from D4-deferred): Tier A — notify + consent-apply
— is the design target now.* Background *download* may still be automatic; it's silent *apply*
that's gated. Tier B is sequenced behind signing, not designed against yet.

### D4. Signing — **LOCKED: DEFERRED.**
The paid **OV Authenticode cert** (374 G4: cert cost + *"days-to-weeks of CA identity verification"*
+ CI secret handling) is **not** purchased now. Consequence (§4.0): **silent auto-apply (Tier B) is
gated** — an unsigned installer auto-launched with no user present can be blocked by SmartScreen/SAC.
The free **Ed25519 update key** is a separate, free item (only needed once Tier B is in play); not
generated now since Tier A doesn't use it. Tier A is unaffected — the user clicks through SmartScreen
exactly as in today's manual flow. Revisit when 374 G4 lands.

### D5. Migration contract — **LOCKED: FORWARD-ONLY.**
- **Direction**: forward-only. An older binary **refuses to open *newer* state** (fail-safe) rather
  than reverse-migrating; no downgrade/rollback of user data. (This means a bad update's recovery
  story is "reinstall the newer version / wait for a fix," not "roll back" — acceptable given Tier A
  is user-consented, so a user won't be silently moved onto a bad version.)
- **Framework**: build one uniform rule — **version-stamp every store + refuse-newer-in-older** —
  generalizing the SQLite backup-then-migrate pattern to the 4 currently-unversioned stores
  (Lucene pin, UI settings, worker config snapshot, + confirm models). **This is the real,
  signing-independent engineering of G1 and the spine's highest-risk piece** (§3).

### D6. Safety-infra scope for v1: how much rollout machinery ships first?
Staged/phased rollout, server-side **kill-switch** ("halt this release"), **product telemetry**
(which versions are live + update success/failure rate), and **version-tagged crash reporting**
(detect a bad update) are all **absent today** and are what make silent auto-apply (D3) safe.
Decision: build the feedback loop in v1, or ship notify-and-consent (D3) first and add the loop
before flipping to silent? *Lean: defer the full loop — it is part of the Tier B gate, alongside
signing. Tier A (consent-apply) ships without it.* Note: product telemetry is also a
**privacy/consent** decision (opt-in?), not just engineering — worth surfacing to the user explicitly.

### D7. Channel topology & feed: how many channels, hosted where?
374 G2 says channels "just work," but the *count* and the *feed location* are unmade choices:
single feed vs. alpha/beta/stable; manifest hosted on GitHub Releases `/latest` (currently
**undefined** — must be set) vs. a static endpoint vs. the `justsearch-releases` repo (409).
*Lean: stable + beta at launch; manifest off GitHub Releases with a real "latest" pointer;
fold the version-sync enforcement (fail build on drift) in as the prerequisite it is.*

## 5. Investigation order once decisions are made

**The spine (Tier A, signing-independent — buildable now):**
1. **Version hygiene** (D7 prereq) — enforce sync-in-build, set "latest". Cheap, unblocks detection.
2. **State-migration audit** (D5) — the real risk; version-stamp the 4 unversioned stores + the
   refuse-newer-in-older rule; produces the N→N+1 round-trip test that defines "safe."
3. **Detection + consent-apply (Tier A)** (D1/D2/D3) — version check + banner + user-run NSIS
   installer; monolithic, models reused in place.

**The Tier B cap (gated on code signing — sequenced behind 374 G4):**
4. **Signing** (D4) — generate Ed25519 update key + OV-cert procurement, when un-deferred.
5. **Silent apply + safety loop** (D3 Tier B / D6) — `tauri-plugin-updater` adoption + staged
   rollout + kill-switch + telemetry + crash-feedback. Designed once signing lands.

## 6. Remaining open questions for the user (D1/D2/D6/D7 — D4/D5 now locked)
- **D2**: monolithic full-installer updates (models reused in place) confirmed for v1? (Lean: yes.)
- **D7**: one update channel or alpha/beta/stable from launch? (Lean: stable + beta.)
- **D6 (Tier B, later)**: is product telemetry acceptable, and opt-in vs. on-by-default?
- **D1 (Tier B, later)**: commit to `tauri-plugin-updater` for the silent path, or a custom updater?

---

*Next step: confirm the remaining Tier-A questions (D2/D7), then update 374 G1 to point here and
execute §5.1–§5.2 (version hygiene + the migration audit) as the first concrete investigations —
the signing-independent spine — each producing a findings appendix. Tier B (silent apply) stays
sequenced behind 374 G4 (signing).*

## 7. Implemented design (2026-07-31)

### 7.1 Scope and user contract

The implementation is a **monolithic, explicit-consent NSIS update**:

- The always-mounted shell performs a delayed background **check only**.
- An authenticated available release is shown in global chrome and Settings.
- Download and apply begin only after the user activates **Install update**.
- Models under AI Home are not part of the installer artifact and are not
  redownloaded by the app-update path.
- Silent unattended apply, staged rollout, product telemetry, and automatic
  rollback remain out of scope.

The UI is a projection, not a trust authority. Only the Tauri shell can read
the authenticated release feed, download the installer, coordinate shutdown,
or launch the installer. Plugin-facing APIs do not expose those commands.

### 7.2 Authenticated release closed set

A release cut produces one closed set:

| Artifact | Purpose |
|---|---|
| `release.v1.json` | Signed JustSearch release identity, monotonic sequence, target, installer digest/size/signature/key, and durable-store compatibility. |
| `release.v1.json.sig` | Detached Ed25519 signature over the exact descriptor bytes. |
| `latest.json` | Tauri updater feed generated from the same descriptor inputs. |
| `JustSearch_<version>_x64-setup.exe` | Monolithic NSIS target. |
| `JustSearch_<version>_x64-setup.exe.sig` | Tauri/Minisign artifact signature. |

`release.v1.json` and `latest.json` must name the same version, URL, and
artifact signature. The shell additionally verifies the descriptor signature,
monotonic release sequence, durable-store compatibility, Tauri signature,
downloaded byte count, SHA-256, and Windows executable shape before preparing
shutdown. A mismatch anywhere fails closed.

Production release endpoint, metadata root public key, and metadata key id are
compile-time inputs. Production accepts HTTPS only. A separately compiled
Sandbox test gate permits runtime overrides and loopback HTTP only; it cannot
enable arbitrary insecure production endpoints.

### 7.3 Persistence closure

`governance/store-recoverability.v1.json` is the compatibility register for
durable authorities across dataDir, AI Home, configuration, shell upgrade
state, and Worker state. Each entry names:

- process owner and owned paths;
- AUTHORED or DERIVED recoverability;
- current and readable legacy format versions;
- atomicity/write mode and corruption policy;
- reconciliation strategy, implementation sources, tests, and fixtures.

The store gate now combines the declared register with a persistence-write
scanner. A new writer cannot silently exist outside the register. Full-rewrite
authored JSON stores use atomic replace, preserve corrupt/future data, read
legacy v0, and emit versioned v1 envelopes. Worker SQLite migration now runs a
pre-writable-header compatibility preflight before a connection can mutate the
database. At implementation close there are no declared compatibility gaps.

### 7.4 Prepare, freeze, shutdown

The implemented protocol reuses the Local API and Worker gRPC transports:

1. Shell calls `POST /api/upgrade/prepare`.
2. Head closes mutating admission, reports real operation-lease blockers, and
   asks Worker to quiesce.
3. Worker stops ingest admission, drains accepted work, performs a final queue
   commit/checkpoint, and returns its graceful outcome.
4. Head returns the preparation id, shutdown nonce, and blocker/loss posture.
5. Shell downloads and independently revalidates the installer.
6. Shell calls `POST /api/upgrade/commit-shutdown` with the same preparation id
   and shutdown nonce.
7. Head acknowledges the frozen preparation, runs its ordered shutdown once,
   and atomically writes `upgrade/head-shutdown-receipt.v1.json`.
8. After observing the original Head child exit, Shell validates the receipt's
   preparation id, nonce, original PID, clean outcome, graceful Worker outcome,
   and empty error set before entering `HEAD_STOPPED`.

Mutating HTTP requests acquire request-scoped operation leases. Long-running
bulk reindex, index GC, rebuild, AI pack import, and runtime activation
operations participate in the same blocker/drain model. Cancellation reopens
admission and Worker ingest only for the matching preparation and nonce.

### 7.5 Durable updater state and recovery

The shell persists:

- `upgrade/intent.v1.json`;
- `upgrade/sequence.v1.json`;
- `upgrade/head-shutdown-receipt.v1.json` (Head-owned);
- `upgrade/installer-launch-witness.v1.json`;
- the staged installer artifact.

The phase vocabulary is closed:

`PREPARED â†’ HEAD_STOPPED â†’ INSTALL_LAUNCHING â†’ INSTALL_LAUNCHED â†’
RECONCILING â†’ COMMITTED`, with terminal `CANCELLED` and
`REPAIR_REQUIRED`.

Before launch, Shell rehashes the staged artifact. It launches the NSIS updater
with `ShellExecuteExW`, requires a nonzero process witness, and atomically
persists that witness before allowing shell exit. It does not call
`Update::install`, because that API launches through an unobserved path and
exits the process without a recoverable error boundary.

Startup reconciliation is evidence-based:

- A pre-launch intent still on the source version becomes `CANCELLED`.
- A witnessed launched intent on the target version becomes `COMMITTED`.
- Version equality alone never proves success.
- Missing, corrupt, contradictory, or unprovable evidence becomes
  `REPAIR_REQUIRED`.
- If handoff fails after clean Head shutdown, Shell resets the upgrade
  preparation and restarts Head rather than leaving the app unnecessarily
  offline.

### 7.6 UI

`appUpdateState.ts` is the one frontend projection of Tauri updater status.
Settings and `jf-app-update-banner` subscribe to it. The global banner is
visible only for actionable `available` and `repair_required` states and routes
the user to Settings. Settings owns check/install actions and never initiates
install without explicit activation.

### 7.7 Release and Sandbox qualification

Tag release workflow inputs include the metadata signing key, public root,
monotonic release sequence, artifact signing key/id, and production descriptor
URL. Tag builds assemble and verify the complete updater closed set before
upload; ordinary branch installer builds do not pretend to be publishable
updater releases.

The existing `upgrade-from-release` Sandbox lane remains the exact published
installer-over-installer arrival test. The new
`in-app-update-from-release` lane stages a verified loopback release set plus:

- `serve-updater-feed.ps1`;
- `start-in-app-update-test.ps1`;
- `collect-updater-evidence.ps1`;
- `updater-qualification.v1.json` and recovery instructions.

The in-app lane necessarily installs a previous-source Sandbox build compiled
with the updater test gate; an exact production source binary rejects runtime
trust overrides by design. The ordinary upgrade lane therefore remains
required to qualify the exact published previous installer.

## 8. Verification evidence

The focused implementation pass records evidence in source tests and command
output rather than claiming a GUI round that was not run:

- persistence register/scanner gate and its fixture tests;
- atomic store, legacy-read, future-version refusal, and Worker SQLite
  compatibility tests;
- operation lease callback/ack, admission freeze/cancel, Worker quiescence,
  Head shutdown receipt, and lifecycle contract tests;
- Rust descriptor/transition/reconciliation/receipt/witness/reset tests;
- release descriptor build/verify/tamper/key-rotation tests;
- updater UI state/banner/Settings tests, TypeScript check, live Settings
  capture, source-to-step coverage, and six-surface accessibility gate;
- Sandbox launcher in-app lane tests and PowerShell parser checks.

The GUI-gated Windows Sandbox was deliberately not launched during this pass.
Its real NSIS handoff, Windows process witness, state survival, and restart
reconciliation remain release-qualification evidence, not implementation-unit
evidence.

## 9. Remaining gates

Implementation is complete when the full integrated verification below is
green. Release enablement still requires:

1. Configure production metadata signing keys, artifact signing key/id,
   monotonic release sequence, and the HTTPS release descriptor endpoint.
2. Cut two updater-capable candidate versions or an equivalent previous-source
   Sandbox build plus target so the in-app Nâ†’N+1 lane is executable.
3. Run the exact published installer-over-release lane.
4. Run the in-app Sandbox lane through normal commit plus at least one captured
   interruption/reconciliation case.
5. Retain the resulting intent, shutdown receipt, launch witness, installed
   version, and authored-state survival evidence.

These are qualification/operations gates. They do not justify weakening
production trust or adding force-kill behavior to NSIS.

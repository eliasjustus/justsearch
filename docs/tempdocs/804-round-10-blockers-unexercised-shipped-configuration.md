---
title: Round-10 blockers — the unexercised shipped configuration
status: "design settled 2026-08-01 — implementation not started. Covers the round-10 fix campaign: both blockers (F7 UI-401, F3 AI-dead — one root cause: PR #350's prod=true flip arming two latent defects), the 12 non-blocking findings, and the harness items. Root-cause evidence lives in tempdoc 734's round-10 section; this doc holds the design."
created: 2026-08-01
updated: 2026-08-01
---

# Round-10 blockers — the unexercised shipped configuration

Companion to tempdoc 734 (rounds ledger; round-10 section holds the verified root causes) and
tempdoc 801 (round-8 campaign; its open "shell-liveness CI step" and "empty 400 body" items are
absorbed here as §B4 and §B7). Round 10's raw evidence: `tmp/sandbox-round10/share/evidence/`.

## §A What happened, in one paragraph

Round 10 (upgrade-from-release) returned NOT QUALIFIABLE with two blockers that share one root
cause. PR #350 flipped the packaged shell's Head launch to `-Djustsearch.prod=true` — correct in
intent (v0.1.0 shipped its entire loopback API unauthenticated) — and that single flag armed two
latent defects nothing had ever run together: (F7) the shell-v0 frontend never adopted the
token-attaching HTTP client, so with enforcement on, every mutating UI call 401s — search, chat,
encryption all dead, on a pristine data dir too; (F3) `justsearch.prod=true` *also* silently
switches `UiSettingsStore` to in-memory, so `llmModelPath` (and every other settings write,
including F4's failing `core.set-chat-enabled`) is discarded — all AI activation fails
`MODEL_PATH_REQUIRED` with no in-product recovery. The round's packs-registry hypothesis was
refuted host-side: nothing reads the packs registry for chat resolution; its error copy is
simply wrong. Both defects are build-level: a fresh install of this candidate is equally dead.

## §B Design

### B1 — Settings persistence is its own explicit axis (F3/F4 root)

`justsearch.prod` currently means three things: loopback trust boundary (CORS + session-token
enforcement + manifest redaction — the intended axis), and, by an implicit rule in
`UiSettingsStore.resolveMode()`, "settings are in-memory" — a verification convenience that
became the shipped default. The design: **delete the implicit rule.** Persistence mode is
decided only by its own explicit setting (`justsearch.ui.settings.mode` /
`UI_SETTINGS_READONLY`); prod mode implies nothing about it. This is safe by construction for
the two existing prod-mode harnesses — both already pass the explicit `IN_MEMORY` override
themselves — so deleting the rule changes only the shipped app, restoring persistence.

Orphans (same PR): the implicit-rule branch and its comment; the assertions in
`UiSettingsStorePersistenceModeTest` that pin prod→IN_MEMORY (updated deliberately as the
contract change, not deleted as an inconvenience). Explicitly rejected: a belt-and-braces
`settings.mode=rw` arg at the shell spawn site — redundant config is a second authority that
can drift; one authority plus the B4 regression tier is the design.

### B2 — Activation resolves the model through a fallback chain (F3 hardening)

`RuntimeActivationService` currently has a one-link resolution chain: user settings. The
design adds the install contract as fallback: **settings (the user's explicit choice) →
install contract (the durable record of what install actually did)**. This conforms to an
existing seam rather than inventing one — the Worker already resolves models contract-first
(`KnowledgeServer`), which is exactly why ONNX features survived round 10's upgrade while chat
died. It also heals a deleted/reset settings file. The error copy is corrected in the same
change: "Import a models pack first" describes a dependency that does not exist; the honest
remedy names Install AI. A packs-registry migration is explicitly rejected — nothing reads
that registry for chat resolution, so migrating into it would build apparatus around a
refuted hypothesis.

### B3 — One authorized-request seam in shell-v0 (F7)

The token chain is intact for four hops and dies at the last: shell-v0 issues bare `fetch`
from ~50 non-GET call sites across 18 files. The design: **one shared authorized-fetch helper
in shell-v0 that reuses the existing resolver and header constant from `api/http`** (no
parallel token resolver — `resolveSessionTokenFromTauri` and `SESSION_TOKEN_HEADER` stay the
single authority), routed through the two covering seams first — `performFetch` (the
`host.data.fetch` capability behind every surface's `doFetch`) and the search store — then a
sweep of the remaining direct-fetch sites onto the same helper. The gate that keeps it fixed:
the existing eslint `no-restricted-globals: fetch` rule — which already names this exact
hazard and has been warning into the void — flips to `error`.

Orphans (same PR): the false comment in `SecuritySurface.ts` claiming `doFetch` injects the
token; any call sites the sweep converts.

### B4 — The shipped configuration becomes an exercised configuration (regression architecture)

The reason neither blocker was caught: dev, ui-shot, RAIL, and unit tiers all run backends
without `prod=true`, so the enforcing side and the attaching side were never tested together
(`green-masked-destructive`, config-axis form). The design closes it in three tiers, cheapest
first, extending existing structure rather than adding a lane:

1. **Unit guards** — `resolveMode()` with prod=true and no override → READ_WRITE; a ui-web
   test that a non-GET through the authorized seam carries the session header.
2. **Live-stack integration test** (the adverse-precondition test): boot with
   `justsearch.prod=true` and NO settings-mode override on a data dir seeded v0.1.0-shaped
   (populated install contract + model file + no settings file); assert activation does not
   fail `MODEL_PATH_REQUIRED`, the settings API is not read-only, and a written setting
   survives a store round-trip. This is simultaneously the F3 regression test and the
   upgrade-survival test round 9 and 10 both asked for.
3. **Packaged-payload lane** — `verify-installer-nsis-win.ps1` already boots the installed
   payload with `prod=true` and stops at readiness; extend it past readiness to the two
   enforcement assertions (headerless POST → 401 proves enforcement is really on; headered
   POST → 200 proves the server half works). The webview half — the real shell driving one
   search — remains sandbox-round territory (a `sandbox-must-watch` entry now; tauri-driver
   later per tempdoc 374).

### B5 — The reindex banner computes its claims from measured capability (F1)

Two distinct defects behind one banner:

1. **A fork of the index-compatibility authority.** `/api/status` said `reindexRequired:
   schema_mismatch` while `/api/knowledge/status` said `COMPATIBLE / FINGERPRINT_MATCH` at
   100% coverage — and the round proved the second one right (dense retrieval fully live under
   zero-lexical-overlap probes). Two producers answer "is the index compatible" and they
   drifted. Implementation must locate the `schema_mismatch` producer and reconcile to ONE
   authority — the measured compat state — rather than teaching the banner to arbitrate.
2. **Cause-list scoping and wording.** The banner's "Reindex required" branch renders ALL
   verdict reasons under the one rebuild remedy, so causes a rebuild cannot clear
   (`lambdamart.not_configured`, `inference.offline`) appear as reindex causes, and its static
   body asserts "results may be keyword-only" from the *presence of a code*, not from
   retrieval capability. Design: the rebuild headline lists only reindex causes (the
   `REINDEX_CAUSE_CODES` vocabulary already exists and is the single place that knows);
   non-reindex causes keep their own rows and remedies; the degradation claim follows the
   measured state per the banner's own tempdoc-595/600 design intent, which this branch
   currently violates.

### B6 — Mode transitions report their outcome, and deferral states must point at real work (F5)

`POST /api/inference/mode` must compute its response from the transition outcome — `success:
true` with the mode unchanged is a self-contradicting payload; if the transition is refused or
deferred, say so with the reason. Separately, `INDEXING` is a deferral state: it claims
inference yielded to indexing work. A deferral state whose justifying work does not exist
(idle queue, zero throughput, sustained) must exit on its own; the design ties the state's
continuation to the thing it defers to.

### B7 — Mutating 4xx responses carry a body, as a contract (F6, absorbing 801's open item)

Three endpoints now demonstrate the class (activate, indexing/roots, install/repair). The
design fixes the class once: a shared error-response path for mutating routes guaranteeing a
non-empty JSON body naming the offending field, plus one sweep-style test over mutating routes
asserting no empty-body 4xx — not three endpoint patches.

### B8 — "Installed" is a claim about the contract, not the current registry (F2)

`installedFully` is recomputed against the *current* registry, so any registry addition in a
new version makes a completed installation read "Not Installed" until a re-run (round 10: one
new 160 MB cuda-runtime artifact did exactly this). Design: split the claim — installation
completeness is measured against the contract that installed it; newly-registered artifacts
surface as a distinct "update available for AI components" state, not as retroactive
non-installation. (Claim computed from the thing it claims about — 801 §D0.)

### B9 — Smaller surface-truthfulness fixes (each with its regression home)

- **F8** Library folder rows render the path/basename the API already returns — never the
  digest. Home: ui-shot assertion.
- **F9** The Activity surface either renders what `/api/action-ledger` holds or scopes its
  promise; "No activity yet" over 405 live entries is false either way. Implementation
  verifies intended scope first. Home: ui-shot with a non-empty ledger.
- **F10** Palette matching covers non-leading tokens ("install ai" must rank the AI-install
  actions). Home: matcher unit test.
- **F12** MCP `serverInfo.version` binds to the build version. Home: contract test.
- **F13** The trace writer emits one parseable JSON object per line (escape newlines) and
  truncates `document.content` attributes — the coverage gate's own input must parse, and
  corpus text does not belong in host-copied artifacts. Home: writer-side test.
- **F14** The Ask rung conforms to its siblings' offline honesty (disabled-with-reason or
  reason-on-activation). Home: ui-shot with AI offline.

### B10 — Harness fixes (the loop's own debt, all round-verified)

- `snap.ps1`/`Save-AppShot`: create the parent dir, guard `Save`, assert the file exists,
  exit non-zero — regression test asserts `Test-Path`, never captured stdout (both traps the
  round documented). **Second round reporting this; the round-9 "fix" never landed.**
- Staged sandbox `CLAUDE.md`: correct the "no auth needed" API table (it produced a near-miss
  catastrophic false finding), document the token pair, and add "a 401 renders as zero results
  in any client that doesn't check status" beside the SSE and `id`-vs-`path` traps. Document
  the renamed-aside data-dir technique and verify-the-active-surface-before-clicking as named
  procedure, not folklore.
- `collect-evidence.ps1`: at least one POST in the sanity ladder, failing loud on 401 — its
  all-GET ladder scored 6/6 while the product's whole mutating surface was dead.
- The MCP external-client procedure gains the token step (the Inspector infers OAuth from the
  bare 401; the shipped bridge already handles the token itself).
- `docs/reference/api-contract-map.md` documents `POST /api/indexing/roots` (second ask).
- **Upgrade-survival coverage is promoted into `governance/sandbox-coverage.v1.json`** (second
  round asking): the four charter questions (index survives, user data survives,
  embedding-compat, installer over-install) become registered must-touch items for
  `upgrade-from-release` rounds, so the generated brief stops being byte-identical to a fresh
  round's.
- F11 residue → `sandbox-must-watch` (mid-upgrade uninstaller wording/data-deletion checkbox).

## §C Reach

**Conforming instances (existing principle, no new structure).** F1, F2, F5, F9, and F12 are
all instances of 801 §D0's invariant — *a claim must be computed from the thing it claims
about* — and their fixes conform to it: banner from measured capability, installedFully from
its contract, mode-response from the transition outcome, activity from its ledger, version
from the build. Nothing new to name there; the invariant keeps earning its keep.

**Named principle 1 — a flag owns one axis.** `justsearch.prod` bundled the trust-boundary
axis (CORS, token, redaction — coherent) with a test-isolation axis (in-memory settings) that
had no business shipping. The failure shape: a convenience semantic piggybacked on an
unrelated flag becomes invisible at flip time — PR #350's author reasoned correctly about the
axis the flag names and could not see the stowaway. Candidate scope: an EnvRegistry audit for
other flags with implied semantics beyond their name (none found for PROD_MODE's remaining
consumers, which are all genuinely trust-boundary; the audit of the other ~90 registry entries
is cheap and worth one pass during implementation). Earns its keep if the audit finds another
stowaway or the next flag-flip lands without an F3-shaped surprise; retire once EnvRegistry
entries each document their full consumer set and the audit comes back clean — at that point
the registry itself embodies the rule and the prose is redundant.

**Named principle 2 — the shipped configuration must be an exercised configuration.** Three
instances in two rounds: round-9 F1 (the tag-build overlay path had never been built — shell
panicked), round-10 F7 (enforcement never run against the real frontend), round-10 F3 (prod
settings mode never run with the product flow). Every verification tier ran a configuration
that differed from the shipped one in exactly the dimension that failed. This is
`green-masked-destructive` generalized from environment preconditions to configuration axes.
The structure the present problem requires already exists in embryo — the packaged-payload
lane (`verify-installer-nsis-win.ps1`) boots the true shipped configuration and stops at
readiness; B4 extends it to one mutating action + persistence round-trip, which is extension,
not new apparatus. Earns its keep when that lane (or the B4.2 integration test) catches a
prod-config regression before a sandbox round pays ~4 hours to find it; retire the prose
when the lane covers boot → mutating action → restart persistence, because then the principle
is embodied in an enforced check and restating it is bloat.

**Sequencing note (not design, recorded so implementation doesn't reinvent it).** B1+B2+B3+B4
are the release-critical path and gate round 11; B5's authority-fork half needs its producer
located before its fix is scoped; everything else is parallelizable worker-grade work.

## §D Derisk addendum (2026-08-01) — what investigation changed before implementation

Eight uncertainties (U1-U8, derisk plan) resolved; three amend the design. Load-bearing claims
below were re-verified at source by the orchestrator.

### D1 — B5 rewritten: two true claims, forked in the UI's bucketing (U1)

`/api/status`'s `schema_mismatch` and `/api/knowledge/status`'s `COMPATIBLE` are **both true**
— they are different comparisons over different data. `index_schema_fp` is the SHA-256 of the
canonical `SSOT/catalogs/fields.v1.json` (`SsotCommitMetadataSource.java:81-93`), compared at
`IndexStatusOps.java:995`; three post-v0.1.0 catalog edits (one dead-field deletion, three
`rmwPolicy` annotations) flipped it with **zero physical consequence for a v0.1.0 index**. The
embedding fingerprint hashes the model file itself and gates the dense leg
(`SearchPlanner.java:87` via `allowQueryEmbeddings()`); the schema state has **zero query-path
consumers** — purely advisory. The fork is one layer up: `readinessNotice.ts:330-335`'s
`REINDEX_CAUSE_CODES` buckets the advisory code with the genuinely-degrading `embedding_*`
codes and asserts the degrading bucket's consequence ("results may be keyword-only") for all.
**B5 is therefore frontend-only**: split the bucket — degrading reindex causes keep the
current wording; `index.schema_mismatch` gets its own branch (info-severity when the embedding
axis is COMPATIBLE) with honest wording ("Index format is out of date. Search is fully
working... rebuilding picks up newer index features"). No backend change; no producer
reconciliation. Force Rebuild does work and does deliver chunk vectors, so the remedy stays.

Two adjacent defects ride along (found by U1, verified):

- **The parity guard is unconditionally neutered**: `HeadlessApp.java:267` and `:607` both set
  `justsearch.index.parity.allow_mismatch=true` with no dev guard, while
  `docs/explanation/11-index-schema-migration.md` and
  `docs/reference/index-schema-mismatch-reindex-noop.md` advertise `FAIL_CLOSED` as shipped
  production enforcement. Either condition the set-sites or retract the docs — the current
  state is a docs/reality drift on an enforcement surface (`verify-dont-guess` applies to our
  own docs too).
- **The banner is the default first-run experience by construction**: on a fresh empty install
  `chunk_embedding.not_ready` fires whenever no chunks exist and `lambdamart.not_configured`
  is an unconditional default — so `retrieval` reads DEGRADED on every fresh install
  independent of any fault. "Not ready" is the wrong claim about work that does not exist;
  the empty-corpus case must not present as degradation.

### D2 — B6 shrinks: the wedge IS B1 (U5)

The INDEXING "wedge" has no independent existence: `RuntimeReconciler` is level-triggered on
`RuntimeSpec.chatEnabled`, and the spec write evaporates in the prod in-memory settings store —
same root as F3/F4. INDEXING never had a queue input to miss (it means "engine parked, GPU
yielded"; `switchToIndexingMode` stops llama-server and schedules nothing). **Rejected
implementation**: a queue-coupled INDEXING exit — it adds a second writer against tempdoc
737's single-writer reconciler and contradicts INDEXING's planned re-projection (737 records
the Mode enum as derivation-then-deletion). What remains of B6: (a) `/api/inference/mode`'s
`success` is a literal (`InferenceHandlers.java:382`) paired with a live mode read taken
before the async reconciler runs — replace with an honest tri-state (intent recorded /
converged / deferred-with-reason); the self-contradicting shape is pinned by
`BrainRuntimeServiceImplTest.java:99-103`, re-pinned deliberately; (b) surface truthfulness:
after install, chat is off because install ≠ enable (`AiInstallService.java:1171-1175` —
deliberate), and nothing on the surface says so. Repro is deterministic at the unit tier —
every existing reconciler/spec test pins READ_WRITE, which is exactly why the suite stayed
green while the shipped app wedged.

### D3 — B3 resized: two seams, one latent hazard, and the eslint gate is weaker than designed (U2)

- **Two patch points, not one**: `performFetch` covers the 7 `doFetch` surfaces (11 non-GET
  sites), but `host.data.invokeOperation` routes through `OperationClient`, whose
  `fetchImpl ?? globalThis.fetch` family (`OperationClient`, `ActionLedgerClient`,
  `TrustChannel`, `VirtualToolDispatcher`) is a second de-facto seam. Sweep size: **38 live
  non-GET sites** (31 bare + 7 `globalThis.fetch`) + `main.jsx:362`; 25 GET-only sites are
  unaffected. Two hand-rolled stream duplicates (`conversationListStore.ts:218`,
  `capabilities/ai.ts:39`) skip the token and should route through `consumeShapeStream` or the
  helper. `TrustChannel.ts:130-133`'s "why we avoid request()" comment is stale — its POST
  401s in prod today; convert, don't disable.
- **Latent boot hazard the helper must not inherit**: `resolveSessionTokenFromTauri` caches
  `null` permanently if the shell's 10s `session_token` wait elapses (slow cold start) — every
  mutating call for the app's lifetime then 401s with no recovery. Amendment: mark resolved
  only when a token was actually obtained or the runtime is genuinely non-Tauri. (Existing
  non-Tauri behavior is safe: no-op, header omitted, no import.)
- **The eslint flip is not the gate**: `no-restricted-globals` never sees `globalThis.fetch`
  (member expression — all 7 (e)-class sites invisible), `main.jsx` is outside the `files`
  glob, and lint is not wired into CI at all (plus 23 pre-existing unrelated errors). Gate
  design: add a `no-restricted-properties`/`no-restricted-syntax` companion for
  `globalThis.fetch`/`window.fetch`, and either wire lint into CI (clearing the 23) or accept
  that the enforced regression homes are the unit + packaged tiers. 66 warnings across 25
  files is the flip's cleanup surface; `PluginLoader.ts:119` keeps its justified disable;
  `themeState.ts`'s four default-parameter references need a decided treatment.

### D4 — B7 rescoped: the empty 400s are unexplained, not unimplemented (U6)

All three "empty-body" endpoints have written JSON bodies via `ApiErrorHandler` **since
v0.1.0** (`AiRuntimeController.java:52`, `IndexingController.java:162`,
`AiInstallController.java:129`), and a global exception fallback exists
(`LocalApiServer.java:447`). Yet two independent rounds measured zero-length 400 bodies with a
client that read 401 bodies fine. B7's first step is now a live packaged-mode repro to find
what strips the body (candidates: an after-hook interaction, the #350 admission filter, a
prod-only response path) — not a shared-error-path build-out, which already exists. The
sweep-style "no empty-body 4xx" assertion still lands, in the B4.3 packaged lane where the
strip manifests.

### D5 — Confirmations with no design change (U3, U4, U7, U8)

- **U3**: B1 blast radius is exactly as designed — three `prod=true` launch sites; both
  harnesses already pass explicit `IN_MEMORY`; one pinning test updates deliberately. Tempdoc
  246's founding rationale ("no settings.json exists in prod") is obsolete — it predates the
  desktop product being prod. The Worker's own `justsearch.prod` semantic (AOT-cache path
  selection, `WorkerSpawner.java:437`) is benign with graceful absence — recorded on the
  principle-1 audit list, no change.
- **U4**: B4.2 fits `LocalApiIntegrationTestBase` (in-process Head, arbitrary sysprops,
  `@TempDir` seeding). The `smokeSidecarBundle` task is manual-only; the CI-wired packaged
  lane is `verify-installer-nsis-win.ps1` via `build-installer.yml` — B4.3 lands there.
- **U7**: mode-conditional coverage is cheap — a `modes` field on register items, filtered by
  `sandbox-launch.py` when writing the per-round `coverage-manifest.json`; `check_coverage.py`
  unchanged.
- **U8**: no open PRs touch the campaign's files; implementation branches fresh from
  `origin/main` (`b3dfe0db`).

### D6 — B7 resolved: the empty 400 bodies were almost certainly a measurement artifact (2026-08-04)

Three tiers of evidence converge. (1) Dev-mode live probes of all three endpoints return full
`ApiErrorHandler` JSON bodies. (2) A genuinely prod-armed in-process boot (B4.2's harness — the
companion test proves 401 enforcement was live in the same filter chain) returns
`status=400 body={"error":"Missing variantId","errorCode":"VARIANT_ID_REQUIRED",...}` —
non-empty, well-formed. (3) Independently, the W5 harness work root-caused a PowerShell 5.1
client trap: on a non-2xx response, PS drains the error body into `$_.ErrorDetails.Message`,
leaving `GetResponseStream()` EMPTY — a client reading the stream measures "zero-length body"
on a response that carried one. Rounds 9 and 10 both measured through PS 5.1. The bodies have
existed since v0.1.0 (§D4); no server-side strip is reachable at any tier we can boot.

Disposition: NO product fix. The harness fix ships (collect-evidence's `Invoke-ApiRequest`
reads `ErrorDetails` first), the staged docs now name the trap, and the packaged lane's
`Send-JsonPost` (raw HttpClient, immune to the trap) asserts on real bodies — if a packaged-
only strip does exist after all, that lane is the tripwire that catches it. Round-9 F3 /
round-10 F6 close as measurement-artifact-with-tripwire rather than defect-fixed.

## §I Implementation record (2026-08-04)

Seven worker bundles, all landed and orchestrator-reviewed; commits `1125b5f1` (backend core),
`ebace6c5` (token seam + sweep), `3349a16e` (harness/coverage), `46efaf24` (banner/truthfulness),
`289fd6f4` (six findings), plus `fd39579d` (§D6). Every new test bite-proven (break → observed
failure → restore), failure lines recorded in the worker reports.

- **B1/B2/B6/B4.2** — as designed, plus two worker-discovered necessities: the contract-resolved
  model path is written back into settings on successful activation (the engine starts from
  `settings.getLlmModelPath()`, so a fallback that didn't heal the setting would activate an
  engine with no model), and `LocalApiIntegrationTestBase` now builds its store via
  `resolveMode()` instead of a hardcoded READ_WRITE (the harness would otherwise bypass the very
  axis B1 fixes). B6 chose a `state: recorded|converged` vocabulary over an `applied` boolean
  (room for `deferred`; two spellings of one fact is a fork). Layering proven in the bites: with
  B1 reverted, B4.2(a) still passes via B2; with B2 disabled, (b)/(c) still pass via B1.
- **B3** — resolver null-poisoning fixed with single-flight; `authorizedFetch` seam; both
  covering seams + all 47 direct sites converted (W2 absorbed part of the sweep; W3 finished
  it); eslint fetch rules at error in BOTH forms (identifier + member-access — the flat-config
  replace-not-merge trap was bite-proven); i18n catalogs keep a justified file-level disable
  (dependency-free foundational modules, GET-only). Two stream duplicates stayed on
  `authorizedFetch` rather than `consumeShapeStream` — deliberate: audience header + best-effort
  semantics + `check-live-channels`' declared reader pattern.
- **B5 + ride-alongs** — the advisory branch renders only when no degrading cause exists AND the
  verdict is all-info (double guard, cannot over-claim); the reindex headline scopes its causes
  to `wordCauses(codes.filter(isReindexCause))`. Worker-caught coherence follow-on:
  `BrainSurface.renderCompatibilityCallouts` filtered by `isReindexCause` and would have
  REGRESSED to a generic "restore full search" over-claim once schema left the set — now
  includes the exported `INDEX_SCHEMA_MISMATCH` constant. Known residual (recorded, not fixed):
  the Brain callout still styles the advisory state warning-toned with an x-circle. Emitter:
  `chunk_embedding.not_ready` suppressed only when `chunkDocCount == 0 && indexedDocuments == 0`
  (both conjuncts load-bearing: indexed-docs-with-zero-chunks stays not_ready; chunks-without-
  parents is an inconsistency that must not be hidden). Banner behavior change to know at
  review: under a reindex headline, non-reindex secondary causes are now omitted from the
  banner entirely (they remain on Health/CapabilityMap).
- **B7** — closed as measurement artifact (§D6).
- **B8/F2** — `pendingRegistryAdditions` on the status payload; `installedFully` stays true when
  the only missing artifacts postdate the contract; cleared after any install run.
- **F8** — root cause was deeper than the label: the substrate wire is hash-only by design
  (ADR-0028); the hash RENDERED because `resolvePathLazy` memoized a FAILED resolution as a
  permanent null — one F7-class 401 poisoned every row for the session. Fixed both: errors stay
  retryable, and `folderRowLabel()` renders basename/title-path with an honest
  "Folder (path unavailable)" fallback that never shows a bare digest.
- **F9** — design intent confirmed (rows are projected + burst-collapsed, tempdoc 550 III(b)):
  the surface now seeds from `GET /api/action-ledger` (the same `ActionLedgerProjection` the
  stream snapshot serializes — a second read, not a second authority); live rows win; a failed
  read renders "Activity unavailable" instead of a confident empty claim.
- **F10** — multi-token palette queries: whitespace-split, every token a subsequence with
  word-start bonus; single-token path untouched. Faithful-revert bite reproduced the round's
  exact symptom.
- **F12** — claim separation: `serverInfo.version` = build version (`EnvRegistry.APP_VERSION`);
  the tool-surface version (tempdoc 654's deliberate value) moved to
  `serverInfo._meta["io.justsearch/toolSurfaceVersion"]`; fingerprints swept across three docs
  + the contract test. The shipped MCPB bridge proxies the backend's initialize, so one fix
  covers the round's observation.
- **F13 — closed as ALREADY FIXED, brief refused on evidence** (the correct refusal): the
  NDJSON escaping + the exact regression test 804 §B9 asked for landed in `0c1acd32`
  (2026-07-15, in the candidate); the round's 127 broken lines all sit in a pre-upgrade
  v0.1.0 time window inside a data-dir file that survived the upgrade. The briefed 256-char
  truncation was ALSO refused: content is already bounded at 1024×16 by the governed
  search-execution-spans contract, and measurement shows document content is 1.6% of the file —
  the finding's size premise was wrong. No change shipped; any bound change needs a contract
  amendment slice.
- **F14** — Ask rung is a `jf-control` with `unavailableBecause('The local AI model is
  offline')` (sibling wording verbatim); the test asserts the reason is reachable, not inert.

Open items out of this campaign (logged as observations, not scope): `packaging/mcpb/manifest.json`
stuck at 0.1.0 (sync-version.ps1 never syncs it; fixing re-packs the bundle → separate change);
no ui-shot step covers the Activity surface; two empty 1-byte `EffectiveConfig*IntegrationTest`
files; Brain callout styling residual above.

Verification status at recording time: per-bundle suites all green in worker runs (full gradle
suite green in W1's and W7's runs; ui-web 377 files / 3901 tests; eslint 0 fetch errors / 23
pre-existing baseline; sandbox pytest 268; governance gates green except the four pre-existing
ui-web reds listed in `expected-state.v1.json` + two proven-pre-existing check failures).
Orchestrator's combined-tree forced suite + ui-shot fixtures + live browser pass recorded below
when complete.

## §V Cross-cutting verification ledger (2026-08-04, orchestrator)

1. **Full forced suite** — `./gradlew.bat test --rerun-tasks --no-build-cache` on the combined
   tree: **BUILD SUCCESSFUL in 4m 31s** (asserted on output text, run bare). First attempt
   failed on `installWebDependencies` (npm NTSTATUS 0xFFFFF030) — a stray worktree Vite
   process held `node_modules`; killed (own PID, CommandLine-verified), retry green.
2. **ui-web combined tree** — typecheck clean; **377 files / 3905 tests** green (W8's final
   run); `npx eslint src`: **0 fetch-rule errors, exactly the 23 pre-existing baseline**.
3. **Stale-dist trap caught live**: the dev runner served `build/install/ui/lib/` from Jul 31
   while `build/libs/` was fresh — the first mode-response probe returned the OLD shape;
   `installDist` refresh + restart, then **B6 verified in situ**:
   `{"state":"recorded","success":true,"mode":"offline","requested":"indexing"}`.
4. **Live browser pass** (dev stack from THIS worktree, clean data dir, Vite-served source):
   - **F14** ✓ — Ask click yields tooltip + toast "The local AI model is offline" (sibling
     treatment; silent no-op gone).
   - **F10** ✓ — palette query `install ai` surfaces Repair/Start/Cancel AI Install above the
     raw-search intent (round 10's exact failing query).
   - **F8** ✓ — indexed-folder row renders `how-to · default · 16 files · indexed just now`,
     no digest.
   - **Activity** ✓ — timeline renders live operation rows with outcomes.
   - **Enrichment transition** ✓ — `chunk_embedding.not_ready` cleared at 100% coverage;
     `chunkEmbeddingReady: true`; no reindex headline at any point.
   - **Search e2e** ✓ — "21 results · 20 matched exactly · 0.37s · meaning + words" through
     the converted `searchState` path (hybrid serving; dev-mode null-token pass-through fine).
   - **W8 residual found by this pass and fixed same-session**: post-enrichment the banner
     still claimed "Showing keyword results" from `inference.offline`'s warn severity while
     the results header simultaneously said "meaning + words". After W8: **"2 causes — AI
     features unavailable."** verified live on reload. (Deviation accepted: positive-cause
     classification on BOTH sides + unknown-conservative — a denylist-only rule would have
     re-created the class for OCR-only degradations.)
5. **Not browser-provable here** (recorded honestly): token attachment under enforcement —
   dev runs prod=false, so that chain's proof lives in the unit tier (header-attach tests),
   the B4.2 integration boot (401 без header / 400-with-body via same filter chain), and the
   B4.3 packaged lane at next installer build. The webview-under-prod full path remains
   sandbox round 11's check (registered as the `webview-performs-one-search` must-watch).
6. Registers: no search-analysis or inference-runtime obligations touched (confirmed — no
   analyzer/fusion/encoder changes in the campaign).

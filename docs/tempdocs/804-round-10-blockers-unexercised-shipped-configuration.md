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

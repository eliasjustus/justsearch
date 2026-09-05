<!-- Sidecar of docs/tempdocs/930-replace-bounded-areas-with-maintained-oss.md, per §19.3 F9's
"founder decision list" item (930 §19.3 F9(e) / F10 row-9 scope correction, 2026-09-05). This
directory is exempt from check-tempdoc-size.mjs. -->

# Invariant decision list: the 40 register-backed gates and checks

930 §19.3 F9 classified the 55 `governance/*.v1.json` registers into Group C (15, runtime/codegen
consumer, keep — out of scope here), Group B (13, consumed by a registered kernel gate), and
Group A (27, consumed only by one dedicated `check-*.mjs`). This is the founder decision list F9
called for instead of a blanket deletion: one row per Group A/B register, so each can be judged on
its own evidence. **The founder's default is KEEP ALL 40 for now** — nothing here is a change,
only a scorecard.

Membership was derived from F9's prose list (Group C's 15 names, subtracted from all 55) and
independently verified against the repo (Method section). Two counts in F9's prose undercounted by
one each on a first pass; the verified split is exactly 13 + 27 = 40, matching F9's stated totals.

## Group B — consumed by a registered kernel gate (13)

| # | register | enforcer | wired in CI? | invariant (one sentence) | incident behind it | changesets | in-edges | lines (enforcer+check+test) | suggested default |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `adr-probes` | gate `adr-coverage`, `scripts/governance/gates/adr-coverage/enforcer.mjs` | Yes — `ci.yml:397` | An ADR's `Covers` paths must resolve and its premise `probes:` must still hold (`enforcer.mjs:5-13`). | None found — pass2 §13.1: "documentation hygiene, not a code invariant," not in the commodity-carrier list. | 1 | in-edge from `consult-register` (Group C) | 570+0+576=1146 | DEFER — in-edge is a one-line doc cross-reference in `consult-register`, not code coupling; also chains into the execution-surfaces cluster via its own `contract-surfaces`/`registry` references. |
| 2 | `ambient-facets` | gate `ambient-purity`, `scripts/governance/gates/ambient-purity/enforcer.mjs` (detect reused from `scripts/ci/check-ambient-purity.mjs`) | Yes — recipe (`consult-register.v1.json:39`) run via `ci.yml:218` | Class-B ambient facets (scrollbar/selection/placeholder/spin) live only in the authority sheet; every shell-v0 component extends `JfElement` (`check-ambient-purity.mjs:3-11`). | tempdoc 574 §15/§16: prevents recurrence of "the per-window-fork / false-global class" (`enforcer.mjs:3-6`). | 0 | none | 28+128+0=156 | KEEP — incident-backed recurrence guard. |
| 3 | `atom-facets` | gate `atom-fork-ratchet`, `scripts/governance/gates/atom-fork-ratchet/enforcer.mjs` (detect reused from `scripts/ci/check-atom-fork-ratchet.mjs`) | Yes — recipe, `ci.yml:218` | A new raw `.badge`/`.chip`/`.pill`/`.tag`/`.status-dot`/`.outcome-tag` CSS rule outside the `@atom` authority fails the shrinking-baseline ratchet (`check-atom-fork-ratchet.mjs:13-19`). | None found — design says a hard ban was "too noisy" (~60/40 true/false), not a caught regression. | 1 | none | 45+155+0=200 | KEEP-AS-TEST — the regex `detect()` + per-file baseline diff could be a ~20-line script test without the kernel's truth-table/self-test scaffolding. |
| 4 | `contract-surfaces` | gate `contract-projection`, `scripts/governance/gates/contract-projection/enforcer.mjs` | Yes — `ci.yml:313` | Every migrated wire record is the single generated projection with no hand copy (`enforcer.mjs:3-6`). | None found — meta-coordinator description borrows the execution-surface pattern, no incident of its own. | 0 | in-edges from `adr-probes` and `contribution-surfaces` | 168+0+0=168 | DEFER — part of the execution-surfaces cluster (see below); dropping it breaks two referencers. |
| 5 | `contribution-surfaces` | gate `contribution-surface`, `scripts/governance/gates/contribution-surface/enforcer.mjs` | Yes — `ci.yml:313` | The registry FE type barrel is a re-export/derivation surface over generated wire types, never a second shape authority (`enforcer.mjs:5-9`). | tempdoc 560 §4c: the "hand-mirror drift class" this gate was built to foreclose. | 0 | none (it is itself a source into `contract-surfaces`) | 309+0+284=593 | KEEP — incident-backed (§4c drift class). |
| 6 | `declaration-kinds` | gate `runtime-witness`, `scripts/governance/gates/runtime-witness/enforcer.mjs` | Yes — `ci.yml:852` | Declared AGENT-audience consumers must match the witnessed delivered-operation set, bidirectionally (`enforcer.mjs:1-14`). | None found — ADR-0042 frames this as closing a design risk ("DR-D"), not a reproduced defect. | 0 | none | 215+0+0=215 | KEEP-AS-TEST — a single bidirectional-set-diff assertion over the snapshot artifact, no kernel wrapper needed. |
| 7 | `execution-surfaces` | gate `execution-surface`, `scripts/governance/gates/execution-surface/enforcer.mjs` | Yes — `ci.yml:368` | Only registered classes may reference `SearchTrace` / project `IndexingJobView` (pass2 §13.1 table row). | **Yes** — `agent-postmortems.md:96` (`subset-isnt-the-suite`, tempdoc 618 §10c): an extracted file became an unregistered `SearchTrace` referencer, invisible until the full kernel ran. Also pass2 §13.1: "553 drift, #614 red." | 0 | in-edges from `contract-surfaces`, `live-channels`, `overlay-positioning-classes` (+1 more per addendum's "four in-edges" count) | 229+0+0=229 | KEEP — the one gate with a documented live catch. |
| 8 | `interaction-surfaces` | gate `interaction-surface`, `scripts/governance/gates/interaction-surface/enforcer.mjs` | Yes — `ci.yml:313` | Exactly one visible USER-audience interaction surface exists, and every core direct-LLM shape routes into it (`enforcer.mjs:3-6`). | tempdoc 561: "a second visible interaction surface (the standalone `core.agent-surface` alongside `core.unified-chat-surface`) — two authorities for one concept" (`enforcer.mjs:6-8`). | 0 | none | 251+0+230=481 | KEEP — incident-backed (561 two-authority defect). |
| 9 | `modals` | gate `modal-arbitration`, `scripts/governance/gates/modal-arbitration/enforcer.mjs` (detect reused from `scripts/ci/check-modal-arbitration.mjs`) | Yes — recipe, `ci.yml:218` | Every modal host composes the one `ModalController` primitive so open/close bind focus-trap + inert + Top Layer + scroll-lock atomically (`check-modal-arbitration.mjs:3-8`). | None found — closes a completeness gap in a sibling gate (`check-modality-contract`), not a reproduced bug. | 0 | none | 23+65+0=88 | KEEP-AS-TEST — positive-coverage "adopter composes symbol X" check, same shape as a Java ArchUnit rule. |
| 10 | `operation-surfaces` | gate `operation-surface`, `scripts/governance/gates/operation-surface/enforcer.mjs` | Yes — `ci.yml:368` | Only registered classes may reference `IndexingJobView` (sibling of execution-surface; pass2 §13.1 table row). | pass2 §13.1: bundled with execution-surface as "553 drift, #614 red." | 0 | none | 254+0+232=486 | KEEP — shares the one documented incident class with execution-surfaces. |
| 11 | `runtime-state` | gate `runtime-state`, `scripts/governance/gates/runtime-state/enforcer.mjs` | Yes — `ci.yml:313` | Every surface describing/consuming the AI-runtime authority (`RuntimeStatus`/`RuntimeSpec`/`RuntimeGpuLease`/`RuntimeReconciler`) is a declared projection/consumer (`enforcer.mjs:3-8`). | None found — "applies the execution-surface pattern" proactively (tempdoc 737 §12c); no incident of its own. | 2 | mutual with `ai-verdict-derivation` | 134+0+0=134 | DEFER — mutually coupled with `ai-verdict-derivation` (KEEP, incident-backed); dropping this side alone breaks that reference. |
| 12 | `surface-altitude` | gate `surface-altitude`, `scripts/governance/gates/surface-altitude/enforcer.mjs` | Yes — `ci.yml:313` | A surface's altitude (DIAGNOSTIC/TRUST/TOOL/PRODUCT) is derived from the authorities it consumes, never a hand-set field (`enforcer.mjs:3-13`). | None found. | 0 | none | 242+0+0=242 | KEEP-AS-TEST — the derivation table is a pure function; a unit test over fixture catalogs covers it without the kernel wrapper. |
| 13 | `transients` | gate `transient-arbitration`, `scripts/governance/gates/transient-arbitration/enforcer.mjs` (detect reused from `scripts/ci/check-transient-arbitration.mjs`) | Yes — recipe, `ci.yml:218` | Every transient overlay composes the one `TransientController` primitive so single-open-per-layer is bound by construction (`check-transient-arbitration.mjs:4-9`). | None found — completeness rung, same shape as `modals`. | 0 | none | 24+64+0=88 | KEEP-AS-TEST — same shape as row 9. |

## Group A — consumed only by one dedicated `check-*.mjs` (27)

| # | register | enforcer (check script) | wired in CI? | invariant (one sentence) | incident behind it | changesets | in-edges | lines (check+test) | suggested default |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `adaptive-regions` | `scripts/ci/check-adaptive-closure.mjs` | Yes — recipe, `ci.yml:218` | A registered chrome region must overflow declared-priority items into a "…" affordance instead of clipping them (`check-adaptive-closure.mjs:3-10`). | None found in the header. | n/a | mutual with `composition-surfaces` | 137+0=137 | DEFER — delete only with `composition-surfaces` (mutual pair). |
| 2 | `ai-verdict-derivation` | `scripts/ci/check-ai-verdict-derivation.mjs` | Yes — recipe, `ci.yml:218` | `BrainSurface`'s AI-state derivation is the one `computeAiEngineVerdict` seam, not a hand-ordered precedence ladder (`check-ai-verdict-derivation.mjs:3-9`). | tempdoc 663: `deriveAiState()` used to reconcile "~5 overlapping state representations" before collapse. | n/a | mutual with `runtime-state` (Group B) | 85+0=85 | KEEP — incident-backed (real prior fork); note the mutual coupling with `runtime-state` if this is ever revisited. |
| 3 | `capability-availability-surfaces` | `scripts/ci/check-capability-availability.mjs` | Yes — recipe, `ci.yml:218` | A surface showing an AI-affordance's availability must project it from the one `projectAvailability` authority, not hardcode wording from raw booleans (`check-capability-availability.mjs:3-8`). | tempdoc 613 §10: "the prior CapabilityPills' 'Chat unavailable'/'Embedding blocked' diverged from the Health map's canonical reason." | n/a | none | 92+82=174 | KEEP — incident-backed (observed divergence). |
| 4 | `chip-facts` | `scripts/ci/check-chip-fact-authority.mjs` | **No** — no `ci.yml` or `run-ui-web-gates.mjs` recipe hit; only cited as "Gate:" in `docs/explanation/27-frontend-presentation-kernel.md:72` | A chip asserting a runtime/build fact must use a `projectFact`-ref, not a baked `label` literal (`check-chip-fact-authority.mjs:3-9`). | None found in the header (tempdoc 594 Move 2, a design consolidation). | n/a | none | 68+0=68 | DROP-CANDIDATE — also flags a separate finding: the canonical doc calls this "the gate" while nothing runs it; either wire it or drop the doc's claim in the same PR. |
| 5 | `composition-surfaces` | `scripts/ci/check-composition-surfaces.mjs` | Yes — recipe, `ci.yml:218` | A multi-zone grid layout is generated from a declared zone-set by the one `composeGridStyles` primitive, not hand-authored (`check-composition-surfaces.mjs:3-9`). | tempdoc 565 §13: "the original bug: Logs both embedded in Health AND a stray rail icon." | n/a | mutual with `adaptive-regions`; in-edge from `declared-surfaces` | 61+0=61 | DEFER — mutual pair with `adaptive-regions`; also referenced by `declared-surfaces`. Despite the real incident cited, it cannot be dropped alone without editing both. |
| 6 | `consequence-classification` | `scripts/ci/check-consequence-classification.mjs` | Yes — recipe, `ci.yml:218` | A surface claiming what a degradation did to search must derive that claim from the one `classifyConsequence` classifier (`check-consequence-classification.mjs:3-9`). | tempdoc 805 §G.2: "Round 11 measured that fork in TWO copies at once (the search banner and `availability.ts`'s affordance caveat)," both wrong while the trace showed dense+cross-encoder execution. | n/a | none | 160+165=325 | KEEP — incident-backed, live-measured fork. |
| 7 | `declared-surfaces` | `scripts/ci/check-declared-surfaces.mjs` | Yes — recipe, `ci.yml:218` | Settings/Library/Help/Health render their regions through the `DeclaredSurface` engine, not hand-painted Lit (`check-declared-surfaces.mjs:3-11`). | None found — the header frames it as a hypothetical ("nothing stops a future agent reverting…"), not a reproduced instance. | n/a | source only (feeds `composition-surfaces`) | 128+0=128 | KEEP-AS-TEST — positive-coverage catalog check, portable to an ArchUnit-style rule. |
| 8 | `folder-status-derivation` | `scripts/ci/check-folder-status-derivation.mjs` | Yes — recipe, `ci.yml:218` | Per-folder status derives from job drain (`inFlightCount`/`failedCount`) via the one `folderStatus` seam, not the walk-completion timestamp (`check-folder-status-derivation.mjs:3-9`). | tempdoc 599 §8.1: "a folder showed '✓ indexed' derived from the walk-completion timestamp, NOT from job drain." | n/a | none | 84+0=84 | KEEP — incident-backed. |
| 9 | `inflight-liveness-projections` | `scripts/ci/check-inflight-liveness.mjs` | Yes — recipe, `ci.yml:218` | Every in-flight-running projection derives liveness from the one `isInFlightLive` authority, not a per-surface reimplementation (`check-inflight-liveness.mjs:3-8`). | None found in the header (framed as "cannot be silently re-implemented," not a reproduced phantom). | n/a | none | 114+0=114 | KEEP-AS-TEST — positive/negative coverage scan, portable to a smaller unit test. |
| 10 | `intent-tier-coverage` | `scripts/ci/check-intent-tier-coverage.mjs` | **No** — F9 routing finding names it explicitly ("wired in no workflow"); confirmed, zero hits in `.github/workflows/*.yml` | Every LLM intent tier maps to exactly one registered conversation shape, projected from the Java shape catalog (`check-intent-tier-coverage.mjs:3-9`). | None found. | n/a | none | 160+0=160 | DROP-CANDIDATE. |
| 11 | `language-agnostic-analysis` | `scripts/ci/check-language-agnostic-analysis.mjs` | Yes — direct, `ci.yml:122` | No per-language analyzer, `content_<lang>` field, or non-empty per-language synonym/dictionary file may exist; analysis stays locale-invariant (`check-language-agnostic-analysis.mjs:3-12`). | CLAUDE.md hard invariant 6 (`rule:language-agnostic-analysis`) — not an incident, a standing product constraint. | n/a | none | 138+0=138 | KEEP — CLAUDE.md hard invariant. |
| 12 | `live-channels` | `scripts/ci/check-live-channels.mjs` | Yes — recipe, `ci.yml:218` | Every always-on connection opener is a declared, budget-counted adopter of the one multiplexed-channel primitive (`check-live-channels.mjs:3-11`). | tempdoc 649/662: "the shell's 5 historically always-on SSE streams routinely saturated [the ~6-connection] pool and starved the cheap status polls under load." | n/a | source only (feeds `execution-surfaces`) | 175+0=175 | KEEP — incident-backed (649 real saturation). |
| 13 | `live-witness` | `scripts/ci/check-live-witness.mjs` | **No** — zero hits outside its own source, `governance/live-witness.v1.json`, ADR-0042, and tempdocs | Keeps the live-registry witness authority (a backend live-registry test, not this script) from being silently deleted or forked (`check-live-witness.mjs:2-11`). | None found — ADR-0042 frames it as closing a design risk, not a reproduced defect. The actual live-registry enforcement is a Java test outside this register's scope; dropping this script does not remove that test. | n/a | none | 77+0=77 | DROP-CANDIDATE — F9 calls it a "declared invariant, not sync artefact" alongside `language-agnostic-analysis`/`readiness-reason-codes`; flagged anyway because it fails all three DROP-CANDIDATE tests literally — founder judgment call. |
| 14 | `message-classes` | `scripts/ci/check-message-classes.mjs` | Yes — recipe, `ci.yml:218` | Every `emitEphemeralToast` classId is a declared `LOCAL_MESSAGE_CLASSES` member, checked both directions (`check-message-classes.mjs:3-9`). | None found in the header. | n/a | none | 130+99=229 | KEEP-AS-TEST — a closed-enum correspondence check, portable to a smaller test. |
| 15 | `overlay-positioning-classes` | `scripts/ci/check-layout-purity.mjs` | Yes — recipe, `ci.yml:218` | Every `jf-*-surface` element's layout flows from the one `SurfaceLayout` authority (composes `surfaceLayoutStyles` or is `display: contents`) (`check-layout-purity.mjs:3-9`). | None found in the excerpt — "the collapse landed" implies a prior consolidation, not a specific reproduced bug cited by name. | n/a | source only (feeds `execution-surfaces`) | 105+0=105 | KEEP-AS-TEST. |
| 16 | `platform-lifecycle` | `scripts/ci/check-platform-lifecycle.mjs` | Partial — `ci.yml:160,165` runs it, but `165` is `--mode report` (advisory), never gates the build | Vendor/runtime lifecycle pins resolve to exactly one adapter per source and fail closed on structural/schema errors; lifecycle findings are advisory only in report mode (`check-platform-lifecycle.mjs:1-11`). | None found. | n/a | none | 538+271=809 | KEEP-AS-TEST — the fail-closed pin-resolution half is the load-bearing part; the vendor-policy evaluation half (bulk of 538 lines) is advisory today and could shrink. |
| 17 | `readiness-reason-codes` | `scripts/ci/check-readiness-reason-codes.mjs` | Yes — direct, `ci.yml:500` | The degradation-cause vocabulary is one closed authority: `LifecycleReasonCode` → `CAUSE_ROWS` wording, checked both directions (`check-readiness-reason-codes.mjs:3-11`). | tempdoc 600 Part IX/X: "reproduced live for `gpu.saturated`" — an unworded code rendered `Degraded: <code>` to the user. | n/a | in-edge from `search-degradation-reason-codes` | 311+259=570 | KEEP — incident-backed, reproduced live. |
| 18 | `realized-capability-surfaces` | `scripts/ci/check-realized-capability.mjs` | Yes — recipe, `ci.yml:218` | A surface displaying per-engine realized state must read the projected `aiState.realized.*` field via `computeRealized`, not raw `worker.gpu.*` (`check-realized-capability.mjs:3-9`). | None found by name — references the general "representation-drift class, 553" without citing a specific reproduced instance for this surface. | n/a | none | 92+82=174 | KEEP-AS-TEST. |
| 19 | `run-renderers` | `scripts/ci/check-run-renderers.mjs` | Yes — recipe, `ci.yml:218` | The agent run renders through one ordered projection and one `<jf-tool-call-card>` primitive (`check-run-renderers.mjs:3-9`). | tempdoc 565 §12.6: "Before §12 it rendered through TWO authorities — the live `ToolCallCard` and a static record-half label — assembled by a dedup of two structures." | n/a | none | 244+0=244 | KEEP — incident-backed (real two-authority fork). |
| 20 | `search-degradation-reason-codes` | `scripts/ci/check-search-degradation-reason-codes.mjs` | **No** — zero hits outside its own source and docs | Each `vocabularies` entry pairs one Java producer enum with one FE wording table (search-side sibling of `readiness-reason-codes`) (`check-search-degradation-reason-codes.mjs:3-10`). | None found by name (extended by "register F-052," a cross-reference, not a described defect in this file). | n/a | source only (feeds `readiness-reason-codes`) | 191+0=191 | DROP-CANDIDATE — but review together with `readiness-reason-codes` (KEEP) given the sibling-vocabulary relationship, even though no code coupling forces it. |
| 21 | `search-issuance` | `scripts/ci/check-search-issuance.mjs` | Yes — recipe, `ci.yml:218` | Every search query issues through the one `buildSearchIntent`/`runSearch` seam (`check-search-issuance.mjs:3-9`). | tempdoc 577 §1.8 de-risk: "the divergent body-shaping paths…found (keystroke POST vs the querySyntax-carrying API client vs pinned-chip restore)." | n/a | none | 90+0=90 | KEEP — incident-backed (de-risk found real divergence). |
| 22 | `steering-surfaces` | `scripts/ci/check-steering-arbitration.mjs` | Yes — recipe, `ci.yml:218` | Every run-control affordance (initiate/set-posture/interject/halt) dispatches through the one `dispatchRunControl` seam (`check-steering-arbitration.mjs:3-9`). | None found in the header. | n/a | none | 125+0=125 | KEEP-AS-TEST. |
| 23 | `store-corruption-policies` | `scripts/ci/check-store-recoverability.mjs` (shared with Group C `store-recoverability`) | Yes — direct, `ci.yml:242,245` | Every Shell/Head/Worker/external/derived/ephemeral state owner participating in an in-place upgrade is a governed `durableStores` entry (`check-store-recoverability.mjs:3-7`). | None found by name for this register specifically; the shared script exists for `store-recoverability` (Group C). | n/a | none listed, but shares its enforcer with a kept Group-C register | 791+828=1619 | DEFER — F9: "shared with Group C registers and cannot go" — dropping this pair would remove `store-recoverability`'s (kept) enforcement too. |
| 24 | `surface-composition` | `scripts/ci/check-surface-composition.mjs` | Yes — recipe, `ci.yml:218`; also invoked by the `surface-catalog-parity` recipe (`consult-register.v1.json:27`) | A host surface's declared member surfaces are its single home-authority (Leg 1), and its `CoreSurfaceCatalog.java`/`CorePlugin.ts` declarations must agree (Leg 2, tempdoc 852 S0) (`check-surface-composition.mjs:3-11`). | tempdoc 571 §11/578: "the original bug: Logs both embedded in Health AND a stray rail icon." | n/a | none | 314+317=631 | KEEP — incident-backed, and double-wired (two recipes cite it). |
| 25 | `surface-task-state` | `scripts/ci/check-surface-task-state-retention.mjs` | Yes — recipe, `ci.yml:218` | A view's `connectedCallback`/`disconnectedCallback` must never destroy a declared recoverable-store; clearing is intent-driven only (`check-surface-task-state-retention.mjs:3-9`). | tempdoc 609 Phase 6: "`SearchSurface.disconnectedCallback` called `setQuery('')`…`UnifiedChatView.connectedCallback` called `resetUnifiedChatState()`, wiping the draft." | n/a | none | 146+0=146 | KEEP — incident-backed (two concrete found defects). |
| 26 | `ui-step-coverage` | `scripts/ci/check-ui-step-coverage.mjs` | Yes — direct, `ci.yml:168` | Every `ui-shot`/`ui-check` step index entry resolves to a real, live component (`check-ui-step-coverage.mjs:3-13`). | tempdoc 615 §6.1a: the step index "silently kept mapping the retired React stack…which no longer exists) while the live UI is Lit shell-v0." | n/a | none | 158+0=158 | KEEP — incident-backed (verifier pointed at deleted code). |
| 27 | `verdict-derivation` | `scripts/ci/check-verdict-derivation.mjs` | Yes — recipe, `ci.yml:218` | The system-health verdict is one derived `SystemHealthVerdict` on `aiStateStore`, consumed by header/footer/status-bar (`check-verdict-derivation.mjs:3-9`). | tempdoc 595 §4.2: "computed in ≥4 places over readiness…collapsed that into ONE derived" value. | n/a | none | 91+0=91 | KEEP — incident-backed (real ≥4-place fork). |

## Delete-as-a-set groups

These are not rows to decide individually — dropping one side without the other (or without the
cluster) breaks a live reference:

1. **`adaptive-regions` ↔ `composition-surfaces`** (mutual). Both DEFER above.
2. **`ai-verdict-derivation` ↔ `runtime-state`** (mutual, cross-group: Group A ↔ Group B). Only
   `ai-verdict-derivation` is independently incident-backed; `runtime-state` rides on the coupling.
3. **`status-facts` ↔ `ui-proportion-baseline`** (mutual). Both are Group C (out of scope for this
   list) — noted here only because the addendum's three mutual pairs would otherwise be
   incomplete.
4. **The execution-surfaces cluster**: `execution-surfaces` (KEEP, the one documented catch) ←
   `contract-surfaces` (DEFER) ← `contribution-surfaces` (KEEP) and `adr-probes` (DEFER); ←
   `live-channels` (KEEP); ← `overlay-positioning-classes` (KEEP-AS-TEST). Six registers across
   both groups, three of them registered kernel gates (`execution-surface`, `contract-projection`,
   `contribution-surface`) plus `adr-coverage`. Dropping any upstream member requires editing the
   others' references even though `execution-surfaces` itself is being kept.
5. **`readiness-reason-codes` (KEEP) ← `search-degradation-reason-codes` (DROP-CANDIDATE)** — not
   mutual, so `search-degradation-reason-codes` can be dropped alone; review together first since
   they are sibling vocabularies by design (tempdoc 600 Part IX).
6. **`store-corruption-policies` (DEFER) shares its enforcer script with kept Group-C
   `store-recoverability`** — an enforcer-sharing constraint, not a register→register edge, but the
   same "cannot go alone" shape.

## How to use this list

The founder marks one bucket per row (KEEP / KEEP-AS-TEST / DROP-CANDIDATE / DEFER-resolved) at
whatever pace suits; nothing here changes behavior on its own. A row marked DROP is executed as its
own `retire-with-a-sweep` PR: delete the register + its enforcer/check (+ test), remove its
`--gate`/recipe wiring, and edit every in-edge that named it (the delete-as-a-set groups above are
the ones where that sweep spans more than one register). KEEP-AS-TEST rows are only executed when
someone is touching that gate anyway — replace the kernel wrapper with the named smaller test in
the same PR, not as a separate migration. A DEFER row is not actionable until its group is decided
together.

## Method

Commands run to derive and verify this list (2026-09-05, from
`.claude/worktrees/agent-acb23558524fc37dc`, based on `worktree-930-oss-stop`):

- Register inventory: `find governance -maxdepth 1 -name "*.v1.json" -printf "%f\n" | sort` → 55.
- Group C exclusion set: the 15 names listed verbatim in
  `docs/tempdocs/930-evidence/derisk-findings.md:89-96` (F9), subtracted from the 55 to get the 40
  Group A/B candidates.
- Gate roster: `python3 -c "import json; d=json.load(open('governance/registry.v1.json')); ..."`
  over `d['gates']` → 35 ids + enforcer paths.
- Register→gate consumption: `for f in $(ls governance/*.v1.json | xargs -n1 basename); do grep -rl
  "$f" scripts/governance/gates/; done` (source-literal hits) plus a regex pass over
  `governance/registry.v1.json`'s `gate.config` fields for `governance/([a-z0-9-]+)\.v1\.json`
  (catches `ambient-purity`'s config-only reference that source grep misses).
- Register→check consumption: the same loop against `scripts/ci/` (excludes `_fixtures`).
- False-positive check: `check-ui-baseline-schemas.mjs` name-drops `declaration-kinds`,
  `sandbox-defect-classes`, `design-reference`, `registry`, `agent-hooks` in a doc comment
  (`:19-21`) without validating them — `ENFORCED` (`:36`) is only the two baselines; excluded from
  consumption.
- Fold detection: gate enforcers whose header says "Folds the standalone `scripts/ci/check-*.mjs`"
  and whose body does `import { detect } from '.../check-*.mjs'` (`ambient-purity`,
  `atom-fork-ratchet`, `modal-arbitration`, `transient-arbitration` enforcers) — the standalone
  script is the real detector, the gate is a kernel wrapper; both line counts are added.
- CI wiring: `grep -n -- "--gate " .github/workflows/ci.yml` for kernel gates; per-script
  `grep -rn "check-<name>" .github/workflows/*.yml` for Group A checks; the `ui-web-gates` recipe
  (`governance/consult-register.v1.json:32-43`) and its parser/runner
  (`scripts/ci/run-ui-web-gates.mjs`, wired at `ci.yml:218`) for the 18 recipe-covered Group A
  registers.
- Unwired confirmation: `grep -rln "check-<name>" --include=*.mjs --include=*.json --include=*.yml
  --include=*.md .` (excluding `_fixtures`) returning only the script's own file (or only doc/tempdoc
  prose) for `chip-facts`, `intent-tier-coverage`, `live-witness`, `search-degradation-reason-codes`.
- Changesets: `ls gates/<id>/.changesets | grep -c '\.md$'` per Group B gate id; `n/a` for Group A
  (no `gates/<id>` directory exists for a check-only register).
- Lines: `wc -l` on each enforcer/check/test file named in the row.
- Incidents: read each enforcer/check header (`sed -n '1,12p'`) for a named tempdoc/defect
  narrative, then `grep -n -iE "found|reproduced|defect|bug|diverged|the original|silently"` over
  the same files for anything past line 12; cross-checked against
  `docs/reference/contributing/agent-postmortems.md` (only one hit: `execution-surfaces`, line 96)
  and `docs/tempdocs/930-evidence/pass2-stop-doing.md:28-41` (the "Lost under stop" incident table).
- In-edges: taken verbatim from the addendum edge list in the task brief; not independently
  re-derived (the brief states these were found by a prior grep pass over all 55 registers' cross-
  references, which this pass did not repeat).

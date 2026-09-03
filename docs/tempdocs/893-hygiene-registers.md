---
title: "Hygiene authorities and auditable projections"
type: tempdocs
status: IMPLEMENTED (2026-09-03) — local and live-Head verification green; hosted-CI proof pending
created: 2026-09-02
updated: 2026-09-03
lane: 887 L14
model: opus (takeover)
parent: 887-improvement-landscape-register
related:
  - 792-stack-currency-audit-round-3   # §25 predicted "a record that never fires becomes decoration"
  - 583-localapiserver-structural-remedy  # §D.3c OpenAPI export; "per-route schema authority is a separate charter"
  - 530-class-size-ratchet-automation  # ratchet kernel; todo-fixme gate
  - 378-workaround-inventory           # stale since 2026-04; close it here
  - 509-operation-label-coherence      # operation labels only; F-22/F-25 naming collisions open
  - 754 / 799 (config-surface gate)    # the closest thing to a flag registry
  - 532-virtual-operation-catalog-ship-or-retract  # open since 2026-05 — owner decision, referenced by item 6
---

# 893 — Hygiene authorities and auditable projections

## Briefing for the agent picking this up

The takeover, research, and replacement design are in §T/§D. Do not implement the original charter
below: it is retained only to show what the investigation tested and rejected. Implementation should
use §D and the derisk record as its authority, stay split into independently verifiable slices, and load
the repository's domain skill before touching governance baselines or canonical docs.

## Original charter (rejected; retained for provenance)

The following was the 2026-09-02 proposal. §T proves why it must not be followed literally, and §D
supersedes its design decisions.

1. **Platform EOL register.** `governance/platform-eol.v1.json`: one row per pinned runtime
   dependency — JDK (25, LTS window), Gradle, Lucene major, Tauri, WebView2 policy, CUDA
   toolkit/driver floor, ONNX Runtime, Node, llama.cpp pin, Tesseract — with `supportedUntil`
   (ISO date or `null` + `source` URL) and the pin's source file. `scripts/ci/check-platform-eol.mjs`
   warns at 90 days, fails at 0 (gate mode), exits 0 in report mode; wire report mode into
   `ci.yml` as advisory. Seed dates from 792 §1 and vendor pages; cite each.
2. **OpenAPI snapshot.** `GET /api/meta/openapi.json` (`OpenApiController.java:20-33`) is
   composed at runtime and never committed. Add a unit-level exporter (same composition, no
   server) writing `contracts/http/openapi.snapshot.json`, plus `check-openapi-snapshot.mjs`
   that regenerates and diffs (idempotency gate, like the `check-*-regen` family); wire into CI.
   Do **not** attempt per-route request/response schemas (583 named that a separate charter).
3. **TODO ratchet scope + 378.** Extend `gates/todo-fixme` `sourceGlobs` to test sources,
   `scripts/**/*.{mjs,cjs,py,ps1}`, and `modules/shell/src-tauri/**/*.rs`; rebaseline (expect
   ~36 markers) via a changeset under `gates/todo-fixme/.changesets/`. Then close
   `378-workaround-inventory`: verify each of its "17 active" against `main`, mark
   resolved/still-present with `file:line`, set `status: closed (superseded by todo-fixme ratchet
   + 887 §S)`.
4. **Codebase-health series.** Decision: **artifact, not commits** — mirror
   `.github/workflows/ci-walltime-trend.yml`: a dispatch/scheduled job that runs the kernel gates
   in report mode, collects every `gates/*/baseline.txt` current value plus module count, test
   file count, and `cloc`-style LOC per module into `health-series.ndjson`, uploads it as an
   artifact, and appends to a rolling artifact. No complexity tool is added (PMD's `CyclomaticComplexity`
   rule in report mode is enough — include its per-module count).
5. **Product glossary.** `docs/reference/glossary.md`: Head/Body/Brain, run/operation/job,
   passage/chunk/document, leg/lane/stage, surface/window/rail, collection/root, spec/status,
   grant/consent tier — each with the *authoritative* definition site (`file:line` or doc §) and
   the UI label if it differs. Resolve or explicitly list 509's F-22 (multiple Ask entry points)
   and F-25 (Simple/Advanced naming). Link from `docs/llms.txt`; regenerate via
   `node scripts/docs/llmstxt-generate.mjs`. No gate — `writing-docs-for-ai.md:63` prefers
   inline definitions; the glossary is the index of where they live.
6. **Feature-flag policy draft** (doc only, in this tempdoc §F): what distinguishes an
   experimental knob from a permanent one; required fields (owner, introduced-in, expiry or
   promotion criterion); where it is declared (proposal: a `stage` attribute on
   `config-surface`'s matrix rows); the retirement gate. Reference 532 as the first case the
   policy would have resolved. The founder decides; you do not implement.
7. **Canonical-doc claim sweep** (added 2026-09-02; the lens 887 offered and never itemised).
   For every file under `docs/explanation`, `docs/reference`, `docs/how-to`: extract sentences
   that assert a mechanism exists or a behaviour holds ("X is enforced by Y", "Z runs in CI",
   "the walk applies …"), and verify each against `main` with a `file:line` or a run. Output: a
   table in this tempdoc §S of stale claims with the one-line fix, then apply the fixes in one
   docs PR (load `/docs-maintenance`). Known instances to seed the sweep: `03-knowledge-server.md:283`
   (half-stale exclude claim, 889), `05-ai-architecture.md:83-85` (CUDA "deferred", 887 §Z-2),
   `CLAUDE.md` "Build fails on PMD" (888), `check-ui-step-coverage.mjs` header (888),
   `ui-a11y-baseline.v1.json` description (888), `297:26`, `ApiSecurityFilters.java:186`. Do
   not build a doctest mechanism (non-goal); the sweep is a one-time audit plus a short
   "how to keep claims checkable" paragraph in `writing-docs-for-ai.md` pointing at the
   existing drift guards (`check-privacy-claims`, `check-frontend-stack-claims`).

## Original acceptance criteria (superseded)

- Items 1-2: new check + test pass; CI step visible in a PR run; `check-workflow-triggers` green.
- Item 3: `node scripts/governance/run.mjs --gate todo-fixme --mode gate` green at the new
  baseline; 378 frontmatter closed with per-item evidence.
- Item 4: one artifact produced by a dispatch run; link it in §Status.
- Item 5: `node scripts/docs/verify-canonical-doc-links.mjs` green; every glossary row has a
  pointer.
- Repo: `./gradlew.bat build -x test` (item 2 touches `modules/ui` tests).

## Constraints

- Do not add rules to `CLAUDE.md` or `.claude/rules/` (always-loaded budget ratchet).
- Non-goals: bumping any dependency (792 §3-4 is a separate execution), JPMS / encapsulation
  (lane 900), executable doc snippets.

## Status

Takeover complete 2026-09-03. The original seven-item charter must not be implemented as written.
Research, replacement design, and derisk record follow.

## T. Takeover investigation (2026-09-03)

### T.1 Verdict

**NO-GO on the current charter; GO now on a corrected design.** The underlying hygiene problems are
real, but four load-bearing premises changed or were wrong:

1. A universal date-valued `supportedUntil` cannot represent this stack's lifecycle policies.
2. The proposed codebase-health series duplicates the shipped governance history/dashboard substrate
   and its scheduled trigger conflicts with ADR-0026 unless that decision is explicitly amended.
3. Expanding the TODO ratchet's globs without changing its measurement semantics counts the gate's own
   vocabulary and test fixtures as debt.
4. A committed OpenAPI document derived offline from the existing route snapshot would only prove
   snapshot-to-snapshot coherence; that route snapshot predates many route changes and its current gate
   explicitly does not prove fidelity to the live router.

The cheapest evidence was already available and was collected before design:

| Proposed item | Cheapest validating / invalidating evidence | Result on 2026-09-03 |
|---|---|---|
| Platform EOL | Compare each vendor's official lifecycle shape with the proposed schema | Refuted universal date semantics. Temurin/Node publish dates; Gradle is event-relative; WebView2/Tauri are rolling; Lucene/ORT/llama.cpp/Tesseract publish no comparable end date. |
| OpenAPI snapshot | Run the existing offline client regen gate and inspect the route snapshot's age against route commits | `check-api-client-regen` is green only for client↔snapshot coherence. The 201-route snapshot was last changed 2026-08-13; many route commits followed. |
| TODO scope | Count the exact proposed source families with the current token matcher | 133 occurrences in 18 files, including 49 occurrences in the TODO gate's own implementation/wording and 20 in a ratchet test. This is not the charter's predicted ~36-comment debt corpus. |
| Codebase-health series | Search for an existing history store and dashboard | Already shipped: `scripts/governance/lib/history.mjs`, `dashboard.mjs`, `tmp/governance-history.ndjson`, and `docs/reference/governance-state.md`. PMD has no `CyclomaticComplexity` rule configured. |
| Glossary | Locate existing definition authorities and F-22/F-25 UI strings | Need remains real; the glossary must index authorities rather than redefine them. F-22/F-25 are still product decisions, not writing cleanup. |
| Flag policy | Inspect `config-surface` and the 532 case | Need remains real. `config-surface` counts and checks readers but has no lifecycle stage/owner/expiry semantics; 532 is still `status: open`, while the live virtual-operation path still says it has no default `vop_*` consumer. |
| Claim sweep | Size the canonical corpus and re-check the seeded claims | 107 Markdown files / 24,570 lines makes an unstructured “every assertion” promise non-verifiable. Seed checks do find real drift: the CUDA posture, PMD claim, a non-reading GET example, and the a11y baseline's nonexistent TS consumer. |

### T.2 Research record

Internet research was warranted only for the externally changing lifecycle and artifact semantics.
Primary sources checked:

- Eclipse Temurin supports LTS streams for at least four years and currently lists Java 25 availability
  through at least September 2031: <https://adoptium.net/support/>. This is the relevant JDK authority
  because CI installs `distribution: temurin`; Oracle's commercial support table is not the repo's pin.
- Gradle declares a minor release EOL when the next minor release arrives; 9.7.1 is current, so the
  repo's 9.6.1 wrapper is already EOL without there being an ISO end date:
  <https://docs.gradle.org/current/userguide/feature_lifecycle.html>.
- Node publishes an explicit release/EOL schedule: <https://nodejs.org/en/about/eol>.
- WebView2 follows Microsoft's Modern Lifecycle and is simply “In Support,” with no retirement date:
  <https://learn.microsoft.com/en-us/lifecycle/products/microsoft-edge-webview2>.
- NVIDIA publishes driver-branch EOLs and CUDA/driver compatibility matrices, not one toolkit EOL date:
  <https://docs.nvidia.com/datacenter/tesla/drivers/supported-drivers-and-cuda-toolkit-versions.html>
  and <https://docs.nvidia.com/deploy/cuda-compatibility/minor-version-compatibility.html>.
- Lucene releases are unscheduled: <https://lucene.apache.org/core/downloads>. ONNX Runtime documents
  cadence/backwards compatibility but no support-until date:
  <https://onnxruntime.ai/docs/reference/releases-servicing.html>. Tauri's security policy supports
  versions above 1.0 without a date: <https://github.com/tauri-apps/tauri/security/policy>.
  llama.cpp and Tesseract likewise publish releases/security guidance, not per-pin EOL dates.
- GitHub Actions artifacts are immutable and normally retained for at most 90 days; a “rolling artifact”
  requires download/append/re-upload with deletion/overwrite semantics and still is not durable history:
  <https://github.com/actions/upload-artifact>. Scheduled workflows in public repos can also be disabled
  after 60 days of inactivity:
  <https://docs.github.com/en/actions/how-tos/manage-workflow-runs/disable-and-enable-workflows>.

No external code or text is proposed for copying; no license/attribution addition is needed.

### T.3 Displacement / duplication

- Item 1 must extend the stack-currency work in 792, not create an unrelated inventory of the same pins.
- Item 2 must reuse `OpenApiController` composition and the committed route-manifest capture; it must not
  introduce a Node reimplementation of OpenAPI rendering.
- Item 3 extends the `todo-fixme` gate but does **not** supersede 378. A marker ratchet cannot close a
  behavioral workaround inventory; 378 needs an independent per-item disposition and a truthful closure
  reason.
- Item 4, as written, is displaced by the 530 history/dashboard substrate. Any durable CI series is an
  extension of that substrate, not a sibling `health-series.ndjson` authority.
- Item 5 is an index into existing canonical definitions, not a second definition source.
- Item 6 belongs on the config declaration/projection path and should resolve or time-box 532; prose in
  this tempdoc alone would not change behavior.
- Item 7 must use a bounded claim inventory with named evidence, not an uncheckable natural-language
  promise over every sentence.

**LITE-CLASS: no.** This contains schema/governance design, runtime-contract projection, product naming,
and canonical documentation work; it is not pure teardown/rename/config deletion.

**BLOCKED ON YOU**

- Nothing for the research/design/derisk sequence. Implementation remains unauthorized by this request.
- A later implementation will need an owner decision before changing ADR-0026 to add any scheduled
  specialty workflow, and before resolving the product choices F-22/F-25.

**PROCEEDING / DONE**

- Dedicated worktree and branch verified from current local `main`.
- Entire tempdoc and its required 887 evidence read; current code and adjacent authorities inspected.
- External lifecycle/artifact research complete; current charter rejected with evidence.
- Proceeding into the requested replacement design, then derisk.

## D. Replacement design (2026-09-03)

The common problem is not a lack of files. It is a lack of explicit authority edges: a pin needs
lifecycle evidence that fits its vendor's policy; a generated snapshot needs to say what it was derived
from and what it does **not** prove; a ratchet needs to define the thing it counts; and a historical
series needs one durable owner. The corrected design therefore keeps the seven useful outcomes but
rejects the proposed one-shape-fits-all registers and duplicate stores.

### D.1 Platform lifecycle evidence, not a universal EOL date

Extend 792 with `governance/platform-lifecycle.v1.json`. It is a metadata overlay on the existing pin
authorities, never a second copy of their versions. Each row identifies the platform, runtime/build/
distribution scope, the actual `pinSource` and a named fail-closed extraction adapter, evidence owner, primary `sourceUrl`,
`sourceCheckedOn`, and `reviewBy`. The checker reads the live pin from the named source; a hand-copied
version in the register is forbidden.

Lifecycle policy is a discriminated value, because the evidence has different shapes:

- `fixed-date`: a vendor publishes a calendar support horizon, so `supportUntil` is meaningful at
  the source's stated day or month precision;
- `release-relative`: support ends when a named successor is released, as with Gradle minors;
- `rolling`: the supported product follows a modern/current-version policy with no announced end;
- `compatibility-matrix`: support is the intersection of two authorities, as for CUDA toolkit and
  driver branches;
- `no-published-eol`: the project publishes releases or compatibility guidance but no support end.

The check has two independent findings. A known support horizon warns inside 90 days and fails after
the end in gate mode. Evidence freshness warns when `reviewBy` arrives and fails only after a declared
grace period; it must never relabel an unknown vendor EOL as an actual EOL. Normal verification is
offline and deterministic. Refreshing dates is an evidence-reviewed maintenance action, not a network
request in PR CI. Advisory CI may run report mode, while the same check remains directly runnable in
gate mode. Dependency upgrades remain out of scope.

This supersedes the proposed `platform-eol.v1.json` name/schema and any copied version column. It does
not supersede 792's stack-currency decisions or the real pin sources.

### D.2 A classified OpenAPI snapshot with two honest proof edges

Keep the live router as the only route authority. Extract OpenAPI rendering from
`OpenApiController` into a pure renderer over `RouteEntry` values; the runtime endpoint and an offline
snapshot exporter both call that renderer. The exporter reads the already-committed route-manifest
capture rather than recreating route registration. This preserves one Java implementation of OpenAPI
semantics and one captured route inventory.

Commit the result beside the existing generated client inputs as a clearly named **reference-client
structural snapshot**, not as `contracts/http/openapi.snapshot.json`. Its description and a top-level
JustSearch extension must say that it mixes reference-client and internal routes, is not the narrowly
versioned Runtime Contract, and does not promise request/long-tail response schemas. Putting the full
surface under a generic `contracts/http` path would contradict the public/reference/internal boundary in
`docs/reference/runtime-contract.md`.

There are two separately named proof edges:

1. An offline regeneration check proves route snapshot → OpenAPI snapshot and route snapshot → typed
   client. It runs the existing API-client check plus the Java renderer/exporter and is suitable for CI.
2. A live capture command refreshes both committed snapshots from one running Head and records the same
   route count/digest. This proves live router → captured route/OpenAPI for that run. Route-changing work
   must run it, but an offline CI pass must not claim that it did.

The current runtime endpoint remains. The rendering logic inside `OpenApiController.build` is moved,
not copied; a Node OpenAPI renderer is forbidden. The existing stale route snapshot is refreshed as
part of the first implementation slice before its derivatives are accepted. Per-route request and
long-tail response schema authority remains the separate 583 charter.

### D.3 Comment-debt measurement and a truthful 378 disposition

The ratchet's intended unit is a debt marker in a source comment, not an arbitrary occurrence of the
words `TODO`, `FIXME`, or `XXX`. Extend the existing gate with small extension-specific comment
extractors for Java/JavaScript/TypeScript/Rust block and line comments and Python/PowerShell line
comments. Quoted strings, policy prose, generated/vendor content, and the gate's own fixtures are not
counted. Fixture tests must prove the boundary before the real source globs expand. The finding and
baseline wording changes from an implied file/token count to the exact unit: source-comment markers.
The resulting measured baseline is accepted through the existing changeset protocol; no predicted
number is treated as evidence.

Tempdoc 378 is a different authority: it records behavioral workarounds, including cases with no marker.
Audit all 29 entries against current code and give each one a present-tense disposition with evidence:
`removed`, `still-present` with its owning current tempdoc, or `absorbed` by a named shipped authority.
Only then may 378 close as a fully dispositioned historical inventory. It must not say that the TODO
ratchet superseded it, and 893 must not create another open-issue store alongside 892.

This changes the existing raw-token matcher rather than stacking exclusions onto unexplained false
positives. The old matcher semantics, old baseline label, and the charter's “~36” expectation are
superseded together.

### D.4 One governance-history substrate; no rolling artifact fiction

Extend the existing governance history/dashboard substrate rather than create `health-series.ndjson`.
A versioned run-level snapshot record may add decision-useful repository facts alongside the existing
per-gate verdict rows: module count, test-file count, per-module source LOC, and only those typed live
measurements that an owning gate explicitly exposes. Collection definitions and excluded paths are part
of the schema so results are reproducible. Readers accept the legacy unversioned gate rows during the
migration. Baseline files are not scraped or totaled: they mix ceilings, floors, inventories, JSON,
Markdown, directories, and git-derived authorities, so a cross-gate “current value” has no coherent
unit. The existing dashboard/API/view remain the readers; no second dashboard or trend store is
introduced.

PMD complexity is excluded from this charter. No `CyclomaticComplexity` rule is configured, and adding
one would be a static-analysis policy change rather than a free report field. A later charter may add it
only with an owned rule, reproducible configuration, and a decision that consumes the result.

Local gate runs remain the primary append path. If remote evidence is useful, the existing public
`CI` workflow's `public-claims` job may emit one run snapshot after the kernel invocation and upload that
run's immutable inspection copy; it needs no new workflow or trigger. A scheduled specialty workflow
still requires an explicit ADR-0026/ADR-0044 decision and trigger-policy update; 893 does not smuggle one
in. Artifacts are transport/inspection copies, not the longitudinal source of truth. If the owner later
requires durable cross-machine history beyond retention, that decision must choose a durable store
explicitly; download/append/overwrite of a “rolling artifact” is rejected.

This displaces the proposed new workflow, sibling NDJSON file, rolling-artifact mutation, and unconfigured
PMD count. Existing `history.mjs`, `dashboard.mjs`, and `ci-walltime-trend.yml` remain and are extended only
where the new facts have a demonstrated reader.

### D.5 Glossary as an authority index

Add `docs/reference/glossary.md` as a navigation index, not a competing dictionary. Each row has the
term, the canonical definition link, the context/surface that uses it, the shipped UI label when that
differs, and an ambiguity/decision state. Definitions stay in their owning architecture, runtime, UI,
or policy document. `docs/llms.txt` links the index after normal regeneration.

F-22 and F-25 are recorded as unresolved product decisions with links to 509 and to their current UI
surfaces. The glossary must not silently choose the canonical Ask entry point or rename Simple/Advanced.
When the owner decides, the implementation and owning canonical document change first, and the glossary
row follows. Link verification is sufficient; no glossary-content gate is added.

### D.6 Feature-flag lifecycle is declared at source and enriched by a validated overlay

The founder-facing proposal puts the minimal lifecycle classification (`permanent`, `experimental`, or
`deprecated`) on every canonical runtime-config declaration in `EnvRegistry` / `ConfigKey` and projects
it into the generated ownership matrix. This closes the circular loophole where an optional overlay
could simply omit an experimental key. A validated governance overlay then carries the richer metadata
only for experimental/deprecated keys: owner, `introducedIn`, and either an expiry date or an objective
promotion/removal criterion. Permanent configuration needs no invented expiry. Validation requires
classification coverage for the generated config universe, joins every rich-metadata row to it, and
rejects missing non-permanent metadata, orphan rows, and stale rows after removal. The existing
`config-surface` gate is the natural enforcement seam because it already owns declaration counts and
reader-presence checks; the generated matrix remains a projection, not an authoring surface.

An expired flag fails only when its recorded decision is missing; the policy does not automatically
delete behavior. Promotion removes experimental metadata only in the same change that establishes the
permanent contract. Removal deletes the flag, reader, docs, tests, and overlay row together.

Tempdoc 532 demonstrates the value of expiry discipline but is **not** a runtime feature flag: the
consumerless virtual-operation substrate is a code/product bet outside `config-surface`. It therefore
cannot be the first enforceable case of this policy. It retains its own ship-or-retract decision and may
motivate a future, separately justified experimental-mechanism policy. The earlier claim that adding a
`stage` field to config rows would resolve 532 is superseded.

The current 532 teardown list is also stale. If the owner chooses retraction, that same change must
sweep current frontend boot/publishing, HTTP routes, backend store/merge, agent dispatch/result bridges,
generated route artifacts, tests, and canonical docs. This is 532 work, not feature-flag work.

### D.7 Bounded canonical-claim audit

Replace “verify every sentence asserting a mechanism” with a reproducible inventory over five
high-drift claim classes:

1. workflow trigger or CI-wiring claims;
2. generated-from / consumed-by claims;
3. endpoint method, status, authentication, or mutation semantics;
4. default, enabled, shipped, deferred, or experimental-state claims;
5. numeric/version/current-value claims.

For each class, record the candidate-producing query or script and the included canonical roots. Every
candidate is dispositioned in this tempdoc as `verified`, `stale`, or `non-mechanistic`, with a primary
`file:line` or command result. Acceptance is that this deterministic candidate set is exhausted, not
that an agent claims to have interpreted every English sentence. Stale canonical text and the generator
or source comment that can recreate it are fixed together. `CLAUDE.md` may receive corrections to false
claims found by the audit, but no new always-loaded rule is added.

Seeded corrections already supported by current evidence are: the CUDA-deferred statement, the blanket
PMD build-failure statement, the token-exempt GET example that does not return search data, and the a11y
baseline's nonexistent TypeScript consumer claim. The knowledge-server seed has already been corrected
and is recorded as verified rather than churned. The final short guidance in
`writing-docs-for-ai.md` names these checkable claim classes and points to existing drift guards; it does
not create a doctest framework.

### D.8 Corrected acceptance boundaries

- Lifecycle: every seeded platform resolves its live pin from an existing source; every policy variant
  and stale-review path has a fixture; offline report/gate modes distinguish evidence staleness from
  actual support expiry.
- API snapshot: the runtime endpoint and offline exporter use the same renderer; both committed
  derivatives are deterministic; the live capture refreshes route and OpenAPI snapshots together and
  records equal route count/digest; the artifact is visibly classified outside the public Runtime
  Contract.
- Comment debt / 378: lexical fixtures prove comments versus strings for every included extension; the
  expanded real corpus is measured, changeset-approved, and green; all 29 workaround entries have a
  current evidence-backed disposition before 378 closes.
- Health: the existing dashboard projection is green before schema extension; legacy history rows still
  read; one run-level record feeds the existing API/view; no heterogeneous baseline aggregation,
  specialty schedule, rolling artifact, or unconfigured complexity metric is present.
- Glossary / claims: every glossary row links to its definition authority; F-22/F-25 remain explicitly
  undecided until owner input; each claim-audit query and candidate disposition is recorded; canonical
  links and required docs regeneration are green.
- Flag proposal: founder approval precedes implementation; every declared config key has one lifecycle
  classification and every non-permanent key has complete, non-orphaned lifecycle metadata. 532 remains
  independently dispositioned.
- Repository: subject-specific Node tests, affected frontend checks, affected `modules:ui` tests, and
  `build -x test` are green. CI wiring is only claimed after a real run on the implementation branch.

## R. Design reach

The design reveals four reusable principles. They are recorded here so adjacent work can conform, but
893 builds only the instances its own seven outcomes require.

| Principle | Candidate scope beyond 893 | Existing violation / pressure | Evidence that it earns its keep | Retirement condition |
|---|---|---|---|---|
| **Metadata must match the shape of its evidence.** A date, stage, or count is not universal merely because it is easy to gate. | Dependency currency, deprecation clocks, feature experiments, ADR review evidence | The original EOL schema forced rolling and release-relative policies into `null`; the original flag proposal treated a consumerless substrate as a config flag. | Every seeded platform and flag can be represented without a false date/category, and reviews lead to a documented upgrade, promotion, removal, or renewed evidence. | Retire a policy variant or field when no owned row uses it and no foreseeable source publishes that evidence shape. |
| **Every snapshot names its authority edge and surface tier.** | Route/client codegen, schemas, generated docs, installer manifests | The current client gate proves only client↔snapshot while nearby prose can be read as live fidelity; a generic full-OpenAPI contract path would over-promise stability. | A stale derivative fails the named offline edge; a live capture reports its digest; readers can distinguish public contract from reference/internal inventory. | Delete the snapshot when a static route authority can generate all consumers directly, or when no consumer/tool uses the snapshot. |
| **A measurement series has one append authority and a named decision consumer.** | Governance gates, CI wall time, benchmarks, release evidence | The proposed health series duplicated local governance history and mistook mutable artifacts for durable storage. Tempdoc 894 also proposes a schedule that must respect the same trigger authority. | One schema/store feeds the dashboard, and each retained metric is used in a threshold, trend review, or maintenance decision. | Remove a metric after a declared review interval if no decision consumes it; remove the series when point-in-time gates provide all required evidence. |
| **Expiry discipline is domain-specific before it is generalized.** | Runtime flags now; speculative substrates, experiments, and dormant workflows only after separate evidence | `config-surface` can govern flags but cannot honestly govern 532's consumerless code. | Experimental flags are promoted or removed by their recorded criterion instead of becoming permanent by neglect. | Retire the overlay if experimentation moves to a single declaration authority that carries the same lifecycle data intrinsically, or if no experimental/deprecated flags remain. |

Broader reach is therefore real but bounded. The platform policy variants, snapshot classification, and
history ownership are needed now. A universal experiment register, a general doctest engine, a new
durable telemetry service, and a scheduled-workflow policy are recognized possibilities, not structures
this tempdoc is authorized to build.

## K. Derisk record (2026-09-03)

The user's explicit research → design → derisk request approved this confidence-building pass. No
feature implementation was started. Investigation concentrated on the seams most likely to invalidate
the replacement design cheaply.

### K.1 Confidence-building plan and results

| Uncertainty to reduce | Read-only probe | Result and design consequence |
|---|---|---|
| Can the lifecycle register describe every seed without lying? | Compare each current pin source with its vendor's primary lifecycle shape | Yes, if policies are discriminated. No, if `supportedUntil` is universal. Pin extraction needs named, tested adapters rather than arbitrary unchecked prose/regex. |
| Can OpenAPI reuse one renderer without inventing a static router authority? | Trace `OpenApiController.build`, `RouteManifestController.build`, the existing capture script, tests, and Runtime Contract classification | The renderer already accepts a route enumeration and is extractable. Offline generation can read the committed capture, but only a live capture proves router fidelity. Placement/wiring of the Java exporter remains to be proven in the module build. |
| Is broader TODO coverage a glob-only change? | Inspect matcher semantics, count the proposed corpus, and search for existing comment scanners | No. The current gate counts raw tokens while claiming comments. Existing language-aware scanners provide patterns, but there is no shared “extract comment spans for six languages” API; lexical fixtures are the critical proof. |
| Does a generic baseline trend have a coherent unit? | Inspect every baseline kind, kernel outputs, history schema, dashboard/API/view consumers, and PMD configuration | No. Baselines mix floors, ceilings, lists, documents, directories, and git facts. Add only run-level repository facts and gate-owned typed measurements. Complexity is absent and out of scope. |
| Where can feature lifecycle classification be complete? | Trace `EnvRegistry`, `ConfigKey`, matrix generation, config-surface enforcement, and 532's current code | Minimal stage belongs on canonical config declarations and is projected; rich non-permanent metadata can be an exactly joined overlay. An overlay-only stage is circular. 532 is a TS consumerless-substrate decision, not a flag. |
| Can the claim audit finish objectively? | Size the corpus and re-check seeded claims against code/workflows | Yes only as five deterministic candidate classes with explicit dispositions. “Every mechanism sentence” remains unfinishable. The seeded set already contains both real drift and one already-corrected claim, validating the disposition model. |

Two bounded read-only subagent audits independently checked the feature-lifecycle and governance-history
seams. They found the overlay-completeness loophole, 532's expanded teardown surface, the heterogeneous
baseline problem, the existing API/view consumer, and the fact that `ci-walltime-trend` reconstructs
history from run metadata rather than maintaining a rolling artifact. Those findings are incorporated
in §D.4/§D.6 rather than left as review notes.

### K.2 Current verification evidence

| Check on the untouched implementation baseline | Result | Meaning |
|---|---|---|
| `check-api-client-regen` | PASS | The generated client matches the old committed route snapshot; it does not prove the snapshot matches the live router. |
| `check-workflow-triggers` | PASS | Current workflows match `workflow-signal-policy.v1.json`; a new workflow or schedule needs an explicit policy/ADR change. |
| governance dashboard `--check` | **FAIL (pre-existing)** | The committed machine projection says 33 gates while the registry now has 35. This must be restored before extending history/dashboard schema so 893 cannot hide inherited drift. |
| current `todo-fixme` gate | PASS, zero findings | Existing main/UI-main scope is healthy. This says nothing about the proposed tests/scripts/Rust corpus. |
| canonical link verifier | PASS (157 files) | The current link baseline is green before glossary/claim edits. |
| world-state rerun | **BLOCKED by environment** | The script now cannot resolve `gray-matter` in either checkout. Initial takeover orientation had already run; implementation must restore the declared workspace dependency before relying on another world-state report. |

No Gradle task was started after the world-state rerun failed, because the repository permits only one
Gradle build and the ownership check was unavailable. The existing `OpenApiControllerTest` structure was
read, not executed. JVM verification is therefore a deliberate remaining implementation proof, not a
claim made by this derisk pass.

### K.3 Residual risks and controls

| Risk | Severity / likelihood | Control before or during implementation |
|---|---|---|
| A hand-written lifecycle extractor silently reads the wrong pin after source formatting changes | High / medium | Use a closed set of named source adapters, fixture every current source shape, and fail closed when extraction is not singular. |
| Offline OpenAPI output becomes a second apparent public contract or masks a stale capture | High / medium | Keep it beside reference-client generated inputs, embed surface classification/capture digest, retain the live-capture acceptance step, and never describe offline regen as router fidelity. |
| Comment lexing changes counts for escaped strings, templates, Rust nested comments, Python/PowerShell quoting, or docstrings | Medium-high / high | Implement extension adapters with adversarial fixtures before measuring the repo. Reuse proven scanners where their semantics fit; do not invert the simple shared stripper blindly. |
| Closing 378 loses still-live behavioral debt | High / medium | Require a 29-row disposition table and route every live entry to its actual owner before changing status. The TODO baseline is never closure evidence. |
| History migration breaks the existing API/UI or produces misleading cross-unit totals | High / medium | Version run records, retain legacy readers, test `history.mjs` + dashboard + `GovernanceStateController`/view shape, and forbid generic baseline scraping. Fix the existing 33→35 projection drift first. |
| Flag metadata omits the very experiments it should govern | High / medium | Require explicit stage at every canonical declaration; validate exact projection coverage and rich metadata for every non-permanent key. Founder approval is a hard precondition. |
| The bounded claim queries still produce an impractical corpus or miss paraphrases | Medium / medium | Land the candidate-query/disposition manifest before prose fixes, publish counts by class, and split the audit if a class is too large. Do not broaden acceptance after seeing results. |
| Product glossary accidentally settles F-22/F-25 by wording | Medium / medium | Mark both unresolved; implementation and owning product docs change only after an explicit owner decision. |

### K.4 Implementation sequence and confidence

The safest sequence is six independently reviewed slices: (0) restore workspace analysis dependencies
and regenerate the already-stale governance projection; (1) platform lifecycle; (2) OpenAPI renderer,
classified snapshots, and live capture; (3) comment-aware ratchet plus the separate 378 disposition;
(4) versioned run-level health records through the existing dashboard/API/view; (5) glossary plus bounded
claim audit; (6) feature-lifecycle policy only after founder approval. F-22/F-25 remain explicit owner
decisions rather than blockers for the glossary index itself.

**Implementation confidence: 7/10 overall.** Lifecycle and glossary work are about 8/10; OpenAPI is
7/10; comment lexing, history migration, and the claim audit are 6/10 until their first focused tests
measure the real shape; feature lifecycle is 5/10 until the founder accepts the declaration/overlay
boundary. The design is implementable, but this is a medium-high difficulty governance/runtime/docs
program, not a set of mechanical edits.

Recommended implementation setting: `gpt-5.6-sol` at high or xhigh reasoning for the OpenAPI,
comment-lexer, history, and cross-authority work; a lower-cost model is reasonable only for isolated
glossary-row population after the authority map and acceptance set are fixed. Keep the slices separate
so each can be verified and reverted without coupling all seven outcomes.

**BLOCKED ON YOU before implementation**

- Approve or revise the feature-lifecycle declaration/overlay policy before item 6 changes code or
  governance.
- Decide F-22/F-25 only if this work is expected to resolve product naming; otherwise the glossary will
  list them as unresolved.
- A scheduled specialty workflow is intentionally absent. Adding one would require separate explicit
  authorization to amend ADR-0026/ADR-0044 and the workflow-signal policy.

**Implementation prerequisites, not owner decisions**

- Restore the missing `gray-matter` workspace dependency sufficiently for world-state to run.
- Reconcile the pre-existing 35-gate registry / 33-gate committed governance projection before changing
  the history schema.
- Re-run the world-state ownership check before any Gradle verification or dev-stack work.

## P. Implementation plan (approved 2026-09-03)

The user's instruction to proceed approves the corrected §D boundary, including source-declared feature
stages with a validated lifecycle overlay, leaving F-22/F-25 explicitly unresolved, and adding no
scheduled specialty workflow. Work stays in `codex/893-hygiene-registers`; no PR is created without a
separate instruction.

### P.0 Prerequisites and integration discipline

- [x] Restore the root locked Node workspace with `npm ci --ignore-scripts`; do not alter dependency
  metadata or act on unrelated audit findings.
- [x] Re-run world-state and confirm this dedicated worktree/branch. Do not start a dev stack.
- [x] Reconcile the pre-existing governance-state projection before extending its schema.
- [x] Keep shared authority edits (`governance/registry.v1.json`, `.github/workflows/ci.yml`, generated
  docs, and this tempdoc) under the primary agent so parallel slices do not race.

### P.1 Platform lifecycle slice

- [x] Add the typed lifecycle metadata overlay, closed pin-source adapters, schema validation, and
  fail-closed extraction for all seeded platforms. Do not copy pin versions.
- [x] Add fixture tests for fixed-date, release-relative, rolling, compatibility-matrix,
  no-published-EOL, stale-review, expired-support, and ambiguous/missing pin extraction.
- [x] Add the advisory CI invocation only after the standalone check is green.
- [x] Supersede the original `platform-eol.v1.json` proposal in docs; there is no old file to delete.

### P.2 Classified OpenAPI projection slice

- [x] Extract a pure Java renderer over route entries; keep the runtime controller as its caller.
- [x] Add an offline exporter that reads the committed route capture and writes the classified
  reference-client OpenAPI snapshot beside existing generated API inputs.
- [x] Extend live capture to refresh route/OpenAPI snapshots together with matching count/digest.
- [x] Add deterministic/unit checks and CI regeneration wiring.
- [x] Refresh the route snapshot from a real Head and compare the paired count/digest before claiming
  live-router fidelity. The 2026-09-03 capture contains 243 routes and both projections carry
  `sha256:9a1ee92e4593d204692792f458554f87ad4165ce51634e76059a1981af0047e6`.
- [x] Remove the renderer body displaced from `OpenApiController`; do not leave Java/Node duplicates or
  create `contracts/http/openapi.snapshot.json`.

### P.3 Comment-debt and workaround-disposition slice

- [x] Replace raw token counting with extension-aware comment extraction and adversarial fixtures for
  Java/JS/TS/Rust/Python/PowerShell, including Rust nested comments and quoted/template strings.
- [x] Expand globs only after scanner tests pass, measure the real corpus, and update the baseline through
  the governance changeset protocol.
- [x] Audit all 29 entries in tempdoc 378 against current code, record removed/still-present/absorbed with
  evidence and a current owner, then close it only if every entry is dispositioned.
- [x] Delete/tombstone the old raw-token semantic claims and old baseline-unit wording in the same slice.

### P.4 Existing governance-history slice

- [x] Regenerate and verify the current 35-gate committed projection first.
- [x] Version history records; add one run-level repository-health record and preserve legacy per-gate
  row reads. Define module/test/LOC units and exclusions exactly.
- [x] Carry the record through the existing dashboard, `GovernanceStateController`, and Governance view;
  add no second store or dashboard and scrape no heterogeneous baseline files.
- [x] Wire one snapshot upload into the existing `public-claims` job. Add no
  new workflow, schedule, rolling artifact, or complexity metric.
  The workflow wiring is locally checked; an actual hosted artifact remains remote-CI evidence.

### P.5 Glossary and bounded claim-audit slice

- [x] Build the glossary as term → canonical authority/UI label/ambiguity-state links; list F-22/F-25 as
  unresolved and link it from `docs/llms.txt`.
- [x] Materialize the five candidate queries and record counts/dispositions before editing claims.
- [x] Correct each stale seeded claim and its generator/source comment where applicable; record the
  already-corrected knowledge-server seed as verified.
- [x] Add the short checkable-claim guidance, run the docs-maintenance regeneration sequence, and keep
  the no-new-`CLAUDE.md`-rule constraint.

### P.6 Feature-lifecycle slice

- [x] Add explicit stage to every canonical `EnvRegistry`/`ConfigKey` declaration and project it into the
  runtime-config matrix.
- [x] Add rich metadata for every experimental/deprecated key and extend `config-surface` validation to
  reject missing, orphaned, stale, or overdue lifecycle rows.
- [x] Document promotion/removal semantics and test exact coverage. Do not modify 532's substrate under
  this slice; only correct the claim that flag policy would resolve it.

### P.7 Verification and closeout

- [x] Run all new Node unit/check scripts and the affected governance suite.
- [x] Run affected `modules:ui` tests, frontend typecheck/unit tests if generated API/UI files changed,
  then `build -x test`; use world-state first because only one Gradle build may run.
- [x] Run canonical link/docs generation checks, workflow-trigger policy, API regeneration checks,
  governance dashboard check, and the expanded TODO gate.
- [x] Perform a critical review against §D.8 and inspect the worktree diff for unrelated changes,
  secrets, copied external text, and orphaned authority claims. Do not open a PR.

### P.8 Parallel ownership

Bounded subagents implemented the separable P.1, P.2, P.3, and P.6 cores; a read-only reviewer audited
claim seeds and then performed a refute-first final review. The primary agent retained shared CI,
registry, dashboard, tempdoc, integration, and verification ownership. Review found six defects; all
six were fixed and the same reviewer confirmed no residual actionable defect in those scopes.

### P.9 Post-review hardening

- [x] Correct Python f-string comment scanning so debt markers inside replacement expressions are
  found without treating literal hashes, nested strings, or format specifications as comments.
- [x] Bound governance history to 5,000 rows and read the history tail once per API request. Retention
  is intentionally row-bounded. A refute-first review found that the first compactor could lose a
  concurrent writer's rows; append plus retention is now protected by a bounded cross-process lock,
  stale-owner recovery, and same-directory atomic replacement, with a coordinated two-process test.
- [x] Make report-mode lifecycle failures visibly actionable while retaining advisory exit semantics;
  GitHub runs emit warning annotations and a job summary.
- [x] Audit configuration lifecycle stages semantically. The resulting declaration census is 277
  permanent, 27 experimental, and 2 deprecated keys; ambiguous product-owner cases remain permanent.
- [x] Register Governance-view visual coverage and make its scroll region keyboard-focusable. The
  focused capture has zero accessibility violations and no overflow.
- [x] Run the live Head capture, then make Java and Node OpenAPI pretty-printing byte-identical so the
  live projection also passes the offline regeneration check.
- [x] Preserve source date precision in platform evidence: Temurin publishes `At least Sep 2031`, so
  the register carries `2031-09` and the checker evaluates month precision conservatively from the
  first day rather than inventing a September 30 deadline.
- [x] Accept the targeted W23 hardening found during workaround disposition: production bootstrap now
  ignores file-backed fake-capability overrides. The source Javadoc, threat model, and regression test
  describe the same production boundary.

## V. Implementation and verification record (2026-09-03)

Implementation is complete for the approved boundary, including the post-review hardening and a real
Head capture. The paired capture requires one Java-computed full-descriptor digest from both live
projections before writing either snapshot; the captured 243-route inventory and its OpenAPI projection
carry the same digest. The offline exporter now uses the same deterministic JSON layout as the live
Node capture, so reproduction is byte-for-byte rather than merely structurally equivalent.

Evidence:

- `./gradlew.bat build -x test --console=plain` — green after the review fixes; 251 actionable tasks.
- Focused JVM run — `ConfigLifecycleTest`, `HeadAssemblyTest`, `OpenApiRendererTest`,
  `OpenApiSnapshotExporterTest`, and `RouteManifestControllerTest` all green; the same invocation ran
  affected Spotless checks and refreshed `:modules:ui:installDist`.
- `npm run test:unit:run` in `modules/ui-web` — 468 files and 6,269 tests passed; `npm run typecheck`
  also passed.
- `node scripts/governance/run-all-tests.mjs` — all 33 discovered governance test files passed.
  Focused evidence included platform lifecycle (24 checks), API capture/client (10 + 4), config
  parser/census and lifecycle (5 + 5), and comment scanner/enforcer (9 + 6).
- Live local gates: `todo-fixme` passed with zero findings; `config-surface` passed with its six known
  dead-key notes. The runtime matrix reports 250/250 EnvRegistry declarations, 56/56 ConfigKey
  declarations, and 306/306 explicit lifecycle stages.
- Canonical link verification (160 files), `docs/llms.txt` check (119 indexed docs), generated-skill
  check (27 skills / 31 files), runtime-config verification, module-dependency projection, governance
  dashboard check, workflow-trigger policy, UI baseline schemas, UI-step coverage, markdown lint,
  API projection regeneration, prompt-surface inventory, and always-loaded budget all passed.
- Two refute-first reviews are recorded in the session evidence: the first found six actionable defects
  across API projection, config census, comment interpolation, workaround ownership, and dashboard
  stability; the second verified all six fixes. `git diff --check` and a credential-pattern diff scan
  were clean.
- Post-review evidence: the live Head capture recorded 243 routes with matching route/OpenAPI digests;
  the Java offline exporter reproduced the live OpenAPI bytes exactly and
  `check-reference-client-openapi-regen.mjs` passed. The Governance visual step rendered the latest
  repository snapshot with zero axe violations and no overflow. Focused scanner, history, lifecycle,
  configuration, JVM, and Governance-view tests passed before the final full verification rerun.
- A final independent refute-first review found one remaining defect: concurrent history compaction
  could overwrite a newer append. The repaired `history.test.mjs` launches two coordinated Node
  writers, proves all 50 distinct gate rows survive within the 100-row cap, and also proves stale-lock
  recovery. Re-review then found malformed owner metadata and PID reuse could leave a permanent lock;
  schema validation with directory-time fallback and a bounded maximum lease now cover both cases.
  The focused test and all 33 governance test files passed after the repairs.

Unverified or deferred evidence:

- CI wiring is locally policy-checked, but no hosted run or uploaded governance-history artifact exists
  for this unpushed branch yet.
- The Governance capture still reports two unrelated global shell-console errors: an unnamed
  `jf-control` and a boot-transition timeout. The Governance surface itself has direct visual,
  accessibility, overflow, unit, and typecheck evidence; this slice does not widen into those global
  shell defects.
- The advisory platform report identifies the repository's Gradle 9.6.1 pin as superseded by 9.7.1.
  This is an owner decision, not an automatic upgrade or a hidden green claim.

## S. Bounded claim-audit result (2026-09-03)

The audit materialized the promised five deterministic query classes over the seven seeded files plus
the a11y schema-version pair. Some seeded corrections preceded this table during the implementation
pass; the table records that ordering rather than claiming the inventory was written first.

| Class | Candidate query and included files | Count | Disposition |
|---|---|---:|---|
| CI/workflow wiring | `rg -n "PMD runs only when invoked|wired as a ci.yml step" CLAUDE.md scripts/ci/check-ui-step-coverage.mjs` | 2 | `CLAUDE.md` was stale and is corrected to task-owned PMD plus whitespace-only Spotless (`JvmBaseConventionsPlugin.kt:154,184`). The UI-step header is now verified by the direct `ci.yml` invocation and a green focused check. |
| Generated/consumed-by | `rg -n "TS e2e|e2eViews|both.*consume" governance/ui-a11y-baseline* scripts/jseval/experiments/regen_a11y_baseline.py` | 1 claim family | Stale. The nonexistent TS consumer and `e2eViews` field were retired from register/schema/generator. The generator now recaptures every declared row instead of truncating the 20-row authority to six. Schema check and Python compile are green. |
| Endpoint/security semantics | `rg -n "token-exempt GET|all .*export paths|\.gz" modules/ui/src/main/java/io/justsearch/ui/api/ApiSecurityFilters.java docs/tempdocs/297-diagnostics-export-redaction.md` | 2 | The token-exempt search example was stale and now names `/api/status`, verified by `ApiSecurityFilters` and `LiveRunsAuthTest`. Tempdoc 297's “all paths” claim was stale; it now records raw `.gz` inclusion as unresolved and is only partially complete. |
| Shipped/default/experimental state | `rg -n "deferred to v3|Three extraction gaps|Current shipping posture|One filesystem-identity gap" docs/explanation/03-knowledge-server.md docs/explanation/05-ai-architecture.md` | 2 | Both old claims were stale. CUDA now matches the pinned CPU baseline plus shipped/staged `cuda12` variant. Knowledge-server prose now retains only the junction-identity gap; Worker traversal and typed extraction-failure sources disprove the other two. |
| Numeric/version/current value | `rg -n '"version": 1|"const": 1' governance/ui-a11y-baseline.v1.json governance/ui-a11y-baseline.schema.json` | 2 | Verified: the register version and schema constant agree; `check-ui-baseline-schemas.mjs` makes the pair load-bearing. |

Independent read-only review also checked every glossary authority link and the seven seed
dispositions. F-22 (fragmented Ask entry points) and F-25 (Simple versus Advanced/Detailed scope) remain
explicitly undecided; the glossary indexes their current authorities without choosing new labels.

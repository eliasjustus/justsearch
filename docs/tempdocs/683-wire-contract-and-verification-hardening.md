---
title: "Wire-contract & verification hardening: strict generated schemas with a dev-throw/prod-degrade posture split, enum/codegen onboarding completion, an in-process gRPC search round-trip in the default test task, evidence-bundle capture repair + durable session-keyed index, release_id/required + baseline-pointer completion on the frozen release, an outward-numbers citation lint in the Public claims lane, and a deliberate-softness portfolio recording every intentionally fail-open seam with its flip condition."
type: tempdocs
status: "in implementation (worktree 683, started 2026-07-06)"
created: 2026-07-06
author: agent implementation session (design de-risked via 9 read-only probes + 2 implementation explorations against main; every load-bearing claim below is cited to file:line on this branch)
category: contracts / verification / governance
related:
  - 564-wire-record-schema-pipeline   # the record→JSON-Schema→{TS,Zod} spine this work completes and hardens
  - 627-process-supervision-crash-recovery   # documents that the system-test suite is never CI-enabled — the gap the new in-process round-trip partially closes
  - 638-dead-code-identification-sweep   # names the dead-but-reachable detection gap the census section addresses
  - 623-release-projection   # the frozen release object (release.v1.json) this work hardens
  - 682-inherited-constants-stabilization-batch   # sibling provenance/stabilization batch, no file overlap
---

# 683 — Wire-contract & verification hardening

## Why (repo-visible facts only)

1. **Runtime wire validation fails open on both paths.** The hand schemas go through
   `validateWithFallback`, which returns the raw payload cast on validation failure,
   prod-silent (`modules/ui-web/src/api/schemas.ts:253-254`); the generated path's
   `parseWireContract` logs `[WireContract]` but also returns the cast payload
   (`schemas.ts:283`). Generated objects use Zod-4 `z.object`, which silently strips
   unknown keys — a renamed backend field parses clean. An `AuditPolicy` enum drift
   shipped this way once (fixed; comment at `api/types/registry.ts:99-104`).
2. **Codegen onboarding is unfinished, not missing.** The 564 pipeline is fail-closed
   at build time for its onboarded surfaces; ~9 registry/conversation enums are still
   hand-mirrored outside the byte-identity generator (`Audience` exists in generated
   AND hand form), SettingsV2 has a record but no schema, and several loose schemas
   duplicate surfaces the generated types already cover.
3. **The Head↔Worker search path opens no gRPC channel in the default `test` task**;
   the spawned-process suite that covers it is opt-in and never CI-scheduled
   (tempdoc 627). The InProcess pattern already exists (`RemoteIndexingJobsBridgeTest`).
4. **The evidence pipeline's capture script is missing.** The `capture_evidence` MCP
   tool spawns `modules/ui-web/scripts/capture-evidence-bundle.mjs` — a file that does
   not exist in the tree — and bundles are gitignored + pruned to the last 20 with no
   link to the session or merge that produced them.
5. **The frozen release object has provenance gaps its own notes disclose:**
   `release_id` optional (current one hand-stapled — `release.v1.json` notes), upstream
   dataset revisions unpinned, and the leak/utility baselines pin absolute values
   outside the release pointer that relevance/perf already use. README numbers are
   CI-checked against the release, but nothing requires new outward numbers to cite it.
6. **Deliberately fail-open seams are recorded nowhere.** Each has a good local reason
   (ADR-0026 runner availability; process-spawning tests; degrade-don't-crash UX), but
   no list of them with flip conditions exists, so the set is never re-examined.

## Design (settled; implementation in this worktree)

**A. Contract hardening.**
- Generator emits `z.strictObject` (Zod 4; `.strict()` is deprecated) —
  `scripts/codegen/gen-wire-schema-types.mjs` single construction site. `required` is
  already honored by the generator; required-fidelity stays per-record opt-in via the
  existing `PreciseWire` marker (conform, don't global-flip — its doc says why).
- Posture split: both validators **throw in dev/CI**, **degrade + log + record in
  prod**; prod drift lands in a small localStorage ring
  (`api/wireDriftTelemetry.ts`, mirroring `availabilityTelemetry.ts`) and its summary
  rides the diagnostics-export POST body so exported bundles show field drift.
- Enum onboarding: 9 enums join the reflection generator's map; FE hand-unions
  deleted; `Audience` deduped onto the generated alias.
- SettingsV2: records move to `app-api` (`io.justsearch.app.api.settings`; ui already
  depends on app-api), join `WireRecordSchemaGenTest` + the codegen targets, NOT
  `PreciseWire` (nulls carry merge semantics). Settings domain parses via the
  generated schema.
- Loose-schema teardown: redundant loose copies of generated surfaces deleted;
  remaining loose surfaces either repointed to existing generated types or recorded
  in the softness portfolio; `validateWithFallback` deleted once consumer-free. The
  error envelope stays untyped (zero FE runtime consumers) and the agent-session
  snapshot stays deliberately loose — both recorded as portfolio rows, not silently.
- gRPC: package-private channel-injection seam on `RemoteKnowledgeClient` + an
  InProcess stub round-trip test in the default `test` task asserting request
  marshalling and `toCoreResult` mapping exact-value. (Real-both-ends stays in the
  system suite — portfolio row.)
- FE proto teardown: the vestigial `*_pb` outputs and buf TS emission go; runtime
  consumers migrated to the 564 path long ago (tempdoc 564).
- ui-check fixture wave: with dev-throw on, unmapped fixture endpoints (`{}` bodies)
  fail loudly; fixtures get minimal-valid bodies (existing `_empty_catalog`
  principle). Validators are not softened to make fixtures pass.

**B. Verification durability.**
- `capture-evidence-bundle.mjs` created to the validator's contract
  (`scripts/evidence/validate-evidencebundle-v1.mjs`), stamps `session_id` (the
  analytics join key — `record-merge.mjs`/`outcome-session.mjs`), and appends a
  durable line to `tmp/agent-telemetry/evidence-index.ndjson` so the
  session→run→bundle join survives pruning. Retention itself unchanged.
- Release hardening: `release_id` required + pattern-bound in
  `release.v1.schema.json` and enforced by the compose command; optional
  `corpus_source.upstream_revision` recorded when available; leak/utility baselines
  migrate to the `current_release` pointer + `fallback_baselines` pattern the
  relevance/perf gates already use (behavior byte-identical until a release carries
  those sections).
- Outward-numbers lint: new `scripts/ci/check-outward-number-citations.mjs` in the
  required Public-claims lane — benchmark-shaped numbers in README/docs/business must
  cite the current `release_id` or match release values.
- Softness portfolio: new section in
  `docs/reference/contributing/discipline-gate-kernel.md` listing every deliberately
  fail-open seam with reason + flip condition.

**C. One-shot liveness census (operational; no deletions here).** Drive the live app
(ui-check step set without fixtures, e2e specs, jseval flows) with head tracing on;
diff `telemetry/traces.ndjson` route spans against the route manifest and the mounted
`jf-*` set against `component-vocabulary.generated.ts`; the suspicion list lands in
§Census below with its journey-coverage caveat. Deletions require human adjudication
and are out of scope for this branch (tempdoc 638 names the gap this probes).

## Acceptance

- All module tests + full `./gradlew.bat test` green; `npm run typecheck` +
  `test:unit:run` green; `gen-wire-schema-types.mjs --check` green; wire gate green
  on the buf edit; docs regen run for the canonical-doc edit.
- New tests: settings schema capture-or-verify; settings fixture round-trip; wire-drift
  ring unit test; posture-split tests pinning BOTH postures; gRPC round-trip
  exact-value test; release_id pattern rejection; leak/utility fallback equivalence.
- Live browser validation: boot the worktree stack, exercise search/settings/chat/
  health, **zero `[WireContract]` console errors**, diagnostics export contains
  `feTelemetry.wireDrift`.

## Teardown ledger (rides in this branch)

Hand-mirrored enum unions (registry.ts, conversation-shape.ts); hand `Audience`
(surface.ts); loose `SearchResponseSchema`, `IndexCapabilitiesSchema`,
`AgentSessionSummarySchema`, `ErrorEnvelopeSchema`; `validateWithFallback`;
`modules/ui-web/src/api/generated/*_pb.*` + buf TS emission target; the loose
SettingsV2 schema after onboarding; `:wireGenerateTs`/`:wireVerify` Gradle tasks
(`:wireGenerate` kept, Java-only); `scripts/wire-contract/` README rewritten to
buf-breaking-only truth + `@bufbuild/protoc-gen-es` and the `generate` script
dropped (`@bufbuild/buf` kept — the wire gate's enforcer resolves buf from that
workspace); dead FE proto-support `wireValidator.ts` + `wireProjection.ts`(+test,
incl. unconsumed `bigintToNumber`); `@bufbuild/protobuf`/`@bufbuild/protovalidate`
removed from ui-web deps.

## Out of scope (deliberate)

Census-driven deletions (adjudication); god-file decomposition (separate stream);
always-loaded instruction-layer work (tempdoc 681); typed error-envelope record
(portfolio row); real-both-ends gRPC in default tests (module isolation — portfolio
row).

## State for a continuing session (no chat context needed)

- **Branch:** `worktree-683-contract-verification-hardening` (worktree under
  `.claude/worktrees/`), 5 commits on top of `2ef7396`, working tree clean. No PR yet
  (owner gates publication).
- **Full validation set (all green as of 2026-07-07):** `./gradlew.bat test` ·
  `cd modules/ui-web && npm run typecheck && npm run test:unit:run` (360 files /
  3,506 tests) · `node scripts/codegen/gen-wire-schema-types.mjs --check` ·
  `node scripts/ci/check-wire-schema-types-regen.mjs` ·
  `node scripts/governance/run.mjs --gate wire --mode gate` · the ui-web check-script
  battery + the six ui-web governance gates · `cd scripts/jseval && python -m pytest -q`
  (2 pre-existing `test_correction_probe` failures, inbox-logged, unrelated).
- **Known-failing checks NOT from this branch** (pre-existing on base, inbox-logged):
  `check-theme-token-closure`, `check-accent-as-text`, `gen-token-names --check`,
  `strip-token-fallbacks --check` — all trace to `RecentsMenu.ts`/`ActionLedgerView.ts`,
  untouched here.
- **Windows dev-loop notes a continuing session needs:** run Gradle with
  `JAVA_HOME=F:/scoop/apps/temurin25-jdk/current` (shell default resolves Java 8);
  run `./gradlew.bat :modules:ui:installDist` explicitly before starting the dev stack
  after Java edits (the runner's assemble step can report up-to-date on a stale dist);
  a lingering ui-shot Vite server can hold native-module file locks and break
  `npm ci` (stop it first); the two heavy suites (Gradle `test`, FE vitest) should not
  run concurrently on one machine — the whole-program dead-code test can time out
  under combined load and read as a false red.
- **Do not treat as canonical:** design provenance for this batch lives in
  maintainer-private working notes; this tempdoc deliberately stands on repo-visible
  facts only, and any external reader should treat the "Why" section above as the
  complete rationale.

## Process lessons from this branch (for future multi-agent implementation work)

1. **Partitioned parallel implementation needs an item→owner table, diffed at
   integration.** Two FE work-halves fell between two parallel implementation briefs
   (each side assumed the other owned them) and the gap survived a green build, a
   green FE suite, and a confident completion report — it was caught only by a
   refute-first review re-deriving delivery from `git diff` rather than from reports.
   Rule that would have prevented it: every parallel brief lists item IDs it owns;
   integration verifies each claimed item against the actual diff (`git diff --stat`
   per agent), not against prose.
2. **Verify the user-reachable path, not the nearest endpoint.** The drift-telemetry
   wiring was first "live-verified" by driving a REST function that no UI code calls;
   the real UI uses the operation-invoke path. The correction habit: before wiring or
   verifying anything, grep the consumers of the exact function you're touching, and
   make the live check drive the surface a user would.
3. **A live check that refuses to pass is data, not friction.** Insisting that the
   verification click the real button (instead of curling the endpoint) surfaced that
   the Export Diagnostics button never upgrades on any surface — a shipped,
   user-facing dead feature that every static tier and the full test suite rated
   green. This is the strongest argument this branch produced for keeping
   live-behavior anchors in every review.
4. **Golden/regen gates did their job exactly once each** — the golden-wire test
   caught the deliberate operation-schema change, and the regen gate caught nothing
   because regen was run; both behaved as designed and neither was softened.

**Method.** Live 3-process stack from this worktree's dist
(`JUSTSEARCH_HEAD_TRACING_LEVEL=detailed`), driven by the full `jseval ui-check
--no-demo` step set (47 of 57 steps passed live) plus an ad-hoc Playwright walk.
Routes: `telemetry/traces.ndjson` HTTP spans (span-name encoded, `http.<method>.<path>`)
vs the 202-route `route-manifest.snapshot.json`, template-matched. Components: deep
shadow-DOM `jf-*` dump vs `component-vocabulary.generated.ts` (125 tags).

**Coverage caveats (read before trusting "never"):** inference offline (5 AI-chain
steps + 2 AI-state steps unexercised), empty index (no ingest run; row-interaction
steps skipped live), the ad-hoc component walk reached only 5 of 9 rail surfaces
(health/settings/help/browse rail entries not clickable outside ui-check's own
setups), and `skeleton-library` fails in all harness modes for reasons unrelated to
this branch (delay-mechanism selectors absent from FE source — logged to inbox).
"Never witnessed" is a suspicion signal, not a death certificate.

**Routes: 41 of 202 witnessed.** The 161 never-witnessed collapse almost entirely
into knowably-undriven families: chat/agent (33 — AI offline), debug tooling (14),
indexing/migration flows (13 — no ingest), AI install/packs (8), metrics streams (8),
MCP + llama-compat (`/mcp`, `/v1/*` — external-agent surfaces), SSE streams generally
(the census counted spans, and streams the FE holds open long-lived may not close
into spans). Residue worth a human glance (not explained by an undriven family):
`GET /api/navigation-history`, `GET /api/presence` + `POST /api/presence/run`,
`GET /api/governance/state`, `GET /api/boot/phases`, `GET /api/action-ledger` (+POST
events), `GET /.well-known/justsearch/manifest.json`, `GET /api/schemas/{name}`,
`POST /api/document/{id}/resolve-address`, `POST /api/offline/process`,
`POST /api/undo/{id}`, `GET /api/operations/{id}/preview`.

**Components: 48 of 125 witnessed** under the partial walk (plus everything the 47
passing ui-check steps render — their mount predicates passed, so the true mounted
set is larger than the walk's). Two concrete findings, both actionable now:
1. **The vocabulary register is stale against reality:** `jf-security-surface`,
   `jf-context-inspector-pane`, `jf-recents-menu` mount live but are ABSENT from
   `component-vocabulary.generated.ts` — the generated register lags the tree
   (inbox-logged; check its regen trigger).
2. The never-witnessed tail is dominated by form-control primitives
   (`jf-*-control`, layouts) and demo/editor surfaces — consistent with tempdoc 638's
   expectation; no deletion is proposed from this run (adjudication is the owner's).

**Live-validation results riding on the same runs:** 47 live steps with **zero real
console errors and zero `[WireContract]` hits** (strict schemas + dev-throw against
real payloads); diagnostics export live-verified embedding `frontend/fe-telemetry.json`
via BOTH transports — the REST body (`POST /api/diagnostics/export`) and, after the
review-cycle fix, the operation-invoke path the UI client actually uses
(`POST /api/operations/core.export-diagnostics/invoke` with `args.feTelemetry` —
seeded context verified inside the produced zip, 2026-07-07) — and omitting the entry
when absent; the repaired evidence capture ran against the live stack producing a
validator-OK `status:passed` bundle with `session_id` stamped and a durable
`evidence-index.ndjson` join line.

**Census catch, escalated during review (2026-07-07):** the Export Diagnostics
`jf-operation` button **never upgrades on either hosting surface** (HealthSurface,
HelpSurface) — connected in the surface shadow tree, no shadowRoot, zero width, while
the element class is globally defined; the 2026-07-04 pre-branch UI audit screenshot
corroborates the button absent on `main` (only Reindex/Force Rebuild render). Export
Diagnostics is therefore currently unreachable from the UI on `main` too — a live
instance of the dead-but-plausible class this tempdoc's census targets, found because
the review's live button-click verification refused to pass on a path users can't
reach. Inbox-logged for a presentation-authority fix (scoped-registry upgrade gap
suspected); out of this branch's scope. The wire-drift telemetry chain is complete and
verified up to that button; it lights up end-to-end the moment the button fossil is
fixed.

**Review cycle (2026-07-07, refute-first pass over the implementation claims):**
verification claims held under independent re-execution; the delivery ledger did not —
two FE halves had silently fallen between parallel implementation briefs (enum
repoint, SettingsV2 FE wiring; both since landed), the proto teardown was incomplete
(Gradle tasks, wire-contract README/deps, dead `wireProjection`/`wireValidator` code;
since completed, −597 lines), and the drift telemetry was wired to a REST function no
UI code calls (since fixed via operation args, above). The golden-wire conformance
test caught the deliberate `feTelemetry` input-schema addition and was recaptured via
its documented delete-and-rerun workflow (+5/−1 lines, the schema change only).

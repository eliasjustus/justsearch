---
status: IN PROGRESS — PR 1 landed (premise probes + 45-ADR frontmatter sweep); PR 2 open
created: 2026-09-01
updated: 2026-09-02
owner_session: unassigned (wave-1 orchestrator; no dev stack needed)
follows:
  - 882-decision-review-lane0-hygiene.md (lane 0; fixed ADR frontmatter on 0008/0010/0013/0038/0039/0041 + README)
  - 269-early-product-decisions-review.md (the only systematic ADR/risk trigger audit, 2026-03-09/11)
  - 742-history-survivorship-audit.md §D5 ("ADR-liveness stays manual at n=1")
  - 633 / 655 / 834 (local API security: session token, Host allowlist, MCP Origin check)
---

# 884 — Decision review, lane B: the governance loop for architectural decisions

**Thesis.** The project has run exactly one systematic review of its architectural decisions
(tempdoc 269, March). Its risk register was deleted seven days later with no successor. Only 4 of
45 ADRs carry a reassess trigger; only 9 are referenced by any gate; the other 28 are prose nobody
is scheduled to reread. The 2026-09-01 review found at least five ADRs whose load-bearing premise
is false in shipped code (0008, 0015, 0018, 0038, 0039) and two whose status contradicted their
body. 742 §D5 declined to build an ADR-liveness lint "if a second accepted-but-unimplemented ADR
ever surfaces"; that threshold is passed. This lane builds the mechanism that makes drift
mechanical to detect, restores the risk register with an instrument per risk, and settles the
drifted ADRs. It also writes the missing ADR for the local-API trust model and closes the one
security defect the review confirmed.

Lane B is in wave 1 alongside A and C (see `882-…lane0-hygiene.md` for the split, order and
cross-lane rules). It needs **no dev stack** and is off the critical path, but it should land
before lane F rewrites ADR-0001/0002 so those get premise probes from day one.

## Scope (contract)

| # | Item | This lane does | Not this lane |
|---|---|---|---|
| 25 | ADR premise probes | a probe register + new rule ids on the existing `adr-coverage` kernel gate: each ADR names 1–3 mechanical probes (named test/gate id, absence, or count where the premise is the count) that fail when its premise drifts; seeded for every ADR with a verifiable premise | frontmatter `status:` fixes (lane 0 did them); a second ADR gate |
| 25 | risk register | restore `docs/reference/architectural-risks.md` (deleted 2026-03-18, `55a3e07cf` in the pre-cutover repo) with one trigger **and one instrument** (metric name, gate, or test) per risk; migrate RISK-001..007 from 269 and add the review's new risks | building the instruments that do not exist yet (each becomes a tracked item on the owning lane) |
| 25 | reassess cadence | `last_reviewed` frontmatter on every ADR ("Reassess When" stays a body section); `adr-coverage/review-stale` warning plus a session-start hint when `last_reviewed` exceeds 6 months; the 269 procedure written into `docs/decisions/README.md` | a cron; a separate skill; a second review this lane does not run itself |
| 25 | drifted ADRs | amend 0015 (tool count), 0018 (VDU routing is default-on, tiered, not PDF-only; `JUSTSEARCH_LAYOUT_ENABLED` is gone), 0039 (narrow to the one Category that exists, or schedule the others), 0006 (record 847/867/869 as the trigger check); retire 0011 (never built, no desktop need); mark 0001/0002 "under re-examination, lane F" | rewriting 0001/0002 (lane F) |
| 23 | local trust model | new ADR "Local API trust boundary" stating the actual posture (loopback bind + Host allowlist + MCP Origin check + session token on mutating methods; same-user native processes are inside the boundary; the token bootstrap route is by design); rewrite CLAUDE.md hard invariant #2 to match; make token enforcement **fail closed** when prod mode has no token | new auth mechanisms; anything in `modules/ui-web` or the Tauri shell |

## File ownership (no other wave-1 lane edits these)

`docs/decisions/**`, `docs/reference/architectural-risks.md` (new), `governance/adr-probes.v1.json`
(new), `scripts/governance/gates/adr-coverage/**` and `scripts/governance/lib/frontmatter.mjs`,
`governance/consult-register.v1.json` (new `docs/decisions/**` region),
`scripts/agent-analytics/world-state.mjs` (staleness hint), `docs/reference/security/threat-model.md`,
`CLAUDE.md` hard-invariant #2 line, `modules/ui/.../api/ApiSecurityFilters.java` (token
fail-closed only) + a new `ApiSecurityFiltersTest`, JSON Schemas for the four Surface records +
their `contract-surfaces.v1.json` entries (the FE build wiring is a cross-lane request).

Lane 0 already edited ADR frontmatter, the ADR README and CLAUDE.md; branch after #592 merges.

Cross-lane rules from the independent review (2026-09-01, apply to A, B, C): ADR numbers are
reserved now — **0046 local trust model (this lane)**, 0047 context window (lane A), 0048
extraction isolation + pacing (lane C). A and C create only their own numbered file; this lane
owns `docs/decisions/README.md`, the index and every existing ADR, and adds 0047/0048 to the
index and the probe register when they land. `EnvRegistry`/`ResolvedConfigBuilder` append
regions are shared across lanes (lane A owns structure only).

## Evidence (verified 2026-09-01 on `main` at 8e148b3b; lane 0 may have moved lines)

### The mechanism gap

- Reassess sections exist only on 0001, 0002, 0003, 0006 (all added by 269, 2026-03-11).
- ADRs referenced from `governance/` or `scripts/ci/` as enforcement: 0014, 0024, 0026, 0030,
  0032, 0036, 0042, 0043, 0044, 0045 (10; review-corrected). The rest have no mechanism that can
  notice drift.
- 742 §D5 (`742-history-survivorship-audit.md:212-216`): "No fingerprint lint now — build it if a
  second accepted-but-unimplemented ADR ever surfaces." 0010 and 0041 (status/body contradiction,
  fixed by lane 0) and 0011 (accepted 2026-03-16, zero `RemoteShard` symbols) are that second case.
- `docs/reference/architectural-risks.md` (137 lines) and `schema-migration-roadmap.md` (591 lines)
  removed 2026-03-18 (`55a3e07cf`, "docs: remove stale reference docs", pre-cutover repo) with no
  successor; `RISK-00N` survives only in tempdocs 269 and 512. 269 §A12 had set RISK-006 to
  "Act — decompose before next feature"; `InferenceLifecycleManager.java` was 1,053 lines then and
  is 1,357 on `main`.

### Drifted ADRs (premise → code)

| ADR | Premise | Code | Probe to write |
|---|---|---|---|
| 0015 MCP tool surface | "4 task-oriented tools, because small models pick badly from long lists" | `McpToolSurface.java:221-254` registers 6: answer, search, browse, ingest, status, runtime_manifest | count of `registerTool(` in that file == N declared in the ADR |
| 0018 VLM PDF extraction | opt-in behind `JUSTSEARCH_LAYOUT_ENABLED`, PDFs only | `JUSTSEARCH_LAYOUT_ENABLED` absent from `modules/`; routing is per-extension inside a tiered fallback budget (`worker-services/.../VisualRoutingDecision.java:45,84`) gated by `justsearch.vdu.quality_threshold` (`EnvRegistry.java:583-588`, default 0.3, which still matches) | grep for the flag returns 0 → the ADR must not name it |
| 0038 wire contract SoT | "hand-written per-language mirrors are forbidden" | `modules/ui-web/src/api/types/surface.ts:1-9` is a self-described hand-written mirror of four Java types, imported by 18 files, absent from `governance/contract-surfaces.v1.json` | every `.ts` under `src/api/types` is either generated or registered |
| 0039 contract substrate | "every cross-language agreement is a Category" (wire, plugin SDK, catalogs, registry serialization) | `contracts/registry.v1.json` registers `wire` only; `contracts/catalog` exists unregistered | category count in registry ≥ count the ADR claims, or the ADR is narrowed |
| 0008 settings ephemeral | corrupt settings silently replaced | lane 0 restored this behaviour (backup + defaults + `SETTINGS_RESET_FROM_CORRUPT`); lane B adds the probe: the reset test exists and is green | named test present |
| 0006 citations | trigger #1 "LLM citation accuracy ≥ 95%" | 847/867/869 rebuilt citation marks (2026-08) without citing 0006; last trigger check 720 (2026-07-12) | record the check; probe = `CitationScorer` + `CitationMatchOps` still wired |
| 0011 remote shard SPI | accepted, "not yet implemented" banner (742) | still zero symbols 5.5 months later; a single-user desktop app has no shard | retire: status `rejected` with the reason; probe = no `RemoteShard` symbol appears without an ADR change |
| 0029 telemetry bridge vs direct-emit | criteria ADR | zero references in 629 tempdocs; both idioms exist | fold into 0027 or add a probe that new `Telemetry.*` direct-emit sites are registered |

### Item 23 — the local trust model

- Filters (`ApiSecurityFilters.java:104-112`): Host validation, MCP Origin validation, CORS,
  session token on `POST/PUT/DELETE` (`:403-411`), operation admission, capability gates.
- The shell never calls `/api/mcp/token`; it reads `head.sessionToken` from the runtime manifest
  (`modules/shell/src-tauri/src/binding.rs:116`, `lib.rs:237,843-844`). The route
  (`LocalApiServer.java:75-83,640,727`) exists for external MCP clients and is covered by the
  loopback-Origin check but not by the token (GET). `threat-model.md:90-113` records the residual:
  a same-user native process can read the data dir anyway. **Verdict: coherent; document it.**
- Defect: `ApiSecurityFilters.java:412-421` — prod mode with no token logs
  `TOKEN_ENFORCEMENT_DISABLED` and continues. This is fail-open on the one control that gates
  mutation. Fail closed: refuse to bind (or 503 every mutating call) and raise a lifecycle
  condition.
- CLAUDE.md hard invariant #2 says "Local API binds to 127.0.0.1 only"; true but no longer the
  posture. The ADR names the posture; the invariant line points at the ADR.

## Independent review fold (2026-09-01, session justsearch-public-9a)

Accepted findings, each re-read by this contract's author; the decisions below carry **[R#]**:

- **[R1]** Lane 0 already rewrote `status:` on 0010/0013/0038/0039/0041 with compound strings
  ("accepted - mechanism superseded by tempdoc 564", …); post-882 the vocabulary is 9 distinct
  strings over 46 ADRs. Amendments build on 882's text; any status logic prefix-matches
  (`startsWith('accepted')`), never equality. No gate validates the vocabulary today;
  `llmstxt-generate.mjs` only filters `stable|in-progress|advisory` for inclusion.
- **[R2]** The first draft named two incompatible mechanisms (standalone `scripts/ci/check-*.mjs`
  lint vs the kernel). The kernel already has an ADR gate: `adr-coverage`
  (`governance/registry.v1.json:315-318`, `scripts/governance/gates/adr-coverage/{enforcer,classifications,rule-descriptions,truth-table}.mjs`,
  tempdoc 530 §2.7) that parses every ADR's frontmatter and validates `Covers:` globs. A second
  ADR gate with its own register is the 553 register-drift shape.
- **[R3]** `scripts/governance/lib/frontmatter.mjs:16-29` is a per-line `key: value` regex; a YAML
  list parses as empty. `llmstxt-generate.mjs` already uses `gray-matter`.
- **[R4]** "Reassess When" is a body `##` section on 0001/0002/0003/0006, not frontmatter.
- **[R5]** The fail-open token branch is unreachable today: `HeadlessApp.java:364-365` pairs
  `prodMode` with `generateSessionToken()` on the only production path; dev-runner, jseval and
  ui-shot never set prod mode. No `ApiSecurityFiltersTest` exists;
  `LocalApiUiTokenPolicyTest.java:229` tests a double that duplicates the logic.
- **[R6]** A JSON-Schema→TypeScript emitter exists: `scripts/codegen/gen-wire-schema-types.mjs`
  driven by `governance/contract-surfaces.v1.json` (25 records, output
  `modules/ui-web/src/api/generated/schema-types`), checked by `check-wire-schema-types-regen.mjs`
  and the `contract-projection` kernel gate.
- **[R7]** Verified counts: MCP tools 6; registry categories 1; `RemoteShard` 0;
  `JUSTSEARCH_LAYOUT_ENABLED` 0; ADR-0029 tempdoc citations 0; gate-referenced ADRs **10** (add
  0030 via consult-register and 0032; 0028 in `observed-happening.v1.json:172` is a note, not
  enforcement). `\b0005\b` false-positives on a float literal in `check-contrast-matrix.mjs:115`.
- **[R8]** The deleted risk register is recoverable:
  `git -C /f/JustSearch show 55a3e07cf^:docs/reference/architectural-risks.md` (137 lines,
  RISK-001..007). The consult register's `workflow-agent-tool` region → ADR-0030
  (`consult-register.v1.json:154-172`) is the code-path→ADR template; both hooks consume the one
  compiled `GOVERNED_REGIONS` (`governed-regions.mjs:33-42`). Ride-along: `maintain-doc-hint.mjs:15`
  still says "currently shell-v0 only".

## Design decisions this lane must make (recommendation in bold)

1. **Mechanism [R2, R3].** **Extend the existing `adr-coverage` kernel gate**, no new gate: rule
   ids `adr-coverage/probe-failed`, `adr-coverage/no-probe` (warn), `adr-coverage/review-stale`
   (warn), with changeset classifications and truth-table fixtures per the `/governance` grammar.
   Switch the gate's frontmatter parsing to `gray-matter`. Register shape:
   `governance/adr-probes.v1.json` `{ adr, premise, probes: [...] }`, referenced from ADR
   frontmatter as `probes: [ids]`; keep "Reassess When" as the body section it already is
   **[R4]**; add only `probes:` and `last_reviewed:` as frontmatter keys.
2. **Probe kinds, in preference order (T1).** (1) an existing named test or gate id (ArchUnit
   rules already are premise probes); (2) absence probes (symbol / flag / file must not exist);
   (3) counts only where the premise *is* the count (0015), never as a general ratchet. The
   failure message quotes the premise and names the amendment procedure, so the fix is "amend
   the ADR" and not "edit the number".
3. **An ADR that cannot have a probe** says so in frontmatter (`probes: none - <reason>`); the
   gate warns for `accepted*`/`stable*` ADRs created after this lane lands and does not block
   old ones.
4. **Review staleness surfaces where agents look (T2).** `last_reviewed` older than 6 months is
   emitted by `world-state.mjs` / `known-state-hint` at session start, and by the gate as a
   warning; a CI-only warning is the 872 pile again.
5. **Risk register instrument rule (T4).** Every row names an instrument that exists on `main`
   (metric id, gate id, test FQCN) **or** a lane tempdoc + item id; the gate checks the referenced
   item heading exists, so a row dies loudly if a lane closes without building it. Add a
   `docs/decisions/**` governed region to the consult register on the `workflow-agent-tool`
   template so editing an ADR pushes the review procedure.
6. **0038's mirror [R6].** **Generate `surface.ts`** from JSON Schemas for the four records via the
   existing emitter and register them in `contract-surfaces.v1.json`; the FE build wiring is a
   one-line cross-lane request (UI files are out of scope for this lane). The exception-list
   amendment is the fallback only if schema authoring is judged out of scope, and then 0038 must
   say so.
7. **Fail-closed token [R5], stated honestly.** Defensive hardening with no live path to break:
   refuse to bind in prod mode without a token and raise a readiness reason code. The test
   exercises the real `setupSessionTokenEnforcement` (`ApiSecurityFilters.java:412-421`) with
   `prodMode = true`, `sessionToken = null`, not a double.
8. **The `/adr-review` skill (T5)** becomes a "how to re-examine an ADR" section of
   `docs/decisions/README.md`; the probe gate plus the staleness hint *are* the schedule.

## Acceptance criteria

- `node scripts/governance/run.mjs --gate adr-coverage --mode gate` green on `main` + this
  branch with the new rule ids; proved to bite by seeding one drift (register a 7th MCP tool in a
  scratch commit) → `adr-coverage/probe-failed`, then removed. Truth-table fixtures cover
  probe-failed, no-probe, review-stale, and a list-valued `probes:` key parsed correctly (R3).
- Every ADR has `last_reviewed`; every `accepted`/`stable` ADR either has ≥1 probe or a stated
  reason. Amended ADRs 0006/0015/0018/0039 read true against `main`; 0011 retired; 0001/0002
  carry the "under re-examination (lane F)" banner.
- `docs/reference/architectural-risks.md` restored with RISK-001..007 (from 269) reconciled
  against `main` (e.g. RISK-006's "Act" is still open: 1,357 lines) plus new rows for: production
  JVMs missing native access (closed by lane 0, keep as history), context window unmanaged
  (lane A), extraction sandbox unreachable (lane C), reindex mechanism has one honest detector
  (lane D), single-connection job queue (instrument: throughput metric, lane C).
- `check-premerge-table`, `check-root-readme` (if README touched), `check-readiness-reason-codes`,
  `check-repo-history-policy` green; `docs/llms.txt` regenerated via `/docs-maintenance`.
- A new `ApiSecurityFiltersTest` drives the real `setupSessionTokenEnforcement` with
  `prodMode = true` + null token → refuses to bind (or 503s every mutating call) and the
  `TOKEN_ENFORCEMENT_DISABLED` warning path is gone; `LocalApiUiTokenPolicyTest`'s duplicated
  double is retired or made to delegate.
- `surface.ts` is generated (or 0038 records the exception with the reason);
  `check-wire-schema-types-regen` green; the ride-along fix at `maintain-doc-hint.mjs:15`.
- Session-start hint: `node scripts/agent-analytics/world-state.mjs` (or the known-state hint)
  lists ADRs with `last_reviewed` older than 6 months.
- `./gradlew.bat build -x test`, `:modules:ui:test`; full `./gradlew.bat test` before closing.
- Independent review by a **named** other session (T6): the orchestrator wrote this contract, so
  the closing reviewer should be lane A's or lane C's session, not "someone else".

## Takeover checklist

0. **First PR, immediately after #592 (T3):** the 46-ADR frontmatter sweep (`probes:` +
   `last_reviewed:`, gray-matter parsing in `adr-coverage`) lands **before** lanes A and C file
   ADR-0047/0048, so they write the new shape from the start. Everything else in this lane follows.
1. Branch after `882-decision-review-lane0-hygiene` (#592) merges; lane 0 edited six ADRs
   (compound `status:` strings — build on them, R1), the ADR README, CLAUDE.md, and the
   readiness/store registers.
2. Load `/governance` before creating the gate; load `/docs-maintenance` before editing canonical docs.
3. Recover the deleted risk register's text from the pre-cutover repo:
   `cd /f/JustSearch && git show 55a3e07cf^:docs/reference/architectural-risks.md` (read-only).
4. Write the probe register first, run it against `main` unchanged, and record which ADRs it
   already flags; that list is the amendment worklist, not my table above.
5. Do not touch `docs/decisions/0001` / `0002` beyond the banner; lane F owns their rewrite.

## Open questions for the owner

- Retire 0011 outright, or keep it `deferred` with a trigger ("a second machine or a shared
  index appears on the roadmap")? Recommendation: retire; a trigger without a roadmap item is
  the pattern this lane exists to end.
- Should the 6-month `last_reviewed` warning become a blocking gate after the first full review
  cycle? Recommendation: warn for one cycle, then decide with data on how many ADRs it flags.

## §B — PR 1 pre-implementation pass (2026-09-02, lane B worker session)

Every claim below was re-verified against this branch (`worktree-lane-B`, based on
`6c3ba431`) before a line of gate code was written. `file:line` is primary-source; a
claim I could not confirm is marked **CORRECTION**.

### B.1 The gate substrate (confirms R2, R3)

- `governance/registry.v1.json:315-330` — the `adr-coverage` gate entry: enforcer,
  `changesetsDir: gates/adr-coverage/.changesets`, `baseline.kind: ratchet-file` over
  `docs/decisions`, `config.adrDir: docs/decisions`,
  `selfTestFixturesDir: scripts/governance/_fixtures/adr-coverage`. One ADR gate exists;
  PR 1 extends it (no second gate).
- `scripts/governance/gates/adr-coverage/enforcer.mjs:16,86` — imports and calls
  `parseFrontmatter` from `scripts/governance/lib/frontmatter.mjs`.
- `scripts/governance/lib/frontmatter.mjs:16-29` — per-line key/value regex. **R3
  confirmed**: a `probes:` key followed by `  - id` list items parses as the empty string
  and the list items are dropped (they do not match the key regex).
- **CORRECTION to R3's remedy scope.** `parseFrontmatter` has four callers
  (`grep -rn parseFrontmatter`): `gates/adr-coverage/enforcer.mjs:86`,
  `gates/tempdoc-wiring/enforcer.mjs:104`, `gates/wire/protobuf-changeset-parser.mjs:90`,
  `lib/changeset-loader.mjs:76`. `gray-matter` returns **typed** values — `tempdoc: 524`
  becomes the number `524` and `date: 2026-09-01` becomes a `Date` (probed:
  `matter(...).data.last_reviewed instanceof Date === true`). Swapping the shared lib
  would change value types under three unrelated gates. **Decision: the adr-coverage
  enforcer imports `gray-matter` directly**; the shared per-line lib is left untouched.
  The contract permits either (§1 "or have the enforcer use gray-matter directly").
- `scripts/governance/run.mjs:198-229` — the self-test harness runs exactly two fixture
  flavors, `positive` (must produce verdict `pass`) and `negative` (must produce verdict
  `fail`), asserting only the verdict. So the `no-probe` / `review-stale` warnings must
  live in the **positive** fixture (verdict stays `pass`) and `probe-failed` in the
  negative one.
- `scripts/governance/lib/truth-table-runner.mjs:76-88` — `assertTruthTableShape` requires
  at least one exported `verdict*` function; `assertVerdictShape` restricts `status` to
  `pass|fail|info`. Warnings are therefore `status: 'info'` verdicts the enforcer emits at
  SARIF `level: 'warning'` (precedent: `gates/consumer-presence/enforcer.mjs:39,54,97`).
- `gates/adr-coverage/.changesets/README.md` documents the *count-ratchet* vocabulary
  (`declared-growth` and friends), but `enforcer.mjs:18-20` exports
  `{covers-added, covers-updated, adr-superseded, emergency-override}`. The README is
  wrong today — ride-along fix in this PR (`log-pre-existing-issues`: verified fix, in a
  file this lane owns).
- Baseline before any edit: `node scripts/governance/run.mjs --gate adr-coverage --mode gate`
  → `adr-coverage: pass`, 46 findings, all `adr-coverage/no-covers-field` notes (no ADR
  carries a `covers:` key today; `docs/decisions/README.md` is counted as an ADR by the
  enforcer's `readdirSync(adrDir)` filter — the new rules must skip it).

### B.2 Frontmatter reality (confirms R1, R4)

- 45 ADRs + `README.md` in `docs/decisions/`. Every file has frontmatter with
  `title/type/status/description` and (except README) `date`.
- Compound statuses confirmed: `0013` "accepted - partially superseded by ADR-0043",
  `0026` "accepted - narrowed by ADR-0044", `0038` "accepted - mechanism superseded by
  tempdoc 564", `0039` "accepted - format superseded by tempdoc 564", `0041` "accepted -
  format superseded in part by tempdoc 564". `0012` is `Superseded` (capital S).
  **Status matching is `startsWith` on the lower-cased value.**
- R4 confirmed: no ADR has a `reassess` frontmatter key; "## Reassess When" is a body
  section (0001/0002/0003/0006). Not migrated.

### B.3 Probe targets — verified individually

| ADR | Probe kind | Verified at |
|---|---|---|
| 0001 | test | `modules/app-launcher/src/test/java/io/justsearch/app/launcher/IndexWriterOwnershipTest.java:52-60` — `onlyLuceneOwnersMayDependOnLuceneClasses`, allowlist `LUCENE_OWNER_PACKAGES` = adapters-lucene + indexerworker (`:25-28`). This is the enforcement of hard invariant #1. |
| 0002 | test | `modules/ipc-common/src/test/java/io/justsearch/ipc/mmf/MmfWorkerSignalLayoutV1Test.java` exists; layout constants at `modules/ipc-common/src/main/java/io/justsearch/ipc/mmf/MmfWorkerSignalLayoutV1.java:33-53`. **CORRECTION to the brief's suggestion** (grep-present per `OFFSET_` constant): a named test outranks a grep by the contract's own preference order (§2 T1), and a per-constant grep would flag `OFFSET_RESERVED1_START` (1 main-source file — its own declaration), a false positive on a deliberately reserved slot. Test kind used; reader counts recorded here instead (ACTIVITY/HEARTBEAT/SHUTDOWN/GRPC_PORT 4 files, ENERGY_REDUCED/MAIN_GPU_ACTIVE 3, RESERVED0_START/RELOAD_SIGNAL 2, RESERVED1_START 1). |
| 0003 | grep-present + grep-absent | `gradle/libs.versions.toml:9,60-66` (`lucene = "10.4.0"`, 7 lucene modules); zero `elasticsearch|opensearch|solr` matches in `libs.versions.toml`, `build.gradle.kts`, `settings.gradle.kts`. |
| 0004 | test + grep-present | `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/LoopPacingPolicy.java:60-66` — `shouldRunBackfill(mainGpuActive, energyReduced, embeddingProvider)` is the mutual-exclusion site; `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/LoopPacingPolicyTest.java` exists. |
| 0006 | grep-present | `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/GrpcSearchService.java:50,230-231,346,820` — `CitationScorerConfig` import, `setCitationScorer`, `isCitationScorerActive`, `matchCitations`; `modules/reranker/src/main/java/io/justsearch/reranker/CitationScorer.java` exists. |
| 0008 | test | `modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsRecoveryNoticeTest.java:28,38` — `publishAssertsCondition` asserts `LifecycleReasonCode.SETTINGS_RESET_FROM_CORRUPT`; the code is at `modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/LifecycleReasonCode.java:158`. |
| 0011 | grep-absent | `RemoteShard` — 0 hits across `*.java`, `*.kts`, `*.ts` outside `docs/`. R7 confirmed. |
| 0015 | count (the ONE count probe) | `modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java:220-256` — 6 `tool("justsearch_...")` registrations (answer, search, browse, ingest, status, runtime_manifest); the section comment at `:213` says "6 curated tools". **CORRECTION to the brief**: the file has no `registerTool(` symbol — the factory is `tool(`, and three call sites break the line between `tool(` and the name, so the count regex must be multiline (probed: 6 matches). No test pins the count (`grep -rn listTools modules/*/src/test` finds no count assertion), which is why the premise-is-the-count probe is warranted here. |
| 0016 | grep-present | `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/QueryFilterBuilder.java:12,364,422` — `BoostQuery` import and `new BoostQuery(new ConstantScoreQuery(...))` with `BooleanClause.Occur.SHOULD`. |
| 0017 | grep-present + grep-absent | `settings.gradle.kts:127-129` — `:modules:ai-backend`, `:modules:gpu-bridge`, `:modules:prompt-support` present; `:modules:ai-bridge`, `:modules:app-ai`, `:modules:ai-worker` absent. |
| 0018 | grep-absent | `JUSTSEARCH_LAYOUT_ENABLED` — 0 hits under `modules/`. Remaining hits are prose only: `docs/decisions/0018-...:26`, `docs/explanation/23-search-pipeline-overview.md:92`, `docs/reference/performance/indexing-throughput.md:90`, `.claude/skills/search-quality/SKILL.md:3287`. The ADR body amendment is PR 2; the probe pins the code fact now. |
| 0022 | grep-present | `gradle/libs.versions.toml:40,138-139` — `recordBuilder = "52"`, `record-builder-processor`/`-core`. The pattern pins the coordinate, not the version. |
| 0024 | script | `scripts/ci/check-installer-execution-level.mjs:9,101` cites ADR-0024 for per-user install. |
| 0026 | script | `scripts/ci/check-workflow-triggers.mjs` exists; `scripts/ci/test-check-workflow-triggers.mjs:278,286` exercises the ADR-0026 note path. |
| 0028 | test | `modules/app-launcher/src/test/java/io/justsearch/app/launcher/LibraryResolveHashOnlyCallerPin.java:13,32-33` — "ADR-0028 / tempdoc 419 T5.4 — pins the 'only one HTTP entry point may resolve hashes' rule". |
| 0030 | json-path | `governance/consult-register.v1.json:154` — the `workflow-agent-tool` region's doc path is `docs/decisions/0030-policy-on-operations-vs-mcp-hints.md`. |
| 0032 | grep-absent | zero `react` matches in `modules/ui-web/package.json`. |
| 0036 | gate | kernel gate `observed-happening` (`governance/registry.v1.json:567`); `gates/observed-happening/rule-descriptions.mjs:32` and `truth-table.mjs:100,113` cite ADR-0036. |
| 0038 | file-set | `modules/ui-web/src/api/types/` holds 5 `.ts` files. `surface.ts:1-9`, `conversation-shape.ts:1-12` and `selection.ts:1-12` self-describe as hand-written mirrors; `registry.ts:1-12` is a re-export barrel over generated projections and `diagnostic.ts:1-12` is generated-derived. None is named in `governance/contract-surfaces.v1.json` (its `records` key on Java schemas plus `generatedDir: modules/ui-web/src/api/generated/schema-types`). All five are listed as explicit, reasoned exceptions; `surface.ts`'s reason points at PR 2 (contract §6, generate it). |
| 0039 | json-path | `contracts/registry.v1.json:5-23` — `categories` has exactly 1 entry (`wire`). R7 confirmed. |
| 0042 | script | `scripts/ci/check-live-witness.mjs:3,75` — "tempdoc 560 §4b/§5 / ADR-0042"; register `governance/live-witness.v1.json:5`. |
| 0043 | script | `scripts/ci/check-language-agnostic-analysis.mjs:3,44,63,80,104,118` cites ADR-0043; register `governance/language-agnostic-analysis.v1.json:2`. |
| 0044 | script | `scripts/ci/workflow-signal-policy.v1.json:11` (`"owner": "ADR-0044 ..."`) enforced by `scripts/ci/check-workflow-triggers.mjs`. |
| 0045 | script | `scripts/ci/check-repo-history-policy.mjs` exists; `scripts/ci/preview-squash-message.mjs:24` cites ADR-0045. |

Rejected probe candidates (recorded so PR 2 does not re-litigate them):

- **0027** (legacy `Telemetry.counter/timer/gauge` retired). A `grep-absent` on
  `Telemetry.gauge(` matches three *javadoc* references (`KnowledgeServer.java:1280`,
  `JvmMetricCatalog.java:20`, `WorkerOpsMetricCatalog.java:24`) that document the
  retirement. A probe that must special-case javadoc is not cheap-and-mechanical;
  `probes: none - <reason>` instead.
- **0029** — the contract itself leaves fold-vs-probe to PR 2.

### B.4 Kind vocabulary — one addition beyond the brief

The brief enumerates `test`, `gate`, `grep-absent`/`grep-present`, `json-path`. ADR-0038's
premise ("no unregistered hand-written mirror exists under `src/api/types`") is a
set-membership statement over a directory that none of those five can express without
degenerating into a file-count ratchet — which decision 2 forbids as a general lever. PR 1
therefore adds a sixth kind, **`file-set`** (`dir` + extension, minus `registeredIn`, minus
a reasoned `exceptions` list), used by exactly one probe. Recorded as a deviation.

The `gate` kind also accepts `script: scripts/ci/<name>.mjs` alongside a kernel
`gate: <id>`, because six of the ADR-owning enforcements (0024, 0026, 0042, 0043, 0044,
0045) are `scripts/ci` checks rather than kernel gates. Same rule id, same failure-message
shape.

## §C — PR 1 post-implementation critical analysis (2026-09-02, lane B worker session)

### C.1 Wrong-gate check — does the probe actually fail when the premise drifts?

Not assumed: every probe *kind* was made to fail on purpose, and the contract's mandated
bite (a 7th MCP tool) was run end-to-end through the kernel, not through a unit helper.

**The mandated bite.** A scratch edit added a 7th `tool("justsearch_scratch", …)` to
`modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java`, then
`node scripts/governance/run.mjs --gate adr-coverage --mode gate`:

```
governance: 1 gate evaluated, 1 fail, 47 findings
  adr-coverage: fail
adr-coverage/probe-failed :: 0015-mcp-tool-surface-design.md: premise "The MCP surface is a
small curated tool list, because small models pick badly from long lists. The count IS the
decision: 6 tools (answer, search, browse, ingest, status, runtime_manifest)." no longer
holds — expected 6 match(es) for /tool\(\s*"justsearch_[a-z_]+"/, found 7 in
modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java (7). The fix is to
re-examine and amend the ADR (docs/decisions/README.md § How to re-examine an ADR), not to
edit the probe until it passes. Probe id: 'adr-0015-six-mcp-tools' in
governance/adr-probes.v1.json.
```

The scratch edit was reverted (`git checkout -- <that one file>`); the tree is clean and
the gate is green again.

**Per-kind bites** (each forced, output recorded at the time):

| Kind | Forced drift | Result |
|---|---|---|
| `grep-present` + `expect` | 7th MCP tool | fail, message above |
| `grep-absent` | pointed the 0018 pattern at `docs/decisions` (where the flag still appears in prose) | `/JUSTSEARCH_LAYOUT_ENABLED/ now appears 1 time(s): docs/decisions/0018-…md (1)` |
| `test` | renamed the pinned member | `'…/IndexWriterOwnershipTest.java' no longer declares 'renamedRuleThatNoLongerExists'` |
| `gate` | unknown gate id | `kernel gate 'no-such-gate' is no longer registered` |
| `json-path` | declared 2 categories, tree has 1 | `contracts/registry.v1.json/categories has 1 entr(ies), declared 2` |
| `file-set` (new file) | dropped a scratch `.ts` into `src/api/types` | `scratch-mirror.ts … is neither registered … nor a declared exception` |
| `file-set` (stale exception) | listed an exception for a file that does not exist | `declared exception(s) … no longer exist — drop them from the register` |

The scratch `.ts` was deleted; `git status --porcelain modules/ui-web` is empty.

**Fail-closed paths, checked deliberately** (`green-masked-destructive`): a probe whose
`paths` resolve to zero files returns *fail*, not vacuous pass
(`probes.mjs` `evaluateGrep`, `scanned === 0`); a missing test file, a missing
`scripts/ci` check, an unparseable JSON register and an unknown `kind` all return fail.

### C.2 Findings from walking the diff (three fixed, in this PR)

1. **A bare `probes: none` satisfied the coverage rule.** The first implementation mapped
   an empty reason to the literal string `'none (no reason given)'`, which is truthy — so
   the laziest possible frontmatter silenced `no-probe`. Fixed: an empty reason returns
   `null`, so `probes: none` with no reason still warns. (`enforcer.mjs`, `readProbesField`.)
2. **`file-set` matched basenames, so a coincidence read as "registered".** Probed:
   `registry.ts` appears 4× and `diagnostic.ts` 1× in
   `governance/contract-surfaces.v1.json:145,151,157,163,169` — as full consumer paths.
   Under basename matching, a *new* hand-mirror named `registry.ts` anywhere would have
   been silently accepted. Fixed to full repo-relative path matching; the two genuinely
   registered files then need no exception entry, so their (redundant) exceptions were
   dropped and the register carries a note instead. The exception list is now exactly the
   three hand-written mirrors the ADR's premise contradicts.
3. **Malformed ADR frontmatter crashed the whole kernel run.** `gray-matter` throws on bad
   YAML where the old per-line parser returned `null`; an unparseable ADR would have taken
   down every gate in the same `run.mjs` invocation. Fixed: caught, and reported as an
   `adr-coverage/probe-failed` error on that ADR.

### C.3 Test precision — does the green mean what it looks like?

- **The negative fixture fails for two reasons** (the pre-existing `stale-coverage` case
  plus the new `probe-failed` case), and `run.mjs` asserts only the *verdict*
  (`run.mjs:206-222`), so on its own it cannot distinguish "probe evaluation works" from
  "the old rule still fires". Rather than delete the pre-existing coverage, the
  discrimination was put on the **positive** side: `positive/0001-sample.md` claims both
  fixture probes through a **list-valued `probes:` key**, and any register probe no ADR
  claims is reported as drift. So if list parsing regresses to the per-line parser (R3),
  the probes become unclaimed and the *positive* fixture flips to `fail`. Verified by
  deleting the list from the fixture: `self-test failed; gate machinery may be broken`,
  with two `adr-coverage/probe-failed` orphan findings. Restored; self-test green.
- **A `test`-kind probe checks that the pinned rule still exists, not that it passes.** A
  `@Disabled` or gutted test satisfies it. Likewise a `gate`-kind probe checks that the
  check is still registered/present, not that it still bites. Both are deliberate: the
  named test/gate *is* the enforcement, and this register's job is to notice when an ADR's
  enforcement is deleted or renamed out from under it. Recorded so a later reader does not
  mistake it for a stronger claim.
- **`expect` on ADR-0017's three-module probe is a presence assertion, not a ratchet.** The
  pattern matches only the three named modules, so `expect: 3` means "all three are still
  declared" and drops to 2 when one is deleted. It cannot grow. ADR-0015 remains the only
  probe where the *premise is the count* (decision 2, T1).

### C.4 Honesty note on `last_reviewed: 2026-09-02`

All 45 ADRs carry today's date. That attests to the 2026-09-01/02 decision review that
produced tempdocs 882/884 and this sweep — a pass over every ADR's status and premise — not
to a full per-ADR re-derivation of every claim. 25 ADRs got a probe whose target this session
verified at `file:line` (§B.3); the other 20 got a stated reason. The practical consequence
is that the whole corpus goes stale on the same day (~2026-03-04), which is the intended
first review cycle, and the open question at the end of this tempdoc ("blocking after one
cycle?") is answered with that data.

### C.5 Deviations from the PR-1 brief

| # | Deviation | Reason |
|---|---|---|
| 1 | `gray-matter` is imported by the adr-coverage enforcer, not by `scripts/governance/lib/frontmatter.mjs` | The brief allows either. gray-matter returns typed values (numbers, `Date`s); three unrelated gates consume the shared parser's string-valued output (§B.1). Smallest blast radius. |
| 2 | ADR-0002's probe is the named `MmfWorkerSignalLayoutV1Test`, not a per-`OFFSET_` grep | Preference order T1 puts a named test above a grep, and the per-constant grep false-fails on `OFFSET_RESERVED1_START` (§B.3). |
| 3 | ADR-0015's count regex matches `tool(` , not `registerTool(` | `registerTool` does not exist in that file; the factory is `tool(`, and it spans lines (§B.3). |
| 4 | Sixth probe kind `file-set` added | ADR-0038's premise is set-membership over a directory; none of the five enumerated kinds expresses it without becoming a file-count ratchet (§B.4). |
| 5 | `gate` kind accepts `script: scripts/ci/<name>.mjs` as well as a kernel gate id | Six ADR-owning enforcements are `scripts/ci` checks, not kernel gates (§B.4). |
| 6 | `no-probe` warns for every live ADR, not only those created after this lane | The brief's "created after this lane lands" is a date test that would need an authoring-date register; after the sweep every existing live ADR has a probe or a reason, so the behaviour is identical and the mechanism is simpler. |
| 7 | `docs/decisions/README.md` got no `probes:` / `last_reviewed:` keys | It is the index, not a decision; the enforcer skips it for the probe and cadence rules (`NON_ADR_FILES`). It still gets the pre-existing `no-covers-field` note, so the 46-finding baseline is unchanged. |
| 8 | Ride-along: `gates/adr-coverage/.changesets/README.md` corrected | It documented the count-ratchet classification vocabulary, which this gate's loader rejects (§B.1). Verified one-line class of fix in a file this lane owns. |

### C.6 PR 2 amendment worklist — what the unchanged tree flags

Run against the tree *before* any edit, `adr-coverage` was **green** with 46 findings, all
`adr-coverage/no-covers-field` notes: no ADR carried a `covers:` key, and the probe/cadence
rules did not exist yet. So the honest answer to "which ADRs did the unchanged tree flag" is
**none** — the pre-existing gate had nothing to say about premises.

The list PR 2 owes therefore comes from the probes this PR wrote plus the reasons it
recorded, not from a gate run:

1. **0038** — `surface.ts`, `conversation-shape.ts` and `selection.ts` are declared
   exceptions in the register today; the ADR says hand-written mirrors are *forbidden*.
   PR 2 generates `surface.ts` (contract §6) and drops its exception — the probe fails on a
   stale exception, so the handshake is mechanical. `conversation-shape.ts` / `selection.ts`
   need either the same treatment or an ADR amendment that admits them.
2. **0018** — the probe pins the code fact (`JUSTSEARCH_LAYOUT_ENABLED` does not exist); the
   ADR body still names the flag and says "PDFs only". Amend. Same drift in two canonical
   docs and one skill mirror, listed at §B.3.
3. **0015** — the ADR description says "4 task-oriented tools"; the probe declares 6. Amend
   the ADR text to the count the probe pins.
4. **0039** — the probe declares one Category because one exists; the ADR claims every
   cross-language agreement is one. Narrow it, or schedule the others.
5. **0011** — the probe pins zero `RemoteShard` symbols. Retire the ADR per the contract's
   recommendation; keep the absence probe so a future build is a deliberate ADR change.
6. **0006** — record the 847/867/869 trigger check.
7. **0027 / 0029 / 0007 / 0014 / 0019 / 0020 / 0021 / 0023 / 0025 / 0031 / 0033 / 0034 /
   0035 / 0037** — carry a stated `probes: none - <reason>`; each reason names why. The
   candidates worth revisiting in PR 2 are 0023 (an ArchUnit pin would work) and 0027 (a
   javadoc-aware absence probe).

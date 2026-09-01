---
status: CONTRACT — ready for takeover (not started)
created: 2026-09-01
updated: 2026-09-01
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

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
| 25 | ADR premise probes | a probe register + gate in the governance kernel: each ADR names 1–3 mechanical probes (grep count, file-absent, JSON path, named test) that fail when its premise drifts; wired into `scripts/governance/run.mjs`; seeded for every ADR with a verifiable premise | frontmatter `status:` fixes (lane 0 did them) |
| 25 | risk register | restore `docs/reference/architectural-risks.md` (deleted 2026-03-18, `55a3e07cf` in the pre-cutover repo) with one trigger **and one instrument** (metric name, gate, or test) per risk; migrate RISK-001..007 from 269 and add the review's new risks | building the instruments that do not exist yet (each becomes a tracked item on the owning lane) |
| 25 | reassess cadence | `reassess_when` + `last_reviewed` frontmatter on every ADR; a `check-adr-review-age` warning (not a block) when `last_reviewed` exceeds 6 months; a `/adr-review` skill that reruns the 269 procedure | a cron; a second review this lane does not run itself |
| 25 | drifted ADRs | amend 0015 (tool count), 0018 (VDU routing is default-on, tiered, not PDF-only; `JUSTSEARCH_LAYOUT_ENABLED` is gone), 0039 (narrow to the one Category that exists, or schedule the others), 0006 (record 847/867/869 as the trigger check); retire 0011 (never built, no desktop need); mark 0001/0002 "under re-examination, lane F" | rewriting 0001/0002 (lane F) |
| 23 | local trust model | new ADR "Local API trust boundary" stating the actual posture (loopback bind + Host allowlist + MCP Origin check + session token on mutating methods; same-user native processes are inside the boundary; the token bootstrap route is by design); rewrite CLAUDE.md hard invariant #2 to match; make token enforcement **fail closed** when prod mode has no token | new auth mechanisms; anything in `modules/ui-web` or the Tauri shell |

## File ownership (no other wave-1 lane edits these)

`docs/decisions/**`, `docs/reference/architectural-risks.md` (new), `governance/adr-probes.v1.json`
(new), `scripts/ci/check-adr-probes.mjs` + `check-adr-review-age.mjs` (new), the kernel wiring in
`scripts/governance/**` and `governance/consult-register.v1.json`, `.claude/skills/adr-review/**`
(new), `docs/reference/security/threat-model.md`, `CLAUDE.md` hard-invariant #2 line and the
Pre-merge table row for the new checks, `modules/ui/.../api/ApiSecurityFilters.java` (token
fail-closed only) + its tests.

Lane 0 already edited ADR frontmatter, the ADR README and CLAUDE.md; branch after #592 merges.

## Evidence (verified 2026-09-01 on `main` at 8e148b3b; lane 0 may have moved lines)

### The mechanism gap

- Reassess sections exist only on 0001, 0002, 0003, 0006 (all added by 269, 2026-03-11).
- ADRs referenced from `governance/` or `scripts/ci/`: 0014, 0024, 0026, 0028, 0036, 0042, 0043,
  0044, 0045. The other 28 have no mechanism that can notice drift.
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

## Design decisions this lane must make (recommendation in bold)

1. **Probe register shape.** **`governance/adr-probes.v1.json`**: `{ adr, premise, probes: [ {type, ...}, … ] }`
   with types `grep-count` (pattern, paths, expect `==|>=|<=`), `path-absent`, `path-present`,
   `json-path` (file, pointer, expect), `test-present` (FQCN + method). Keep it to what a gate can
   evaluate without a build. Follow the `/governance` skill's changeset + classification grammar;
   the gate id is `adr-probes`, mode `gate`, tier row added to `tier-register.md`.
2. **Where the probe lives.** **In the register, referenced from the ADR frontmatter by id**, not
   inline in the ADR, so the doc stays prose and the gate stays data. ADR frontmatter gains
   `probes: [ids]`, `reassess_when: [...]`, `last_reviewed: YYYY-MM-DD`.
3. **What to do with an ADR that cannot have a probe.** **Say so in frontmatter** (`probes: none —
   reason`). The gate warns on missing probes for `accepted`/`stable` ADRs created after this lane
   lands; it does not block old ones.
4. **Risk register instrument rule.** Every risk row names an instrument that exists on `main`
   (a metric id in a MetricCatalog, a gate id, a test FQCN) or an item on a lane tempdoc that will
   create it, with the lane named. A risk with neither is not a risk row; it is a note, and notes
   have no home (872).
5. **Fail-closed token.** **Refuse to start the API server** in prod mode without a token; the
   shell always supplies one via the manifest, so the only way to hit this is a broken launch,
   which should be loud. Add the readiness reason code via `check-readiness-reason-codes`.

## Acceptance criteria

- `node scripts/governance/run.mjs --gate adr-probes --mode gate` green on `main` + this branch;
  proved to bite by seeding one drift (e.g. register a 7th MCP tool in a scratch commit) → red,
  then removed. Wired into the kernel so CI runs it.
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
- `ApiSecurityFiltersTest` (or sibling) has a test: prod mode + blank token → server refuses /
  every mutating request 503; the old `TOKEN_ENFORCEMENT_DISABLED` warning path is gone.
- `./gradlew.bat build -x test`, `:modules:ui:test`; full `./gradlew.bat test` before closing.
- Independent review by a session other than the implementer.

## Takeover checklist

1. Branch after `882-decision-review-lane0-hygiene` (#592) merges; lane 0 edited six ADRs,
   the ADR README, CLAUDE.md, and the readiness/store registers.
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

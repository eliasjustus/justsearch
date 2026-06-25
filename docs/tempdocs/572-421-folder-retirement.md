---
title: "572 — Retirement of the 421 frontend-framework-kernel draft folder (as-built)"
type: tempdocs
status: done
created: 2026-06-09
related:
  - docs/decisions/0031-fe-three-primitives.md … 0041-catalog-category-format.md (the 11 graduated FE ADRs)
  - docs/reference/ui/frontend-kernel/kernel/ (the 7 graduated kernel contracts)
  - docs/reference/ui/frontend-substrate-state.md (the graduated R5.1 substrate-state register)
  - docs/reference/contributing/conflict-ledger.md (the canonical conflict-ledger protocol + register)
  - tempdoc 563 (the FE rewrite the retired draft scaffolded — React → Lit)
  - tempdoc 564 (the record-as-IDL contract decision that superseded the draft's proto-format ADRs 0040/0041)
---

# 572 — Retirement of the 421 FE-rewrite kernel draft (as-built)

The `421` frontend-framework-kernel draft folder was a planning overview *during* the FE rewrite.
That rewrite shipped (tempdoc 563), so the scaffold's purpose was served. A reference audit found
the folder was **not** pure remnant: alongside spent planning slices it held the FE's **foundational
ADRs and kernel contracts**, cited by shipped subsystems (564/560/551/552), a canonical how-to, a rule
file, and the declared `parent:` of 6 tempdocs. So retiring it was a **graduation of canonical decision
records**, executed structurally — not a blind delete.

## §1 What moved where

**ADRs → `docs/decisions/` (flat, MADR-lite, graduation + Status banner each):**

| Draft (retired) | Canonical ADR | Status |
|---|---|---|
| 01-three-primitives | `0031-fe-three-primitives` | Accepted |
| 02-lit-web-components | `0032-fe-lit-web-components` | Accepted (supersedes the FE half of ADR-0012) |
| 03-framework-not-product | `0033-fe-framework-not-product` | Accepted |
| 04-backend-owned-truth | `0034-fe-backend-owned-truth` | Accepted |
| 05-plugin-boundary | `0035-fe-plugin-boundary` | Accepted (the how-to's "ADR 05") |
| 06-resource-category | `0036-fe-resource-category` | Accepted |
| 07-universal-sse-envelope | `0037-universal-sse-envelope` | Accepted |
| 08-wire-contract-source-of-truth | `0038-wire-contract-source-of-truth` | Accepted in principle; **mechanism superseded by 564** |
| 09-contract-substrate | `0039-contract-substrate` | Accepted in principle; **format superseded by 564** |
| 09a-wire-contract-format | `0040-wire-contract-format` | **Superseded by 564** (proto3 rejected as SoT) |
| 09b-catalog-category-format | `0041-catalog-category-format` | Accepted in principle; format superseded in part by 564 |

(`00-decision-index` folded into the `docs/decisions/README.md` Decision Log, which now lists 0026–0041.)

**Kernel contracts → `docs/reference/ui/frontend-kernel/kernel/`** (7 docs: primitives, runtime-contracts,
shell-store-layout, platform-stack, shape-governance, contract-substrate (currency banner: proto realization
superseded by 564), streaming-envelope). ADR/sibling links repointed; canonical frontmatter + graduation banner added.

**Other live artifacts:**
- 486's **R5.1 substrate-state register** → `docs/reference/ui/frontend-substrate-state.md` (the one durable
  artifact from the draft's living inventory; the strategy prose + F/G wishlist + history logs were dated and deleted).
- The **conflict-ledger closure protocol** → canonical `docs/reference/contributing/conflict-ledger.md`
  (protocol + going-forward register); the dated C-0NN rows retired to git.

## §2 Currency — the landmine the migration guarded against

The wire-contract decisions (0038–0041) chose **protobuf as the contract source-of-truth**; tempdoc 564
(shipped) **reversed exactly that** — proto3 cannot faithfully model the wire, so the source is the Java
record (record-as-IDL) and proto is a derived/gated view. Each was therefore marked **Superseded /
superseded-in-part by 564** rather than canonized as current — verified against 564, not assumed.

## §3 What was deleted (git is the archive)

The spent planning bulk: the `3a-*` renderer slices, the 436–497 implementation slices, `20-systems/`,
`30-agent-workflows/`, `40-reference-workloads/`, `00-orientation/`, `60-migration-history/`,
`archive/source-tempdocs/`, the AGENT-ENTRYPOINT / GLOSSARY / TRACEABILITY / README scaffolding, and 486's
strategy/wishlist prose. All preserved in git history.

## §4 Inbound references rewritten

Every external reference to the retired folder (30 files) was rewritten to the new canonical homes:
the canonical how-to (`write-a-plugin.md`) repointed to ADR-0035 + the extension substrate (560) + 569;
the 6 `parent:` frontmatter fields dropped (499×2/500/526/561/565); the `adrs:` list in 564 repointed;
the contributing docs + the `contracts/` governance files + the 2 ui-web code comments + the 4 tempdoc
tooling scripts updated; dated-tempdoc prose genericized. Net: a repo-wide `git grep` for the folder slug
returns **zero**.

## §5 Verification

- `git grep` for the folder slug: **0** matches repo-wide (the core done-condition — proves every reference rehomed).
- `:modules:api-contract-projection-java:build`: green in isolation (the backend module whose generated
  classes appeared in the full-build log).
- `check-tempdoc-numbers`: green for this work (this doc was renumbered 570→571→572 to dodge two in-flight
  cross-worktree numbers). The residual **#558** collision is pre-existing cross-worktree drift between
  `558-presentation-pairs` and main, unrelated to this migration (logged to `observations.md`).
- `llmstxt-generate` + `skills-sync`: clean (after kernel-doc frontmatter was added).
- **Static build green:** `./gradlew.bat build -x test -x integrationTest -x systemTest -PskipWebBuild=true`
  → **BUILD SUCCESSFUL** (compile + spotless + PMD + ArchUnit across all modules, with the migration in place).
- **The full `./gradlew.bat build -x test` does NOT go green in this worktree — both failures are environmental,
  not this migration:** (1) `:modules:ui:installWebDependencies` fails with `npm.cmd` exit `-4051` (a Windows
  npm-spawn error); (2) `:modules:ui:integrationTest` → `SchemaMismatchStatusContractTest` times out waiting
  for `/api/status` readiness (statusCode 500 — a live Worker that can't reach readiness in a fresh worktree
  with no models/index/data). Both are live-stack/tooling concerns; `git diff` confirms **every changed source
  file is a comment or a doc** (no Java/proto/npm/package.json change), so neither failure is reachable by this
  migration. Per the goal's own fallback — a docs/governance migration with no runtime/UI surface — the green
  static gate suite + the slug-clean grep are the final verification; a live dev/browser check does not apply.

## §6 Merge runbook

**Target: `gate-fixes-main-green`** (the live integration branch), **not** bare `origin/main`. Rationale:
this migration is based on `gate-fixes-main-green@8b3ec7f55` and references tempdoc 569, which exists
**only** on `gate-fixes-main-green` (via its later commits), not on `origin/main` (19c4b7585). Merging to
bare `origin/main` would leave the 569 references dangling and would also drag the entire 8-commit
gate-fixes wave onto main as a side effect. `gate-fixes-main-green` is the staging branch carrying the
565/567/569 wave toward main; this retirement should ride that wave to main as one unit.

**State at planning time:** the migration is **uncommitted** (179 deletions, ~31 real edits, 3 new files).
`gate-fixes-main-green` advanced +3 commits past my base (`8b3ec7f55 → 2ed679a87`: the 569 reference-impl).

**Conflict surface vs `gate-fixes-main-green` — exactly 2 files:**
- `docs/observations.md` — both sides appended → **keep both** appended blocks.
- `…/slices/486-consumer-feature-discovery.md` — their 569 §13 checkpoint **modified** it; this migration
  **deleted** it → **modify/delete; keep deleted** (486 is retired; its substrate info lives in
  `docs/reference/ui/frontend-substrate-state.md`). All my other edited tempdocs (564/565/560/551/552/…)
  were NOT touched by the +3 commits → no conflict.

**Steps:**
1. **Commit** on `worktree-421-folder-flatten`: stage all migration files; **exclude**
   `synonyms.{de,en}.v1.txt` (a CRLF→LF line-ending artifact, not part of this migration) and `tmp/`.
2. **Merge `gate-fixes-main-green` into the worktree branch**, resolving the 2 conflicts above.
3. **Post-merge reconciliation edit** (do AFTER step 2 so it lands on the latest 569): repoint 569's two
   short-form `421/…` references (lines ~17, ~256) — `421/50-decisions/05-plugin-boundary` →
   `docs/decisions/0035-fe-plugin-boundary.md`, `421/10-kernel/04-shape-governance` →
   `docs/reference/ui/frontend-kernel/kernel/04-shape-governance.md`; mark the `472`/`476` slice refs
   historical. (These short-form refs predate this migration and were not caught by the full-slug grep.)
4. **Re-verify in the worktree:** slug grep = 0; `build -x test -x integrationTest -x systemTest -PskipWebBuild=true`
   green; `check-tempdoc-numbers` (572 free); `llmstxt-generate` + `skills-sync` clean.
5. **Merge to `gate-fixes-main-green`** from the main checkout (`F:\JustSearch`, currently on that branch):
   `git merge worktree-421-folder-flatten` (clean after step 2 pre-merged its tip). Re-run the step-4 gate.
6. **Onward to `main`:** `gate-fixes-main-green → main` carries the full green wave (565/567/569 + this
   retirement) as a unit — the user's call on timing.

**Merge caveats:**
- The full `./gradlew.bat build -x test` won't go green in any worktree (npm `-4051` + the live-stack
  `integrationTest` worker-readiness timeout — both environmental, see §5). Use the step-4 static gate +
  slug grep as the pre-merge verification.
- **#558** cross-worktree collision is pre-existing (the `558-presentation-pairs` worktree resolves it);
  this doc's number (572) is collision-free against all current worktrees.
- The graduated ADRs preserve the draft's content with cross-refs to deleted *planning* siblings marked
  historical (banner) rather than fabricated — dead links by design, not gate failures.
- Full ADR-by-ADR currency re-verification (beyond the 564 supersession already marked) is a reasonable
  follow-up, not required to retire the folder.

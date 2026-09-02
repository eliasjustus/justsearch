---
classification: merge-import
tempdoc: 884
---

**The rebalance `884-surface-projection-plus-preexisting-drift.md` routed as its second tracked
item.** That changeset declared 27 `dead-code/silent-growth` findings — 4 from lane B's generated
Surface projection, 23 pre-existing — and said plainly that the real fix was two things: wire the
gate into CI with its input, then rebalance the baseline so the pre-existing 23 stop riding on a
declaration. Both are done in this PR; this file is the record of the second.

**Measured, not assumed.** `npm --prefix modules/ui-web run knip:report` (knip 6.20.0, matching
`modules/ui-web/package-lock.json`) then `node scripts/governance/run.mjs --gate dead-code --mode
gate` on `main`'s content: 27 growth findings, exactly reconciling 884's 23 + 4 now that lane B's
four have merged. Every one of them is pre-existing with respect to this branch —
`git diff origin/main...HEAD --name-only | grep modules/ui-web` returns nothing, which is the
command to re-check rather than take on trust.

**What moved.** `gates/dead-code/baseline.txt` now records what the tree actually contains:

- **19 files newly pinned** (0 → n): `search-v3/{sv3-run 8, Sv3Sidebar 6, Sv3Composer 5,
  sv3-composer-morph 4, Sv3SessionRow 3, sv3-results 2, Sv3Main 1, sv3-record 1, fixtures 1}`,
  `components/TaskList.ts 4`, `generated/schema-types/{surface.ts 4, live-runs-response.ts 2,
  ai-install-status.ts 1}`, `chat/recordEvidence.ts 1`, `documentPane/charAnchor.ts 1`,
  `router/bootstrap.ts 1`, `state/appUpdateState.ts 1`, `state/enrichmentCoverage.ts 1`,
  `views/settingsRegister.ts 1`.
- **8 files raised**: `api/domains/packs.ts 1 → 13`, `generated/schema-types/index.ts 45 → 51`,
  `knowledge-search-response.ts 4 → 8`, `router/navigationHandler.ts 4 → 7`, `api/streams.ts 4 → 6`,
  `api/types/surface.ts 3 → 5`, `api/types/registry.ts 22 → 23`,
  `chat/evidenceProjection.ts 3 → 4`.
- **9 files LOWERED** — a ratchet that only ever moved up would be a permission slip, so the shrinks
  are taken in the same pass: `shell-v0/index.ts 94 → 91`, `conversationListStore.ts 5 → 2`,
  `aiStateStore.ts 9 → 8`, `AgentSessionController.ts 4 → 3`, `effective-policy.ts 3 → 2`,
  `shellContextState.ts 3 → 2`, `URLProjector.ts 2 → 1`, `aiVerdict.ts 2 → 1`, `verdict.ts 2 → 1`.
- **5 rows DELETED** as stale allowances — files whose pins survived after their exports gained
  consumers: `ai-pack-import-status.ts`, `shape-handlers/core-rag-ask.ts`, `i18n.ts`,
  `runControlIntent.ts`, `indexingJobsBridge.ts`. `--rebalance` cannot reach these (it iterates the
  measured counts, and a file with zero findings is not measured), so they would have been permanent
  otherwise.

Result: **186 rows, gate green with 0 findings.** Not green *because of* this changeset — green
because the pin matches the measurement.

**This changeset therefore covers nothing, and that is deliberate.** A `merge-import` declaration
sets `growthCovered`, which suppresses `silent-growth` for the WHOLE gate run; on a branch that
genuinely grew dead code that would be a blanket. Here the baseline already equals the measurement,
so there is no growth to suppress, and the branch touches no `modules/ui-web` file at all. It is
filed because the gate's own README asks for the rationale to live beside the gate, and because a
27-file baseline move with no record is exactly the silent pin-bump the kernel exists to stop.

**Why the numbers can be trusted now in a way they could not before.** Until this PR the gate ran
nowhere — `ci.yml:206-207` said so in its own comment. This PR produces `tmp/knip-report.json`,
`tmp/npm-audit-report.json` and `tmp/arch-preflight/module-deps.json` in the Public-claims job and
runs `dead-code`, `npm-audit` and `module-deps` there, and runs `dead-code-jvm` in the
platform-contracts unit-test lane that already builds its input. From here a regrowth is a red on
the PR that causes it, which is the only condition under which a baseline number means anything.

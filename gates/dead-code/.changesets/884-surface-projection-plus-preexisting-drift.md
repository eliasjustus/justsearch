---
classification: merge-import
tempdoc: 884
---

**This gate has been effectively inert for seven weeks, and producing its input for the first time
surfaced 27 findings at once. Four are this branch's; twenty-three are not.**

Why they appeared together: `dead-code` needs `tmp/knip-report.json`, which nothing produces in CI
(`.github/workflows/ci.yml:206-207` says so outright — "other kernel gates need inputs (knip, npm
audit, a Gradle run) this fast job does not build"), and locally a bare kernel run reports
`kernel/input-missing`, which `scripts/agent-analytics/expected-state.v1.json`'s
`governance-kernel-inputs-unbuilt` pin correctly describes as an unbuilt report rather than a red.
So the gate is wired, registered, and unable to notice anything. `gates/dead-code/baseline.txt` was
last touched 2026-07-16 (#215, the PR that "revived" this gate). Tempdoc 884 PR 2 ran
`--produce-inputs` because its independent review demanded a full-kernel run; that is the only
reason any of this is visible.

**The four that are this branch's** (`git diff origin/main...HEAD -- modules/ui-web/src` returns
exactly three files; the fourth entry is their re-export barrel):

| File | Growth | Why it is legitimate |
|---|---|---|
| `src/api/generated/schema-types/surface.ts` | 0 → 4 | A **generated** projection (`SurfaceWire`, `surfaceWireSchema`, `I18nKey`, `SurfaceRef`). Every generated module exports its full type surface whether or not each symbol has a consumer today; that is what a projection is. The established precedent is the same directory's `index.ts`, already baselined at 45 unused exports. |
| `src/api/generated/schema-types/index.ts` | 45 → 51 | The barrel re-exporting the new module. Mechanical consequence of the row above. |
| `src/api/types/surface.ts` | 3 → 5 | The composing barrel that replaced the hand-written mirror. `surfaceWireSchema` is re-exported for parity with `registry.ts`, and `SurfaceWire` is re-exported as the wire type consumers narrow from. `surfaceCatalogSchema`, added in the same change, IS consumed — `SurfaceCatalogClient.ts` parses through it — which is the point of tempdoc 884 review S1. |

**The twenty-three that are not** — `search-v3/*` (8 files), `packs.ts` (1 → 13),
`TaskList.ts`, `streams.ts`, `navigationHandler.ts`, `evidenceProjection.ts`, `charAnchor.ts`,
`appUpdateState.ts`, `enrichmentCoverage.ts`, `settingsRegister.ts`, `bootstrap.ts`,
`recordEvidence.ts`, `api/types/registry.ts`, `knowledge-search-response.ts`,
`ai-install-status.ts`, `live-runs-response.ts` — are untouched by this branch and arrived through
merged history while the gate could not see them. `merge-import` is the provenance-accurate
classification for the set as a whole: a changeset applies to the whole gate run, not per file, so
declaring `declared-growth` would assert that lane B knowingly traded away dead-code hygiene in
files it never opened. It did not.

**The real fix, routed rather than deferred into silence.** Two tracked items, neither of which
belongs in a governance-loop PR that already touches the sibling `adr-coverage` gate:

1. **Wire `dead-code` (and `npm-audit`, `module-deps`) into CI with their inputs**, or accept
   openly that they are local-only gates. Right now the registry implies enforcement that does not
   happen — the same "shipped but wired to nothing" shape tempdoc 799 Q documented for
   `check-installer-execution-level.mjs` and that tempdoc 884 PR 1 fixed for the kernel's own
   18 test files. Owner: whoever owns the CI fact lanes (ADR-0044).
2. **Rebalance `gates/dead-code/baseline.txt`** once the gate runs somewhere, so the 23 pre-existing
   entries stop riding on a changeset. Owner: whoever lands the ui-web work.

Recorded in tempdoc 884's residue list with this evidence.

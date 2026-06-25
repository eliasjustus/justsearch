---
title: "548 → main merge runbook"
status: done
related:
  - docs/tempdocs/548-ui-web-theoretical-improvements.md
---

# 548 → main merge runbook

## Context

`worktree-548-impl` holds the completed 548 work (74 commits ahead of main). Main has
advanced **170 commits** since the merge-base, but the base is only ~25h old
(2026-05-25) and main's commits are almost entirely **549** (unified search-trace /
explain-panel / per-hit narration) and **550** (action-ledger SSE, navigation gating
via the SourceTier×RiskTier trust lattice, consent ceremony, redo, macros) — design
directions largely orthogonal to 548. Verified facts:

- **Conflict surface = 8 files** (changed on both sides since the merge-base). The other
  ~314 changed files are one-sided → merge clean.
- **Compile-risk-but-not-textual-conflict = 2 files** main changed that my code depends on
  yet my branch did not touch: `plugin-api/plugin-types.ts` (main:6) and
  `operations/OperationClient.ts` (main:4). `api/types/registry.ts` (Provenance /
  OperationResult / Resource) is **untouched by main** → all §4.3 provenance work merges
  clean. Every other 548 file (intentRouter, parser, UnifiedChatView, UserStateDocument,
  ShellAddress.java, the catalog clients, aggregateRegistry, manifest substrate,
  provenance.ts) is main:0 → clean.

## Strategy

Merge **main → worktree-548-impl** inside the worktree (conflicts resolve in isolation),
resolve the 8, re-verify the full stack against main's 314 new files, then fast-forward
`main` from the main checkout. Do **not** merge branch→main blind.

```
# from the worktree
git merge main        # expect 8 conflicts
# …resolve per below…
git commit            # the merge commit
# full re-verify (see Verification), then from F:\JustSearch (main checkout):
git merge worktree-548-impl   # fast-forward / clean merge commit
```

## Per-file conflict resolution (all 8)

1. **`docs/observations.md`** — append-only inbox. Keep BOTH sets of appended lines.
2. **`gates/consumer-drift/slots.json`** — **union**: main populated `slots[]` (G33
   explain-panel mount, §32 autonomy-dial substrates); my §5.2 added the `discovery` block
   with `knownUncovered`. Keep main's `slots[]` entries AND my `discovery` block. Then
   reconcile `knownUncovered`: if any of main's new slots covers a substrate dir listed in
   my `knownUncovered`, the §5.2 closure emits a `stale-grandfather` info hint — prune that
   dir from `knownUncovered` (it's now covered). My enforcer/truth-table changes
   (`scripts/governance/gates/consumer-drift/*`) are main:0 → merge clean.
3. **`gradle/class-size-exceptions.txt`** — take main's current pins (KnowledgeHttpApiAdapter
   realigned to 2328 etc.) + keep my branch's declared-growth entries. Re-run the class-size
   gate after; rebalance if it reports a shrink.
4. **`StatusRecordSchemaTest.java`** — merge main's 549 SearchTrace field assertions with my
   §4.1 lifecycle-enum expectations (additive on both sides). **Recompile.**
5. **`BackendIntentRouterImpl.java`** — take main's full structure (`forwardNavigation` +
   the audit-only SourceTier×RiskTier gating). `ShellAddress` is sealed and my branch added
   `Query`+`Answer` (ShellAddress.java is main:0 → clean), so main's 2-arm switch is now
   **non-exhaustive → add the two arms**:
   ```java
   case ShellAddress.Query ignored  -> forwardNavigation(intent, provenance);
   case ShellAddress.Answer ignored -> forwardNavigation(intent, provenance);
   ```
   This is coherent: `forwardNavigation`'s gating block is guarded by `instanceof
   ShellAddress.Navigation`, so Query/Answer skip the Navigation-only lattice and broadcast
   normally (and the gating is audit-only WARN anyway). Drop my old `forwardToFrontend`
   (main's `forwardNavigation` subsumes its sourceId-resolution logic). **Recompile.**
6. **`Shell.ts`** — take main's 550 changes (Authorize-surfaces consent ceremony, redo,
   macros); re-add my localized `installCoreWalkthroughManifest()` call (beside
   `installCoreDemoManifest`) + its import.
7. **`HostApiImpl.ts`** — take MY restructured 228-line assembler + the six
   `capabilities/*.ts` modules. Main's only change here is the `data.invokeOperation`
   consent routing; **port it into `capabilities/data.ts`** (see Compile-risk ports). Main
   also added three OPTIONAL forward-decl sub-APIs to the interface
   (`introspection?`/`pipelineExecution?`/`provenance?`, `?: unknown`) — main does NOT
   assemble them in `createHostApi` either (they're unwired on both sides), so my assembler
   omitting them is type-safe; no action.
8. **`SearchSurface.ts`** — take main's 549 per-hit narration additions + re-apply my §4.5
   pinned-chip reroute (`navigate-with-context` instead of the direct `setQuery` bypass).

## Compile-risk ports (clean-text, but my code must match main's new signatures)

- **`capabilities/data.ts` ← `plugin-types.ts` + `OperationClient.ts`:** main changed
  `PluginHostApi['data'].invokeOperation` to `(id, params?, opts?: { consented?: boolean })`
  and added `OperationClient.invokeWithConsent` (`invoke` still exists). Update
  `createDataApi`'s `invokeOperation` to take the `opts` param and route through
  `client.invokeWithConsent(id, { args: params }, { consented: opts?.consented === true })`
  — mirroring exactly what main did inline in `HostApiImpl`. This is the single signature my
  capability modules must re-align; `npm run typecheck` confirms the rest.

## Verification (against the MERGED state — this is the gate)

1. `cd modules/ui-web && npm run typecheck` — catches any type drift from main's 314 files
   (esp. the plugin-types signature port).
2. `npm run test:unit:run` — full FE suite (was 2013 green on the branch).
3. `npm run lint` (eslint) — the FSD/wire-types boundaries.
4. `./gradlew.bat build -x test` then `:modules:app-services:test :modules:app-api:test
   :modules:app-agent-api:test` — the Java conflicts (BackendIntentRouterImpl + the answer
   verb + StatusRecordSchemaTest).
5. `node scripts/governance/run.mjs --mode gate` — confirm `consumer-drift` still passes with
   the unioned slots.json; record any ui-bundle/ts-any/class-size deltas. **Gate-drift is now
   partly main's** — main's own observations log a pre-existing class-size failure (549
   KnowledgeHttpApiAdapter growth), so post-merge red gates are not all from 548.
6. **Live smoke** (dev stack + model): re-confirm §4.3(d) welcome walkthrough renders and
   §4.5 cited answer renders, now against merged code (catches runtime breakage the unit
   tier misses).

## Semantic integrations (resolved, not just textual)

- **548 Query/Answer × 550 nav-gating:** route through `forwardNavigation`; its lattice is
  Navigation-guarded + audit-only, so Query/Answer broadcast unaffected. Coherent today;
  when a higher-than-LOW surface lands and enforcement wires on, revisit whether
  agent-emitted Query/Answer should be gated (likely yes, via their lowered target surface).
- **548 capability modules × 550 consent capsule:** consent threading lives only in
  `data.invokeOperation` → ported into `data.ts`. Confirm during the port that 550 didn't add
  consent threading to other sub-APIs (grep main's plugin-types diff for other `consent` on
  `ui`/`registration`/etc. — none seen).

## Decisions / gates before the final fast-forward

- The pre-existing `ui-bundle`/`ts-any` (+ main's class-size) gate drift is a merge-readiness
  decision: fix in-scope or merge with a documented waiver.
- The 548 branch carries cosmetic BOM-prefixed commit messages (from `git commit -F -` via a
  PS pipe) — harmless; not worth a history rewrite.
- Stop before the `main` fast-forward for sign-off (user-gated: "don't merge yet").

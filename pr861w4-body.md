> **Stacked on #549 (861 W2)**, whose identity/record API this consumes; #549 is merged in here so CI is meaningful. **Merge #549 first.** The W4 change is `agent-spawn-reaper.cjs` plus the two `861-w4-*` test files.

## Summary

Phase 4 of tempdoc 861 — the module that answers "may I kill this?", and the one arm that acts on the answer. §7.3 gated this behind A1/A2/A4 landing as text first, because "a worker implements the matrix literally".

`reapEligible` is a pure projection: it kills nothing, writes nothing, reads no directory, and is not even `async`. `executeReap` is the single effectful path. An occasion picks a column and a capability; the matrix decides. Phase 5 can wire six occasions without any of them owning kill logic.

## Changes

**`isVerifiedMatch` is the only kill licence.** Identity is pid AND creation time AND fingerprint; `refuse` is a third verdict value, not a `false`, and licenses nothing.

**The evidence must be fresh at the moment of the kill, not of the decision.** `executeReap` does not accept the projection's verdict as its warrant: it reads the process table itself, immediately before acting, and re-verifies. A snapshot aged past the freshness bound between the two points refuses at execution, with no cooperation required from the caller — #549's F1 TOCTOU review finding, made structural.

**[A1] A lapsed lease is not by itself a licence to kill.** Rev 1's "lapsed -> reap" reproduces `dev-runner.cjs:2102-2113` verbatim — the 2026-07-14 defect where a quiet owner's enrichment wait was reaped while the stack did exactly what it was started for. A quiet owner is not an absent owner. The lapsed column is split on owner activity through the existing `classifyActivity` (`ownership-verdict.cjs:82-92`), with `known: false` treated as leave and the record's declared hold widening the threshold as the dev-runner does.

**[A4] No kill runs from a PreToolUse hook.** Enforced, not trusted: under `capability: 'advisory'` the projection mints no `reap` entries at all — each downgrades to `report` carrying `ceiling: 'reap'` — and `executeReap` refuses anything that is not a `reap`. The default capability is `advisory`, so an occasion that forgot to declare itself gets a report, not a kill list.

**Precedence is part of the specification.** The never-reap dimensions (`ownerless-singleton`, the dev-runner's own active run) are evaluated first, so no later branch can reach a `reap` for them; identity second; only then the owner/lease/activity join, the only part that can return `reap`. `blocksProceed` implements §6.4's "refuses to proceed while an unreapable holder remains". Occasion wiring is Phase 5.

## Testing

Both files sit under `scripts/agent-analytics/` on purpose: per §7.6 `scripts/dev/*.test.mjs` runs in CI nowhere, and the safety-critical tests here are the matrix and the identity branch. Auto-discovery is what makes them run.

`861-w4-reaper-matrix.test.mjs` (67 checks) — 17 rows across 3 columns (sweep; conflict/execute i.e. teardown; conflict/advisory i.e. before-a-build), asserting cell id, disposition, bucket, owner-activity state, and `blocksProceed`. It closes by asserting the set of cells exercised **equals** the module's declared cell vocabulary, so a dropped cell turns the file red instead of quietly vanishing from the coverage claim. It also compiles a **mutant** with the [A1] join replaced by a constant `stale` — rev 1's rule exactly — and asserts the reap-while-working cell flips to `reap`. A test asserting only "lapsed + fresh owner -> contention" would pass against an implementation that never consults activity.

`861-w4-reaper-kill.test.mjs` (13 checks) — adverse branches first, per §6.3's instruction that they are required tests, not happy-path afterthoughts. All three [A2] states end-to-end through `executeReap` (recycled pid; unreadable creation time; table unavailable), each asserting `taskkill` was never invoked and the record was marked failed-verify and **retained**; plus the empty-table case, since `getProcessTable` fails silently to `[]` by design. Then TOCTOU three ways: an aged snapshot refuses at execution; a fresh-snapshot control with the same entry proceeds, proving the refusal is the staleness and not the fixture; and `executeReap` ignores the table eligibility used.

Two tests reach real `taskkill.exe`. **Every victim is a `node -e` child the test spawned three lines earlier**, carrying a random per-run marker matched against the process-table row alongside the spawn's own pid before any record is built — so a discovered pid can never reach the kill.

`node scripts/agent-analytics/run-all-tests.mjs` — 44/44 pass. `./gradlew.bat build -x test` — BUILD SUCCESSFUL. Three source-mutation probes (each reverted) confirmed the [A4] downgrade, the execution-time freshness bound, and the dev-runner never-reap row are each load-bearing.

Session-Id: bccfc163-7b8f-4b1a-b9e4-0c011632d8a1

🤖 Generated with [Claude Code](https://claude.com/claude-code)

---
status: active
created: 2026-08-13
updated: 2026-08-13
---
# 823 addendum — review adjudications from the round-16 wave (#410–#413)

Companion to `823-round-16-findings-and-requalification.md` (landing via PR #410) and
`824-f1-install-reliability-design.md`. Kept as a separate file so it can ride the #413
fixup without conflicting with 823's own branch. Everything here is an **adjudication
recorded at review time**, not new design.

## 1. Publisher change: the upgrade-path consequence (addendum to 823 §2 / F2)

The signing work sets `bundle.publisher`. On this wave's base there is no publisher key in
`modules/shell/src-tauri/tauri.conf.json`, so every installer built before it keyed its NSIS
manufacturer registry path off the default. The change moves that path:

```
Software\justsearch   ->   Software\Elias Justus
```

Two consequences on a machine that already carries a pre-change install:

- **A custom install directory is not restored.** The installer reads the previous
  `InstallDir` from the manufacturer key; under the new key there is nothing to read, so it
  falls back to the default location instead of the directory the user chose.
- **The over-install uninstaller handoff passes an empty `_?=`.** The old uninstaller is
  located through the same key, so the handoff that would normally point the running
  uninstaller at the existing install directory carries nothing.

**Adjudication: no migration shim.** Owner-established fact (tempdoc 772): there are no
public users; the only machines carrying `Software\justsearch` keys are dev and sandbox
machines. Writing an NSIS key-migration for a population of zero is residue by construction.

**What this obliges instead:**

1. Round 17's charter must **pre-register this expectation in its upgrade lane** — the lane
   should expect a default-directory install and an empty `_?=` when over-installing a
   round-16-era build, and treat that as the documented outcome rather than a finding.
2. **Over-install from a round-16-era install must be validated before GA** (the reviewer's
   recommendation, kept on record). "No users today" justifies skipping the shim; it does not
   justify shipping an unexercised upgrade path.

## 2. 824 deviation notes

### (a) BrainSurface post-restart narrowing — a deliberate deviation from §3.3c's "iff"

Design §3.3c reads as an "iff": the full-strength *"a required component is missing"* copy
shows exactly when the capability is not observably active. The shipped narrowing is weaker
in one direction, on purpose.

`deriveRepairRemedy` (`modules/ui-web/src/shell-v0/views/BrainSurface.ts:280-304`) softens the
copy only when at least one package is in state `failed` **and** every such package reads
`functionalStatus === 'active'`. After a restart, `repairNeeded` can come from the disk
recompute while no package is in `failed` state at all — the recompute cannot attribute the
gap to a capability — so `affected` is empty, `allRunning` is false, and the full-strength
copy shows even though the capability is observably active.

This is **fail-closed, commented at the call site, and pinned by test**
(`BrainSurface.repairRemedy.test.ts` — "no observation at all ⇒ fails closed to the
full-strength copy"). Accepted at review as the correct trade: a copy that under-alarms on an
unattributable gap is worse than one that over-alarms. Recorded here so a future reader does
not treat the gap between §3.3c's "iff" and the code as drift.

### (b) F4 is settled at handler depth only

`AiInstallApiContractTest` (`modules/ui/src/test/java/io/justsearch/ui/api/`) settles round
16's "400 with an empty body" by reading raw response bytes — but it stands up **its own
Javalin instance**, not the production route wiring. It therefore proves the handler returns a
typed error body; it does not prove the production server's filter/exception-mapper chain
lets that body through unmodified.

The production-wiring proof remains **round 17's raw `curl.exe -i -X POST …/api/ai/install/repair
-d '{}'` probe** (824 §3.5). Round 17 must run it against the real installed stack; the
handler-depth test is the regression home, not the wiring evidence.

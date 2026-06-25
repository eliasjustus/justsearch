---
title: "531 — Substrate-consumer drift: a gate kind on the 530 discipline-gate kernel"
type: tempdocs
status: active
created: 2026-05-20
updated: 2026-05-25 (consumer-drift gate kind implemented + self-tested on the 530 kernel)
category: discipline / structural-prevention
related:
  - tempdoc 530 (the discipline-gate kernel this ships as a gate kind on)
  - tempdoc 527 (the one-shot manual audit this generalizes)
  - .claude/rules/agent-lessons.md §"Substrate discipline" (C-018 + the §X.11 refinement)
  - tempdoc 524 (a prior, narrower audit on core-contracts)
  - tempdoc 511 (the lint rule on api/types/registry — first instance of structural enforcement for one substrate slot; generalizes into one row of this gate's config)
  - scripts/contract-governance/README.md (the kernel design template 530 broadens; same SARIF + changeset protocol applies here)
---

# 531 — Substrate-consumer drift as a gate kind

## Note on this rewrite

The first draft of 531 (2026-05-20 morning) proposed its own `substrate-slots:` YAML manifest + its own pre-merge grep-counting script + its own pass/fail output format. That draft was structurally correct in *what it should check* but wrong about *where the checking machinery lives*: it landed as the fifth bespoke ratchet implementation in the codebase, with its own escape-hatch protocol, its own baseline format, its own output shape. The rewritten 530 (2026-05-20 afternoon) named exactly this drift — "tempdoc 531 — substrate-consumer drift — proposes its own `substrate-slots:` YAML manifest + its own grep-counting script" — as evidence for why a unified gate kernel is needed.

This rewrite reframes 531 as **one application of the 530 kernel**, not a parallel tool. The design substance — what to check, how to scope consumers, what grace-period semantics mean — is unchanged. What changes is that the enforcement mechanism, the baseline format, the escape-hatch protocol, and the output format are all the kernel's, not 531's.

## The pressure (unchanged from the audit-go-stale framing)

Tempdoc 527 was a one-shot manual audit. It surfaced three concrete hollows in real production code:

- `VirtualOperationCatalog` — `bootVirtualOperationCatalog()` never called from production; `resolveAgentToolCall` always returns null; live-verification claims in 521 §14.β2 were test-only.
- `host.ai` streaming bridge — zero production callsites for `streamShape` / `invokeShape` / `openSession`.
- 9 of 14 `PluginHostApi` sub-interfaces — fully implemented but called only by `HostApiImpl.ts` internal bridging.

The audit's verdict is **stale within a release cycle**. By the time 521/527 reviewers next look, the inventory will have shifted; the next set of hollows will have grown by other shapes; the substrate-without-consumer pattern will reappear under different names.

C-018 is a project discipline (substrate must name a consumer at landing). The discipline holds case-by-case but has **no recurring enforcement**. Tempdoc 511 added a lint rule against raw `api/types/registry` imports — that was one slot. Generalizing requires recurring-CI enforcement across many slots.

## The idea — `consumer-drift` as a 530 gate kind

The 530 kernel exposes "gate kinds" as configurable enforcers under a central registry. Each gate kind defines: how to compute per-target current value, what baseline to compare against, what escape-hatch protocol applies when the current diverges from the baseline.

`consumer-drift` is one such gate kind. Its responsibilities:

- **Enforcer module.** Produces `(slot_id, current_consumer_count)` tuples by grep-counting production callsites for each declared substrate slot, scoped per the slot's own `where` filter. The enforcer is a thin module — its only job is the count; the kernel handles compare, classify, output. ~50 LOC of TypeScript-or-Node.
- **Baseline strategy.** Per-slot `min` consumer count from a config file under `gates/consumer-drift/slots.yaml` (or whatever directory layout the kernel adopts under §530 Open Question "Registry split vs unified"). Each row:
  ```yaml
  - id: virtual-operation-catalog
    declared-in: modules/ui-web/src/api/operations/VirtualOperationCatalog.ts
    where:
      include: ["modules/**/src/main/**", "modules/ui-web/src/**"]
      exclude: ["*.test.{ts,java}", "**/test/**", "modules/ui-web/src/.../HostApiImpl.ts"]
    expected-min: 1
    grace:
      until: "tempdoc 532 ships consumer-or-retraction"
      # or: a commit-count bound, or a date.
  - id: host-ai-streaming-bridge.streamShape
    declared-in: modules/ui-web/src/.../HostApiImpl.ts
    where: ...
    expected-min: 1
    grace:
      until: "tempdoc 533 ships first plugin"
  ```
- **Escape-hatch protocol.** The kernel's universal `.changesets/<id>.md` mechanism. When a slot's `expected-min` is unmet *after* its grace expires, the gate fails. The only way to merge without satisfying the count is a changeset declaring classification — `slot-retraction` / `grace-extension` / `emergency-override` — with tempdoc reference. The truth-table classifies the diff (e.g., `slot-retraction` requires the declared-in path to be deleted in the same commit; mismatch → `misclassification` verdict).
- **Output.** SARIF v2.1.0 via the kernel. Same feed everything else goes to. Human-readable summary derives from SARIF; no parallel report format.
- **Grace semantics.** Three legal expirations:
  - **Tempdoc-bound**: `until: "tempdoc N ships X"` — gate consults a small index (or just looks for closure in tempdoc N's status field; design TBD at kernel-broadening time).
  - **Commit-bound**: `until: 30-commits-from-landing-commit`.
  - **Date-bound**: `until: 2026-07-01`.
  Each compiles to a kernel-recognized deadline; expiry without consumer → gate fires; satisfy or changeset.

## What changes vs the original 531

Same checking semantics; different host. The substance:

| Aspect | 531-old (parallel tool) | 531-new (gate kind) |
|---|---|---|
| Manifest format | Custom `substrate-slots:` YAML | Per-gate config under `gates/consumer-drift/` (kernel-prescribed) |
| Pre-merge runner | New script `scripts/ci/check-substrate-drift.mjs` | Kernel's enforcer driver invokes the `consumer-drift` enforcer module |
| Baseline storage | Inline `min:` per slot | Kernel-handled; same format every gate kind uses |
| Escape hatch | Implicit (edit the `min:`) | Mandatory `.changesets/<id>.md` declaring classification + tempdoc ref; truth-table validated |
| Output format | New pass/fail text + JSON | Same SARIF v2.1.0 as every other gate |
| Grace-period syntax | `grace-period: 30-commits-from-landing` (slot-local) | Kernel-prescribed deadline DSL; same shape across all gate kinds |
| Reviewer mental model | New tool to learn | "Another row in the gate registry" |

Net effect: the same audit becomes ~30 lines of enforcer config + one ~50-LOC enforcer adapter, vs a fresh parallel tool with its own learning curve.

## What this dissolves

- **The "one-shot audit goes stale" failure mode.** Audits become a CI artifact under the kernel; the consumer-drift gate fires every commit, not every reviewer cycle.
- **The asymmetry between substrate slices** that ship with a Pass-8 component-callsite check (post-509 D1 discipline fix) and substrate slices that ship without it. The post-Pass-8 closure artifact for any substrate-shipping slice becomes "add the slot to `gates/consumer-drift/slots.yaml`," concretely.
- **Reinvented enforcement.** No new ratchet implementation; no new escape-hatch protocol; no new output format. The kernel's investment pays back here.

## What this is NOT

- **Not a parallel implementation.** Where 531-old proposed standalone tooling, 531-new is a configuration application of 530. If 530 stalls or reframes, 531 reframes correspondingly — not the other way around.
- **Not a substitute for human-judgment audits.** 527 found nuances (single-consumer fragility, in-flight vs hollow, "named follow-up vs unnamed pending") that a count-based gate won't reproduce. The gate catches the *gross* C-018 failure; humans still calibrate edges.
- **Not a hard rule that every substrate ships with its consumer.** Grace periods are explicit and changeset-classifiable. The kernel's `.changesets/<id>.md` protocol is the audited extension mechanism.
- **Not a tool for backfilling old slots.** Existing substrate gets grandfathered into the gate's config with current consumer counts as `min`; the ratchet starts from where things are. Same approach as `gradle/class-size-exceptions.txt` for the class-size gate.

## Composition

- **530 (the kernel)** — 531 cannot ship before the kernel admits `consumer-drift` as a registered gate kind. Hard dependency.
- **511** (lint rule on `api/types/registry`) — generalizes into one row of `gates/consumer-drift/slots.yaml`. The existing ESLint rule can stay as the fast-path for that specific import shape; the consumer-drift gate covers the broader "any slot, any callsite shape" axis.
- **524 (core-contracts audit)** — that audit's verdict was "no deletions warranted" based on widened search methodology. The gate doesn't override that verdict; instead, the four contract-governance types it found (`ContractSampler`, `BootContractValidator`, `BootContractRegistry`, plus the annotations) each get a row in the gate's config with their measured consumer counts as `min`. The verdict becomes the gate's seed.
- **527 (the audit)** — 527's three named hollows become the gate's first three rows on landing. Each row's `grace.until` references the relevant successor tempdoc:
  - `virtual-operation-catalog` → `until: "tempdoc 532 ships consumer-or-retraction"`
  - `host-ai-streaming-bridge.*` → `until: "tempdoc 533 ships first plugin"`
  - `plugin-host-api.<sub-interface>` (per row) → `until: "tempdoc 533 ships first plugin"`
- **532, 533** — the two tempdocs whose grace deadlines anchor the initial gate rows. If 532 ships retraction, the slot row gets deleted in the same commit (changeset classification: `slot-retraction`). If 533 ships the first plugin and a sub-interface gains its first consumer, the gate auto-recompiles its baseline.

## Open questions (now narrower — kernel resolves most)

- **What counts as a "consumer"?** Static type reference vs method invocation. 527's audit distinguished: type reference can be incidental; method invocation is real consumption. The `consumer-drift` enforcer should bias toward method invocation by default, with a per-row `match: type-reference | method-invocation | both` override for slots whose consumer shape is intrinsically about static reference (e.g., a sealed-sum permits clause).
- **Performance.** Grep-counting across the whole codebase per slot per commit is the cost; at ~20 slots × ~10K source files × seconds, probably tens of seconds end-to-end. Probably fine; revisit if it bites. Kernel-side caching of file hashes across runs is the optimization if needed.
- **Tempdoc-status references in grace clauses.** `until: "tempdoc 532 ships consumer-or-retraction"` needs to be resolvable mechanically. Option A: tempdoc front-matter `status` field with a closed-set vocabulary; the gate parses front-matter. Option B: a registry of "tempdoc N is closed in commit X" entries. Lean: A is lighter and already exists; the gate-resolvable status vocabulary is a kernel concern (since other gate kinds may need it).
- **Open inherited from 531-old, still relevant**: which Pass-8 verdicts are responsible for *adding* rows? The cleanest closure discipline is "any Pass-8 verdict on a substrate-shipping slice must commit the corresponding row to `gates/consumer-drift/slots.yaml` as part of the verdict artifact." But this is a process question, not a kernel question; record here, settle when 530 lands.

## Implementation log (2026-05-25)

The `consumer-drift` gate kind shipped on the (now-landed) 530 discipline-gate
kernel, modeled on the `class-size` ratchet gate. Files:

- `scripts/governance/gates/consumer-drift/{enforcer,truth-table,classifications,rule-descriptions}.mjs`
- `gates/consumer-drift/slots.json` (the `ratchet-file` baseline) + `.changesets/`
- Registry entry in `governance/registry.v1.json` (id `consumer-drift`)
- Self-test fixtures `scripts/governance/_fixtures/consumer-drift/{positive,negative}/`

**How it works.** Each slot in `slots.json` declares `{ id, symbol, declaredIn,
includeGlobs, excludeGlobs, expectedMin, grace? }`. The enforcer counts
production files (include − exclude − declaredIn) referencing the slot symbol;
`truth-table.verdictForSlot` maps `(count, expectedMin, withinGrace,
classification, declaredInExists)` to pass/fail/info. Below-min drift fails
unless within a grace window or covered by a `gates/consumer-drift/.changesets/`
changeset classified `slot-retraction` (declaredIn must be deleted) /
`grace-extension` / `emergency-override` (latter two require tempdoc/adr
justification, enforced by the kernel's changeset-loader). Stale-slot
(declaredIn vanished without a retraction) and changeset-mismatch (changeset
names an unknown slot) also fire.

**Verification.** `node scripts/governance/run.mjs --gate consumer-drift
--self-test` → positive pass / negative fail; full-kernel `--self-test` green
across all 14 gates (no regression); real `--gate consumer-drift` run → pass,
0 findings. `truth-table.test.mjs` covers all 8 verdict branches + the
classification indexer (10 checks).

**First-slice scope / deferred.**
- **Production slots: intentionally empty on landing.** The gate mechanism is
  proven by the fixtures; real slots are populated *after measuring* each
  substrate's current consumer count and grandfathering `expectedMin` to it
  (ratchet-from-here, like class-size). **527's three hollows must be
  re-measured, not seeded verbatim** — tempdoc 543 changed several (e.g.
  `VirtualOperationCatalog` is now wired via 543 W10/W13), so the 527 inventory
  is stale.
- **Grace DSL:** only date-bound (`grace.untilDate`) is implemented. The
  tempdoc-bound (`until: "tempdoc N ships X"`) and commit-bound variants from
  §grace-semantics are deferred (they need a kernel-resolvable tempdoc-status
  vocabulary — a kernel concern, per the original open question).
- **Consumer match-type:** counts symbol occurrences (word-boundary). The
  `match: type-reference | method-invocation | both` refinement (open question
  on what counts as a consumer) is deferred.
- **Tier-register row / 511 ESLint-rule folding** (compose with 511) not yet done.

### Critical-analysis pass (2026-05-25)

A hostile-reviewer pass on the first slice surfaced three defects, now fixed:

- **B — baseline-tampering guard added.** The first slice read only the *live*
  `slots.json`, so lowering a slot's `expectedMin` or deleting a slot in the
  same commit was an invisible bypass — the exact silent-pin-bump hole the
  class-size gate closes. The enforcer now reads `slots.json` at the baseline
  ref (`readPriorSlots`, mirroring class-size's `readPriorRatchet`) and fails on
  `silent-floor-drop` (expectedMin lowered without a `grace-extension` /
  `emergency-override` changeset) or `silent-slot-removal` (slot dropped without
  a `slot-retraction`). Raising a floor is `floor-raised` (info). Degrades
  gracefully when no baseline ref is available.
- **C — escape/grace/stale paths now integration-tested.** The self-test
  fixtures only exercised healthy/below-min. Added `enforcer.test.mjs` (9 checks)
  driving `enforceConsumerDrift` over scaffolded temp trees: changeset escape
  (slot-retraction + grace-extension), date grace + its boundary, stale-slot,
  changeset-mismatch, and the B tampering paths. `truth-table.test.mjs` extended
  to 17 checks (the two tampering verdict fns).
- **D — grace off-by-one fixed.** `isWithinGrace` now treats `untilDate` as
  inclusive through the end of that UTC day (was expiring at 00:00).

Remaining honest caveats (NOT yet addressed): **the gate still enforces nothing
on the real repo until slots are populated** (production `slots.json` is empty by
design); and consumer counting is **symbol-occurrence** based, so comments /
string literals / barrel re-exports can over-count and mask drift (the
`match: method-invocation` refinement remains deferred).

### Independent-review fixes (2026-05-25)

A second (independent) reviewer pass found two more defects, now fixed +
tested (enforcer.test 9 → 10 checks; truth-table.test stays 17):

- **M1 — malformed `slots.json` no longer crashes the whole governance run.**
  `loadSlots`' `JSON.parse` is now wrapped; a parse error returns a `fail`
  verdict with a `consumer-drift/malformed-slots` finding instead of throwing
  past the unguarded `run.mjs` dispatch loop (which would abort every other
  gate). Fails-closed, loud, contained.
- **L1 — stale-slot no longer double-fires.** When `declaredIn` vanished
  without a `slot-retraction`, the slot emitted both `below-min` and
  `stale-slot`. `stale-slot` now supersedes (the loop `continue`s); the test
  asserts `below-min` is absent.

The reviewer confirmed the H2 over-count and M2 empty-slots caveats above are
the real residual limitations (both documented, not silent).

### Why production slots are still empty — population is blocked (2026-05-25)

An attempt to populate ≥1 real slot (closing finding A) surfaced a genuine
blocker, recorded here so the next agent doesn't re-discover it:

- **The at-risk substrates are off-limits.** The substrates actually *prone* to
  going consumerless — 527's hollows (`VirtualOperationCatalog`, `host.ai`
  streaming bridge, the 9-of-14 `PluginHostApi` sub-interfaces) — all live in
  `modules/ui-web` (FE), are owned by the in-flight 543 agent, and are **stale**
  (543 wired several, e.g. VirtualOperationCatalog via W10/W13). Seeding them
  now would conflict with active work and mismeasure.
- **541's composition slots don't fit the counting model.** They are
  method-chain expressions (`ServicePhase.Output.workers().search()`), not
  greppable type symbols; symbol-occurrence counting over-counts them wildly.
- **Healthy backend type slots are low-value + over-counted.** `OperationLeaseService`
  (542) is greppable (13 refs) but stable/healthy (guarding it adds little), and
  the count includes the impl + `NoOp` + sibling api types — the H2 over-count
  in practice, forcing a soft floor.

**Conclusion:** sound population needs *either* the H2 `match: method-invocation`
/ import-aware counting refinement first, *or* coordination with the 543 agent
to seed the FE hollows once they stabilize post-merge. Forcing a slot today
would be undisciplined (merge conflict, or a noisy/speculative guard). The gate
ships as a verified, ready mechanism; it stays inert until a sound slot exists.

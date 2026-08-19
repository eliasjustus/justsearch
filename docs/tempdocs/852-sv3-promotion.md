---
number: 852
title: The window cutover — promoting Search v3 to the one interaction surface
status: IN PROGRESS — S0 implemented by this PR; S1+ pending
created: 2026-08-19
scope-of-this-file: S0 only. The full program charter (target end state, the parity ledger,
  the slice DAG S1-S11, the open questions) lives with the orchestrator and lands here as the
  program's later slices land. This file exists so the S0 code has its design of record in-repo
  rather than only in a PR body.
forcing-function: `check-window-cutover` (shipped with 851) WARNs until 2026-09-30 and FAILS
  after, keyed on (a) `core.search-v3-surface` audience USER in CorePlugin.ts and (b) the
  `governance/window-cutover.done` marker the program's final slice writes.
---

## Status

| Slice | What | State |
|---|---|---|
| **S0** | FE↔Java surface-parity leg on `check-surface-composition` | **implemented (this PR)** |
| S1-S11 | substrate, parity ports, the flip, the sweeps, the rename | pending |

S0's second half — recording that record-attribute hydration lives in
`modules/ui-web/src/shell-v0/components/chat/` (adopting 847-S1's location) — is a decision
addressed to the 847 and 848 implementers and carries no code. It is noted here and not
duplicated as an entry above.

## S0 — the FE↔Java surface-parity leg

### Why this exists

Three facts, each fine alone, compose into one specific hole:

1. The `interaction-surface` gate parses `new Surface(...)` declarations out of
   `modules/app-observability/src/main/java/io/justsearch/app/observability/surface/CoreSurfaceCatalog.java`
   **only** (`governance/interaction-surfaces.v1.json` `scan`). Its FE leg scans for a second
   `registerViewFactory` mount, and its `feMirror` is `coreInteractionShapes.ts` — a *shape*
   mirror, not a surface/audience mirror.
2. `check-window-cutover.mjs` keys "the promotion is complete" on
   `registeredAudience(CorePlugin.ts)` plus the marker file (`:79-88`, `:129-130`) — i.e. it reads
   **only** the TypeScript side.
3. `check-surface-composition.mjs` already read *both* files, but only to resolve dangling
   member refs against the merged id set. Its own comment recorded the limit: the RAIL set was
   "Java-only (the only placement this static gate authoritatively knows)".

Composed: **an implementer who flips `audience` in `CorePlugin.ts` alone ships two USER/RAIL
interaction windows, passes every gate, and satisfies the 2026-09-30 forcing function.** The gate
meant to prevent the outcome cannot see the FE registration; the gate enforcing the deadline reads
nothing else. Neither is wrong on its own.

`core.search-v3-surface` has **no entry in `CoreSurfaceCatalog.java` at all** today, so the
`interaction-surface` gate is currently blind to search-v3 on every leg — it is not "allowing" a
second window, it cannot see one. The safety S8 (the flip) depends on is created *by* S8 unless
something arms it earlier. That is what S0 is.

### What shipped

A second leg on `scripts/ci/check-surface-composition.mjs` — a leg on plumbing the gate already
had, not a new gate:

> Any surface id declared in **both** `CorePlugin.ts` and `CoreSurfaceCatalog.java` must agree on
> `audience` and `placement`. A disagreement fails the build.

- **Comment-stripped first**, both sources, using the same technique and for the same reason as
  `check-window-cutover.mjs` `stripComments` — a commented-out or merely discussed registration is
  not a declaration. (This also hardened leg 1's Java parse, which previously matched
  `Placement.*` inside comments; leg 1's result on `main` is byte-identical either way —
  `4 host(s), 7 member(s)` before and after.)
- **One-sided surfaces are out of scope.** FE-only (`core.search-v3-surface`,
  `core.memory-surface`, `core.command-palette`) and Java-only (`core.ask-surface`,
  `core.system-surface`, `core.free-chat-surface`, `core.extract-surface`) ids are not compared:
  there is no second declaration to disagree with. 14 of the 21 ids are compared today.
- **The failure message names both files, both values, and the rationale**, because a gate whose
  message does not explain the hazard gets "fixed" by editing whichever side is nearer.
- The script was refactored to export pure functions (`parseJavaSurfaces`,
  `parseCorePluginSurfaces`, `checkComposition`, `checkParity`, `run`) so both legs are testable
  without a repo on disk. Leg 1's rules and messages are unchanged.

### The pre-existing drift this leg found

Arming the leg immediately surfaced two live disagreements that predate it:

| Surface | `CorePlugin.ts` | `CoreSurfaceCatalog.java` |
|---|---|---|
| `core.health-surface` | `audience: 'OPERATOR'` | `Audience.USER` |
| `core.activity-surface` | `audience: 'OPERATOR'` | `Audience.USER` |

The FE re-declaration wins in the shell (`CorePlugin.ts:45-53` states this for `core.help-surface`
in as many words: "this FE re-declaration would otherwise override the wire's placement"), so the
wire catalog is currently wrong about who can see Health and Activity. `core.logs-surface`, the
third System-hub member, agrees at OPERATOR on both sides — which is what makes these two look
like drift rather than a rule.

**Which side is right is a product decision about who sees Health and Activity, not a gate fix**,
and it is outside S0's scope. So they are recorded rather than silently tolerated, in the script's
`KNOWN_PARITY_DRIFT` ledger, with three properties that keep it from becoming an escape hatch:

1. Each entry **pins the exact pair of values**. The pinned pair warns.
2. **Any change to that pair fails** — settled (delete the stale entry) or drifted further (that is
   new drift, and the ledger does not exempt it).
3. **An entry naming a pair no longer declared in both files fails** as residue.

`core.search-v3-surface` and `core.unified-chat-surface` are deliberately not in the ledger —
those are the ids S0 exists to protect. Logged to the observations inbox as a pre-existing issue.

### Mutation probe — evidence the leg bites

A gate armed slices before the thing it protects is the shape that ships inert, so the bite was
verified against the real tree, not only against fixtures. `core.search-v3-surface` cannot serve as
the probe subject (it has no Java entry), so `core.unified-chat-surface` — the shipped USER/RAIL
window, and the closest analogue of the flip — was mutated on the FE side only:

| # | Mutation (`CorePlugin.ts` only) | Result |
|---|---|---|
| 0 | none | **PASS** (exit 0) — `4 host(s), 7 member(s); 14 surface(s) … checked, 2 recorded pre-existing disagreement(s)` |
| 1 | `audience: 'USER'` → `'DEVELOPER'` | **FAIL** (exit 1) — `core.unified-chat-surface declares audience 'DEVELOPER' in …/CorePlugin.ts but 'USER' in …/CoreSurfaceCatalog.java. … a ONE-SIDED flip ships a broken or duplicated USER window while every other gate stays green …` |
| 2 | `placement: 'RAIL'` → `'DEEPLINK'` | **FAIL** (exit 1) — same message shape, on `placement` |
| 3 | reverted | **PASS** (exit 0), output identical to #0; `git diff` shows no change to `CorePlugin.ts` |

A second probe checked **test precision** rather than gate bite: replacing `stripComments` with the
identity function turned exactly the four comment-handling assertions red
(`FAIL (4 of 28)`) and nothing else — so those tests pass because stripping works, not because the
fixture happened to parse. Reverted.

### Tests

`scripts/ci/check-surface-composition.test.mjs` (new, 28 assertions, the bare-node style of
`check-window-cutover.test.mjs`): parser behaviour; agreement passes; audience mismatch fails;
placement mismatch fails; both fields disagreeing yields one failure per field; a one-sided
search-v3 promotion fails on both fields; FE-only and Java-only surfaces are not compared; four
comment cases (line-commented FE field, block-commented FE field, a commented-out whole FE
registration placed *after* the live one so it cannot pass by overwrite order, commented-out Java
field); the ledger's warn / drifted-further / settled / residue paths; and leg 1's four composition
rules plus the 578-Option-A merged-id-set resolution, so the refactor cannot silently regress them.

### Routing, and one limitation left open

The leg adds a second **subject** to this gate: `CoreSurfaceCatalog.java`. The gate's existing
wiring — the `ui-web-gates` recipe in `governance/consult-register.v1.json`, pushed by the consult
hook on any `modules/ui-web/src/**` edit — only covers the FE side, so a Java-only edit would not
have reached it. The CLAUDE.md pre-merge row for `CoreSurfaceCatalog.java` now names
`check-surface-composition` alongside `--gate surface-altitude` (`check-premerge-table` green:
49 script refs, all resolving). `governance/surface-composition.v1.json`'s description was
updated to describe both legs rather than only the first.

**Open:** neither this gate nor its test is wired into `.github/workflows/ci.yml` — that is the
pre-existing arrangement for this gate and is unchanged here, so the leg is enforced by the
pre-merge/consult path, not by hosted CI. Recorded rather than fixed: adding a gate to CI is a
workflow change with its own review surface, and S8 lists `check-surface-composition` explicitly
in its required-gate set regardless.

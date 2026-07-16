---
title: "740 — The always-loaded budget: a real ratchet, never wired, asserted as fact"
type: tempdoc
status: charter — investigated + measured, NOT implemented. One owner decision open (§5): wire it as a gate, or delete it and correct CLAUDE.md.
created: 2026-07-15
updated: 2026-07-15
related:
  - 620 Move 3 (the design this ratchet came from)
  - 618 §13 (the `--bump` auditable-raise protocol)
  - 681 (the -24% instruction-layer re-baseline — the ratchet's one big success)
  - 739 (the sibling case: a control that decayed because agents rationally discounted it)
  - .claude/rules/tier-register.md (the tier model this is a data point for)
---

# 740 — The always-loaded budget: real, never wired, asserted as fact

## 1. Question

`CLAUDE.md` states, as fact, in the rule that governs adding rules:

> "the always-loaded-budget ratchet **caps its bytes** because bloat makes rules
> *less* followed"

Does it? Measured 2026-07-15: **no**. Four always-loaded files are over ceiling
by **5,174 B total** (`CLAUDE.md` +1604, `branch-safety.md` +1892,
`tier-register.md` +1579, `hooks-reference.md` +99), and nothing reports it.

The first framing of this was "an unwired script nobody runs — probably dead
code." That framing is **wrong** and the correction is the point of this doc.

## 2. It existed, and it worked

`scripts/ci/check-always-loaded-budget.mjs` + `scripts/ci/always-loaded-budget.v1.json`
are not vestigial. They were designed (620 Move 3), baselined, and **actively
used by hand for three weeks**:

- **13 audited `--bump` raises** in `baseline.bumps`, 2026-06-21 → 2026-07-08,
  each carrying a written reason (e.g. *"618 Seam C: log-pre-existing-issues rule
  now points at note-observation.mjs…"*).
- The earliest bump is stamped **2026-06-21 — four days before this repo's first
  public commit**. The tool predates the cutover; its real history is squashed
  away and not visible here.
- `f604144b` (2026-07-07, PR #78) is *"instruction-layer re-baseline —
  always-loaded set −24%"*: content migrated out, ceilings ratcheted down. The
  mechanism doing exactly what it was built to do.

## 3. But it never held

Red/green at every commit touching an always-loaded file or the baseline
(24 commits scanned):

| Date | Commit | State |
|---|---|---|
| 2026-06-25 | `29579e51` | **RED** (3 files, +2138 B) — red at the first public commit |
| 2026-07-01 | `9d8328b8` | GREEN |
| 2026-07-01 | `8a74c568` | **RED** (+6 B) — same day |
| 2026-07-07 | `f604144b` | GREEN — the 681 re-baseline |
| 2026-07-11 | `e47305ff` | **RED** (+114 B) |
| 2026-07-15 | working tree | **RED** (4 files, +5174 B) |

Green twice, days each, in a 20-day life. The drift is accelerating:
**+6 B → +114 B → +5174 B**. It has been red since 2026-07-11 and grew ~45× past
the ceiling in that window, because nothing surfaces it.

## 4. Diagnosis

**Two failures, and the second is the interesting one.**

**(a) The escape valve became the path.** 13 raises in 17 days is one every ~32
hours. The ratchet's guarantee is one-directional ("the ceiling never ratchets
up"); `--bump` was added (618 §13) as the explicit, auditable exception to that.
A cap that is raised every other day is not capping — it is recording its own
surrender in well-formatted JSON. The bump trail is honest and well-designed;
that is precisely what makes it legible as a failure.

**(b) It is the only sibling that was never wired.** The script's own docstring
names its model: *"the same shape as the class-size / ui-bundle / npm-audit
ratchets."* All three of those are registered gates. This one appears in **no CI
workflow, no `governance/registry.v1.json` entry, no hook, no `.githooks/`
entry** — verified by pickaxe (`git log -S`) across all of public history, not
just the current tree. Its only enforcement is the `CLAUDE.md` sentence quoted
in §1.

So it is **prose-tier** (~70% by this repo's own tier model) for a rule whose
whole purpose is mechanical byte-counting — the one thing prose is worst at and
automation is best at. It decayed to ~70%, then to zero.

This is 739's shape, one rung lower. `docs-granularity-hint` at least *fired*, so
agents discounted it (739 F-5). This one never fires, so there is nothing to
discount — it silently stopped being true, while the file that governs
rule-additions still tells every agent, every session, that a cap is in force.

**The defect is not the 5,174 B. It is a control whose absence is invisible and
whose presence is asserted as fact.**

## 5. Open decision (owner)

Trimming to green and leaving it unwired is **not** an option: that is the state
that has already failed twice (2026-07-01, 2026-07-11). The measured pattern says
it returns to red within days.

- **(A) Wire it as a gate** — like its three named siblings. Already built,
  baselined, with a `--bump` protocol for accountable raises. Registration is
  small. Then 5,174 B becomes a real decision (trim, or bump-with-reason) instead
  of a number nobody sees. **Recommended.**
- **(B) Delete it and correct `CLAUDE.md`** — if the conclusion is that the
  always-loaded set should grow freely, say so and drop the claim. Honest, and
  cheaper than (A).

Either resolves the lie. Only (A) resolves the bloat.

## 6. Evidence

| Claim | How |
|---|---|
| 4 files over by 5,174 B | `node scripts/ci/check-always-loaded-budget.mjs` (exit 1) |
| 13 bumps, 2026-06-21 → 2026-07-08 | `baseline.bumps` in `scripts/ci/always-loaded-budget.v1.json` |
| Never in CI / registry / hooks | `git log -S 'always-loaded-budget' -- .github/ governance/ scripts/agent-analytics/hooks/ .githooks/` → no wiring commit in any of them; the one `governance/` hit is prose in `consult-register.v1.json`'s `$comment` about the always-loaded *layer*, not this check |
| Red/green timeline (§3) | Per-commit scan of the 24 commits touching `CLAUDE.md` / `.claude/rules/` / the baseline, comparing each file's byte size to that commit's own ceilings |
| Siblings are gates | `class-size`, `ui-bundle`, `npm-audit` in `governance/registry.v1.json`; the first two carry standing-red per `expected-state.v1.json` |

## 7. Unverified assumptions

- **Pre-cutover history is invisible.** Public history begins at `29579e51`
  (2026-06-25, squashed). The 2026-06-21 bump proves the tool predates it, but
  whether it was ever *wired* before the cutover cannot be answered from this
  repo. "Never wired" is a claim about public history only.
- **Why maintenance stopped on 2026-07-08 is not established.** The last baseline
  touch is `483e47bf`; after it, growth with no bumps and no rebalance. Whether
  that was a decision or a lapse is not knowable from the artifacts.
- **The 5,174 B is not attributed.** Which additions caused it, and whether each
  was worth its bytes, is un-analysed. (A) makes that analysis necessary; this
  doc does not do it.
- **`--rebalance`'s behaviour on a red file is not re-verified here** — the
  docstring says it only lowers ceilings for files that shrank, so it cannot
  paper over the current red. Taken from the docstring, not probed.

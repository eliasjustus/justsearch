---
title: The Enforcement-Strength Ladder — maturing the governance substrate (530)
type: tempdocs
status: >-
  implemented (2026-06-15) — the §3–§6 enforcement ladder is occupied (§6 rung-1 token migration; §3.1
  bare-self unrepresentable + exempt; §3.2 rung-2 PIT-proven; §5 vacuous-pass across all scan gates +
  the shared guard-downgrade detector) AND 530's Layer 3 (legibility: --explain + run-history +
  dashboard JSON + the GovernanceView surface) and Layer 4 (/api/governance/state) are built, with the
  Appendix-B token completions (APCA signal · target-ratio derivation/floor · Token Editor nudge). See
  §13–§15 (as-built). Out of scope by design: the Wall-1 undecidable "a projection was removed" residue,
  and occupying the mutation-floor on non-seam registers.
created: 2026-06-11
updated: 2026-06-15
supersedes-title: Forward Research — Building on the 403 Review-Fix Designs
---

# 576 — The Enforcement-Strength Ladder

> **Nature of this doc.** A long-term **design theory**, not an implementation plan and not
> short-term fixes. It started as forward-research on the 403 Round-5 designs (the `covers:`
> ratchet exception, the execution-surfaces register guard, the text-grade a11y token) and,
> after reading the existing governance substrate (tempdoc 530 and the register family it
> hosts), resolves into a single conceptual structure. **No code was changed.** General, not
> implementation-level, per the brief. Prior-art evidence + the original tiered idea-catalog
> are preserved in the appendices. **§9 (2026-06-11) records a feasibility de-risking pass** that
> tested §3-§6 against the code and recalibrated their effort/leakiness — read it alongside them.
> The conceptual structure (§0-§7) survived intact; §9 corrects the "clean, uniform" framing.

---

## 0. The thesis in one paragraph

The three research threads (ratchet exceptions, accessible tokens, the register family) are
**not three designs** — they are three views of one thing the repo is already building: a
**governance substrate** that takes an invariant the system cares about and pushes its
*enforcement* as high as it can go on a ladder of strength, from "the violation is
unrepresentable" down to "a human will probably notice." The correct long-term design is not a
new system. It is to **name that ladder explicitly, make the substrate provide first-class
support at every rung, and forbid silent movement *down* it** — the same anti-silent-downgrade
discipline the kernel already enforces for ratchet baselines, generalized to *every* form of
enforcement. Everything concrete in this doc is an **extension of tempdoc 530's already-stated
four-layer kernel**, not a replacement.

---

## 1. What already exists (and is good enough to extend, not replace)

The investigation found a far more complete substrate than the original 576 idea-list assumed.
Three load-bearing facts reshaped the design:

**(a) The discipline-gate kernel (530) is already the unification substrate.** Every governance
check in the repo — the ratchet gates (`class-size`, `npm-audit`, `ui-bundle`, `test-efficacy`,
`todo-fixme`, `ts-any`, `dead-code`, …) **and** the entire register family
(`execution-surface`, `surface-altitude`, `consumer-drift`, `operation-surface`,
`interaction-surface`, `contribution-surface`, `observed-happening`, `prose-tier-register`, …)
— is already a *gate-class on 530*, sharing one registry (`governance/registry.v1.json`, keyed
by `kind:`), one changeset/exception protocol, one SARIF emitter, one truth-table model, and
one shared library (`scripts/governance/lib/`: `changeset-loader`, `truth-table-runner`,
`sarif-emitter`, `explain`, `suggest`, `preflight`, **`history`**, **`dashboard`**, `git-utils`,
`frontmatter`). 530 itself frames the end-state as *"one kernel; gates have different kinds"*
(530 §4.3). **My Thread-C instinct to "build a register kernel" was already 80% shipped.**

**(b) 530 has a four-layer model with Layers 3 and 4 explicitly deferred** — and my Thread-A
ideas are *its own roadmap*. Layer 1 (substrate) and Layer 2 (the gate catalog) are shipped.
Layer 3 (UX/coaching: `--explain`, trend dashboards, run-history) and Layer 4 (cross-system
integration: unified registry, tempdoc-wiring, a `/api/governance/state` read surface) are
designed-but-deferred. `lib/history.mjs` and `lib/dashboard.mjs` already exist — Layer 3 is
*half-built*. So "trend store", "loud self-explaining signals", "the agent immune system" are
**completions of 530's Layer 3/4**, not new inventions.

**(c) The repo already has the three rung-mechanisms — each shipped as a single instance.**
- *Rung 1 (unrepresentable):* the register family's **single-authority typed projection** — *"one
  single-authority declaration → a governed (total, lossy-downward) projection → a register-backed
  anti-drift gate"* (553 §1). Plus 567's **(background, foreground) role pairs** with
  `deriveForeground` auto-deriving a WCAG-AA foreground so a *derived* on-color can't be illegible.
- *Rung 2 (guarded & biting):* **555's mutation-strength "test-efficacy" ratchet** — the only
  mechanism in the repo that proves a guard *bites* (kills mutants), not merely *exists*. Today
  it is hardwired to `logic-seams.v1.json`.
- *Rung 4 (bounded exceptions):* the **`covers:` value-bounded persistent exception** — today
  local to the `ui-bundle` gate (`classifications.mjs persistentlyCovers`).

Each of these is the *right mechanism trapped at one address*. The long-term design is to lift
each to a **kernel capability any invariant can opt into.**

**The constraints the existing design imposes (and this design honors):**
- **547 anti-redundancy / AHA (`rule:explore-before-implementing`):** *only unify what shares a
  reason to change.* The register family was *deliberately* kept as separate gate-classes — a
  mutation threshold has nothing to do with UI-altitude rules. So we unify **cross-cutting
  concerns** (exception protocol, guard-integrity, feedback) into kernel capabilities, and leave
  **domain reasoning** per-register. Thin shared substrate, never a monolith.
- **Wall 1 (undecidability) and Wall 2 (not all behavior is law-projectable):** the registers are
  honest that they cannot *discover* an undeclared fork (Rice's theorem; 553 §14.3) and that IO/
  native/controller behavior is not projectable (555 §6). The ladder **raises the floor within
  declared scope to ~100%**; it does **not** solve discovery. New-invariant discovery stays
  rung 5 (authoring oracle + periodic audit, ~70%). This honesty is load-bearing, not a caveat.

---

## 2. The core structure: the Enforcement-Strength Ladder

For any invariant the system wants to hold, its enforcement sits on exactly one rung. Higher is
strictly stronger. The design principle is: **push every invariant as high as its domain allows,
and never let it slide down silently.**

| Rung | Name | The guarantee | Existing repo mechanism | Honest cost / when it's reachable |
|---|---|---|---|---|
| **1** | **Unrepresentable** | The violation cannot be *expressed* | single-authority typed projection (register family); role-typed tokens; discriminated-union schema (no `self` placeholder) | needs a canonical type/projection the domain can be funnelled through |
| **2** | **Guarded & biting** | A check provably *fails* on violation, and we have evidence it bites | `test-efficacy` mutation ratchet (555); a contrast-matrix gate; ArchUnit boundaries | needs a guard *and* an oracle that the guard isn't a no-op |
| **3** | **Guarded, unverified** | A gate/test exists but might be a placeholder / never-failing assertion | the `self` guard token; any `test:` that resolves to a weak assertion | the danger zone — looks like rung 2, enforces like rung 5 |
| **4** | **Ratcheted, bounded-exception** | The metric only improves; over-budget is allowed only value-bounded, accountable, self-revoking | the 530 ratchet gates + `covers:` | for metrics that legitimately can't be zero *today* |
| **5** | **Prose / oracle / audit** | A human or agent will probably notice (~70%) | `seam-hint`, register-as-authoring-oracle, periodic human/LLM audit | the undecidable-discovery + not-projectable residue (Wall 1/2) |

The substrate's job is then exactly three things, and the whole design is their completion:

> **(I) Locate** every invariant on a rung — *the registry already does this via `kind:`.*
> **(II) Prevent silent downgrade** between rungs — *the unifying invariant (§5).*
> **(III) Make the ladder legible** to the agents that maintain the trunk — *530 Layer 3.*

Rung 3 is the crux. It is the rung that *masquerades* as rung 2 — a guard that exists but doesn't
bite (the `self` placeholder #374 fixed; the 94%-line-coverage / 75%-mutation `HybridFusionUtils`
from 555 §9.2). **The single highest-leverage move in this whole design is to make rung 3
detectable and forbid resting there** — i.e. every declared guard must provably resolve *and*
bite, or it is honestly relabelled rung 4/5. That is the meta-invariant (§3).

---

## 3. Maturation gap A — the guard-integrity meta-invariant (rung 2↔3)

**The problem, concretely.** Guard-resolution is re-implemented in ~10+ separate enforcers
(`execution-surface` checks "dangling guard — `gate:`/`test:` must resolve"; `interaction-surface`
checks "unguarded projection"; `observed-happening` checks "kind-mismatch"; etc.). Each register
re-derives "is this guard real?" — so the rung-2-vs-3 check can silently rot in any one of them,
and **none of them checks that a resolved guard actually *bites*.** The `self`→`test:` fix (#374)
was a one-register instance of a missing kernel-wide invariant.

**The correct long-term structure (two layers):**

1. **Guard-resolution as one kernel capability.** Lift "every declared concept names a guard that
   *resolves* to a real artifact" out of the N enforcers into a single kernel meta-pass over *all*
   registers. The guard field becomes a shared **discriminated union** — `{test}|{gate}|{hook}|
   {archunit}` — where `self`/absent is a *schema-level error*, making "declared-but-unguarded"
   unrepresentable (rung 1 applied to the governance layer itself). This generalizes the
   `prose-tier-register` meta-gate (which already cross-validates that every prose rule resolves
   to a tier marker) from prose rules to *all* registers. **[De-risked §9]** this cleanly covers
   the ~3/7 registers that carry an explicit `guard:` string; the rest (contract-surfaces,
   surface-altitude, observed-happening, consumer-drift) enforce via auto-scan / role-derivation /
   floor and are not "unguarded" — the meta-pass scopes to the guard-string family, and the
   resolver must be *extracted* (prose-tier-register's is inlined).

2. **Guard-strength as an opt-in kernel capability (the rung-2 floor).** Generalize 555's
   mutation-efficacy from "the `logic-seams` gate" into a substrate capability **any register can
   opt into**: a guard tagged `verify: mutation` is periodically mutation-tested and must hold a
   strength floor, or the kernel relabels it rung-3 and fails. This is the difference between
   "the test exists" (resolution) and "the test would catch the bug" (bite). It directly answers
   the repo's own `rule:audit-without-test` lesson at the substrate level. **[De-risked §9]** the
   cheapest item of all — the PIT pipeline already reads whichever register it is pointed at, so
   this is config/plugin parameterization (~3-4h), not per-module CI plumbing.

**Why this is the right structure, not a smaller one.** The cheap version — keep per-register
guard checks, just fix each `self` by hand — leaves rung 3 *undetectable in aggregate*: the next
register, or the next agent, reintroduces a placeholder and no single check sees it. Centralizing
the meta-invariant is the only structure where "no invariant may silently sit on rung 3" is a
*property of the system* rather than a convention re-litigated per gate. (Closest external prior
art: Semgrep mandates a test fixture per rule; ArchUnit's `freeze()`. Neither verifies *bite* —
555's mutation ratchet is ahead of the field here; the design just unhardwires it.)

---

## 4. Maturation gap B — the universal bounded-exception protocol (rung 4)

**The problem.** Rung 4 is where metrics live that can't be zero today. The repo's exception
machinery is strong but **unevenly distributed**: the `covers:` value-bound (the bidirectional,
edit-stable, merge-friendly persistence that is genuinely ahead of betterer/PHPStan/ESLint — see
Appendix A) lives only in `ui-bundle`. Accountability (owner/reason) and self-revocation loudness
vary per gate. And the *aggregate* failure mode — "baseline rot", where the exception ledger grows
forever — is unaddressed anywhere.

**The correct long-term structure — make rung 4 a single, uniform contract in the kernel
changeset protocol:**

1. **`covers:` becomes kernel-level**, available to every ratchet gate, not a `ui-bundle` local.
   A bounded exception is *the* shape of a rung-4 exception: value-bounded on **both** sides
   (revoke on improve-past **and** regress-past), so it can never silently absorb a *new* worse
   regression. (This bidirectional bound is the property no mainstream tool has; it is the part of
   the 403 work most worth generalizing.) **[De-risked §9]** the per-gate value-models diverge
   (bytes / LOC / strength+coverage / counts), so this lands as a *protocol with a gate-specific
   value interpreter*, not a uniform `metric=value` free for every gate.
2. **Accountability is mandatory frontmatter** (`owner`, `reason`, the existing `tempdoc`/`adr`).
   A value-bounded exception is still only *auditable* if it says who and why.
3. **Self-revocation is loud and self-explaining.** When a bound revokes, the kernel states the
   direction and the numbers ("improved 14→9, ceiling 12 — delete this changeset"). On an
   agent-maintained trunk, a signal an agent can act on in one iteration is worth more than a
   correct-but-opaque failure. (This is 530 Layer 3.)
4. **The exception ledger is itself a ratcheted metric (the meta-ratchet).** Total count of live
   bounded exceptions may only decrease (temporary rises via the same protocol). This is the
   *structural* cure for baseline rot: SonarQube avoids rot by having no baseline; we avoid it by
   making the baseline's **size** a gated, shrinking number. Per-entry tools (PHPStan, ESLint)
   fight rot one entry at a time and lose; fighting it in aggregate is the move none of them make.
5. **Auto-relaxation stays impossible.** `--rebalance` may only *tighten*, and only as an explicit
   reviewable commit. On a fleet trunk, an automatic floor-relax means whichever agent runs last
   silently loosens it. This is already the posture; the design *fixes it as an invariant* (no
   silent downgrade, §5) rather than a convention.

**Why not weaker.** The tempting smaller design is "leave `covers:` in ui-bundle; other gates copy
it when needed." That recreates the per-register duplication §3 just diagnosed, and leaves the
meta-ratchet (the only thing that actually stops rot) unbuilt. Rung 4 is a *protocol*; protocols
belong in the kernel.

---

## 5. The unifying invariant — *no silent downgrade*

The kernel already forbids specific silent downgrades: silent baseline-shift, silent pin-bump,
silent tier-change, silent floor-drop (consumer-drift). **These are all the same invariant at
rung 4.** The long-term design generalizes it to the whole ladder:

> **No invariant's enforcement strength may decrease without a classified, accountable changeset
> — at any rung.**

- Rung 1→lower: removing a single-authority projection, or widening a discriminated-union schema
  back to allow `self`, is a downgrade → requires a changeset.
- Rung 2→3: a guard's mutation-strength dropping below floor, or a `verify: mutation` tag being
  removed, is a downgrade → fail/changeset.
- Rung 3→5: deleting a guard, or letting a register's scan-population collapse to zero (the
  vacuous-pass failure mode — a renamed dir makes a closure check silently match nothing), is a
  downgrade → the kernel asserts `expectedMinPopulation ≥ 1` per register.
- Rung 4: the existing baseline-shift family, plus the meta-ratchet (§4.4).

This single invariant is what turns "a pile of gates" into "a substrate." It is also the precise
defense against the agent-fleet failure mode the 2024-26 literature names: *agents optimize to
whatever the gate measures, and will widen any one-sided hole to make a gate green.* A ladder with
no silent downgrade has no one-sided holes.

**[De-risked §9]** the investigation *validated* this section the hard way: there is **no shared
downgrade detector today** — every gate re-implements baseline-tampering inline. That absence is
exactly the gap the ladder predicts; realizing the invariant is a lambda-based
`lib/baseline-tamper-detector.mjs` refactor (each gate supplies its comparison), plus a new
`enumerateAllGateChangesets()` for the §4.4 meta-ratchet.

---

## 6. Thread B as the worked example — tokens climbing the ladder

The accessible-token work is not a separate design; it is **one domain walked up the ladder**, and
it shows the general structure is correct because it applies cleanly to a UI concern.

- **Today:** 567's role system puts *derived* `accent-on-*` foregrounds at rung 1 (unrepresentable
  illegibility, via `deriveForeground`). But a *fill* token used as *text on a surface* (the #3
  amber bug) is **rung 5** — only a browser spot-check or a human catches it. The `--text-warning`
  fix added one rung-1 token by hand.
- **The climb (general form):**
  1. **Rung 1 — taxonomy.** Split every accent into three *graded roles* — `fill-X` (no text-
     legibility claim), `text-X` (legible on surface), `on-X` (legible on `fill-X`) — and make a
     text slot only *accept* a text/on grade (role-typed slots + a lint). The amber bug becomes
     **unrepresentable**, the same way single-authority projection makes a fork unrepresentable.
  2. **Rung 2 — the contrast-matrix guard.** Enumerate `every text-graded token × every surface`
     *per theme* and assert each *intended* pairing clears threshold; fail on regression. This is
     literally another gate-class on 530 (a fitness function), and its guard *bites* by
     construction (it computes the ratio). Make it dual-signal: WCAG-2 AA as the hard floor +
     **APCA Lc** as the sharper perceptual signal — because WCAG-2's math is *why* the mid-tone
     amber slipped through, and APCA pairs naturally with the oklch space already in use. (APCA is
     not yet a standard as of April 2026 — so it is a *signal*, never the sole floor.) **[De-risked
     §9]** the matrix can run **headless** (an `oklchToRgb` shim already exists in a test) but is
     *medium* effort: it must implement `color-mix(in oklch)` + flatten `var()` chains. And the
     rung-1 migration is **~173 `color: var(--accent*)` sites** — so build this gate *first* to
     find which actually fail, then migrate only that subset; don't migrate 173 sites blind.
  3. **Rung 3 legibility (Layer 3).** The Token Editor already live-previews edits and *already
     shows WCAG ratios* (567 A2) — completing it with an inline pass/fail readout per role, per
     theme, is the **feedback rung made user-facing.** This is the one genuinely new *user* feature
     the whole design implies. **[De-risked §9]** verified ~90% built — `renderRoles` already
     renders a live `ratio:1 AA/AAA/fail` badge per role; the addition is small.
  4. **Derivation, not magic values.** `--text-warning`'s 6.3:1 is a hand-tuned constant. The
     long-term form derives every text-grade token from `{hue, chroma, targetRatio}` per theme, so
     the guarantee is *recomputed*, not *remembered*. (Rung 1 made durable.)

The lesson generalizes: **prefer climbing to rung 1 (make it unrepresentable) over building a
better rung-2 detector.** The matrix gate is the *backstop*; the role taxonomy is the *fix*.

---

## 7. What this design is NOT

- **Not a rewrite of 530.** It is the completion of 530's own deferred Layers 3-4 plus one new
  cross-cutting capability (guard-integrity) and the generalization of three trapped mechanisms
  (`covers:`, mutation-efficacy, role-derivation). The kernel, registry, changeset protocol, SARIF,
  and `lib/` substrate all stand.
- **Not a monolithic "register kernel".** Per 547/AHA, domain reasoning stays per-register; only
  the cross-cutting concerns (guard-integrity, exception protocol, feedback, no-silent-downgrade)
  become kernel capabilities. The register family stays a family.
- **Not a claim to solve discovery.** Wall 1/2 stand. The ladder raises the floor *within declared
  scope* and forbids silent downgrade; finding *new* invariants stays the rung-5 oracle + audit.
- **Not dropping WCAG-2, and not auto-relaxing baselines.** Dual-threshold contrast; tighten-only
  rebalance.

---

## 8. Priority (each its own normally-scoped tempdoc — order revised by the §9 de-risking)

The original ordering was by *leverage* alone; the §9 feasibility pass re-sequences by
leverage **and** cost-to-build, and by which gaps unlock others:

1. **Guard-strength generalization (§3.2).** Cheapest (~3-4h, the pipeline is already
   register-agnostic) and the highest-leverage rung-2 floor. Start here.
2. **Shared baseline-tamper-detector + the §4.4 meta-ratchet (§5).** Factor the per-gate
   copy-paste into one `lib/` detector — this *builds the home* the no-silent-downgrade invariant
   needs, and §4 builds on it. Precedes §4 for that reason.
3. **Guard-resolver extraction + the meta-pass (§3.1).** Scoped to the guard-string registers; the
   structural keystone for "no invariant silently on rung 3." Generalizes #374.
4. **`covers:` → kernel protocol (§4).** A gate-specific value interpreter on the §5 lib; lifts the
   strongest 403 mechanism off `ui-bundle`.
5. **Tokens (§6): the contrast-matrix gate FIRST, then the rung-1 taxonomy migration of the
   failing subset.** The editor live-readout (§6 step 3) is nearly free; APCA is an additive
   dual-signal that needs dependency-vetting.

---

## 9. De-risking findings (2026-06-11)

A read-only feasibility pass — 3 Explore agents on the highest-risk assumptions + direct probes —
tested §3-§6 against the code *before* any implementation. It did not change the **conceptual**
structure (the ladder held, and §5 was *validated*), but it recalibrated several feasibility
claims. **Net confidence in the remaining work: 7/10** — feasible, no blockers, my two scariest
unknowns resolved favorably, but honest moderate complexity plus a couple of leaky generalizations
and a sizable (gate-findable) migration. *Process note:* one subagent falsely reported
`persistentlyCovers` "not found"; a direct grep confirmed it exists (`classifications.mjs:30-62`,
commit `54805fa02`) — subagent audits are hypotheses, verify them.

**De-risked UP (cheaper than the prose implied):**
- **§3.2 mutation-as-a-capability — EASY (~3-4h).** The PIT pipeline is already register-agnostic
  (`MutationConventionsPlugin` + `report-pit-strength.mjs` read whichever register they're pointed
  at). Opting another register's guard into mutation verification is parameterization, not CI
  plumbing. This was the scariest item; it is the cheapest.
- **§6 step 3 editor readout — ALREADY ~90% BUILT.** `TokenEditorPlugin.renderRoles` already
  computes `deriveForeground` and renders a live `ratio:1 AA/AAA/fail` badge per role on edit. The
  "one new user feature" is nearly free.

**Recalibrated DOWN (leakier / harder than the prose implied):**
- **§3.1 guard meta-pass — NARROWER.** Only ~3/7 registers carry a `guard:` string; the rest
  (contract-surfaces, surface-altitude, observed-happening, consumer-drift) enforce via
  auto-scan / role-derivation / floor. One discriminated-union schema covers the guard-string
  family, not "all registers." The reusable resolver doesn't exist yet — prose-tier-register's is
  inlined, needs extraction to `lib/guard-resolver.mjs` (~6-8h).
- **§4 `covers:` lift — LEAKY.** Per-gate value-models diverge (bytes / LOC / strength+coverage /
  counts), so `covers:` generalizes only as a *protocol with a gate-specific value interpreter*,
  not a uniform `metric=value`. The bidirectional-bound idea is sound; "free for every gate" was
  oversold.
- **§5 no-silent-downgrade — VALIDATED, but a real refactor.** There is *no* shared detector
  today; every gate re-implements baseline-tampering inline (ui-bundle enforcer ~219-268,
  class-size ~217-257, test-efficacy ~196-215, npm-audit ~192-240, consumer-drift truth-table
  ~96-123). That absence *is* the gap the ladder predicts — realizing it is a lambda-based
  `lib/baseline-tamper-detector.mjs` refactor + a new `enumerateAllGateChangesets()`.
- **§6 matrix gate — feasible headless but MEDIUM.** No browser needed (an `oklchToRgb` shim
  exists in `builtinPaletteContrast.test.ts`), but it must implement `color-mix(in oklch)` +
  flatten `var()` chains (tokens.css: ~210 var refs, 97 oklch, 31 color-mix, 19 light overrides);
  no Node color lib in deps. The rung-1 migration is **~173 `color: var(--accent*)` sites / 54
  files** — build the gate first to find the failing subset (mostly light-theme saturated-accent-
  as-text), then migrate only those.

The above re-sequences §8; it does not alter §0-§7's thesis. When a gap is picked up, this section
is the calibrated starting point.

## 10. Implementation + review-fix outcomes (2026-06-11)

The five gaps (§3.2, §5, §3.1, §4, §6) were implemented on `worktree-576-ladder`, then a critical
review surfaced five follow-up fixes — all landed on the same branch:
1. **exception-count protects its own ceiling** — a silent `maxExceptions` raise now fails via the
   shared `detectBaselineTamper` (the meta-ratchet was vulnerable to the rot it polices — §5 applied
   to itself).
2. **CI wiring** — register-guard-resolution / exception-count / check-contrast-matrix /
   check-accent-as-text + the new node tests are now in `ci.yml` (a `governance` change-scope), and
   the CLAUDE.md pre-merge list.
3. **§6 rung-1 completed** — `--text-<role>` for every accent (both themes, gate-verified AA),
   `ROLE_CATALOG.textToken`, the matrix gate broadened to 24 pairings, and `check-accent-as-text` (a
   per-file ratchet that fails NEW accent-FILL-as-text — proportionate, not a blind ~94-site migration).
4. **colorMath removed** — it was dead code / a false "shared surface" (the FE resolves oklch via the
   browser; the `.mjs` gate keeps its own copy).
5. **Accountability scope** — owner+reason on growth changesets is wired into the **ui-bundle gate
   first** (it carries the `covers:` ledger); the `requireAccountabilityFor` mechanism generalizes to
   the other ratchet gates opt-in when their changesets are next touched, rather than a disproportionate
   retro-backfill of all (e.g. 37 class-size changesets). Documented in `gates/ui-bundle/.changesets/README.md`.

## 11. Post-merge state + the honest design-vs-shipped gap (2026-06-11)

§10's five mechanisms were merged into `main` (merge commit `e58caf999`; a 560 merge later landed
on top). Two ratchets correctly caught `main`'s *own* growth during the merge and were reconciled
**explicitly, not silently** — which is §5 working as designed: the exception-count ledger rose
44 → 48 (recorded by an `exception-budget-raise` changeset — the meta-ratchet catching its own
merge), and `check-accent-as-text` grandfathered 94 → 99 legacy sites via a tighten-only
`--rebalance`. Two red signals were confirmed **pre-existing on plain `main`, not 576-caused**, and
logged to `docs/observations.md`: the `--surface-border` ghost token (575 §17) and the LambdaMart
p99 perf flake (environmental).

**The honest gap — what shipped is the scaffolding, not the conceptual peak.** Two post-
implementation critical-analysis passes (conceptual) found §10 reads more "complete" than the
design intends. Recorded here so a future reader does not mistake the *mechanisms* for the
*ambition*:

- **§3.2 guard-*bite* is latent, not biting.** The mutation capability is wired so a register *can*
  opt a guard into strength-verification — but no register's guard is actually held to a mutation
  floor yet. "Every guard provably bites" exists as a *capability*, not *active enforcement*. Rung 2
  is reachable, not yet occupied.
- **§3.1 shipped at rung 2, not rung 1.** Guard-integrity landed as a gate that *detects* an
  unresolved guard — not the designed discriminated-union *schema* that makes an unguarded rule
  *unrepresentable*. The danger-zone (rung 3) is now detectable, not yet impossible.
- **§6 built the ratchet, not the cleanup.** `check-accent-as-text` blocks *new* accent-fill-as-text
  and the `text-*` taxonomy exists — but the ~173 legacy sites are not migrated. The gate is the
  backstop; the rung-1 *fix* (every text slot accepts only a text grade) is the unfinished half.
- **§5 realized at rung 4.** The shared `detectBaselineTamper` + the meta-ratchet generalize
  no-silent-downgrade across the *ratchet* gates — the full-ladder form (rung 1↔2↔3 downgrades as
  classified changesets) is structure, not yet enforcement.

So §10's "implemented" = the **mechanisms and CI wiring** are real, live, and merged; the **deeper
ambition** (unrepresentable rules, guards proven to bite, the 173-site migration, the Layer-3
legibility surface) remains future work. Each §3-§6 stays its own normally-scoped tempdoc *for that
deeper form* — the scaffolding it builds on now exists in `main`. No obligation is created here.

## §12 — Calibrated effort estimate for the remaining (deeper) work (2026-06-15)

A calibration pass measured this repo's *actual* implementation velocity — land-merges measured by
their two parents (`M^1..M^2` for branch-only commits, `M^1...M^2` for net diff) — to ground §8's
per-item effort numbers in observed reality rather than intuition. Reference cycles on `main`:

| Cycle (land-merge) | branch commits | net diff | continuous session-time |
|---|---|---|---|
| 576 scaffolding (`e58caf999`) | 13 | 55 files / 1462 ins / 239 del | ~6h (15:08→21:12, one sitting) |
| 575 register + 571 reframe (`0e034a9`) | 10 | 24 files / 1682 ins | ~4.7h |
| 575 §13–15 impl (`351c1b3`) | 5 | 20 files / 751 ins | ~1.6h |
| 569 §14–19 rollout (`4353dec5`) | 18 | 85 files / 5666 ins | one cycle |
| 574 kernel land (`ff675e59`) | 50 | 151 files / 4766 ins / 1795 del | ~6.7h |
| 571 altitude axis (`dfcb4286`) | 17 | 65 files / 2250 ins | ~5h |

**Three findings that recalibrate §8:**

1. **A branch-cycle is ~5–7h of continuous session-time** even at 50–150 files / 1.5–4.8k
   insertions — that is *agent-coding* velocity, not human-effort. §8's per-item hour figures
   (~3–4h, ~6–8h) are sound *as agent session-hours*; do not inflate them to human-days.
2. **A design-theory tempdoc is multi-cycle, not one merge.** 574 ≈ 5 merges, 575 ≈ 4, 569 ≈ 2.
   The deeper form of §3–§6 will be **3–4 cycles, one per gap**, not a single branch.
3. **Calendar-span overstates work** (it includes repeated `main`-sync merges — 569's ~20h span is
   mostly sync/idle); session-time between a cycle's first and last *authored* commit is the honest
   figure.

**Per-gap estimate for the deeper form (agent session-time):**

| Gap | Estimate | Note |
|---|---|---|
| §3.2 — occupy rung 2 (a guard held to a *biting* mutation floor, not just opt-in capability) | ~3–6h · ~5–10 files | PIT pipeline already register-agnostic (§9) |
| §3.1 — rung-1 discriminated-union guard schema (unguarded rule *unrepresentable*, not just detected) | ~6–9h · ~15–25 files | extract `lib/guard-resolver.mjs`; leakiest item |
| §6 — migrate the ~173 `accent-as-text` sites / 54 files to rung-1 role-typed slots | ~6–8h · 54+ files · ~1.5–3k ins/del | mechanically large, low-risk; the gate already names the failing subset |
| §5 — full-ladder no-silent-downgrade (rung 1↔2↔3 as classified changesets) | ~6–10h · ~20–30 files | conceptual + cross-gate refactor |

**Whole deeper ambition:** mirrors a 575/571-scale effort — **3–4 branch-cycles, ~18–30 agent
session-hours, ~1.5–2 calendar days, ~120–200 files cumulative, ~4–6.5k insertions.** Confidence
**~6/10** (§9 self-rated the remaining work 7/10; discounted slightly for §3.1's rung-1 schema being
the leakiest generalization). Each gap stays independently shippable; none blocks the others.

## §13 As-built — §6 rung-1 token migration (2026-06-15)

Closes the §11 "§6 built the ratchet, not the cleanup" gap: the legacy accent-fill-as-text sites are
migrated to the `--text-*` taxonomy, and the ratchet baseline is driven to **empty (`{}`)** — so any
future `color: var(--accent-<role>)` now fails `check-accent-as-text`. The illegible default is no
longer merely backstopped; it is **unrepresentable** (rung 1 occupied for this domain). Done on
`worktree-576-token-migration`; documented here per the no-new-tempdoc instruction.

**What was done**
- Added the one missing text grade `--text-info` to `modules/ui-web/src/styles/tokens.css` (dark +
  light), deriving from `--text-chat` (since `--accent-info` aliases `--accent-chat`); AA by
  inheritance, confirmed by the contrast-matrix gate.
- Migrated **104 sites across 47 files** (`color: var(--accent-<role>)` → `color: var(--text-<role>)`)
  using the gate's exact regex, touching only standalone `color:` — never `border-color` /
  `background` / `--accent-on-*` / suffixed `--accent-x-NN`. Roles migrated: tint, danger, warning,
  success, command, chat, info.
- `--rebalance`d the baseline to `{}` (was 99 sites / 45 files; had grown to 104/47 with new drift).

**Pre-flight that de-risked it (vs. the §12 "per-site judgment" worry):** a block-scan proved
**0 of 104 sites are text-on-full-fill** (no enclosing rule sets `background: var(--accent-<sameRole>)`
at full strength). The faint-tint chip cases (`background: var(--accent-tint-16)`) are text-on-≈surface,
so `--text-<role>` is correct there too. The migration was therefore a uniform mechanical swap, not the
case-by-case `--text` vs `--accent-on` decision §12 budgeted for. (Calibration note: §6 came in *below*
the §12 estimate — the judgment collapsed to a single proven invariant.)

**Verification** (all green): `check-accent-as-text` (0, baseline `{}`); `check-contrast-matrix`
(32 role pairings AA in both themes — the measured legibility oracle); `npm run typecheck`;
`npm run test:unit:run` (314 files / 2972 tests); live shell render in the worktree (Vite :5174) —
chrome, toast, status bar render with migrated tokens, no console errors. Content surfaces could not be
screenshotted (worktree serve was offline / no backend → "Loading…"; this also tripped `ui-shot`'s
`search-input` wait), but the contrast oracle covers their legibility authoritatively.

**Still future work (unchanged):** §3.2 occupy rung 2 (a guard held to a biting mutation floor — the
capability is wired, no register opts in yet); §3.1 rung-1 (`guard:"self"` is still representable —
forbidding it needs an "exempt" category for genuinely-unguardable surfaces); §5 full-ladder
(rung 1↔2↔3 downgrade detection). `exception-count` was red on `main` (live 55 > ceiling 48) from
unrelated drift — out of this slice's scope, logged to observations.

## §14 As-built — §5 + §3.1 + §3.2 deeper-form completion (2026-06-15)

The three remaining deeper gaps, implemented on `worktree-576-token-migration` (same branch as §13),
reusing the existing governance substrate — no new kernel infra. Investigation (own probes + 3 Explore
agents) found the substrate more complete than §11 framed (`guard-resolver.mjs`, `baseline-tamper-
detector.mjs`, `covers.mjs`, the `report-pit-strength`/`test-efficacy` pipeline all already exist).

### §5 — the vacuous-pass guard (`expectedMinPopulation`)
The concrete §5 deliverable ("the kernel asserts expectedMinPopulation >= 1 per register" — a renamed
scan root must not let a positive-coverage gate pass vacuously). Shipped as ONE shared helper
`scripts/governance/lib/population-floor.mjs` (`verdictForVacuousScan`), wired into the two
filesystem-path-walk coverage gates whose detection can silently collapse: **execution-surface**
(floor 12; live 26) and **operation-surface** (floor 10; live 23). Each register declares
`scan.expectedMinPopulation`; the gate fails with `…/vacuous-scan` if the scan detects fewer. Unit test
`population-floor.test.mjs` (11 checks) + both gate self-tests green; wired into `ci.yml`.
**Deferred (honest, Wall-1):** the fully-general rung 1↔2↔3 downgrade detector (e.g. "a single-authority
projection was removed") is undecidable in the general case; the realizable slices are this vacuous-pass
guard + §3.1's make-`self`-unrepresentable (which prevents the rung 2→3 slide). The broader
`detectBaselineTamper` + `exception-count` meta-ratchet already cover the rung-4 baseline-relaxation case.

### §3.1 — bare `self` made unrepresentable + the `exempt:<reason>` kind
Rung-1 for the guard layer itself: a bare `self`/`none-yet`/absent guard is now a build failure
(`register-guard-resolution/invalid-guard-form`, universal across all kinds), so a NEW register entry
cannot be left silently unguarded. The only ways to be unguarded are a real guard (`gate:`/`test:`) or
an accountable `exempt:<reason>` (mandatory non-empty reason; resolved in `guard-resolver.mjs`). A
required-guarded kind (execution-surface `projection`/`producer`) now needs a REAL guard — an exemption
does not satisfy it. Migrated all **31** legacy `self`/`none-yet` entries (19 execution + 12 operation)
**honestly, without manufacturing rung-3 masquerades**: source/carrier/consumer → `exempt:<reason>`
(canonical type / re-export barrel / opaque carrier / consumer-with-no-projection-law);
operation-surface `projection`/`producer` → `gate:operation-surface` (genuinely structurally guarded by
that gate's projection-lineage check); the deferred `run-event-store-base` → `exempt:deferred (565
§15.C)`. Unit test `guard-resolver.test.mjs` (14 checks) + the gate's positive/negative self-test
(positive now uses `exempt:`, negative adds a bare-`self` case) green; wired into `ci.yml`. No
regression to the execution-surface / operation-surface guard checks (the new `exempt`/`gate:` tokens
resolve cleanly).

### §3.2 — occupy rung 2 (a seam floor proven to BITE)
The mutation-floor infra was already complete (`logic-seams.v1.json`, `report-pit-strength.mjs`, the
`test-efficacy` gate + per-seam `strength-baseline.v1.json`); §11's "latent, not biting" meant the
floors (grandfathered-from-measured 2026-06-03) had never been **re-validated against a live PIT run**.
NOT adding a `verify:mutation` tag — logic-seam membership already IS the opt-in (no `verify` field
exists; adding one is redundant per AHA). Ran a real PIT pass (`report-pit-strength.mjs --run`, ~one
build) on both seam modules; measured strengths (2026-06-15):

| seam | measured | floor | result |
|---|---|---|---|
| hybrid-fusion | 76% (165/215, 9 no-cov) | 76 | at floor |
| chunk-offset-math | 88% (16/18) | 88 | at floor |
| file-freshness | 100% (13/13) | 100 | at floor |
| admission-policy | 100% (1/1) | 100 | at floor |
| splade-backoff | 100% (4/4) | 100 | at floor |

`test-efficacy --mode gate` runs **green against the live numbers** (`within-baseline`). Every seam
meets its floor on current code, so the floor demonstrably **bites** — a regression below it now fails
the gate (the rung-2 guarantee, occupied). No drift from the grandfathered floors → no `--rebalance`,
no changeset needed. The PIT report (`tmp/pit-strength-report.v1.json`) is reproducible via the
`runMutation` CI lane; not committed (tmp is gitignored). Honest scope: PIT stays opt-in CI (ADR-0026
manual-CI; the run is slow), so the discipline is "re-run on a seam touch" — the gate is the teeth, the
run is the proof.

## §15 As-built — the legibility/cross-system layer + token completions (2026-06-15)

The remaining 576 line — §5 family-wide, 530 Layer 3 (legibility) + Layer 4 (cross-system), and the
Appendix-B token completions — implemented on `worktree-576-token-migration`, reusing the existing
substrate. After this, every enforcement rung is occupied AND the ladder is legible.

**§5 family-wide (vacuous-pass + the shared downgrade detector).** The §14 vacuous-pass guard
(`lib/population-floor.mjs`) is now wired into EVERY scan-coverage gate that can silently collapse —
execution-surface (12), operation-surface (10), observed-happening (8), surface-altitude (8),
contribution-surface (1); interaction-surface already guarded it via `verdictForEmptyCoreSet`. And the
mechanically-detectable rung transitions now each fail via ONE shared detector: `register-guard-resolution`
compares each entry's guard STRENGTH (real-guard 2 > exempt 1 > bare 0) vs baseline and routes any
DECREASE through `detectBaselineTamper` — a silently weakened guard fails unless a
`gates/register-guard-resolution/.changesets` `guard-downgrade` changeset covers it (bare `self` is
already forbidden by §3.1; dropped mutation-floor by `test-efficacy`; population collapse by the
vacuous-pass helper). `guard-resolver.test.mjs` (24 checks) covers `guardStrength` +
`detectGuardDowngradeEvents`. The fully-general "a single-authority projection was removed" stays the
undecidable residue (Wall-1), out of scope by design.

**530 Layer 3 — legibility.** `--explain <ruleId>`, the run-history store (`lib/history.mjs`, wired into
`run.mjs`) and the dashboard generator (`lib/dashboard.mjs`) already existed. New: `dashboard.mjs` gains
a stable JSON projection (`buildState`, committed-authority-only — gate roster, exception ceiling,
strength floors, class-size debt — no machine-local churn) written to the Head classpath resource, and a
**user-facing governance dashboard surface** `GovernanceView` (`jf-governance-view`,
`core.governance-surface`, DEVELOPER/DEEPLINK) that projects it — read-only, tokens-only, no own
`h1`/`main`. Makes the ladder legible.

**530 Layer 4 — cross-system.** `GET /api/governance/state` (`GovernanceRoutes`, loopback-only,
read-only) serves the committed projection from the classpath resource; the dashboard surface composes
it. A projection of the registry, not a fork. `LegacyEndpointGuardTest` pins the route;
`api-contract-map` documents it; `CoreSurfaceCatalogTest` count 16 → 17.

**Appendix B — token completions.** (B4) `check-contrast-matrix` adds APCA Lc as an additive signal
(faithful inline APCA-W3, no new dep) while WCAG-2 AA stays the hard floor — 9 saturated-fill pairings
surface as APCA-soft. (B2) `gen-text-tokens.mjs` derives a text grade from `{hue, chroma, targetRatio}`
(`--emit`, for new tokens) and `--check` enforces a per-theme target FLOOR on every committed
`--text-*` (recomputed, not remembered), without flattening the superior tuned values; wired into CI.
(B6) the Token Editor shows a per-role nudge-to-compliant (gap + push direction) when a role can't clear
its floor.

**Verification.** All touched governance gates + node unit tests green (operation-surface's
`undeclared-surface` red is pre-existing unrelated drift, logged); FE typecheck + 2972 unit tests;
app-observability + ui Java unit + integration tests (incl. `GovernanceStateControllerTest` proving the
`registry` projection is served, and `LegacyEndpointGuardTest`). **Live-stack browser pass (2026-06-15,
post-merge):** with the dev stack on `main`, `GET /api/governance/state` returns the `registry`
projection (35 gates · exception ceiling 48 · 5 strength floors · 7533 LOC debt) and the **governance
dashboard surface renders it populated in BOTH themes** (dark + light, screenshots clean, no console
errors). The Token Editor nudge is verified by `TokenEditorPlugin` unit tests + typecheck (it renders
only when a role fails its floor — the unit test exercises that path). Note: a fresh
`:modules:ui:installDist` was required before the stack picked up the new controller (the documented
stale-jar pitfall). Pre-existing unrelated reds (`operation-surface/undeclared-surface`; a `569`
ui-bundle changeset missing `owner:`/`reason:`) fail the *whole-repo* `verifyGovernanceGates`,
independent of this work.

## §16 Closure — spec-gap follow-up (2026-06-15, `worktree-576-gaps`)

A self-review found seven places the §13–§15 delivery deviated from or under-verified the literal spec.
Closures (G1/G4/G5/G6 = new code+tests; G2/G3 = honest reconciliation with the data/correctness reason;
G7 = browser verification):

- **G1 — interaction-surface declared floor.** Added `scan.expectedMinPopulation` (3; live 5) +
  `verdictForVacuousScan` on the parsed core-shape set, so EVERY scan-coverage gate now declares a
  population floor (the size-0 `verdictForEmptyCoreSet` stays the catastrophic hard-stop). Self-test green.
- **G4 — the downgrade path now BITES in a fixture.** The register-guard-resolution §5 detector was a
  no-op in fixtureMode; it now reads the prior register from a fixture `_baseline/` (mirroring class-size).
  A negative self-test fixture downgrades a `test:` guard to `exempt:` → the gate emits
  `silent-guard-downgrade` and fails. `enforcer.test.mjs` asserts this (closes audit-without-test).
- **G5 — `--explain` completeness.** `explain-coverage.test.mjs` proves every declared ruleId across all
  gates resolves via `--explain` (343 rules / 35 gates). Wired into CI.
- **G6 — register-agnostic PIT, demonstrated.** `report-pit-strength.test.mjs` runs the report against a
  SECOND fixture register and asserts it iterates THAT register's seams (not the hardwired default).
  Wired into CI.
- **G2 — "one shared detector", stated honestly.** `detectBaselineTamper` IS the one shared
  no-silent-downgrade substrate for the baseline-relative *strength* downgrades (the ratchet gates +
  guard-downgrade). Bare-`self` (a never-allowed schema state) and population-collapse (a
  no-baseline catastrophic floor) are **absolute-rung** checks, not deltas — routing them through a
  delta-detector would, for population, force a changeset on every legitimate surface removal (churn).
  So the correct structure is: one shared detector for deltas + per-rung absolute floors. The earlier
  "one shared detector for all four" was an over-claim; this is the accurate architecture.
- **G3 — "derived" realized as floor-verified + derivation-capable (NOT wholesale-replaced).** Measured:
  re-deriving every `--text-*` at a single per-theme target FLATTENS the deliberately-varied tuned
  contrast (dark warning 10.7→8:1, light command 10.5→6:1) and collapses same-hue roles (tint=chat →
  identical color) — a quality regression. B2's intent ("recomputed, not remembered") is met by the
  `gen-text-tokens --check` FLOOR (recomputes each token's contrast every run) + the `--emit` derivation
  capability for NEW tokens; the hand-tuned values are kept by correctness, not replaced.
- **G7 — Token Editor nudge: unit-verified + a reachability finding.** The nudge is unit-tested
  (TokenEditorPlugin.test). Browser investigation (Theme Editor surface, worktree Vite :5174) surfaced a
  genuine finding instead of a screenshot: at the production floor (every role is `WCAG_AA` = 4.5),
  `deriveForeground` picks the optimal black/white foreground whose MINIMUM achievable contrast is
  ~4.58:1 (the black/white crossover) — so a role essentially never reports `!meets`, making the nudge
  AND the pre-existing `fail` badge near-unreachable. They fire only if a role's floor is raised above
  ~4.58 (e.g. to AAA 7). The nudge renders correctly when that condition holds (unit test); forcing it
  in the browser would require an artificial floor raise (dishonest), so it is recorded as a finding +
  logged to observations (re-target the nudge to AAA / the APCA signal to make it actually useful).

  **§16.1 — G7 RESOLVED (2026-06-15, `worktree-576-nudge`).** Acted on the finding: ported the faithful
  APCA-W3 `apcaLc` into `themes/contrast.ts` (the same maths as the contrast-matrix gate, no new dep) and
  re-targeted the nudge — it now fires when a role clears WCAG-AA but its APCA `|Lc| < 60` (perceptually
  weak — the saturated mid-tone hues WCAG-2 under-penalises, i.e. the #3 bug class this work exists to
  surface; the rare genuine AA failure still nudges too). This is **reachable**: browser-verified on the
  live Theme Editor surface — the nudge fires on the 6 perceptually-weak default roles (Command Lc 50,
  Success 55, Warning 59, Danger 42, Tint 48, Link 56) and correctly NOT on the strong ones (Chat 67,
  Highlight 86), recomputing per-theme (dark 6 nudges, light 1). `contrast.test` gains `apcaLc` cases
  incl. the "clears AA but APCA-weak" trigger. The nudge is now a useful authoring signal, not dead code.

**Verification.** All touched gates + node tests green (incl. the new G4/G5/G6 tests); the negative
downgrade fixture goes red on the silent-guard-downgrade rule; FE typecheck. Browser (worktree Vite
:5174): the governance dashboard + Theme Editor surfaces render cleanly; the nudge-reachability finding
above. Done on `worktree-576-gaps`; not merged (held for the user).

## §17 — Theoretical extensions & second-round research (2026-06-15)

The §3–§6 mechanisms are shipped. This section is a **research-only** forward look: what the ladder
*could* become, grounded in a fresh external survey (4 parallel research passes on frontiers Appendix A
did **not** cover) plus an internal probe of what is already built. No obligation is created; every item
below is "viable, not scheduled." Build-state is marked **[built]** / **[cheap]** / **[real-work]**
against the live code.

### 17.0 The deepest finding — reframe the ladder as a *cost-to-game* curve

Four independent literatures (architectural fitness functions, policy-as-code testing, agent-governance,
posture scoring) converge on **one lens that the ladder implicitly encodes but never names**:

> **A rung is only as strong as the gap between the cost of *gaming* it and the cost of *complying* with it.**

This is Koch's "enforceability rubric" (2026, *From Governance Norms to Enforceable Controls*) stated as
a single inequality, and it re-derives every design choice in §2 from first principles:
- **Rung 1 (unrepresentable)** is strongest because cost-to-game = ∞ — you cannot even *express* the
  violation, so there is no cheap green path.
- **Rung 3 is the danger zone** precisely because it has *low* cost-to-game (just don't trigger the
  no-op guard) while *looking* like rung 2 — a high apparent strength masking a cheap bypass.
- **Rung 5 (prose)** fails not because agents lack access to the doc but because the proxy ("the doc
  exists") is satisfiable without the intent ("the rule is followed") at ≈ zero cost.

The design rule this yields (Koch): **place each invariant at the lowest rung where it becomes
*observable and determinate*, because at that rung gaming it costs as much as complying** — so an agent
optimizing for the cheapest green path is forced through the front door. This is the precise theoretical
backing for the repo's own observation that *"agents optimize to whatever the gate measures."* The
empirical confirmation is now documented in the wild: **test-suite elision** ("AI deleted my tests and
said 'all tests pass'"), **harness manipulation** (agent edits the CI workflow to drop failing
categories), and **spec-test co-contamination** (agent co-authors impl + test with the same wrong
assumption). The ladder *is* an immune system against exactly these moves; §5's no-silent-downgrade
invariant is the ratchet that stops backsliding down the cost curve. **[cheap]** — fold this lens into
§2 and annotate each rung with the gaming move it defeats *and* the one it cannot (which the next rung
must catch). Pure documentation; turns the ladder table into an explicit immune-system contract.

### 17.1 Polish

- **P1 — Per-rung "defeats / cannot-defeat" annotation.** As above: each rung (ideally each gate) names
  the gaming class it closes and the residue the next rung owns. Makes the Swiss-cheese layering explicit
  for the agents that maintain the trunk. **[cheap]**, doc-only.

### 17.2 Simplify

- **S1 — Unify the three downgrade detectors under one `StrengthDelta` model.** "No silent downgrade"
  is currently *three* code paths: `baseline-tamper-detector.mjs` (rung-4 ceilings/baselines),
  `guard-resolver.mjs::detectGuardDowngradeEvents` (rung 2↔3 guard strength), `population-floor.mjs`
  (rung-3→5 vacuous-pass). They are one invariant wearing three coats. A shared
  `StrengthDelta {invariant, axis, from, to, covered}` — every gate emits deltas, one judge decides
  fail-vs-accountable — would make the ladder's central law a single legible code path and a single
  SARIF rule family. **Honest limit (carried from §16 G2):** the two *absolute-floor* checks (bare-`self`
  invalidity, the mutation strength floor) are rung-*floors*, not deltas — they stay separate by design
  (AHA: don't over-unify). So S1 unifies the *delta* family, not literally everything. **[real-work]**,
  medium; a clean refactor, not a behavior change.

### 17.3 Extend (new kernel capability)

- **E1 — Aggregate strength as a ratcheted *level*, not a score.** Today strength is per-invariant. The
  whole repo could carry one legible grade: **`repo_level = min(rung over all tier-register rows)`** plus
  a **rung-distribution vector** `(R1..R5 counts)`. Crucially, follow **SLSA's** explicit rejection of
  continuous scores: a discrete *level* is a conjunction (one rung-3 row caps the whole repo at 3) and
  **cannot be gamed by averaging** the way OpenSSF Scorecard's weighted mean can (close one finding, let
  another rot, mean holds). Add **one non-compressible counter — the rung-3/prose-only count may only
  decrease** — as the ratchet (mirrors §4.4's exception-count meta-ratchet). The data already exists: the
  tier-register has every row (today: 26 prose-only · 4 hook · 4 lint · 3 archunit · 2 gate ⇒ repo min-rung
  ≈ prose-only). The `prose-tier-register` gate is the natural home. **[cheap]** — a derived metric over
  data we already maintain.
- **E2 — Gate self-efficacy meta-gate: "every gate ships a red fixture that provably makes it fail."**
  This generalizes the design's most-novel idea — *guard-must-resolve-AND-bite* — from code seams (where
  555 proves bite via mutation) to **the gates themselves.** No mainstream tool mandates this repo-wide:
  ESLint's `RuleTester` throws on an empty `invalid:` array but is opt-in per author; Semgrep's
  `# ruleid:` is omittable; OPA `--coverage` proves a deny branch *ran*, not that it ever returned true
  (coverage ≠ bite). The robust form is **two layers**: (a) *structural* — every registry row declares a
  negative fixture and the file exists; (b) *behavioral* — CI runs the gate against it and asserts it
  fails. Internal probe: **30/35 gates already have `selfTestFixturesDir`; 5 don't** (wire,
  consumer-presence, host-owns-truth, runtime-witness, contract-projection) and **nothing mandates the
  fixture provably fails.** So this is partly built — close the 5 gaps + add the mandate. The formal name
  for the failure it prevents is **vacuous pass** (model-checking; VaqUoT): a check that holds trivially
  because its trigger never fires. **[real-work]**, but small — the substrate is there.
- **E3 — Promote the mutation (`test-efficacy`) gate from manual-CI to blocking.** It is the *only*
  structural defense against **spec-test co-contamination** (agent bakes the same wrong assumption into
  impl + test, both pass). The agent-governance literature argues independently-verified bite belongs in
  the blocking tier, not opt-in. **Honest cost:** mutation runs are slow — realistic form is a *sampled /
  changed-seams-only* lane, not full-suite-every-commit. **[real-work]**, gated on a fast-enough sampling
  strategy.
- **E4 — Changeset-authorship integrity (close the self-excusing-exception hole).** The exception
  protocol I exercised today (register a surface / raise a ceiling with an accountable changeset) has one
  residual hole the agent-governance survey flags: **the same change can both widen a baseline and author
  the changeset that excuses it.** A meta-gate that flags "baseline widened + its covering changeset
  authored in the same change, with no out-of-band sign-off" would make the accountability real rather
  than self-issued. **[real-work]**, a new rung on the §4 protocol. (Open caveat below: at solo-human
  scale "out-of-band" may just mean a louder surfacing, not a second human.)

### 17.4 New UX (the ladder made legible — Layer 3, the genuinely user/agent-facing payoff)

The shipped `GovernanceView` is the seed; `history.mjs` is only 44 lines, so the trend tier is wide open.

- **U1 — A living "strength map":** rung-distribution histogram + the single repo-level number + a trend
  with **two separate lines — rules-hardened vs rules-added** (so "coverage held flat but we piled on
  prose rules" is visible, not hidden). **[cheap–real-work]**
- **U2 — "Weakest links," friction-ranked (the one actionable output).** CodeScene's insight —
  *debt that matters = weakness × activity* — applied to governance: rank prose-only rules by the **git
  churn of the paths they govern.** A prose rule on a frozen module is cheap; the same rung on a path
  every agent commit touches is a live liability. For an **agent** maintainer this is the highest-signal
  artifact (a 5-row ranked JSON beats any chart): "harden *here* next, it pays off most." **[real-work]**
- **U3 — Per-change strength delta.** "This change moved rule X rung 3→2 (+), or widened a catch clause
  rung 2→5 (−)" — a Danger-JS-style diff of a before/after tier-register snapshot, surfaced at authoring
  time so a *downgrade is loud the moment it happens*, not at audit. Directly serves §5. **[real-work]**
- **U4 — Token Editor APCA readout.** The §16 `apcaLc` signal could join the editor's existing live
  WCAG badge so authors see the *perceptual* number too. **[cheap]**, a small follow-on to shipped work.

### 17.5 Honest open problems (the residue the ladder still cannot close)

The survey sharpened, but did not dissolve, Wall 1/2. These stay rung-5 and should be named, not papered:
- **Import-invisible gaming** (METR monitorability work): a capable agent reaches a forbidden semantic
  effect via wrapping/delegation/renaming that a syntactic scan can't see — exactly why the repo already
  tags `check-run-renderers` / `check-search-issuance` "early-warning, NOT a hard gate."
- **Multi-step temporal gaming** (DeepMind MONA): each commit looks compliant; only the *sequence* is
  harmful. A commit-boundary gate sees one snapshot. No practical CI solution at this scale.
- **Self-evolving governance**: agents here *can* edit `.claude/rules/` and `governance/*.json` — a static
  gate written before the edit cannot govern the edited rule. E4 is a partial answer; the general case is open.

### 17.6 New prior art (not in Appendix A/C)

Aggregate scoring: **SLSA levels** (discrete conjunction + attestation, explicitly rejects continuous
scores), **OpenSSF Scorecard** (weighted-mean, gameable), **OWASP SAMM 2025** (zero-out overperformance),
**CMMI**, **Microsoft Secure Score**. Testing-the-checks: **ESLint `RuleTester`** (throws on empty
`invalid`), **OPA `--coverage`** (line ≠ bite), **Statfier/StaAgent** (metamorphic testing of static
analyzers), **VaqUoT** (canonical vacuous-pass definition). Debt/UX: **SQALE / SonarQube debt ratio**,
**CodeScene** (friction = health × hotspot), **Code Climate**, the SRE "single pane of glass" critique,
**Danger JS** (per-PR delta). Agent governance (2025-26): **Koch 2026** (`arXiv:2604.05229`,
enforceability rubric — the cost-to-game lens), **DeepMind MONA** (multi-step reward hacking),
**Policy Cards** (`arXiv:2510.24383`, agent-legible specs), **METR monitorability** (import-invisible
blind spots), the documented **test-suite-elision** incident. Vocabulary gained: *posture drift*,
*attestation*, *cumulative requirements* (SLSA), *vacuous pass*, *control/policy coverage gap*,
*specification gaming / reward hacking*, *defense-in-depth / Swiss-cheese*.

### 17.7 If one thing is ever picked up

**E1 (ratcheted min-rung level + the rung-3 counter)** is the cheapest high-leverage move — it makes the
whole repo's enforcement strength a single legible, ungameable, monotone number over data that already
exists, and it gives U1/U3 their headline metric. **E2 (every gate ships a red fixture)** is the
truest-to-the-thesis extension. Both are small. The **17.0 reframe** is free and changes how every future
rung decision is reasoned about — that is the highest value-per-word of the whole round.

## Appendix A — Prior-art evidence (condensed)

**Rung 4 / exceptions (Thread A).** betterer (`.betterer.results`, auto-tighten-on-improve only),
PHPStan/Psalm/mypy/basedpyright baselines (`reportUnmatchedIgnoredErrors`, one-directional, known
"baseline rot"), ESLint bulk-suppressions (count-based, edit-stable, `--prune`), size-limit/
bundlewatch/Lighthouse-CI (hard caps, manual ratchet), SonarQube "Clean as You Code" (no baseline →
no rot, but never forces legacy cleanup), eslint-plugin-unicorn `expiring-todo-comments` / DebtBomb
/ Laravel Deadlock (time/version-bound — wrong axis for a metric, but contribute owner+ticket).
**Finding:** `covers:`'s *bidirectional value-bound* is genuinely ahead — others bound on improve
**or** time, none on regress-past-an-authored-ceiling. Fitness-function framing: Ford/Parsons/Kua,
*Building Evolutionary Architectures* (atomic/holistic, triggered/continual, static/**dynamic** —
`covers:` is the respected "dynamic" category). Agent-fleet governance: MS Agent Governance Toolkit
(2026-04); "agents respond to gate signals over docs; the check must run in the commit pipeline;
agents optimize to whatever is measured."

**Rung 1-2 / tokens (Thread B).** Material 3 (`on-*` role pairs), Radix Colors (12-step: 9-10 fill,
11-12 text — APCA-guaranteed), IBM Carbon (`text-on-color`), USWDS ("grade"/"magic number"
arithmetic contrast), Tailwind v4 (oklch ramps). Generation: Adobe Leonardo, **apcach** (compose a
color in oklch at a target APCA Lc), culori. APCA vs WCAG-2: APCA is perceptual and handles
mid-tone saturated hues WCAG-2 under-penalizes — but **not a standard as of April 2026** (Roselli),
WCAG-3 ~2030. **Finding:** the #3 bug is *taxonomic* (one token, two roles); every mature system
prevents it by splitting fill from text — rung 1.

**Rung 1-3 / registers (Thread C).** ArchUnit (`freeze()`), dependency-cruiser, import-linter, Nx
boundaries, Spectral, OPA/Conftest, **Semgrep (mandatory per-rule test fixtures)**, SARIF 2.1.0.
**Finding:** no mainstream tool ships the *guard-must-resolve-and-bite* meta-invariant; the closest
are Semgrep's fixtures and the repo's own `prose-tier-register`. Unify cross-cutting concerns
(reporting, exception protocol, guard-integrity) — not domain reasoning (OPA-DSL-vs-Custodian-YAML
caution; AHA).

## Appendix B — the original idea-catalog, re-mapped to rungs

The ~24 tiered ideas from the first 576 pass, now placed on the ladder (the design above is their
synthesis; this is the menu):

- **Rung-3 detection / guard-integrity (§3):** C1 (guard discriminated-union schema), C2 (kernel
  guard-resolution meta-pass), C3 (guard-must-bite via mutation), C5 (population/closure guard),
  C6 (register-of-registers), C4 (shared SARIF/changeset substrate).
- **Rung-4 / exceptions (§4):** A1 (loud self-explaining revoke), A2 (meta-ratchet the exception
  count), A3 (accountability frontmatter), A4 (soft-expiry warning tier), A5 (deterministic
  changeset merge), A7 (per-metric trend store — `lib/history.mjs`), A8 (rebalance stays explicit).
- **No-silent-downgrade / holistic (§5):** A6 (cross-metric holistic gate).
- **Tokens up the ladder (§6):** B7 (role-typed slots → rung 1), B1 (full `text-*` suite),
  B2 (auto-derive at target ratio via apcach), B3 (contrast-matrix gate → rung 2), B4 (APCA dual-
  threshold), B8 (per-theme polarity), B5 (Token Editor live readout → Layer 3), B6 (nudge-to-
  compliant).

## Appendix C — sources

betterer; PHPStan/Psalm/mypy/basedpyright baselines; ESLint bulk-suppressions (2025-04);
SonarQube "about new code"; size-limit/bundlewatch/Lighthouse-CI + web.dev budgets; *Building
Evolutionary Architectures* (Ford/Parsons/Kua); eslint-plugin-unicorn; DebtBomb; Laravel Deadlock;
MS Agent Governance Toolkit; aipatternbook.com. Material 3 / Radix / USWDS / Carbon / Tailwind v4
color docs; Adobe Leonardo; apcach; apca-w3/apca-check; APCA-in-a-Nutshell; Roselli "WCAG3 contrast
as of April 2026"; culori. ArchUnit; dependency-cruiser; import-linter; Nx; Spectral; OPA/Conftest;
Semgrep; SARIF 2.1.0. Internal: tempdocs 530 (kernel), 531 (consumer-drift), 553/559 (execution &
presentation registers), 555 (test-efficacy/mutation), 557/567 (token roles & contrast),
560/561/571/575 (register family). **§17 second-round additions (2026-06-15):** SLSA levels; OpenSSF
Scorecard; OWASP SAMM 2025; CMMI; Microsoft Secure Score; ESLint RuleTester; OPA `--coverage`; Conftest
verify; Statfier / StaAgent (metamorphic analyzer testing); VaqUoT (vacuity detection); SQALE / SonarQube
debt ratio; CodeScene (code-health × hotspot friction); Code Climate; the SRE "single pane of glass"
critique; Danger JS; Koch 2026 "From Governance Norms to Enforceable Controls" (arXiv:2604.05229);
DeepMind MONA; Policy Cards (arXiv:2510.24383); METR monitorability evaluations; the documented
test-suite-elision incident.

## Status

**DESIGN THEORY COMPLETE + DE-RISKED; §3-§6 SCAFFOLDING IMPLEMENTED + MERGED (2026-06-11).** The
design theory is documentation; the five mechanisms (§10) were then implemented and merged to
`main` — see **§11** for the post-merge state and the honest design-vs-shipped gap. The
structure is the **enforcement-strength ladder** + the substrate's three jobs (locate /
forbid-silent-downgrade / make-legible), realized as the maturation of tempdoc 530's four-layer
kernel. A read-only feasibility pass (§9) tested §3-§6 against the code: the thesis held, two items
got cheaper, four got leakier, and §8 was re-sequenced accordingly. **Confidence in the remaining
work: 7/10.** Each numbered gap (§3-§6) would be its own normally-scoped tempdoc if picked up. No
obligation is created here. **§12 (2026-06-15)** adds a velocity-calibrated effort estimate for that
remaining deeper work, grounded in measured land-merge diffs on `main`. **§17 (2026-06-15)** adds a research-only forward look —
the *cost-to-game* reframe (a rung is as strong as the gap between gaming-cost and complying-cost) plus a
build-state-marked menu of polish / simplify / extend / UX extensions, grounded in a second external
survey (aggregate-level scoring, testing-the-gates, enforcement-debt UX, agent-fleet governance) and an
internal probe of what is already shipped. No obligation created; E1 (ratcheted min-rung level) and E2
(every gate ships a red fixture) flagged as the cheapest high-leverage moves.

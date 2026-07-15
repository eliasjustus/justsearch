---
title: "738 — Simple/Detailed disclosure coverage: bring user-facing technical strings under the one uiMode authority"
type: tempdoc
status: implemented + independently reviewed; merged forward onto main 2026-07-15 and re-verified. Pending merge. NOTE — authored as tempdoc **696**; briefly renumbered to **728** on 2026-07-15, then renumbered again to **738** the same day after 728 was found to collide with in-flight work in other worktrees (see §Renumber) — so pre-2026-07-15 references to "tempdoc 696", and any short-lived same-day reference to the intermediate 728 number, meaning disclosure both mean this doc
created: 2026-07-08
updated: 2026-07-15
related:
  - 557 (uiMode / Simple-Advanced authority origin, Q8)
  - 586 (uiMode rail-trim consumer, F-2)
  - 687 (Search Thread — degradation banner + collapsed pill)
  - 565 (run-step presentation authority)
  - docs/explanation/27-frontend-presentation-kernel.md (the authority pattern this conforms to)
---

# 738 — Simple/Detailed disclosure coverage

## Problem

The shipped UI shows internal/system vocabulary to every user regardless of their Simple/Advanced
preference: the search-degradation banner names its raw cause ("Learned re-ranking (LambdaMART) is
not configured"); the results meta line shows a millisecond timing and an internal mode token
("… · 178ms · Keyword"); result rows show a raw filesystem path; the agent surface shows the model's
raw reasoning body, the model name, and a raw "Paused — awaiting budget" state. None of these consult
the user's Simple/Advanced preference.

This is **incomplete coverage of an existing authority**, not a missing feature — the
representation-drift class the frontend presentation kernel exists to prevent
(`docs/explanation/27-frontend-presentation-kernel.md`). `state/uiModeState.ts` is the app-wide
Simple/Advanced authority (tempdoc 557 Q8); today its only live consumer is the `Shell` rail-trim
(tempdoc 586 F-2). The technical strings above were never brought under it.

The related "banner is oversized" observation is a **downstream symptom**: the degradation banner is
tall only because it renders its raw causes expanded. Gating the causes on disclosure makes it a slim
pill by default — no size-budget structure is needed.

## Approach

Complete the coverage. Each user-facing string that encodes an internal fact becomes a
**plain-or-technical projection gated by `uiMode`**, defaulting to plain (Simple). This is the
disclosure sibling of the kernel's existing single authorities (tone, originator, display-name,
display-fact, availability). No new disclosure mechanism is introduced — consumers project from the
existing `uiModeState`.

Concretely:

- **Surface a `Simple | Detailed` toggle** in the shell chrome so hidden detail is always
  recoverable (dispatching the existing journaled `set-ui-mode` action seam that Settings uses).
- **Degradation banner:** default to the collapsed one-line pill (the `renderCollapsedDegradationBanner`
  form already shipped in 687); render the raw `causes` only in Detailed or via the notice's local
  "See details" expand. A `severity: error` verdict opens expanded even in Simple. Plain-language
  `headline`/`body` copy (`state/readinessNotice.ts` already separates a plain headline/body from the
  technical, severity-tagged `causes[]`).
- **Search results:** in Simple, translate the retrieval-mode token and the latency to plain wording,
  and render a humanized breadcrumb location (derived from the result's existing `path` + `collection`
  fields) instead of the raw path; Detailed restores the raw mode/ms/path.
- **Agent surface:** the authored run-step labels (the run-step presentation authority, tempdoc 565
  §17) stay in both modes; the raw model-reasoning body, the model name, and the raw budget state are
  gated to Detailed / translated to plain in Simple.

## What this supersedes (teardown rides along)

The banner's default-expansion is now decided by disclosure + severity, which supersedes tempdoc
687's "expand once, then remember the seen cause-set" machinery. Keeping both would be two mechanisms
deciding one thing (the drift this kernel forbids). Removed in the same change: the per-cause-set
`seenDegradationCauseHash` persistence (userConfig field + setter), the cause-hash + arming guard, and
the seen-state default logic. The collapsed renderer and the local expand affordance are kept — they
become the default form.

## Scope

In: the degradation banner, the search meta line + result location, the agent raw-body/model-name/
budget strings, the toggle, and the teardown above. Out (tracked separately, not entangled here): the
leaked GUID-filename search result (a filed indexing/content-hygiene bug), export-button emphasis, the
agent-run-completion behaviour, the onboarding-card dismissal, and the agent-bubble proportion
question.

## Principle (recorded; not built as generalized structure)

**Detail level is a presentation authority, not a per-surface choice** — every user-facing string
that encodes an internal fact is a plain↔technical projection gated by the one Simple/Detailed
authority, defaulting to plain. It applies beyond this change (Health, Security, AI Brain, Library);
the search "why this result?" evidence already conforms, so the principle is half-applied and these
leaks are where it isn't. It earns its keep if future UI review surfaces fewer "raw jargon shown to a
Simple-mode user" findings; it should be retired if the product ever collapses Simple/Advanced into a
single always-plain mode. The generalized structure is deliberately not built now — the present
problem only needs the specific sites wired.

## Proportion/density — the deferred aspect, now measured and activated (→ tempdoc 697)

This tempdoc argued the notification's *size* would "dissolve into the disclosure fix" (collapse it
to a pill and it's slim). **Live measurement falsified that** (2026-07-08, against the running stack):

- the Simple **collapsed pill** is **~76px** for a single line — ~2× a slim pill — because the
  remedy renders as a full-size op-button (~42px) inside a one-line row;
- the **user message bubble** is **~75px** for a 17px one-line message (audit-3's **D1**): the
  `.message` `white-space: pre-wrap` (`views/unifiedChatStyles.ts`) renders the Lit template's
  surrounding newlines as ~2 phantom blank lines;
- the **expanded banner** is ~159px (mostly legitimate content, plus the same oversized button).

These are **distinct root causes** in **distinct components** — not one shared bug, a *class*. That
is exactly the earn-its-keep trigger the proportion principle was parked behind ("build it only if
oversize findings recur across multiple distinct chrome atoms after disclosure is fixed"). The
condition is now met, so the proportion principle activates and its **long-term fix is designed
separately in tempdoc 697** (a measured size-budget guardrail + per-component root-cause fixes) —
kept out of this tempdoc because it is a cross-cutting concern, not part of disclosure coverage.

## Implementation status (2026-07-08)

Shipped in four commits on `worktree-ui-audit-density-review` (unit suite green throughout — 3719):

- **Toggle (Shell topbar).** A `Simple | Detailed` segmented control renders from the live
  `getUiMode()`; selecting a mode calls `setUiMode` + a best-effort `POST /api/settings/v2 {ui:{mode}}`
  (mirrors Settings' persist shape, independent of SettingsSurface being mounted).
- **Degradation banner.** Defaults to the collapsed pill; raw `causes` render only in Detailed / via
  the local "See details" expand; a `severity:error` verdict opens expanded even in Simple. Tempdoc
  687's seen-hash machinery (`syncDegradationBannerExpansion`, `degradationCauseHash`,
  `armedDegradationCauseHash`, `setSeenDegradationCauseHash` + the `seenDegradationCauseHash` userConfig
  field) is removed — **the teardown rode along in the same commit.**
- **Search results.** `ResultsCard` projects the mode label and latency plainly in Simple
  ("exact-word search", "found in 0.06s") vs technically in Detailed ("Keyword", "62ms"); the result
  location is a folder breadcrumb ("ssot › docs › help") in Simple vs the full path in Detailed
  (new path-derived `formatLocationBreadcrumb`).
- **Agent surface.** The per-turn receipt's model name is Detailed-only (C7); the run's budget state
  is plain in Simple ("Paused — waiting to continue") vs technical in Detailed (C8). C6 (raw model
  reasoning) needed no new gating — `ReasoningBlock` and terminal `ToolCallCard`s already collapse by
  default, and gating tool args further would hide approval context.

**Live browser validation (worktree FE on a real backend, both modes):** the toggle, the banner
(Simple pill ↔ Detailed expanded causes), the meta line, the breadcrumb, and the Q&A receipt model
name all round-trip correctly. Live validation additionally surfaced and fixed a boot desync — the
topbar toggle initially lagged the async settings seed when `ui.mode='advanced'` was persisted; fixed
by rendering the toggle from the live authority.

**Deferred / not done (intentional):**
- **Plain-copy pass on `readinessNotice` headlines** (e.g. "Reduced search capability" → a plainer
  phrase). The C1 leak (the raw LambdaMART *cause* string) is fixed structurally by the collapsed-pill
  default; rewriting the shared headline/body copy risks diverging from the Health header and is an
  owner-tunable product-copy decision, left as a follow-up.
- **C10 (stacked banners)** — largely subsumed: the Simple-mode banner is now a slim pill rather than a
  full-height block, so it no longer stacks as a competing banner with the top-center recap digest. A
  dedicated cross-component arbitration was not built (would be structure for a case the slim pill
  already resolves); revisit only if a real collision recurs.
- **C8 budget-gate live check** — the plain/technical budget string is unit-tested; a live budget-gate
  is hard to trigger deterministically and was not exercised in-browser.

## Independent review round (2026-07-08)

An independent review (reviewer ≠ implementer) surfaced real gaps, all addressed:

- **Two additional raw-path leak sites** were closed: the document reading-pane header
  (`DocumentPane`) rendered the raw path unconditionally (C4 one click from a result), and the
  `ResultsCard` breadcrumb had a raw-path fallback that re-leaked for a drive-root file in Simple.
  Both now gate/behave correctly.
- **The Settings "Interface mode" toggle** (a second control over the same preference) desynced from
  the new topbar toggle and used a divergent label. Live validation corrected the review's file
  pointer: the *visible* control renders through the **declared-surface option-group**
  (`themes/builtinPresentations.ts`), not the hand-authored `renderInterface`. Fixed there — the
  label reads "Detailed", and a `uiMode` subscription keeps `this.ui.mode` (the declared binding) in
  sync so a topbar change updates the Settings toggle. Both live-validated in-browser.
- **Regression tests added** for the previously-untested gated sites (C8 budget, meta latency,
  result path/breadcrumb wiring + root-file no-leak, DocumentPane breadcrumb, and a Shell toggle test
  guarding the live-`getUiMode` boot-desync fix), per `audit-driven-fixes-need-test`.

## Rollout / verification

Ship behind the existing `uiMode` preference, reversible per surface. Verify with unit tests over
both `uiMode` states, and — because this is user-visible — live in-browser validation on both the
search and agent surfaces in both Simple and Detailed, with the local model active for the agent
run. Independent review (reviewer ≠ implementer) before merge, per the slice-execution rules.

## Verification record (2026-07-15 closeout) — what carries evidence, and what does not

`## Rollout / verification` above is the *plan*. This is the *record*. Split deliberately, because two
of this tempdoc's claims rest on evidence that was never written down.

**Verified 2026-07-15, each with its evidence:**

| claim | evidence |
|---|---|
| the disclosure gating is intact against current `main` | ui-web unit suite **3731 passed / 363 files**, typecheck exit 0 — re-run *after* merging `origin/main`, not on the authoring base |
| C7 (model name hidden in Simple) is really gated in production, not just in the test | `views/UnifiedChatView.ts` — `if (receipt.modelLabel && isAdvancedMode()) parts.push(receipt.modelLabel);` — read at source by the independent reviewer (reviewer ≠ implementer) |
| the C7 test-string change is not a masked regression | the `not.toContain('Llama 3 8B')` intent assertion is untouched and passes; `toBe(...)` remains exact-match, tightened to the new upstream string rather than loosened to `contains` |
| the ~45 renumbered citations changed no meaning | 45 removed / 45 added, symmetric; the reviewer's refutation target held — `scripts/dev/justsearch-dev-mcp/server.mjs:138,2564` cites the *unrelated JDK* 696 and was correctly left alone |
| no regression across the frontend gate surface | full `ui-web-gates` recipe: **39 checks, none skipped**, green except 3 pre-existing `main` failures in untouched files |
| build + public CI | `./gradlew.bat build -x test` → `BUILD SUCCESSFUL`; PR #188 → 10/10 CI checks pass, `mergeStateStatus: CLEAN` |

**Unverified assumptions — claims WITHOUT a recorded evidence pointer:**

- **"Live browser validation … the toggle, the banner, the meta line, the breadcrumb, and the Q&A
  receipt model name all round-trip correctly"** (§Implementation status, 2026-07-08). No screenshot,
  measure-run, or evidence-bundle id was recorded. It is plausible and consistent with the unit tests,
  but **it is the original implementer's word, and it was not re-run on 2026-07-15.** The 2026-07-15
  re-verification is unit-tier + static-review-tier only. Anyone revisiting this should re-run the live
  pass rather than inherit the claim.
- **C8 (budget state) was never live-exercised** — explicitly deferred (§Implementation status): a live
  budget-gate is hard to trigger deterministically. Unit-tested only. Still true.
- **The a11y evidence for this tempdoc's surfaces is partial.** `ui-a11y-gate` covers six *view*
  surfaces; it structurally cannot render a degraded banner or a chat turn. Tempdoc 697's
  `chat-proportion` step covers the chat states (0 axe violations, 0 console errors), but the *search*
  meta-line/breadcrumb changes have no dedicated measured a11y capture beyond the `search` view step.

**Deferred / not done** is recorded in §Implementation status and remains accurate: the plain-copy pass
on `readinessNotice` headlines (owner-tunable product copy), and C10 stacked banners (largely subsumed
by the slim pill; revisit only on a real collision).

## Renumber: 696 → 728 → 738 (2026-07-15)

This tempdoc was authored as **696** on 2026-07-08 in the `ui-audit-density-review` worktree. In
parallel, a different agent authored an unrelated `696-dev-tooling-jdk-resolution-and-line-ending-normalization`
that reached `main` first. Two unrelated docs therefore claimed 696 — in a repo where tempdocs are
cited by bare number ("tempdoc 696"), including from ~45 code comments. Renumbered to **728** (the
next free number; highest on main was 727), and every disclosure-meaning citation was repointed.
Upstream's 696 is untouched.

That renumber picked 728 by checking only `main` for a free number, which cannot see other
worktrees' in-flight docs — 728 was already claimed by `728-sandbox-validation-redesign.md`,
in-flight across four other local worktrees at the time. `node scripts/ci/check-tempdoc-numbers.mjs`
correctly caught this second collision (worktree-vs-worktree is exactly the case it's designed to
catch — see the gap note below for what it *doesn't* catch). Renumbered again to **738**, verified
free across every registered worktree and `origin` for both `docs/tempdocs/**` and
`gates/*/.changesets/**`, and every disclosure-meaning citation (including the ~45 code comments) was
repointed a second time. Upstream's 728 (the sandbox-validation doc) is untouched.

**`check-tempdoc-numbers` did not catch the original 696 collision, and that is a real gap** (logged
to the observations inbox). It only reports a number claimed by two or more *in-flight* worktrees:
any basename already on `origin` is filtered out of the comparison
(`scripts/ci/check-tempdoc-numbers.mjs:117`), so an in-flight doc colliding with an already-merged
one passes green. Reproduced live — the same run that missed the 696 collision flagged an unrelated
720 collision between two other worktrees. The check guards worktree-vs-worktree (as the 728
collision above confirms), not worktree-vs-main.

## Merge-forward onto main (2026-07-15)

The branch sat a week and fell 80 commits behind. `git merge origin/main` produced **zero textual
conflicts** — and one **semantic** one, which only the post-merge suite caught:

- **C7's receipt test broke.** Upstream PR #171 (tempdoc 720, "honest grounding badge for settled
  zero-citation answers") reclassified a settled, zero-citation, chunk-precise answer from the
  `grounded` frame to `sourced`. `answerFrameLabel()` returns `null` for `grounded` but a real string
  for `sourced` (`components/chat/evidenceProjection.ts:182`), so the receipt line gained
  "Based on your documents — per-sentence grounding not verified".
- **C7's actual behaviour was never broken** — `not.toContain('Llama 3 8B')` still passed. What broke
  was an over-tight `toBe('3.2s')` that incidentally pinned the whole line. Fixed by making the
  expected string exact against the new upstream text, *not* by loosening it to a `contains` check
  (that would be the "make the failure invisible" move this repo forbids).
- Post-merge: typecheck exit 0, **3731/3731 unit tests green**.

**Lesson worth carrying:** the branch was fully green on its own week-old base, and that green meant
nothing — it verified a base that no longer existed. Diffstat, conflict count, and ancestry all looked
clean. Only running the suite against the *merged* base surfaced the break. (Ancestry was also the
wrong instrument for "has this landed?" — per `squash-merge-verify-content-not-ancestry`, the content
check `git diff <branch> origin/main -- <paths>` is the reliable one.)

## Finding: a new leak of this very class, arrived post-authorship

`answerFrameLabel()` renders "Based on your documents — **per-sentence grounding not verified**"
**unconditionally — it never consults `uiMode`**. That is technical vocabulary shown to a Simple-mode
user: exactly the leak class this tempdoc exists to close. It arrived *after* this tempdoc was written,
via a PR that had no way to know the disclosure authority existed.

It is **out of scope here** (this tempdoc's scope names the banner, meta line, location, and the agent
model-name/budget strings; the evidence surface was assessed as already-conforming) and it belongs to
someone else's shipped feature, so it is logged to the observations inbox rather than absorbed.

But it is the strongest available evidence for this tempdoc's own **Principle**: detail level is a
presentation authority, and a *coverage* that depends on each future author knowing the authority
exists will keep leaking. The Principle section says the generalized structure is deliberately not
built because "the present problem only needs the specific sites wired." This finding is the first
data point against that — the sites keep multiplying at prose-tier (~70%) adherence. Not an argument
to build it now; an argument to record the trigger. Compare tempdoc 697, which faced the identical
question for *proportion* and resolved it by moving the invariant to Gate-tier rather than trusting
review.

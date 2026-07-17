---
title: "Java formatting is deliberately not enforced — remove the dead google-java-format branch"
status: "decided + implemented 2026-07-15 (owner decision, same session); PR-ready 2026-07-17. GJF removed; whitespace/newline hygiene retained unconditionally; the CLAUDE.md claim corrected. This is a DECISION record — the standing answer to 'why is there no Java formatter?'"
created: 2026-07-15
author: agent session 1b3050fb (Opus 4.8); owner decision by Elias
category: build / tooling / decision-record
related:
  - the `dependency-update-audit` tempdoc (236) — `status: done`; its :144 row deferred google-java-format 1.34.1 and :344 recorded the reason ("Reformats all Java; must be isolated commit"). That deferral is now RESOLVED as "won't do" rather than left pending.
  - the `dependency-hygiene-triage` tempdoc (591) — `status: implemented-mostly`; its :105 "spotlessCheck green — no reformat … rule-neutral" is the misread this doc explains.
  - the `config-surface-triage` tempdoc (728) — same session; same failure shape (scaffolding survives and reports success after the substance is gone). GJF was explicitly out of 728's scope, which is why this doc exists.
---

# 729 — Java formatting is deliberately not enforced

## The decision

**JustSearch does not auto-format Java.** Google Java Format is removed, not deferred.
Trailing-whitespace and final-newline hygiene are retained.

If you are here because you noticed there is no Java formatter: that is intentional.
Do not re-add one without re-reading "Why" below.

## What was actually there

`JvmBaseConventionsPlugin.kt` gated formatting on:

```kotlin
if (enableGjf || currentMajor < 23) { java { googleJavaFormat(...) } }
else { format("javaSources") { trimTrailingWhitespace(); endWithNewline() } }
```

`enableGjf` was set **nowhere** in the repo, and the JDK is **25** everywhere (toolchain
`JvmBaseConventionsPlugin.kt:69`, local, and CI `ci.yml` `JAVA_VERSION: "25"`). So the
`else` branch was the permanent, only path. Proven mechanically, not inferred: Gradle
registered `spotlessJavaSources*` tasks and **no `spotlessJava` task** — the latter is what
`googleJavaFormat()` would have created.

Net effect: **Google Java Format had not run in this repository since the JDK-23 crossover.**
`spotlessApply` on Java trimmed whitespace. `spotlessCheck` green meant "no trailing
spaces", not "formatted".

## Why it survived nine months

Not an oversight — a **misread green**. The GJF 1.34.1 upgrade was deliberately deferred
with sound rationale (236:144, 236:344 "Reformats all Java; must be isolated commit"). The
guard was correct when written ("Minimal hygiene fallback for newer JDK until GJF
compatibility lands"). Then the project moved to JDK 25 and the temporary branch became
the only branch, silently.

The signal that should have caught it was the thing that was broken: 591:105 recorded
*"spotlessCheck green — no reformat … rule-neutral"* as **evidence** the Spotless bump was
safe. The green meant the formatter wasn't running. A deferral priced as "formatting stays
as-is" actually bought "formatting is unenforced".

**The defect was the silent fallback, not the missing formatter.** A build that printed
"Java formatting not enforced on JDK 25" would have been caught in a week. Instead the
fallback kept the task list populated and the check green — the same shape as tempdoc 728's
findings (dead config that still resolves; a dead knob with a passing test).

## Why "don't enforce" is the right answer here (not "upgrade and reformat")

The textbook benefits of style uniformity mostly do not apply to this repo:

- **Ends style debates / lowers reviewer load** — worth ~zero. Agents author most code and
  do not bikeshed; there is one reviewer.
- **Onboarding** — worth ~zero. No team onboarding.
- **Diff-noise elimination** — the only benefit that matters, and the *cheap* 20% of it
  (trailing whitespace, final newline) is retained. LLM-authored Java is already close to
  conventional layout, so residual drift is small.

Against that, enabling GJF costs one commit touching every Java file, conflicting with
every in-flight worktree, that nobody reviews line-by-line.

**Honest limits of this reasoning:**

- The drift magnitude was **not measured**. Measuring it means running GJF 1.34.1 standalone
  (1.25.2 cannot run on JDK 25 — the whole problem) and counting changed files. If someone
  wants the decision on data, that is the experiment.
- **The one real counter-argument**: agents pattern-match on surrounding code, so drift is
  self-reinforcing here in a way it is not on a human team following a style guide. This is
  the thing that could legitimately reverse this decision. Watch for it; it is not
  hypothetical.
- Rejected counter-argument: *"it only gets more expensive, do it now."* A reformat's cost is
  dominated by the coordination window, not file count — a bigger mechanical diff is not
  meaningfully harder.

## What changed

**Removed** (dead by construction — could not execute):
- the `enableGjf` gradle property and the `if/else` conditional
- the `java { googleJavaFormat(gjfVersionStr ?: "1.25.2") }` branch
- the `gjfVersionStr` catalog lookup
- `googleJavaFormat = "1.25.2"` from `gradle/libs.versions.toml` — a dependency carried for
  code that never ran

**Retained, now unconditional and honest** — `format("javaSources") { trimTrailingWhitespace();
endWithNewline() }`. This is the only thing enforcing that hygiene: **there is no
`.editorconfig` in this repo** (verified), and agents writing through file tools never pass
through an editor that would apply one. Deleting it would have started landing trailing
whitespace in diffs with no backstop — i.e. it would have cost the one benefit that mattered.

**Task name deliberately preserved.** `build.gradle.kts:256` wires
`setOf("processResources", "spotlessJavaSources")` into a `mustRunAfter`. The *fallback*
task name had become load-bearing in the root build — evidence of how deep the "temporary"
state had rooted. Keeping `format("javaSources")` keeps that wiring intact.
(Latent trap now closed: had anyone enabled GJF, `spotlessJavaSources` would have vanished
and that `mustRunAfter` would have silently matched nothing.)

**Corrected** — `CLAUDE.md`'s "Build fails on PMD/Spotless violations — run `spotlessApply`
first". True for PMD; misleading for Java formatting. Agents were being told to run a
command that did almost nothing, every session.

## Verification

- `./gradlew.bat build -x test` green; full `./gradlew.bat test` green.
- **The load-bearing task name survives**: `:modules:core:tasks --all` still lists
  `spotlessJavaSources{,Apply,Check,Diagnose}`, so `build.gradle.kts:256`'s `mustRunAfter`
  wiring is intact. (There is still no `spotlessJava` task — correct; that is what
  `googleJavaFormat()` would have registered, and it is gone for good now rather than
  gone by accident.)
- `prose-tier-register` gate: pass (CLAUDE.md edited).
- **Lockfiles deliberately NOT regenerated.** `google-java-format` appears in 34
  `gradle.lockfile`s and that is *correct and unrelated*: the locked coordinate is
  `com.google.googlejavaformat:google-java-format:1.27.0=annotationProcessor,…` — ErrorProne
  pulls GJF in transitively for its suggested-fixes, at a different version (1.27.0) than the
  Spotless pin this doc removed (1.25.2), on a different configuration. The catalog entry and
  the annotationProcessor dependency were never the same thing. The build staying green after
  removing the catalog entry is the proof. **Do not "clean up" those lockfile entries** —
  they are live.

## Pre-existing issue found while doing this (not fixed here)

The `always-loaded-budget` ratchet was **already red before this change**:
`CLAUDE.md` was 22770 B against a 22656 B ceiling (over by 114 B) and
`.claude/rules/hooks-reference.md` is 2839 B against 2740 B (over by 99 B) — untouched by
this work. This change added 80 B and used the sanctioned
`--bump CLAUDE.md --reason …` path, whose recorded reason names the 114 B of pre-existing
overage it necessarily absorbs. `hooks-reference.md` was deliberately **not** bumped, so it
stays visible for its owner.

Root cause of the drift is worth noting because it is this doc's own theme: the check is
**not wired into CI** — its own `$comment` says *"Wiring: run pre-merge (manual now)"*. A
ratchet nobody runs silently stops ratcheting. Same shape as the GJF fallback: the guard
exists, reports nothing, and rots.

## Re-adding GJF, if this decision is ever reversed

Three lines in `JvmBaseConventionsPlugin.kt` plus a catalog pin. Use **1.34.1+** (1.25.2
cannot run on modern JDKs). Land it as an isolated reformat commit in a window with no
parallel worktrees — the plan 236:344 already scoped. Update this doc's status rather than
silently re-adding.

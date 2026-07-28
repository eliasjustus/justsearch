---
title: "793: Why Dependabot never bumps a Java library — discriminate and fix the library-flow gap"
type: tempdoc
status: "chartered (2026-07-28) from 792 §33. Investigation licensed; no library bump is licensed until the cause is named. Source evidence: 792 Part I §2 (five-PR census) and 792 Part V R1 (hypothesis re-ranking)."
created: 2026-07-28
related:
  - 792-stack-currency-audit-round-3   # the audit that found this; §2 + Part V R1 are the evidence
---

# 793 — Dependabot library flow: discriminate the cause, then restore it

## Why this lane exists

`.github/dependabot.yml` declares a weekly `gradle` ecosystem with group `gradle-deps` and
`patterns: ["*"]`. It runs, it opens PRs, and those PRs merge — most recently the same day 792's
audit ran. **And it has never once bumped a `[libraries]` entry in `gradle/libs.versions.toml`.**

Census of every Gradle Dependabot PR in this repository (792 §2): #8 and #306 merged, #61/#102/#186
closed. Every one of them changed only Gradle **plugin** coordinates. Meanwhile 32 libraries sit
behind, six across a major line.

The lane matters because the signal is *actively misleading*: a green, merging, weekly dependency
lane reads as "dependency maintenance is covered." Until libraries flow, 794's measurement work has
almost nothing to measure.

## What 792 already established (do not redo)

**Refuted.** The version-catalog `module = "group:artifact"` shorthand is **not** the cause.
Dependabot demonstrably parses and edits the catalog — PR #306 rewrote five `[versions]` entries and
one inline `[plugins]` version. A parser that could not read the file could not have done that.

**Leading hypothesis — lockfile reconciliation.** Upstream `dependabot/dependabot-core` issue #12557
records Gradle dependency lockfiles combined with a version catalog as a known-fragile combination
(reported 2025-07, closed via PR #12853). The reported symptom differs from ours — there the catalog
updated and the lockfile did not — but it establishes the interaction as a real failure surface. One
reading is that after the fix, Dependabot skips coordinates it cannot lockfile-reconcile rather than
opening a PR that would break the lock.

The internal correlation is exact as far as the available evidence goes:

| Coordinate class | Present in a `gradle.lockfile`? | Ever bumped? |
|---|---|---|
| spotless, spotbugs, dependency-analysis, ben-manes, protobuf-plugin, kotlin plugin marker | no | **yes** |
| every `[libraries]` entry (lucene-core in 12 lockfiles, grpc-core in 10, …) | yes | **never** |

**Not excluded — dependency verification.** `gradle/verification-metadata.xml` (1,795 components)
was present at the initial public release, so this repository has no "before" period against which
to compare. That discriminator is unavailable here and must be tested directly.

## The discriminating question

Not *"does Dependabot parse the catalog"* — it does. The question is:

> **Does Dependabot skip a coordinate because it appears in a `gradle.lockfile`, because it cannot
> regenerate `verification-metadata.xml`, or for some third reason?**

## Items

1. **Determine the cause.** Direct evidence first (upstream issue tracker and dependabot-core
   behaviour for the catalog + lockfile + verification combination). If documentary evidence is
   inconclusive, run the minimal experiment: a controlled change that isolates one variable and
   observe the next scheduled run. Do not run more than one variable at a time.
2. **Restore library flow**, by whatever the cause implies — configuration change, an upstream issue
   filed with a reproduction, or an accepted local mechanism if upstream cannot serve this repository's
   shape. All three are legitimate outcomes; a named cause with no fix is not.
3. **Make the gap non-silent.** Whatever the outcome, the failure mode this lane exposes — a
   dependency lane that is green while covering a fraction of what it appears to — should not be able
   to recur undetected. 792 §29 sketches the general form (a signal that declares its own scope);
   this lane needs only the specific instance.

## Acceptance

- The cause is named with primary-source evidence, or explicitly recorded as
  upstream-unresolved-with-a-filed-reproduction.
- A library-line bump reaches `main` through the restored path, verified by content and not by the
  lane's own green.
- The coverage gap has a detector, or a recorded reason why one is not warranted.

## Explicitly out of scope

- Bumping the backlog. That is ordinary PR work once flow is restored, and 792 §33 holds ONNX
  Runtime, Lucene and llama.cpp back deliberately as 794's validation set.
- Anything in 794 (stack identity, identity slices, migration).

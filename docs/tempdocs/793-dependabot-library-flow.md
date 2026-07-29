---
title: "793: Why Dependabot never bumps a Java library — discriminate and fix the library-flow gap"
type: tempdoc
status: "CAUSE FOUND (2026-07-28) — a credentialed GitHub Packages repository inside dependencyResolutionManagement makes every Gradle job fail with 'Dependabot can't authenticate to a private package registry'; plugins resolve via pluginManagement and are unaffected. Evidence: this repo's own Dependabot job logs. Fix designed, NOT applied — it touches build-critical library resolution and the Revapi baseline path. Awaiting go-ahead."
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

## RESOLVED (2026-07-28) — the cause is a private registry in the *library* repository block

**Primary source: this repository's own Dependabot job logs** (`/network/updates/35531433/jobs`, the
`build.gradle.kts` manifest). Every Gradle rebase job carries the same failure:

> **Errored with the message "Dependabot can't authenticate to a private package registry"**

Version-update jobs report "No PRs affected." So the lane is not silent by choice — it is **failing**,
and the failure is invisible from the outside because the group PRs that *do* land (plugins) make it
look healthy.

**Mechanism.** `settings.gradle.kts` splits repository declaration across two blocks:

| Block | Repositories | Resolves | Dependabot |
|---|---|---|---|
| `pluginManagement` | `gradlePluginPortal()`, `mavenCentral()` | **plugins** | fine — no private registry |
| `dependencyResolutionManagement` | a credentialed **GitHub Packages** maven repo, `google()`, `mavenCentral()` | **libraries** | **auth error** |

The GitHub Packages repository (used for Revapi baselines of previously-published `io.justsearch`
artifacts) is declared with `credentials { username/password from env }`, guarded by
`if (System.getenv("GITHUB_ACTOR") != null && System.getenv("GITHUB_TOKEN") != null)`. **Dependabot
parses that block statically and does not evaluate the conditional** — it sees a private registry and
fails.

GitHub's own ecosystem support table corroborates the limitation: for the `gradle` ecosystem,
**private registries are documented as not supported**.

This explains every observation at once, including the ones that defeated the earlier hypotheses:
plugins bump (clean resolution path), libraries never do (auth-failing path), no broken PRs are
produced, and nothing errors visibly in the repository.

**Hypotheses closed.** All three prior candidates are refuted, each on evidence rather than by
elimination:

- **`module = "g:a"` shorthand** — refuted at the source. Dependabot's version-catalog parser handles
  both `module = "group:artifact"` and separate `group`/`name` keys (`dependabot-core` PR #6249).
  792 Part V's earlier "refutation" of this reached the right answer by the wrong route and is
  superseded here; the reasoning error is recorded in §R1-correction below.
- **Shared `[versions]` references** (`dependabot-core` #6990) — refuted by local data: 19 never-bumped
  version keys are referenced by exactly **one** library each (`javalin`, `sqlite`, `cel`, `commonmark`,
  `lz4`, `directory-watcher`, `hdrhistogram`, `jqwik`, `archunit`, `findsecbugs`, …). Reference count
  is not the discriminator.
- **Lockfile reconciliation** (`dependabot-core` #12557, #14633) — weakened decisively: in both upstream
  cases Dependabot **opens the PR** and leaves the lockfile stale. Our symptom is no PR at all. Real
  bug, wrong bug.

The clean empirical split, measured across the catalog: **4 of 4** bumped `[versions]` keys are
referenced only by `[plugins]`; **0** bumped keys are referenced by any `[libraries]`.

### R1-correction — a reasoning error worth recording

792 Part V argued the shorthand hypothesis was refuted *because Dependabot demonstrably edits
`libs.versions.toml`*. That inference was invalid: every edit it made was on the `[plugins]` path, and
`[libraries]` is a **different parser code path** (`details_for_library_dependency` vs
`details_for_plugin_dependency` in PR #6249). Evidence from one path was used to clear another. The
conclusion happened to be right; the argument was not. This is the `interrogate-results` failure in its
most common form — a confirming-shaped result accepted without checking that it bears on the claim.

## FIX APPLIED (2026-07-29) — the repository was dead residue; deleted rather than relocated

Investigating the three candidate fixes below turned up a fourth, strictly better option: **the
repository resolved nothing at all.** Four independent checks, all negative:

| Check | Result |
|---|---|
| Is Revapi applied by any build script? | **No** — no plugin, no task, anywhere |
| Does any module declare an external `io.justsearch:` dependency? | **No** — inter-module edges are all `project(...)` |
| Has any `gradle.lockfile` ever pinned an `io.justsearch` artifact? | **No** |
| Does anything else need the repo? | **No** — it was filtered to `includeGroup("io.justsearch")` |

So the block was **residue from an abandoned Revapi baseline setup** — and residue that had become
actively destructive, silently costing the repository every library update for its entire public
history. This is the `retire-with-a-sweep` class exactly: the retiree's fingerprint outlived its
reason and acquired false authority.

**Change:** the `exclusiveContent { … GitHubPackages … }` block is deleted from
`settings.gradle.kts`, replaced by a comment recording why it is gone and instructing that a future
Revapi wiring supply the repository through a **CI-only init script** rather than re-adding it here.

**Explicitly untouched:** publishing to GitHub Packages. That is a separate
`publishing { repositories { … } }` block in `modules/app-api/build.gradle.kts` and
`modules/api-contract-projection-java/build.gradle.kts`, verified independent of resolution.

**Blast radius, honestly:** lower than feared. The deleted block was guarded by
`if (GITHUB_ACTOR != null && GITHUB_TOKEN != null)`, so it was already inert locally — a local build
proves the normal resolution path is unaffected but cannot exercise the CI path. **CI is the real
gate**, and the decisive signal is behavioural: the next scheduled Gradle Dependabot run must produce
a PR containing a `[libraries]` entry.

**Residue not swept here (deliberate):** `config/revapi/analysis.json`, `config/revapi/differences.json`,
`config/revapi/app-api-baseline.json`, and `gradle.properties`' "Revapi baselines" comment all survive
and are now unreferenced. Kept out of this change to hold a build-critical diff surgical and
reviewable; logged to the observations inbox as a `retire-with-a-sweep` follow-up. The inline comment
left in `settings.gradle.kts` is what prevents the repository from being re-added in the meantime.

## Candidate fixes considered (superseded by the deletion above)

The GitHub Packages repository is needed **only in CI**, and is already conditional on CI-only env
vars. The conditional is what Dependabot cannot see.

1. **Move it out of `settings.gradle.kts` into a CI-only Gradle init script.** Dependabot then parses a
   settings file containing no private registry; CI injects the repository via `--init-script`.
   Preferred — it makes the CI-only intent structural rather than a runtime conditional, and leaves
   local and Dependabot resolution identical.
2. **Declare it to Dependabot via `registries:`** in `dependabot.yml`. Cheapest, but the documented
   Gradle-ecosystem limitation says private registries are unsupported, so this is likely to fail.
3. **Drop the repository** if Revapi baselines can resolve another way. Smallest config, largest
   behavioural question — needs the Revapi baseline path checked first.

**Risk to weigh before applying any of them:** this block governs how *all* library resolution works.
A wrong move breaks the build for every consumer, and the Revapi baseline path is the specific thing
that must keep working. Verify against CI, not only locally.

## The discriminating question (superseded — retained for the record)

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

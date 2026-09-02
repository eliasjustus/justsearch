---
title: "Static-analysis and concurrency conventions: JSpecify + NullAway pilot, @GuardedBy discipline with a lock-order inventory and a JCStress pilot on NativeSessionHandle, internal-package encapsulation rule"
type: tempdocs
status: CHARTERED (2026-09-02) — not started; conventions DECIDED below (fable, 2026-09-02) — implement, do not re-litigate
created: 2026-09-02
updated: 2026-09-02
lane: 887 L11
model: opus (takeover)
parent: 887-improvement-landscape-register
related:
  - 398-ort-concurrency-invariant-regression-gate   # parked ~2026-04 "until NativeSessionHandle is next touched" — touched 3× since
  - 386 / 402 / 819 / 843 / 862                    # the five one-off race fixes
  - 638-dead-code-identification-sweep              # ArchUnit visibility reads exist here
  - 19-module-architecture (docs/explanation)        # dependency-direction enforcement
  - 888-ci-enforcement-closure-tier0                 # PMD/SpotBugs wiring — this lane assumes 888 item 2 landed
---

# 900 — Static-analysis and concurrency conventions

## Briefing for the agent picking this up

Fresh start. Read this file and 887 Appendix A7 §7.2/7.3 + A8 §8.1. Work in a worktree.
Error Prone is already applied to every Java module (`build-logic/.../ErrorProneConventionsPlugin.kt`),
which is the hook NullAway plugs into. ArchUnit tests live in `modules/app-launcher/src/test/`
(`LayeringEnforcementTest`, `UnreferencedCodeTest`). Java is deliberately not auto-formatted
(729) — keep diffs to annotations and the pilot module. Four PRs. The conventions below were
decided by the orchestrating (fable) session; if implementation shows one is wrong, write why in
§Status and ask — do not silently pick another.

## Conventions (decided)

- **Nullness:** JSpecify (`org.jspecify:jspecify`) annotations; packages opt in with
  `@NullMarked` in `package-info.java`; NullAway runs via Error Prone with
  `-XepOpt:NullAway:AnnotatedPackages=io.justsearch` and **only `@NullMarked` packages are
  checked** (`OnlyNullMarked=true`), so adoption is per-package and never a big-bang. Pilot
  module: `modules/ort-common` (leaf, small, concurrency-sensitive — pairs with the JCStress
  pilot). Second: `modules/core`. NullAway severity: error in CI, warning locally until the
  pilot is clean.
- **Concurrency:** every field read or written under a lock carries `@GuardedBy("<lock>")`
  (jcip annotations already in the catalog); every class with a lock has a one-line lock-order
  comment or `@ThreadSafe`/`@NotThreadSafe`. Error Prone's `GuardedBy` checker promoted to
  error. A **lock-order inventory** doc (`docs/reference/concurrency-lock-order.md`) lists every
  lock that can be held while acquiring another, per module, with the ordering rule. JCStress
  pilot on `NativeSessionHandle`'s state machine (398's parked gate), off the default graph like
  PIT, runnable on demand.
- **Encapsulation:** no JPMS (module descriptors would fight the sidecar classpath and AOT cache
  work in 269; decided against). Instead: an `io.justsearch.<module>.internal..` package
  convention plus an ArchUnit rule "no class outside module M accesses `M..internal..`", added
  to `LayeringEnforcementTest` with an allowlist baseline that ratchets (pattern:
  `gates/module-deps`). Existing classes are **not** moved in this lane; the rule bites on new
  internal packages and the pilot module's reorganisation only.

## Scope

1. NullAway wiring in `build-logic` + JSpecify dependency + `@NullMarked` on `ort-common`
   packages; fix findings (real bugs get a test each); document the opt-in step in
   `docs/reference/contributing/agent-guide.md` (static analysis section).
2. `@GuardedBy` pass over the five race-fixed classes (`SpladeEncoder`, the index write-path
   coordinator, `EmbeddingFingerprint*` boot path, the streaming producer, the observation
   shard writer) + `NativeSessionHandle`; Error Prone `GuardedBy` to error; lock-order inventory.
3. JCStress pilot: `modules/ort-common` `jcstressTest` source set (Gradle plugin), two tests
   for the create/close/retry races 398 §0 names; run once, record the result table in §Status.
4. ArchUnit internal-package rule + baseline + `ort-common` reorganised to `internal/` where
   its `NativeSessionHandleBuilderVisibilityTest` pin already implies it.

## Acceptance criteria

- `./gradlew.bat :modules:ort-common:compileJava` fails on an injected null-return in a
  `@NullMarked` package (prove the gate bites, then remove the injection).
- `./gradlew.bat build -x test` green across all modules with `GuardedBy` at error.
- `./gradlew.bat :modules:ort-common:jcstress` runs; results in §Status.
- `:modules:app-launcher:test` green with the new ArchUnit rule; baseline file committed under
  `gates/`-style path with a changeset.
- 398 updated: "gate built here" or "still not needed, because …".

## Constraints

- Do not annotate beyond the listed modules/classes — adoption is per-package, tracked by the
  count of `@NullMarked` packages (report it).
- Do not move classes across modules; do not touch `SearchExecutor`'s virtual-thread setup.
- Non-goals: PMD/SpotBugs wiring (888), fuzzing (894), Rust/Python lint (888 item 4).

## Status

(unstarted)

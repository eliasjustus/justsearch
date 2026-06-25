---
title: "373: Dependency Verification DX"
status: done
created: 2026-03-30
---

# 373: Dependency Verification DX

## Problem

When `gradle/verification-metadata.xml` is stale (missing
checksums for updated dependencies), the build fails with a
cryptic error that looks like a code problem:

```
Execution failed for task ':modules:indexer-worker:startScripts'.
> Dependency verification failed for configuration '...:runtimeClasspath'
  One artifact failed verification: junit-bom-5.11.3.module
```

The error mentions "verification failed" but doesn't clearly
indicate this is a configuration file issue, not a code issue.
The fix (`--write-verification-metadata sha256`) is buried in
Gradle documentation. A developer unfamiliar with the
verification system will spend time investigating their code
when the issue is a stale XML file.

In the 366 tempdoc, this blocked backend startup entirely and
was initially attributed to code changes before being identified
as an infrastructure issue.

## Scope

Investigate and research how to improve the developer experience
around dependency verification failures.

## Questions to resolve

1. Should the CI or pre-commit hooks automatically detect stale
   verification metadata?
2. Can the error message be made actionable (e.g., a Gradle
   plugin that catches verification failures and prints
   "Run: ./gradlew.bat --write-verification-metadata sha256 help")?
3. Should `resolveAndLockAll --write-locks` also update
   verification metadata, or should they be separate commands?
4. How often does verification metadata go stale — is it every
   dependency update, or only when the Gradle version changes?
5. What do other projects with dependency verification do to
   prevent this developer experience issue?

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Open-questions doc on Gradle dependency-verification DX (49 lines, 5 questions). Questions logged for future investigation; the tempdoc itself is terminal as a question-capture artifact.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.


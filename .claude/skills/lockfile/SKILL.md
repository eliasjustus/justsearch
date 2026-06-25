---
description: "TRIGGER when: editing build.gradle.kts files, adding/removing dependencies, changing plugin versions, or encountering 'dependency verification failed' errors. Loads the lockfile regeneration workflow."
user-invocable: true
---

# Lockfile & Dependency Hygiene

## When to Regenerate

Regenerate lockfiles after ANY of these changes:
- Adding or removing a dependency in `build.gradle.kts`
- Changing a dependency version (including plugin versions)
- Changing `dependencyLocking` configuration
- Updating Gradle wrapper version

## Regeneration Command

```bash
./gradlew.bat --no-configuration-cache resolveAndLockAll --write-locks
```

This updates `gradle.lockfile` and all `modules/*/gradle.lockfile` files.

## Verification

```bash
# Check for lock-skew (CI gate)
node scripts/ci/report-lock-skew.mjs
```

## Rules

- **Never hand-edit** lockfiles. Always regenerate via the command above.
- After regeneration, `git add` the changed lockfiles and commit alongside your dependency change.
- If you see `Dependency verification failed` in CI, the lockfiles are stale — regenerate.
- Lockfile changes should be in the same commit as the `build.gradle.kts` change that caused them.

---
description: "TRIGGER when: a build or test command fails, agent is asked to fix CI, or investigating a failed GitHub Actions run. Loads the CI failure symptom-to-fix decision tree."
user-invocable: true
---

# CI Triage

Decision tree for diagnosing CI and build failures. Match the symptom, follow the fix.

## Workflow Signals

Before interpreting a GitHub Actions failure, run:

```bash
node scripts/ci/workflow-signal-health.mjs --repo eliasjustus/justsearch --md
```

Use the computed failure class to route the public hosted `CI` fact lanes and the active manual specialty workflows: `docs-lint.yml`, `build-installer.yml`, `codeql.yml`, and `phase-3-observability-nightly.yml`.

- `release-blocking-failure` on `Build Installer` routes to tempdoc 374 / Production-Reality Verification.
- Public hosted `CI` failures route by fact-lane name first: public claims, license/notices, build, unit tests, secret scan, or DCO.
- `Phase 3 Observability Nightly` routes to tempdocs 400/404 only when the failing step reaches `jseval gate`; setup or dependency install failures are `infra-drift` / `workflow-assumption-drift`.
- `Docs Lint` failures route to the matching docs lint section below.
- `CodeQL` failures route to the reported query/path first; do not treat them as generic build failures.

After touching any `@Tag("stress")` subject or concurrency-sensitive code, run:

```bash
./gradlew.bat test -PincludeStress=true --tests "*Stress*"
```

## Symptom → Fix Map

### Test failure
```bash
./gradlew.bat :modules:<module>:test              # reproduce
# Read the test, understand its intent, fix YOUR code (not the test)
```

### PMD violation
```bash
./gradlew.bat pmdMain pmdTest                     # reproduce
# Fix the code to satisfy the rule. Do NOT add @SuppressWarnings.
# If rule is wrong for this case, check agent-guide §3.3 PMD table for exceptions.
```

### Spotless violation
```bash
./gradlew.bat spotlessCheck                       # reproduce
./gradlew.bat spotlessApply                       # fix
# Then re-commit the formatted files.
```

### Markdownlint failure
```bash
npx markdownlint "docs/**/*.md"                   # reproduce
# Common: MD040 (fenced code block language), MD013 (line length)
```

### Docs lint — link check failure
```bash
node scripts/docs/verify-canonical-doc-links.mjs  # reproduce
# Canonical docs must not reference tempdocs. Replace with source file references.
```

### Docs lint — runtime config matrix drift
```bash
node scripts/docs/verify-runtime-config-matrix.mjs  # reproduce
node scripts/docs/generate-runtime-config-matrix.mjs --write-doc docs/reference/configuration/runtime-config-ownership-matrix.md  # fix
```

### Docs lint — module dep graph drift
```bash
node scripts/architecture/module-deps.mjs --check-canonical  # reproduce
node scripts/architecture/module-deps.mjs --update-canonical  # fix
```

### Docs lint — llms.txt drift
```bash
node scripts/docs/llmstxt-generate.mjs --check    # reproduce
node scripts/docs/llmstxt-generate.mjs             # fix
```

### Docs lint — skills drift
```bash
node scripts/docs/skills-sync.mjs --check          # reproduce
node scripts/docs/skills-sync.mjs                   # fix
```

### Gradle lockfile failure
```bash
# Symptom: "Dependency verification failed" or lock-skew errors
./gradlew.bat --no-configuration-cache resolveAndLockAll --write-locks  # fix
```

## PMD Categories (Quick Reference)

| Category | Key Rules |
|----------|-----------|
| Best Practices | UnusedPrivateMethod, UnusedLocalVariable, AvoidReassigningParameters |
| Code Style | UnnecessaryImport, FieldNamingConventions, ClassNamingConventions |
| Design | CyclomaticComplexity, NPathComplexity, TooManyMethods, CouplingBetweenObjects |
| Error Prone | EmptyCatchBlock, AvoidDuplicateLiterals, CloseResource |
| Performance | InefficientStringBuffering, ConsecutiveLiteralAppends |

## Key Rule: Fix Root Causes

- **Never** comment out failing code, weaken assertions, delete tests, or add suppressions
- **Never** broaden catch clauses or remove validation to make errors disappear
- If a test fails after your changes, the test is probably right and your code is wrong

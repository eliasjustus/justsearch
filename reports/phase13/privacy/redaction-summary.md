---
title: Phase 13 Privacy & Redaction Summary
owner: Platform Team
last_updated: '2025-11-08'
---

# Phase 13 Privacy & Redaction Evidence

## Command log

- `./gradlew redactionLint`
  - Runs the dedicated `:modules:app-services:redactionLintTest` task (filtering only the redaction goldens) and copies the resulting HTML report to `build/reports/analysis/redaction-lint.html`.

## Artifacts

- `build/reports/analysis/redaction-lint.html` &mdash; HTML summary copied from `modules/app-services/build/reports/tests/redactionLint/index.html`.
- JUnit XML: `modules/app-services/build/test-results/redactionLint/TEST-LoggingRedactionGoldenTest.xml`.

## Coverage snapshot

- `modules/app-services/src/test/java/io/justsearch/app/services/observability/LoggingRedactionGoldenTest.java`
  - Exercises the Logback + Logstash encoder stack with `SensitiveString` wrappers to ensure secrets never leak (`super-secret` is redacted to `[REDACTED]`) and verifies schema compliance (`SSOT/schemas/telemetry/log.schema.json`).
- Normative guidance for `SensitiveString` / `RedactionLogger` lives in `docs/observability/logging.md#goldens` and `docs/policy/security-privacy.md:120-172`.
- The lint output (above) is now part of the verification chain via `./gradlew redactionLint` ⟶ `reports/phase13/privacy/redaction-summary.md`.

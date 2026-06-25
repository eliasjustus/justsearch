---
title: "AiInstallService Refactoring"
status: done
created: 2026-02-02
updated: 2026-02-02
origin: tempdoc 83 (section 1)
---

# 83a — AiInstallService Refactoring

Testability and structural improvements for `AiInstallService`.

**Key file:** `modules/ui/src/main/java/io/justsearch/ui/ai/install/AiInstallService.java`
**Test file:** `modules/ui/src/test/java/io/justsearch/ui/ai/install/AiInstallServiceTest.java`

---

## Investigation Summary

**AiInstallService.java** (~966 lines) handles v1 AI model installation: download, verify, persist state, apply settings, smoke test. Prior to this work it had **13 unit tests** covering only 3 private static utility methods via reflection. The service was never instantiated in tests.

**Root cause of low testability:** The constructor called `resolveHomeDir()` (a static method reading `EnvRegistry.HOME` → `PlatformPaths.resolveDataDir()` → `user.dir`), making it impossible to control the home directory in tests without system property manipulation.

## Item Disposition

| # | Item | Verdict | Rationale |
|---|------|---------|-----------|
| 1 | Path injection | **DONE** | Added 5-arg constructor with `Path aiHomeDir`. Existing constructors delegate. |
| 2 | StateStore interface | **DEFERRED** | Path injection + `@TempDir` gives ~80% of the value. StateStore adds new interface + 2 impls for marginal benefit over reading JSON file directly in tests. |
| 3 | Integration test | **DEFERRED** | AiInstallController is a trivial passthrough. Unit tests via path injection are higher value. |
| 4 | DownloadStrategy | **DROPPED** | OS process interaction can't be meaningfully unit tested. Code is isolated and stable. |
| 5 | PolicyValidator | **DROPPED** | 3 simple conditionals (5-15 lines each). Already testable via existing integration tests. |
| 6 | Service split | **DROPPED** | v1 install is being superseded by v2 pack import. Restructuring a sunset path is negative ROI. |

## Changes Made

### Constructor (AiInstallService.java)

Added a 5-arg constructor accepting `Path aiHomeDir` as the primary constructor. Existing 4-arg and 3-arg constructors delegate to it with `resolveHomeDir()`. Zero production call site changes.

### Tests (AiInstallServiceTest.java)

Added `ServiceTests` nested class with 6 tests using `@TempDir` via path injection:

| Test | What it verifies |
|------|------------------|
| `initialStatusIsIdle` | Fresh service has state=idle, phase=idle |
| `termsRequiredThrows` | `startInstall(false)` → 400 TERMS_REQUIRED |
| `cancelSetsRequestedFlag` | `cancel()` sets cancelRequested=true |
| `persistenceRoundTrip` | Pre-written JSON loads correctly; cancelRequested reset on load |
| `corruptedStateFileIgnored` | Invalid JSON in state file → service starts idle |
| `manifestLoadsFromClasspath` | Bundled model registry loads with non-empty assets |

Total test count: 19 (13 existing + 6 new).

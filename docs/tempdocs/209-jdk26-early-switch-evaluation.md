---
title: JDK 26 Early Switch Evaluation
type: tempdocs
status: done
created: 2026-02-17
---

> NOTE: Noncanonical working note. Verify against canonical docs + code before acting.

# Goal

Decide whether JustSearch should switch its default Java baseline from JDK 25 to JDK 26 early.

# Current decision (as of 2026-02-17)

Do not switch the default baseline yet.
Start a JDK 26 canary lane now, and re-evaluate after JDK 26 GA.

## Why

1. JDK 26 is pre-GA on 2026-02-17.
2. Gradle runtime support for Java 26 is not yet official; safest path is Gradle on 25 with toolchains for 26.
3. Repo is currently hard-pinned to Java 25 in multiple places:
   - `build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt`
   - `.github/workflows/ci.yml` (and related workflow files with `JAVA_VERSION: "25"`)
4. Test stack still relies on Byte Buddy experimental mode with older transitive versions:
   - `modules/app-launcher/build.gradle.kts`
   - `modules/indexer-worker/build.gradle.kts`
   - `modules/ui/build.gradle.kts`
   - `modules/app-ai/build.gradle.kts`
5. Packaging/runtime-image tasks are wired to 25 toolchains / host Java home and need controlled migration:
   - `modules/ui/build.gradle.kts`
   - `build.gradle.kts`

# Scheduled re-evaluation checkpoint

**Primary checkpoint:** **2026-03-24 10:00:00 America/New_York**  
**UTC equivalent:** **2026-03-24T14:00:00Z**

Reason: one week after JDK 26 GA target date (2026-03-17), allowing initial ecosystem stabilization and first patch release signals.

# Re-evaluation gate criteria

1. JDK 26 canary CI lane is green for:
   - compile + unit + integration + system tests
   - packaging tasks used in release flow
2. Mockito/Byte Buddy path is updated so `-Dnet.bytebuddy.experimental=true` is removable.
3. Perf regression checks (Claim A/B + perf suite) show no unacceptable regressions vs JDK 25.
4. Native/runtime checks pass for:
   - FFM paths (`ai-bridge`)
   - JNI-heavy dependencies (ONNX Runtime, SQLite JDBC)
5. jlink/runtime-image tasks are confirmed to use intended toolchain/version, not accidental host-JDK fallback.

# Proposed next actions before checkpoint

1. Add non-blocking JDK 26 canary workflow leg (Gradle runtime on 25, toolchain compile/test on 26).
2. Upgrade Mockito/Byte Buddy compatibility path.
3. Make toolchain version explicit/configurable for jlink-related tasks.
4. Record 25 vs 26 benchmark comparisons in this tempdoc before 2026-03-24.

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 90 days at audit time.


---
title: "236: Dependency & Infrastructure Update Audit (Round 2)"
type: tempdoc
status: done
created: 2026-02-26
updated: 2026-03-09
---

# 236: Dependency & Infrastructure Update Audit (Round 2)

**Status:** complete — all version bumps implemented and verified on branch `worktree-dep-update-236`.
**Scope:** Full audit of all pinned dependencies and infrastructure components: JVM backend,
frontend, Gradle plugins, Rust/Cargo. Does not include llama-server (already upgraded to b8157
on main).

---

## 1. Approach and prioritization

### 1.1 Tiers

| Tier | Criteria | Action |
|------|----------|--------|
| **Critical** | Active CVE or discontinued with unpatched vulnerability | Fix immediately |
| **High** | Security-motivated patch debt, or large stability gap | Prioritize |
| **Medium** | Meaningful improvement available, manageable API surface | Batch and schedule |
| **Low** | Additive or tooling-only; no functional impact | Opportunistic |
| **Deferred** | Requires multi-library coordination or major API migration | Plan separately |
| **Done** | Already at target or current on main | No action |

### 1.2 Recommended execution order

1. **lz4-java fork migration** — two CVEs, purely transitive, 2-line change
2. **PDFBox, commons-io, Handlebars** — transitive CVE flags, low-effort bumps
3. **SQLite JDBC** — security patches, independent version bump
4. **Netty + gRPC** — upgrade together (stay on 4.1.x; 4.2.x is a separate evaluation)
5. **Shadow plugin + rmcp/reqwest removal** — orphaned catalog entry and unused Rust deps
6. **Lucene 10.4.0** — codec migration, 10-15% query perf improvement
7. **ONNX Runtime + DJL Tokenizers** — upgrade together
8. **Logback + SLF4J** — upgrade together; audit `%property{}` first
9. **Jackson** — 2.18.1 → 2.18.6 (stay on 2.18.x patch line)
10. **Gradle 9.4.0** — wrapper update (Kotlin already at 2.3.10)
11. **Build tooling batch** — PMD, Spotless, Error Prone, FindSecBugs
12. **Test library batch** — JUnit 5, Mockito, AssertJ
13. **Commons + resolution-pin batch** — commons-lang3, text, codec, Gson, ASM, JNA, Jinjava
14. **Frontend batch** — TypeScript, Tailwind, Framer Motion, lucide-react, etc.
15. **Rust crates** — rmcp, reqwest

---

## 2. Inventory and status

### Legend

- **Current (main)**: version currently in `libs.versions.toml` or `build.gradle.kts` on main
- **Target**: recommended upgrade target for this round
- **Done**: already at target on main, or no action needed

### 2.1 JVM — search and transport

| Component | Current (main) | Target | Gap | Tier |
|-----------|---------------|--------|-----|------|
| Apache Lucene | 10.3.1 | **10.4.0** | 1 minor | Medium |
| Apache Tika | 3.2.3 | 3.2.3 | — | **Done** |
| gRPC Java | 1.78.0 | **1.79.0** | 1 minor | High (pair w/ Netty) |
| Protobuf Java | 4.33.5 | 4.33.5 | — | **Done** |
| Netty | 4.1.110.Final | **4.1.131.Final** | 21 patches | High |
| Javalin | 6.3.0 | **6.7.0** | 4 patches | Low |
| Jackson Databind | 2.18.1 | **2.18.6** | 5 patches | Low |
| networknt json-schema-validator | 1.4.0 | — | — | Deferred |

Note: Netty 4.2.x (4.2.10.Final) is GA but is a major line change. Stay on 4.1.x for this
round. Jackson 2.21.1 exists but is a minor bump; stay on 2.18.x patch line. Javalin 7.0.1
is GA but requires javax→jakarta; defer.

### 2.2 JVM — AI / ML

| Component | Current (main) | Target | Gap | Tier |
|-----------|---------------|--------|-----|------|
| ONNX Runtime Java | 1.19.2 | **1.24.3** | 5 minor | Medium |
| DJL Tokenizers | 0.30.0 | **0.36.0** | 6 minor | Medium |

### 2.3 JVM — storage and utility

| Component | Current (main) | Target | Gap | Tier |
|-----------|---------------|--------|-----|------|
| SQLite JDBC | 3.45.1.0 | **3.51.2.0** | 6 SQLite versions | High |
| lz4-java | org.lz4:1.8.0 | **at.yawk.lz4:1.10.4** | 2 CVEs + discontinued | **Critical** |
| PDFBox | 3.0.5 (pinned) | **3.0.6** | 1 patch | High (Snyk flag) |
| commons-io | 2.20.0 (pinned) | **2.21.0** | 1 minor | High (CVE flag) |
| Guava | 33.5.0-jre | 33.5.0-jre | — | **Done** |
| directory-watcher | 0.19.1 | 0.19.1 | — | **Done** |
| OSHI Core | 6.6.0 | **6.10.0** | 4 minor | Low |
| commons-math3 | 3.6.1 | 3.6.1 | abandoned but 1 call site | **Leave** |
| Jinjava | 2.7.1 | **2.7.6** | 5 patches | Low |
| Handlebars | 4.4.0 | **4.5.0** | 1 minor | High (CVE) |
| commonmark | 0.27.1 | 0.27.1 | — | **Done** |
| pcollections | 4.0.2 (pinned) | — | — | Deferred |
| JNA | 5.14.0 (pinned) | **5.18.1** | 4 minor | Low |

### 2.4 JVM — logging

| Component | Current (main) | Target | Gap | Tier |
|-----------|---------------|--------|-----|------|
| SLF4J | 2.0.16 | **2.0.17** | 1 patch | Low |
| Logback Classic | 1.5.6 | **1.5.32** | 26 patches | Medium |
| Logstash Logback Encoder | 7.4 | — | — | Deferred (needs Jackson 3) |

### 2.5 JVM — observability

| Component | Current (main) | Target | Gap | Tier |
|-----------|---------------|--------|-----|------|
| OpenTelemetry Java | 1.58.0 | **1.60.1** | 2 minor | Low |

### 2.6 JVM — resolution-pinned deps (build.gradle.kts)

| Component | Current (pinned) | Target | Gap | Tier |
|-----------|-----------------|--------|-----|------|
| commons-lang3 | 3.18.0 | **3.20.0** | 2 minor | Low |
| commons-codec | 1.19.0 | **1.21.0** | 2 minor | Low |
| commons-text | 1.12.0 | **1.15.0** | 3 minor | Low |
| Gson | 2.13.1 | **2.13.2** | 1 patch | Low |
| ASM | 9.9 | **9.9.1** | 1 patch | Low |
| error_prone_annotations | 2.44.0 | **2.47.0** | 3 minor | Low |

### 2.7 JVM — build tooling

| Component | Current (main) | Target | Gap | Tier |
|-----------|---------------|--------|-----|------|
| Gradle | 9.3.1 | **9.4.0** | 1 minor | Medium |
| Kotlin JVM plugin | 2.3.10 | 2.3.10 | — | **Done** |
| PMD | 7.16.0 | **7.22.0** | 6 minor | Low |
| Spotless | 8.2.1 | **8.3.0** | 1 minor | Low |
| Error Prone | 2.40.0 | **2.47.0** | 7 minor | Low |
| JaCoCo | 0.8.14 | 0.8.14 | — | **Done** |
| SpotBugs core | 4.9.3 | **4.9.8** | 5 patches | Low |
| SpotBugs plugin | 6.4.8 | 6.4.8 | — | **Done** |
| FindSecBugs | 1.13.0 | **1.14.0** | 1 minor | Low |
| Shadow plugin | `johnrengelman` 8.1.1 | **Remove** | orphaned entry | High |
| node-gradle | 7.1.0 | 7.1.0 | — | **Done** |
| protobuf-gradle | 0.9.6 | 0.9.6 | — | **Done** |
| DAGP | 3.6.1 | 3.6.1 | — | **Done** |
| licenseReport | 3.1.1 | 3.1.1 | — | **Done** |
| google-java-format | 1.25.2 | — | — | Deferred |

Note: Shadow plugin is orphaned — declared in catalog but never applied. Remove.

### 2.8 JVM — testing

| Component | Current (main) | Target | Gap | Tier |
|-----------|---------------|--------|-----|------|
| JUnit 5 | 5.12.2 | **5.14.3** | 2 minor | Low |
| JUnit Platform | 1.12.2 | **1.14.3** | 2 minor | Low |
| Mockito | 5.14.0 | **5.22.0** | 8 minor | Low |
| ArchUnit | 1.4.1 | 1.4.1 | — | **Done** |
| AssertJ | 3.26.0 | **3.27.7** | 1 minor | Low |

Note: JUnit 6.0.3 exists (requires Java 17 min). Defer to separate evaluation.

### 2.9 Frontend (npm)

| Component | Current (main) | Target | Gap | Tier |
|-----------|---------------|--------|-----|------|
| React + react-dom | ^19.2.4 | — | — | **Done** |
| TypeScript | ^5.8.4 | **^5.9.3** | 1 minor | Medium |
| Vite | ^7.3.1 | — | — | **Done** |
| Vitest | ^4.0.18 | — | — | **Done** |
| Playwright | ^1.58.1 | **^1.58.2** | 1 patch | Low |
| Tailwind CSS | ^4.1.17 | **^4.2.1** | 1 minor | Low |
| Zustand | ^5.0.11 | — | — | **Done** |
| Zod | ^4.3.6 | — | — | **Done** |
| Framer Motion | ^12.30.0 | **^12.35.1** | 5 patches | Low |
| ESLint | ^9.39.3 | **^9.39.4** | 1 patch | Low |
| @eslint/js | ^9.39.3 | **^9.39.4** | 1 patch | Low |
| lucide-react | ^0.563.0 | **^0.577.0** | 14 patches | Low |
| @tauri-apps/api | ^2.9.1 | **^2.10.1** | 1 minor | Low |
| Lingui | ^5.9.1 | **^5.9.2** | 1 patch | Low |
| Node.js (.nvmrc) | 24.12.0 | **24.14.0** | 2 patches | Low |

Note: ESLint 10.x removes eslintrc format. Stay on 9.x; evaluate 10.x separately.
TypeScript 5.9 has an `ArrayBuffer` type hierarchy change — audit before upgrading.

### 2.10 Rust / Cargo

| Component | Cargo.toml range | Latest | Gap | Tier |
|-----------|-----------------|--------|-----|------|
| tauri + plugins | `"2"` | current | — | **Done** |
| rmcp | `"0.1"` (0.1.5) | **Remove** | unused — zero imports | High |
| reqwest | `"0.12"` (0.12.28) | **Remove** | unused — zero imports | High |
| tokio, serde, uuid, open | current | — | — | **Done** |

Note: rmcp and reqwest have zero `use` imports in any `.rs` file. They were added
speculatively for MCP server support that was never implemented. Removing eliminates
dual-reqwest bloat in the binary. tokio is still needed by tauri.

### 2.11 GitHub Actions

All workflows already standardized to `upload-artifact@v7`. **Done.**

---

## 3. Critical — act immediately

### 3.1 lz4-java: two CVEs, library discontinued → fork migration

**CVE-2025-12183** (CVSS HIGH) — out-of-bounds read in JNI fast decompressor.
**CVE-2025-66566** (CVSS 8.2 HIGH) — info leak via buffer reuse in safe decompressor.

Original `org.lz4:lz4-java` is **discontinued**. Fork: `at.yawk.lz4:lz4-java` (same
Java package names, true drop-in). No direct LZ4 call sites in JustSearch (purely
transitive via Lucene).

**Change:** Two lines in `gradle/libs.versions.toml`:
```toml
lz4 = "1.10.4"
lz4-java = { module = "at.yawk.lz4:lz4-java", version.ref = "lz4" }
```

---

## 4. High priority

### 4.1 PDFBox 3.0.6

Snyk flags 3.0.5. 18 bug fixes including resource leaks and LZWFilter crash.
Update resolution strategy in `build.gradle.kts`: `useVersion("3.0.6")`.

### 4.2 commons-io 2.21.0

Transitive CVE-2025-48924 flag. Update resolution strategy: `useVersion("2.21.0")`.
(Note: 2.22.0 does not exist on Maven Central; corrected to 2.21.0.)

### 4.3 Handlebars 4.5.0

Transitive CVE-2025-48924 via commons-lang3. Bump in `libs.versions.toml`.

### 4.4 SQLite JDBC 3.51.2.0

Security fixes: integer overflow in `concat_ws()` (3.49.1), memory corruption in
aggregates (3.50.2). Bump `sqlite` in `libs.versions.toml`.

### 4.5 Netty + gRPC

Netty 4.1.110 → 4.1.131 (21 patches of bug/security fixes); gRPC 1.78.0 → 1.79.0.
Upgrade together — gRPC 1.79.0 targets Netty 4.1.130.

### 4.6 Shadow plugin — remove orphaned catalog entry

`com.github.johnrengelman.shadow` 8.1.1 is declared in the version catalog but **never
applied** in any `build.gradle.kts`. The two `shadowJar` references in `indexer-worker` and
`system-tests` are comments only. **Action: delete the catalog entry.**

### 4.7 rmcp + reqwest — remove unused Rust dependencies

`rmcp` and `reqwest` are declared in `modules/shell/src-tauri/Cargo.toml` with features
(`server`, `transport-io`, `macros`, `json`) but **zero `use` imports exist in any `.rs` file**.
These were likely added for planned MCP server support in the Tauri shell that was never
implemented. Removing them eliminates dual-reqwest bloat (both 0.12.28 and 0.13.2 in
`Cargo.lock`) and ~3 crate trees from the binary.
**Action: remove from Cargo.toml and regenerate Cargo.lock.**

---

## 5. Medium priority

### 5.1 Lucene 10.4.0

Codec migration: `Lucene103Codec` → `Lucene104Codec`,
`Lucene99HnswScalarQuantizedVectorsFormat` → `Lucene104HnswScalarQuantizedVectorsFormat`.
10-15% query perf improvement. Check `SortField.reverse` subclasses first.

### 5.2 ONNX Runtime 1.24.3 + DJL Tokenizers 0.36.0

Upgrade together. No breaking API changes in either.

### 5.3 Gradle 9.4.0

Wrapper update. Kotlin 2.3.10 already compatible.

### 5.4 Logback 1.5.32 + SLF4J 2.0.17

**Breaking change in 1.5.13:** `%property{key}` patterns no longer work. Audit logback XML
first. Update SLF4J resolution pin in `build.gradle.kts` alongside.

### 5.5 TypeScript 5.9.3

**Breaking:** `ArrayBuffer` no longer structural supertype of `TypedArray` variants.
Audit for `ArrayBuffer` usage before upgrading.

### 5.6 ~~rmcp + reqwest~~ → moved to §4.7 (remove, not upgrade)

---

## 6. Low priority — batch commits

### 6.1 Build tooling

PMD → 7.22.0, Spotless → 8.3.0, Error Prone → 2.47.0, SpotBugs core → 4.9.8,
FindSecBugs → 1.14.0.

### 6.2 Test libraries

JUnit 5 → 5.14.3 (+ Platform 1.14.3), Mockito → 5.22.0, AssertJ → 3.27.7, OSHI → 6.10.0.

### 6.3 Jackson 2.18.6

Update both `libs.versions.toml` and resolution strategy in `build.gradle.kts`.

### 6.4 OpenTelemetry 1.60.1

Check for Zipkin/Jaeger exporter usage first (both deprecated from OTel Java).

### 6.5 Javalin 6.7.0

No breaking changes within 6.x.

### 6.6 Commons + resolution-pin batch

In `build.gradle.kts`: commons-lang3 → 3.20.0, commons-codec → 1.21.0,
error_prone_annotations → 2.47.0, Gson → 2.13.2, ASM → 9.9.1, JNA → 5.18.1,
SLF4J → 2.0.17 (if not done with Logback).

In `libs.versions.toml`: commons-text → 1.15.0, commons-codec → 1.21.0, Jinjava → 2.7.6.

### 6.7 Frontend batch

Tailwind → ^4.2.1, Framer Motion → ^12.35.1, lucide-react → ^0.577.0 (grep `Flip*` icons
first), Tauri API → ^2.10.1, Lingui → ^5.9.2, Playwright → ^1.58.2,
ESLint + @eslint/js → ^9.39.4, Node.js .nvmrc → 24.14.0.

---

## 7. Deferred / complex migrations

| Item | Blocker |
|------|---------|
| Netty 4.2.x | Major line change; separate evaluation |
| Jackson 2.21+ / 3.x | Minor+ version bump; evaluate separately |
| Javalin 7.0 (Jetty 12) | javax → jakarta audit |
| JUnit 6.x | Major version; requires Java 17 min |
| ESLint 10.x | Removes eslintrc; migration required |
| Logstash encoder 9.0 | Requires Jackson 3 |
| networknt json-schema-validator | Breaking API at every boundary; 2 call sites work fine at 1.4.0 |
| google-java-format 1.34.1 | Reformats all Java; must be isolated commit |
| commons-math3 replacement | 1 call site (`Percentile` in benchmarks); not worth migrating |
| pcollections 5.0.0 | Major version audit |

---

## 8. Components at latest — no action

**JVM:** Tika 3.2.3, Protobuf 4.33.5, Guava 33.5.0-jre, JaCoCo 0.8.14, ArchUnit 1.4.1,
directory-watcher 0.19.1, commons-compress 1.28.0, commons-logging 1.3.5, commonmark 0.27.1,
Kotlin 2.3.10, SpotBugs plugin 6.4.8, node-gradle 7.1.0, protobuf-gradle 0.9.6,
licenseReport 3.1.1, DAGP 3.6.1.

**Frontend:** React 19.2.4, Vite 7.3.1, Vitest 4.0.18, Zustand 5.0.11, Zod 4.3.6.

**Rust:** tauri + all plugins (within 2.x), tokio, serde, uuid, open.

**GitHub Actions:** All at current versions. upload-artifact @v7.

**Native:** llama-server b8157 (already upgraded on main).

---

## 9. Pre-flight research findings (2026-03-09)

All audit-before-upgrade checks have been completed. Results:

| Check | Result | Impact |
|-------|--------|--------|
| LZ4 direct call sites | **None** | Drop-in fork swap is safe |
| `extends SortField` | **None** | Lucene 10.4 `final` change is safe |
| `%property{}` in logback XML | **None** | Logback 1.5.32 is safe |
| `ArrayBuffer` in frontend TS/TSX | **None** | TypeScript 5.9 is safe |
| `use rmcp` / `use reqwest` in Rust | **None** | Remove, don't upgrade |
| `Flip*` lucide icons | **None** | lucide-react bump is safe |
| Zipkin/Jaeger OTel exporters | **None** | OTel 1.60.1 is safe |
| `javax.servlet` usage | **None** | Javalin 7.0 would be low-effort (deferred) |
| Shadow plugin applied anywhere | **No** | Orphaned catalog entry — remove |
| Jackson resolution pin in build.gradle.kts | **None** | Only `libs.versions.toml` needs updating |

### Lucene 10.4 codec migration — verified API (javadoc-confirmed)

**Key finding:** The migration is NOT a uniform rename. Only the quantized format was replaced:

| Old (10.3.1) | New (10.4.0) | Status |
|--------------|-------------|--------|
| `Lucene103Codec` | `Lucene104Codec` | Renamed (new package `lucene104`) |
| `Lucene99HnswScalarQuantizedVectorsFormat` | `Lucene104HnswScalarQuantizedVectorsFormat` | **Replaced** — old class removed |
| `Lucene99HnswVectorsFormat` | `Lucene99HnswVectorsFormat` | **Unchanged** — stays in `lucene99` |

Constructor `(int maxConn, int beamWidth)` is preserved on the new quantized format.

**Files to change:**

- `JustSearchCodec.java`:
  - `Lucene103Codec` → `Lucene104Codec` (import + constructor)
  - `Lucene99HnswScalarQuantizedVectorsFormat` → `Lucene104HnswScalarQuantizedVectorsFormat`
  - `Lucene99HnswVectorsFormat` — **keep as-is**
- `ComponentsFactoryTest.java`:
  - `Lucene99HnswScalarQuantizedVectorsFormat` assertions → `Lucene104...`
  - `Lucene99HnswVectorsFormat` assertions — **keep as-is**
- `VectorQuantizationGate.java`:
  - `Lucene99HnswScalarQuantizedVectorsFormat` → `Lucene104...`
  - `Lucene99HnswVectorsFormat` — **keep as-is**
- `LuceneRuntimeUtils.java`: string `_Lucene103` → `_Lucene104` (file extension match)
- `VectorFormatDetector.java`: comments only — update for accuracy

### Decisions made

- **rmcp + reqwest**: Remove (zero usage) instead of upgrading (was Medium, now High)
- **Shadow plugin**: Remove orphaned entry instead of migrating plugin ID
- **commons-math3**: Leave at 3.6.1 — 1 call site (`Percentile`) in benchmarks only
- **json-schema-validator**: Leave at 1.4.0 — 2 call sites work fine, breaking API at every boundary

### Gaps and risks identified

**Dual/inconsistent declarations (must update both):**

1. **commons-codec**: `libs.versions.toml` at `1.17.0`, `build.gradle.kts` resolution pin at
   `1.19.0`. Both must go to `1.21.0`.
2. **Module-level Jackson pins**: 8 modules with 37 total `useVersion("2.18.1")` calls:
   `ai-worker` (5), `app-api-tck` (5), `app-launcher` (5), `app-services` (5),
   `indexer-worker` (5), `indexing` (5), `system-tests` (5), `app-api` (2).
   Must update alongside `libs.versions.toml`.
3. **Module-level SLF4J pins**: 18 modules with `useVersion("2.0.16")` calls:
   `test-support`, `indexing`, `search`, `telemetry`, `infra-core`, `reranker`,
   `system-tests`, `app-api-tck`, `ui`, `app-api`, `adapters-lucene`,
   `indexer-worker`, `ai-bridge`, `ai-worker`, `app-launcher`, `core`,
   `app-services`, `app-search`. Must update alongside root-level pin.
4. **logback-core hardcoded**: 3 declarations — `app-observability` (impl),
   `indexing` (impl), `telemetry` (testImpl). All at `1.5.6`. Must update to `1.5.32`.
5. **JUnit params hardcoded**: 6 declarations across `ui`, `indexer-worker`,
   `adapters-lucene`, `ai-bridge`, `system-tests` (×2). All at `5.12.2`.
6. **OTel hardcoded**: `telemetry` module hardcodes `opentelemetry-sdk-common:1.58.0` and
   `opentelemetry-sdk-trace:1.58.0`. Must update alongside catalog.

**Missing from audit (newly discovered):**

7. **lightgbm4j 4.6.0-2** (`app-services`): Not in catalog. Check if newer version exists.
8. **PDFBox 3.0.3** (`app-services`): Hardcoded at 3.0.3 while resolution pin is 3.0.5.
   Stale — the resolution strategy overrides it, but the declaration is misleading.
9. **rrd4j 3.10** (`telemetry`): Not in catalog. Check if newer version exists.
10. **jetty-jakarta-servlet-api 5.0.2** (`ui`): Not in catalog. Niche — leave.
11. **kotlin-stdlib 2.2.21** (`ui`): Hardcoded, behind Kotlin plugin 2.3.10. Should use
    catalog reference or let Gradle align automatically.
11b. **grpc-api 1.78.0** (`ui`): Hardcoded, must update alongside gRPC catalog bump to 1.79.0.
12. **antlr4-runtime 4.9.3** (`app-api`, Revapi classpath): Not in catalog. Niche — leave.
13. **Gradle settings plugins**: `com.gradle.develocity` 4.3.2 and
    `org.gradle.toolchains.foojay-resolver` 1.0.0 in `settings.gradle.kts`. Not tracked.
14. **@tailwindcss/postcss**: Listed at `^4.1.18` in `package.json` but not tracked. Should
    be bumped alongside Tailwind CSS to `^4.2.1`.

**Process risks:**

15. **PMD version is hardcoded** in `build.gradle.kts` (`toolVersion = "7.16.0"`), not in
    `libs.versions.toml`. Must update the hardcoded string.
16. **Verification-metadata.xml will be massive**: Every version bump adds new SHA-256 entries.
    Regeneration is a single command but produces a large diff. Should be its own commit.
17. **Lockfile regeneration order**: Gradle lockfiles must be regenerated AFTER all version
    changes but BEFORE `verification-metadata.xml`. Wrong order causes build failures.
18. **ONNX Runtime native path**: ORT isolation uses `onnxruntime.native.path` system property
    (standard ORT mechanism since 1.14). Verified: not classloader-based. **Low risk** for
    1.24.3 upgrade — but test embedding after upgrade to confirm.
19. **checker-qual pin**: `build.gradle.kts` pins at `3.49.5`. Verified: Error Prone 2.47.0
    does NOT depend on checker-qual (comes via Guava instead). Pin is independent of EP
    upgrade. **No action needed** unless Guava is bumped.
20. **errorpronePlugin**: `net.ltgt.gradle:gradle-errorprone-plugin` at 4.0.1 in
    `libs.versions.toml`. This plugin just wires EP into Gradle — typically version-agnostic.
    The ErrorProneConventionsPlugin reads EP version from catalog, no hardcoded version.
    **Low risk**, but verify build compiles after EP bump.
21. **build-logic dependencies**: `build-logic/build.gradle.kts` line 61-63 pulls Spotless
    and SpotBugs plugin JARs using `libs.versions.spotless` and `libs.versions.spotbugsPlugin`.
    These resolve from the shared catalog, so catalog updates propagate automatically. **No
    extra action needed.**

**Discovered during post-implementation sweep (2026-03-10):**

22. **reports/build.gradle.kts**: Had its own resolution pins for Jackson (2.18.1), SLF4J
    (2.0.16), Gson (2.13.1) — separate from root build.gradle.kts. Missed in original audit.
    **Fixed** to 2.18.6, 2.0.17, 2.13.2.
23. **CI workflows NODE_VERSION**: 12 workflow files had `NODE_VERSION: "24.12.0"` while
    SSOT/tools/.nvmrc was updated to 24.14.0. **Fixed** — all 12 files aligned.
24. **Settings plugins**: `com.gradle.develocity` 4.3.2 and `foojay-resolver` 1.0.0 — both
    confirmed at latest. No action needed.
25. **GitHub Actions**: actions/checkout@v6, actions/setup-java@v5, actions/setup-node@v6,
    actions/upload-artifact@v7 — all at latest major versions. No action needed.
26. **third_party/llama.cpp**: Contains Android example build files with hardcoded AndroidX
    versions (compose-bom 2023.08.00, etc.). Out of scope — third-party vendored code.

---

## 10. Implementation checklists

### Tier: Critical

- [x] **lz4-java**: Confirm no LZ4 call sites
- [x] **lz4-java**: Change group to `at.yawk.lz4`, version to `1.10.4` in `libs.versions.toml`
- [x] **lz4-java**: Regenerate lockfiles, run `./gradlew.bat test` — all pass

### Tier: High

- [x] **PDFBox**: Update resolution strategy in `build.gradle.kts` to `3.0.6`
- [x] **commons-io**: Update resolution strategy to `2.21.0` (2.22.0 doesn't exist; corrected)
- [x] **Handlebars**: Bump to `4.5.0` in `libs.versions.toml`
- [x] **SQLite JDBC**: Bump `sqlite = "3.51.2.0"` in `libs.versions.toml`
- [x] **Netty + gRPC**: Bump `grpc = "1.79.0"`, `netty = "4.1.131.Final"`, + grpc-api 1.79.0 in ui module
- [x] **Shadow plugin**: Remove orphaned catalog entry from `libs.versions.toml`
- [x] **rmcp + reqwest**: Remove from `Cargo.toml`; regenerate `Cargo.lock`; verified zero usage in .rs files

### Tier: Medium

- [x] **Lucene 10.4.0**: Codec migration + version bump (partial rename verified)
- [x] **ONNX + DJL**: Bump `onnxruntime = "1.24.3"`, `djl-tokenizers = "0.36.0"`
- [x] **Gradle 9.4.0**: Update wrapper properties with SHA-256
- [x] **Logback + SLF4J**: Bump logback 1.5.32, SLF4J 2.0.17 (root + 18 module pins + 3 logback-core)
- [x] **TypeScript 5.9.3**: Bump in `package.json`

### Tier: Low (batch commits)

- [x] Build tooling: PMD 7.22.0, Spotless 8.3.0, Error Prone 2.47.0, SpotBugs 4.9.8, FindSecBugs 1.14.0
- [x] errorpronePlugin 4.0.1 works with Error Prone 2.47.0 (build passes)
- [x] Test libraries: JUnit 5.14.3 (+ 6 hardcoded junit-params), Mockito 5.22.0, AssertJ 3.27.7, OSHI 6.10.0
- [x] Jackson 2.18.6 (catalog + 37 module-level pins across 8 modules)
- [x] OpenTelemetry 1.60.1 (+ 2 hardcoded in telemetry module)
- [x] Javalin 6.7.0
- [x] Commons + pins: lang3 3.20.0, text 1.15.0, codec 1.21.0, Gson 2.13.2, ASM 9.9.1, JNA 5.18.1, Jinjava 2.7.6, error_prone_annotations 2.47.0
- [x] Frontend: Tailwind 4.2.1 + postcss 4.2.1, Framer Motion 12.35.1, lucide-react 0.577.0, Tauri API 2.10.1, Lingui 5.9.2, Playwright 1.58.2, ESLint 9.39.4, Node 24.14.0
- [x] lightgbm4j 4.6.0-2 and rrd4j 3.10 confirmed at latest — no action needed
- [x] kotlin-stdlib 2.2.21 → 2.3.10 in ui module (aligned with Kotlin plugin)
- [x] PDFBox 3.0.3 → 3.0.6 in app-services (aligned with resolution pin)

### Post-upgrade verification

- [x] Lockfiles and verification-metadata regenerated with each commit
- [x] Full JVM build passes (`./gradlew.bat assemble`)
- [x] Full JVM test suite passes (only pre-existing ArchUnit failure in UnreferencedCodeTest)
- [x] Frontend: `npm install` + `npm run typecheck` (pre-existing errors only) + `npm run test:unit:run` (182/182 pass)
- [x] Rust: rmcp/reqwest removal verified (zero imports); cargo build blocked by pre-existing missing resources/headless/ (gitignored build artifact)

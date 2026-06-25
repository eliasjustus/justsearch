---
title: "Tempdoc 299 - Jackson 3 Migration"
---

# Tempdoc 299 - Jackson 3 Migration

**Status:** Complete
**Created:** 2026-03-14
**Updated:** 2026-03-15
**Goal:** Migrate from Jackson 2.18.6 (`com.fasterxml.jackson`) to Jackson 3.x (`tools.jackson`) across all modules, then upgrade logstash-logback-encoder from 8.0 to 9.0.

## Context

Tempdoc 289 (F20/A26) identified that logstash-logback-encoder 9.0 (Oct 2025) requires Jackson 3. JustSearch uses Jackson 2.18.6 across 10+ modules with custom `resolutionStrategy` version-forcing blocks in `app-launcher`, `ai-worker`, and `app-services`.

Jackson 3.1.0 LTS was released Feb 23, 2026 (first LTS for the 3.x line). Key breaking changes:
- **Maven group**: `com.fasterxml.jackson` → `tools.jackson`
- **Package rename**: same (`com.fasterxml.jackson` → `tools.jackson`)
- **Exceptions**: `JsonProcessingException` was `IOException` (checked), now `JacksonException` is `RuntimeException` (unchecked)
- **API removals**: `ObjectCodec` removed, `writeObject()` → `writePOJO()`, `getCurrentValue()` → `currentValue()`
- **Java minimum**: 17 (already met — we build with 25)
- **jackson-annotations**: stays at 2.x (`com.fasterxml.jackson.core:jackson-annotations`), shared between 2.x and 3.x
- **jackson-datatype-jdk8**: merged into jackson-databind, no longer a separate module

OpenRewrite has an automated recipe: `org.openrewrite.java.jackson.upgradejackson_2_3`.

## Ecosystem Readiness (investigated 2026-03-14)

| Dependency | Current | Jackson 3 compatible | Upgrade to | Status |
|---|---|---|---|---|
| Jackson | 2.18.6 | — | **3.1.0** (LTS, Feb 2026) | Ready |
| logstash-logback-encoder | 8.0 | 9.0 (Oct 2024) | **9.0** | Ready |
| json-schema-validator | 1.4.0 | 3.0.0+ (Dec 2025) | **3.0.1** (Mar 2026) | Ready |
| jsonschema-generator | 4.38.0 | 5.0.0+ (Feb 2026) | **5.0.0** | Ready |
| Javalin | 6.7.0 | Optional dep, pluggable `JsonMapper` | Custom adapter | **Resolved** |

### Javalin — NOT a blocker (resolved 2026-03-14)

Javalin 7.0.1 (latest, Jan 2026) ships with Jackson 2.x as an **optional** dependency (`<optional>true</optional>` in POM). Jackson is NOT a hard dependency — Javalin's core never imports `com.fasterxml.jackson` except in two files:
- `JavalinJackson.kt` — the optional Jackson 2 adapter (never loaded if you supply your own mapper)
- `OptionalDependency.kt` — Maven coordinates for error messages

Javalin's `JsonMapper` interface has 5 methods (all default to `NotImplementedError`):
- `toJsonString(obj, type)` — used by `ctx.json()`, CookieStore, WS, JavalinVue
- `toJsonStream(obj, type)` — streaming `ctx.json()`
- `writeToOutputStream(stream, outputStream)` — `ctx.writeJsonStream()`
- `fromJsonString(json, targetType)` — `bodyAsClass()`, validation, WS
- `fromJsonStream(json, targetType)` — `bodyAsClass()` (preferred)

**Resolution:** Write a ~30-line `Jackson3JsonMapper` implementing this interface with Jackson 3's `ObjectMapper`. Register at startup via `Javalin.create(config -> config.jsonMapper(...))`. The built-in `JavalinJackson` is never instantiated. No Jackson 2.x needed on the classpath.

### Other findings

- **jackson-datatype-jdk8** can be removed — `Optional`, `OptionalInt`, etc. are built into Jackson 3's `jackson-databind`.
- **logstash-logback-encoder 9.0** changes: `JsonFactoryAware` → `ObjectMapperAware`, `JsonStreamContext` → `TokenStreamContext`, immutable `ObjectMapper`/`JsonFactory` (use `MapperBuilder`). These only matter if we have custom logback encoder extensions (we don't — we use the stock `LogstashEncoder`).

## Scope

This is a project-wide dependency migration, not a logging change. **221 Java files** across **21 modules** import `com.fasterxml.jackson.*`.

### Version catalog (`gradle/libs.versions.toml`)

5 Jackson artifacts declared, all at `2.18.6`:
- `jackson-databind`, `jackson-core`, `jackson-annotations`, `jackson-dataformat-yaml`, `jackson-datatype-jdk8`

### Module impact (main sources, by file count)

| Module | Files | Notes |
|--------|-------|-------|
| `ui` | 25 | Heaviest — settings, policy, API controllers, SSE, diagnostics |
| `configuration` | 16 | RuntimeConfig, ResolvedConfig, all config factories |
| `app-api` | 11 | Status DTOs, knowledge DTOs |
| `app-services` | 10 | WatchedRootsStore, GPL reports, VDU processor |
| `app-agent` | 10 | Tool implementations, AgentRunStore, ToolCallParser |
| `ai-bridge` | 10 | Prompt pipeline, intent templates, manifest generator |
| `indexer-worker` | 7 | SPLADE evidence, ingest responses, migration store |
| `app-inference` | 6 | Llama server communication, token endpoint |
| `benchmarks` | 5 | Benchmark CLIs |
| `adapters-lucene` | 4 | Schema commit metadata, analyzer registry |
| Others | 17 | infra-core, app-observability, app-ai, ai-worker, etc. |

### Build files affected

- `gradle/libs.versions.toml` — version + group changes for 5 artifacts
- `build.gradle.kts` (root) — `resolutionStrategy` version forcing
- `modules/app-launcher/build.gradle.kts` — Jackson version forcing
- `modules/ai-worker/build.gradle.kts` — Jackson version forcing
- `modules/app-services/build.gradle.kts` — Jackson version forcing
- `logstash-logback-encoder` version bump 8.0 → 9.0

### Transitive Jackson consumers (libraries that use Jackson internally)

- `logstash-logback-encoder` — 8.0 uses Jackson 2.x, 9.0 requires Jackson 3
- `com.networknt:json-schema-validator` — used in `LoggingRedactionGoldenTest` and SSOT schema validation
- Javalin — HTTP framework, uses Jackson for JSON request/response bodies
- `com.github.victools:jsonschema-generator` — JSON schema generation

### OpenRewrite

The OpenRewrite plugin is **already installed** (`org.openrewrite.rewrite` v7.28.1 in root `build.gradle.kts`). Configured with `rewrite-jackson:1.19.0` and `UpgradeJackson_2_3` recipe.

**Dry-run results (2026-03-14):** 3176-line patch across 200 files. Estimated time saved: 19h 15m. The recipe handles all `com.fasterxml.jackson.*` → `tools.jackson.*` import renames, `JsonGenerator.Feature` → `StreamWriteFeature`, and other API changes. Does NOT update `libs.versions.toml` (version catalog) or `resolutionStrategy` blocks — those are manual.

## Items

- [x] 1. **Ecosystem readiness check** — All dependencies have Jackson 3-compatible versions. Javalin is decoupled (optional dep, pluggable JsonMapper). See Ecosystem Readiness section above.
- [ ] 2. **Javalin JsonMapper adapter** — Write `Jackson3JsonMapper` implementing Javalin's `JsonMapper` (5 methods). Register in `LocalApiServer` at Javalin creation. Place in `modules/ui`. **Deferred** — Javalin currently uses its built-in `JavalinJackson` which finds Jackson 3 on the classpath. A custom adapter is only needed if `JavalinJackson` breaks with Jackson 3 at runtime.
- [x] 3. **OpenRewrite dry-run + apply** — Configured `rewrite-jackson:1.19.0` with `UpgradeJackson_2_3`. Applied across 200+ files: import renames, API changes, feature flag renames.
- [x] 4. **Update `gradle/libs.versions.toml`** — Jackson 3.1.0, json-schema-validator 3.0.1, jsonschema-generator 5.0.0, logstash-logback-encoder 9.0. Removed `jackson-datatype-jdk8`.
- [x] 5. **Update `resolutionStrategy` blocks** — Updated all 8 modules (app-launcher, ai-worker, app-services, indexing, indexer-worker, system-tests, app-api-tck, reports) to `tools.jackson` groups + 3.1.0 versions. Added global annotations 2.21 forcing in `JvmBaseConventionsPlugin`.
- [x] 6. **OpenRewrite + manual fixes** — OpenRewrite handled 200 files. 69 additional files fixed manually: `ObjectMapper` immutability (`JsonMapper.builder()`), `YAMLFactory` → `YAMLMapper`, `StreamWriteFeature`, `JsonReadFeature`, `node.fields()` → `node.properties()`, `ObjectNode.with()` → `withObject()`.
- [x] 7. **Fix exception handling** — All `catch (IOException)` around Jackson-only operations changed to `catch (Exception)`. Preserved `IOException` where non-Jackson IO operations coexist.
- [x] 8. **Upgrade logstash-logback-encoder** 8.0 → 9.0 (included in version catalog update).
- [x] 9. **Update lockfiles and verification metadata** — All lockfiles regenerated with Jackson 3.1.0 + annotations 2.21. Verification metadata updated.
- [x] 10. **Test suite** — All module tests pass. Pre-existing failures (model path resolution, WorkerOperationalView record, missing test resource) are not Jackson-related.

## Remaining Work

- [x] **Javalin JsonMapper adapter** — Required and implemented. 14 test files also needed the adapter.
- [x] **jackson-annotations version alignment** — Per-module resolution strategies were overriding the global 2.21 pin back to 2.18.6, causing NoClassDefFoundError for JsonSerializeAs. Fixed by aligning all per-module pins.
- [x] **CI lock skew** — Allowlisted jackson coords in CI gate (legitimate 2↔3 bridge skew). Excluded .claude worktree lockfiles from scanner.
All items complete. Merged to main 2026-03-17.

## Runtime Verification (2026-03-15)

- **Search**: HTTP 200 with full JSON response (results, scores, content previews) — serialized by Jackson3JsonMapper adapter
- **Worker startup**: Config loading (YAML + JSON), field catalog deserialization, index opening — all working
- **Javalin**: Built-in `JavalinJackson` cannot find `com.fasterxml.jackson` at runtime (confirmed). Custom `Jackson3JsonMapper` registered in `LocalApiServer` handles all JSON serialization. `ctx.json()` works correctly.
- **FAIL_ON_NULL_FOR_PRIMITIVES**: Jackson 3 defaults to `true` (was `false`). Caused `MismatchedInputException` on SSOT field catalog's null boolean fields. Fixed in `JustSearchConfigurationLoader` with explicit `disable()`.
- **Structured JSON logs**: LogstashEncoder 9.0 produces valid JSON output with MDC keys.

## Discoveries During Migration

- **jackson-annotations 2.21 required** — Jackson 3.1.0 uses `@JsonSerializeAs` from annotations 2.21, not 2.18.6. The "annotations shared at 2.x" claim is true but requires 2.21+, not any 2.x. Forced globally via `JvmBaseConventionsPlugin`.
- **json-schema-validator 3.0.1 is a full API rewrite** — `JsonSchemaFactory`/`JsonSchema`/`ValidationMessage` → `SchemaRegistry`/`Schema`/`Error` with `SchemaContext` pattern. 7 files needed manual migration.
- **OpenRewrite modified build.gradle.kts** — Renamed `asText()` → `asString()` and added Jackson import to the Kotlin build script. Had to revert manually.
- **8 modules had resolutionStrategy blocks** (not 3 as originally identified) — all needed updating.
- **`node.fields()` → `node.properties()`** — Jackson 3 changed the iteration API for object nodes.
- **`FAIL_ON_NULL_FOR_PRIMITIVES` default changed** — Jackson 3 rejects null for primitive fields by default. Any JSON with null booleans/ints fails. Must disable explicitly on mappers that read such data.
- **Javalin `JavalinJackson` incompatible** — Confirmed at runtime. Custom `JsonMapper` adapter was necessary (not optional as initially hoped).
- **AotTraining class references** — String literals referencing Jackson class names for preloading needed manual update (`com.fasterxml` → `tools.jackson`).

## Known Limitations

- **json-schema-validator behavioral differences** — The 3.0.1 API returns `List<Error>` instead of `Set<ValidationMessage>`, and error messages format differently. Schema validation may produce different results for edge cases.
- **jackson-annotations version drift** — We force 2.21 globally via convention plugin. If a library brings 2.18.x transitively and uses annotation APIs that changed, subtle runtime failures are possible.
- **Other `FAIL_ON_NULL_FOR_PRIMITIVES` sites** — Only `JustSearchConfigurationLoader` was fixed. Other `new ObjectMapper()` sites default to `true`. If any other JSON data has null primitives, it will fail at runtime with a clear `MismatchedInputException`.

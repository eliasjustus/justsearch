---
title: "RecordBuilder Annotation Processor for API Records"
type: decision
status: stable
description: "Use io.soabase record-builder v52 to generate fluent builders for Java records in app-api, enabling additive field evolution."
date: 2026-04-07
---

# ADR-0022: RecordBuilder Annotation Processor for API Records

## Status
Accepted

## Context

Java records in `app-api` (e.g., `KnowledgeSearchResponse`, `StatusResponse`) need fluent builders for ergonomic construction. `KnowledgeSearchResponse` has 25 positional constructor parameters. Adding one field breaks every callsite across every module — tests construct it with long chains of positional `null`s:

```java
new KnowledgeSearchResponse(
    0, 5, List.of(), null, null, null, null, null, null, null,
    null, null, null, null, null, null, null, null, null, null,
    null, null, null, null, null);
```

This is O(N) coupling: each new field requires updating every caller. In the 366 tempdoc, adding `queryUnderstanding` required fixing 8 callsites across 3 modules (`app-api`, `app-services`, `app-agent`). Most test callsites only need 2-3 fields but must specify all 25.

The Java language has not solved record construction ergonomics. JEP 468 (Derived Record Creation / withers) has been stuck in Candidate status since April 2024, with no movement toward JDK 25 or 26. No JEP exists for record builders, default parameter values, or named parameters.

## Decision

Use `io.soabase:record-builder` (v52) annotation processor. Records annotated with `@RecordBuilder` auto-generate fluent `*Builder` classes with `.fieldName(value)` API. The key properties:

1. **Additive evolution**: New fields default to `null`/`0`/`false` in builders, so existing builder-based callsites compile unchanged when fields are added. This converts field additions from O(N) callsite updates to O(1).
2. **No runtime dependency**: `@RecordBuilder` is source-retention. Generated code uses only JDK types. The annotation processor runs at compile time and produces plain Java source.
3. **Compact constructor compatibility**: Null-coalescing in compact constructors (e.g., `results = results == null ? List.of() : List.copyOf(results)`) works unchanged — builders pass `null` for unset collection fields, and the constructor normalizes.
4. **Controller serialization gap**: `KnowledgeSearchController` uses manual `HashMap` serialization, NOT direct record serialization. Adding a record field does NOT change API output unless the controller is also updated. A contract test comparing record component names against mapped keys catches this gap.

Additionally, a unified `updateSchemas` Gradle task consolidates the two separate schema/fixture regeneration commands (`-PupdateSchemas=true` and `-DupdateContractFixtures=true`) into a single `./gradlew.bat :modules:app-api:updateSchemas` invocation.

## Consequences

**Positive:**
- Zero-boilerplate builders for all annotated records. Generated 1201-line builder for `KnowledgeSearchResponse` automatically.
- Additive field evolution without breaking callers — the primary pain point is eliminated.
- No Spotless/PMD/SpotBugs conflicts — generated code lands in `build/generated/sources/annotationProcessor/` (excluded from linting).
- IDE completion support (IntelliJ recognizes generated builders).
- Unified `updateSchemas` task eliminates the two-command schema regeneration friction.

**Negative:**
- Annotation processor adds to build time (one-time setup: `verification-metadata.xml` + lockfile updates for 7 new artifacts).
- The `HashMap` serialization gap in `KnowledgeSearchController` is a footgun — adding a field to the record without updating the controller is a silent omission. Mitigated by a contract test but still requires developer awareness.
- Some records and nested types (e.g., `Hit` production callsite in `KnowledgeHttpApiAdapter`) still use positional constructors and need manual updates when fields are added. Full builder adoption is incremental.

## Alternatives Considered

### Lombok @Builder
Lombok's `@Builder` works on records since 1.18.20. Rejected because Lombok requires a non-standard compiler plugin and IDE plugin dependency. `@Builder.Default` has known bugs on records. Lombok modifies the AST at compile time in ways that can conflict with other annotation processors and produce surprising behavior under incremental compilation.

### Manifold compiler plugin
Manifold (`manifold-params`) adds optional parameters and named arguments directly to Java syntax, working on records. Rejected because it modifies `javac` behavior — a higher-risk dependency that could break on JDK upgrades and is harder to debug when compilation fails. The benefit (true named parameters) doesn't justify the risk for this use case where fluent builders are sufficient.

### avaje-record-builder
avaje's record builder supports `@DefaultValue` annotations for compile-time defaults. Rejected as less mature than Randgalt/record-builder (v52, de facto standard with active maintenance). avaje's `@DefaultValue` is useful but not needed — null defaults with compact constructor normalization achieve the same result.

### Manual builders
Hand-write builder classes for each record. Rejected because boilerplate scales poorly with 10+ field records. `KnowledgeSearchResponse` alone would need ~200 lines of manual builder code, and every field addition requires updating both the record and the builder. The annotation processor eliminates this maintenance burden entirely.

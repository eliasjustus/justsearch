---
description: "TRIGGER when: adding fields to Java records in modules/app-api/, modifying @RecordBuilder annotated types, changing KnowledgeSearchResponse or StatusResponse, or updating the API contract. Loads the field-addition workflow and controller HashMap caveat."
user-invocable: true
---

# API Record Modification Workflow

## Critical Caveat

`KnowledgeSearchController.handleSearch()` builds a `HashMap` manually from record fields. Adding a field to the record does not change API output unless the controller is also updated with an explicit `put()` call.

## Adding a Field to an API Record

1. **Add field** to the record's parameter list.
2. **Null-coalesce collections** in compact constructor:
   ```java
   myList = myList == null ? List.of() : List.copyOf(myList);
   ```
3. **Compile:** `./gradlew.bat :modules:app-api:compileJava` (builder regenerates automatically).
4. **Update production construction site** such as `KnowledgeHttpApiAdapter`.
5. **Update controller** (`KnowledgeSearchController`) by adding the field to the manual `HashMap` build.
6. **Regenerate schemas + fixtures:** `./gradlew.bat :modules:app-api:updateSchemas`.
7. **Run frontend contract tests:** `cd modules/ui-web && npm run test:unit:run`.
8. **Verify:** `./gradlew.bat :modules:app-api:test :modules:app-agent:test`.
9. **Frontend declaration check:** If the field is consumed by the frontend, update the relevant TypeScript declaration/schema. Loose Zod schemas may accept undeclared fields, so a missing frontend field declaration can survive runtime validation.

## v1 Contract Files

The files `status-v1.json`, `knowledge-status-v1.json`, and `debug-state-v1.json` are intentionally manual backward-compatibility baselines. Only update them on breaking changes such as field removal or rename, not on ordinary field additions.

## @RecordBuilder Convention

Records annotated with `@RecordBuilder` auto-generate `*Builder` classes. New fields default to `null`/`0`/`false` in builders, so existing builder-based test callsites compile unchanged.

Currently annotated records include: `KnowledgeSearchResponse`, `StatusResponse`, `WorkerDebugView`, `KnowledgeStatusResponse`, `EnrichmentStatusView`, `IndexCapabilitiesView`, `KnowledgeSearchRequest`, and `WorkerOperationalView`.

## Contract Test

A contract test compares record component names against mapped keys in `KnowledgeSearchController`, catching field omissions at test time. If you add a field to the record but forget the controller, this test fails.

## Key Files

- `modules/app-api/src/main/java/io/justsearch/app/api/` - API record definitions
- `modules/ui/src/main/java/io/justsearch/ui/api/KnowledgeSearchController.java` - manual HashMap serialization
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeHttpApiAdapter.java` - production construction
- `modules/ui-web/src/api/domains/` and `modules/ui-web/src/api/schemas.ts` - TypeScript declarations/Zod schemas; loose schemas may not catch missing frontend field declarations

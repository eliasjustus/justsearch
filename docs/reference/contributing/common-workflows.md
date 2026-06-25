---
title: Common Workflows
type: reference
status: stable
description: "Step-by-step recipes for recurring JustSearch contribution tasks — add a gRPC method / REST endpoint / configuration key / frontend component / agent tool; modify SSOT catalogs; add a field to an API record or registry declaration; run test suites; regenerate after doc edits. Relocated out of the always-loaded layer (tempdoc 620 Phase 2); the path-triggerable recipes are also delivered just-in-time via governance/consult-register.v1.json."
---

# Common Workflows

On-demand recipes for recurring contribution tasks. This file is **not** loaded
every session (tempdoc 620 residence relocation) — load it when you start one of
these tasks. The four path-triggerable recipes (gRPC, REST, config key, agent
tool) are *also* pushed just-in-time by `consult-doc-hint` when you edit the
relevant region (see `governance/consult-register.v1.json`); this file is the
full reference behind those nudges.

## Add a gRPC method
1. Define in `modules/ipc-common/src/main/proto/indexing.proto`
2. Implement service method in `modules/worker-services/`
3. **Add forward in `DelegatingIngestService.java`** (`modules/indexer-worker/.../grpc/`) — the gRPC server registers this wrapper, not the impl directly. Without the forward, the RPC returns UNIMPLEMENTED at runtime.
4. Add client call in `modules/app-services/` (`RemoteKnowledgeClient` or relevant client)
5. Add contract test
6. Verify: `./gradlew.bat :modules:ipc-common:build :modules:worker-services:test :modules:indexer-worker:test`

## Add a REST endpoint
1. Add handler/controller in `modules/ui/src/main/java/.../api/`
2. Register the route through the relevant `modules/ui/src/main/java/io/justsearch/ui/api/routes/*Routes.java` class, then wire that route class from `LocalApiServer.java` if needed
3. Update `docs/reference/api-contract-map.md` if public
4. Verify: `./gradlew.bat :modules:ui:test`

## Add a configuration key
1. Add entry in `EnvRegistry.java` (operator-facing) or `ConfigKey.java` (YAML-only)
2. Wire in `ResolvedConfigBuilder.java` — add `putYaml*()` contribution + `resolve*()` call in `build*()`
3. Expose via `ResolvedConfig` record field
4. Document in `docs/reference/configuration/environment-variables.md`
5. Verify: `./gradlew.bat :modules:configuration:test`

## Add a frontend component
Load `/ui-check` for visual verification via `jseval ui-shot`.
1. Create in `modules/ui-web/src/components/` or appropriate subdirectory
2. Follow patterns in neighboring components
3. Verify: `cd modules/ui-web && npm run typecheck && npm run test:unit:run`

**Changed a `modules/ui-web` dependency?** The gradle web build runs `npm ci`
(reproducible, never rewrites `package-lock.json` — tempdoc 618 §2), so it will
**fail** if `package.json` and `package-lock.json` drift. Regenerate the lock
explicitly with `cd modules/ui-web && npm install`, then commit the updated
`package-lock.json`. To live-validate a worktree's FE in a browser, run
`node scripts/dev/serve-worktree-fe.cjs` (§7).

## Add an agent tool
1. Add or update the operation definition in `OperationCatalog` / `AgentToolsOperationCatalog`
2. Implement or update the operation handler in `modules/app-services/.../registry/operations/handlers/`
3. Confirm `AgentToolEmitter` projects the intended model-visible wire name and schema
4. Add safety/approval metadata at the operation layer
5. Update `docs/explanation/22-agent-system-architecture.md`
6. Verify: `./gradlew.bat :modules:app-agent:test :modules:app-services:test`

## Modify SSOT catalogs (fields, analyzers, synonyms)
Load `/ssot-catalog` for the dual-copy checklist and field role reference.
1. Edit JSON in `SSOT/catalogs/`
2. **If adding fields**: also update the classpath copy at `modules/adapters-lucene/src/main/resources/SSOT/catalogs/fields.v1.json` — production loads this when the repo root is unavailable
3. **If adding fields with extraction logic**: update `IndexingDocumentOps.java` to populate the new field during ingestion. Existing indices will NOT have the new field — test corpora must be re-indexed (`jseval run --reset` or `--start-backend --clean`)
4. Regenerate fingerprints if needed
5. Verify pinned-hash tests: `./gradlew.bat :modules:ssot-tools:test`
6. Check commit metadata compatibility: `./gradlew.bat :modules:adapters-lucene:test`

## Run specific test suites
- Single module: `./gradlew.bat :modules:<module>:test`
- Full unit suite: `./gradlew.bat test`
- Frontend unit: `cd modules/ui-web && npm run test:unit:run`
- Frontend typecheck: `cd modules/ui-web && npm run typecheck`
- System/integration: `./gradlew.bat :modules:system-tests:test`
- Format check: `./gradlew.bat spotlessCheck`
- Format fix: `./gradlew.bat spotlessApply`

## Add a field to an API record
Load `/api-record` for the full workflow including the controller HashMap caveat.

Records in `app-api` (e.g., `KnowledgeSearchResponse`, `WorkerDebugView`)
use `@RecordBuilder` to generate fluent builders. When adding a field:

1. Add the field to the record's parameter list
2. If the field is a collection type, add null-coalescing in the compact
   constructor (e.g., `myList = myList == null ? List.of() : List.copyOf(myList)`)
3. Compile: `./gradlew.bat :modules:app-api:compileJava` — the builder
   regenerates automatically
4. Update the production construction site (e.g., `KnowledgeHttpApiAdapter`)
   to pass the new field
5. **Builder-based test callsites need no update** — the builder defaults
   new fields to `null`/`0`/`false`, so existing builder calls compile
   unchanged. Note: some records and nested types (e.g., `Hit` production
   callsite in `KnowledgeHttpApiAdapter`) still use positional constructors
   and will need manual updates. Check `@RecordBuilder` annotation presence.
6. If the record is part of the API contract, update the controller
   (`KnowledgeSearchController`) to include the field in the response map
7. Regenerate schemas and fixtures: `./gradlew.bat :modules:app-api:updateSchemas`
8. If the record is an FE wire surface, it is a generated record→JSON-Schema→{TS,Zod}
   projection (tempdoc 564). Emit/refresh its JSON Schema via the owning module's
   `updateSchemas` task (`:modules:app-api:updateSchemas` for app-api records;
   `:modules:app-observability:updateSchemas` for `HealthEvent`), then regenerate the FE
   types: `node scripts/codegen/gen-wire-schema-types.mjs` (add a `TARGETS` entry + a
   `governance/contract-surfaces.v1.json` row for a NEW surface). The parallel
   `wire-types.ts` (typescript-generator) path was retired in 564 Phase 4.
9. If the record is part of `/api/status` or `/api/knowledge/search`, the FE validates the
   raw wire at the parse boundary via `parseWireContract(<generated schema>, …)` (non
   fail-open). New fields flow through the generated schema-types; do not hand-author a
   second `.loose()` Zod (the `wire-type-single-authority` gate refuses a hand copy).
10. Run frontend contract tests: `cd modules/ui-web && npm run test:unit:run`
11. Verify: `./gradlew.bat :modules:app-api:test :modules:app-agent:test :modules:app-observability:test`

## Add a field to a registry declaration (Operation / Resource)
The `/api/registry/{operations,resources}` wire is a generated projection of a typed
**wire view** record, not the domain record directly (tempdoc 560 §4c). Adding a field:
1. Add it to the domain record (`Operation`/`Resource` in `modules/app-agent-api/.../registry/`)
2. Add the matching field on the typed wire view (`UIOperationView`/`UIResourceView`) and map it
   in the emitter (`UIOperationEmitter`/`UIResourceEmitter`). Mark nullability on the wire view:
   `Optional<>` or `@Nullable` for present-as-null; `@JsonInclude(NON_NULL)` for omitted-when-absent;
   plain for required + non-null (the `PreciseWire` rule drives the generated `required`/nullable).
3. Regenerate + sync the schema: `./gradlew.bat :modules:app-api:updateSchemas` then
   `./gradlew.bat :modules:ui:syncSsotSchemas`, then `node scripts/codegen/gen-wire-schema-types.mjs`
4. The FE re-exports/derives from the generated wire in `modules/ui-web/src/api/types/registry.ts`
   (derive nested types from the wire — do not hand-mirror); update fixtures the precise types flag.
5. Verify: the `UI{Operation,Resource}ViewConformanceTest` pins the wire byte-for-byte;
   `cd modules/ui-web && npm run typecheck && npm run test:unit:run`; the `contract-projection` +
   `wire-type-single-authority` gates.

## After modifying docs
Load `/docs-maintenance` for the full regeneration checklist and doc quality rules.
- Regenerate llms.txt: `node scripts/docs/llmstxt-generate.mjs`
- Sync skills from canonical docs: `node scripts/docs/skills-sync.mjs`
- After module changes: `node scripts/architecture/module-deps.mjs --update-canonical`
- After config changes: `node scripts/docs/generate-runtime-config-matrix.mjs --write-doc docs/reference/configuration/runtime-config-ownership-matrix.md`

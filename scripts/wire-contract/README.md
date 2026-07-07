# scripts/wire-contract/

Pinned `buf` CLI for the JustSearch wire-Category contract. Its ONLY remaining
job is `buf breaking` compatibility checking, invoked by the `wire` governance
gate (`scripts/governance/gates/wire/enforcer.mjs` →
`protobuf-buf-breaking.mjs`, which resolves the binary from this workspace's
`node_modules`). Installation:

```sh
cd scripts/wire-contract
npm install
```

Run the gate from the repo root:

```sh
node scripts/governance/run.mjs --gate wire --mode gate
```

## Where generated types come from (this workspace does NOT generate code)

- **Frontend (TS/Zod)**: FE wire types are not emitted from proto. They come
  from the record→JSON-Schema→{TS,Zod} pipeline
  (`scripts/codegen/gen-wire-schema-types.mjs`), guarded by the
  `check-wire-schema-types-regen` regen gate.
- **Java**: emitted by the `com.google.protobuf` Gradle plugin in
  `modules/api-contract-projection-java` (`:wireGenerate` wraps
  `:modules:api-contract-projection-java:generateProto`). No buf CLI involved.

The former TS emission flow (`buf generate` + `protoc-gen-es` →
`modules/ui-web/src/api/generated/*_pb.*`) was retired;
`contracts/wire/buf.gen.yaml` intentionally has `plugins: []`.

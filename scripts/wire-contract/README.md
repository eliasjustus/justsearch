# scripts/wire-contract/

Pinned tooling for the JustSearch wire-Category contract substrate (slice
3a-1-8 Phase 2). Hosts the npm-installed `buf` CLI + `protoc-gen-es` emitter
for TypeScript generation.

## Why a separate workspace

Per ADR-09a's distribution posture: the contract is language-neutral. The
buf CLI + TS emitter are kept out of the developer's global PATH and pinned
to repo-controlled versions. Installation:

```sh
cd scripts/wire-contract
npm install
```

This populates `node_modules/.bin/{buf, protoc-gen-es}` for the
`:wireGenerate` Gradle task to invoke.

## Java emission

Java emission uses the existing `com.google.protobuf` Gradle plugin from
`modules/api-contract-projection-java/build.gradle.kts`. No buf CLI involved
on the Java side. See ADR-09a §"Decision" for the rationale.

## Regenerating TypeScript

From the repo root:

```sh
./gradlew.bat :wireGenerate
```

This runs `buf generate` against `contracts/wire/buf.gen.yaml`, emitting TS
to `modules/ui-web/src/api/generated/`. The output is committed to git.
`./gradlew.bat :wireVerify` confirms the working tree matches the regenerated
output (CI gate).

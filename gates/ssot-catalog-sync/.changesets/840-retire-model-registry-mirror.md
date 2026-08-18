---
classification: mirror-retirement
mirror: model-registry
tempdoc: 840
---

Retire the `model-registry` mirror. `ai/model-registry.v2.json` moved from
`modules/ui/src/main/resources/ai/` to `modules/configuration/src/main/resources/ai/` — the
module that owns `ModelRegistryLoader` and that `modules:app-services` already depends on via
`api(project(":modules:configuration"))`. The registry now ships as a single production resource
reachable from every consumer's compile/runtime/test classpath by construction, so the
`modules/configuration/src/test/resources/ai/model-registry.v2.json` test-only copy this mirror
existed to keep in sync is deleted, and `ModelRegistryLoaderTest` / the new
`ModelRegistryClasspathReachabilityTest` load the real production resource directly. No second
copy remains to dual-copy, so the mirror entry is removed.

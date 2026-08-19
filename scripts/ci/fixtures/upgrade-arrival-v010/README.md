# Fixture: upgrade-arrival-v010

Models the data-dir shape a v0.1.0 install leaves behind, for the packaged verify
lane's upgrade-arrival leg (tempdoc 805 Part G.4, `-IncludeUpgradeArrival`).

## What's here

- `install-contract.v2.json` -- a bill-of-materials matching the shape
  `AiInstallService.buildContract` (`modules/app-services/src/main/java/io/justsearch/app/services/ai/install/AiInstallService.java`,
  ~L900-959) and `InstallContract` (`modules/configuration/src/main/java/io/justsearch/configuration/model/InstallContract.java`)
  produce:
  - `schemaVersion: 2`, `downloadProfile: GPU_FULL`.
  - Five model-package entries (`embedding`, `splade`, `reranker`, `ner`, `chat`)
    with fake (non-functional) `sha256` values and the real registry
    `targetDir`/`variantFilename`/`installedFiles` shapes for a GPU_FULL
    install (`modules/configuration/src/main/resources/ai/model-registry.v2.json`).
  - A `cuda-runtime` entry recorded `skipped: true, skipReason: "No variant"`
    with no `installedFiles` -- `cuda-runtime` ships `variants: []` in both the
    v0.1.0 and 0.2.0 registries, so `ModelPackage.selectVariant` returns null
    and the writer always records it this way (tempdoc 805 Part H, U3).
  - No `modelsDir` / `installIntent` fields -- both post-date v0.1.0's schema
    vintage; their absence exercises the same nullable back-compat fallback
    (`resolveContractModelsDir` -> `aiHome/models`; `installIntent` -> Full
    Desktop) a real v0.1.0-installed machine hits today.

- **No `ui/settings.json`** -- the upgrade-arrival leg's whole point (tempdoc
  804 sec B4.2 / round 10's regression): `-Djustsearch.prod=true` must not
  silently switch `UiSettingsStore` to in-memory persistence just because no
  settings file exists yet.

- **No real model bytes** -- this fixture is metadata-only. The `sha256`
  values are placeholders and none of the files the contract references
  actually exist on disk under the copied data dir. This is deliberate: the
  leg asserts that activation resolves a model *path* via the contract
  fallback (never regressing to `MODEL_PATH_REQUIRED`), not that activation
  fully succeeds.

## Sibling coverage

The in-process `ProdModeSettingsPersistenceIntegrationTest`
(`modules/ui/src/integrationTest/java/io/justsearch/ui/api/ProdModeSettingsPersistenceIntegrationTest.java`)
already models this exact shape (contract + chat model bytes + variant dir,
no settings file) in-process, with real chat-model bytes on disk so activation
can actually complete. This fixture is the packaged-payload sibling -- it
proves the same shape survives a real NSIS install + bundled JRE boot, not
just an in-process JVM.

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.UiSettings;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 883 §C.5c residue — the Worker snapshot is written from the config as it stands AFTER the
 * boot steps that write system properties, not from the one built before them.
 *
 * <p>The residue: {@code resolveConfig} built a {@code ResolvedConfig}, then
 * {@code maybeAutoSelectCuda12Variant} wrote {@code justsearch.server.exe} (and
 * {@code maybeMirrorOrtNativePath} wrote {@code justsearch.onnxruntime.native_path}), and then the
 * snapshot was written from the config built BEFORE those writes. A boot-time cuda12 auto-select
 * therefore never crossed the process boundary: the Head switched variants and the Worker's
 * snapshot still named the old exe.
 *
 * <p>Both assertions below fail against that ordering, because the sysprop is written between the
 * build and the snapshot exactly as the two boot steps do it.
 */
@DisplayName("HeadlessApp — worker snapshot ordering (883 §C.5c)")
final class HeadlessAppWorkerSnapshotOrderingTest {

  private static final String SERVER_EXE = "justsearch.server.exe";
  private static final String SNAPSHOT_PROP = "justsearch.worker.config_snapshot";

  @TempDir Path tempDir;

  @AfterEach
  void clearProps() {
    System.clearProperty(SERVER_EXE);
    System.clearProperty(SNAPSHOT_PROP);
  }

  @Test
  @DisplayName("a sysprop written after the build reaches both the snapshot and the returned config")
  void postBuildSyspropReachesTheSnapshot() throws Exception {
    // The boot build: settings.json at 300 names the exe the user chose.
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.putSettings(SERVER_EXE, "C:/installed/llama-server.exe");
    builder.contributeEnvRegistry();
    ConfigStore store = new ConfigStore(builder.build());
    assertEquals(
        "C:/installed/llama-server.exe",
        store.get().resolution(SERVER_EXE).value(),
        "precondition: the pre-write config names the original exe");

    // What maybeAutoSelectCuda12Variant does: writes the sysprop, does NOT rebuild.
    System.setProperty(SERVER_EXE, "C:/installed/variants/cuda12/llama-server.exe");

    Path snapshotPath = tempDir.resolve("worker-config-snapshot.json");
    HeadlessApp.SnapshotResult result =
        HeadlessApp.snapshotAfterPostBuildWrites(store, new UiSettings(), snapshotPath);

    assertEquals(snapshotPath, result.writtenSnapshot(), "the snapshot must have been written");
    // Substring, not equality: the snapshot re-serialises resolved paths, so the separator is the
    // platform's and the JSON escaping is Jackson's.
    assertTrue(
        Files.readString(snapshotPath).contains("cuda12"),
        "the Worker's snapshot must name the exe the Head just auto-selected");
    assertEquals(
        "C:/installed/variants/cuda12/llama-server.exe",
        result.config().resolution(SERVER_EXE).value(),
        "and the rest of boot must see the same config the snapshot was written from");
    assertEquals(
        "C:/installed/variants/cuda12/llama-server.exe",
        store.get().resolution(SERVER_EXE).value(),
        "the ConfigStore is updated in place, so no reader is left on the stale config");
  }

  @Test
  @DisplayName("the rebuild does not drop the settings the initial build contributed")
  void rebuildKeepsSettingsContributions() throws Exception {
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    UiSettings settings = new UiSettings();
    settings.setLlmModelPath("C:/models/chat.gguf");
    io.justsearch.app.services.config.ConfigStoreRebuilder.contributeUiSettings(builder, settings);
    ConfigStore store = new ConfigStore(builder.build());

    HeadlessApp.SnapshotResult result =
        HeadlessApp.snapshotAfterPostBuildWrites(
            store, settings, tempDir.resolve("worker-config-snapshot.json"));

    assertEquals(
        "C:/models/chat.gguf",
        result.config().resolution("justsearch.llm.model_path").value(),
        "a rebuild that dropped ordinal 300 would silently un-set every GUI value at boot");
  }
}

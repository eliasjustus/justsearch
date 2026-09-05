package io.justsearch.configuration.model;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallContractIOTest {

  @TempDir Path tempDir;

  @Test
  void roundTrip_writeThenRead() {
    var model = new InstallContract.InstalledModel(
        "embedding", "model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
        "onnx/embed", "AAAA", List.of("model.onnx", "tokenizer.json"), false, null);
    var skipped = InstallContract.InstalledModel.skipped("chat", SkipCause.HARDWARE, "No CUDA");
    // Tempdoc 840 Phase 2: the typed cause is a PERSISTED field, so it must survive the round trip.
    // An unclassified entry (the pre-840 shape, and the "nothing to install" bookkeeping entry) must
    // survive as null rather than defaulting to some cause the planner never decided.
    var declined =
        InstallContract.InstalledModel.skipped("reranker", SkipCause.USER_DECLINED, "You declined it");
    var unclassified = InstallContract.InstalledModel.skipped("ner", "No variant");

    var contract = new InstallContract(
        2, System.currentTimeMillis(),
        HardwareProfile.cpuOnly(), DownloadProfile.CPU,
        Map.of(
            "embedding", model,
            "chat", skipped,
            "reranker", declined,
            "ner", unclassified));

    InstallContractIO.write(contract, tempDir);
    InstallContract loaded = InstallContractIO.read(tempDir);

    assertNotNull(loaded);
    assertEquals(2, loaded.schemaVersion());
    assertEquals(DownloadProfile.CPU, loaded.downloadProfile());
    assertFalse(loaded.hardwareProfile().cudaFunctional());

    var embeddingModel = loaded.getModel("embedding");
    assertNotNull(embeddingModel);
    assertEquals("model.onnx", embeddingModel.variantFilename());
    assertEquals(ModelPrecision.FP32, embeddingModel.precision());
    assertFalse(embeddingModel.skipped());

    var chatModel = loaded.getModel("chat");
    assertNotNull(chatModel);
    assertTrue(chatModel.skipped());
    assertEquals("No CUDA", chatModel.skipReason());
    assertEquals(SkipCause.HARDWARE, chatModel.skipCause());

    assertEquals(
        SkipCause.USER_DECLINED,
        loaded.getModel("reranker").skipCause(),
        "a decline must stay distinguishable from a hardware skip across a restart");
    assertNull(
        loaded.getModel("ner").skipCause(),
        "an unclassified skip must not gain a cause the planner never decided");
  }

  @Test
  void readMissingContract_returnsNull() {
    assertNull(InstallContractIO.read(tempDir));
  }

  @Test
  void futureContractIsRefusedWithoutOverwrite() throws Exception {
    Path file = tempDir.resolve(InstallContract.CONTRACT_FILENAME);
    String future =
        """
        {"schemaVersion":99,"installedAtEpochMs":0,"models":{}}
        """;
    Files.writeString(file, future);
    assertThrows(UnsupportedStoreVersionException.class, () -> InstallContractIO.read(tempDir));
    assertEquals(future, Files.readString(file));
  }

  @Test
  void malformedContractIsRefusedWithoutOverwrite() throws Exception {
    Path file = tempDir.resolve(InstallContract.CONTRACT_FILENAME);
    String malformed = "{not-json";
    Files.writeString(file, malformed);
    assertThrows(CorruptDurableStoreException.class, () -> InstallContractIO.read(tempDir));
    assertEquals(malformed, Files.readString(file));
  }

  @Test
  void writeRejectsNonCurrentSchema() {
    InstallContract wrongVersion =
        new InstallContract(
            3,
            System.currentTimeMillis(),
            HardwareProfile.cpuOnly(),
            DownloadProfile.CPU,
            Map.of());
    assertThrows(IllegalArgumentException.class, () -> InstallContractIO.write(wrongVersion, tempDir));
  }

  @Test
  void resolveModelPath_returnsCorrectPath() {
    var model = new InstallContract.InstalledModel(
        "embedding", "model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
        "onnx/embed", "AAAA", List.of("model.onnx"), false, null);
    var contract = new InstallContract(
        2, System.currentTimeMillis(),
        HardwareProfile.cpuOnly(), DownloadProfile.CPU,
        Map.of("embedding", model));

    Path resolved = contract.resolveModelPath("embedding", tempDir);
    assertNotNull(resolved);
    assertEquals(tempDir.resolve("onnx/embed/model.onnx"), resolved);
  }

  @Test
  void resolveModelPath_skippedReturnsNull() {
    var contract = new InstallContract(
        2, System.currentTimeMillis(),
        HardwareProfile.cpuOnly(), DownloadProfile.CPU,
        Map.of("chat", InstallContract.InstalledModel.skipped("chat", "No CUDA")));

    assertNull(contract.resolveModelPath("chat", tempDir));
  }

  // ==================== Tempdoc 374 alpha.20 Bug M: modelsDir field ====================

  /**
   * Tempdoc 374 alpha.20 Bug M: contract carries absolute modelsDir so cold restart
   * survives env-var-not-inheriting failure modes. Round-trip verifies serialization
   * + deserialization keeps the field intact.
   */
  @Test
  void roundTrip_preservesModelsDir() {
    Path modelsDir = tempDir.resolve("staged-models");
    var contract = new InstallContract(
        2, System.currentTimeMillis(),
        HardwareProfile.cpuOnly(), DownloadProfile.CPU,
        Map.of(), modelsDir);

    InstallContractIO.write(contract, tempDir);
    InstallContract loaded = InstallContractIO.read(tempDir);

    assertNotNull(loaded);
    assertEquals(modelsDir, loaded.modelsDir(),
        "modelsDir must round-trip through Jackson serialization (374 alpha.20 Bug M)");
  }

  /**
   * Backwards-compat: a contract written before alpha.20 (no modelsDir field) must
   * deserialize cleanly with modelsDir=null. Existing alpha.16-19 installs have such
   * contracts; their next Install AI run rewrites with the new field, but until then
   * KnowledgeServer.resolveModelsDir's fallback chain handles the null.
   */
  @Test
  void backwardsCompat_oldContractWithoutModelsDir_deserializesCleanly() throws Exception {
    String oldContractJson =
        "{\n"
            + "  \"schemaVersion\": 2,\n"
            + "  \"installedAtEpochMs\": 1700000000000,\n"
            + "  \"hardwareProfile\": {\"gpuDetected\": false, \"cudaFunctional\": false, \"vramBytes\": 0},\n"
            + "  \"downloadProfile\": \"CPU\",\n"
            + "  \"models\": {}\n"
            + "}\n";
    Files.writeString(
        tempDir.resolve(InstallContract.CONTRACT_FILENAME), oldContractJson);

    InstallContract loaded = InstallContractIO.read(tempDir);

    assertNotNull(loaded);
    assertNull(loaded.modelsDir(),
        "pre-alpha.20 contract without modelsDir field must deserialize with null"
            + " (no exception). Resolution falls through to alpha.18 env-var path.");
    assertEquals(2, loaded.schemaVersion());
    assertEquals(DownloadProfile.CPU, loaded.downloadProfile());
    assertNull(loaded.installIntent(),
        "pre-tempdoc-657 contract without installIntent field must deserialize with null");
  }

  /**
   * 5-arg constructor (backwards-compat) defaults modelsDir to null. Used by tests and
   * by old Jackson deserialization paths that pre-date the modelsDir field.
   */
  @Test
  void backwardsCompat_fiveArgConstructor_defaultsModelsDirNull() {
    var contract = new InstallContract(
        2, System.currentTimeMillis(),
        HardwareProfile.cpuOnly(), DownloadProfile.CPU,
        Map.of());

    assertNull(contract.modelsDir(),
        "the 5-arg backwards-compat constructor must default modelsDir to null");
    assertNull(contract.installIntent(),
        "the 5-arg backwards-compat constructor must default installIntent to null");
  }

  /**
   * Tempdoc 657 — the 6-arg constructor (pre-installIntent) defaults installIntent to null, and the
   * new field round-trips through disk when set.
   */
  @Test
  void installIntent_defaultsNullOnSixArg_andRoundTripsWhenSet() throws Exception {
    var sixArg = new InstallContract(
        2, System.currentTimeMillis(),
        HardwareProfile.cpuOnly(), DownloadProfile.CPU,
        Map.of(), tempDir);
    assertNull(sixArg.installIntent(),
        "the 6-arg backwards-compat constructor must default installIntent to null");

    var withIntent = new InstallContract(
        2, System.currentTimeMillis(),
        HardwareProfile.cpuOnly(), DownloadProfile.CPU,
        Map.of(), tempDir, InstallIntent.MCP_LITE);
    InstallContractIO.write(withIntent, tempDir);
    InstallContract loaded = InstallContractIO.read(tempDir);
    assertEquals(InstallIntent.MCP_LITE, loaded.installIntent(),
        "installIntent must round-trip through disk");
  }
}

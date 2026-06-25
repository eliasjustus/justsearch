package io.justsearch.configuration.model;

import static org.junit.jupiter.api.Assertions.*;

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
    var skipped = InstallContract.InstalledModel.skipped("chat", "No CUDA");

    var contract = new InstallContract(
        2, System.currentTimeMillis(),
        HardwareProfile.cpuOnly(), DownloadProfile.CPU,
        Map.of("embedding", model, "chat", skipped));

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
  }

  @Test
  void readMissingContract_returnsNull() {
    assertNull(InstallContractIO.read(tempDir));
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
    java.nio.file.Files.writeString(
        tempDir.resolve(InstallContract.CONTRACT_FILENAME), oldContractJson);

    InstallContract loaded = InstallContractIO.read(tempDir);

    assertNotNull(loaded);
    assertNull(loaded.modelsDir(),
        "pre-alpha.20 contract without modelsDir field must deserialize with null"
            + " (no exception). Resolution falls through to alpha.18 env-var path.");
    assertEquals(2, loaded.schemaVersion());
    assertEquals(DownloadProfile.CPU, loaded.downloadProfile());
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
  }
}

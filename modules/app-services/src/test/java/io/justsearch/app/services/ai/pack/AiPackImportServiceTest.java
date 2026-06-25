package io.justsearch.app.services.ai.pack;

import io.justsearch.app.api.AiPackPreflightResult;
import io.justsearch.app.api.AiPackImportStatus;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.policy.EnterprisePolicyServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiPackImportServiceTest {

  @TempDir Path tmp;

  private String prevHome;

  @AfterEach
  void cleanup() {
    if (prevHome == null) System.clearProperty("justsearch.home");
    else System.setProperty("justsearch.home", prevHome);
  }

  @Test
  void zipImportSucceedsAndWritesInstalledPacks() throws Exception {
    setHome(tmp);

    byte[] chatBytes = "chat-model".getBytes(StandardCharsets.UTF_8);
    byte[] embedBytes = "embed-model".getBytes(StandardCharsets.UTF_8);
    String chatSha = sha256Hex(chatBytes);
    String embedSha = sha256Hex(embedBytes);

    String manifestJson =
        """
        {
          "schemaVersion": 1,
          "packId": "justsearch.ai-pack.v2.models.default",
          "packVersion": "2.0.0",
          "kind": "models",
          "createdAt": "2025-12-23T00:00:00Z",
          "requiresAppMin": "1.0.0",
          "files": [
            { "id": "chat", "pathInPack": "payload/models/chat.gguf", "sha256": "%s", "sizeBytes": %d },
            { "id": "embed", "pathInPack": "payload/models/embed.gguf", "sha256": "%s", "sizeBytes": %d }
          ],
          "assets": [
            { "role": "model.chat", "fileId": "chat" },
            { "role": "model.embedding", "fileId": "embed" }
          ]
        }
        """
            .formatted(chatSha, chatBytes.length, embedSha, embedBytes.length);
    byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
    String manifestSha = sha256Hex(manifestBytes);

    Path zip = tmp.resolve("pack.zip");
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("pack-manifest.v1.json", manifestBytes);
    entries.put("payload/models/chat.gguf", chatBytes);
    entries.put("payload/models/embed.gguf", embedBytes);
    writeZip(zip, entries);

    AiPackImportService svc =
        new AiPackImportService(
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            new EnterprisePolicyServiceImpl(),
            new PackAllowlistService(Set.of(manifestSha)));

    svc.startImport(zip, false);
    AiPackImportStatus st = awaitDone(svc);
    assertEquals("completed", st.state, st.errorCode + " " + st.message);

    assertTrue(Files.isRegularFile(tmp.resolve("models").resolve("chat.gguf")));
    assertTrue(Files.isRegularFile(tmp.resolve("models").resolve("embed.gguf")));
    assertTrue(Files.isRegularFile(tmp.resolve("installed-packs.v1.json")));

    var record = svc.getInstalledPacks();
    assertNotNull(record);
    assertNotNull(record.packs);
    assertEquals(1, record.packs.size());
    assertEquals("justsearch.ai-pack.v2.models.default", record.packs.get(0).packId);
  }

  @Test
  void zipImportRuntimePackInstallsIntoVariantDirAndWritesInstalledPacks() throws Exception {
    setHome(tmp);

    byte[] exeBytes = "llama-server".getBytes(StandardCharsets.UTF_8);
    byte[] dllBytes = "ggml-cuda".getBytes(StandardCharsets.UTF_8);
    byte[] noticeBytes = "NOTICE".getBytes(StandardCharsets.UTF_8);
    String exeSha = sha256Hex(exeBytes);
    String dllSha = sha256Hex(dllBytes);
    String noticeSha = sha256Hex(noticeBytes);

    String manifestJson =
        """
        {
          "schemaVersion": 1,
          "packId": "justsearch.ai-pack.v3.runtime.cuda",
          "packVersion": "1.0.0",
          "kind": "runtime",
          "variantId": "cuda-12.4",
          "createdAt": "2025-12-23T00:00:00Z",
          "requiresAppMin": "1.0.0",
          "files": [
            { "id": "exe", "pathInPack": "payload/llama-server.exe", "sha256": "%s", "sizeBytes": %d },
            { "id": "dll", "pathInPack": "payload/ggml-cuda.dll", "sha256": "%s", "sizeBytes": %d },
            { "id": "notice", "pathInPack": "payload/NOTICE-NVIDIA-CUDA.txt", "sha256": "%s", "sizeBytes": %d }
          ],
          "assets": [
            { "role": "runtime.llamaServer", "fileId": "exe" },
            { "role": "runtime.runtimeFile", "fileId": "dll" },
            { "role": "runtime.runtimeFile", "fileId": "notice" }
          ]
        }
        """
            .formatted(exeSha, exeBytes.length, dllSha, dllBytes.length, noticeSha, noticeBytes.length);
    byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
    String manifestSha = sha256Hex(manifestBytes);

    Path zip = tmp.resolve("runtime-pack.zip");
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("pack-manifest.v1.json", manifestBytes);
    entries.put("payload/llama-server.exe", exeBytes);
    entries.put("payload/ggml-cuda.dll", dllBytes);
    entries.put("payload/NOTICE-NVIDIA-CUDA.txt", noticeBytes);
    writeZip(zip, entries);

    AiPackImportService svc =
        new AiPackImportService(
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            new EnterprisePolicyServiceImpl(),
            new PackAllowlistService(Set.of(manifestSha)));

    svc.startImport(zip, false);
    AiPackImportStatus st = awaitDone(svc);
    assertEquals("completed", st.state, st.errorCode + " " + st.message);

    Path variantDir = tmp.resolve("native-bin/llama-server/variants/cuda-12.4");
    assertTrue(Files.isRegularFile(variantDir.resolve("llama-server.exe")));
    assertTrue(Files.isRegularFile(variantDir.resolve("ggml-cuda.dll")));
    assertTrue(Files.isRegularFile(variantDir.resolve("NOTICE-NVIDIA-CUDA.txt")));
    assertTrue(Files.isRegularFile(tmp.resolve("installed-packs.v1.json")));

    var record = svc.getInstalledPacks();
    assertNotNull(record);
    assertNotNull(record.packs);
    assertEquals(1, record.packs.size());
    assertEquals("runtime", record.packs.get(0).kind);
    assertEquals("justsearch.ai-pack.v3.runtime.cuda", record.packs.get(0).packId);
    assertEquals(3, record.packs.get(0).files.size());
    assertEquals("cuda-12.4", record.packs.get(0).files.get(0).variantId);
  }

  @Test
  void zipImportRuntimePackCanInstallOnnxRuntimeVariantFiles() throws Exception {
    setHome(tmp);

    byte[] exeBytes = "llama-server".getBytes(StandardCharsets.UTF_8);
    byte[] dllBytes = "ggml-cuda".getBytes(StandardCharsets.UTF_8);
    byte[] noticeBytes = "NOTICE".getBytes(StandardCharsets.UTF_8);
    byte[] ortBytes = "onnxruntime".getBytes(StandardCharsets.UTF_8);
    byte[] ortCudaBytes = "onnxruntime-providers-cuda".getBytes(StandardCharsets.UTF_8);

    String exeSha = sha256Hex(exeBytes);
    String dllSha = sha256Hex(dllBytes);
    String noticeSha = sha256Hex(noticeBytes);
    String ortSha = sha256Hex(ortBytes);
    String ortCudaSha = sha256Hex(ortCudaBytes);

    String manifestJson =
        """
        {
          "schemaVersion": 1,
          "packId": "justsearch.ai-pack.v3.runtime.cuda-plus-ort",
          "packVersion": "1.0.0",
          "kind": "runtime",
          "variantId": "cuda-12.4",
          "createdAt": "2025-12-23T00:00:00Z",
          "requiresAppMin": "1.0.0",
          "files": [
            { "id": "exe", "pathInPack": "payload/llama-server.exe", "sha256": "%s", "sizeBytes": %d },
            { "id": "dll", "pathInPack": "payload/ggml-cuda.dll", "sha256": "%s", "sizeBytes": %d },
            { "id": "notice", "pathInPack": "payload/NOTICE-NVIDIA-CUDA.txt", "sha256": "%s", "sizeBytes": %d },
            { "id": "ort", "pathInPack": "payload/onnxruntime.dll", "sha256": "%s", "sizeBytes": %d },
            { "id": "ortCuda", "pathInPack": "payload/onnxruntime_providers_cuda.dll", "sha256": "%s", "sizeBytes": %d }
          ],
          "assets": [
            { "role": "runtime.llamaServer", "fileId": "exe" },
            { "role": "runtime.runtimeFile", "fileId": "dll" },
            { "role": "runtime.runtimeFile", "fileId": "notice" },
            { "role": "runtime.onnxruntime", "fileId": "ort" },
            { "role": "runtime.onnxruntimeFile", "fileId": "ortCuda" }
          ]
        }
        """
            .formatted(
                exeSha,
                exeBytes.length,
                dllSha,
                dllBytes.length,
                noticeSha,
                noticeBytes.length,
                ortSha,
                ortBytes.length,
                ortCudaSha,
                ortCudaBytes.length);
    byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
    String manifestSha = sha256Hex(manifestBytes);

    Path zip = tmp.resolve("runtime-pack-ort.zip");
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("pack-manifest.v1.json", manifestBytes);
    entries.put("payload/llama-server.exe", exeBytes);
    entries.put("payload/ggml-cuda.dll", dllBytes);
    entries.put("payload/NOTICE-NVIDIA-CUDA.txt", noticeBytes);
    entries.put("payload/onnxruntime.dll", ortBytes);
    entries.put("payload/onnxruntime_providers_cuda.dll", ortCudaBytes);
    writeZip(zip, entries);

    AiPackImportService svc =
        new AiPackImportService(
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            new EnterprisePolicyServiceImpl(),
            new PackAllowlistService(Set.of(manifestSha)));

    svc.startImport(zip, false);
    AiPackImportStatus st = awaitDone(svc);
    assertEquals("completed", st.state, st.errorCode + " " + st.message);

    Path llamaVariantDir = tmp.resolve("native-bin/llama-server/variants/cuda-12.4");
    assertTrue(Files.isRegularFile(llamaVariantDir.resolve("llama-server.exe")));
    assertTrue(Files.isRegularFile(llamaVariantDir.resolve("ggml-cuda.dll")));
    assertTrue(Files.isRegularFile(llamaVariantDir.resolve("NOTICE-NVIDIA-CUDA.txt")));

    Path ortVariantDir = tmp.resolve("native-bin/onnxruntime/variants/cuda-12.4");
    assertTrue(Files.isRegularFile(ortVariantDir.resolve("onnxruntime.dll")));
    assertTrue(Files.isRegularFile(ortVariantDir.resolve("onnxruntime_providers_cuda.dll")));
  }

  @Test
  void zipImportFailsClosedOnExtraFile() throws Exception {
    setHome(tmp);

    byte[] chatBytes = "chat-model".getBytes(StandardCharsets.UTF_8);
    byte[] embedBytes = "embed-model".getBytes(StandardCharsets.UTF_8);
    String chatSha = sha256Hex(chatBytes);
    String embedSha = sha256Hex(embedBytes);

    String manifestJson =
        """
        {
          "schemaVersion": 1,
          "packId": "justsearch.ai-pack.v2.models.default",
          "packVersion": "2.0.0",
          "kind": "models",
          "createdAt": "2025-12-23T00:00:00Z",
          "requiresAppMin": "1.0.0",
          "files": [
            { "id": "chat", "pathInPack": "payload/models/chat.gguf", "sha256": "%s", "sizeBytes": %d },
            { "id": "embed", "pathInPack": "payload/models/embed.gguf", "sha256": "%s", "sizeBytes": %d }
          ],
          "assets": [
            { "role": "model.chat", "fileId": "chat" },
            { "role": "model.embedding", "fileId": "embed" }
          ]
        }
        """
            .formatted(chatSha, chatBytes.length, embedSha, embedBytes.length);
    byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
    String manifestSha = sha256Hex(manifestBytes);

    Path zip = tmp.resolve("pack-extra.zip");
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("pack-manifest.v1.json", manifestBytes);
    entries.put("payload/models/chat.gguf", chatBytes);
    entries.put("payload/models/embed.gguf", embedBytes);
    entries.put("payload/models/extra.txt", "nope".getBytes(StandardCharsets.UTF_8));
    writeZip(zip, entries);

    AiPackImportService svc =
        new AiPackImportService(
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            new EnterprisePolicyServiceImpl(),
            new PackAllowlistService(Set.of(manifestSha)));

    svc.startImport(zip, false);
    AiPackImportStatus st = awaitDone(svc);
    assertEquals("failed", st.state);
    assertEquals("PACK_EXTRA_FILE", st.errorCode);
  }

  @Test
  void zipImportAcceptsUtf8BomInManifest() throws Exception {
    setHome(tmp);

    byte[] chatBytes = "chat-model".getBytes(StandardCharsets.UTF_8);
    byte[] embedBytes = "embed-model".getBytes(StandardCharsets.UTF_8);
    String chatSha = sha256Hex(chatBytes);
    String embedSha = sha256Hex(embedBytes);

    String manifestJson =
        """
        {
          "schemaVersion": 1,
          "packId": "justsearch.ai-pack.v2.models.default",
          "packVersion": "2.0.0",
          "kind": "models",
          "createdAt": "2025-12-23T00:00:00Z",
          "requiresAppMin": "1.0.0",
          "files": [
            { "id": "chat", "pathInPack": "payload/models/chat.gguf", "sha256": "%s", "sizeBytes": %d },
            { "id": "embed", "pathInPack": "payload/models/embed.gguf", "sha256": "%s", "sizeBytes": %d }
          ],
          "assets": [
            { "role": "model.chat", "fileId": "chat" },
            { "role": "model.embedding", "fileId": "embed" }
          ]
        }
        """
            .formatted(chatSha, chatBytes.length, embedSha, embedBytes.length);

    byte[] jsonBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
    byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    byte[] manifestBytes = new byte[bom.length + jsonBytes.length];
    System.arraycopy(bom, 0, manifestBytes, 0, bom.length);
    System.arraycopy(jsonBytes, 0, manifestBytes, bom.length, jsonBytes.length);

    String manifestSha = sha256Hex(manifestBytes);

    Path zip = tmp.resolve("pack-bom.zip");
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("pack-manifest.v1.json", manifestBytes);
    entries.put("payload/models/chat.gguf", chatBytes);
    entries.put("payload/models/embed.gguf", embedBytes);
    writeZip(zip, entries);

    AiPackImportService svc =
        new AiPackImportService(
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            new EnterprisePolicyServiceImpl(),
            new PackAllowlistService(Set.of(manifestSha)));

    svc.startImport(zip, false);
    AiPackImportStatus st = awaitDone(svc);
    assertEquals("completed", st.state, st.errorCode + " " + st.message);
  }

  @Test
  void preventsSilentDowngradeUnlessExplicit() throws Exception {
    setHome(tmp);

    // First install v2.0.0
    var first = makeZipPack(tmp.resolve("pack-v2.zip"), "2.0.0");
    AiPackImportService svc1 =
        new AiPackImportService(
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            new EnterprisePolicyServiceImpl(),
            new PackAllowlistService(Set.of(first.manifestSha)));
    svc1.startImport(first.zipPath, false);
    AiPackImportStatus st1 = awaitDone(svc1);
    assertEquals("completed", st1.state, st1.errorCode + " " + st1.message);

    // Then attempt v1.0.0 without allowDowngrade
    var second = makeZipPack(tmp.resolve("pack-v1.zip"), "1.0.0");
    AiPackImportService svc2 =
        new AiPackImportService(
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            new EnterprisePolicyServiceImpl(),
            new PackAllowlistService(Set.of(second.manifestSha)));
    svc2.startImport(second.zipPath, false);
    AiPackImportStatus st2 = awaitDone(svc2);
    assertEquals("failed", st2.state);
    assertEquals("PACK_DOWNGRADE_BLOCKED", st2.errorCode);
  }

  @Test
  void preflightZipComputesManifestDigestAndDoesNotWriteToAiHome() throws Exception {
    setHome(tmp);

    byte[] chatBytes = "chat-model".getBytes(StandardCharsets.UTF_8);
    byte[] embedBytes = "embed-model".getBytes(StandardCharsets.UTF_8);
    String chatSha = sha256Hex(chatBytes);
    String embedSha = sha256Hex(embedBytes);

    String manifestJson =
        """
        {
          "schemaVersion": 1,
          "packId": "justsearch.ai-pack.v2.models.default",
          "packVersion": "2.0.0",
          "kind": "models",
          "createdAt": "2025-12-23T00:00:00Z",
          "requiresAppMin": "1.0.0",
          "files": [
            { "id": "chat", "pathInPack": "payload/models/chat.gguf", "sha256": "%s", "sizeBytes": %d },
            { "id": "embed", "pathInPack": "payload/models/embed.gguf", "sha256": "%s", "sizeBytes": %d }
          ],
          "assets": [
            { "role": "model.chat", "fileId": "chat" },
            { "role": "model.embedding", "fileId": "embed" }
          ]
        }
        """
            .formatted(chatSha, chatBytes.length, embedSha, embedBytes.length);

    byte[] jsonBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
    byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    byte[] manifestBytes = new byte[bom.length + jsonBytes.length];
    System.arraycopy(bom, 0, manifestBytes, 0, bom.length);
    System.arraycopy(jsonBytes, 0, manifestBytes, bom.length, jsonBytes.length);
    String manifestSha = sha256Hex(manifestBytes);

    Path zip = tmp.resolve("pack-preflight.zip");
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("pack-manifest.v1.json", manifestBytes);
    entries.put("payload/models/chat.gguf", chatBytes);
    entries.put("payload/models/embed.gguf", embedBytes);
    writeZip(zip, entries);

    AiPackImportService svc =
        new AiPackImportService(
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            new EnterprisePolicyServiceImpl(),
            new PackAllowlistService(Set.of()));

    AiPackPreflightResult r = svc.preflight(zip);
    assertEquals("justsearch.ai-pack.v2.models.default", r.packId());
    assertEquals("2.0.0", r.packVersion());
    assertEquals(manifestSha, r.manifestSha256());

    assertFalse(Files.exists(tmp.resolve("models")));
    assertFalse(Files.exists(tmp.resolve("installed-packs.v1.json")));
    assertFalse(Files.exists(tmp.resolve("pack-import-state.json")));
  }

  @Test
  void preflightFolderComputesManifestDigestAndDoesNotWriteToAiHome() throws Exception {
    setHome(tmp);

    byte[] chatBytes = "chat-model".getBytes(StandardCharsets.UTF_8);
    byte[] embedBytes = "embed-model".getBytes(StandardCharsets.UTF_8);
    String chatSha = sha256Hex(chatBytes);
    String embedSha = sha256Hex(embedBytes);

    String manifestJson =
        """
        {
          "schemaVersion": 1,
          "packId": "justsearch.ai-pack.v2.models.default",
          "packVersion": "2.0.0",
          "kind": "models",
          "createdAt": "2025-12-23T00:00:00Z",
          "requiresAppMin": "1.0.0",
          "files": [
            { "id": "chat", "pathInPack": "payload/models/chat.gguf", "sha256": "%s", "sizeBytes": %d },
            { "id": "embed", "pathInPack": "payload/models/embed.gguf", "sha256": "%s", "sizeBytes": %d }
          ],
          "assets": [
            { "role": "model.chat", "fileId": "chat" },
            { "role": "model.embedding", "fileId": "embed" }
          ]
        }
        """
            .formatted(chatSha, chatBytes.length, embedSha, embedBytes.length);
    byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
    String manifestSha = sha256Hex(manifestBytes);

    Path packRoot = tmp.resolve("pack-folder");
    Files.createDirectories(packRoot.resolve("payload/models"));
    Files.write(packRoot.resolve("pack-manifest.v1.json"), manifestBytes);
    Files.write(packRoot.resolve("payload/models/chat.gguf"), chatBytes);
    Files.write(packRoot.resolve("payload/models/embed.gguf"), embedBytes);

    AiPackImportService svc =
        new AiPackImportService(
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            new EnterprisePolicyServiceImpl(),
            new PackAllowlistService(Set.of()));

    AiPackPreflightResult r = svc.preflight(packRoot);
    assertEquals("justsearch.ai-pack.v2.models.default", r.packId());
    assertEquals("2.0.0", r.packVersion());
    assertEquals(manifestSha, r.manifestSha256());

    assertFalse(Files.exists(tmp.resolve("models")));
    assertFalse(Files.exists(tmp.resolve("installed-packs.v1.json")));
    assertFalse(Files.exists(tmp.resolve("pack-import-state.json")));
  }

  private record BuiltPack(Path zipPath, String manifestSha) {}

  private BuiltPack makeZipPack(Path zipPath, String packVersion) throws Exception {
    byte[] chatBytes = ("chat-" + packVersion).getBytes(StandardCharsets.UTF_8);
    byte[] embedBytes = ("embed-" + packVersion).getBytes(StandardCharsets.UTF_8);
    String chatSha = sha256Hex(chatBytes);
    String embedSha = sha256Hex(embedBytes);

    String manifestJson =
        """
        {
          "schemaVersion": 1,
          "packId": "justsearch.ai-pack.v2.models.default",
          "packVersion": "%s",
          "kind": "models",
          "createdAt": "2025-12-23T00:00:00Z",
          "requiresAppMin": "1.0.0",
          "files": [
            { "id": "chat", "pathInPack": "payload/models/chat.gguf", "sha256": "%s", "sizeBytes": %d },
            { "id": "embed", "pathInPack": "payload/models/embed.gguf", "sha256": "%s", "sizeBytes": %d }
          ],
          "assets": [
            { "role": "model.chat", "fileId": "chat" },
            { "role": "model.embedding", "fileId": "embed" }
          ]
        }
        """
            .formatted(packVersion, chatSha, chatBytes.length, embedSha, embedBytes.length);
    byte[] manifestBytes = manifestJson.getBytes(StandardCharsets.UTF_8);
    String manifestSha = sha256Hex(manifestBytes);

    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("pack-manifest.v1.json", manifestBytes);
    entries.put("payload/models/chat.gguf", chatBytes);
    entries.put("payload/models/embed.gguf", embedBytes);
    writeZip(zipPath, entries);
    return new BuiltPack(zipPath, manifestSha);
  }

  private void setHome(Path home) {
    prevHome = System.getProperty("justsearch.home");
    System.setProperty("justsearch.home", home.toAbsolutePath().toString());
    // Also align data dir for any callers that fall back to PlatformPaths.
    System.setProperty("justsearch.data.dir", home.toAbsolutePath().toString());
  }

  private static void writeZip(Path zip, Map<String, byte[]> entries) throws Exception {
    try (OutputStream fos = Files.newOutputStream(zip);
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {
      for (var e : entries.entrySet()) {
        ZipEntry ze = new ZipEntry(e.getKey());
        zos.putNextEntry(ze);
        zos.write(e.getValue());
        zos.closeEntry();
      }
    }
  }

  private static String sha256Hex(byte[] bytes) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update(bytes);
    return HexFormat.of().formatHex(digest.digest());
  }

  private static AiPackImportStatus awaitDone(AiPackImportService svc) throws Exception {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      AiPackImportStatus st = svc.getStatus();
      if (!"running".equalsIgnoreCase(st.state)) {
        // Wait for the import thread to fully terminate before returning.
        // This prevents file-locking issues on Windows during @TempDir cleanup.
        svc.awaitThreadCompletion(1000);
        return st;
      }
      Thread.sleep(50);
    }
    fail("Timed out waiting for pack import to finish");
    return svc.getStatus();
  }

}

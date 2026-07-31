package io.justsearch.app.services.ai.pack;

import io.justsearch.app.api.AiPackPreflightResult;
import io.justsearch.app.api.AiPackImportStatus;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.OperationLeaseSnapshot;
import io.justsearch.app.services.policy.EnterprisePolicyServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
  void stalePersistedImportStatusResetsToIdleOnStartup() throws Exception {
    setHome(tmp);
    Files.writeString(
        tmp.resolve("pack-import-state.json"),
        """
        {"state":"running","phase":"install","message":"stale","errorCode":"",
         "bytesTotal":100,"bytesDone":40,"startedAtEpochMs":1,"updatedAtEpochMs":2}
        """);

    AiPackImportService service =
        new AiPackImportService(
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            new EnterprisePolicyServiceImpl(),
            new PackAllowlistService(Set.of()));

    AiPackImportStatus status = service.getStatus();
    assertEquals("idle", status.state);
    assertEquals("", status.phase);
    assertEquals(0, status.bytesDone);
    assertTrue(Files.readString(tmp.resolve("pack-import-state.json")).contains("\"state\" : \"idle\""));
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

    byte[] userOwnedBytes = "user-owned-model".getBytes(StandardCharsets.UTF_8);
    Path userOwnedModel = tmp.resolve("models").resolve("user-owned.gguf");
    Files.createDirectories(userOwnedModel.getParent());
    Files.write(userOwnedModel, userOwnedBytes);

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
    assertArrayEquals(userOwnedBytes, Files.readAllBytes(userOwnedModel));
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

  /**
   * Tempdoc 617: a pack import writes multi-GB assets on a background thread that outlives its HTTP
   * request, so the request-scoped mutation lease is already released while the write is running.
   * The import must hold an op-lease of its own for the thread's whole lifetime, or upgrade prepare
   * reports no blocker and the installer can launch mid-write.
   *
   * <p>Two properties are pinned separately because they fail in different ways:
   *
   * <ul>
   *   <li><b>registered synchronously</b> — the lease exists before {@code startImport} returns. If
   *       it were registered inside the thread instead, a prepare landing in that window would see
   *       no blocker.
   *   <li><b>released on the import thread</b> — proves the lease spans the background work rather
   *       than being released by the caller as soon as the thread was spawned.
   * </ul>
   */
  @Test
  void packImportHoldsAnOperationLeaseForTheImportThreadLifetime() throws Exception {
    setHome(tmp);

    List<String> events = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<String> releaseThread = new AtomicReference<>();

    AiPackImportService service =
        new AiPackImportService(
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            new EnterprisePolicyServiceImpl(),
            new PackAllowlistService(Set.of()));
    service.setOperationLeaseService(
        recordingLeases(events, releaseThread, Thread.currentThread().getName()));

    // Any pack path is fine: the lease contract must hold whether the import succeeds or fails.
    Path pack = tmp.resolve("nonexistent-pack.zip");
    service.startImport(pack, false);

    assertEquals(
        List.of("register:ai.pack-import"),
        List.copyOf(events),
        "lease must be registered before startImport returns, not inside the import thread");

    awaitDone(service);
    service.awaitThreadCompletion(5_000);

    assertEquals(2, events.size(), "lease must be released exactly once: " + events);
    assertTrue(events.get(1).startsWith("release:"), "second event must be the release: " + events);
    assertNotEquals(
        Thread.currentThread().getName(),
        releaseThread.get(),
        "lease must be released by the import thread, not the caller — releasing on the caller "
            + "would end the lease while the background write is still in flight");
  }

  private static OperationLeaseService recordingLeases(
      List<String> events, AtomicReference<String> releaseThread, String callerThread) {
    return new OperationLeaseService() {
      @Override
      public OperationLeaseHandle register(
          String opClass,
          OpCriticality criticality,
          long expectedDurationSec,
          Map<String, Object> md) {
        events.add("register:" + opClass);
        return new OperationLeaseHandle() {
          private final AtomicBoolean released = new AtomicBoolean(false);

          @Override
          public String opId() {
            return "test-op";
          }

          @Override
          public String opClass() {
            return opClass;
          }

          @Override
          public void renew() {}

          @Override
          public void release(OpLeaseOutcome outcome) {
            if (released.compareAndSet(false, true)) {
              events.add("release:" + outcome);
              releaseThread.set(Thread.currentThread().getName());
            }
          }

          @Override
          public void close() {
            release(OpLeaseOutcome.SUCCESS);
          }
        };
      }

      @Override
      public OperationLeaseSnapshot freezeAdmission(String reason) {
        return snapshot();
      }

      @Override
      public OperationLeaseSnapshot snapshot() {
        return new OperationLeaseSnapshot(false, "", "", List.of());
      }

      @Override
      public void releaseAdmission(String preparationId) {}
    };
  }
}

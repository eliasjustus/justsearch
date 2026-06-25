package io.justsearch.app.services.ai.pack;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.ApiErrorCode;
import org.junit.jupiter.api.Test;

class AiPackValidatorTest {

  @Test
  void validModelsOnlyManifestPasses() {
    AiPackManifestV1 m = new AiPackManifestV1();
    m.schemaVersion = 1;
    m.packId = "justsearch.ai-pack.v2.models.default";
    m.packVersion = "2.0.0";
    m.kind = "models";

    AiPackManifestV1.FileEntry chat = new AiPackManifestV1.FileEntry();
    chat.id = "chat";
    chat.pathInPack = "payload/models/chat.gguf";
    chat.sha256 = "67d1659bfe71b89d50b45a4ad1a9e5b997e5bb16ce5da66a6a6167abd569e9e2";
    chat.sizeBytes = 123;

    AiPackManifestV1.FileEntry embed = new AiPackManifestV1.FileEntry();
    embed.id = "embed";
    embed.pathInPack = "payload/models/embed.gguf";
    embed.sha256 = "d4e388894e09cf3816e8b0896d81d265b55e7a9fff9ab03fe8bf4ef5e11295ac";
    embed.sizeBytes = 456;

    m.files = java.util.List.of(chat, embed);

    AiPackManifestV1.AssetEntry a1 = new AiPackManifestV1.AssetEntry();
    a1.role = "model.chat";
    a1.fileId = "chat";
    AiPackManifestV1.AssetEntry a2 = new AiPackManifestV1.AssetEntry();
    a2.role = "model.embedding";
    a2.fileId = "embed";
    m.assets = java.util.List.of(a1, a2);

    AiPackValidator.ValidationResult vr = AiPackValidator.validateModelsOnly(m);
    assertTrue(vr.ok(), vr.message());
  }

  @Test
  void rejectsUnsupportedKind() {
    AiPackManifestV1 m = new AiPackManifestV1();
    m.schemaVersion = 1;
    m.packId = "x";
    m.packVersion = "1.0.0";
    m.kind = "runtime";
    m.files = java.util.List.of();
    m.assets = java.util.List.of();
    AiPackValidator.ValidationResult vr = AiPackValidator.validateModelsOnly(m);
    assertFalse(vr.ok());
    assertEquals(ApiErrorCode.PACK_KIND_UNSUPPORTED, vr.errorCode());
  }

  @Test
  void rejectsPathNotUnderPayload() {
    AiPackManifestV1 m = new AiPackManifestV1();
    m.schemaVersion = 1;
    m.packId = "x";
    m.packVersion = "1.0.0";
    m.kind = "models";

    AiPackManifestV1.FileEntry f = new AiPackManifestV1.FileEntry();
    f.id = "chat";
    f.pathInPack = "models/chat.gguf";
    f.sha256 = "67d1659bfe71b89d50b45a4ad1a9e5b997e5bb16ce5da66a6a6167abd569e9e2";
    f.sizeBytes = 1;
    m.files = java.util.List.of(f);

    AiPackManifestV1.AssetEntry a = new AiPackManifestV1.AssetEntry();
    a.role = "model.chat";
    a.fileId = "chat";
    m.assets = java.util.List.of(a);

    AiPackValidator.ValidationResult vr = AiPackValidator.validateModelsOnly(m);
    assertFalse(vr.ok());
    assertEquals(ApiErrorCode.PACK_PATH_INVALID, vr.errorCode());
  }

  @Test
  void rejectsUnusedFilesInV2() {
    AiPackManifestV1 m = new AiPackManifestV1();
    m.schemaVersion = 1;
    m.packId = "x";
    m.packVersion = "1.0.0";
    m.kind = "models";

    AiPackManifestV1.FileEntry f1 = new AiPackManifestV1.FileEntry();
    f1.id = "chat";
    f1.pathInPack = "payload/models/chat.gguf";
    f1.sha256 = "67d1659bfe71b89d50b45a4ad1a9e5b997e5bb16ce5da66a6a6167abd569e9e2";
    f1.sizeBytes = 1;

    AiPackManifestV1.FileEntry f2 = new AiPackManifestV1.FileEntry();
    f2.id = "embed";
    f2.pathInPack = "payload/models/embed.gguf";
    f2.sha256 = "d4e388894e09cf3816e8b0896d81d265b55e7a9fff9ab03fe8bf4ef5e11295ac";
    f2.sizeBytes = 1;

    AiPackManifestV1.FileEntry f3 = new AiPackManifestV1.FileEntry();
    f3.id = "extra";
    f3.pathInPack = "payload/models/extra.gguf";
    f3.sha256 = "67d1659bfe71b89d50b45a4ad1a9e5b997e5bb16ce5da66a6a6167abd569e9e2";
    f3.sizeBytes = 1;

    m.files = java.util.List.of(f1, f2, f3);

    AiPackManifestV1.AssetEntry a1 = new AiPackManifestV1.AssetEntry();
    a1.role = "model.chat";
    a1.fileId = "chat";
    AiPackManifestV1.AssetEntry a2 = new AiPackManifestV1.AssetEntry();
    a2.role = "model.embedding";
    a2.fileId = "embed";
    m.assets = java.util.List.of(a1, a2);

    AiPackValidator.ValidationResult vr = AiPackValidator.validateModelsOnly(m);
    assertFalse(vr.ok());
    assertEquals(ApiErrorCode.PACK_UNUSED_FILES, vr.errorCode());
  }
}

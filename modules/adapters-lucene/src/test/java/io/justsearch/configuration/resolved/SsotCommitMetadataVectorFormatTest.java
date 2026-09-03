/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

final class SsotCommitMetadataVectorFormatTest {

  @Test
  @ResourceLock("ConfigStore.global")
  void metadataAndFingerprintFollowTheEffectiveQuantizationSelection() {
    ConfigStore previous = ConfigStore.globalOrNull();
    try {
      ConfigStore.setGlobal(new ConfigStore(ResolvedConfig.builder().build()));
      Map<String, Object> defaultMetadata = new SsotCommitMetadataSource().build();

      ResolvedConfig float32 =
          ResolvedConfig.builder()
              .putDefault("index.vector.quantization.enabled", "false")
              .build();
      ConfigStore.setGlobal(new ConfigStore(float32));
      Map<String, Object> float32Metadata = new SsotCommitMetadataSource().build();

      assertEquals("int8_sq", defaultMetadata.get("vector_format"));
      assertEquals("float32", float32Metadata.get("vector_format"));
      assertNotNull(defaultMetadata.get(IndexFingerprint.COMMIT_META_KEY));
      assertNotNull(float32Metadata.get(IndexFingerprint.COMMIT_META_KEY));
      assertNotEquals(
          defaultMetadata.get(IndexFingerprint.COMMIT_META_KEY),
          float32Metadata.get(IndexFingerprint.COMMIT_META_KEY),
          "the fingerprint must move with the vector format selected by the runtime");
    } finally {
      if (previous == null) {
        ConfigStore.clearGlobal();
      } else {
        ConfigStore.setGlobal(previous);
      }
    }
  }
}

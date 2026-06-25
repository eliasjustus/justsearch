package io.justsearch.ssot.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Guards the fingerprint computation against silent changes from Jackson version upgrades.
 * The expected hash is the canonical output of Jackson {@code ORDER_MAP_ENTRIES_BY_KEYS} for
 * the {@code content_all} analyzer descriptor.
 */
class SsotValidatorFingerprintTest {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  @Test
  void fingerprintMatchesKnownDescriptor() throws Exception {
    // content_all analyzer descriptor — locale-invariant ICU only (no per-locale synonyms),
    // post tempdoc 581 §13 collapse.
    ObjectNode desc = MAPPER.createObjectNode();
    desc.put("id", "content_all");
    desc.put("locale", "*");
    desc.put("provider", "icu");
    desc.putArray("components").add("icu");

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    MAPPER.writeValue(bos, desc);
    String canonical = bos.toString(StandardCharsets.UTF_8);

    // Verify canonical form is deterministic sorted-key JSON
    assertEquals(
        "{\"id\":\"content_all\",\"locale\":\"*\",\"provider\":\"icu\",\"components\":[\"icu\"]}",
        canonical);

    // Verify hash matches the value stored in analyzers.v1.json
    byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "ef02d97578260b3b981711916361278d85ca091d7c6aa2f7530ac01288bbc363",
        HexFormat.of().formatHex(hash));
  }
}

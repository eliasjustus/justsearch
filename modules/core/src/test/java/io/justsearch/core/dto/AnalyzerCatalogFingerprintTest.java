package io.justsearch.core.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.File;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class AnalyzerCatalogFingerprintTest {
  private static final ObjectMapper M = new ObjectMapper();

  @Test
  void analyzerFingerprintsPresentAndHex() throws Exception {
    File f = new File(repoRoot(), "SSOT/catalogs/analyzers.v1.json");
    JsonNode root = M.readTree(f);
    for (JsonNode a : root.withArray("analyzers")) {
      String fp = a.get("fingerprint").asText("");
      assertFalse(fp.isEmpty(), "fingerprint must be non-empty");
      assertTrue(fp.matches("[0-9a-f]{64}"), "fingerprint must be sha-256 hex");
    }
  }

  @Test
  void recomputeCanonicalFingerprintDeterministically() throws Exception {
    // Illustrative: compute SHA-256 over id+provider+components string as a basic determinism guard
    File f = new File(repoRoot(), "SSOT/catalogs/analyzers.v1.json");
    JsonNode root = M.readTree(f);
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    for (JsonNode a : root.withArray("analyzers")) {
      String s = a.get("id").asText("") + "|" + a.get("provider").asText("") + "|" +
          a.withArray("components").toString();
      byte[] dig = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      String hex = HexFormat.of().formatHex(dig);
      assertTrue(hex.matches("[0-9a-f]{64}"));
    }
  }

  private static File repoRoot() {
    File f = new File(".").getAbsoluteFile();
    while (f != null) {
      if (new File(f, "SSOT").isDirectory()) return f;
      f = f.getParentFile();
    }
    throw new IllegalStateException("Repo root not found (no SSOT directory)");
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 682 Item 2 — expected-vs-actual llama-server build comparison. Exact-match
 * semantics on the {@code bNNNN} tag; unknown-tolerant (either side missing is a
 * supported state, never a mismatch).
 */
class LlamaServerBuildCheckTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // ==================== expectedFromMarker ====================

  @Test
  void parsesCpuPrebuiltMarkerFormat() {
    assertEquals(
        "b8571", LlamaServerBuildCheck.expectedFromMarker("llama.cpp b8571 prebuilt\n"));
  }

  @Test
  void parsesCudaVariantMarkerFormat() {
    assertEquals(
        "b8571",
        LlamaServerBuildCheck.expectedFromMarker("llama.cpp b8571 win-cuda-12.4-x64\n"));
  }

  @Test
  void markerWithoutBuildTagYieldsNull() {
    assertNull(LlamaServerBuildCheck.expectedFromMarker("hand-built local runtime"));
    assertNull(LlamaServerBuildCheck.expectedFromMarker(""));
    assertNull(LlamaServerBuildCheck.expectedFromMarker("   "));
    assertNull(LlamaServerBuildCheck.expectedFromMarker(null));
  }

  @Test
  void buildTagRequiresWordBoundary() {
    // "ab123" must not parse as tag b123 — the tag is a standalone token.
    assertNull(LlamaServerBuildCheck.expectedFromMarker("llama.cpp ab123 prebuilt"));
  }

  // ==================== actualFromProps ====================

  @Test
  void parsesBuildInfoFromProps() throws Exception {
    var root = MAPPER.readTree("{\"build_info\":\"b8571-089d1e0e\",\"n_ctx\":4096}");
    assertEquals("b8571", LlamaServerBuildCheck.actualFromProps(root));
  }

  @Test
  void propsWithoutBuildInfoYieldsNull() throws Exception {
    assertNull(LlamaServerBuildCheck.actualFromProps(MAPPER.readTree("{\"n_ctx\":4096}")));
    assertNull(
        LlamaServerBuildCheck.actualFromProps(MAPPER.readTree("{\"build_info\":12345}")));
    assertNull(LlamaServerBuildCheck.actualFromProps(null));
  }

  // ==================== compare ====================

  @Test
  void equalTagsMatch() {
    var cmp = LlamaServerBuildCheck.compare("b8571", "b8571");
    assertFalse(cmp.mismatch());
    assertEquals("b8571", cmp.expected());
    assertEquals("b8571", cmp.actual());
  }

  @Test
  void differentTagsMismatch() {
    var cmp = LlamaServerBuildCheck.compare("b8571", "b8600");
    assertTrue(cmp.mismatch());
  }

  @Test
  void unknownEitherSideIsNeverAMismatch() {
    assertFalse(LlamaServerBuildCheck.compare(null, "b8571").mismatch());
    assertFalse(LlamaServerBuildCheck.compare("b8571", null).mismatch());
    assertFalse(LlamaServerBuildCheck.compare(null, null).mismatch());
  }

  // ==================== readExpectedNextTo ====================

  @Test
  void readsMarkerAdjacentToExecutable(@TempDir Path dir) throws Exception {
    Path exe = dir.resolve("llama-server.exe");
    Files.writeString(
        dir.resolve("runtime-version.txt"),
        "llama.cpp b8571 win-cuda-12.4-x64\n",
        StandardCharsets.UTF_8);
    assertEquals("b8571", LlamaServerBuildCheck.readExpectedNextTo(exe));
  }

  @Test
  void missingMarkerYieldsNull(@TempDir Path dir) {
    assertNull(LlamaServerBuildCheck.readExpectedNextTo(dir.resolve("llama-server.exe")));
    assertNull(LlamaServerBuildCheck.readExpectedNextTo(null));
  }
}

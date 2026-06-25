package io.justsearch.app.services.registry.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 560 §4c Phase B — wire-conformance pin for the {@code /api/registry/operations} entry
 * shape produced by {@link UIOperationEmitter}.
 *
 * <p>Phase B refactors the emitter to BUILD a typed {@code UIOperationView} record and serialize it
 * (one wire authority for the Operation shape, so the generated FE projection is faithful). This
 * golden capture-or-verify pins the EXACT current wire BEFORE that refactor, so the refactor cannot
 * silently change the bytes the FE consumes. Capture (first run, or after a deliberate wire change):
 * delete the golden + re-run. Verify (every other run): asserts byte-identical.
 */
@DisplayName("UIOperationView wire conformance")
final class UIOperationViewConformanceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String GOLDEN = "ui-operation-wire.golden.json";

  @Test
  @DisplayName("the emitted core-operation wire matches the committed golden")
  void coreOperationWireMatchesGolden() throws IOException {
    List<Map<String, Object>> entries =
        new UIOperationEmitter().emit(new CoreOperationCatalog(), List.of());
    String currentJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entries);

    Path goldenPath = goldenDir().resolve(GOLDEN);
    if (!Files.exists(goldenPath)) {
      Files.createDirectories(goldenPath.getParent());
      Files.writeString(goldenPath, currentJson + System.lineSeparator());
      fail("Golden captured at " + goldenPath + ". Re-run to verify (expected on first run).");
    }

    JsonNode golden = MAPPER.readTree(Files.readString(goldenPath));
    JsonNode current = MAPPER.readTree(currentJson);
    assertEquals(
        golden,
        current,
        "Operation wire diverged from the golden at "
            + goldenPath
            + ". The Phase-B emitter refactor must preserve the wire byte-for-byte; if a change is "
            + "intended, delete the golden and re-run to recapture.");
  }

  /** Resolve the test-resources dir (walk up to the module root). */
  private static Path goldenDir() {
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null && !Files.isDirectory(cursor.resolve("src/test/resources"))) {
      cursor = cursor.getParent();
    }
    Path base = cursor == null ? Path.of("src/test/resources") : cursor.resolve("src/test/resources");
    return base.resolve("registry/emitter");
  }
}

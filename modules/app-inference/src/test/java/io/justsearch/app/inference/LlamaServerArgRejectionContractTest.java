package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the runtime {@code --reasoning-budget} rejection detector to its declared register
 * ({@code governance/llama-server-arg-rejection.v1.json}, tempdoc 835).
 *
 * <p>The failure this prevents already happened once: a CI script matched b8185's full rejection
 * string ({@code invalid value}) while the bundled b8571 emits {@code invalid stoi argument}, so the
 * detector stopped firing on the build actually shipped and nobody noticed. One register, one Java
 * constant pinned to it here, and one JS reader that reads the register directly.
 */
@DisplayName("llama-server argument rejection: register <-> runtime detector")
class LlamaServerArgRejectionContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String REGISTER = "governance/llama-server-arg-rejection.v1.json";

  private static Path repoRoot() {
    Path p = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 10 && p != null; i++) {
      if (Files.exists(p.resolve(REGISTER))) {
        return p;
      }
      p = p.getParent();
    }
    throw new IllegalStateException("repo root with " + REGISTER + " not found from " + Paths.get("").toAbsolutePath());
  }

  private static JsonNode register() throws IOException {
    return MAPPER.readTree(repoRoot().resolve(REGISTER).toFile());
  }

  @Test
  @DisplayName("the runtime constant equals the declared marker — drift fails the build")
  void constantMatchesRegister() throws IOException {
    assertEquals(
        register().get("reasoningBudget").get("rejectionMarker").asString(),
        LlamaServerArgRejection.REASONING_BUDGET_MARKER);
  }

  @Test
  @DisplayName("every build's observed rejection wording is detected — prefix, not suffix")
  void everyObservedSuffixIsDetected() throws IOException {
    JsonNode suffixes = register().get("reasoningBudget").get("observedSuffixes");
    assertTrue(suffixes.size() >= 2, "register must record both known build wordings");
    for (JsonNode entry : suffixes) {
      String line =
          LlamaServerArgRejection.REASONING_BUDGET_MARKER + entry.get("suffix").asString();
      assertTrue(
          LlamaServerArgRejection.isReasoningBudgetRejection(line),
          "not detected for " + entry.get("build").asString() + ": " + line);
    }
  }

  @Test
  @DisplayName("b8571's actual wording is detected (the one the old suffix matcher missed)")
  void b8571WordingIsDetected() {
    assertTrue(
        LlamaServerArgRejection.isReasoningBudgetRejection(
            "error while handling argument \"--reasoning-budget\": invalid stoi argument"));
  }

  @Test
  @DisplayName("b8185's wording is still detected")
  void b8185WordingIsDetected() {
    assertTrue(
        LlamaServerArgRejection.isReasoningBudgetRejection(
            "error while handling argument \"--reasoning-budget\": invalid value"));
  }

  @Test
  @DisplayName("a rejection of a different argument is not a reasoning-budget rejection")
  void otherArgumentRejectionIsNotMatched() {
    assertFalse(
        LlamaServerArgRejection.isReasoningBudgetRejection(
            "error while handling argument \"--reasoning-format\": invalid value"));
    assertFalse(LlamaServerArgRejection.isReasoningBudgetRejection("load_model: loaded"));
    assertFalse(LlamaServerArgRejection.isReasoningBudgetRejection(null));
  }
}

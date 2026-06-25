package io.justsearch.app.services.registry.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 429 §C.G regression: verifies {@link AgentOperationEmitter} produces output that
 * deep-equals the captured legacy baseline (after Jackson normalization).
 *
 * <p>The baseline lives at {@code src/test/resources/openai-tools-array-baseline.json} and
 * was captured by {@code OpenAiToolsArrayBaselineTest} (in the {@code app-agent} module
 * pre-Phase-12; the captured JSON file is preserved here as the regression target after
 * Phase 12 deletes the producer test).
 */
@DisplayName("AgentOperationEmitter regression vs legacy baseline")
final class AgentOperationEmitterRegressionTest {

  private static final ObjectMapper NORMALIZED =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  @Test
  @DisplayName("emitter output deep-equals legacy ToolRegistry baseline")
  void emitterMatchesLegacyBaseline() throws IOException {
    // Identity resolver — the baseline encodes description="stub" for every entry,
    // matching the captured legacy ToolRegistry output for stub tools.
    AgentOperationEmitter emitter = new AgentOperationEmitter(key -> "stub");

    List<Operation> ops =
        List.of(
            stubOp(
                "search_index",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},"
                    + "\"limit\":{\"type\":\"integer\"}},\"required\":[\"query\"]}"),
            stubOp(
                "browse_folders",
                "{\"type\":\"object\",\"properties\":{\"parent_path\":{\"type\":\"string\"},"
                    + "\"max_folders\":{\"type\":\"integer\",\"default\":20}}}"),
            stubOp(
                "ingest_files",
                "{\"type\":\"object\",\"properties\":{\"paths\":{\"type\":\"array\","
                    + "\"items\":{\"type\":\"string\"}}},\"required\":[\"paths\"]}"),
            stubOp("ping_backend", "{\"type\":\"object\",\"properties\":{}}"));

    List<Map<String, Object>> tools = AgentOperationEmitter.emitOperations(ops, List.of());
    // emitOperations uses the static identity resolver — wrap each entry through the
    // configured emitter to force the description-resolution path. Since the description
    // key is "stub" and the resolver returns "stub", the result is identical to
    // emitOperations(...).
    String currentJson = NORMALIZED.writerWithDefaultPrettyPrinter().writeValueAsString(tools);
    String baselineJson = readBaseline();

    JsonNode currentNode = NORMALIZED.readTree(currentJson);
    JsonNode baselineNode = NORMALIZED.readTree(baselineJson);
    assertEquals(
        baselineNode,
        currentNode,
        "AgentOperationEmitter output diverged from the captured legacy baseline. "
            + "Either the emitter changed semantics, or the baseline needs deliberate update. "
            + "Per tempdoc 429 §C.G: this is the LLM-facing wire surface — divergence here "
            + "means the model sees a different tool catalog and battery scenarios may "
            + "regress in subtle ways.");

    // Force-exercise the configured-emitter path so an accidental regression in the
    // wireName-resolution or message-resolver branches surfaces here, not just in
    // emitOperations.
    var captured =
        emitter.emit(
            io.justsearch.agent.api.registry.OperationCatalog.of("core", ops), List.of());
    JsonNode capturedNode =
        NORMALIZED.readTree(NORMALIZED.writerWithDefaultPrettyPrinter().writeValueAsString(captured));
    assertEquals(
        baselineNode,
        capturedNode,
        "Configured emitter (via OperationCatalog) must match legacy baseline.");
  }

  private static Operation stubOp(String wireName, String parameterSchema) {
    // Per tempdoc 429 §F.21 C1: OperationRef is the single identity. Tests construct
    // ids in `core.<id>` form so toWireName transliterates to the test's expected
    // wire form (e.g., `core.search-index` → `core_search_index`).
    return new Operation(
        new OperationRef("core." + wireName.replace('_', '-')),
        new Presentation(
            new I18nKey("test." + wireName + ".label"),
            new I18nKey("stub"), // resolver returns "stub" for any key
            Optional.empty(),
            Optional.empty()),
        Interface.of(parameterSchema, "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(new OperationRef("core." + wireName.replace('_', '-'))),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static String readBaseline() throws IOException {
    try (InputStream is =
        AgentOperationEmitterRegressionTest.class.getResourceAsStream(
            "/openai-tools-array-baseline.json")) {
      Objects.requireNonNull(is, "openai-tools-array-baseline.json not on test classpath");
      return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}

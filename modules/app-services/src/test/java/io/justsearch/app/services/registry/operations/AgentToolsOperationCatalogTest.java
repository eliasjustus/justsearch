/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.AvailabilityExpression;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.app.services.registry.emitter.AgentOperationEmitter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 868 §A.2 / §B.4 — the catalog Interface is the LLM-facing schema, and it had drifted from
 * what the executor actually honours.
 *
 * <p>The drift was invisible and total: {@code AgentOperationEmitter} projects {@code
 * op.intf().inputs()} to the model, this catalog declared only {@code query}/{@code limit}, and the
 * schema that DID declare {@code path_prefix} ({@code SearchTool.PARAMETER_SCHEMA}) is preserved for
 * unit tests with no production consumer. So the system prompt instructed the model to use a
 * parameter the model could not see, and across 37 recorded runs it was used zero times — while the
 * outward MCP surface declared it and external agents had the scoping the in-app delegate did not.
 * These assertions pin the coupling that was missing, in both directions: what the catalog declares,
 * and what the emitter actually hands the model.
 */
final class AgentToolsOperationCatalogTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static Operation op(OperationRef ref) {
    return new AgentToolsOperationCatalog()
        .definitions().stream()
        .filter(o -> o.id().equals(ref))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no operation declared for " + ref.value()));
  }

  private static JsonNode inputs(OperationRef ref) {
    return MAPPER.readTree(op(ref).intf().inputs());
  }

  @Test
  @DisplayName("the search-index Interface declares every property SearchTool honours from the model")
  void searchIndexInterfaceDeclaresEveryModelFacingProperty() {
    JsonNode properties = inputs(AgentToolsOperationCatalog.SEARCH_INDEX).get("properties");
    // `query`, `limit` and `path_prefix` are the three keys SearchTool reads from LLM-authored
    // arguments (SearchTool.java:198, :210, :222). `mode`/`pipeline` are deliberately NOT declared —
    // they are internal retrieval levers, not a capability the model should steer — and `docIds` is
    // merged server-side by AgentToolDispatcher, never chosen by the model.
    for (String declared : List.of("query", "limit", "path_prefix")) {
      assertTrue(
          properties.has(declared),
          "the catalog Interface must declare '"
              + declared
              + "' — it is what the emitter shows the model, and SearchTool honours the key");
    }
    assertEquals(
        "string",
        properties.get("path_prefix").get("type").asText(),
        "path_prefix is an absolute folder path");
    assertFalse(
        properties.get("path_prefix").get("description").asText().isBlank(),
        "an undescribed parameter is a parameter the model will not use");
  }

  @Test
  @DisplayName("the emitter shows the model path_prefix — the projection, not just the declaration")
  void emitterProjectsPathPrefixToTheModel() {
    // The declaration above is only half the fix: AgentOperationEmitter.toOpenAiTool sets
    // `parameters` from op.intf().inputs(), so this asserts the key survives the projection the
    // model actually reads rather than trusting the catalog in isolation.
    List<Map<String, Object>> tools =
        AgentOperationEmitter.emitOperations(
            List.of(op(AgentToolsOperationCatalog.SEARCH_INDEX)), List.of());
    assertEquals(1, tools.size());
    String rendered = MAPPER.writeValueAsString(tools.get(0));
    assertTrue(
        rendered.contains("path_prefix"),
        "the projected tool the LLM sees must carry path_prefix; got " + rendered);
  }

  @Test
  @DisplayName("868 §B.2 — core.read-document is declared with the read tool's three arguments")
  void readDocumentInterfaceDeclaresItsArguments() {
    JsonNode intf = inputs(AgentToolsOperationCatalog.READ_DOCUMENT);
    JsonNode properties = intf.get("properties");
    for (String declared : List.of("path", "offset_chars", "max_chars")) {
      assertTrue(properties.has(declared), "read-document must declare '" + declared + "'");
    }
    assertEquals("string", properties.get("path").get("type").asText());
    assertEquals("integer", properties.get("offset_chars").get("type").asText());
    assertEquals("integer", properties.get("max_chars").get("type").asText());
    assertEquals(
        "path",
        intf.get("required").get(0).asText(),
        "only the document path is required; the paging arguments default");
  }

  @Test
  @DisplayName("868 §B.2 — read-document is LOW risk, unconfirmed, unaudited, and index-gated")
  void readDocumentPolicyMatchesSearch() {
    Operation read = op(AgentToolsOperationCatalog.READ_DOCUMENT);
    Operation search = op(AgentToolsOperationCatalog.SEARCH_INDEX);

    assertEquals(RiskTier.LOW, read.policy().risk());
    assertInstanceOf(ConfirmStrategy.None.class, read.policy().confirm());
    assertEquals(AuditPolicy.NONE, read.policy().audit());
    assertEquals(
        java.util.Set.of(ExecutorTag.AGENT),
        read.executors(),
        "the read tool is agent-only, like search");

    // The readable universe is exactly the indexed corpus, so the read tool must be offered on
    // precisely the condition search is: available unless the index is not serving. A read offered
    // while the Worker is down would fail every call with an infrastructure error the model would
    // read as "the document does not exist".
    Optional<AvailabilityExpression> readWhen = read.availability().expression();
    assertTrue(readWhen.isPresent(), "read-document must declare an availability expression");
    assertEquals(
        search.availability().expression().orElseThrow(),
        readWhen.get(),
        "read-document must be gated on the SAME index.unavailable condition as search");
  }
}

package io.justsearch.app.services.mcphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.services.registry.emitter.AgentOperationEmitter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The <b>runtime-witness</b> half of the NonEmpty&lt;ConsumerHook&gt; keystone for the AGENT audience
 * (tempdoc 560 §7 — the witness is audience-dispatched; AGENT = prompt-construction). A static gate
 * proves a declaration <i>has</i> a consumer; this proves the declared consumer (the agent loop)
 * <i>actually receives the data</i>: an MCP-host operation is genuinely emitted into the model's tool
 * vocabulary by the production {@link AgentOperationEmitter}, with a model-meaningful name,
 * description, and parameter schema. "Substrate exists" ≠ "consumer renders it" (§7) — this is the
 * test that distinguishes them.
 */
class McpHostEmitWitnessTest {

  private static McpHostService connectedService() {
    McpHostService service =
        new McpHostService(
            List.of(new McpServerConfig("reference", List.of("noop"))),
            server -> new McpClient(new FakeMcpTransport()));
    service.connect();
    return service;
  }

  @Test
  @SuppressWarnings("unchecked")
  void agentLoopConsumerActuallyReceivesTheMcpTool() {
    McpHostService service = connectedService();
    OperationCatalog catalog = OperationCatalog.of("core", service.operations());

    // The production projection the agent loop uses to build the LLM tool list.
    List<Map<String, Object>> tools = new AgentOperationEmitter().emit(catalog, null);

    String echoWire = OperationCatalog.toWireName(new OperationRef("vendor.mcphost.reference-echo"));
    Map<String, Object> echoFn =
        tools.stream()
            .map(t -> (Map<String, Object>) t.get("function"))
            .filter(fn -> echoWire.equals(fn.get("name")))
            .findFirst()
            .orElse(null);

    assertNotNull(echoFn, "the declared agent-loop consumer must actually receive the MCP tool");
    // Model sees a meaningful description (resolved from the MCP tool description), not a raw key.
    assertEquals("Echo back the provided message", echoFn.get("description"));
    // And the MCP inputSchema reached the model as the tool parameters.
    Map<String, Object> params = (Map<String, Object>) echoFn.get("parameters");
    assertNotNull(params);
    assertTrue(params.toString().contains("message"), params.toString());
  }

  @Test
  @SuppressWarnings("unchecked")
  void allProjectedToolsAreWitnessedNotJustOne() {
    McpHostService service = connectedService();
    OperationCatalog catalog = OperationCatalog.of("core", service.operations());
    List<Map<String, Object>> tools = new AgentOperationEmitter().emit(catalog, null);

    long emitted =
        tools.stream()
            .map(t -> (Map<String, Object>) t.get("function"))
            .map(fn -> (String) fn.get("name"))
            .filter(name -> name != null && name.startsWith("vendor_mcphost_reference"))
            .count();
    assertEquals(service.operations().size(), emitted, "every declared MCP op must reach the model");
  }
}

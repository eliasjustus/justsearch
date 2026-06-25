package io.justsearch.app.services.mcphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.TrustTier;
import org.junit.jupiter.api.Test;

/** Verifies the EXECUTABLE-axis projection (tempdoc 560 §4.4) builds a correctly-gated Operation. */
class McpToolProjectionTest {

  private static McpTool sampleTool() {
    return new McpTool(
        "search_web",
        "Search the public web",
        "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}");
  }

  @Test
  void refIsOperationRefSafeAndNamespaced() {
    Operation op = McpToolProjection.toOperation("reference", sampleTool());
    assertEquals("vendor.mcphost.reference-search-web", op.id().value());
  }

  @Test
  void riskAndConfirmGateMediumExternalCalls() {
    Operation op = McpToolProjection.toOperation("reference", sampleTool());
    assertEquals(RiskTier.MEDIUM, op.policy().risk());
    assertTrue(op.policy().confirm() instanceof ConfirmStrategy.Inline);
  }

  @Test
  void provenanceIsTrustedPluginNotCore() {
    Operation op = McpToolProjection.toOperation("reference", sampleTool());
    assertEquals(TrustTier.TRUSTED_PLUGIN, op.provenance().tier());
  }

  @Test
  void executableForAgentAudience() {
    Operation op = McpToolProjection.toOperation("reference", sampleTool());
    assertTrue(op.executors().contains(ExecutorTag.AGENT));
    assertEquals(Audience.AGENT, op.audience());
  }

  @Test
  void declaresNonEmptyAgentLoopConsumer() {
    Operation op = McpToolProjection.toOperation("reference", sampleTool());
    assertFalse(op.consumers().isEmpty(), "MCP-host ops must satisfy NonEmpty<ConsumerHook> by construction");
    assertEquals(1, op.consumers().size());
    ConsumerHook hook = op.consumers().get(0);
    assertTrue(hook instanceof ConsumerHook.Realized);
    assertEquals("agent-loop", hook.consumerId());
    assertEquals(Audience.AGENT, hook.audience());
  }

  @Test
  void inputSchemaBecomesOperationInputContract() {
    McpTool tool = sampleTool();
    Operation op = McpToolProjection.toOperation("reference", tool);
    assertEquals(tool.inputSchemaJson(), op.intf().inputs());
  }

  @Test
  void descriptionFlowsToPresentationForModelVisibility() {
    Operation op = McpToolProjection.toOperation("reference", sampleTool());
    // AgentOperationEmitter resolves descriptionKey -> prose; a literal description passes through.
    assertEquals("Search the public web", op.presentation().descriptionKey().value());
  }

  @Test
  void blankDescriptionGetsSyntheticFallback() {
    Operation op = McpToolProjection.toOperation("reference", new McpTool("noop", "", "{}"));
    assertTrue(op.presentation().descriptionKey().value().contains("noop"));
  }

  @Test
  void irregularToolNamesSanitizeToValidRefs() {
    Operation op = McpToolProjection.toOperation("reference", new McpTool("Get-Issue Details!", "x", "{}"));
    assertEquals("vendor.mcphost.reference-get-issue-details", op.id().value());
  }

  @Test
  void toPluginBundlesContributedOpsAsTrustedManifest() {
    var ops =
        java.util.Set.of(
            McpToolProjection.refFor("reference", "add"),
            McpToolProjection.refFor("reference", "echo"));
    io.justsearch.agent.api.registry.Plugin plugin = McpToolProjection.toPlugin("reference", ops);

    assertEquals("vendor.mcphost.reference", plugin.id().value());
    assertEquals(
        io.justsearch.agent.api.registry.TrustTier.TRUSTED_PLUGIN, plugin.trustTier());
    assertEquals(io.justsearch.agent.api.registry.Audience.AGENT, plugin.audience());
    assertEquals(2, plugin.contributions().operations().size());
    assertTrue(
        plugin.contributions().operations().contains(McpToolProjection.refFor("reference", "add")));
    assertFalse(plugin.consumers().isEmpty(), "the plugin manifest must satisfy NonEmpty<ConsumerHook>");
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.mcphost;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Plugin;
import io.justsearch.agent.api.registry.PluginContributions;
import io.justsearch.agent.api.registry.PluginRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.TrustTier;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Projects an external {@link McpTool} onto the canonical {@link Operation} declaration — the
 * EXECUTABLE axis of the unified extension substrate (tempdoc 560 §4.4). This is Path B: a real
 * {@code Operation} that flows through {@code OperationExecutorImpl} and the trust lattice, NOT the
 * gate-bypassing {@code VirtualOperationStore} path.
 *
 * <p>Trust + gate by construction:
 *
 * <ul>
 *   <li><b>{@code TrustTier.TRUSTED_PLUGIN}</b> provenance — the host owns truth (§4.5); the tool is
 *       a contribution, never core authority.
 *   <li><b>{@code RiskTier.MEDIUM} + {@code ConfirmStrategy.Inline}</b> — an external side-effecting
 *       call. Under any UNTRUSTED dispatch tier (AGENT_LOOP or MCP) the {@code (UNTRUSTED ×
 *       MEDIUM)} lattice cell yields a confirmation gate, so the model cannot silently invoke it.
 *   <li><b>{@code consumers = [Realized("agent-loop", AGENT)]}</b> — the NonEmpty&lt;ConsumerHook&gt;
 *       keystone (§5/§6, P1) satisfied <i>by construction</i>: the agent loop is the declared
 *       consumer, and the runtime witness is that {@code AgentOperationEmitter} actually emits the
 *       tool into the model's vocabulary.
 * </ul>
 */
public final class McpToolProjection {
  /** Vendor namespace for all MCP-host operations ({@code vendor.\w+} per OperationRef grammar). */
  public static final String VENDOR = "vendor.mcphost";

  /** The declared consumer of every MCP-host operation: the agent loop (AGENT audience). */
  public static final ConsumerHook AGENT_LOOP_CONSUMER =
      new ConsumerHook.Realized("agent-loop", Audience.AGENT);

  private McpToolProjection() {}

  /** The deterministic OperationRef for a server's tool: {@code vendor.mcphost.<server>-<tool>}. */
  public static OperationRef refFor(String serverId, String toolName) {
    return new OperationRef(VENDOR + "." + sanitize(serverId) + "-" + sanitize(toolName));
  }

  /** Project a discovered tool onto a fully-declared {@link Operation}. */
  public static Operation toOperation(String serverId, McpTool tool) {
    OperationRef ref = refFor(serverId, tool.name());
    String description =
        tool.description().isBlank()
            ? "External MCP tool '" + tool.name() + "' from server '" + serverId + "'."
            : tool.description();
    return new Operation(
        ref,
        new Presentation(
            new I18nKey("MCP · " + tool.name()),
            new I18nKey(description),
            Optional.empty(),
            Optional.of("mcp")),
        // The MCP inputSchema is already JSON Schema — it becomes the operation's input contract
        // verbatim, which is exactly what AgentOperationEmitter projects as the tool `parameters`.
        Interface.of(tool.inputSchemaJson(), "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(ref),
        new Provenance(TrustTier.TRUSTED_PLUGIN, VENDOR, "1.0"),
        Set.of(ExecutorTag.AGENT),
        Audience.AGENT,
        List.of(AGENT_LOOP_CONSUMER));
  }

  /** The deterministic PluginRef for a server: {@code vendor.mcphost.<server>}. */
  public static PluginRef pluginRefFor(String serverId) {
    return new PluginRef(VENDOR + "." + sanitize(serverId));
  }

  /**
   * Project a connected MCP server onto a {@link Plugin} Manifest declaration (tempdoc 560 §4.1):
   * one COMPOSITION over the EXECUTABLE operations the server contributed. {@code TRUSTED_PLUGIN}
   * provenance (host owns truth, §4.5), AGENT audience, and the same agent-loop consumer the ops
   * declare — so the plugin itself satisfies NonEmpty&lt;ConsumerHook&gt; by construction.
   */
  public static Plugin toPlugin(String serverId, Set<OperationRef> operations) {
    PluginRef ref = pluginRefFor(serverId);
    return new Plugin(
        ref,
        new Presentation(
            new I18nKey("MCP server · " + serverId),
            new I18nKey("External MCP server '" + serverId + "' (" + operations.size() + " tool(s))."),
            Optional.empty(),
            Optional.of("mcp")),
        new Provenance(TrustTier.TRUSTED_PLUGIN, VENDOR, "1.0"),
        Audience.AGENT,
        PluginContributions.ofOperations(operations),
        List.of(AGENT_LOOP_CONSUMER));
  }

  private static String sanitize(String raw) {
    String cleaned =
        raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    return cleaned.isEmpty() ? "x" : cleaned;
  }
}

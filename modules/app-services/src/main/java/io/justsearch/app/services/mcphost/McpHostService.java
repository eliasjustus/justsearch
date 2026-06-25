/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.mcphost;

import io.justsearch.agent.api.registry.ContributionRegistry;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Plugin;
import io.justsearch.agent.api.registry.PluginCatalog;
import io.justsearch.agent.api.registry.PluginRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orchestrates the MCP-host first consumer (tempdoc 560 §6): for each configured external MCP
 * server, connect, discover tools, and project them onto canonical {@link Operation} declarations +
 * their executing {@link McpToolHandler}s.
 *
 * <p>The result feeds the existing substrate at the {@code OperationSubstrateInit} seam — the
 * projected operations merge into the agent-tools catalog (so the model sees them) and the handlers
 * register into the shared {@link HandlerRegistry} (so the executor can run them). No new dispatch
 * path: external MCP tools become EXECUTABLE entries through the one router under the one lattice
 * (§2, "AI is just another contributor").
 *
 * <p>Resilience: a server that fails to connect or list tools is logged and skipped; it never aborts
 * the others or destabilizes bootstrap.
 */
public final class McpHostService implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(McpHostService.class);

  private final List<McpServerConfig> servers;
  private final Function<McpServerConfig, McpClient> clientFactory;
  private final List<McpClient> openClients = new ArrayList<>();
  // The MCP-host is the first composer over the one contribution-registry mechanism (tempdoc 560
  // §4.2 / 507 KCS): each server installs its whole contribution set atomically and can be revoked.
  private final ContributionRegistry contributions = new ContributionRegistry();
  // Answers external servers' sampling/createMessage via the host LLM (tempdoc 560 Phase 1); null ⇒
  // clients reply not-supported (no hang). Settable post-connect (the LLM is wired in a later phase).
  private volatile Function<JsonNode, JsonNode> sampler;
  private boolean connected;

  public McpHostService(List<McpServerConfig> servers) {
    this(servers, McpHostService::defaultClient);
  }

  public McpHostService(List<McpServerConfig> servers, Function<McpServerConfig, McpClient> clientFactory) {
    this.servers = List.copyOf(Objects.requireNonNull(servers, "servers"));
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
  }

  /** Wire a host-LLM sampler so external servers' {@code sampling/createMessage} are answered. */
  public void setSampler(Function<JsonNode, JsonNode> sampler) {
    this.sampler = sampler;
    for (McpClient client : openClients) {
      client.setSamplingHandler(sampler);
    }
  }

  /** Connect to every configured server and build the projected operations + handlers (idempotent). */
  public synchronized void connect() {
    if (connected) {
      return;
    }
    for (McpServerConfig server : servers) {
      connectServer(server);
    }
    connected = true;
    log.info(
        "MCP-host: composed {} tool(s) from {} plugin(s)",
        contributions.operations().size(),
        contributions.plugins().size());
  }

  private void connectServer(McpServerConfig server) {
    McpClient client;
    try {
      client = clientFactory.apply(server);
      client.initialize();
    } catch (RuntimeException e) {
      log.warn("MCP-host: server '{}' failed to connect: {}", server.id(), e.toString());
      return;
    }
    openClients.add(client);
    if (sampler != null) {
      client.setSamplingHandler(sampler);
    }
    // notifications/tools/list_changed → re-project the server's tools into the registry (hot-swap).
    client.setToolsChangedListener(() -> projectAndInstall(server, client));
    projectAndInstall(server, client);
  }

  /** (Re)project one server's tools and atomically (re)install them as that server's plugin. */
  private synchronized void projectAndInstall(McpServerConfig server, McpClient client) {
    List<McpTool> tools;
    try {
      tools = client.listTools();
    } catch (RuntimeException e) {
      log.warn("MCP-host: server '{}' tools/list failed: {}", server.id(), e.toString());
      return;
    }
    List<Operation> serverOps = new ArrayList<>();
    Map<OperationRef, OperationHandler> serverHandlers = new LinkedHashMap<>();
    Set<OperationRef> serverOpRefs = new LinkedHashSet<>();
    for (McpTool tool : tools) {
      Operation op = McpToolProjection.toOperation(server.id(), tool);
      serverOps.add(op);
      serverOpRefs.add(op.id());
      serverHandlers.put(op.id(), new McpToolHandler(client, tool.name()));
    }
    // Idempotent re-projection: drop the prior set, then install the current one atomically (§4.2).
    PluginRef pluginId = McpToolProjection.pluginRefFor(server.id());
    boolean wasInstalled = contributions.isInstalled(pluginId);
    contributions.uninstall(pluginId);
    if (serverOps.isEmpty()) {
      return;
    }
    try {
      contributions.install(
          new ContributionRegistry.Installation(
              McpToolProjection.toPlugin(server.id(), serverOpRefs), serverOps, serverHandlers));
      if (wasInstalled) {
        log.info("MCP-host: server '{}' tools changed → re-projected {} tool(s)", server.id(), serverOps.size());
      }
    } catch (RuntimeException e) {
      log.warn("MCP-host: server '{}' contribution install rejected: {}", server.id(), e.toString());
    }
  }

  /** The composed MCP-host operations (empty until {@link #connect()} runs). */
  public List<Operation> operations() {
    return contributions.operations();
  }

  /**
   * The {@link Plugin} Manifest declarations — one per connected server, bundling that server's
   * projected operations (tempdoc 560 §4.1: a plugin is a COMPOSITION over primitive axes). Read
   * from the contribution registry that composes them.
   */
  public List<Plugin> plugins() {
    return contributions.plugins();
  }

  /** The MCP-host plugins as a {@link PluginCatalog} (empty namespace-"core" catalog if none). */
  public PluginCatalog pluginCatalog() {
    return PluginCatalog.of("core", contributions.plugins());
  }

  /** The composer's registry — the one contribution mechanism (§4.2) the MCP-host installs into. */
  public ContributionRegistry contributionRegistry() {
    return contributions;
  }

  /** Register every composed handler into the shared registry (call before OperationSubstrateInit). */
  public void registerHandlers(HandlerRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    contributions.handlers().forEach(registry::register);
  }

  /**
   * Reap the spawned MCP subprocesses when the JVM exits (child processes are not auto-killed on
   * Windows). Idempotent and best-effort; intended for the production bootstrap singleton.
   */
  public void installShutdownHook() {
    Runtime.getRuntime().addShutdownHook(new Thread(this::close, "mcp-host-shutdown"));
  }

  @Override
  public void close() {
    for (McpClient client : openClients) {
      try {
        client.close();
      } catch (RuntimeException e) {
        log.debug("MCP-host: error closing client: {}", e.toString());
      }
    }
  }

  /** Production client factory: a stdio subprocess transport per server. */
  public static McpClient defaultClient(McpServerConfig server) {
    StdioMcpTransport transport =
        new StdioMcpTransport(server.command(), server.env(), JsonMapper.builder().build());
    return new McpClient(transport);
  }
}

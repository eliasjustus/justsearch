package io.justsearch.app.services.mcphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationRef;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Verifies the MCP-host orchestrator connects, projects tools, and registers handlers resiliently. */
class McpHostServiceTest {

  private static McpClient fakeClient(McpServerConfig server) {
    return new McpClient(new FakeMcpTransport());
  }

  @Test
  void connectProjectsAllServerTools() {
    McpHostService service =
        new McpHostService(
            List.of(new McpServerConfig("reference", List.of("noop"))), McpHostServiceTest::fakeClient);
    service.connect();

    List<Operation> ops = service.operations();
    assertEquals(2, ops.size(), "fake server advertises echo + add");
    assertTrue(ops.stream().anyMatch(o -> o.id().value().equals("vendor.mcphost.reference-echo")));
    assertTrue(ops.stream().anyMatch(o -> o.id().value().equals("vendor.mcphost.reference-add")));
  }

  @Test
  void registerHandlersPopulatesRegistryForEachOp() {
    McpHostService service =
        new McpHostService(
            List.of(new McpServerConfig("reference", List.of("noop"))), McpHostServiceTest::fakeClient);
    service.connect();

    HandlerRegistry registry = new HandlerRegistry();
    service.registerHandlers(registry);

    for (Operation op : service.operations()) {
      assertTrue(registry.resolve(op.id()).isPresent(), "handler missing for " + op.id().value());
    }
  }

  @Test
  void connectEmitsOnePluginPerServerBundlingItsOps() {
    McpHostService service =
        new McpHostService(
            List.of(new McpServerConfig("reference", List.of("noop"))), McpHostServiceTest::fakeClient);
    service.connect();

    List<io.justsearch.agent.api.registry.Plugin> plugins = service.plugins();
    assertEquals(1, plugins.size());
    io.justsearch.agent.api.registry.Plugin plugin = plugins.get(0);
    assertEquals("vendor.mcphost.reference", plugin.id().value());
    // the plugin bundles exactly the ops the server contributed
    assertEquals(service.operations().size(), plugin.contributions().operations().size());
    assertTrue(service.pluginCatalog().findByIdValue("vendor.mcphost.reference").isPresent());
  }

  @Test
  void connectIsIdempotent() {
    McpHostService service =
        new McpHostService(
            List.of(new McpServerConfig("reference", List.of("noop"))), McpHostServiceTest::fakeClient);
    service.connect();
    int first = service.operations().size();
    service.connect();
    assertEquals(first, service.operations().size());
  }

  @Test
  void failingServerIsSkippedNotFatal() {
    Function<McpServerConfig, McpClient> factory =
        server -> {
          if (server.id().equals("broken")) {
            throw new McpException("cannot start");
          }
          return new McpClient(new FakeMcpTransport());
        };
    McpHostService service =
        new McpHostService(
            List.of(
                new McpServerConfig("broken", List.of("noop")),
                new McpServerConfig("reference", List.of("noop"))),
            factory);

    service.connect(); // must not throw

    List<Operation> ops = service.operations();
    assertFalse(ops.isEmpty(), "the healthy server's tools should still be projected");
    assertTrue(ops.stream().allMatch(o -> o.id().value().startsWith("vendor.mcphost.reference-")));
    for (Operation op : ops) {
      assertEquals(OperationRef.class, op.id().getClass());
    }
  }
}

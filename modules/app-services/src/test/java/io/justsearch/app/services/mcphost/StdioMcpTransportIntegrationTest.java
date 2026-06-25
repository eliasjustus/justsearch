package io.justsearch.app.services.mcphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end transport test: spawns the Node reference MCP server (scripts/mcp/reference-server.mjs)
 * and drives it through the real {@link StdioMcpTransport}. Skipped (not failed) when {@code node}
 * or the script is unavailable, so it never blocks a build that lacks Node — the deterministic unit
 * coverage lives in {@link McpClientTest}.
 */
class StdioMcpTransportIntegrationTest {
  private final ObjectMapper mapper = JsonMapper.builder().build();

  @Test
  void connectsToReferenceServerAndCallsTools() {
    Optional<String> node = findNode();
    Optional<Path> script = findReferenceServer();
    assumeTrue(node.isPresent(), "node not on PATH");
    assumeTrue(script.isPresent(), "reference-server.mjs not found");

    StdioMcpTransport transport =
        new StdioMcpTransport(List.of(node.get(), script.get().toString()));
    try (McpClient client = new McpClient(transport)) {
      client.initialize();
      assertTrue(client.isInitialized());

      List<McpTool> tools = client.listTools();
      assertEquals(4, tools.size(), "expected echo/add/reverse/get-image from the reference server");
      assertTrue(tools.stream().anyMatch(t -> t.name().equals("add")));

      ObjectNode addArgs = mapper.createObjectNode();
      addArgs.put("a", 2);
      addArgs.put("b", 40);
      McpToolResult sum = client.callTool("add", addArgs);
      assertFalse(sum.isError());
      assertEquals("42", sum.text());

      ObjectNode echoArgs = mapper.createObjectNode();
      echoArgs.put("message", "round-trip");
      assertEquals("round-trip", client.callTool("echo", echoArgs).text());

      ObjectNode revArgs = mapper.createObjectNode();
      revArgs.put("text", "abc");
      assertEquals("cba", client.callTool("reverse", revArgs).text());

      // Phase 1: a real-transport non-text content block (image) survives, not flattened away.
      McpToolResult image = client.callTool("get-image", mapper.createObjectNode());
      assertFalse(image.isError());
      assertTrue(image.hasNonTextContent(), "the image block must survive the real stdio transport");
      assertTrue(image.blocks().stream().anyMatch(McpContentBlock::isImage));
    }
  }

  private static Optional<String> findNode() {
    String pathEnv = System.getenv("PATH");
    if (pathEnv == null) {
      return Optional.empty();
    }
    String[] names = {"node.exe", "node"};
    for (String dir : pathEnv.split(File.pathSeparator)) {
      for (String name : names) {
        Path candidate = Paths.get(dir, name);
        if (Files.isRegularFile(candidate)) {
          return Optional.of(candidate.toString());
        }
      }
    }
    return Optional.empty();
  }

  private static Optional<Path> findReferenceServer() {
    Path dir = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 6 && dir != null; i++) {
      Path candidate = dir.resolve("scripts/mcp/reference-server.mjs");
      if (Files.isRegularFile(candidate)) {
        return Optional.of(candidate);
      }
      dir = dir.getParent();
    }
    return Optional.empty();
  }
}

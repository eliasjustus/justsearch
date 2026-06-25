package io.justsearch.app.services.mcphost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Verifies the MCP-host config loader: lenient parsing, safe defaults, never throws. */
class McpHostConfigTest {

  @Test
  void parsesValidServerList() {
    String json =
        "[{\"id\":\"reference\",\"command\":[\"node\",\"server.mjs\"],\"env\":{\"K\":\"V\"}}]";
    List<McpServerConfig> servers = McpHostConfig.parse(JsonMapper.builder().build().readTree(json));
    assertEquals(1, servers.size());
    assertEquals("reference", servers.get(0).id());
    assertEquals(List.of("node", "server.mjs"), servers.get(0).command());
    assertEquals("V", servers.get(0).env().get("K"));
  }

  @Test
  void skipsMalformedEntriesButKeepsValidOnes() {
    String json =
        "[{\"id\":\"BAD ID\",\"command\":[\"x\"]},{\"id\":\"good\",\"command\":[\"y\"]}]";
    List<McpServerConfig> servers = McpHostConfig.parse(JsonMapper.builder().build().readTree(json));
    assertEquals(1, servers.size());
    assertEquals("good", servers.get(0).id());
  }

  @Test
  void nonArrayJsonYieldsEmpty() {
    List<McpServerConfig> servers =
        McpHostConfig.parse(JsonMapper.builder().build().readTree("{\"id\":\"x\"}"));
    assertTrue(servers.isEmpty());
  }

  @Test
  void missingFileYieldsEmptyNotException() {
    assertTrue(McpHostConfig.fromFile(Path.of("does-not-exist-12345.json")).isEmpty());
  }

  @Test
  void readsFromFile(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("mcp-servers.json");
    Files.writeString(file, "[{\"id\":\"reference\",\"command\":[\"node\",\"s.mjs\"]}]");
    List<McpServerConfig> servers = McpHostConfig.fromFile(file);
    assertEquals(1, servers.size());
    assertEquals("reference", servers.get(0).id());
  }
}

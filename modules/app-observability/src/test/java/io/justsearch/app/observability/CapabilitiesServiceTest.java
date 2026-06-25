package io.justsearch.app.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Comparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CapabilitiesServiceTest {
  @TempDir Path tempDir;
  private Path repoRoot;

  @BeforeEach
  void setUp() throws IOException {
    repoRoot = tempDir.resolve("repo");
    Files.createDirectories(repoRoot);
    Path ssot = repoRoot.resolve("SSOT");
    Files.createDirectories(ssot.resolve("prompts/en"));
    writePrompt(ssot.resolve("prompts/en/intent.v1.json"));
  }

  @Test
  void capabilitiesReflectsPromptTemplates() {
    CapabilitiesService service = new CapabilitiesService(repoRoot);
    CapabilitiesService.CapabilitiesView view = service.capabilities();

    assertNotNull(view.schemaVersions());
    assertTrue(view.plugins().isEmpty());
    assertFalse(view.promptTemplates().isEmpty());
    assertTrue(
        view.promptTemplates().stream().anyMatch(t -> "intent".equals(t.taskId())));
    assertTrue("phase-7".equals(view.source().phase()));
  }

  @Test
  void promptTemplatesEmptyWhenDirectoryMissing() throws Exception {
    Path promptsDir = repoRoot.resolve("SSOT/prompts");
    try (java.util.stream.Stream<Path> walk = Files.walk(promptsDir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }
    CapabilitiesService service = new CapabilitiesService(repoRoot);
    CapabilitiesService.CapabilitiesView view = service.capabilities();

    assertTrue(view.promptTemplates().isEmpty());
  }

  @Test
  void invalidPromptTemplateThrows() throws Exception {
    Path prompt = repoRoot.resolve("SSOT/prompts/en/intent.v1.json");
    Files.writeString(prompt, "{ invalid json");
    CapabilitiesService service = new CapabilitiesService(repoRoot);

    assertThrows(IllegalStateException.class, service::capabilities);
  }

  @Test
  void promptTemplateFallsBackToFilenameWhenTaskIdMissing() throws Exception {
    Path prompt = repoRoot.resolve("SSOT/prompts/en/fallback.json");
    Files.writeString(prompt, "{ \"template\": \"Hello\" }");
    CapabilitiesService service = new CapabilitiesService(repoRoot);
    CapabilitiesService.CapabilitiesView view = service.capabilities();

    assertTrue(view.promptTemplates().stream().anyMatch(template -> template.taskId().equals("fallback")));
  }

  @Test
  void mustachePromptTemplatesAreLoaded() throws Exception {
    Path prompt =
        repoRoot.resolve("SSOT/prompts/en/intent/intent.v2.mustache");
    Files.createDirectories(prompt.getParent());
    String content =
        """
        {
          "template_id": "intent.v2",
          "schema_ver": "intent_v2",
          "default_locale": "en-US",
          "task_id": "intent",
          "template_ver": "v2",
          "required_params": ["query"]
        }
        ---
        {{query}}
        """;
    Files.writeString(prompt, content);
    CapabilitiesService service = new CapabilitiesService(repoRoot);
    CapabilitiesService.CapabilitiesView view = service.capabilities();

    assertTrue(
        view.promptTemplates().stream()
            .anyMatch(template -> "v2".equals(template.templateVer())));
  }

  @Test
  void capabilitiesPayloadMatchesSchema() throws Exception {
    Path schemaFile = Path.of("SSOT/schemas/config/capabilities-view.schema.json").toAbsolutePath();
    // Skip if running outside the repo root (e.g., CI with a different CWD)
    if (!Files.exists(schemaFile)) return;

    CapabilitiesService service = new CapabilitiesService(repoRoot);
    CapabilitiesService.CapabilitiesView view = service.capabilities();

    ObjectMapper mapper = new ObjectMapper();
    byte[] jsonBytes = mapper.writeValueAsBytes(view);
    tools.jackson.databind.JsonNode payload = mapper.readTree(jsonBytes);
    tools.jackson.databind.JsonNode schemaNode = mapper.readTree(schemaFile.toFile());

    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    var ctx = new SchemaContext(
        registry.getDialect(SpecificationVersion.DRAFT_2020_12.getDialectId()), registry);
    Schema schema = ctx.newSchema(
        SchemaLocation.of(schemaFile.toUri().toString()), schemaNode, null);
    var violations = schema.validate(payload);
    assertTrue(violations.isEmpty(),
        () -> "Capabilities payload failed schema validation:\n"
            + violations.stream().map(e -> "  - " + e.getMessage()).reduce("", (a, b) -> a + b + "\n"));
  }

  @Test
  void serverCapabilitiesAdvertisesHostSubApiContractVersions() {
    // Tempdoc 508 §11.8 / §13.8 — host.* keys for PluginHostApi sub-interfaces.
    // The map must include all currently-shipped sub-APIs so plugins can declare
    // per-sub-API contract requirements and have them validated by the existing
    // per-Category validator in PluginRegistry.assertCompatibleAgainstHostCategories.
    CapabilitiesService service = new CapabilitiesService(repoRoot);
    CapabilitiesService.CapabilitiesView view = service.capabilities();
    var versions = view.serverCapabilities().contractVersions();
    // Wire stays advertised (back-compat).
    assertTrue(versions.containsKey("wire"));
    // Host sub-interfaces — every sub-API exposed in plugin-types.ts must
    // declare a contract version here so plugins can target specific versions.
    assertTrue(versions.containsKey("host.data"), "host.data must be advertised");
    assertTrue(versions.containsKey("host.search"), "host.search must be advertised");
    assertTrue(versions.containsKey("host.navigation"), "host.navigation must be advertised");
    assertTrue(versions.containsKey("host.ui"), "host.ui must be advertised");
    assertTrue(versions.containsKey("host.discovery"), "host.discovery must be advertised");
    assertTrue(versions.containsKey("host.settings"), "host.settings must be advertised");
    assertTrue(versions.containsKey("host.platform"), "host.platform must be advertised");
    assertTrue(versions.containsKey("host.inspector"), "host.inspector must be advertised");
    assertTrue(versions.containsKey("host.theme"), "host.theme must be advertised");
    assertTrue(versions.containsKey("host.layout"), "host.layout must be advertised");
    assertTrue(versions.containsKey("host.utilities"), "host.utilities must be advertised");
    assertTrue(versions.containsKey("host.registration"), "host.registration must be advertised");
    // Tempdoc 508-followup §γ2: host.selection is a real sub-interface as of
    // followup-γ (boundary-typed snapshot view + per-tier composition for
    // setSelection / clearSelection). The §13.A3 removal was reversed when
    // the sub-interface was actually wired in HostApiImpl.
    assertTrue(versions.containsKey("host.selection"),
        "host.selection must be advertised — it is a real sub-interface as of followup-γ2");
    // Tempdoc 508-followup §ε1: host.ai promoted from 0.9 to 1.0 once
    // getSessionTranscript + getSessionMetadata landed and β1 fixed
    // shape-dispatch on the backend. No longer experimental.
    assertTrue(versions.containsKey("host.ai"), "host.ai must be advertised");
    assertEquals("1.0", versions.get("host.ai"),
        "host.ai must declare 1.0 after followup-ε1 promotion, was: " + versions.get("host.ai"));
  }

  private void writePrompt(Path path) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    var root = mapper.createObjectNode();
    root.put("task_id", "intent");
    root.put("template", "Hello");
    Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
  }
}

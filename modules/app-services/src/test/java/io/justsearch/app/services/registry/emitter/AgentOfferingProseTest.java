/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.services.bootstrap.phases.BootstrapHelpers;
import io.justsearch.app.services.conversation.CoreWorkflowCatalog;
import io.justsearch.app.services.conversation.WorkflowOperationProjection;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 876 §B.3 + §B.7 — the guard sits at the OFFERING seam, over the composed production
 * catalog set emitted through the PRODUCTION message resolver, because that is where the defects
 * lived: the two projected workflow tools reached the model with {@code
 * registry-workflow.research-brief.description} as their literal description (the resolver loaded
 * only the operation catalog), and three operation descriptions named a {@code browse_folders} wire
 * tool that does not exist plus a {@code mode} parameter the Interface deliberately withholds.
 *
 * <p>These are not fixture tests. Each one composes what production composes and asserts a property
 * of the prose the model actually reads, so a future catalog entry, a future workflow, or a
 * regression of the resolver back to one file turns them red without anyone remembering to extend a
 * list.
 */
final class AgentOfferingProseTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * A lowercase word carrying at least one underscore — the shape of a tool parameter name
   * ({@code path_prefix}, {@code offset_chars}) and of a wire tool name ({@code
   * core_browse_folders}) in this system.
   */
  private static final Pattern SNAKE_CASE = Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");

  /** A wire tool name as it appears in prose. */
  private static final Pattern WIRE_NAME = Pattern.compile("\\bcore_[a-z0-9_]+\\b");

  /**
   * The composed operation set production offers the model: both static base catalogs plus the
   * workflows projected onto agent tools against those operations. The de-duplication by ref
   * (first declaration winning) mirrors what {@code OperationCatalogComposition.installBaseCatalogs}
   * guarantees at boot — the base catalogs declare disjoint refs today ({@code
   * core.navigate-to-surface}'s single canonical declaration lives in {@code
   * AgentToolsOperationCatalog}; {@code CoreOperationCatalog} keeps only the ref constant, tempdoc
   * 560 WS4) — so it is a belt-and-braces guard that keeps this fixture honest if that ever
   * changes, not a live requirement.
   */
  private static List<Operation> composedOperations() {
    Map<OperationRef, Operation> byRef = new LinkedHashMap<>();
    for (Operation op : new CoreOperationCatalog().definitions()) {
      byRef.putIfAbsent(op.id(), op);
    }
    for (Operation op : new AgentToolsOperationCatalog().definitions()) {
      byRef.putIfAbsent(op.id(), op);
    }
    List<Operation> base = List.copyOf(byRef.values());
    List<Operation> composed = new ArrayList<>(base);
    composed.addAll(WorkflowOperationProjection.project(CoreWorkflowCatalog.catalog(), base));
    return List.copyOf(composed);
  }

  /** The production message resolver: the union loader the composition root wires into the emitter. */
  private static AgentOperationEmitter productionEmitter() {
    Properties messages = BootstrapHelpers.loadRegistryMessages();
    return new AgentOperationEmitter(key -> messages.getProperty(key, key));
  }

  /** Wire name -> the emitted OpenAI {@code function} object, in offering order. */
  private static Map<String, Map<String, Object>> offeredTools() {
    List<Operation> ops = composedOperations();
    OperationCatalog catalog = OperationCatalog.of("core", ops);
    Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
    for (Map<String, Object> tool : productionEmitter().emit(catalog, List.of())) {
      Object fn = tool.get("function");
      assertTrue(fn instanceof Map, "every emitted tool carries a function object; got " + tool);
      @SuppressWarnings("unchecked")
      Map<String, Object> function = (Map<String, Object>) fn;
      byName.put(String.valueOf(function.get("name")), function);
    }
    return byName;
  }

  /** Wire name -> the Operation it was projected from, for the offered subset. */
  private static Map<String, Operation> offeredOperations() {
    Set<String> offeredNames = offeredTools().keySet();
    Map<String, Operation> byName = new LinkedHashMap<>();
    for (Operation op : composedOperations()) {
      String wire = OperationCatalog.toWireName(op.id());
      if (offeredNames.contains(wire)) {
        byName.putIfAbsent(wire, op);
      }
    }
    return byName;
  }

  @Test
  @DisplayName("876 §B.3: every offered tool's description resolves — no raw i18n key reaches the model")
  void everyOfferedDescriptionResolves() {
    Map<String, Map<String, Object>> tools = offeredTools();
    Map<String, Operation> operations = offeredOperations();
    assertFalse(tools.isEmpty(), "the composed production catalog offers at least one tool");

    Map<String, String> unresolved = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Object>> entry : tools.entrySet()) {
      Operation op = operations.get(entry.getKey());
      if (op == null) {
        continue; // a virtual tool has no backing Operation; none are wired in this emitter.
      }
      String key = op.presentation().descriptionKey().value();
      String description = String.valueOf(entry.getValue().get("description"));
      if (key.equals(description)) {
        unresolved.put(entry.getKey(), key);
      }
    }
    assertEquals(
        Map.of(),
        unresolved,
        "each of these tools reached the model with its raw i18n key as the description. Either the"
            + " key has no entry in its message catalog, or the catalog holding it is not one of"
            + " BootstrapHelpers.loadRegistryMessages()'s resources (tempdoc 876 B.3 — that is"
            + " exactly how registry-workflow.* keys used to leak).");
  }

  @Test
  @DisplayName("876 §B.3: the offering includes a projected workflow — the check is not vacuous")
  void theOfferingCoversTheWorkflowNamespace() {
    // The workflow-projected tools are the ones the single-file resolver broke, so a green
    // everyOfferedDescriptionResolves() means nothing unless at least one of them is in the set it
    // walks. core.research-brief composes only LlmSteps, so it projects against any registry
    // (tempdoc 876 B.4); core.demo-compose composes vendor.mcphost.* and correctly does not.
    assertTrue(
        offeredTools().containsKey("core_workflow_research_brief"),
        "expected the projected research-brief workflow among the offered tools; got "
            + offeredTools().keySet());
  }

  @Test
  @DisplayName("876 §B.7: every core_* name in DEFAULT_SYSTEM_PROMPT is a real offered wire name")
  void theSystemPromptNamesOnlyRealTools() throws IOException {
    // A SOURCE-TEXT check, and by necessity: AgentPromptComposer is package-private in
    // io.justsearch.agent (module app-agent) and app-services DEPENDS ON app-agent, so app-agent's
    // test classpath cannot see this catalog and the constant cannot be read from here. The prompt
    // text is nonetheless the subject of the invariant, and it is fully determined by the source.
    // Same technique and rationale as AgentRunDurabilityClosureTest's mapper-switch check.
    String prompt = defaultSystemPromptText();
    Set<String> offered = offeredTools().keySet();

    Set<String> mentioned = new LinkedHashSet<>();
    Matcher m = WIRE_NAME.matcher(prompt);
    while (m.find()) {
      mentioned.add(m.group());
    }
    // A green from an empty mention set would mean the extraction broke, not that the prompt is
    // honest — pin that the region actually carries tool guidance.
    assertFalse(
        mentioned.isEmpty(),
        "the DEFAULT_SYSTEM_PROMPT region was located but names no core_* tool at all — the"
            + " extraction is wrong");

    Set<String> unknown = new LinkedHashSet<>(mentioned);
    unknown.removeAll(offered);
    assertEquals(
        Set.of(),
        unknown,
        "DEFAULT_SYSTEM_PROMPT instructs the model to call tools that are not offered. Offered: "
            + offered);
  }

  @Test
  @DisplayName("876 §B.7: a description only names parameters its own Interface declares")
  void descriptionsNameOnlyDeclaredParameters() {
    Map<String, Map<String, Object>> tools = offeredTools();
    Map<String, Operation> operations = offeredOperations();
    Set<String> wireNames = tools.keySet();

    List<String> offenders = new ArrayList<>();
    for (Map.Entry<String, Map<String, Object>> entry : tools.entrySet()) {
      Operation op = operations.get(entry.getKey());
      if (op == null) {
        continue;
      }
      Set<String> declared = declaredProperties(op);
      String description = String.valueOf(entry.getValue().get("description"));
      Matcher m = SNAKE_CASE.matcher(description);
      while (m.find()) {
        String token = m.group();
        if (declared.contains(token) || wireNames.contains(token)) {
          continue;
        }
        offenders.add(
            "tool "
                + entry.getKey()
                + " describes '"
                + token
                + "', which is neither a property of its Interface "
                + declared
                + " nor an offered wire tool name");
      }
    }
    assertEquals(
        List.of(),
        offenders,
        "an operation's description is read by the MODEL, so it may only name parameters the"
            + " operation's own Interface declares, or another tool it can actually call. Naming"
            + " anything else teaches the model a call it cannot make (tempdoc 876 B.7).");
  }

  private static Set<String> declaredProperties(Operation op) {
    JsonNode inputs = MAPPER.readTree(op.intf().inputs());
    JsonNode properties = inputs.get("properties");
    if (properties == null || !properties.isObject()) {
      return Set.of();
    }
    Set<String> names = new LinkedHashSet<>();
    for (String property : properties.propertyNames()) {
      names.add(property);
    }
    return names;
  }

  /**
   * The literal text of {@code AgentPromptComposer.DEFAULT_SYSTEM_PROMPT}: every line from its
   * declaration to the terminating {@code ;}, with comment lines removed (a comment naming a tool is
   * not an instruction to the model, and a stale one must not fail this test for the wrong reason).
   */
  private static String defaultSystemPromptText() throws IOException {
    Path source =
        repoRoot()
            .resolve("modules/app-agent/src/main/java/io/justsearch/agent/AgentPromptComposer.java");
    String composer = Files.readString(source, StandardCharsets.UTF_8);
    StringBuilder region = new StringBuilder();
    boolean inConstant = false;
    for (String line : composer.split("\r?\n")) {
      String trimmed = line.strip();
      if (!inConstant) {
        if (trimmed.startsWith("static final String DEFAULT_SYSTEM_PROMPT")) {
          inConstant = true;
        }
        continue;
      }
      if (!trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*")) {
        region.append(line).append('\n');
      }
      if (trimmed.endsWith(";")) {
        break;
      }
    }
    assertTrue(inConstant, "DEFAULT_SYSTEM_PROMPT was not found in " + source);
    assertNotEquals(0, region.length(), "the DEFAULT_SYSTEM_PROMPT region is empty");
    return region.toString();
  }

  private static Path repoRoot() {
    Path p = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 10 && p != null; i++) {
      if (Files.exists(p.resolve("governance/consult-register.v1.json"))) {
        return p;
      }
      p = p.getParent();
    }
    throw new IllegalStateException("repo root not found from " + Paths.get("").toAbsolutePath());
  }
}

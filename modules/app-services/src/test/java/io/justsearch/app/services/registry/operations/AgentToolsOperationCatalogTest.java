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
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
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
 * schema that DID declare {@code path_prefix} was a tool-local constant read only by unit tests
 * (deleted in tempdoc 877 §2.1). So the system prompt instructed the model to use a
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
    // `query`, `limit` and `path_prefix` are the three keys SearchTool.execute reads from
    // LLM-authored arguments. `mode`/`pipeline` are deliberately NOT declared —
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

  @Test
  @DisplayName("879 — core.ingest-files declares the indexing-jobs Resource it affects")
  void ingestFilesDeclaresWhatItAffects() {
    // OperationLineage is not inert: the FE renders `affects` in operationButton.ts and
    // operationHoverPreview.ts. Ingest queues indexing work, so it affects the same indexing-jobs
    // Resource core.rebuild-index declares. Pin it so the declaration cannot silently regress to
    // empty() — which on this axis reads as "nothing is affected".
    Operation ingest = op(AgentToolsOperationCatalog.INGEST_FILES);
    assertEquals(
        java.util.Set.of(new ResourceRef("core.indexing-jobs")),
        ingest.lineage().affects(),
        "ingest queues indexing work; core.indexing-jobs is the Resource that changes");
    assertTrue(
        ingest.lineage().supersedes().isEmpty(), "ingest supersedes no other operation's effect");
    // NOT core.indexed-roots: that Resource is the WATCHED-root list, changed only by the
    // add/remove-watched-root gestures. Ingest dispatches a one-shot ScanRoot and registers nothing.
    assertFalse(
        ingest.lineage().affects().contains(new ResourceRef("core.indexed-roots")),
        "ingest does not register a watched root");
  }

  @Test
  @DisplayName("879 — every agent tool's retry declaration is what the dispatcher now acts on")
  void retryDeclarationsArePinned() {
    // Tempdoc 879 wired AgentToolDispatcher to OperationPolicy.retry(); before that the loop
    // hard-coded `risk == LOW` and these declarations changed nothing, so they could be edited
    // without consequence. Pinning them makes any future edit a deliberate act with a visible
    // behavioural cost — the whole point of moving the axis from decoration to authority.
    Map<OperationRef, RetryPolicy> expected =
        Map.of(
            // Idempotent reads over the index: replaying observes again rather than mutating.
            AgentToolsOperationCatalog.SEARCH_INDEX,
                RetryPolicy.autoRetry(2, "core.search-index"),
            AgentToolsOperationCatalog.BROWSE_FOLDERS,
                RetryPolicy.autoRetry(2, "core.browse-folders"),
            // A paged read must not be transparently repeated behind a different offset.
            AgentToolsOperationCatalog.READ_DOCUMENT, RetryPolicy.noRetry(),
            // Writes and navigation: replaying is either unsafe or user-visible.
            AgentToolsOperationCatalog.REMEMBER, RetryPolicy.noRetry(),
            AgentToolsOperationCatalog.INGEST_FILES, RetryPolicy.noRetry(),
            AgentToolsOperationCatalog.FILE_OPERATIONS, RetryPolicy.noRetry(),
            AgentToolsOperationCatalog.NAVIGATE_TO_SURFACE, RetryPolicy.noRetry());

    List<Operation> declared = new AgentToolsOperationCatalog().definitions();
    assertEquals(
        expected.size(), declared.size(), "a new agent tool must declare its retry axis here too");
    for (Operation declaredOp : declared) {
      assertEquals(
          expected.get(declaredOp.id()),
          declaredOp.policy().retry(),
          () -> "retry declaration changed for " + declaredOp.id().value());
    }
  }

  /**
   * Tempdoc 878 §D.9 — bind {@code OutputLineage.CORPUS_READERS} to the catalog it describes.
   *
   * <p>That set is a private copy of three operation ids, living in {@code app-agent-api} where the
   * catalog is not visible, and it FAILS OPEN: an id that no longer matches classifies {@code
   * RUNTIME}, and a runtime-classified output gets no quoting frame on the FE. So both ways of
   * getting it wrong — renaming an operation, and adding a corpus reader without touching that file
   * — produce corpus text rendered as the agent's own voice, silently, which is the exact failure
   * {@code OutputLineage} exists to prevent.
   *
   * <p>A copy bound to its authority by a test is not drift; an unbound copy is. Deriving the set
   * instead would need a "reads the corpus" flag on {@code Operation} that nothing else would use —
   * structure for a three-element set. This test is the cheaper half of the same guarantee, and it
   * covers the case a mere existence check would miss: a NEW agent tool must be classified either
   * way, deliberately, before it can ship.
   */
  @Test
  @DisplayName("878 §D.9 — every agent operation is classified corpus-quoted or runtime, on purpose")
  void everyAgentOperationIsClassifiedByOutputLineage() {
    // The agent tools whose OUTPUT is a runtime/computed value, not the user's documents quoted
    // back. Listing them is the point: a new agent tool fails this test until someone decides.
    java.util.Set<String> declaredRuntimeReaders =
        java.util.Set.of(
            "core.ingest-files",
            "core.remember",
            "core.navigate-to-surface",
            "core.file-operations");

    for (Operation operation : new AgentToolsOperationCatalog().definitions()) {
      if (!operation.executors().contains(ExecutorTag.AGENT)) {
        continue;
      }
      String id = operation.id().value();
      boolean corpusReader =
          io.justsearch.agent.api.registry.OutputLineage.forOperationId(id)
              == io.justsearch.agent.api.registry.OutputLineage.CORPUS_QUOTED;
      assertTrue(
          corpusReader || declaredRuntimeReaders.contains(id),
          "agent operation '"
              + id
              + "' is classified RUNTIME by OutputLineage.forOperationId, but this test does not"
              + " list it as a deliberate runtime reader. If its output quotes the user's documents,"
              + " add it to OutputLineage.CORPUS_READERS — otherwise its text renders unframed, as"
              + " the agent's own words. If it really is a computed value, add it here.");
    }

    // The other direction, read from the AUTHORITY rather than restated here — a third hand-written
    // copy of these ids would check nothing. An id in CORPUS_READERS that no operation answers to is
    // a rename that already broke the classification, and it downgrades silently to RUNTIME.
    java.util.Set<String> declaredIds =
        new AgentToolsOperationCatalog()
            .definitions().stream()
                .map(o -> o.id().value())
                .collect(java.util.stream.Collectors.toSet());
    java.util.Set<String> readers =
        io.justsearch.agent.api.registry.OutputLineage.corpusReaderIds();
    assertFalse(readers.isEmpty(), "the authority must actually name some corpus readers");
    for (String reader : readers) {
      assertTrue(
          declaredIds.contains(reader),
          "OutputLineage.CORPUS_READERS names '"
              + reader
              + "', which this catalog no longer declares — the classifier fails OPEN, so the"
              + " renamed operation's corpus text would render with no quoting frame at all");
    }
  }
}

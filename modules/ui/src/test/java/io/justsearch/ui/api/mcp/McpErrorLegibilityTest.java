/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.WorkerServices;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.ui.api.KnowledgeSearchController;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 725 (design #2, increment W3) — pins the error-result legibility grammar across {@link
 * McpToolSurface}: the 3 "knowledge server not available" sites (answer/search/status) and the 5
 * generic {@code catch (Exception e)} sites (answer/search/status/operation
 * dispatch/runtime_manifest) both state the condition descriptively and point at {@code
 * justsearch_status} as a remedy, never as an imperative instruction to act now.
 *
 * <p>The runtime_manifest catch site is verified by code reading, not a live test here: its
 * exception path requires a Jackson serialization failure on the (real, final) {@code
 * RuntimeManifest} record, which the other 4 sites' straightforward mock-throws don't need —
 * confirmed instead that {@code log.warn} was added and the message uses {@link
 * McpToolSurface}'s shared {@code toolFailureMessage} helper (same as the other 4 sites).
 */
@DisplayName("McpToolSurface: error-result legibility (tempdoc 725 W3)")
final class McpErrorLegibilityTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneId.of("UTC"));

  private static final String STATUS_POINTER = "justsearch_status tool";

  private static String textOf(Map<String, Object> result) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    return (String) content.get(0).get("text");
  }

  private static McpToolSurface surfaceWithNoBackend() {
    return new McpToolSurface(
        List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class),
        () -> null,
        () -> null,
        FIXED_CLOCK);
  }

  // ---------------------------------------------------------------------
  // "Knowledge server not available" — answer / search / status
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("answer: unavailable knowledge server states the condition + status-tool pointer")
  void answerUnavailableMessage() {
    Map<String, Object> result =
        surfaceWithNoBackend().callTool("justsearch_answer", Map.of("query", "q"), "s1");

    assertEquals(Boolean.TRUE, result.get("isError"));
    String text = textOf(result);
    assertTrue(
        text.contains("Knowledge server is not available (worker offline or still starting)."),
        text);
    assertTrue(text.contains(STATUS_POINTER), text);
    assertNoImperativeCallInstruction(text);
  }

  @Test
  @DisplayName("search: unavailable knowledge server states the condition + status-tool pointer")
  void searchUnavailableMessage() {
    Map<String, Object> result =
        surfaceWithNoBackend().callTool("justsearch_search", Map.of("query", "q"), "s1");

    assertEquals(Boolean.TRUE, result.get("isError"));
    String text = textOf(result);
    assertTrue(
        text.contains("Knowledge server is not available (worker offline or still starting)."),
        text);
    assertTrue(text.contains(STATUS_POINTER), text);
    assertNoImperativeCallInstruction(text);
  }

  @Test
  @DisplayName("status: unavailable knowledge server states the condition + status-tool pointer")
  void statusUnavailableMessage() {
    Map<String, Object> result =
        surfaceWithNoBackend().callTool("justsearch_status", Map.of(), "s1");

    assertEquals(Boolean.TRUE, result.get("isError"));
    String text = textOf(result);
    assertTrue(
        text.contains("Knowledge server is not available (worker offline or still starting)."),
        text);
    assertTrue(text.contains(STATUS_POINTER), text);
    assertNoImperativeCallInstruction(text);
  }

  // ---------------------------------------------------------------------
  // Generic catch (Exception e) sites: uniform "<tool> failed: <class>: <message>. ..." grammar
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("answer: generic failure states tool name, exception class/message, status pointer")
  void answerGenericFailureMessage() {
    DocumentService documents = mock(DocumentService.class);
    when(documents.retrieveContext(any())).thenThrow(new IllegalStateException("boom"));
    WorkerServices workers = new WorkerServices(null, documents, null, null, null);
    HeadAssembly facade = mock(HeadAssembly.class);
    when(facade.workers()).thenReturn(workers);
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> null,
            () -> facade,
            FIXED_CLOCK);

    Map<String, Object> result = surface.callTool("justsearch_answer", Map.of("query", "q"), "s1");

    assertEquals(Boolean.TRUE, result.get("isError"));
    String text = textOf(result);
    assertTrue(text.startsWith("Answer failed: IllegalStateException: boom."), text);
    assertTrue(text.contains(STATUS_POINTER), text);
    assertNoImperativeCallInstruction(text);
  }

  @Test
  @DisplayName("search: generic failure states tool name, exception class/message, status pointer")
  void searchGenericFailureMessage() {
    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.search(any())).thenThrow(new IllegalStateException("boom"));
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> ctrl,
            () -> null,
            FIXED_CLOCK);

    Map<String, Object> result = surface.callTool("justsearch_search", Map.of("query", "q"), "s1");

    assertEquals(Boolean.TRUE, result.get("isError"));
    String text = textOf(result);
    assertTrue(text.startsWith("Search failed: IllegalStateException: boom."), text);
    assertTrue(text.contains(STATUS_POINTER), text);
    assertNoImperativeCallInstruction(text);
  }

  @Test
  @DisplayName("status: generic failure states tool name, exception class/message, status pointer")
  void statusGenericFailureMessage() {
    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.status()).thenThrow(new IllegalStateException("boom"));
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> ctrl,
            () -> null,
            FIXED_CLOCK);

    Map<String, Object> result = surface.callTool("justsearch_status", Map.of(), "s1");

    assertEquals(Boolean.TRUE, result.get("isError"));
    String text = textOf(result);
    assertTrue(text.startsWith("Status failed: IllegalStateException: boom."), text);
    assertTrue(text.contains(STATUS_POINTER), text);
    assertNoImperativeCallInstruction(text);
  }

  @Test
  @DisplayName(
      "operation dispatch: generic failure states tool name, exception class/message, status"
          + " pointer")
  void operationDispatchGenericFailureMessage() {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    when(dispatcher.dispatch(any(), any(), any())).thenThrow(new IllegalStateException("boom"));
    McpToolSurface surface =
        new McpToolSurface(
            List.of(new AgentToolsOperationCatalog()),
            dispatcher,
            () -> null,
            () -> null,
            FIXED_CLOCK);

    Map<String, Object> result =
        surface.callTool("justsearch_ingest", Map.of("paths", List.of("C:/tmp/notes")), "s1");

    assertEquals(Boolean.TRUE, result.get("isError"));
    String text = textOf(result);
    assertTrue(
        text.startsWith("Operation core.ingest-files failed: IllegalStateException: boom."),
        text);
    assertTrue(text.contains(STATUS_POINTER), text);
    assertNoImperativeCallInstruction(text);
  }

  /**
   * Grammar rule (tempdoc 725): error results state facts descriptively; the status-tool pointer
   * is remedy phrasing ("state is available via ..." / "is reported by ..."), never an imperative
   * ("call justsearch_status now").
   */
  private static void assertNoImperativeCallInstruction(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    assertFalse(lower.contains("now call"), "must not issue an imperative instruction: " + text);
    assertFalse(lower.contains("call the justsearch_status"), "must not issue an imperative instruction: " + text);
  }
}

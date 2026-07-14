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
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.DocumentService.ContextSection;
import io.justsearch.app.api.DocumentService.QualitySignals;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.api.WorkerServices;
import io.justsearch.app.services.HeadAssembly;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tempdoc 725 (design #2, increments W2a/W2b/W2c) — pins {@code justsearch_answer}'s
 * self-describing evidence-pack header, the {@code response_format} concise/detailed density
 * tier, and the {@code contextFormat} the call site requests from {@code DocumentService}.
 *
 * <p>The W2b regression (audit-without-test rule): {@code McpToolSurface.callAnswer} used to
 * request {@code ContextFormat.XML} even though the Worker's {@code ContextBudgeter} has no
 * XML/PLAIN branch and always renders LABELED ({@code "[From: label]\n"} sections) — a dead
 * orphan request (tempdoc 725 orphan #5). {@link #answerRequestsLabeledContextFormat()} captures
 * the actual {@link RetrieveContextParams} passed to {@code DocumentService.retrieveContext} and
 * fails if the call site ever requests a format other than LABELED again.
 */
@DisplayName("McpToolSurface justsearch_answer: response legibility (tempdoc 725 W2)")
final class McpAnswerLegibilityTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneId.of("UTC"));

  private record Invocation(
      Map<String, Object> result, RetrieveContextParams capturedParams) {}

  private static Invocation invokeAnswer(ContextResult canned, Map<String, Object> args) {
    DocumentService documents = mock(DocumentService.class);
    ArgumentCaptor<RetrieveContextParams> captor =
        ArgumentCaptor.forClass(RetrieveContextParams.class);
    when(documents.retrieveContext(captor.capture()))
        .thenReturn(CompletableFuture.completedFuture(canned));
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
    Map<String, Object> result = surface.callTool("justsearch_answer", args, "s1");
    return new Invocation(result, captor.getValue());
  }

  private static Invocation invokeAnswer(ContextResult canned) {
    return invokeAnswer(canned, Map.of("query", "widget torque"));
  }

  private static String textOf(Map<String, Object> result) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    return (String) content.get(0).get("text");
  }

  private static ContextResult fixtureResult(boolean truncated, List<ContextSection> sections) {
    List<ContextCitation> citations =
        List.of(
            new ContextCitation("doc-1", 0, 2, 0, 40, 0.9f, "excerpt-1", 1, 4, "", 0),
            new ContextCitation("doc-1", 1, 2, 40, 80, 0.8f, "excerpt-2", 5, 8, "", 0),
            new ContextCitation("doc-2", 0, 1, 0, 30, 0.7f, "excerpt-3", 1, 3, "", 0));
    return new ContextResult(
        "[From: doc-1]\nexcerpt-1\n\n---\n\n[From: doc-2]\nexcerpt-3",
        3,
        3,
        0,
        citations,
        "HYBRID",
        "HYBRID_AVAILABLE",
        truncated,
        sections,
        new QualitySignals(0.9f, 0.1f, 0.5f, 5, 3));
  }

  // ---------------------------------------------------------------------
  // W2a: evidence-pack header
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("(a) header states passage/document counts, retrieval mode, and the no-answer fact")
  void headerStatesCountsModeAndNoAnswerFact() {
    String text = textOf(invokeAnswer(fixtureResult(false, List.of())).result());

    assertTrue(
        text.startsWith(
            "Evidence pack: 3 passages from 2 documents (retrieval mode: HYBRID). No synthesized"
                + " answer is included."),
        text);
    assertFalse(text.contains("Context was truncated to fit limits."), text);
  }

  @Test
  @DisplayName("(a) header appends the truncation fact only when contextTruncated is true")
  void headerAppendsTruncationFactWhenTruncated() {
    String text = textOf(invokeAnswer(fixtureResult(true, List.of())).result());

    assertTrue(
        text.contains(
            "(retrieval mode: HYBRID). No synthesized answer is included. Context was truncated"
                + " to fit limits."),
        text);
  }

  /**
   * Mirrors the REAL shape {@code RemoteDocumentService.retrieveContextFallback} (gRPC-failure
   * catch, FULLTEXT_FALLBACK path) actually returns: empty citations (a chunk-RAG-only concept
   * the full-document fallback never populates), a non-blank budgeter-built context with two
   * {@code [From: ...]} sections, populated {@code sections()}/{@code docsUsed()}, and
   * retrievalMode "FULLTEXT_FALLBACK" — the 9-arg {@code ContextResult} constructor this call site
   * uses (quality defaults to {@code QualitySignals.EMPTY}).
   */
  private static ContextResult fulltextFallbackFixtureResult() {
    String context = "[From: doc-a.txt]\ncontent-a\n\n---\n\n[From: doc-b.txt]\ncontent-b";
    List<ContextSection> sections =
        List.of(
            new ContextSection("doc-a.txt", "content-a", false, 0, 0),
            new ContextSection("doc-b.txt", "content-b", false, 1, 1));
    return new ContextResult(
        context, 0, 0, 2, List.of(), "FULLTEXT_FALLBACK", "GRPC_FAILED", false, sections);
  }

  @Test
  @DisplayName(
      "(a) header derives passage/document counts from sections in the FULLTEXT_FALLBACK path"
          + " (citations empty, tempdoc 725 review fix)")
  void headerDerivesCountsFromSectionsWhenCitationsEmptyInFallback() {
    String text = textOf(invokeAnswer(fulltextFallbackFixtureResult()).result());

    assertTrue(
        text.startsWith(
            "Evidence pack: 2 passages from 2 documents (retrieval mode: FULLTEXT_FALLBACK). No"
                + " synthesized answer is included."),
        text);
    assertFalse(text.contains("Evidence pack: 0 passages from 0 documents"), text);
  }

  // ---------------------------------------------------------------------
  // W2b: the call site must request the format the Worker actually renders
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("(b) callAnswer requests ContextFormat.LABELED (the format ContextBudgeter renders)")
  void answerRequestsLabeledContextFormat() {
    Invocation invocation = invokeAnswer(fixtureResult(false, List.of()));

    assertEquals(
        RetrieveContextParams.ContextFormat.LABELED,
        invocation.capturedParams().contextFormat(),
        "the MCP call site must request the format the Worker's ContextBudgeter actually renders"
            + " — requesting XML here regressed to a dead orphan request (tempdoc 725 orphan #5)");
  }

  // ---------------------------------------------------------------------
  // W2c: response_format concise/detailed
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("(c) detailed (default) renders the full assembled context verbatim")
  void detailedRendersFullContextVerbatim() {
    ContextResult canned = fixtureResult(false, List.of());
    String text = textOf(invokeAnswer(canned).result());

    assertTrue(text.contains(canned.context()), text);
  }

  @Test
  @DisplayName("(c) concise caps passages at 3 highest-rank sections, trimmed to ~600 chars each")
  void conciseCapsAndTrimsPassages() {
    String longContent = "x".repeat(700);
    List<ContextSection> fourSections =
        List.of(
            new ContextSection("doc-1", longContent, false, 0, 0),
            new ContextSection("doc-2", longContent, false, 1, 1),
            new ContextSection("doc-3", longContent, false, 2, 2),
            new ContextSection("doc-4", "should not appear", false, 3, 3));
    ContextResult canned = fixtureResult(false, fourSections);

    String text =
        textOf(invokeAnswer(canned, Map.of("query", "q", "response_format", "concise")).result());

    assertTrue(text.contains("[From: doc-1]"), text);
    assertTrue(text.contains("[From: doc-2]"), text);
    assertTrue(text.contains("[From: doc-3]"), text);
    assertFalse(text.contains("doc-4"), text);
    assertFalse(text.contains("should not appear"), text);
    assertTrue(text.contains(McpSearchResultFormatter.TRUNCATION_REMEDY), text);
    // Each trimmed passage is <= 600 chars of source content, so the run of "x" characters never
    // reaches the untrimmed 700-char length.
    assertFalse(text.contains("x".repeat(700)), text);
  }

  @Test
  @DisplayName("(c) response_format is optional and defaults to detailed")
  void responseFormatDefaultsToDetailed() {
    ContextResult canned = fixtureResult(false, List.of());
    String withoutArg = textOf(invokeAnswer(canned, Map.of("query", "q")).result());
    String withDetailed =
        textOf(invokeAnswer(canned, Map.of("query", "q", "response_format", "detailed")).result());

    assertEquals(withDetailed, withoutArg);
  }
}

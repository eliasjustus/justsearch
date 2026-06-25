package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.agent.api.registry.OperationResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 565 §3.A — the runtime-property regression test the suite was missing: a grounded agent
 * run whose search hits carry chunk identity ({@code parentDocId}) yields a NON-EMPTY grounding
 * source list on {@link AgentEvent.AgentDone}. The de-risk pass found §3.A's parts unit-tested but
 * this end-to-end property unpinned (and {@code AgentGroundingSeamAuditTest} explicitly disclaims
 * it) — so a regression (or a silent-skip masquerade) could ship green. This pins the chunk-precise
 * happy path, the dedup, and (tempdoc 603 D-3) the provenance-vs-precision behavior: a hit WITHOUT
 * chunk identity but WITH a {@code path} becomes a DOCUMENT-LEVEL source (sentinel chunk/lines) rather
 * than being dropped (the 603 D-1 "No grounded sources while the answer cites them" bug); only a hit
 * with neither identity is truly uncitable (the now-narrow WARN branch in
 * {@link AgentSession#collectGroundingSources()}).
 */
final class AgentSessionGroundingTest {

  private static AgentSession session() {
    return new AgentSession(List.of(Map.of("role", "user", "content", "q")), 8000);
  }

  private static ToolCallRequest searchCall(String id) {
    return new ToolCallRequest(id, "core_search_index", "{\"query\":\"x\"}");
  }

  private static Map<String, Object> chunkHit(String parentDocId, int chunkIndex, int startLine) {
    return Map.of(
        "parentDocId", parentDocId,
        "chunkIndex", chunkIndex,
        "path", "/docs/" + parentDocId + ".md",
        "title", "Doc " + parentDocId,
        "excerpt", "an excerpt",
        "startLine", startLine,
        "endLine", startLine + 4,
        "headingText", "");
  }

  @Test
  @DisplayName("search hits carrying parentDocId → non-empty, ordered, field-mapped grounding sources")
  void chunkIdentifiedHits_yieldNonEmptyGroundingSources() {
    var session = session();
    session.recordExecution(
        searchCall("call-1"),
        OperationResult.success(
            "found 2", Map.of("searchResults", List.of(chunkHit("d1", 0, 5), chunkHit("d2", 1, 12)))));

    List<AgentEvent.AgentSource> sources = session.collectGroundingSources();

    assertEquals(2, sources.size(), "both chunk-identified hits are citable");
    assertEquals("d1", sources.get(0).parentDocId());
    assertEquals(5, sources.get(0).startLine(), "the passage span is carried for the deep-link");
    assertEquals("Doc d1", sources.get(0).title());
    assertEquals("d2", sources.get(1).parentDocId(), "first-seen order is preserved");
  }

  @Test
  @DisplayName("603 D-3: search hits WITHOUT parentDocId but WITH a path → document-level provenance sources (sentinel chunk/lines), NOT dropped")
  void documentLevelHits_yieldProvenanceSources() {
    var session = session();
    // A whole-document hit (the main BM25/keyword pipeline under BLOCKED_LEGACY) carries no
    // parentDocId — its stored fields lack the chunk-only parent_doc_id. Tempdoc 603 D-1: these were
    // DROPPED, so the Sources pane read "No grounded sources" while the answer cited them. D-3: the
    // document IS a real source the answer drew on — emit it as a document-level provenance source
    // (identity = path; chunk ordinal + lines = the DOC_LEVEL_SENTINEL, so the FE renders the SOURCED
    // frame and suppresses the precise-line deep-link).
    session.recordExecution(
        searchCall("call-1"),
        OperationResult.success(
            "found 1",
            Map.of(
                "searchResults",
                List.of(Map.of("path", "/a.md", "title", "A", "excerpt", "ex")))));

    List<AgentEvent.AgentSource> sources = session.collectGroundingSources();

    assertEquals(1, sources.size(), "a path-bearing hit is a real (document-level) source, not dropped");
    AgentEvent.AgentSource s = sources.get(0);
    assertEquals("/a.md", s.parentDocId(), "the document path is the source identity");
    assertEquals("/a.md", s.path());
    assertEquals("A", s.title());
    assertEquals(-1, s.chunkIndex(), "document-level: chunk ordinal is the sentinel (not a valid ordinal)");
    assertEquals(-1, s.startLine(), "document-level: no precise line (suppresses the false highlight)");
    assertEquals(-1, s.endLine());
  }

  @Test
  @DisplayName("603 D-3: a hit with NEITHER parentDocId NOR path → not addressable → empty (the now-narrow WARN case)")
  void hitsWithNoIdentityAtAll_yieldEmptyGrounding() {
    var session = session();
    session.recordExecution(
        searchCall("call-1"),
        OperationResult.success(
            "found 1", Map.of("searchResults", List.of(Map.of("title", "A", "excerpt", "ex")))));

    assertTrue(
        session.collectGroundingSources().isEmpty(),
        "no chunk identity AND no path — the source is not addressable, grounding is empty");
  }

  @Test
  @DisplayName("603 D-3: the same document (by path) across hits/turns dedups to one document-level source")
  void repeatedDocumentLevelSources_areDeduped() {
    var session = session();
    var docHit = Map.<String, Object>of("path", "/a.md", "title", "A", "excerpt", "ex");
    session.recordExecution(
        searchCall("call-1"), OperationResult.success("r", Map.of("searchResults", List.of(docHit))));
    session.recordExecution(
        searchCall("call-2"), OperationResult.success("r", Map.of("searchResults", List.of(docHit))));

    assertEquals(
        1, session.collectGroundingSources().size(), "same document path appears once");
  }

  @Test
  @DisplayName("the same source returned across two tool calls dedups by parentDocId#chunkIndex")
  void repeatedSourcesAcrossToolCalls_areDeduped() {
    var session = session();
    var result =
        OperationResult.success("r", Map.of("searchResults", List.of(chunkHit("d1", 0, 1))));
    session.recordExecution(searchCall("call-1"), result);
    session.recordExecution(searchCall("call-2"), result);

    assertEquals(
        1, session.collectGroundingSources().size(), "same parentDocId#chunkIndex appears once");
  }

  @Test
  @DisplayName("a run with no search at all → empty grounding (no WARN: no hits to be ungrounded about)")
  void noSearch_yieldsEmptyGroundingWithoutWarn() {
    assertTrue(session().collectGroundingSources().isEmpty());
  }
}

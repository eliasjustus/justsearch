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

  /**
   * Tempdoc 865 §7.4 — the MINT half of the deliberate tool-card/evidence-set divergence; the card
   * half is {@code toolSearchCard.projection.test.ts} (`modules/ui-web`), which runs the same
   * fixture through {@code agentSearchCardData}. Both halves guard the {@code
   * agent-tool-search-card} row in {@code governance/execution-surfaces.v1.json}.
   *
   * <p>The two surfaces answer different questions and are deliberately NOT merged: a tool card is
   * a RECEIPT OF ONE CALL (every hit that call returned, in that call's own order), while this mint
   * is the RUN'S EVIDENCE SET (deduped across calls, identity-bearing only). The legible story the
   * reader gets is "this call returned 3; the run drew on 3 distinct documents (out of 5 hits)".
   *
   * <p><b>Why this fixture is MULTI-CALL.</b> The worker collapses chunk hits to one hit per parent
   * document before results ever reach the agent ({@code
   * SearchExecutor.collapseChunkHitsToParents}, falling back to {@code hit.docId()} when {@code
   * PARENT_DOC_ID} is absent), so WITHIN one call there are no duplicate parents to dedup and no
   * identity-less hits to drop — both dedup branches below are inert single-call, and a single-call
   * version of this test would pass trivially while proving nothing. Cross-call accumulation is the
   * only live divergence mechanism, so the fixture repeats documents ACROSS calls: drop either
   * {@code seen.add(...)} guard in {@link AgentSession#collectGroundingSources()} and the mint
   * returns 5 sources instead of 3, failing here.
   */
  @Test
  @DisplayName("865 §7.4: the cards are a per-call receipt (5 rows) while the mint accumulates the run's DISTINCT documents (3)")
  void cardsAreAPerCallReceipt_whileTheMintAccumulatesDistinctDocumentsAcrossCalls() {
    // The same fixture toolSearchCard.projection.test.ts renders: A is chunk-precise, B is
    // document-level (no parentDocId), C arrives only on the second call.
    var hitA =
        Map.<String, Object>of(
            "title", "Doc A",
            "path", "f:/docs/a.md",
            "excerpt", "passage A",
            "line", 3,
            "parentDocId", "docs/a.md",
            "chunkIndex", 2,
            "startLine", 3,
            "endLine", 9);
    var hitB = Map.<String, Object>of("title", "Doc B", "path", "f:/docs/b.md", "excerpt", "passage B", "line", 0);
    var hitC =
        Map.<String, Object>of(
            "title", "Doc C",
            "path", "f:/docs/c.md",
            "excerpt", "passage C",
            "line", 11,
            "parentDocId", "docs/c.md",
            "chunkIndex", 0,
            "startLine", 11,
            "endLine", 14);

    List<Map<String, Object>> call1 = List.of(hitA, hitB);
    List<Map<String, Object>> call2 = List.of(hitA, hitB, hitC);
    var session = session();
    session.recordExecution(
        searchCall("call-1"), OperationResult.success("found 2", Map.of("searchResults", call1)));
    session.recordExecution(
        searchCall("call-2"), OperationResult.success("found 3", Map.of("searchResults", call2)));

    List<AgentEvent.AgentSource> sources = session.collectGroundingSources();

    // The cards render every hit of every call — one row per searchResults entry, no cross-call
    // dedup (pinned on the real projection by the TS half).
    int cardRows = call1.size() + call2.size();
    assertEquals(5, cardRows, "the two tool cards render 5 rows in total (the receipt side)");
    // The mint emits the run's DISTINCT documents: A once (chunk identity), B once (document
    // identity), C once. Removing either dedup branch makes this 5 and collapses the divergence.
    assertEquals(3, sources.size(), "the run drew on 3 distinct documents across the two calls");
    assertTrue(
        cardRows > sources.size(),
        "the receipt total exceeds the run's distinct-document count — the divergence this register row declares");
    assertEquals(
        List.of("docs/a.md", "f:/docs/b.md", "docs/c.md"),
        sources.stream().map(AgentEvent.AgentSource::parentDocId).toList(),
        "first-seen order across calls, one entry per distinct document identity");
    assertEquals(
        -1,
        sources.get(1).chunkIndex(),
        "the document-level source keeps the sentinel — dedup by 'doc#path' does not invent chunk precision");
  }
}

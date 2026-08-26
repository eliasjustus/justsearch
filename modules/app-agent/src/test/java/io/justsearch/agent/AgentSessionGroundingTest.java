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

  // -----------------------------------------------------------------------------------------
  // Tempdoc 868 §B.3 — the second producer and the acquisition axis
  // -----------------------------------------------------------------------------------------

  private static ToolCallRequest readCall(String id) {
    return new ToolCallRequest(id, "core_read_document", "{\"path\":\"/a.md\"}");
  }

  private static Map<String, Object> readPage(String path, String excerpt) {
    return Map.of(
        "path", path,
        "title", "Doc " + path,
        "excerpt", excerpt,
        "startChar", 0,
        "endChar", excerpt.length(),
        "truncated", false);
  }

  @Test
  @DisplayName("868 §B.3: readResults mints a DOCUMENT-LEVEL source marked acquisition=opened")
  void readResults_mintOpenedDocumentLevelSources() {
    var session = session();
    session.recordExecution(
        readCall("call-1"),
        OperationResult.success(
            "[read] /a.md", Map.of("readResults", List.of(readPage("/a.md", "the page text")))));

    List<AgentEvent.AgentSource> sources = session.collectGroundingSources();

    assertEquals(1, sources.size(), "a read establishes the document it opened as a source");
    AgentEvent.AgentSource s = sources.get(0);
    assertEquals(
        AgentEvent.AgentSource.ACQUISITION_OPENED,
        s.acquisition(),
        "nothing ranked this document — the agent named it and read it, and 865 §7.6's invariant is"
            + " that an opened source carries LESS relevance evidence than a retrieved one");
    assertEquals("/a.md", s.parentDocId(), "the path is the identity: a read has no chunk ordinal");
    assertEquals(-1, s.chunkIndex(), "document-level sentinel");
    assertEquals(-1, s.startLine());
    assertEquals(
        "the page text",
        s.excerpt(),
        "the excerpt is the page the model saw — what the citation matcher verifies against");
  }

  @Test
  @DisplayName("868 §B.3: a document already RETRIEVED is not re-minted by a later read — opened never upgrades")
  void readOfAnAlreadyRetrievedDocument_doesNotReMint() {
    var session = session();
    // Search first: the document is established as retrieved, with whatever identity search gave it.
    session.recordExecution(
        searchCall("call-1"),
        OperationResult.success(
            "r",
            Map.of("searchResults", List.of(Map.of("path", "/a.md", "title", "A", "excerpt", "hit")))));
    // Then read the SAME document. The run-wide dedup key is `doc#<path>` on both arms.
    List<AgentEvent.AgentSource> readDelta =
        session.recordExecution(
            readCall("call-2"),
            OperationResult.success(
                "[read] /a.md", Map.of("readResults", List.of(readPage("/a.md", "the page text")))));

    assertTrue(readDelta.isEmpty(), "the read added no NEW document, so its delta is empty");
    List<AgentEvent.AgentSource> sources = session.collectGroundingSources();
    assertEquals(1, sources.size(), "one document, one source");
    assertEquals(
        AgentEvent.AgentSource.ACQUISITION_RETRIEVED,
        sources.get(0).acquisition(),
        "the first identity wins: a document that WAS ranked keeps that stronger evidence, and"
            + " 'opened' must never overwrite it (the invariant is directional)");
    assertEquals("hit", sources.get(0).excerpt(), "the retrieved excerpt is not replaced either");
  }

  @Test
  @DisplayName("868 §B.3: a read-only run's terminal sources all carry acquisition=opened")
  void readOnlyRun_terminalSourcesAreAllOpened() {
    var session = session();
    session.recordExecution(
        readCall("call-1"),
        OperationResult.success("r", Map.of("readResults", List.of(readPage("/a.md", "page a")))));
    session.recordExecution(
        readCall("call-2"),
        OperationResult.success("r", Map.of("readResults", List.of(readPage("/b.md", "page b")))));

    List<AgentEvent.AgentSource> sources = session.collectGroundingSources();
    assertEquals(2, sources.size());
    assertTrue(
        sources.stream()
            .allMatch(
                s -> AgentEvent.AgentSource.ACQUISITION_OPENED.equals(s.acquisition())),
        "a run that only read must not present its evidence as retrieved: " + sources);
  }

  @Test
  @DisplayName("868 §B.3: a read page with no path is not addressable — skipped, not minted blank")
  void readResultWithoutAPath_isSkipped() {
    var session = session();
    session.recordExecution(
        readCall("call-1"),
        OperationResult.success(
            "r", Map.of("readResults", List.of(Map.of("title", "A", "excerpt", "ex")))));
    assertTrue(session.collectGroundingSources().isEmpty());
  }

  @Test
  @DisplayName("868 §B.3: both producer keys on one result contribute, and the delta order follows them")
  void searchAndReadResultsOnOneResult_bothContribute() {
    var session = session();
    List<AgentEvent.AgentSource> delta =
        session.recordExecution(
            searchCall("call-1"),
            OperationResult.success(
                "r",
                Map.of(
                    "searchResults",
                    List.of(Map.of("path", "/s.md", "title", "S", "excerpt", "hit")),
                    "readResults",
                    List.of(readPage("/r.md", "page")))));

    assertEquals(2, delta.size());
    assertEquals(AgentEvent.AgentSource.ACQUISITION_RETRIEVED, delta.get(0).acquisition());
    assertEquals(AgentEvent.AgentSource.ACQUISITION_OPENED, delta.get(1).acquisition());
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

  /**
   * Tempdoc 865 §7.1 — the MINT is per call, and the per-call deltas PARTITION the run's evidence
   * set: each source appears in exactly one delta, in terminal order.
   *
   * <p>This is the property the whole slice rests on. {@code AgentSentenceCite.sourceIndex} is a
   * POSITION into the terminal list, resolved on the FE as {@code sources[cite.sourceIndex]}, so a
   * reconstruction from deltas that reordered or repeated anything would point every inline mark at
   * the wrong document — silently, and confidently. Two calls sharing a document are what makes the
   * partition observable: the shared document belongs to the FIRST call's delta and to no other.
   */
  @Test
  @DisplayName("865 §7.1: per-call deltas partition the run's evidence set, in terminal order")
  void perCallDeltas_partitionTheRunsEvidenceSet() {
    var session = session();
    List<AgentEvent.AgentSource> delta1 =
        session.recordExecution(
            searchCall("call-1"),
            OperationResult.success(
                "r", Map.of("searchResults", List.of(chunkHit("d1", 0, 5), chunkHit("d2", 1, 12)))));
    // Call 2 re-returns d1 (already established) and adds d3.
    List<AgentEvent.AgentSource> delta2 =
        session.recordExecution(
            searchCall("call-2"),
            OperationResult.success(
                "r", Map.of("searchResults", List.of(chunkHit("d1", 0, 5), chunkHit("d3", 0, 2)))));

    assertEquals(
        List.of("d1", "d2"),
        delta1.stream().map(AgentEvent.AgentSource::parentDocId).toList(),
        "the first call's delta is everything it established");
    assertEquals(
        List.of("d3"),
        delta2.stream().map(AgentEvent.AgentSource::parentDocId).toList(),
        "the second call's delta is what it ADDED — d1 was already established, so it is not re-sent");

    List<AgentEvent.AgentSource> concatenated =
        java.util.stream.Stream.concat(delta1.stream(), delta2.stream()).toList();
    assertEquals(
        identityOf(session.collectGroundingSources()),
        identityOf(concatenated),
        "concatenated deltas == the terminal source list, element for element and in order");
  }

  /**
   * Tempdoc 865 §7.5 — compare the eight IDENTITY components, explicitly ignoring the inclusion
   * axis.
   *
   * <p>The two sides legitimately differ there and only there: a delta is minted absent, while the
   * terminal resolves inclusion against the final prompt. Bare record equality would pass here today
   * only because this fixture records no compression receipt — a precondition nothing in the test
   * states, and one a future fixture could remove without meaning to, turning an alignment guard into
   * a puzzling failure about a budget fact.
   */
  private static List<AgentEvent.AgentSource> identityOf(List<AgentEvent.AgentSource> sources) {
    return sources.stream()
        .map(
            s ->
                s.withInclusion(
                    AgentEvent.AgentSource.INCLUSION_ABSENT,
                    AgentEvent.AgentSource.INCLUDED_CHARS_UNKNOWN))
        .toList();
  }

  /**
   * Tempdoc 865 §7.1 "emit only on change" — a tool call that established nothing returns an EMPTY
   * delta, which the dispatch seam turns into NO key on the event. An absent key means "established
   * nothing"; a present-but-empty one would be the run narrating something that did not happen.
   */
  @Test
  @DisplayName("865 §7.1: a call that establishes nothing yields an empty delta (⇒ no key)")
  void aCallThatEstablishesNothing_yieldsAnEmptyDelta() {
    var session = session();
    session.recordExecution(
        searchCall("call-1"),
        OperationResult.success("r", Map.of("searchResults", List.of(chunkHit("d1", 0, 5)))));

    // A non-search tool: no `searchResults` at all.
    assertTrue(
        session
            .recordExecution(
                new ToolCallRequest("call-2", "core_file_read", "{}"),
                OperationResult.success("read ok"))
            .isEmpty(),
        "a tool that returns no search evidence establishes nothing");
    // A search that returns only documents the run already has.
    assertTrue(
        session
            .recordExecution(
                searchCall("call-3"),
                OperationResult.success(
                    "r", Map.of("searchResults", List.of(chunkHit("d1", 0, 5)))))
            .isEmpty(),
        "a search returning only already-established documents establishes nothing NEW");
    assertEquals(1, session.collectGroundingSources().size());
  }

  /**
   * Tempdoc 865 §7.1 (the A2 mitigation) — the WIRE SHAPE of the stamped delta, pinned by a test in
   * place of the descriptor a typed event field would have given it.
   *
   * <p>{@code structuredData} is declared free-form ({@code AgentRunShape}: {@code
   * EventField.object("structuredData", "")}), which is the honest cost of the carrier decision: no
   * schema conformance test can see this key. So the nine keys and their types are pinned HERE, and
   * they are exactly {@code AgentSource}'s IDENTITY fields — the FE reads the delta through the same
   * generated {@code AgentSource} interface it reads the terminal {@code sources} through, and a
   * drift would be a silently wrong render rather than a type error.
   *
   * <p>Tempdoc 865 §7.5 — {@code AgentSource} also carries the two INCLUSION fields, and their
   * absence here is the contract, not an omission: inclusion is resolved against the final prompt at
   * the terminal, and a tool call has no final prompt to be a fact about.
   *
   * <p>Tempdoc 868 §B.3 — {@code acquisition} is the NINTH, and it belongs here for the mirror-image
   * reason inclusion does not: how a source was acquired is fixed at the mint and cannot change, so
   * a delta that dropped it would let a reloaded run silently re-describe an opened document as
   * retrieved.
   */
  @Test
  @DisplayName("865 §7.1 / 868 §B.3 conformance: the stamped grounding key's wire shape is AgentSource's nine identity fields")
  void groundingStampWireShapeConformance() {
    var session = session();
    List<AgentEvent.AgentSource> delta =
        session.recordExecution(
            searchCall("call-1"),
            OperationResult.success(
                "r",
                Map.of(
                    "searchResults",
                    List.of(chunkHit("d1", 2, 5), Map.of("path", "/a.md", "title", "A", "excerpt", "ex")))));

    OperationResult stamped = OperationResult.success("r").withGrounding(delta);

    assertEquals("grounding", OperationResult.GROUNDING_KEY, "the key the FE reads");
    Object raw = stamped.structuredData().get(OperationResult.GROUNDING_KEY);
    assertTrue(raw instanceof List<?>, "the delta rides as a list");
    List<?> wire = (List<?>) raw;
    assertEquals(2, wire.size());

    Map<?, ?> chunkPrecise = (Map<?, ?>) wire.get(0);
    assertEquals(
        java.util.Set.of(
            "parentDocId",
            "chunkIndex",
            "path",
            "title",
            "excerpt",
            "startLine",
            "endLine",
            "headingText",
            "acquisition"),
        chunkPrecise.keySet(),
        "exactly AgentSource's nine IDENTITY fields — no more (a leak; 865 §7.5's two inclusion"
            + " fields belong to the terminal, not to a delta), no fewer (a silently absent field)");
    assertEquals(
        AgentEvent.AgentSource.ACQUISITION_RETRIEVED,
        chunkPrecise.get("acquisition"),
        "a search hit is retrieved: something ranked it");
    assertEquals("d1", chunkPrecise.get("parentDocId"));
    assertEquals(2, chunkPrecise.get("chunkIndex"), "an ordinal, not a string");
    assertEquals(5, chunkPrecise.get("startLine"));

    Map<?, ?> documentLevel = (Map<?, ?>) wire.get(1);
    assertEquals(
        -1,
        documentLevel.get("chunkIndex"),
        "the document-level sentinel survives the wire projection — it is what tells the FE this"
            + " source has no chunk identity and therefore no matcher could examine it");

    // The stamp is a MERGE, not a replacement: whatever the operation already reported stays.
    OperationResult withOther =
        OperationResult.success("r", Map.of("searchResults", List.of())).withGrounding(delta);
    assertTrue(
        withOther.structuredData().containsKey("searchResults"),
        "the stamp merges into structuredData, exactly as withLineage does");
  }
}

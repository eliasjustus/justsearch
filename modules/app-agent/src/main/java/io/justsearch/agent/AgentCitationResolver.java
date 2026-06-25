/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.app.api.DocumentService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 565 §3.A — resolves an agent answer's per-sentence inline citations by reusing the RAG
 * answer↔source matcher ({@link DocumentService#matchCitations}). The matcher keys sources by
 * indexed-chunk identity ({@code parentDocId}+{@code chunkIndex}), which the agent search hits now
 * carry (565 §2a), so the SAME authoritative matcher the RAG path uses runs over the agent answer —
 * no second matching authority.
 *
 * <p>This is the inline-mark ENRICHMENT layer on top of the always-attached grounding sources: it
 * degrades to source-only (no inline marks) on any failure/timeout/empty rather than silent-zeroing
 * into a dead feature (the 565 §10 guard). The answer always cites verifiable local passages; the
 * matcher only adds which sentence cites which source.
 */
final class AgentCitationResolver {

  private static final Logger LOG = LoggerFactory.getLogger(AgentCitationResolver.class);
  /**
   * Cosine-similarity floor for an answer sentence to count as grounded by a source chunk — the ONE
   * shared cutoff (tempdoc 565 §15.A), read from the matcher API contract so the agent and RAG paths
   * cite identically (was a divergent local 0.45).
   */
  private static final double SIMILARITY_THRESHOLD =
      DocumentService.DEFAULT_CITATION_SIMILARITY_THRESHOLD;
  /** The agent loop blocks at most this long on the matcher before citing sources without marks. */
  private static final long MATCH_TIMEOUT_MS = 4000L;

  private final DocumentService documentService;

  AgentCitationResolver(DocumentService documentService) {
    this.documentService = documentService;
  }

  /**
   * Match the answer's sentences back to the grounding sources. Returns the inline-citation links
   * (sentence → source index), or an empty list when matching is unavailable/failed/empty.
   */
  List<AgentEvent.AgentSentenceCite> resolve(String answer, List<AgentEvent.AgentSource> sources) {
    if (documentService == null || answer == null || answer.isBlank() || sources.isEmpty()) {
      return List.of();
    }
    List<DocumentService.ContextCitation> citations = new ArrayList<>(sources.size());
    for (AgentEvent.AgentSource s : sources) {
      // Only parentDocId + chunkIndex drive the authoritative match (it re-fetches the chunk from
      // the index); the other fields are display metadata, defaulted here.
      citations.add(
          new DocumentService.ContextCitation(
              s.parentDocId(), s.chunkIndex(), 1, 0, 0, 0f, s.excerpt(),
              s.startLine(), s.endLine(), s.headingText(), 0));
    }
    try {
      DocumentService.CitationMatchResult result =
          documentService
              .matchCitations(answer, citations, SIMILARITY_THRESHOLD)
              .toCompletableFuture()
              .get(MATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      List<AgentEvent.AgentSentenceCite> out = new ArrayList<>();
      for (DocumentService.CitationMatchEntry m : result.matches()) {
        int sourceIndex = indexOfSource(sources, m.parentDocId(), m.chunkIndex());
        if (sourceIndex >= 0) {
          out.add(new AgentEvent.AgentSentenceCite(m.sentenceText(), sourceIndex, m.similarity()));
        }
      }
      return List.copyOf(out);
    } catch (Exception e) {
      // 565 §10 guard: never silent-zero into a dead feature — log + degrade to source-only.
      LOG.warn(
          "Answer↔source citation match failed/timed out ({}); citing sources without inline marks",
          e.toString());
      return List.of();
    }
  }

  /** The index of the source matching (parentDocId, chunkIndex); falls back to the same document. */
  private static int indexOfSource(
      List<AgentEvent.AgentSource> sources, String parentDocId, int chunkIndex) {
    int sameDoc = -1;
    for (int i = 0; i < sources.size(); i++) {
      AgentEvent.AgentSource s = sources.get(i);
      if (s.parentDocId().equals(parentDocId)) {
        if (s.chunkIndex() == chunkIndex) {
          return i;
        }
        if (sameDoc < 0) {
          sameDoc = i;
        }
      }
    }
    return sameDoc;
  }
}

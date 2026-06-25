package io.justsearch.app.services.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Tempdoc 580 §17 P4 — guard tests for the agent-citation contributor. */
class AgentCitationContributorTest {

  @Test
  void fromDoneEvent_citedSourcesAreCited_groundingNotCitedAreShown() {
    Map<String, Object> payload =
        Map.of(
            "sources",
                List.of(
                    Map.of("parentDocId", "d1", "chunkIndex", 0),
                    Map.of("parentDocId", "d2", "chunkIndex", 0),
                    Map.of("parentDocId", "d3", "chunkIndex", 1)),
            // sentences cite source index 0 (d1) and 2 (d3); d2 is grounding but uncited
            "citations",
                List.of(
                    Map.of("sentenceText", "s1", "sourceIndex", 0, "similarity", 0.9),
                    Map.of("sentenceText", "s2", "sourceIndex", 2, "similarity", 0.8)));

    List<ResultDisposition> out =
        AgentCitationContributor.fromDoneEvent("agent-iid", payload, 42L);

    assertEquals(3, out.size());
    Map<String, ResultDisposition.Kind> byDoc =
        out.stream().collect(Collectors.toMap(ResultDisposition::docId, ResultDisposition::kind));
    assertEquals(ResultDisposition.Kind.CITED, byDoc.get("d1"));
    assertEquals(ResultDisposition.Kind.SHOWN, byDoc.get("d2"));
    assertEquals(ResultDisposition.Kind.CITED, byDoc.get("d3"));
    out.forEach(
        d -> assertEquals(ResultDisposition.Contributor.AGENT_CITATION, d.contributor()));
  }

  @Test
  void fromDoneEvent_dedupsByParentDocId() {
    Map<String, Object> payload =
        Map.of(
            "sources",
                List.of(
                    Map.of("parentDocId", "dup", "chunkIndex", 0),
                    Map.of("parentDocId", "dup", "chunkIndex", 1)),
            "citations", List.of());
    assertEquals(1, AgentCitationContributor.fromDoneEvent("i", payload, 1L).size());
  }

  @Test
  void fromDoneEvent_emptyWhenNoSources() {
    assertEquals(
        0, AgentCitationContributor.fromDoneEvent("i", Map.of("citations", List.of()), 1L).size());
  }
}

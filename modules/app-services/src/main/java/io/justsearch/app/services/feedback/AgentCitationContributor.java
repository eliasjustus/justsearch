/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tempdoc 580 §17.3 / §16 (Track C P4) — the AGENT-CITATION contributor: harvests
 * {@link ResultDisposition}s from a persisted {@code AgentDone} event, feeding the one canonical
 * disposition stream (the §17.3 multi-contributor, anti-fork design).
 *
 * <p>The agentic signal (§16, code-verified) is already persisted as the answer's grounding
 * {@code sources} (each carrying {@code parentDocId}) and its per-sentence {@code citations} (each
 * carrying a {@code sourceIndex} into sources). This projects them to dispositions: a source the
 * answer <em>cited</em> → {@link ResultDisposition.Kind#CITED}; a grounding source it did <em>not</em>
 * cite → {@link ResultDisposition.Kind#SHOWN}. It is the LLM-as-judge tier (§16): dense and
 * day-one, but reorder-only and not user behaviour.
 *
 * <p>Tempdoc 580 §17 P4 (Fix B, resolved): these dispositions are keyed by the agent run's
 * {@code sessionId} (passed in by {@link AgentDispositionWiring} from the stamped event payload), and
 * the agent search path now captures a {@link FeatureSnapshot} under that SAME {@code sessionId} (from
 * each {@code tool_exec_completed}'s {@code feedbackFeatures}). So a CITED/SHOWN disposition JOINS its
 * ranking features and becomes a real training label — the §17.4 join the original P4 left unwired
 * (before, a fresh {@code agent-<UUID>} id matched no snapshot and every agent disposition was dropped).
 */
public final class AgentCitationContributor {

  private AgentCitationContributor() {}

  /**
   * Projects an {@code AgentDone} event payload to dispositions.
   *
   * @param interactionId the agent interaction correlation id
   * @param doneEventPayload the persisted "done" event payload (with {@code sources} + {@code citations})
   * @param occurredAtMs disposition timestamp
   * @return the harvested dispositions (one per distinct grounding source doc)
   */
  public static List<ResultDisposition> fromDoneEvent(
      String interactionId, Map<String, Object> doneEventPayload, long occurredAtMs) {
    List<?> sources =
        doneEventPayload.get("sources") instanceof List<?> s ? s : List.of();
    List<?> citations =
        doneEventPayload.get("citations") instanceof List<?> c ? c : List.of();

    // The docs the answer actually cited, resolved via each citation's sourceIndex → sources[i].
    Set<String> citedDocs = new HashSet<>();
    for (Object cite : citations) {
      if (cite instanceof Map<?, ?> cm && cm.get("sourceIndex") instanceof Number idx) {
        int i = idx.intValue();
        if (i >= 0 && i < sources.size() && sources.get(i) instanceof Map<?, ?> sm) {
          String pid = parentDocId(sm);
          if (pid != null) {
            citedDocs.add(pid);
          }
        }
      }
    }

    List<ResultDisposition> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Object src : sources) {
      if (src instanceof Map<?, ?> sm) {
        String pid = parentDocId(sm);
        if (pid != null && seen.add(pid)) {
          ResultDisposition.Kind kind =
              citedDocs.contains(pid)
                  ? ResultDisposition.Kind.CITED
                  : ResultDisposition.Kind.SHOWN;
          out.add(
              new ResultDisposition(
                  interactionId, pid, kind,
                  ResultDisposition.Contributor.AGENT_CITATION, occurredAtMs));
        }
      }
    }
    return out;
  }

  private static String parentDocId(Map<?, ?> sourceMap) {
    return sourceMap.get("parentDocId") instanceof String pid && !pid.isBlank() ? pid : null;
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Tempdoc 580 §17 P4 (Fix B) — wires the agent feedback contributors as a live listener on the agent
 * run-event stream. Two reactions, correlated by the run's {@code sessionId} (stamped on every event
 * payload by {@code AgentRunStore.appendEvent}):
 *
 * <ul>
 *   <li>On each {@code tool_exec_completed} carrying search {@code feedbackFeatures}, capture a
 *       {@link FeatureSnapshot} keyed by {@code sessionId} — the per-search ranking features (the §17.4
 *       join input the agent path previously lacked).
 *   <li>On {@code done}, project the answer's grounding sources + citations into {@link ResultDisposition}s
 *       keyed by the SAME {@code sessionId}, so a CITED/SHOWN disposition joins its FeatureSnapshot and
 *       becomes a real training label (the join the original P4 left unwired — agent dispositions used a
 *       fresh {@code agent-<UUID>} with no snapshot, so they were all dropped by {@code LabelProjection}).
 * </ul>
 *
 * <p>Takes a listener-registrar function rather than the agent store type, so the feedback package stays
 * decoupled from app-agent (only the caller, HeadAssembly, holds both). Best-effort: the underlying
 * {@link NdjsonAppendStore#append} swallows failures, so feedback never affects the loop.
 */
public final class AgentDispositionWiring {

  private AgentDispositionWiring() {}

  /**
   * Registers the contributors on the agent run-event stream.
   *
   * @param addEventListener the store's {@code addEventListener} (e.g. {@code agentRunStore::addEventListener})
   * @param dataDir the resolved data directory
   */
  public static void register(
      Consumer<BiConsumer<String, Map<String, Object>>> addEventListener, Path dataDir) {
    Path feedback = dataDir.resolve("feedback");
    NdjsonAppendStore<ResultDisposition> dispositions =
        new NdjsonAppendStore<>(
            feedback.resolve("result-dispositions.ndjson"), ResultDisposition.class);
    NdjsonAppendStore<FeatureSnapshot> snapshots =
        new NdjsonAppendStore<>(feedback.resolve("feature-snapshots.ndjson"), FeatureSnapshot.class);
    addEventListener.accept(
        (eventType, payload) -> {
          long now = Instant.now().toEpochMilli();
          String sessionId = str(payload.get("sessionId"));
          if ("tool_exec_completed".equals(eventType)) {
            captureAgentSnapshot(snapshots, sessionId, payload, now);
          } else if ("done".equals(eventType)) {
            // The run's sessionId is the join key. Fall back to a fresh id only if it is absent — then
            // the disposition is still recorded but won't join (the honest pre-fix behavior).
            String iid =
                sessionId != null && !sessionId.isBlank()
                    ? sessionId
                    : "agent-" + UUID.randomUUID();
            AgentCitationContributor.fromDoneEvent(iid, payload, now).forEach(dispositions::append);
          }
        });
  }

  /**
   * Capture the per-search {@link FeatureSnapshot} from a {@code tool_exec_completed} event's
   * {@code feedbackFeatures} (the §17 P4 feedback channel emitted by {@code SearchTool.buildSearchEvidence},
   * keyed by {@code parentDocId} — the same id-space agent dispositions reference). Multiple searches in
   * one run emit multiple snapshots under the same {@code sessionId}; {@code LabelProjection} unions them.
   */
  private static void captureAgentSnapshot(
      NdjsonAppendStore<FeatureSnapshot> store,
      String sessionId,
      Map<String, Object> payload,
      long now) {
    if (sessionId == null || sessionId.isBlank()) {
      return;
    }
    if (!(payload.get("structuredData") instanceof Map<?, ?> sd)) {
      return;
    }
    if (!(sd.get("feedbackFeatures") instanceof List<?> feats) || feats.isEmpty()) {
      return; // not a search tool result
    }
    List<FeatureSnapshot.HitFeatures> hits = new ArrayList<>();
    for (Object o : feats) {
      if (!(o instanceof Map<?, ?> f)) {
        continue;
      }
      String docId = str(f.get("docId"));
      if (docId == null || docId.isBlank()) {
        continue;
      }
      hits.add(
          new FeatureSnapshot.HitFeatures(
              docId,
              intOf(f.get("rank")),
              floatOf(f.get("sparse")),
              floatOf(f.get("dense")),
              floatOf(f.get("splade")),
              floatOf(f.get("fused")),
              null));
    }
    if (!hits.isEmpty()) {
      store.append(new FeatureSnapshot(sessionId, "agent-search", now, hits));
    }
  }

  private static String str(Object o) {
    return o instanceof String s ? s : null;
  }

  private static int intOf(Object o) {
    return o instanceof Number n ? n.intValue() : 0;
  }

  private static float floatOf(Object o) {
    return o instanceof Number n ? n.floatValue() : 0f;
  }
}

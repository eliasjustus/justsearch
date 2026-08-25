/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.conversation.ConversationStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link ConversationStore} that records what the engine wrote to it.
 *
 * <p>Lifted out of {@code SubstrateDrivenEngineTest} by tempdoc 863 slice A so the shape-driven
 * record tests in {@code ConversationEngineTest} assert against the SAME double the substrate-driven
 * ones do — the point of those tests is that both dispatch modes now write the same two turns, and a
 * second copy of the double would let them drift apart while both stayed green.
 *
 * <p>It is deliberately NOT {@link ConversationStore#noop()}: the engine refuses to stamp a run
 * against the no-op store (a store that keeps nothing cannot justify suppressing the run plane's own
 * record), so a test that wants the recording path must hand it a store that really keeps things.
 */
class RecordingStore implements ConversationStore {
  final Map<String, List<Map<String, Object>>> appended = new LinkedHashMap<>();
  final Map<String, List<String>> excludedSources = new LinkedHashMap<>();
  int loadHistoryCount;

  @Override
  public List<String> excludedSourceIds(String sessionId) {
    return excludedSources.getOrDefault(sessionId, List.of());
  }

  @Override
  public List<Map<String, Object>> loadHistory(String sessionId) {
    loadHistoryCount++;
    return List.of();
  }

  @Override
  public void appendMessage(String sessionId, String shapeId, Map<String, Object> message) {
    appended.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);
  }

  @Override
  public List<SessionSummary> listSessions(String shapeId, int limit) {
    return List.of();
  }

  @Override
  public Optional<SessionSummary> getSessionMeta(String sessionId) {
    return Optional.empty();
  }

  @Override
  public void deleteSession(String sessionId) {}

  @Override
  public void branchFrom(String parentSessionId, String branchPointMessageId, String newSessionId) {}

  @Override
  public void setContextFloor(String sessionId, String floorMessageId) {}

  @Override
  public List<Map<String, Object>> loadEffectiveContext(String sessionId) {
    // Tempdoc 610 Phase C — the engine now seeds PERSISTENT shapes from
    // loadEffectiveContext; with no floor it equals loadHistory, so delegate
    // (this also keeps the existing loadHistoryCount assertions valid).
    return loadHistory(sessionId);
  }

  @Override
  public void compactContext(String sessionId, String floorMessageId, String summaryText) {}

  @Override
  public void excludeMessage(String sessionId, String messageId, boolean excluded) {}

  @Override
  public List<String> excludedMessageIds(String sessionId) {
    return List.of();
  }
}

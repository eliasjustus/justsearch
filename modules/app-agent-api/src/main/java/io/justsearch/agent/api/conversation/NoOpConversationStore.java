/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * No-op implementation of {@link ConversationStore} for EPHEMERAL shapes.
 * All operations are silent no-ops; loadHistory returns empty.
 */
final class NoOpConversationStore implements ConversationStore {

  static final NoOpConversationStore INSTANCE = new NoOpConversationStore();

  private NoOpConversationStore() {}

  @Override
  public List<Map<String, Object>> loadHistory(String sessionId) {
    return List.of();
  }

  @Override
  public void appendMessage(String sessionId, String shapeId, Map<String, Object> message) {
    // no-op
  }

  @Override
  public List<SessionSummary> listSessions(String shapeId, int limit) {
    return List.of();
  }

  @Override
  public void deleteSession(String sessionId) {
    // no-op
  }

  @Override
  public Optional<SessionSummary> getSessionMeta(String sessionId) {
    return Optional.empty();
  }

  @Override
  public void branchFrom(
      String parentSessionId, String branchPointMessageId, String newSessionId) {
    // no-op
  }

  @Override
  public void setContextFloor(String sessionId, String floorMessageId) {
    // no-op
  }

  @Override
  public List<Map<String, Object>> loadEffectiveContext(String sessionId) {
    return List.of();
  }

  @Override
  public void compactContext(String sessionId, String floorMessageId, String summaryText) {
    // no-op
  }

  @Override
  public void excludeMessage(String sessionId, String messageId, boolean excluded) {
    // no-op
  }

  @Override
  public List<String> excludedMessageIds(String sessionId) {
    return List.of();
  }
}

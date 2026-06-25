/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.stream;

/**
 * Discriminator for {@link SseEnvelope#frameKind()}.
 *
 * <p>Per slice 436: every envelope frame is either a {@code update} (data payload) or a
 * {@code lifecycle} (connection-management signal). The lifecycle subkind is encoded
 * within the payload (e.g., {@code payload.kind = "heartbeat"}); this enum only
 * distinguishes the top-level data-vs-lifecycle role.
 */
public enum SseFrameKind {
  /** Data frame carrying a typed payload (e.g., catalog snapshot, condition update). */
  UPDATE,
  /** Lifecycle frame (connected, heartbeat, closing, error, reset, snapshot). */
  LIFECYCLE
}

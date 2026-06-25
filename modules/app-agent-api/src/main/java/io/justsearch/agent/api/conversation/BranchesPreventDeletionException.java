/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

import java.util.List;
import java.util.Objects;

/**
 * Slice 515 FIX-3 (relocated by slice 516 FIX-T3) — thrown by
 * {@link ConversationStore#deleteSession(String)} when the target session has
 * one or more child branches whose lazy {@code loadHistory} walk depends on
 * the parent's existence. Allowing the deletion would silently orphan the
 * branches' inherited prefix.
 *
 * <p>The controller catches this via the interface-module import (this file)
 * rather than the implementation class, restoring the abstraction symmetry.
 * Maps to HTTP 409 Conflict with {@code childSessionIds} in the response body
 * so the FE can offer a cascade-delete UX.
 *
 * <p>Extends {@link IllegalStateException} so callers that catch the standard
 * runtime exception type continue to work.
 */
public final class BranchesPreventDeletionException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  private final List<String> childSessionIds;

  public BranchesPreventDeletionException(String parentSessionId, List<String> childSessionIds) {
    super("Session "
        + Objects.requireNonNull(parentSessionId, "parentSessionId")
        + " has "
        + Objects.requireNonNull(childSessionIds, "childSessionIds").size()
        + " child branch(es); delete those first.");
    this.childSessionIds = List.copyOf(childSessionIds);
  }

  public List<String> childSessionIds() {
    return childSessionIds;
  }
}

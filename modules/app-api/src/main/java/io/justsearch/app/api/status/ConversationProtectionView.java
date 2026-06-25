/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tempdoc 629 (#2) — conversation (AUTHORED-store) at-rest encryption state for the {@code /api/status}
 * endpoint. The reactive, single-authority projection of the {@code DataKeyManager} key lifecycle:
 *
 * <ul>
 *   <li>{@code "not_configured"} — passphrase encryption is not set up.
 *   <li>{@code "locked"} — encryption is on but the data key is locked (history gated until unlock).
 *   <li>{@code "unlocked"} — encryption is on and the data key is in memory.
 * </ul>
 *
 * <p>This replaces the one-shot {@code GET /api/conversations/encryption} fetch as the FE's status
 * source: because it rides the polled {@code /api/status} pipe, the Health card + the locked-chat
 * affordance read one reactive authority and can never go stale (the bug the LAYER's dedicated
 * endpoint caused — 629 FE design §4). The POST control endpoints (setup/unlock/lock/…) stay.
 *
 * <p>{@code @JsonInclude(NON_NULL)} drops the field from the wire when absent (test-only paths).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationProtectionView(String state) {

  /** A view indicating the state could not be determined (test-only / pre-wire path). */
  public static ConversationProtectionView unknown() {
    return new ConversationProtectionView("unknown");
  }
}

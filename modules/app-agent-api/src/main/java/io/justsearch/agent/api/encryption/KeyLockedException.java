/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.encryption;

/**
 * Tempdoc 629 (LAYER) — thrown when an AUTHORED store is asked to read/write encrypted content while
 * the data-encryption key is LOCKED (encryption is configured but the user hasn't unlocked).
 *
 * <p>This is the typed "locked" signal that must be surfaced <em>above</em> the stores' existing
 * fail-soft read catches (which return empty on IO error) — otherwise locked data would render as
 * <em>deleted</em> rather than <em>locked</em> (629 confidence-probe L4). Controllers map this to a
 * "locked" response; the conversation surface projects it as an unlock affordance.
 */
public final class KeyLockedException extends RuntimeException {
  public KeyLockedException() {
    super("data-at-rest encryption key is locked");
  }
}

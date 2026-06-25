/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Operation criticality — informs how the dev-runner admission gate dispatches takeover requests
 * against a live op-lease. Tempdoc 542 Layer 1.
 *
 * <p>Criticality is declared at op-registration time; the dev-runner derives admission semantics
 * from the enum, so call sites never speak about admission policy directly.
 */
public enum OpCriticality {
  /**
   * Op tolerates routine takeover. Current {@code warn} semantics apply unchanged. Default for
   * status polls, search queries, settings reads.
   */
  INTERRUPTIBLE,

  /**
   * Op tolerates takeover but carries a structured "what was lost" signal in the resulting
   * stop-report. {@code warn} proceeds; the dev-runner stop-report tags the named op + loss
   * metadata. Default for medium ingests, partial bulk operations.
   */
  INTERRUPTIBLE_WITH_LOSS,

  /**
   * Op MUST complete to leave the system in a consistent state. {@code warn} upgrades to a
   * sync handshake ({@code HANDSHAKE_REQUIRED} response). {@code force} proceeds but produces
   * the loudest possible stop-report disposition. Default for migrations, bulk reindexes,
   * schema operations.
   */
  MUST_COMPLETE,

  /**
   * Op is unsafe to interrupt at all. {@code force} succeeds only with a typed confirmation
   * token matching the live opId ({@code REQUIRES_CONFIRMATION} response). Default for index-
   * corruption recovery, write-barrier migrations.
   */
  UNSAFE_TO_INTERRUPT;
}

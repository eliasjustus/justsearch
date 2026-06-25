/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.recovery;

import io.justsearch.configuration.resolved.ResolvedConfig;

/**
 * The single orchestration-layer authority for the corruption-recovery decision (tempdoc 628 Stage B).
 *
 * <p>Recovery decisions were previously scattered: the Lucene adapter ({@code RuntimeSession}) decided
 * backup-and-serve-empty for {@code CORRUPT_INDEX}, while {@code KnowledgeServer} separately decided
 * blue/green for {@code SCHEMA_MISMATCH} — two philosophies gated by two overloaded config keys in two
 * layers that could not see each other. This class is the one place that maps the unified
 * {@code index.recovery.policy} config into the recovery posture, so the worker's recovery behaviour is
 * decided in exactly one spot.
 *
 * <p>The adapter still performs the low-level backup-and-empty mechanism (it is well-tested), but it
 * cannot rebuild from source — {@code adapters-lucene} has no dependency on the blue/green migration
 * engine. So when the adapter has recovered an index to empty (signalled by an
 * {@code IndexRecoveryMarker}), this authority decides whether the worker then rebuilds it from the
 * source files still on disk (the G3 join) — never leaving a silently-empty index behind.
 */
public final class IndexRecoveryPolicy {

  /** The recovery posture for a detected index-corruption fault. */
  public enum RecoveryAction {
    /** Never auto-recover; surface loudly and let an operator decide (conservative). */
    FAIL_CLOSED,
    /** Back up the damaged index and serve empty, but do not auto-rebuild. */
    BACKUP_ONLY,
    /** Back up, serve degraded, and rebuild from the source files on disk (default). */
    BACKUP_REBUILD
  }

  private IndexRecoveryPolicy() {}

  /** Resolves the configured recovery posture (defaulting to {@code BACKUP_REBUILD}). */
  public static RecoveryAction fromConfig(ResolvedConfig.Index idx) {
    String policy = idx == null ? null : idx.indexRecoveryPolicy();
    if (policy == null) {
      return RecoveryAction.BACKUP_REBUILD;
    }
    return switch (policy.trim().toUpperCase(java.util.Locale.ROOT)) {
      case "FAIL_CLOSED" -> RecoveryAction.FAIL_CLOSED;
      case "BACKUP_ONLY" -> RecoveryAction.BACKUP_ONLY;
      default -> RecoveryAction.BACKUP_REBUILD;
    };
  }

  /**
   * Whether an index that the adapter just recovered to empty should be rebuilt from the source files
   * on disk. True for {@code BACKUP_REBUILD} (the default and G2 prod posture); false otherwise.
   */
  public static boolean shouldRebuildFromSource(ResolvedConfig.Index idx) {
    return fromConfig(idx) == RecoveryAction.BACKUP_REBUILD;
  }
}

package io.justsearch.indexerworker.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import org.junit.jupiter.api.Test;

/** Tempdoc 628 Stage B: the single corruption-recovery decision authority's table. */
class IndexRecoveryPolicyTest {

  private static ResolvedConfig.Index indexWithPolicy(String policy) {
    var b = ResolvedConfig.builder();
    if (policy != null) {
      b.putSettings("index.recovery.policy", policy);
    }
    return b.build().index();
  }

  @Test
  void defaultIsBackupRebuild() {
    ResolvedConfig.Index idx = indexWithPolicy(null);
    assertEquals(IndexRecoveryPolicy.RecoveryAction.BACKUP_REBUILD, IndexRecoveryPolicy.fromConfig(idx));
    assertTrue(
        IndexRecoveryPolicy.shouldRebuildFromSource(idx),
        "default (G2) must rebuild-from-source so corruption self-heals instead of serving empty");
  }

  @Test
  void failClosedDoesNotRebuild() {
    ResolvedConfig.Index idx = indexWithPolicy("FAIL_CLOSED");
    assertEquals(IndexRecoveryPolicy.RecoveryAction.FAIL_CLOSED, IndexRecoveryPolicy.fromConfig(idx));
    assertFalse(IndexRecoveryPolicy.shouldRebuildFromSource(idx));
  }

  @Test
  void backupOnlyDoesNotRebuild() {
    ResolvedConfig.Index idx = indexWithPolicy("backup_only");
    assertEquals(IndexRecoveryPolicy.RecoveryAction.BACKUP_ONLY, IndexRecoveryPolicy.fromConfig(idx));
    assertFalse(IndexRecoveryPolicy.shouldRebuildFromSource(idx));
  }
}

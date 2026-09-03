/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.configuration.resolved.ResolvedConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IndexStatusOps search-config compatibility tombstones")
final class IndexStatusOpsSearchConfigTest {

  @Test
  @DisplayName("entity boost remains field 9 and is always reported as zero")
  void entityBoostIsAZeroValuedCompatibilityTombstone() {
    IndexStatusOps ops = newOpsWithNullCollaborators();
    ResolvedConfig config = ResolvedConfig.builder().contributeEnvRegistry().build();
    ops.setResolvedConfigSupplier(() -> config);

    io.justsearch.ipc.SearchConfig searchConfig = ops.buildSearchConfig();

    assertEquals(
        9,
        searchConfig.getDescriptorForType().findFieldByName("entity_boost").getNumber(),
        "the retired field number remains reserved on the compatibility wire");
    assertEquals(0.0, searchConfig.getEntityBoost(), 0.0);
  }

  private static IndexStatusOps newOpsWithNullCollaborators() {
    return new IndexStatusOps(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        0L);
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/**
 * Grouped index schema compatibility status for structured /api/status response.
 */
public record SchemaStatusGroup(
    long observedAtMs,
    String fpCurrent,
    String fpStored,
    String compatState,
    boolean reindexRequired,
    String reindexRequiredReason) {

  public static SchemaStatusGroup from(WorkerOperationalView w) {
    return new SchemaStatusGroup(
        System.currentTimeMillis(),
        w.compatibility().indexSchemaFpCurrent(),
        w.compatibility().indexSchemaFpStored(),
        w.compatibility().indexSchemaCompatState(),
        w.compatibility().reindexRequired(),
        w.compatibility().reindexRequiredReason());
  }
}

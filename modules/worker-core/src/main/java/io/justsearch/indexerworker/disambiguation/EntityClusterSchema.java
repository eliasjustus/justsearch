/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.disambiguation;

/**
 * DDL for the entity-clusters.db database.
 *
 * <p>Two tables: {@code entity_clusters} maps raw entity forms to cluster IDs and canonical forms;
 * {@code entity_overrides} records user-initiated merge/split operations.
 */
public final class EntityClusterSchema {
  private EntityClusterSchema() {}

  public static final String CREATE_CLUSTERS_TABLE =
      """
      CREATE TABLE IF NOT EXISTS entity_clusters (
        raw_form TEXT NOT NULL,
        entity_type TEXT NOT NULL,
        cluster_id TEXT NOT NULL,
        canonical_form TEXT NOT NULL,
        confidence REAL NOT NULL DEFAULT 1.0,
        created_at_ms INTEGER NOT NULL,
        updated_at_ms INTEGER NOT NULL,
        PRIMARY KEY (raw_form, entity_type)
      )
      """;

  public static final String CREATE_CLUSTER_ID_INDEX =
      """
      CREATE INDEX IF NOT EXISTS idx_ec_cluster
        ON entity_clusters(cluster_id)
      """;

  public static final String CREATE_CANONICAL_INDEX =
      """
      CREATE INDEX IF NOT EXISTS idx_ec_canonical
        ON entity_clusters(canonical_form, entity_type)
      """;

  public static final String CREATE_OVERRIDES_TABLE =
      """
      CREATE TABLE IF NOT EXISTS entity_overrides (
        override_id TEXT PRIMARY KEY,
        override_type TEXT NOT NULL CHECK (override_type IN ('MERGE', 'SPLIT')),
        entity_type TEXT NOT NULL,
        source_forms TEXT NOT NULL,
        target_canonical TEXT,
        created_at_ms INTEGER NOT NULL
      )
      """;
}

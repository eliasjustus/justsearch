/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.disambiguation;

/** A single cluster entry representing a raw-to-canonical entity mapping. */
public record ClusterEntry(
    String rawForm,
    String entityType,
    String clusterId,
    String canonicalForm,
    double confidence,
    long createdAtMs,
    long updatedAtMs) {}

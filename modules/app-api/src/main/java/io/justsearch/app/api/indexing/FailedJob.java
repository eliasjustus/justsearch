/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.indexing;

/**
 * One permanently-failed indexing job (state=FAILED), as shown in the Health surface's failed-jobs
 * list. Mirrors {@code IndexingService.FailedJobInfo} projected to the wire.
 *
 * <p>Tempdoc 564 Phase 5: the failed-jobs surface becomes record-backed so the FE validates it
 * against a generated JSON-Schema → Zod projection instead of an unchecked raw cast; the wire JSON is
 * unchanged.
 */
public record FailedJob(
    String path, String errorMessage, int attempts, long lastUpdatedMs, String collection) {}

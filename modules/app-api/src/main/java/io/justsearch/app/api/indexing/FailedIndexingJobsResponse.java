/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.indexing;

import io.justsearch.agent.api.registry.PreciseWire;
import java.util.List;

/**
 * Envelope for the substrate-shaped failed-jobs surfaces — {@code GET /api/indexing-jobs/failed}
 * and its folder-scoped sibling {@code GET /api/indexing-jobs/failed/by-prefix}.
 *
 * <p>Tempdoc 911 (885 UL.9): both endpoints previously hand-built {@code Map<String,Object>} rows
 * that were <em>almost</em> an {@link IndexingJobView} — they omitted {@code scanId} — so no
 * schema described the wire and the FE read {@code state} off untyped JSON. One record for both,
 * because they are one shape: a second envelope declaring the same fields would be a fork, not a
 * projection.
 *
 * <p>{@code scanId} is present-but-empty here: {@code IndexingService.FailedJobInfo} (the Head's
 * only source for these rows) does not carry the scan id, and {@link IndexingJobView}'s contract
 * already spells "unknown scan" as {@code ""}. Present-and-empty is the honest projection; dropping
 * the key was what made the payload un-typeable.
 */
public record FailedIndexingJobsResponse(List<IndexingJobView> jobs, int count)
    implements PreciseWire {}

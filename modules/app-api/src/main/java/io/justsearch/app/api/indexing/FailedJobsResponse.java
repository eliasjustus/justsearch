/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.indexing;

import java.util.List;

/**
 * Envelope for {@code GET /api/indexing/failed-jobs} — the permanently-failed indexing jobs plus a
 * count.
 *
 * <p>Tempdoc 564 Phase 5: the generated wire-contract surface the FE validates at the parse boundary.
 */
public record FailedJobsResponse(List<FailedJob> jobs, int count) {}

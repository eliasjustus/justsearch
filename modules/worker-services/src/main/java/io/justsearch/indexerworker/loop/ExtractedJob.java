/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.extract.ValidatedExtractionArtifact;
import java.nio.file.Path;

/**
 * Intermediate state for a job that passed pre-checks and was extracted.
 *
 * <p>Tempdoc 516 Slice 3: lifted from {@code IndexingLoop}'s private nested record
 * so the upcoming Slice 4a {@code JobBatchExtractor} can return it without a circular
 * package dependency on the loop class. The record shape is unchanged.
 */
public record ExtractedJob(
    Path filePath,
    String collection,
    ValidatedExtractionArtifact artifact,
    long startTime,
    FileEnvelope envelope) {}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.nio.file.Path;

/** Failure-domain boundary for parser execution. */
public interface ExtractionSandbox {
  ExtractionArtifact extract(Path file) throws IOException, ContentExtractor.ExtractionException;
}

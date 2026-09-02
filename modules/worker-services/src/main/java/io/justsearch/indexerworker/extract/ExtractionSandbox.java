/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.nio.file.Path;

/** Failure-domain boundary for parser execution. */
public interface ExtractionSandbox extends AutoCloseable {
  ExtractionArtifact extract(Path file) throws IOException, ContentExtractor.ExtractionException;

  /**
   * The extraction policy this sandbox enforces. Hoisted onto the interface by tempdoc 885 so
   * {@code TimeboxedContentExtractor} no longer has to {@code instanceof}-chain over the
   * implementations (which silently returned the defaults for any sandbox it did not know).
   */
  default TikaExtractionPolicy policy() {
    return TikaExtractionPolicy.defaults();
  }

  /**
   * Releases process-level resources. In-process sandboxes have none; the persistent pool kills
   * its children here, which is how the Worker's shutdown path leaves no orphan.
   */
  @Override
  default void close() {}
}

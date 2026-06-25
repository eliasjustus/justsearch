/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import java.nio.file.Path;

/** Trusted source metadata captured at the Worker ingestion boundary. */
record FileEnvelope(
    Path originalPath,
    String normalizedPath,
    String pathHash,
    Object fileKey,
    long sizeBytes,
    long modifiedAtMs,
    boolean regularFile,
    long observedAtMs) {

  static FileEnvelope fromSnapshot(FileFreshnessSnapshot snapshot) {
    return new FileEnvelope(
        snapshot.originalPath(),
        snapshot.normalizedPath(),
        snapshot.pathHash(),
        snapshot.fileKey(),
        snapshot.sizeBytes(),
        snapshot.modifiedAtMs(),
        snapshot.regularFile(),
        snapshot.observedAtMs());
  }
}

package io.justsearch.indexerworker.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 915 §C — {@code CHUNK_THRESHOLD_CHARS} and {@code CONTENT_PREVIEW_MAX_CHARS} decide what
 * is written to disk (whether chunk documents exist at all, and how much of a document lands in the
 * stored {@code content_preview}), so both are {@code index_fingerprint} inputs.
 *
 * <p>They are mirrored into {@code adapters-lucene} rather than imported, because that module must
 * not depend on {@code worker-services}. A mirror that drifts silently is worse than the dependency
 * it avoids: the fingerprint would keep claiming a shape the writer no longer produces. This test
 * is the only thing standing between the two copies, so it lives here, where both are visible.
 */
final class ChunkWriterFingerprintMirrorTest {

  @Test
  void theFingerprintMirrorsTheChunkWriterConstants() {
    assertEquals(
        ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS,
        SsotCommitMetadataSource.CHUNK_THRESHOLD_CHARS,
        "CHUNK_THRESHOLD_CHARS drifted: update SsotCommitMetadataSource to match"
            + " ChunkDocumentWriter, and expect the change to invalidate existing indexes");
    assertEquals(
        ChunkDocumentWriter.CONTENT_PREVIEW_MAX_CHARS,
        SsotCommitMetadataSource.CONTENT_PREVIEW_MAX_CHARS,
        "CONTENT_PREVIEW_MAX_CHARS drifted: update SsotCommitMetadataSource to match"
            + " ChunkDocumentWriter, and expect the change to invalidate existing indexes");
  }
}

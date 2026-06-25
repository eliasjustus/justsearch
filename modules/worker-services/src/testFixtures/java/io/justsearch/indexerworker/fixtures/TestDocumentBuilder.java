package io.justsearch.indexerworker.fixtures;

import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.ValidatedExtractionArtifact;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps.ParentIndexMetadata;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import java.nio.file.Path;
import org.slf4j.LoggerFactory;

/**
 * Centralizes document building for tests by routing through the validated-artifact entry point.
 *
 * <p>Production code can only build documents via {@link
 * IndexingDocumentOps#buildDocument(Path, ValidatedExtractionArtifact, String, WorkerSignalBus,
 * io.justsearch.indexerworker.embed.EmbeddingProvider, boolean,
 * io.justsearch.indexerworker.splade.SpladeEncoder, ParentIndexMetadata,
 * IndexingDocumentOps.StageRecorder, org.slf4j.Logger, float[],
 * IndexingDocumentOps.SourceFileMetadata)}, which requires a {@link ValidatedExtractionArtifact}.
 * Tests use this fixture to wrap an {@link ExtractionResult} into a default-policy artifact
 * without restating the wrap chain at every call site.
 */
public final class TestDocumentBuilder {

  private static final IndexingDocumentOps.StageRecorder NOOP_RECORDER =
      (stageId, durationMs, reasonCode) -> {};

  private TestDocumentBuilder() {}

  /** Wraps an {@link ExtractionResult} into a default-policy validated artifact for tests. */
  public static ValidatedExtractionArtifact validatedArtifact(ExtractionResult extraction) {
    ExtractionArtifact artifact =
        ExtractionArtifact.full(
            extraction, TikaExtractionPolicy.defaults(), "test-fixture", false);
    try {
      return artifact.validate(TikaExtractionPolicy.defaults(), null);
    } catch (ContentExtractor.ExtractionException e) {
      throw new IllegalStateException("Test fixture artifact failed validation", e);
    }
  }

  /** Builds an IndexDocument with no collection, no signals, no embedding, no SPLADE. */
  public static IndexDocument buildDocument(Path filePath, ExtractionResult extraction) {
    return buildDocument(filePath, extraction, null);
  }

  /** Builds an IndexDocument with an explicit collection. */
  public static IndexDocument buildDocument(
      Path filePath, ExtractionResult extraction, String collection) {
    return buildDocument(filePath, extraction, collection, null, null);
  }

  /** Builds an IndexDocument with an explicit collection, signal bus, and parent metadata. */
  public static IndexDocument buildDocument(
      Path filePath,
      ExtractionResult extraction,
      String collection,
      WorkerSignalBus signalBus,
      ParentIndexMetadata parentMetadata) {
    return IndexingDocumentOps.buildDocument(
        filePath,
        validatedArtifact(extraction),
        collection,
        signalBus,
        /* embeddingProvider */ null,
        /* allowEmbeddingWrites */ false,
        /* spladeEncoder */ null,
        parentMetadata,
        NOOP_RECORDER,
        LoggerFactory.getLogger(TestDocumentBuilder.class),
        /* precomputedEmbedding */ null,
        /* sourceMetadata */ null);
  }
}

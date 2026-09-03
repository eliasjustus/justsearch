/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.Objects;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.lucene104.Lucene104HnswScalarQuantizedVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104ScalarQuantizedVectorsFormat.ScalarEncoding;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;

/**
 * Restart-safe codec for JustSearch vector indexes.
 *
 * <p>Lucene persists the outer codec name in each segment and recreates that codec through its
 * service loader when an index is reopened. A codec that returns one directly configured vector
 * format therefore cannot safely write both Float32 and quantized segments under the same name.
 * This version always returns {@link PerFieldKnnVectorsFormat}; Lucene then records the concrete
 * format name and suffix in each vector field's metadata and reads that stored format after a
 * restart, independently of the current configuration.
 */
public final class JustSearchCodecV2 extends FilterCodec {
  public static final String NAME = "JustSearchCodecV2";

  private final PerFieldKnnVectorsFormat perFieldFormat;

  /** Creates the shipped codec: Int8 scalar-quantized HNSW for every vector field. */
  public JustSearchCodecV2() {
    this(quantizedFormat());
  }

  /**
   * Creates a restart-safe codec using {@code writeFormat} for every vector field written by this
   * instance. The actual format is persisted by Lucene's per-field wrapper.
   */
  public JustSearchCodecV2(KnnVectorsFormat writeFormat) {
    super(NAME, new Lucene104Codec());
    this.perFieldFormat = new FixedPerFieldKnnVectorsFormat(writeFormat);
  }

  @Override
  public KnnVectorsFormat knnVectorsFormat() {
    return perFieldFormat;
  }

  /** Creates a non-quantized Float32 HNSW format. */
  public static KnnVectorsFormat float32Format() {
    return float32Format(
        ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_M,
        ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_EF_CONSTRUCTION);
  }

  public static KnnVectorsFormat float32Format(int m, int efConstruction) {
    return new Lucene99HnswVectorsFormat(m, efConstruction);
  }

  /** Creates the default unsigned-byte scalar-quantized HNSW format (8 bits per dimension). */
  public static KnnVectorsFormat quantizedFormat() {
    return quantizedFormat(
        ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_M,
        ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_EF_CONSTRUCTION);
  }

  public static KnnVectorsFormat quantizedFormat(int m, int efConstruction) {
    return new Lucene104HnswScalarQuantizedVectorsFormat(
        ScalarEncoding.UNSIGNED_BYTE, m, efConstruction);
  }

  private static final class FixedPerFieldKnnVectorsFormat extends PerFieldKnnVectorsFormat {
    private final KnnVectorsFormat writeFormat;

    private FixedPerFieldKnnVectorsFormat(KnnVectorsFormat writeFormat) {
      this.writeFormat = Objects.requireNonNull(writeFormat, "writeFormat");
    }

    @Override
    public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
      return writeFormat;
    }
  }
}

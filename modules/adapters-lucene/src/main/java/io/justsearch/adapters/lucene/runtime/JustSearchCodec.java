/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.util.Objects;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.lucene104.Lucene104HnswScalarQuantizedVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;

/**
 * Codec wrapper that installs Int8 scalar-quantized HNSW vectors format.
 *
 * <p>This "Pragmatic Codec" reduces vector memory footprint by ~75% compared to Float32:
 * <ul>
 *   <li>Float32: 768 dims Ã— 4 bytes = 3,072 bytes/doc</li>
 *   <li>Int8: 768 dims Ã— 1 byte = 768 bytes/doc</li>
 * </ul>
 *
 * <p>Configuration:
 * <ul>
 *   <li><b>Quantization:</b> 7-bit scalar (Int8)</li>
 *   <li><b>Confidence Interval:</b> 0.99 (dynamic range calculation)</li>
 *   <li><b>HNSW M:</b> 16 (connections per node)</li>
 *   <li><b>HNSW efConstruction:</b> 200 (build-time accuracy)</li>
 * </ul>
 */
public final class JustSearchCodec extends FilterCodec {
  private static final String NAME = "JustSearchCodec";

  /** HNSW M parameter: max connections per node (default 16) */
  private static final int DEFAULT_M = 16;

  /** HNSW efConstruction: build-time beam width (default 200) */
  private static final int DEFAULT_EF_CONSTRUCTION = 200;

  private final KnnVectorsFormat knnFormat;

  public JustSearchCodec() {
    // Default to Float32 for backwards compatibility with existing indexes.
    // New indexes should use RuntimeConfig.vector().quantizationEnabledOrDefault() via LuceneLifecycleManager,
    // which passes the appropriate format to JustSearchCodec(format).
    // Tested: Quantization works with Lucene 10.3.1 (VectorQuantizationGate 5K/20K/50K docs all pass).
    this(float32Format());
  }

  public JustSearchCodec(KnnVectorsFormat format) {
    super(NAME, new Lucene104Codec());
    this.knnFormat = Objects.requireNonNull(format, "format");
  }

  @Override
  public KnnVectorsFormat knnVectorsFormat() {
    return knnFormat;
  }

  /**
   * Creates a non-quantized Float32 HNSW format (for testing or when precision is critical).
   */
  public static KnnVectorsFormat float32Format() {
    return float32Format(DEFAULT_M, DEFAULT_EF_CONSTRUCTION);
  }

  public static KnnVectorsFormat float32Format(int m, int efConstruction) {
    return new Lucene99HnswVectorsFormat(m, efConstruction);
  }

  /**
   * F6: Creates an Int8 scalar-quantized HNSW format for reduced memory footprint.
   *
   * <p>Reduces vector memory by ~75% compared to Float32:
   * <ul>
   *   <li>Float32: 768 dims Ã— 4 bytes = 3,072 bytes/doc</li>
   *   <li>Int8: 768 dims Ã— 1 byte = 768 bytes/doc</li>
   * </ul>
   *
   * <p>Uses default 7-bit scalar quantization with 0.99 confidence interval.
   *
   * @return quantized HNSW vectors format
   */
  public static KnnVectorsFormat quantizedFormat() {
    return quantizedFormat(DEFAULT_M, DEFAULT_EF_CONSTRUCTION);
  }

  public static KnnVectorsFormat quantizedFormat(int m, int efConstruction) {
    return new Lucene104HnswScalarQuantizedVectorsFormat(m, efConstruction);
  }
}

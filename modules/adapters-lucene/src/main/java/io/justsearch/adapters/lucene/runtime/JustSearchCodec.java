/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.util.Objects;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;

/**
 * Legacy codec retained so existing Float32 indexes whose segments name {@code JustSearchCodec}
 * remain readable.
 *
 * <p>New writes must use {@link JustSearchCodecV2}. This class deliberately defaults to Float32
 * because old segments persisted only this outer name and cannot reconstruct a caller-selected
 * quantized format after restart.
 */
public final class JustSearchCodec extends FilterCodec {
  private static final String NAME = "JustSearchCodec";

  private final KnnVectorsFormat knnFormat;

  public JustSearchCodec() {
    // Do not change this default: legacy segments persist only the outer codec name. New writes use
    // JustSearchCodecV2, whose PerFieldKnnVectorsFormat persists the concrete vector format.
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
    return JustSearchCodecV2.float32Format();
  }

  public static KnnVectorsFormat float32Format(int m, int efConstruction) {
    return JustSearchCodecV2.float32Format(m, efConstruction);
  }

  /** Creates the same unsigned-byte scalar-quantized HNSW writer used by V2. */
  public static KnnVectorsFormat quantizedFormat() {
    return JustSearchCodecV2.quantizedFormat();
  }

  public static KnnVectorsFormat quantizedFormat(int m, int efConstruction) {
    return JustSearchCodecV2.quantizedFormat(m, efConstruction);
  }
}

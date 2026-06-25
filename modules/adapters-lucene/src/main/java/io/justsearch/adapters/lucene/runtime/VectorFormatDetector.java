/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.store.Directory;

/**
 * Detects the vector format used in a Lucene index.
 *
 * <p>Primary detection uses commit metadata stamped by the writer.
 * Segment-level inspection is best-effort (may not work for all configurations
 * due to Lucene's codec ServiceLoader re-instantiation).
 *
 * <p>Usage:
 * <pre>
 * try (DirectoryReader reader = DirectoryReader.open(directory)) {
 *   VectorFormatDetector.Summary summary = VectorFormatDetector.inspect(reader);
 *   System.out.println("Overall: " + summary.overallState());
 * }
 * </pre>
 */
public final class VectorFormatDetector {

  /** Vector format types. */
  public enum FormatType {
    /** Standard Float32 HNSW vectors (Lucene99HnswVectorsFormat). */
    FLOAT32,
    /** Int8 scalar-quantized HNSW vectors (Lucene104HnswScalarQuantizedVectorsFormat). */
    INT8_SQ,
    /** Unknown or unrecognized format. */
    UNKNOWN
  }

  /** Per-segment vector format information. */
  public record SegmentVectorFormat(String segmentName, FormatType format) {}

  /**
   * Summary of vector formats across all segments in an index.
   *
   * @param segments per-segment format details (best-effort)
   * @param float32Count count of segments detected as Float32
   * @param quantizedCount count of segments detected as quantized
   * @param overallState aggregate state: "FLOAT32" | "INT8_SQ" | "MIXED" | "UNKNOWN"
   */
  public record Summary(
      List<SegmentVectorFormat> segments,
      int float32Count,
      int quantizedCount,
      String overallState
  ) {}

  private VectorFormatDetector() {}

  /**
   * Inspects the index to determine vector format.
   *
   * <p>Uses commit metadata if available, falling back to segment inspection.
   *
   * @param reader an open DirectoryReader
   * @return summary of vector formats; never null
   */
  public static Summary inspect(DirectoryReader reader) {
    if (reader == null) {
      return new Summary(List.of(), 0, 0, "UNKNOWN");
    }

    // Try commit metadata first (authoritative when present)
    try {
      Map<String, String> userData = reader.getIndexCommit().getUserData();
      String storedFormat = userData.get("vector_format");
      if (storedFormat != null && !storedFormat.isBlank()) {
        int segmentCount = reader.leaves().size();
        if ("int8_sq".equalsIgnoreCase(storedFormat)) {
          return new Summary(List.of(), 0, segmentCount, "INT8_SQ");
        } else if ("float32".equalsIgnoreCase(storedFormat)) {
          return new Summary(List.of(), segmentCount, 0, "FLOAT32");
        }
      }
    } catch (IOException ignored) {
      // Fall through to segment inspection
    }

    // Fallback: best-effort segment inspection
    return inspectSegments(reader);
  }

  /**
   * Inspects segments to detect vector format (best-effort).
   *
   * <p>Note: Due to Lucene's ServiceLoader codec instantiation, this may not
   * correctly detect quantized formats if the codec's default constructor
   * doesn't preserve the format choice. Use commit metadata for authoritative detection.
   */
  public static Summary inspectSegments(DirectoryReader reader) {
    if (reader == null) {
      return new Summary(List.of(), 0, 0, "UNKNOWN");
    }

    List<SegmentVectorFormat> segments = new ArrayList<>();
    int float32 = 0;
    int quantized = 0;

    for (LeafReaderContext ctx : reader.leaves()) {
      if (ctx.reader() instanceof SegmentReader sr) {
        String name = sr.getSegmentName();
        String knnFormatClass = sr.getSegmentInfo().info.getCodec()
            .knnVectorsFormat().getClass().getSimpleName();

        FormatType type;
        if (knnFormatClass.contains("ScalarQuantized")) {
          type = FormatType.INT8_SQ;
          quantized++;
        } else if (knnFormatClass.contains("Hnsw")) {
          type = FormatType.FLOAT32;
          float32++;
        } else {
          type = FormatType.UNKNOWN;
        }
        segments.add(new SegmentVectorFormat(name, type));
      }
    }

    String overall;
    if (segments.isEmpty()) {
      overall = "UNKNOWN";
    } else if (quantized > 0 && float32 > 0) {
      overall = "MIXED";
    } else if (quantized > 0) {
      overall = "INT8_SQ";
    } else if (float32 > 0) {
      overall = "FLOAT32";
    } else {
      overall = "UNKNOWN";
    }

    return new Summary(List.copyOf(segments), float32, quantized, overall);
  }
}

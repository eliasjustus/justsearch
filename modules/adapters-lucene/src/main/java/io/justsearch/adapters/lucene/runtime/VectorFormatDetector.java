/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104HnswScalarQuantizedVectorsFormat;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FilterCodecReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.store.Directory;

/**
 * Detects the vector format used in a Lucene index.
 *
 * <p>Primary detection uses the concrete per-field vector-format name that Lucene persists in each
 * segment. Commit metadata is a fallback only for empty indexes or indexes with no vector-bearing
 * segments; it reports the configured write policy without inventing vector-segment counts.
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
  private static final String FLOAT32_FORMAT_NAME = "Lucene99HnswVectorsFormat";

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
   * <p>Uses segment metadata when available, falling back to commit metadata.
   *
   * @param reader an open DirectoryReader
   * @return summary of vector formats; never null
   */
  public static Summary inspect(DirectoryReader reader) {
    if (reader == null) {
      return new Summary(List.of(), 0, 0, "UNKNOWN");
    }

    Summary segmentSummary = inspectSegments(reader);
    if (!segmentSummary.segments().isEmpty()) {
      return segmentSummary;
    }

    // Only an index with no vector-bearing segments may fall back to the commit-wide write policy.
    // An unrecognized vector-bearing segment stays UNKNOWN rather than being relabelled by metadata
    // that may describe a later writer configuration.
    try {
      Map<String, String> userData = reader.getIndexCommit().getUserData();
      String storedFormat = userData.get("vector_format");
      if (storedFormat != null && !storedFormat.isBlank()) {
        if ("int8_sq".equalsIgnoreCase(storedFormat)) {
          return new Summary(List.of(), 0, 0, "INT8_SQ");
        } else if ("float32".equalsIgnoreCase(storedFormat)) {
          return new Summary(List.of(), 0, 0, "FLOAT32");
        }
      }
    } catch (IOException ignored) {
      // Preserve the UNKNOWN segment summary when commit metadata is unreadable.
    }

    return segmentSummary;
  }

  /**
   * Inspects the concrete format name stored on each vector field. Legacy non-per-field segments
   * fall back to the segment codec's vector format class.
   */
  public static Summary inspectSegments(DirectoryReader reader) {
    if (reader == null) {
      return new Summary(List.of(), 0, 0, "UNKNOWN");
    }

    List<SegmentVectorFormat> segments = new ArrayList<>();
    int float32 = 0;
    int quantized = 0;
    int unknown = 0;

    for (LeafReaderContext ctx : reader.leaves()) {
      SegmentReader sr = unwrapSegmentReader(ctx.reader());
      if (sr != null) {
        String name = sr.getSegmentName();
        List<String> storedFormatNames = new ArrayList<>();
        int vectorFieldCount = 0;
        for (FieldInfo fieldInfo : sr.getFieldInfos()) {
          if (!fieldInfo.hasVectorValues()) continue;
          vectorFieldCount++;
          String storedName =
              fieldInfo.getAttribute(PerFieldKnnVectorsFormat.PER_FIELD_FORMAT_KEY);
          if (storedName != null) {
            storedFormatNames.add(storedName);
          }
        }
        if (vectorFieldCount == 0) continue;
        KnnVectorsFormat legacyFormat = sr.getSegmentInfo().info.getCodec().knnVectorsFormat();
        FormatType type =
            resolveSegmentFormat(storedFormatNames, vectorFieldCount, legacyFormat.getName());
        if (type == FormatType.INT8_SQ) quantized++;
        if (type == FormatType.FLOAT32) float32++;
        if (type == FormatType.UNKNOWN) unknown++;
        segments.add(new SegmentVectorFormat(name, type));
      }
    }

    String overall;
    if (segments.isEmpty()) {
      overall = "UNKNOWN";
    } else if (unknown > 0) {
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

  static FormatType resolveSegmentFormat(
      List<String> storedFormatNames, int vectorFieldCount, String legacyFormatName) {
    if (vectorFieldCount <= 0) {
      return FormatType.UNKNOWN;
    }
    if (storedFormatNames.isEmpty()) {
      return classifyFormatName(legacyFormatName);
    }
    if (storedFormatNames.size() != vectorFieldCount) {
      return FormatType.UNKNOWN;
    }

    FormatType resolved = null;
    for (String storedName : storedFormatNames) {
      FormatType current = classifyFormatName(storedName);
      if (current == FormatType.UNKNOWN) {
        return FormatType.UNKNOWN;
      }
      if (resolved != null && resolved != current) {
        return FormatType.UNKNOWN;
      }
      resolved = current;
    }
    return resolved == null ? FormatType.UNKNOWN : resolved;
  }

  private static FormatType classifyFormatName(String name) {
    // Lucene 10.4 persists this scalar-quantized format under the historical
    // "Lucene104HnswBinaryQuantizedVectorsFormat" NAME. JustSearchCodecV2 is the authority that
    // constrains production writes with that name to ScalarEncoding.UNSIGNED_BYTE.
    if (Lucene104HnswScalarQuantizedVectorsFormat.NAME.equals(name)) {
      return FormatType.INT8_SQ;
    }
    if (FLOAT32_FORMAT_NAME.equals(name)) {
      return FormatType.FLOAT32;
    }
    return FormatType.UNKNOWN;
  }

  private static SegmentReader unwrapSegmentReader(LeafReader reader) {
    LeafReader candidate = reader;
    if (candidate instanceof CodecReader codecReader) {
      candidate = FilterCodecReader.unwrap(codecReader);
    }
    candidate = FilterLeafReader.unwrap(candidate);
    return candidate instanceof SegmentReader segmentReader ? segmentReader : null;
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpl;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent store for GPL (Generative Pseudo Labels) training triples.
 *
 * <p>Appends records as NDJSON to a single file in the data directory. Each line is a
 * self-contained JSON object. File is created on first write; subsequent writes append atomically.
 *
 * <p>Two formats are supported in the same file:
 *
 * <p>Legacy format (no feature columns):
 *
 * <pre>
 * {"doc_id":"...","synthetic_query":"...","score":0.85,"timestamp_ms":1234567890}
 * </pre>
 *
 * <p>Extended format (with feature columns, written by {@link #appendWithFeatures}):
 *
 * <pre>
 * {"query_id":"doc-abc#0","doc_id":"...","synthetic_query":"...","score":0.87,
 *  "is_negative":false,"sparse":12.4,"vector":0.73,"whole_sparse":12.4,"whole_vector":0.73,
 *  "whole_splade":0.21,"whole_cc":0.84,"chunk_sparse":0.0,"chunk_vector":0.0,
 *  "chunk_splade":0.0,"chunk_cc":0.0,"parent_token_count":980,"qpp_max_idf":8.2,
 *  "qpp_avg_ictf":6.1,"qpp_query_scope":0.33,"rank_position":1,"timestamp_ms":1234567890}
 * </pre>
 *
 * <p>The training runner must handle both formats; fields absent in legacy records are treated as
 * unknown by the LambdaMART feature builder.
 *
 * <p>Thread safety: {@link #append} and {@link #appendWithFeatures} are synchronized so concurrent
 * calls from multiple threads within the same coordinator instance do not interleave writes.
 */
public final class GplTrainingTripleStore {
  private static final Logger log = LoggerFactory.getLogger(GplTrainingTripleStore.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Named Stage 3A GPL feature payload kept additive so LambdaMART V1 remains compatible. */
  public record FeaturePayload(
      float sparse,
      float vector,
      float wholeSparse,
      float wholeVector,
      float wholeSplade,
      float wholeCc,
      float chunkSparse,
      float chunkVector,
      float chunkSplade,
      float chunkCc,
      float branchWhole,
      float branchChunk,
      float branchCc,
      boolean branchPresentWhole,
      boolean branchPresentChunk,
      float branchWeightWhole,
      float branchWeightChunk,
      float branchEffectiveWeightWhole,
      float branchEffectiveWeightChunk,
      float branchModifierWhole,
      float branchModifierChunk,
      Long parentTokenCount,
      float qppMaxIdf,
      float qppAvgIctf,
      float qppQueryScope,
      int rankPosition,
      long timestampMs) {

    public static Builder builder() {
      return new Builder();
    }

    /** Small mutable builder so GPL callers do not need a 16-argument constructor. */
    public static final class Builder {
      private float sparse;
      private float vector;
      private float wholeSparse;
      private float wholeVector;
      private float wholeSplade;
      private float wholeCc;
      private float chunkSparse;
      private float chunkVector;
      private float chunkSplade;
      private float chunkCc;
      private float branchWhole;
      private float branchChunk;
      private float branchCc;
      private boolean branchPresentWhole;
      private boolean branchPresentChunk;
      private float branchWeightWhole;
      private float branchWeightChunk;
      private float branchEffectiveWeightWhole;
      private float branchEffectiveWeightChunk;
      private float branchModifierWhole;
      private float branchModifierChunk;
      private Long parentTokenCount;
      private float qppMaxIdf;
      private float qppAvgIctf;
      private float qppQueryScope;
      private int rankPosition;
      private long timestampMs;

      private Builder() {}

      public Builder sparse(float value) {
        this.sparse = value;
        return this;
      }

      public Builder vector(float value) {
        this.vector = value;
        return this;
      }

      public Builder wholeSparse(float value) {
        this.wholeSparse = value;
        return this;
      }

      public Builder wholeVector(float value) {
        this.wholeVector = value;
        return this;
      }

      public Builder wholeSplade(float value) {
        this.wholeSplade = value;
        return this;
      }

      public Builder wholeCc(float value) {
        this.wholeCc = value;
        return this;
      }

      public Builder chunkSparse(float value) {
        this.chunkSparse = value;
        return this;
      }

      public Builder chunkVector(float value) {
        this.chunkVector = value;
        return this;
      }

      public Builder chunkSplade(float value) {
        this.chunkSplade = value;
        return this;
      }

      public Builder chunkCc(float value) {
        this.chunkCc = value;
        return this;
      }

      public Builder branchWhole(float value) {
        this.branchWhole = value;
        return this;
      }

      public Builder branchChunk(float value) {
        this.branchChunk = value;
        return this;
      }

      public Builder branchCc(float value) {
        this.branchCc = value;
        return this;
      }

      public Builder branchPresentWhole(boolean value) {
        this.branchPresentWhole = value;
        return this;
      }

      public Builder branchPresentChunk(boolean value) {
        this.branchPresentChunk = value;
        return this;
      }

      public Builder branchWeightWhole(float value) {
        this.branchWeightWhole = value;
        return this;
      }

      public Builder branchWeightChunk(float value) {
        this.branchWeightChunk = value;
        return this;
      }

      public Builder branchEffectiveWeightWhole(float value) {
        this.branchEffectiveWeightWhole = value;
        return this;
      }

      public Builder branchEffectiveWeightChunk(float value) {
        this.branchEffectiveWeightChunk = value;
        return this;
      }

      public Builder branchModifierWhole(float value) {
        this.branchModifierWhole = value;
        return this;
      }

      public Builder branchModifierChunk(float value) {
        this.branchModifierChunk = value;
        return this;
      }

      public Builder parentTokenCount(Long value) {
        this.parentTokenCount = value;
        return this;
      }

      public Builder qppMaxIdf(float value) {
        this.qppMaxIdf = value;
        return this;
      }

      public Builder qppAvgIctf(float value) {
        this.qppAvgIctf = value;
        return this;
      }

      public Builder qppQueryScope(float value) {
        this.qppQueryScope = value;
        return this;
      }

      public Builder rankPosition(int value) {
        this.rankPosition = value;
        return this;
      }

      public Builder timestampMs(long value) {
        this.timestampMs = value;
        return this;
      }

      public FeaturePayload build() {
        return new FeaturePayload(
            sparse,
            vector,
            wholeSparse,
            wholeVector,
            wholeSplade,
            wholeCc,
            chunkSparse,
            chunkVector,
            chunkSplade,
            chunkCc,
            branchWhole,
            branchChunk,
            branchCc,
            branchPresentWhole,
            branchPresentChunk,
            branchWeightWhole,
            branchWeightChunk,
            branchEffectiveWeightWhole,
            branchEffectiveWeightChunk,
            branchModifierWhole,
            branchModifierChunk,
            parentTokenCount,
            qppMaxIdf,
            qppAvgIctf,
            qppQueryScope,
            rankPosition,
            timestampMs);
      }
    }
  }

  private final Path storeFile;

  /**
   * In-memory triple count — initialized once from the existing file at construction time, then
   * incremented on each successful {@link #append}. Avoids an O(n) file scan on every
   * {@link #count} call.
   */
  private volatile long tripleCount;

  /**
   * Creates a triple store backed by {@code gpl-training-triples.ndjson} in the given data
   * directory.
   *
   * @param dataDir the resolved data directory (from {@code PlatformPaths.resolveDataDir()})
   */
  public GplTrainingTripleStore(Path dataDir) {
    this(dataDir, "gpl-training-triples.ndjson");
  }

  /**
   * Tempdoc 580 §17 P5 — a triple store over a custom NDJSON file under {@code dataDir} (e.g. the
   * disposition-derived <em>real-feedback</em> labels, kept distinct from the GPL synthetic store so
   * the synthetic set can be demoted to a cold-start prior). {@code fileName} may contain a subdir.
   */
  public GplTrainingTripleStore(Path dataDir, String fileName) {
    this.storeFile = dataDir.resolve(fileName);
    try {
      Files.createDirectories(this.storeFile.getParent());
    } catch (IOException e) {
      log.warn("Failed to create data directory {}: {}", this.storeFile.getParent(), e.getMessage());
      log.debug("Failed to create data directory (stack trace)", e);
    }
    long existing = 0L;
    if (Files.exists(this.storeFile)) {
      try (var lines = Files.lines(this.storeFile, StandardCharsets.UTF_8)) {
        existing = lines.filter(l -> !l.isBlank()).count();
      } catch (IOException e) {
        log.warn("Failed to count existing GPL triples at startup; count will start from 0", e);
      }
    }
    this.tripleCount = existing;
  }

  /**
   * Appends a training triple to the store.
   *
   * @param docId the source document ID
   * @param syntheticQuery the LLM-generated query for this document
   * @param score the cross-encoder relevance score (0.0–1.0)
   * @throws IOException if writing to the store file fails
   */
  public synchronized void append(String docId, String syntheticQuery, float score)
      throws IOException {
    long timestampMs = Instant.now().toEpochMilli();
    String line = buildJson(docId, syntheticQuery, score, timestampMs) + "\n";
    Files.writeString(
        storeFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    tripleCount++;
    log.debug("GPL triple appended: doc={} score={}", docId, score);
  }

  /**
   * Appends a training triple with LambdaMART feature columns to the store.
   *
   * <p>The extended format includes BM25/dense scores and QPP values captured from the live index
   * during the negative-sampling re-query pass. The {@code queryId} links positive and negative
   * triples for the same synthetic query so the training runner can form pairwise comparisons.
   *
   * @param queryId stable identifier for the query (e.g. {@code "doc-abc#0"})
   * @param docId the document being labelled (source doc for positives, retrieved doc for negatives)
   * @param syntheticQuery the LLM-generated query text
   * @param score cross-encoder relevance score (0.0–1.0)
   * @param isNegative true if this triple is a non-relevant (negative) example
   * @param payload named Stage 3A feature payload; additive so LambdaMART V1 still consumes the
   *     legacy {@code sparse}/{@code vector} aliases
   * @throws IOException if writing to the store file fails
   */
  public synchronized void appendWithFeatures(
      String queryId,
      String docId,
      String syntheticQuery,
      float score,
      boolean isNegative,
      FeaturePayload payload)
      throws IOException {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("query_id", queryId);
    node.put("doc_id", docId);
    node.put("synthetic_query", syntheticQuery);
    node.put("score", score);
    node.put("is_negative", isNegative);
    node.put("sparse", payload.sparse());
    node.put("vector", payload.vector());
    node.put("whole_sparse", payload.wholeSparse());
    node.put("whole_vector", payload.wholeVector());
    node.put("whole_splade", payload.wholeSplade());
    node.put("whole_cc", payload.wholeCc());
    node.put("chunk_sparse", payload.chunkSparse());
    node.put("chunk_vector", payload.chunkVector());
    node.put("chunk_splade", payload.chunkSplade());
    node.put("chunk_cc", payload.chunkCc());
    node.put("branch_whole", payload.branchWhole());
    node.put("branch_chunk", payload.branchChunk());
    node.put("branch_cc", payload.branchCc());
    node.put("branch_present_whole", payload.branchPresentWhole());
    node.put("branch_present_chunk", payload.branchPresentChunk());
    node.put("branch_weight_whole", payload.branchWeightWhole());
    node.put("branch_weight_chunk", payload.branchWeightChunk());
    node.put("branch_effective_weight_whole", payload.branchEffectiveWeightWhole());
    node.put("branch_effective_weight_chunk", payload.branchEffectiveWeightChunk());
    node.put("branch_modifier_whole", payload.branchModifierWhole());
    node.put("branch_modifier_chunk", payload.branchModifierChunk());
    if (payload.parentTokenCount() != null) {
      node.put("parent_token_count", payload.parentTokenCount());
    }
    node.put("qpp_max_idf", payload.qppMaxIdf());
    node.put("qpp_avg_ictf", payload.qppAvgIctf());
    node.put("qpp_query_scope", payload.qppQueryScope());
    node.put("rank_position", payload.rankPosition());
    node.put("timestamp_ms", payload.timestampMs());
    String line;
    try {
      line = MAPPER.writeValueAsString(node) + "\n";
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize GPL triple to JSON", e);
    }
    Files.writeString(
        storeFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    tripleCount++;
    log.debug("GPL triple appended: queryId={} doc={} isNegative={} score={}", queryId, docId, isNegative, score);
  }

  /**
   * Returns the number of triples currently in the store.
   *
   * <p>O(1) — returns the in-memory counter maintained by {@link #append} and {@link
   * #appendWithFeatures}. The counter is initialised from the file at construction time and
   * incremented on each successful write.
   *
   * @return triple count, or 0 if no triples have been appended
   */
  public long count() {
    return tripleCount;
  }

  /**
   * Removes all triples. Called at the start of each GPL job to avoid duplicates on retry (E2E-6).
   */
  public synchronized void clear() throws IOException {
    Files.deleteIfExists(storeFile);
    tripleCount = 0;
  }

  /** Returns true if the store file exists on disk. */
  public boolean exists() {
    return Files.exists(storeFile);
  }

  /** Returns the path to the backing NDJSON file. */
  public Path storeFile() {
    return storeFile;
  }

  private static String buildJson(String docId, String syntheticQuery, float score, long ts) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("doc_id", docId);
    node.put("synthetic_query", syntheticQuery);
    node.put("score", score);
    node.put("timestamp_ms", ts);
    try {
      return MAPPER.writeValueAsString(node);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize GPL triple to JSON", e);
    }
  }
}

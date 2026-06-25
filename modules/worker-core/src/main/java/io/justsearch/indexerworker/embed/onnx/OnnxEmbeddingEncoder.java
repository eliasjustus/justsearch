/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.indexerworker.metrics.EncoderOrtRunSpans;
import io.justsearch.ort.OrtCudaStatus;
import io.justsearch.ort.SessionHandle;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ONNX Runtime-based embedding encoder for nomic-embed-text-v1.5.
 *
 * <p>Loads an ONNX model and produces L2-normalized dense embeddings. Supports GPU acceleration
 * with lazy initialization and CPU fallback (same pattern as {@code SpladeEncoder} and {@code
 * CrossEncoderReranker}).
 *
 * <p>The ONNX model outputs {@code last_hidden_state} (per-token embeddings). This encoder applies
 * pooling (mean or CLS) and L2 normalization to produce a single unit-length vector per input text.
 * Long texts exceeding {@code maxSeqLen} are chunked with a sliding window.
 *
 * <p>Pooling strategy is auto-detected from a {@code pooling_config.json} file in the model
 * directory (key: {@code "pooling_mode"}). Supported values: {@code "mean"} (default, used by
 * nomic-embed), {@code "cls"} (used by gte-modernbert). If no config file is found, defaults to
 * mean pooling for backward compatibility.
 */
public final class OnnxEmbeddingEncoder implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingEncoder.class);

  /** Pooling strategy for extracting a single vector from per-token hidden states. */
  public enum PoolingStrategy {
    /** Attention-mask-weighted mean of all token embeddings (nomic-embed, E5, BGE). */
    MEAN_POOL,
    /** First token (CLS) embedding (gte-modernbert, DPR). */
    CLS
  }

  // --- Session management (delegated to SessionHandle — tempdoc 397 §7.4 / §14.5 W1) ---
  private final SessionHandle sessions;

  // --- Tokenizer ---
  private final HuggingFaceTokenizer tokenizer;
  private final int maxSeqLen;
  private final boolean needsTokenTypeIds;

  // --- Chunking ---
  private final int chunkSize;
  private final int chunkOverlap;

  // --- Pooling strategy (auto-detected from model config) ---
  private final PoolingStrategy poolingStrategy;

  // --- Embedding dimension (detected from model output) ---
  private volatile int embeddingDimension;

  // --- Per-call profiling (356->357: shared accumulator, pull model) ---
  private final io.justsearch.indexerworker.metrics.EncoderProfileAccumulator profiler =
      new io.justsearch.indexerworker.metrics.EncoderProfileAccumulator(
          "tokenize", "tensor", "ort", "extract");
  private static final int PROFILE_LOG_INTERVAL = 50;

  // --- Per-ORT-call span tracer (tempdoc 400 LR2-a) ---
  private static final Tracer ORT_TRACER = EncoderOrtRunSpans.encoderTracer("embed");

  /** Result of embedding a single text (may contain chunk vectors if text was long). */
  public record EmbedResult(float[] vector, List<float[]> chunkVectors, int chunkCount) {}

  /**
   * Tempdoc 397 §14.24 FD-Embedding primary constructor. All construction inputs are pre-built
   * by the composition root (or by {@link #buildAssembly} for dev-mode fallback paths). Encoder
   * performs zero filesystem I/O.
   *
   * @param sessions pre-configured session handle
   * @param shape model-intrinsic facts (max sequence length + pooling + token_type_ids)
   * @param tokenizer pre-loaded DJL HuggingFace tokenizer
   */
  public OnnxEmbeddingEncoder(
      SessionHandle sessions, EmbeddingShape shape, HuggingFaceTokenizer tokenizer) {
    this.sessions = sessions;
    this.maxSeqLen = shape.maxSequenceLength();
    this.chunkSize = Math.min(512, maxSeqLen);
    this.chunkOverlap = 128;
    this.needsTokenTypeIds = shape.needsTokenTypeIds();
    this.tokenizer = tokenizer;
    this.poolingStrategy = shape.poolingStrategy();

    log.info(
        "OnnxEmbeddingEncoder initialized: maxSeqLen={}, tokenTypeIds={}, poolingStrategy={}",
        maxSeqLen,
        needsTokenTypeIds,
        poolingStrategy);
    io.justsearch.indexerworker.metrics.OperationalMetrics.getInstance()
        .registerEncoder("embed", profiler);
  }

  /**
   * Builds a complete {@link EmbeddingAssembly} from a session handle + model directory + max
   * sequence length. Tempdoc 397 §14.24 FD-Embedding. Shared helper called by
   * {@code InferenceCompositionRoot.composeEmbedAssembly} (variant-driven path) and by test
   * harnesses + the worker-embed service lazy-init path.
   *
   * @throws OrtException if session input-name probe fails
   * @throws UncheckedIOException if tokenizer load fails
   */
  public static EmbeddingAssembly buildAssembly(
      SessionHandle sessions, Path modelDir, int maxSeqLen) throws OrtException {
    // Tempdoc 397 §14.24 FD-ProbeDeletion: probe input names via the assembler helper.
    // Tempdoc 374 sandbox round 4 issue H: previously hardcoded model.onnx, which
    // broke when Install AI only downloaded model_fp16.onnx on a CUDA-functional
    // host. resolveExistingModelFile picks whichever declared variant is on disk.
    Path probeModel =
        io.justsearch.ort.ModelManifest.loadOrDefault(modelDir).resolveExistingModelFile(modelDir);
    io.justsearch.ort.OrtSessionAssembler.ProbedNames probed =
        io.justsearch.ort.OrtSessionAssembler.probeModelNames(
            sessions.environment(), probeModel);
    boolean needsTokenTypeIds = probed.inputs().contains("token_type_ids");
    Path tokenizerPath = modelDir.resolve("tokenizer.json");
    HuggingFaceTokenizer tokenizer;
    try {
      tokenizer =
          HuggingFaceTokenizer.newInstance(
              tokenizerPath, Map.of("truncation", "false", "padding", "false"));
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to load embedding tokenizer from " + tokenizerPath, e);
    }
    PoolingStrategy poolingStrategy = detectPoolingStrategy(modelDir, "pooling_config.json");
    return new EmbeddingAssembly(
        sessions, new EmbeddingShape(maxSeqLen, needsTokenTypeIds, poolingStrategy), tokenizer);
  }

  /**
   * Embeds a text, chunking if it exceeds the model's context window.
   *
   * @param text input text to embed
   * @return embedding result with primary vector and optional chunk vectors
   * @throws OrtException if ONNX inference fails
   */
  public EmbedResult embed(String text) throws OrtException {
    Encoding encoding = tokenizer.encode(text);
    long[] ids = encoding.getIds();
    long[] mask = encoding.getAttentionMask();
    long[] typeIds = encoding.getTypeIds();

    if (ids.length <= maxSeqLen) {
      // Short text: single embedding
      float[] vector = embedSingle(ids, mask, typeIds);
      return new EmbedResult(vector, List.of(), 1);
    }

    // Long text: chunk and mean-pool
    List<long[][]> chunks = createChunks(ids, mask, typeIds);
    List<float[]> chunkVectors = new ArrayList<>(chunks.size());

    for (long[][] chunk : chunks) {
      chunkVectors.add(embedSingle(chunk[0], chunk[1], chunk[2]));
    }

    float[] pooled = meanPoolChunks(chunkVectors);
    return new EmbedResult(pooled, chunkVectors, chunks.size());
  }

  /**
   * Embeds a batch of pre-chunked text strings in a single ORT inference call.
   *
   * <p>Each text is tokenized, truncated to maxSeqLen, and padded to the batch's max length. The
   * batch is run through ORT as a single [batchSize, maxLen] tensor. Each result is mean-pooled and
   * L2-normalized independently.
   *
   * @param texts list of text strings to embed (already chunked by caller)
   * @return list of embedding vectors, one per input text
   * @throws OrtException if ONNX inference fails
   */
  /**
   * Maximum ORT inference batch size. batch=16 is the saturation point for
   * per-doc throughput (Batch E probe, tempdoc 390/394), but at the default
   * 3072 MB embed arena with shrinkage-on, batch=16 triggers BFCArena
   * fragmentation OOMs on MatMul / BiasSoftmax activations (51 OOMs observed
   * over 5184-doc scifact run, 2026-04-20). Each OOM triggers per-doc fallback
   * in {@code EmbeddingBackfillOps} which negates the batching gain.
   *
   * <p>Kept at 8 until one of the following lands:
   * <ul>
   *   <li>Item 4 (E6' shrinkage-off config) — validated with 6144 MB arena,
   *       zero OOMs at batch=16;</li>
   *   <li>arena_extend_strategy = kNextPowerOfTwo (reduces fragmentation,
   *       same root cause as SPLADE PRESPARSE OOMs at batch=16);</li>
   *   <li>Adaptive sub-batching with OOM retry — capacity-aware fallback in
   *       the encoder itself.</li>
   * </ul>
   *
   * <p>Historical constraint (tempdoc 334 Phase 8): batch=16 also failed at
   * 2048 MB arena and at 4096 MB × 3 concurrent sessions (5× regression from
   * VRAM fragmentation). That scenario is superseded by the shrinkage-on
   * default (4.8 GB observed peak) but the per-encoder fragmentation issue
   * remains.
   */
  private static final int MAX_ORT_BATCH_SIZE = 8;

  public List<float[]> embedBatch(List<String> texts) throws OrtException {
    if (texts.isEmpty()) {
      return List.of();
    }
    if (texts.size() == 1) {
      // Fast path: avoid batch overhead for single text
      float[] vec = embed(texts.get(0)).vector();
      return List.of(vec);
    }

    // Sub-batch if input exceeds optimal ORT batch size
    if (texts.size() > MAX_ORT_BATCH_SIZE) {
      List<float[]> allResults = new ArrayList<>(texts.size());
      for (int start = 0; start < texts.size(); start += MAX_ORT_BATCH_SIZE) {
        int end = Math.min(start + MAX_ORT_BATCH_SIZE, texts.size());
        allResults.addAll(embedBatchInternal(texts.subList(start, end)));
      }
      return allResults;
    }

    return embedBatchInternal(texts);
  }

  private List<float[]> embedBatchInternal(List<String> texts) throws OrtException {
    int batchSize = texts.size();
    long tTok = System.nanoTime();
    List<long[][]> tokenized = new ArrayList<>(batchSize);
    for (int i = 0; i < batchSize; i++) {
      Encoding enc = tokenizer.encode(texts.get(i));
      int seqLen = Math.min(enc.getIds().length, maxSeqLen);
      tokenized.add(
          new long[][] {
            truncate(enc.getIds(), seqLen),
            truncate(enc.getAttentionMask(), seqLen),
            truncate(enc.getTypeIds(), seqLen)
          });
    }
    profiler.addPhaseNs("tokenize", System.nanoTime() - tTok);
    return embedPreTokenizedBatch(tokenized);
  }

  /**
   * Embeds a batch of pre-tokenized chunks in a single ORT inference call.
   *
   * <p>Each element is {@code {ids, mask, typeIds}}. All chunks are padded to the batch's max
   * length. Sub-batches at {@link #MAX_ORT_BATCH_SIZE} to avoid memory pressure.
   *
   * @param tokenizedChunks list of token arrays, each {@code long[3][seqLen]}
   * @return one L2-normalized mean-pooled vector per input chunk
   */
  private List<float[]> embedPreTokenizedBatch(List<long[][]> tokenizedChunks) throws OrtException {
    if (tokenizedChunks.isEmpty()) {
      return List.of();
    }
    if (tokenizedChunks.size() > MAX_ORT_BATCH_SIZE) {
      List<float[]> allResults = new ArrayList<>(tokenizedChunks.size());
      for (int start = 0; start < tokenizedChunks.size(); start += MAX_ORT_BATCH_SIZE) {
        int end = Math.min(start + MAX_ORT_BATCH_SIZE, tokenizedChunks.size());
        allResults.addAll(embedPreTokenizedBatch(tokenizedChunks.subList(start, end)));
      }
      return allResults;
    }

    int batchSize = tokenizedChunks.size();
    long[][] allIds = new long[batchSize][];
    long[][] allMask = new long[batchSize][];
    long[][] allTypeIds = new long[batchSize][];
    int maxLen = 0;

    for (int i = 0; i < batchSize; i++) {
      long[][] chunk = tokenizedChunks.get(i);
      allIds[i] = chunk[0];
      allMask[i] = chunk[1];
      allTypeIds[i] = chunk[2];
      maxLen = Math.max(maxLen, chunk[0].length);
    }

    // Pad all to uniform length
    for (int i = 0; i < batchSize; i++) {
      allIds[i] = padRight(allIds[i], maxLen);
      allMask[i] = padRight(allMask[i], maxLen);
      allTypeIds[i] = padRight(allTypeIds[i], maxLen);
    }

    long[] shape = {batchSize, maxLen};

    long tTensor = System.nanoTime();
    OrtEnvironment env = sessions.environment();
    try (OnnxTensor inputIdsTensor =
            OnnxTensor.createTensor(env, flatten(allIds, batchSize, maxLen), shape);
        OnnxTensor attentionMaskTensor =
            OnnxTensor.createTensor(env, flatten(allMask, batchSize, maxLen), shape);
        OnnxTensor tokenTypeIdsTensor =
            needsTokenTypeIds
                ? OnnxTensor.createTensor(env, flatten(allTypeIds, batchSize, maxLen), shape)
                : null) {

      Map<String, OnnxTensor> inputs = new HashMap<>();
      inputs.put("input_ids", inputIdsTensor);
      inputs.put("attention_mask", attentionMaskTensor);
      if (tokenTypeIdsTensor != null) {
        inputs.put("token_type_ids", tokenTypeIdsTensor);
      }

      long tOrt = System.nanoTime();
      profiler.addPhaseNs("tensor", tOrt - tTensor);

      // Tempdoc 400 LR2-a/LR2-b: encoder.ort_run starts before sessions.acquire()
      // so the lease.acquire child span emitted inside NativeSessionHandle parents
      // under it naturally. encoder.gpu is set post-acquire when lease.isCpu()
      // becomes knowable.
      Span ortSpan = EncoderOrtRunSpans.maybeOrtRun(ORT_TRACER, "embed", batchSize, maxLen);
      try (Scope _ = ortSpan.makeCurrent()) {
        try (var lease = sessions.acquire()) {
          ortSpan.setAttribute("encoder.gpu", !lease.isCpu());
          OrtSession session = lease.session();
          try (OrtSession.Result result = session.run(inputs, lease.runOptions())) {
            long tExtract = System.nanoTime();
            long ortElapsed = tExtract - tOrt;
            profiler.recordOrtCall(ortElapsed);

            // last_hidden_state: [batchSize, maxLen, dim]
            float[][][] hidden = (float[][][]) result.get(0).getValue();
            int dim = hidden[0][0].length;

            if (embeddingDimension == 0) {
              embeddingDimension = dim;
            }

            List<float[]> vectors = new ArrayList<>(batchSize);
            for (int b = 0; b < batchSize; b++) {
              vectors.add(l2Normalize(pool(hidden[b], allMask[b], dim)));
            }
            profiler.addPhaseNs("extract", System.nanoTime() - tExtract);
            // callCount() is approximate — concurrent threads may skip or double-fire
            // at interval boundaries. Acceptable for periodic diagnostic logging.
            long calls = profiler.callCount();
            if (calls % PROFILE_LOG_INTERVAL == 0) {
              var snap = profiler.snapshot();
              if (snap != null) {
                log.info(
                    "Embed per-call profile ({}calls): {}, ort=[{}], batch={}, seqLen={}",
                    calls, snap.formatAvgPhases(calls), snap.formatOrtDist(), batchSize, maxLen);
              }
            }
            return vectors;
          }
        }
      } finally {
        ortSpan.end();
      }
    }
  }

  /**
   * Batch-embeds texts with chunking support for long documents.
   *
   * <p>Short texts (≤ {@code maxSeqLen} tokens) are embedded directly. Long texts are split into
   * overlapping chunks (same windowing as {@link #embed}), and chunk vectors are mean-pooled per
   * document. All chunks across all texts are flattened into a single batch for efficient ORT
   * inference, then reassembled per original text.
   *
   * @param texts list of text strings to embed
   * @return one {@link EmbedResult} per input text, with chunk vectors for long texts
   * @throws OrtException if ONNX inference fails
   */
  public List<EmbedResult> embedBatchWithChunking(List<String> texts) throws OrtException {
    if (texts.isEmpty()) {
      return List.of();
    }
    if (texts.size() == 1) {
      return List.of(embed(texts.get(0)));
    }

    // Phase 1: Tokenize all texts, chunk long ones, track doc→chunk mapping
    long tTok = System.nanoTime();
    List<long[][]> flatChunks = new ArrayList<>();
    // chunkMapping[i] = {startIndexInFlatChunks, chunkCount} for text i
    int[][] chunkMapping = new int[texts.size()][2];

    int chunkedCount = 0;
    for (int i = 0; i < texts.size(); i++) {
      Encoding enc = tokenizer.encode(texts.get(i));
      long[] ids = enc.getIds();
      long[] mask = enc.getAttentionMask();
      long[] typeIds = enc.getTypeIds();

      if (ids.length <= maxSeqLen) {
        // Short text: single chunk (no truncation needed)
        chunkMapping[i] = new int[] {flatChunks.size(), 1};
        flatChunks.add(new long[][] {ids, mask, typeIds});
      } else {
        // Long text: create overlapping chunks
        List<long[][]> chunks = createChunks(ids, mask, typeIds);
        chunkMapping[i] = new int[] {flatChunks.size(), chunks.size()};
        flatChunks.addAll(chunks);
        chunkedCount++;
      }
    }
    profiler.addPhaseNs("tokenize", System.nanoTime() - tTok);

    log.debug(
        "embedBatchWithChunking: texts={}, chunkedTexts={}, totalFlatChunks={}",
        texts.size(),
        chunkedCount,
        flatChunks.size());

    // Phase 2: Batch-embed all chunks (sub-batched at MAX_ORT_BATCH_SIZE internally)
    List<float[]> allChunkVectors = embedPreTokenizedBatch(flatChunks);

    // Phase 3: Reassemble per-text results
    List<EmbedResult> results = new ArrayList<>(texts.size());
    for (int i = 0; i < texts.size(); i++) {
      int startIdx = chunkMapping[i][0];
      int count = chunkMapping[i][1];

      if (count == 1) {
        results.add(new EmbedResult(allChunkVectors.get(startIdx), List.of(), 1));
      } else {
        List<float[]> chunkVectors = new ArrayList<>(count);
        for (int c = 0; c < count; c++) {
          chunkVectors.add(allChunkVectors.get(startIdx + c));
        }
        float[] pooled = meanPoolChunks(chunkVectors);
        results.add(new EmbedResult(pooled, chunkVectors, count));
      }
    }

    return results;
  }

  /** Returns the embedding dimension (detected from first inference, or 0 if not yet known). */
  public int embeddingDimension() {
    return embeddingDimension;
  }

  // ---------------------------------------------------------------------------
  // Single-chunk embedding
  // ---------------------------------------------------------------------------

  /**
   * Embeds a single chunk of tokens and returns the L2-normalized mean-pooled vector.
   *
   * <p>The ONNX model outputs {@code last_hidden_state} with shape {@code [1, seqLen, dim]}. We
   * apply attention-mask-aware mean pooling and L2 normalization.
   */
  private float[] embedSingle(long[] ids, long[] mask, long[] typeIds) throws OrtException {
    int seqLen = Math.min(ids.length, maxSeqLen);

    // Truncate if needed
    long[] truncIds = truncate(ids, seqLen);
    long[] truncMask = truncate(mask, seqLen);
    long[] truncTypeIds = truncate(typeIds, seqLen);

    long[] shape = {1, seqLen};

    OrtEnvironment env = sessions.environment();
    try (OnnxTensor inputIdsTensor =
            OnnxTensor.createTensor(env, LongBuffer.wrap(truncIds), shape);
        OnnxTensor attentionMaskTensor =
            OnnxTensor.createTensor(env, LongBuffer.wrap(truncMask), shape);
        OnnxTensor tokenTypeIdsTensor =
            needsTokenTypeIds
                ? OnnxTensor.createTensor(env, LongBuffer.wrap(truncTypeIds), shape)
                : null) {

      Map<String, OnnxTensor> inputs = new HashMap<>();
      inputs.put("input_ids", inputIdsTensor);
      inputs.put("attention_mask", attentionMaskTensor);
      if (tokenTypeIdsTensor != null) {
        inputs.put("token_type_ids", tokenTypeIdsTensor);
      }

      // Tempdoc 400 LR2-a/LR2-b: span starts before acquire; see batched path above.
      Span ortSpan = EncoderOrtRunSpans.maybeOrtRun(ORT_TRACER, "embed", 1, truncMask.length);
      try (Scope _ = ortSpan.makeCurrent()) {
        try (var lease = sessions.acquire()) {
          ortSpan.setAttribute("encoder.gpu", !lease.isCpu());
          OrtSession session = lease.session();
          try (OrtSession.Result result = session.run(inputs, lease.runOptions())) {
            // last_hidden_state: [1, seqLen, dim]
            float[][][] hidden = (float[][][]) result.get(0).getValue();
            int dim = hidden[0][0].length;

            if (embeddingDimension == 0) {
              embeddingDimension = dim;
            }

            return l2Normalize(pool(hidden[0], truncMask, dim));
          }
        }
      } finally {
        ortSpan.end();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Chunking (replicates EmbeddingActor.createChunks)
  // ---------------------------------------------------------------------------

  /**
   * Creates overlapping chunks from token arrays using a sliding window.
   *
   * @return list of chunks, each containing [ids, mask, typeIds] arrays
   */
  private List<long[][]> createChunks(long[] ids, long[] mask, long[] typeIds) {
    List<long[][]> chunks = new ArrayList<>();
    int stride = Math.max(1, chunkSize - chunkOverlap);

    int start = 0;
    while (start < ids.length) {
      int end = Math.min(start + chunkSize, ids.length);
      int len = end - start;

      long[] chunkIds = new long[len];
      long[] chunkMask = new long[len];
      long[] chunkTypeIds = new long[len];
      System.arraycopy(ids, start, chunkIds, 0, len);
      System.arraycopy(mask, start, chunkMask, 0, len);
      System.arraycopy(typeIds, start, chunkTypeIds, 0, len);
      chunks.add(new long[][] {chunkIds, chunkMask, chunkTypeIds});

      start += stride;

      // If remaining tokens are very small, merge with last chunk
      if (start < ids.length && ids.length - start < chunkSize / 4) {
        int lastStart = start - stride;
        int lastEnd = Math.min(ids.length, lastStart + maxSeqLen);
        int extLen = lastEnd - lastStart;
        long[] extIds = new long[extLen];
        long[] extMask = new long[extLen];
        long[] extTypeIds = new long[extLen];
        System.arraycopy(ids, lastStart, extIds, 0, extLen);
        System.arraycopy(mask, lastStart, extMask, 0, extLen);
        System.arraycopy(typeIds, lastStart, extTypeIds, 0, extLen);
        chunks.set(chunks.size() - 1, new long[][] {extIds, extMask, extTypeIds});
        break;
      }
    }

    return chunks;
  }

  // ---------------------------------------------------------------------------
  // Mean pooling across chunks (replicates EmbeddingActor.meanPool)
  // ---------------------------------------------------------------------------

  private float[] meanPoolChunks(List<float[]> vectors) {
    if (vectors.isEmpty()) {
      return new float[embeddingDimension > 0 ? embeddingDimension : 768];
    }
    if (vectors.size() == 1) {
      return vectors.get(0);
    }

    int dim = vectors.get(0).length;
    double[] sum = new double[dim];
    for (float[] vec : vectors) {
      for (int i = 0; i < Math.min(vec.length, dim); i++) {
        sum[i] += vec[i];
      }
    }

    int count = vectors.size();
    float[] result = new float[dim];
    for (int i = 0; i < dim; i++) {
      result[i] = (float) (sum[i] / count);
    }

    return l2Normalize(result);
  }

  // ---------------------------------------------------------------------------
  // GPU session lifecycle (delegated to SessionHandle)
  // ---------------------------------------------------------------------------

  /** Releases the GPU session to free VRAM (called when Main claims GPU). */
  public void releaseGpuSession() {
    sessions.releaseGpu();
  }

  /** Returns true if GPU is currently available for inference. */
  public boolean isGpuAvailable() {
    return sessions.isGpuAvailable();
  }

  /** Returns the ORT CUDA status for observability. */
  public OrtCudaStatus getOrtCudaStatus() {
    return sessions.status();
  }


  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Detects pooling strategy from a pooling config file in the model directory. Expected format:
   * {@code {"pooling_mode": "cls"}} or {@code {"pooling_mode": "mean"}}. Defaults to {@link
   * PoolingStrategy#MEAN_POOL} if no config file exists or the value is unrecognized.
   *
   * @param modelDir directory containing model files
   * @param poolingConfigFile name of the pooling config file (from manifest)
   */
  private static PoolingStrategy detectPoolingStrategy(Path modelDir, String poolingConfigFile) {
    Path configFile = modelDir.resolve(poolingConfigFile);
    if (Files.exists(configFile)) {
      try {
        String content = Files.readString(configFile);
        // Simple JSON parsing — look for "pooling_mode" value
        if (content.contains("\"cls\"")) {
          log.info("Embedding pooling strategy: CLS (from {})", poolingConfigFile);
          return PoolingStrategy.CLS;
        } else if (content.contains("\"mean\"")) {
          log.info("Embedding pooling strategy: MEAN_POOL (from {})", poolingConfigFile);
          return PoolingStrategy.MEAN_POOL;
        }
      } catch (IOException e) {
        log.debug("Failed to read {}, using default: {}", poolingConfigFile, e.getMessage());
      }
    }
    log.debug("Embedding pooling strategy: MEAN_POOL (default)");
    return PoolingStrategy.MEAN_POOL;
  }

  /**
   * Applies the configured pooling strategy to extract a single vector from token-level hidden
   * states.
   */
  private float[] pool(float[][] tokenHiddenStates, long[] attentionMask, int dim) {
    if (poolingStrategy == PoolingStrategy.CLS) {
      // CLS pooling: take the first token's hidden state
      return tokenHiddenStates[0].clone();
    }
    // Mean pooling: attention-mask-weighted average of all token embeddings
    float[] pooled = new float[dim];
    float maskSum = 0.0f;
    for (int t = 0; t < tokenHiddenStates.length; t++) {
      if (attentionMask[t] == 1) {
        maskSum += 1.0f;
        for (int d = 0; d < dim; d++) {
          pooled[d] += tokenHiddenStates[t][d];
        }
      }
    }
    if (maskSum > 0.0f) {
      for (int d = 0; d < dim; d++) {
        pooled[d] /= maskSum;
      }
    }
    return pooled;
  }

  private static float[] l2Normalize(float[] vec) {
    double norm = 0.0;
    for (float v : vec) {
      norm += (double) v * v;
    }
    double magnitude = Math.sqrt(norm);
    if (magnitude == 0.0) {
      return vec;
    }
    float[] result = new float[vec.length];
    for (int i = 0; i < vec.length; i++) {
      result[i] = (float) (vec[i] / magnitude);
    }
    return result;
  }

  private static long[] truncate(long[] arr, int len) {
    if (arr.length == len) {
      return arr;
    }
    long[] result = new long[len];
    System.arraycopy(arr, 0, result, 0, len);
    return result;
  }

  private static long[] padRight(long[] arr, int targetLen) {
    if (arr.length == targetLen) {
      return arr;
    }
    long[] result = new long[targetLen];
    System.arraycopy(arr, 0, result, 0, arr.length);
    return result;
  }

  private static LongBuffer flatten(long[][] array, int rows, int cols) {
    long[] flat = new long[rows * cols];
    for (int i = 0; i < rows; i++) {
      System.arraycopy(array[i], 0, flat, i * cols, cols);
    }
    return LongBuffer.wrap(flat);
  }

  @Override
  public void close() {
    sessions.close();
    tokenizer.close();
    log.info("OnnxEmbeddingEncoder closed");
  }

}

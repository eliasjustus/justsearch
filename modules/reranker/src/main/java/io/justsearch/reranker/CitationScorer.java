/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.justsearch.ort.OnnxSessionCache;
import io.justsearch.ort.OrtCudaStatus;
import io.justsearch.ort.SessionHandle;
import java.io.Closeable;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CPU-based cross-encoder scorer for post-hoc citation matching.
 *
 * <p>Scores (answer_sentence, source_chunk) pairs using an ONNX cross-encoder model to determine
 * which source chunks support each answer sentence. This replaces embedding-based cosine similarity
 * for citation matching, avoiding GPU contention with the LLM.
 *
 * <p>This class is thread-safe. The ONNX session is shared across threads, while tensors are
 * created per-request. CPU-only by design — no GPU session management.
 *
 * <p>Usage (tempdoc 397 §14.24 FC / §14.28 U1): the composition root
 * {@code InferenceCompositionRoot.compose(...)} produces a {@code RerankerAssembly} (citation
 * reuses the reranker assembly shape) whose CPU-only {@link SessionHandle} is passed to this
 * constructor. Tests + benchmarks route through {@code InferenceCompositionRootTestHelper.sessionFor}
 * in ort-common testFixtures.
 */
public final class CitationScorer implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(CitationScorer.class);

  // Session management (delegated to SessionHandle — tempdoc 397 §14.5 PR 2).
  private final SessionHandle sessions;
  private final OrtEnvironment env;
  private final RerankerTokenizer tokenizer;
  private final boolean needsTokenTypeIds;
  private volatile boolean closed;

  /**
   * Tempdoc 397 §14.24 FD primary constructor. All construction inputs are pre-built by the
   * composition root (or by {@link #buildAssembly} for dev-mode fallback paths). Encoder
   * performs zero filesystem I/O.
   *
   * @param sessions pre-configured session handle (CPU-only)
   * @param shape model-intrinsic facts ({@code maxSeqLen} + {@code needsTokenTypeIds})
   * @param tokenizer pre-loaded reranker tokenizer
   */
  public CitationScorer(
      SessionHandle sessions, RerankerShape shape, RerankerTokenizer tokenizer) {
    this.sessions = sessions;
    this.env = OrtEnvironment.getEnvironment();
    this.tokenizer = tokenizer;
    this.needsTokenTypeIds = shape.needsTokenTypeIds();
    log.info(
        "CitationScorer initialized (CPU-only): maxSeqLen={}, tokenTypeIds={}",
        shape.maxSequenceLength(),
        needsTokenTypeIds);
  }

  /**
   * Builds a complete {@link RerankerAssembly} for citation from a session handle + tokenizer
   * path + max sequence length. Tempdoc 397 §14.24 FD. Shared helper called by
   * {@code InferenceCompositionRoot.composeCitationAssembly} and by test harnesses.
   *
   * @throws OrtException if session input-name probe fails
   */
  public static RerankerAssembly buildAssembly(
      SessionHandle sessions, Path tokenizerPath, int maxSequenceLength) throws OrtException {
    return CrossEncoderReranker.buildAssembly(sessions, tokenizerPath, maxSequenceLength);
  }

  /** Returns true if the scorer is ready for inference. */
  public boolean isAvailable() {
    return !closed;
  }

  /**
   * Tempdoc 422: exposes the underlying ONNX session's OrtCuda status so the {@code
   * /api/inference/encoders} explainer can report runtime accelerator state for the citation
   * scorer. Citation runs CPU-only by design, so this consistently reports the CPU/disabled
   * status carried by the session handle.
   */
  public OrtCudaStatus getOrtCudaStatus() {
    return sessions.status();
  }

  /**
   * Scores all sentence-chunk pairs and returns matches above threshold.
   *
   * <p>For each sentence, scores it against all chunks using the cross-encoder and keeps the best
   * matching chunk if its score exceeds the threshold.
   *
   * @param sentences answer sentences to match
   * @param chunkTexts source chunk texts
   * @param chunkDocIds parent document IDs for each chunk
   * @param threshold minimum score for a match (0.0–1.0, applied to sigmoid-normalized scores)
   * @param deadlineMs maximum time budget in milliseconds (0 = no limit)
   * @return scoring result with matches, counts, and timing
   */
  public ScoringResult scoreAll(
      List<String> sentences,
      List<String> chunkTexts,
      List<String> chunkDocIds,
      double threshold,
      long deadlineMs) {

    long startNanos = System.nanoTime();
    long deadlineNanos =
        deadlineMs > 0 ? startNanos + deadlineMs * 1_000_000L : Long.MAX_VALUE;

    if (sentences.isEmpty() || chunkTexts.isEmpty()) {
      return new ScoringResult(List.of(), sentences.size(), 0, 0);
    }

    List<ScoredMatch> matches = new ArrayList<>();
    int sentencesMatched = 0;
    String[] chunksArray = chunkTexts.toArray(new String[0]);

    for (int si = 0; si < sentences.size(); si++) {
      if (System.nanoTime() > deadlineNanos) {
        log.debug(
            "Citation scoring deadline exceeded after {} of {} sentences", si, sentences.size());
        break;
      }

      String sentence = sentences.get(si);
      if (sentence == null || sentence.isBlank()) {
        continue;
      }

      try {
        List<Float> scores = scoreSentenceAgainstChunks(sentence, chunksArray);

        // Find best matching chunk, skipping empty chunks
        double bestScore = 0.0;
        int bestChunkIdx = -1;
        for (int ci = 0; ci < scores.size(); ci++) {
          if (chunksArray[ci] == null || chunksArray[ci].isBlank()) {
            continue;
          }
          double score = scores.get(ci);
          if (score > bestScore) {
            bestScore = score;
            bestChunkIdx = ci;
          }
        }

        if (bestChunkIdx >= 0 && bestScore >= threshold) {
          sentencesMatched++;
          matches.add(
              new ScoredMatch(
                  si,
                  sentence,
                  bestChunkIdx,
                  bestChunkIdx < chunkDocIds.size() ? chunkDocIds.get(bestChunkIdx) : "",
                  bestScore));
        }
      } catch (OrtException e) {
        log.warn("Citation scoring failed for sentence {}", si, e);
      }
    }

    long totalMs = (System.nanoTime() - startNanos) / 1_000_000;
    log.debug(
        "Citation scoring completed: {} sentences, {} chunks, {} matches in {}ms",
        sentences.size(),
        chunkTexts.size(),
        matches.size(),
        totalMs);
    return new ScoringResult(matches, sentences.size(), sentencesMatched, totalMs);
  }

  /**
   * Scores one sentence against all chunks in a single batch.
   *
   * <p>Mirrors the reranker pattern: one query (sentence) scored against N documents (chunks).
   */
  private List<Float> scoreSentenceAgainstChunks(String sentence, String[] chunks)
      throws OrtException {

    RerankerTokenizer.EncodedBatch batch = tokenizer.encodePairs(sentence, chunks);

    int batchSize = batch.batchSize();
    int seqLength = batch.seqLength();
    long[] shape = new long[] {batchSize, seqLength};

    try (OnnxTensor inputIdsTensor =
            OnnxTensor.createTensor(env, flatten(batch.inputIds()), shape);
        OnnxTensor attentionMaskTensor =
            OnnxTensor.createTensor(env, flatten(batch.attentionMask()), shape);
        OnnxTensor tokenTypeIdsTensor =
            needsTokenTypeIds
                ? OnnxTensor.createTensor(env, flatten(batch.tokenTypeIds()), shape)
                : null) {

      Map<String, OnnxTensor> inputs = new HashMap<>();
      inputs.put("input_ids", inputIdsTensor);
      inputs.put("attention_mask", attentionMaskTensor);
      if (tokenTypeIdsTensor != null) {
        inputs.put("token_type_ids", tokenTypeIdsTensor);
      }

      try (var lease = sessions.acquire()) {
        try (OrtSession.Result result = lease.session().run(inputs, lease.runOptions())) {
          return extractScores(result);
        }
      }
    }
  }

  /**
   * Extracts scores from ONNX output tensor, normalizing raw logits to [0,1] via sigmoid.
   *
   * <p>Cross-encoder models output either:
   *
   * <ul>
   *   <li>{@code float[batch][num_labels]} — raw logits (ms-marco models), sigmoid-normalized
   *   <li>{@code float[batch]} — assumed pre-normalized direct scores
   * </ul>
   */
  private List<Float> extractScores(OrtSession.Result result) throws OrtException {
    Object output = result.get(0).getValue();

    if (output instanceof float[][] logits2d) {
      // ms-marco MiniLM outputs [batch, 1] — raw logit, apply sigmoid for [0,1] scale
      return IntStream.range(0, logits2d.length)
          .mapToObj(i -> sigmoid(logits2d[i][0]))
          .toList();
    } else if (output instanceof float[] logits1d) {
      return IntStream.range(0, logits1d.length).mapToObj(i -> logits1d[i]).toList();
    }

    throw new OrtException("Unexpected output shape: " + output.getClass());
  }

  private static float sigmoid(float x) {
    return (float) (1.0 / (1.0 + Math.exp(-x)));
  }

  private static LongBuffer flatten(long[][] array) {
    int rows = array.length;
    int cols = array[0].length;
    long[] flat = new long[rows * cols];
    for (int i = 0; i < rows; i++) {
      System.arraycopy(array[i], 0, flat, i * cols, cols);
    }
    return LongBuffer.wrap(flat);
  }

  @Override
  public void close() {
    closed = true;
    sessions.close();
    tokenizer.close();
    log.info("CitationScorer closed.");
  }

  /**
   * A matched sentence-chunk pair above the scoring threshold.
   *
   * @param sentenceIndex 0-based index of the sentence in the answer
   * @param sentenceText the sentence text
   * @param chunkIndex index into the chunks list
   * @param parentDocId parent document ID for the matched chunk
   * @param score cross-encoder relevance score
   */
  public record ScoredMatch(
      int sentenceIndex, String sentenceText, int chunkIndex, String parentDocId, double score) {}

  /**
   * Result of scoring all sentence-chunk pairs.
   *
   * @param matches list of matches above threshold
   * @param sentencesTotal total sentences in the answer
   * @param sentencesMatched number of sentences with at least one match
   * @param latencyMs total processing time in milliseconds
   */
  public record ScoringResult(
      List<ScoredMatch> matches, int sentencesTotal, int sentencesMatched, long latencyMs) {}
}

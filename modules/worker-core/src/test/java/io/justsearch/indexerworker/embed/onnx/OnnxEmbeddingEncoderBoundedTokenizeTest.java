/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder.EmbedResult;
import io.justsearch.ort.Composition;
import io.justsearch.ort.ModelArtifacts;
import io.justsearch.ort.ModelSessionPolicy;
import io.justsearch.ort.ModelSessionPolicyResolver;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.RuntimePolicy;
import io.justsearch.ort.SessionHandle;
import io.justsearch.ort.testing.InferenceCompositionRootTestHelper;
import io.justsearch.ort.testing.ModelDirTestResolver;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression for the tempdoc 710 Move 3 port of the tempdoc 686 SPLADE crash fix to the embed
 * lane: {@code embedBatchWithChunking}'s Phase 1 used to tokenize every caller text upfront with
 * truncation disabled ({@link #buildFp32CpuSession} loads the same tokenizer config as
 * production, which sets {@code "truncation": "false"}). The fix groups Phase 1 tokenization into
 * {@code TOKENIZE_GROUP_CHAR_BUDGET}-bounded {@code batchEncode} calls (mirroring {@code
 * SpladeEncoder.encodeBatchTokenBudget}'s Phase 1).
 *
 * <p>Like {@code SpladeEncoderBoundedTokenizeTest}, the memory bound itself is not assertable
 * in-JVM; this test pins the OBSERVABLE contract instead — grouping must not reorder, drop, or
 * cross-wire results relative to embedding each text alone. Unlike SPLADE (which truncates every
 * text to one {@code maxSeqLen}-bounded inference regardless of raw length, so per-text compute
 * is O(1)), embed's chunking makes per-text compute scale with raw length beyond {@code
 * maxSeqLen}. To keep this test's runtime bounded, the batch mixes many short (single-chunk,
 * cheap) filler texts — sized to comfortably exceed the 512k-char group budget in aggregate,
 * forcing multiple tokenize groups — with a few genuinely long (multi-chunk) documents placed at
 * different points in the sequence, and verifies a representative sample of positions (all long
 * docs plus a spread of filler positions) rather than every position.
 */
@DisplayName("710: OnnxEmbeddingEncoder bounded-group tokenization preserves per-text results")
final class OnnxEmbeddingEncoderBoundedTokenizeTest {

  private static final int MAX_SEQ_LEN = 512;

  private static Path modelDir;
  private static SessionHandle sharedSessions;
  private static OnnxEmbeddingEncoder encoder;
  private static HuggingFaceTokenizer tokenizer;

  @BeforeAll
  static void setUp() throws Exception {
    ModelDirTestResolver.Discovery discovery = discoverModelDir();
    assumeTrue(discovery.modelDir() != null, discovery.missDescription());
    modelDir = discovery.modelDir();

    Path modelFile = modelDir.resolve("model.onnx");

    // Deliberately loads model.onnx (FP32) directly rather than InferenceCompositionRootTestHelper
    // .cpuSessionFor: this model dir's manifest declares "cpu": "model_fp16.onnx", and FP16-on-CPU
    // is documented as catastrophic (30+ min graph optimization) — see
    // OnnxEmbeddingEncoderLongDocForensicTest for the same precaution.
    sharedSessions = buildFp32CpuSession("embed-bounded-tokenize-test", modelFile);
    EmbeddingAssembly assembly =
        OnnxEmbeddingEncoder.buildAssembly(sharedSessions, modelDir, MAX_SEQ_LEN, MAX_SEQ_LEN, false);
    encoder = new OnnxEmbeddingEncoder(assembly.sessions(), assembly.shape(), assembly.tokenizer());
    tokenizer = assembly.tokenizer();
  }

  @AfterAll
  static void tearDown() {
    if (encoder != null) {
      encoder.close();
    }
  }

  // Tempdoc 710 Move 6: shared walker (obs:spladebatchsweeptest).
  private static ModelDirTestResolver.Discovery discoverModelDir() {
    return ModelDirTestResolver.discover(
        "models/onnx/gte-multilingual-base",
        "JUSTSEARCH_EMBED_ONNX_MODEL_PATH",
        "model.onnx",
        "tokenizer.json");
  }

  private static SessionHandle buildFp32CpuSession(String consumerName, Path modelFile)
      throws OrtException {
    VariantSelection variant =
        InferenceCompositionRootTestHelper.cpuVariant(modelFile, ModelPrecision.FP32);
    OptLevel cpuOptLevel =
        ModelSessionPolicyResolver.deriveCpuOptLevel(variant.precision(), ExecutionProvider.CPU);
    ModelSessionPolicy policy =
        ModelSessionPolicy.forFallback(
            /* gpuConfig= */ null,
            cpuOptLevel,
            /* deferCpuSession= */ false,
            /* gpuRetryEnabled= */ false,
            /* gpuRetryIntervalMs= */ 60_000L);
    Composition comp =
        new Composition(
            RuntimePolicy.defaults(), policy, new ModelArtifacts(variant.modelFile(), variant.modelFile()));
    return OrtSessionAssembler.buildManager(consumerName, comp, () -> false);
  }

  private static double cosine(float[] a, float[] b) {
    assertEquals(a.length, b.length, "vector dimension mismatch");
    double dot = 0.0;
    double normA = 0.0;
    double normB = 0.0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      normA += (double) a[i] * a[i];
      normB += (double) b[i] * b[i];
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  /** Single-chunk filler text, unique per index (distinct topic rotation) — well under MAX_SEQ_LEN. */
  private static String fillerText(int index) {
    String[] topics = {
      "artificial intelligence research", "quantum computing hardware", "coastal erosion patterns",
      "distributed systems consistency", "renewable energy storage", "orchestral composition history",
      "deep sea biodiversity", "trade route archaeology", "central bank policy", "chess endgame theory"
    };
    StringBuilder sb = new StringBuilder();
    sb.append("FILLER-").append(index).append(": ");
    for (int i = 0; i < 90; i++) {
      sb.append(topics[(index + i) % topics.length]).append(" section ").append(i).append(". ");
    }
    return sb.toString();
  }

  /** Multi-chunk long document, unique per index — just over MAX_SEQ_LEN tokens. */
  private static String longDocText(int index) {
    StringBuilder sb = new StringBuilder();
    sb.append("LONGDOC-").append(index).append(": ");
    for (int i = 0; i < 260; i++) {
      sb.append("paragraph ").append(index).append(" segment ").append(i)
          .append(" discusses topic ").append((index * 7 + i) % 13).append(". ");
    }
    return sb.toString();
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.MINUTES)
  @DisplayName("multi-group batch: sampled positions == singleton embed(), order preserved")
  void groupBoundariesPreserveResults() throws Exception {
    List<String> batch = new ArrayList<>();
    // 3 long (multi-chunk) docs interleaved among ~230 short filler docs; filler chars alone
    // (~230 * ~2300 chars) comfortably exceed TOKENIZE_GROUP_CHAR_BUDGET (512_000), forcing
    // multiple tokenize groups regardless of exactly where the long docs land.
    int fillerIdx = 0;
    for (int segment = 0; segment < 3; segment++) {
      for (int i = 0; i < 76; i++) {
        batch.add(fillerText(fillerIdx++));
      }
      batch.add(longDocText(segment));
    }

    long totalChars = batch.stream().mapToLong(String::length).sum();
    assertTrue(
        totalChars > 512_000,
        "test batch must exceed TOKENIZE_GROUP_CHAR_BUDGET to force multiple groups, got "
            + totalChars);

    List<EmbedResult> batched = encoder.embedBatchWithChunking(batch);
    assertEquals(batch.size(), batched.size());

    // Sanity: the long docs actually exceed maxSeqLen (exercise the multi-chunk path).
    int longDocPos = 76; // first long doc position (after the first filler segment)
    int tokenCount = tokenizer.encode(batch.get(longDocPos)).getIds().length;
    assertTrue(tokenCount > MAX_SEQ_LEN, "long doc must exceed maxSeqLen, got " + tokenCount);
    assertTrue(batched.get(longDocPos).chunkCount() > 1, "long doc must produce multiple chunks");

    // Verify a representative sample: all 3 long docs, plus first/last fillers and a spread of
    // interior fillers (rather than all ~233 positions — see class doc for the runtime rationale).
    List<Integer> samplePositions = new ArrayList<>();
    samplePositions.add(0);
    samplePositions.add(76); // long doc 0
    samplePositions.add(153); // long doc 1
    samplePositions.add(230); // long doc 2
    samplePositions.add(batch.size() - 1);
    for (int p = 10; p < batch.size(); p += 37) {
      samplePositions.add(p);
    }

    for (int pos : samplePositions) {
      EmbedResult singleton = encoder.embed(batch.get(pos));
      double cos = cosine(batched.get(pos).vector(), singleton.vector());
      assertTrue(
          cos > 0.999,
          "position "
              + pos
              + ": batched vector diverged from singleton embed() (cos="
              + cos
              + ", text="
              + (batch.get(pos).length() > 40 ? batch.get(pos).substring(0, 40) + "..." : batch.get(pos))
              + ")");
      assertEquals(
          singleton.chunkCount(),
          batched.get(pos).chunkCount(),
          "position " + pos + ": chunk count mismatch between batched and singleton paths");
    }
  }
}

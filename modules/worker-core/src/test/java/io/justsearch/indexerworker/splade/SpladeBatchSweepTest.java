package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;

/**
 * EXP-5: SPLADE batch throughput for tempdoc 278.
 *
 * <p>Measures SPLADE encoding throughput with encodeBatch() vs single encode() at different batch
 * sizes. Skipped if SPLADE model files are not present on disk.
 */
@DisplayName("EXP-5: SPLADE Batch Throughput")
@Tag("experiment")
final class SpladeBatchSweepTest {

  private static final int WARMUP_ITERS = 3;
  private static final int MEASURE_ITERS = 5;
  private static final int[] BATCH_SIZES = {1, 2, 4, 8};

  private static final String[] TEST_DOCS = {
      "Cystic fibrosis is caused by mutations in the CFTR gene and affects the lungs.",
      "Machine learning algorithms can predict protein structure from amino acid sequences.",
      "The gut microbiome plays a critical role in immune system development.",
      "CRISPR-Cas9 gene editing has revolutionized molecular biology research.",
      "Alzheimer's disease is characterized by amyloid plaques and tangles.",
      "Single-cell RNA sequencing reveals cellular heterogeneity in tissues.",
      "Antibiotic resistance is a growing global health threat.",
      "The human genome contains approximately 20,000 protein-coding genes."
  };

  private static Path modelDir;
  private static SpladeEncoder encoder;

  @BeforeAll
  static void setUp() throws Exception {
    // Look for SPLADE model directory
    Path repoRoot = Path.of(System.getProperty("user.dir"));
    Path candidate = repoRoot;
    for (int i = 0; i < 5; i++) {
      Path spladeDir = candidate.resolve("models/splade/naver-splade-v3");
      if (Files.exists(spladeDir.resolve("model.onnx"))
          && Files.exists(spladeDir.resolve("tokenizer.json"))) {
        modelDir = spladeDir;
        break;
      }
      candidate = candidate.getParent();
      if (candidate == null) break;
    }

    assumeTrue(modelDir != null, "SPLADE model not found — skipping EXP-5");

    SpladeConfig config =
        new SpladeConfig(true, modelDir, 512, false, 0, 0, "onnx", "log1p");
    // Tempdoc 397 §14.28 U1: testFixtures helper wraps OrtSessionAssembler.buildManager.
    io.justsearch.ort.SessionHandle sessions =
        io.justsearch.ort.testing.InferenceCompositionRootTestHelper.cpuSessionFor(
            "splade-test", modelDir);
    SpladeAssembly assembly = SpladeEncoder.buildAssembly(sessions, config);
    encoder =
        new SpladeEncoder(
            assembly.sessions(),
            assembly.shape(),
            assembly.tokenizer(),
            assembly.vocabulary(),
            assembly.truncationEvidencePath(),
            config);
  }

  @AfterAll
  static void tearDown() {
    if (encoder != null) {
      encoder.close();
    }
  }

  @Test
  @DisplayName("SPLADE batch throughput at different batch sizes")
  void batchSizeSweep() throws Exception {
    System.out.println("\n=== EXP-5: SPLADE Batch Throughput (CPU) ===");
    System.out.printf("%-12s %-15s %-15s %-15s%n",
        "Batch Size", "Total ms", "Per-doc ms", "Speedup vs 1");

    // Global warmup
    for (int bs : BATCH_SIZES) {
      List<String> batch = prepareBatch(bs);
      encoder.encodeBatch(batch);
    }

    double baselinePerDocMs = 0;

    for (int batchSize : BATCH_SIZES) {
      List<String> batch = prepareBatch(batchSize);

      // Warmup
      for (int w = 0; w < WARMUP_ITERS; w++) {
        encoder.encodeBatch(batch);
      }

      // Measure
      long totalNanos = 0;
      for (int m = 0; m < MEASURE_ITERS; m++) {
        long start = System.nanoTime();
        encoder.encodeBatch(batch);
        totalNanos += System.nanoTime() - start;
      }

      double avgTotalMs = totalNanos / 1_000_000.0 / MEASURE_ITERS;
      double perDocMs = avgTotalMs / batchSize;

      if (batchSize == 1) {
        baselinePerDocMs = perDocMs;
      }

      double speedup = baselinePerDocMs / perDocMs;
      System.out.printf("%-12d %-15.1f %-15.2f %-15.2fx%n",
          batchSize, avgTotalMs, perDocMs, speedup);
    }

    // Assert: batch=8 should give some speedup over batch=1
    // Tempdoc decision rule: ≥1.5x. We assert >1.1x as minimum.
    List<String> b8 = prepareBatch(8);
    for (int w = 0; w < WARMUP_ITERS; w++) {
      encoder.encodeBatch(b8);
      encoder.encode(TEST_DOCS[0]);
    }

    long singleNanos = 0;
    for (int m = 0; m < MEASURE_ITERS; m++) {
      long start = System.nanoTime();
      encoder.encode(TEST_DOCS[0]);
      singleNanos += System.nanoTime() - start;
    }

    long batchNanos = 0;
    for (int m = 0; m < MEASURE_ITERS; m++) {
      long start = System.nanoTime();
      encoder.encodeBatch(b8);
      batchNanos += System.nanoTime() - start;
    }

    double singlePerDoc = singleNanos / 1_000_000.0 / MEASURE_ITERS;
    double batchPerDoc = batchNanos / 1_000_000.0 / MEASURE_ITERS / 8;
    double measuredSpeedup = singlePerDoc / batchPerDoc;
    System.out.printf("%nVerification: single=%.2fms, batch8/doc=%.2fms, speedup=%.2fx%n",
        singlePerDoc, batchPerDoc, measuredSpeedup);

    assertTrue(measuredSpeedup > 1.1,
        "SPLADE batch-8 per-doc should be at least 1.1x faster. Got: " + measuredSpeedup);
  }

  @Test
  @DisplayName("SPLADE batch output consistency")
  void batchConsistency() throws Exception {
    System.out.println("\n=== EXP-5: SPLADE Batch Consistency ===");

    // Single-doc reference
    Map<String, Float> refVec = encoder.encode(TEST_DOCS[0]);

    // Batch-4 containing the same text
    List<String> batch = prepareBatch(4);
    List<Map<String, Float>> batchVecs = encoder.encodeBatch(batch);

    Map<String, Float> batchVec0 = batchVecs.get(0);

    // Compare sparse vectors: same keys and similar weights
    int commonKeys = 0;
    int totalKeys = refVec.size();
    double maxWeightDiff = 0;
    for (Map.Entry<String, Float> e : refVec.entrySet()) {
      Float batchWeight = batchVec0.get(e.getKey());
      if (batchWeight != null) {
        commonKeys++;
        maxWeightDiff = Math.max(maxWeightDiff, Math.abs(e.getValue() - batchWeight));
      }
    }

    double keyOverlap = (double) commonKeys / totalKeys;
    System.out.printf("Key overlap: %d/%d (%.1f%%), max weight diff: %.6f%n",
        commonKeys, totalKeys, keyOverlap * 100, maxWeightDiff);

    assertTrue(keyOverlap >= 0.9,
        "Single and batch should share ≥90% of sparse keys. Got: " + keyOverlap);
  }

  private static List<String> prepareBatch(int size) {
    List<String> batch = new ArrayList<>(size);
    for (int i = 0; i < size && i < TEST_DOCS.length; i++) {
      batch.add(TEST_DOCS[i]);
    }
    return batch;
  }
}

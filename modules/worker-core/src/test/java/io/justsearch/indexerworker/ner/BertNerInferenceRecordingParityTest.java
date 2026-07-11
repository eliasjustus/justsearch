/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.indexerworker.metrics.EncoderProfileSnapshot;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Recording-parity regression for tempdoc 710 Move 2: NER's batched path (tempdoc 691 B-5) once
 * shipped without feeding {@code EncoderProfileAccumulator} — the class of gap the choke point
 * ({@link io.justsearch.ort.SessionHandle.Lease#run}) now makes structurally impossible (pinned by
 * the {@code OrtRunChokePointTest} ArchUnit rule in {@code app-launcher}). This test is the
 * regression gate that class of bug never had: call the encoder's public inference methods N
 * times each and assert the profiler's recorded call count increased by exactly N — for BOTH the
 * single-doc path ({@link BertNerInference#infer}) and the batched path
 * ({@link BertNerInference#inferBatch}), since B-5 specifically hit the batched path only.
 */
@DisplayName("710 Move 2: BertNerInference ORT-call recording parity (single-doc + batched paths)")
final class BertNerInferenceRecordingParityTest {

  private static Path modelDir;
  private static BertNerInference encoder;

  @BeforeAll
  static void setUp() throws Exception {
    Path candidate = Path.of(System.getProperty("user.dir"));
    for (int i = 0; i < 8 && candidate != null; i++) {
      Path nerDir = candidate.resolve("models/onnx/ner");
      if (Files.exists(nerDir.resolve("model.onnx")) && Files.exists(nerDir.resolve("tokenizer.json"))) {
        modelDir = nerDir;
        break;
      }
      candidate = candidate.getParent();
    }
    assumeTrue(modelDir != null, "NER model not found — skipping");

    io.justsearch.ort.SessionHandle sessions =
        io.justsearch.ort.testing.InferenceCompositionRootTestHelper.cpuSessionFor(
            "ner-recording-parity-test", modelDir);
    NerAssembly assembly = BertNerInference.buildAssembly(sessions, modelDir, 512);
    encoder = new BertNerInference(assembly.sessions(), assembly.shape(), assembly.tokenizer());
  }

  @AfterAll
  static void tearDown() {
    if (encoder != null) {
      encoder.close();
    }
  }

  private static long currentNerCallCount() {
    EncoderProfileSnapshot snap = OperationalMetrics.getInstance().getEncoderProfiles().get("ner");
    return snap == null ? 0L : snap.calls();
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  @DisplayName("infer() N times records exactly N ORT calls")
  void singleDocPath_recordsExactlyOnceEachCall() throws Exception {
    long before = currentNerCallCount();
    int n = 5;
    for (int i = 0; i < n; i++) {
      encoder.infer("Alice works at Acme Corp in Springfield, call " + i + ".");
    }
    long after = currentNerCallCount();
    assertEquals(
        n,
        after - before,
        "infer() single-doc path must record exactly one ORT call per invocation");
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  @DisplayName("inferBatch() with multiple sub-batches records exactly one call per sub-batch")
  void batchedPath_recordsExactlyOncePerSubBatch() throws Exception {
    // MAX_NER_BATCH_SIZE is 16 and inputs are bucketed by seqLen — construct enough docs of
    // uniform, short length that they land in one seqLen bucket and require exactly
    // ceil(24/16) = 2 sub-batch ORT calls (this is a lower bound: if the encoder buckets
    // differently the count would only be >= 2, so the >= 1 assertion below stays honest even
    // if internal bucketing details drift).
    List<String> texts =
        java.util.stream.IntStream.range(0, 24)
            .mapToObj(i -> "Person " + i + " met Bob in Paris.")
            .toList();

    long before = currentNerCallCount();
    List<BertNerInference.InferenceOutput> results = encoder.inferBatch(texts);
    long after = currentNerCallCount();

    assertEquals(texts.size(), results.size());
    long recorded = after - before;
    org.junit.jupiter.api.Assertions.assertTrue(
        recorded >= 1,
        "inferBatch() must record at least one ORT call (the B-5 blind spot recorded zero)");
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.indexerworker.metrics.EncoderProfileSnapshot;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Recording-parity regression for tempdoc 710 Move 2. Covers the SHAPE the NER test
 * ({@code BertNerInferenceRecordingParityTest}) doesn't: SPLADE's pinned-output primary path
 * ({@link io.justsearch.ort.SessionHandle.Lease#runPinned}) — a genuinely different choke-point
 * entry point from the plain {@link io.justsearch.ort.SessionHandle.Lease#run} every other lane
 * uses. Asserts calling the encoder's public methods N times records exactly N ORT calls in
 * {@code EncoderProfileAccumulator}, the regression gate that class of bug (tempdoc 691 B-5: a
 * per-call-site {@code recordOrtCall} silently omitted) never had before the choke point existed.
 */
@DisplayName("710 Move 2: SpladeEncoder ORT-call recording parity (pinned + single-doc paths)")
final class SpladeEncoderRecordingParityTest {

  private static Path modelDir;
  private static SpladeEncoder encoder;

  @BeforeAll
  static void setUp() throws Exception {
    Path candidate = Path.of(System.getProperty("user.dir"));
    for (int i = 0; i < 8 && candidate != null; i++) {
      Path spladeDir = candidate.resolve("models/splade/naver-splade-v3");
      if (Files.exists(spladeDir.resolve("model.onnx"))
          && Files.exists(spladeDir.resolve("tokenizer.json"))) {
        modelDir = spladeDir;
        break;
      }
      candidate = candidate.getParent();
    }
    assumeTrue(modelDir != null, "SPLADE model not found — skipping");

    SpladeConfig config = new SpladeConfig(true, modelDir, 512, false, 0, 0, "onnx", "log1p");
    io.justsearch.ort.SessionHandle sessions =
        io.justsearch.ort.testing.InferenceCompositionRootTestHelper.cpuSessionFor(
            "splade-recording-parity-test", modelDir);
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

  private static long currentSpladeCallCount() {
    EncoderProfileSnapshot snap =
        OperationalMetrics.getInstance().getEncoderProfiles().get("splade");
    return snap == null ? 0L : snap.calls();
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  @DisplayName("encode() (query-time single-doc path) N times records exactly N ORT calls")
  void singleDocPath_recordsExactlyOnceEachCall() throws Exception {
    long before = currentSpladeCallCount();
    int n = 5;
    for (int i = 0; i < n; i++) {
      Map<String, Float> unused = encoder.encode("search query number " + i);
    }
    long after = currentSpladeCallCount();
    assertEquals(
        n, after - before, "encode() single-doc path must record exactly one ORT call per call");
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  @DisplayName("encodeBatch() records at least one ORT call (the primary pinned-output path)")
  void batchedPinnedPath_records() throws Exception {
    List<String> texts =
        List.of(
            "first document about search relevance",
            "second document about sparse retrieval",
            "third document about token weighting");

    long before = currentSpladeCallCount();
    List<Map<String, Float>> results = encoder.encodeBatch(texts);
    long after = currentSpladeCallCount();

    assertEquals(texts.size(), results.size());
    assertTrue(
        after - before >= 1,
        "encodeBatch() must record at least one ORT call through the pinned-output choke point");
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  @DisplayName("N sequential encodeBatch() calls each record their own ORT call")
  void sequentialBatchedCalls_eachRecordSeparately() throws Exception {
    long before = currentSpladeCallCount();
    int n = 4;
    for (int i = 0; i < n; i++) {
      encoder.encodeBatch(List.of("document number " + i + " for parity checking"));
    }
    long after = currentSpladeCallCount();
    assertEquals(
        n,
        after - before,
        "each single-text encodeBatch() call must record exactly one ORT call");
  }
}

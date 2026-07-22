/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.encryption.DataKeyState;
import io.justsearch.agent.api.encryption.StoreCipher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 778 (store hardening) — the regression the coordinator required: the F-021 {@link
 * LabelProjection} join MUST survive at-rest encryption of the {@code AUTHORED} feedback store. When
 * the disposition + feature-snapshot streams are sealed line-by-line, a rebuild reading them with the
 * SAME key must still produce the real-label triples (both the explicit-disposition pass and the
 * derived-SHOWN-negative contrast pass). This is the runnable test behind the audit — not just the
 * store gate (audit-without-test).
 */
class FeedbackLabelsSealedJoinTest {

  /** An enabled+unlocked data key, so the cipher actually seals (not passthrough). */
  private static StoreCipher enabledCipher() {
    byte[] dek = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    return new StoreCipher(
        new DataKeyState() {
          @Override
          public boolean enabled() {
            return true;
          }

          @Override
          public boolean locked() {
            return false;
          }

          @Override
          public byte[] dek() {
            return dek;
          }
        });
  }

  @Test
  void labelProjectionJoinSurvivesSealedStores(@TempDir Path dataDir) throws IOException {
    StoreCipher cipher = enabledCipher();
    Path feedback = dataDir.resolve("feedback");

    // A contrastful group: two ranked hits, the user OPENED d1 (explicit positive); d2 was shown but
    // passed over (the derived SHOWN negative). Sealed on write with the AUTHORED key.
    var snapshots =
        new NdjsonAppendStore<>(
            feedback.resolve("feature-snapshots.ndjson"), FeatureSnapshot.class, cipher);
    snapshots.append(
        new FeatureSnapshot(
            "iid-1",
            "q",
            1L,
            List.of(
                new FeatureSnapshot.HitFeatures("d1", 1, 0.9f, 0.8f, 0.7f, 0.85f, 1024L),
                new FeatureSnapshot.HitFeatures("d2", 2, 0.2f, 0.1f, 0.1f, 0.2f, 512L))));
    var dispositions =
        new NdjsonAppendStore<>(
            feedback.resolve("result-dispositions.ndjson"), ResultDisposition.class, cipher);
    dispositions.append(
        new ResultDisposition(
            "iid-1", "d1", ResultDisposition.Kind.OPENED,
            ResultDisposition.Contributor.SEARCH_INTERACTION, 2L));

    // 1) Prove the bytes are actually SEALED on disk (not a passthrough false-green).
    for (String f : List.of("feature-snapshots.ndjson", "result-dispositions.ndjson")) {
      List<String> lines =
          Files.readAllLines(feedback.resolve(f), StandardCharsets.UTF_8).stream()
              .filter(l -> !l.isBlank())
              .toList();
      assertTrue(!lines.isEmpty(), f + " must have content");
      for (String line : lines) {
        assertTrue(line.startsWith("JSEv1:"), f + " line must be sealed, was: " + line);
      }
    }

    // 2) The join survives: rebuild WITH the same key yields the explicit positive + the derived
    // SHOWN negative — one contrast group, exactly as the plaintext path (FeedbackLabelsTest) would.
    LabelProjection.Result result = FeedbackLabels.rebuild(dataDir, cipher);
    assertEquals(2, result.triples(), "explicit OPENED(d1) + derived SHOWN(d2)");
    assertEquals(1, result.contrastGroups(), "the group has both a positive and a negative");
    assertTrue(Files.exists(FeedbackLabels.realLabelPath(dataDir)));
  }
}

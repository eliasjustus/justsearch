/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 840 Phase 2 — {@code declinedAiPackages} is the user's per-component install INTENT. It
 * has to survive being read before anything is installed and after a run that never completed, so it
 * lives here rather than in the install contract; these tests pin the null-safety and normalisation
 * it inherits from {@code excludePatterns}.
 */
final class UiSettingsDeclinedAiPackagesTest {

  @Test
  @DisplayName("defaults to empty and never returns null")
  void defaultsToEmpty() {
    UiSettings s = new UiSettings();
    assertTrue(s.getDeclinedAiPackages().isEmpty());

    s.setDeclinedAiPackages(null);
    assertTrue(s.getDeclinedAiPackages().isEmpty(), "null clears rather than NPEs on the next read");
  }

  @Test
  @DisplayName("trims, drops blanks/nulls, and dedupes while preserving order")
  void normalisesInput() {
    UiSettings s = new UiSettings();
    s.setDeclinedAiPackages(
        Arrays.asList(" reranker ", null, "", "   ", "chat", "reranker", "splade"));

    assertEquals(List.of("reranker", "chat", "splade"), s.getDeclinedAiPackages());
  }

  @Test
  @DisplayName("caps an abusive payload rather than persisting it")
  void capsAbusivePayloads() {
    List<String> many = new ArrayList<>();
    for (int i = 0; i < 1_000; i++) {
      many.add("pkg-" + i);
    }
    UiSettings s = new UiSettings();
    s.setDeclinedAiPackages(many);

    assertEquals(512, s.getDeclinedAiPackages().size());
  }
}

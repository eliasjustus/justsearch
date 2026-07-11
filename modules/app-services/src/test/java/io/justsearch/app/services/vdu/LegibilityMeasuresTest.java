/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link LegibilityMeasures#belowFloor}'s conjunctive (AND) semantics: both the Laplacian
 * and contrast floors must be breached before the gate fires. See the method's javadoc for why
 * OR would be unsafe (a single ambiguous signal, e.g. noise's high Laplacian variance, must not
 * alone drive an abstain decision).
 */
final class LegibilityMeasuresTest {

  @Test
  @DisplayName("both signals below floor: true")
  void bothBelowFloorIsTrue() {
    LegibilityMeasures measures = new LegibilityMeasures(5.0, 0.01);
    assertTrue(measures.belowFloor(50.0, 0.05));
  }

  @Test
  @DisplayName("only Laplacian below floor: false (conjunctive, not either-signal)")
  void onlyLaplacianBelowFloorIsFalse() {
    LegibilityMeasures measures = new LegibilityMeasures(5.0, 0.20);
    assertFalse(measures.belowFloor(50.0, 0.05));
  }

  @Test
  @DisplayName("only contrast below floor: false (conjunctive, not either-signal)")
  void onlyContrastBelowFloorIsFalse() {
    LegibilityMeasures measures = new LegibilityMeasures(500.0, 0.01);
    assertFalse(measures.belowFloor(50.0, 0.05));
  }

  @Test
  @DisplayName("neither below floor: false")
  void neitherBelowFloorIsFalse() {
    LegibilityMeasures measures = new LegibilityMeasures(500.0, 0.20);
    assertFalse(measures.belowFloor(50.0, 0.05));
  }

  @Test
  @DisplayName("exactly at floor is not below it (strict less-than)")
  void exactlyAtFloorIsNotBelow() {
    LegibilityMeasures measures = new LegibilityMeasures(50.0, 0.05);
    assertFalse(measures.belowFloor(50.0, 0.05));
  }
}

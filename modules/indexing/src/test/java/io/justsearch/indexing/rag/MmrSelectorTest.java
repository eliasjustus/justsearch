package io.justsearch.indexing.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MMR selector")
final class MmrSelectorTest {

  @Test
  @DisplayName("select() prefers diverse second pick when candidates are redundant")
  void prefersDiversityWhenRedundant() {
    List<Double> rel = List.of(1.0, 0.95, 0.8);
    List<float[]> vecs =
        List.of(
            new float[] {1.0f, 0.0f},
            new float[] {0.9f, 0.1f},
            new float[] {0.0f, 1.0f});

    List<Integer> selected = MmrSelector.select(0.5, rel, vecs, 3);
    assertEquals(List.of(0, 2, 1), selected);
  }

  @Test
  @DisplayName("select() handles mismatched lengths safely")
  void mismatchedLengthsSafe() {
    List<Integer> selected = MmrSelector.select(0.5, List.of(1.0), List.of(), 5);
    assertTrue(selected.isEmpty());
  }
}

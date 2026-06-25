package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Category vocabulary (slices 444a + 3a.1.4 + 448 phase 6)")
final class CategoryTest {

  @Test
  @DisplayName("vocabulary is closed at exactly five values (LOG_TAIL retired in slice 448 phase 6)")
  void vocabularyIsClosed() {
    assertEquals(5, Category.values().length);
  }

  @Test
  @DisplayName("each vocabulary value is round-trippable via valueOf")
  void valuesRoundTripViaValueOf() {
    for (Category value : Category.values()) {
      assertSame(value, Category.valueOf(value.name()));
    }
  }

  @Test
  @DisplayName("the five canonical names are present (LOG_TAIL retired)")
  void canonicalNamesPresent() {
    // Per slice 444a §B.2 + slice 3a.1.4 §B.3 + slice 448 phase 6: the five-Category
    // vocabulary covers Resource shapes across the reference workloads after LOG_TAIL
    // retired (CONFLICT-LEDGER C-012 path-b — operator-trace surfaces moved to the
    // sibling DiagnosticChannel primitive). This test pins the exact names so a future
    // commit that drops or renames a value breaks the build instead of silently
    // changing the wire shape.
    assertSame(Category.STATE, Category.valueOf("STATE"));
    assertSame(Category.EVENT_STREAM, Category.valueOf("EVENT_STREAM"));
    assertSame(Category.HISTORY, Category.valueOf("HISTORY"));
    assertSame(Category.TABULAR, Category.valueOf("TABULAR"));
    assertSame(Category.TIMESERIES, Category.valueOf("TIMESERIES"));
  }
}

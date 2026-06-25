package io.justsearch.testsupport.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class PageWindowAssertTest {

  private static PagingDeterminismHarness.PageWindow window(int page, String... ids) {
    return new PagingDeterminismHarness.PageWindow(page, List.of(ids));
  }

  @Test
  void detectsDuplicateDocumentIds() {
    assertThrows(
        IllegalStateException.class,
        () ->
            PageWindowAssert.assertNoDuplicates(List.of(window(1, "a", "b"), window(2, "b", "c"))));
  }

  @Test
  void detectsMissingPages() {
    assertThrows(
        IllegalStateException.class,
        () -> PageWindowAssert.assertNoHoles(List.of(window(1, "a"), window(3, "b"))));
  }

  @Test
  void pageWindowValidationsCoverBranches() {
    PagingDeterminismHarness.PageWindow window = window(1, "a", "b");
    assertEquals(List.of("a", "b"), window.docIds());
    assertEquals(Set.of("a", "b"), window.asSet());
    assertThrows(
        IllegalArgumentException.class, () -> new PagingDeterminismHarness.PageWindow(0, List.of()));
  }
}

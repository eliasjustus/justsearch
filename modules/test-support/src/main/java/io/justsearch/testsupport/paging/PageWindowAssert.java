/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.testsupport.paging;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Convenience assertions for deterministic paging traces. */
public final class PageWindowAssert {
  private PageWindowAssert() {}

  public static void assertNoDuplicates(List<PagingDeterminismHarness.PageWindow> windows) {
    Objects.requireNonNull(windows, "windows");
    Set<String> seen = new HashSet<>();
    for (PagingDeterminismHarness.PageWindow window : windows) {
      for (String id : window.docIds()) {
        if (!seen.add(id)) {
          throw new IllegalStateException("Duplicate document id in paging trace: " + id);
        }
      }
    }
  }

  public static void assertNoHoles(List<PagingDeterminismHarness.PageWindow> windows) {
    Objects.requireNonNull(windows, "windows");
    int expected = 1;
    for (PagingDeterminismHarness.PageWindow window : windows) {
      if (window.pageNumber() != expected) {
        throw new IllegalStateException("Paging windows missing page " + expected);
      }
      expected++;
    }
  }

}

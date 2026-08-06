/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.QueryFilterBuilder;
import java.io.File;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tempdoc 813 Slice A — path-prefix normalization has TWO implementations that must agree, because
 * the same watched-root path is used to scope two different stores:
 *
 * <ul>
 *   <li>{@link PathNormalizer#normalizePathPrefix(String)} — scopes the SQLite job queue (job
 *       counts, prefix deletes).
 *   <li>{@link QueryFilterBuilder#normalizePathPrefix(String)} — scopes Lucene PATH prefix queries
 *       (search filters, and the per-root coverage counts added by this slice).
 * </ul>
 *
 * <p>A Library folder row folds numbers from BOTH into one claim ("N remaining · M% enriched"), so
 * a divergence would silently attribute one root's documents to another. This test is the pin;
 * neither implementation may drift without it going red.
 */
@DisplayName("SQLite and Lucene path-prefix normalizers agree")
final class PathPrefixNormalizerParityTest {

  private static final boolean WINDOWS =
      io.justsearch.configuration.PlatformPaths.isWindows();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/lib/foo",
        "/lib/foo/",
        "/lib/Foo/Bar",
        "C:/Users/Elias/Documents",
        "C:\\Users\\Elias\\Documents",
        "/lib/foo bar/deep/nested/path",
        "/a_b",
        "/single"
      })
  @DisplayName("both implementations produce the identical normalized prefix")
  void normalizersAgree(String input) {
    assertEquals(
        PathNormalizer.normalizePathPrefix(input),
        QueryFilterBuilder.normalizePathPrefix(input),
        "SQLite and Lucene prefix normalization diverged for: " + input);
  }

  @Test
  @DisplayName("both append exactly one trailing separator, idempotently")
  void trailingSeparatorIsAppendedOnceByBoth() {
    String bare = File.separator + "lib" + File.separator + "foo";
    String withSep = bare + File.separator;

    for (String normalized :
        new String[] {
          PathNormalizer.normalizePathPrefix(bare),
          QueryFilterBuilder.normalizePathPrefix(bare),
          PathNormalizer.normalizePathPrefix(withSep),
          QueryFilterBuilder.normalizePathPrefix(withSep)
        }) {
      assertTrue(normalized.endsWith(File.separator), "must end with a separator: " + normalized);
      assertFalse(
          normalized.endsWith(File.separator + File.separator),
          "must not double the separator: " + normalized);
    }
    assertEquals(
        PathNormalizer.normalizePathPrefix(bare), PathNormalizer.normalizePathPrefix(withSep));
    assertEquals(
        QueryFilterBuilder.normalizePathPrefix(bare),
        QueryFilterBuilder.normalizePathPrefix(withSep));
  }

  @Test
  @DisplayName("both fold case on Windows and preserve it elsewhere")
  void caseFoldingIsPlatformConsistentAcrossBoth() {
    String mixed = File.separator + "Lib" + File.separator + "FOO";
    String sqlite = PathNormalizer.normalizePathPrefix(mixed);
    String lucene = QueryFilterBuilder.normalizePathPrefix(mixed);

    assertEquals(sqlite, lucene);
    if (WINDOWS) {
      assertEquals(mixed.toLowerCase(Locale.ROOT) + File.separator, sqlite);
    } else {
      assertEquals(mixed + File.separator, sqlite);
    }
  }

  @Test
  @DisplayName("both exclude a sibling whose name extends the prefix")
  void siblingPrefixIsExcludedByBoth() {
    String foo = File.separator + "lib" + File.separator + "foo";
    String foobarChild =
        PathNormalizer.normalizePath(
            File.separator + "lib" + File.separator + "foobar" + File.separator + "a.txt");
    String fooChild =
        PathNormalizer.normalizePath(
            File.separator + "lib" + File.separator + "foo" + File.separator + "a.txt");

    for (String prefix :
        new String[] {
          PathNormalizer.normalizePathPrefix(foo), QueryFilterBuilder.normalizePathPrefix(foo)
        }) {
      assertTrue(fooChild.startsWith(prefix), "own child must match: " + prefix);
      assertFalse(
          foobarChild.startsWith(prefix), "sibling /lib/foobar must NOT match: " + prefix);
    }
  }

  @Test
  @DisplayName("blank input is the one DELIBERATE divergence — callers must reject it first")
  void blankInputDivergesByDesign() {
    // PathNormalizer keeps blank blank so deleteByPathPrefix's match-everything guard still bites
    // on Linux (tempdoc 668). QueryFilterBuilder has no such guard and would hand back a bare
    // separator — which prefixes every absolute POSIX path. Pinned here so the asymmetry is a
    // known contract rather than a latent trap: prefix consumers must reject blank BEFORE
    // normalizing (IndexCountOps#queryRootCoverageCounts does).
    assertEquals("", PathNormalizer.normalizePathPrefix(""));
    assertEquals(File.separator, QueryFilterBuilder.normalizePathPrefix(""));
    assertNotEquals(
        PathNormalizer.normalizePathPrefix(""), QueryFilterBuilder.normalizePathPrefix(""));
  }
}

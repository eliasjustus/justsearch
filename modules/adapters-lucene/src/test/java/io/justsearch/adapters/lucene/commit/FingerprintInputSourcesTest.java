package io.justsearch.adapters.lucene.commit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 915 §C — the fingerprint has to read the value the runtime actually uses, from the same
 * place the runtime reads it. Every input that is duplicated or defaulted anywhere is a chance for
 * the commit path and the comparison path to disagree, and a disagreement costs a full reindex.
 */
final class FingerprintInputSourcesTest {

  /**
   * Writing a default out explicitly is a no-op edit. If the fingerprint hashed the raw nullable
   * config it would move, and every user would pay a rebuild for a comment-grade config change.
   * Asserted on the effective accessors, which are the single source the codec also reads.
   */
  @Test
  void anExplicitlyWrittenHnswDefaultIsIndistinguishableFromLeavingItUnset() {
    ResolvedConfig unset =
        ResolvedConfig.builder().contributeBaseSources().build();
    ResolvedConfig explicit =
        ResolvedConfig.builder()
            .contributeBaseSources()
            .putDefault(
                "index.vector.hnsw.m", String.valueOf(ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_M))
            .putDefault(
                "index.vector.hnsw.ef_construction",
                String.valueOf(ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_EF_CONSTRUCTION))
            .build();

    assertEquals(
        unset.index().effectiveVectorHnswM(),
        explicit.index().effectiveVectorHnswM(),
        "unset and explicitly-default HNSW m must be the same effective value");
    assertEquals(
        unset.index().effectiveVectorHnswEfConstruction(),
        explicit.index().effectiveVectorHnswEfConstruction(),
        "unset and explicitly-default ef_construction must be the same effective value");
    assertEquals(ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_M, unset.index().effectiveVectorHnswM());
    assertEquals(
        ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_EF_CONSTRUCTION,
        unset.index().effectiveVectorHnswEfConstruction());
  }

  /**
   * The analysis-library versions are read reflectively-ish from the libraries themselves rather
   * than pinned, so this only asserts they resolve to something usable — a null or empty version
   * would silently make an analyzer upgrade invisible to the fingerprint.
   */
  @Test
  void theAnalysisLibraryVersionsResolve() {
    String lucene = org.apache.lucene.util.Version.LATEST.toString();
    String icu = com.ibm.icu.util.VersionInfo.ICU_VERSION.toString();
    assertNotNull(lucene);
    assertNotNull(icu);
    assertTrue(lucene.matches("\\d+.*"), "Lucene version should start with a digit: " + lucene);
    assertTrue(icu.matches("\\d+.*"), "ICU version should start with a digit: " + icu);
  }
}

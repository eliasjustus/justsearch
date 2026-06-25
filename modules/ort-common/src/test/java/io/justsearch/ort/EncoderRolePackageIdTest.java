package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the invariant that every {@link EncoderRole} has a non-null, non-blank
 * {@code packageId()} and that the mapping exactly matches the package IDs used by
 * {@code KnowledgeServer.initDeferredModels()} when it calls {@code VariantSelector.select(...)}.
 *
 * <p>Because {@code packageId} is a constructor argument on the enum, adding a new constant
 * without one is a compile error — this test is the runtime defence for the <em>value</em> of
 * the mapping, not its <em>completeness</em>.
 */
@DisplayName("EncoderRole.packageId()")
class EncoderRolePackageIdTest {

  @Test
  @DisplayName("every role returns a non-blank packageId")
  void allRolesHaveNonBlankPackageId() {
    for (EncoderRole role : EncoderRole.values()) {
      String id = role.packageId();
      assertNotNull(id, role + " has null packageId");
      if (id.isBlank()) {
        throw new AssertionError(role + " has blank packageId");
      }
    }
  }

  @Test
  @DisplayName("package IDs match KnowledgeServer.initDeferredModels call sites")
  void packageIdsMatchKnowledgeServerCallSites() {
    // KnowledgeServer.java:601,687,729,780,844,889 — the "resolveVariant(packageId, ...)"
    // strings verified manually from the code. If any of these change, update KnowledgeServer
    // and this test in the same PR.
    assertEquals("embedding", EncoderRole.EMBEDDING.packageId());
    assertEquals("embedding", EncoderRole.BGE_M3.packageId()); // shares embedding package
    assertEquals("splade", EncoderRole.SPLADE.packageId());
    assertEquals("ner", EncoderRole.NER.packageId());
    assertEquals("reranker", EncoderRole.RERANKER.packageId());
    assertEquals("citation-scorer", EncoderRole.CITATION.packageId());
  }

  @Test
  @DisplayName("all 6 roles present (exhaustiveness tripwire)")
  void allRolesPresent() {
    assertEquals(6, EnumSet.allOf(EncoderRole.class).size());
  }

  // ==================== Tempdoc 374 alpha.21 Bug R: isCpuOnly ====================

  /**
   * Tempdoc 374 alpha.18 Bug I + alpha.21 Bug R: citation-scorer is the only CPU-only
   * role. The runtime path (composeCitationRole) hardcodes gpuEnabled=false; the head
   * status display must use the same per-role policy via this helper.
   */
  @Test
  @DisplayName("isCpuOnly: only CITATION returns true")
  void isCpuOnly_onlyCitation() {
    for (EncoderRole role : EncoderRole.values()) {
      if (role == EncoderRole.CITATION) {
        org.junit.jupiter.api.Assertions.assertTrue(role.isCpuOnly(),
            "CITATION must be CPU-only by design (alpha.18 Bug I)");
      } else {
        org.junit.jupiter.api.Assertions.assertFalse(role.isCpuOnly(),
            role + " must NOT be CPU-only — it has GPU-capable variants");
      }
    }
  }

  @Test
  @DisplayName("isPackageCpuOnly: 'citation-scorer' returns true; others return false")
  void isPackageCpuOnly_byPackageId() {
    org.junit.jupiter.api.Assertions.assertTrue(EncoderRole.isPackageCpuOnly("citation-scorer"));
    org.junit.jupiter.api.Assertions.assertFalse(EncoderRole.isPackageCpuOnly("embedding"));
    org.junit.jupiter.api.Assertions.assertFalse(EncoderRole.isPackageCpuOnly("splade"));
    org.junit.jupiter.api.Assertions.assertFalse(EncoderRole.isPackageCpuOnly("ner"));
    org.junit.jupiter.api.Assertions.assertFalse(EncoderRole.isPackageCpuOnly("reranker"));
    org.junit.jupiter.api.Assertions.assertFalse(EncoderRole.isPackageCpuOnly("chat"));
    org.junit.jupiter.api.Assertions.assertFalse(EncoderRole.isPackageCpuOnly("unknown-pkg"));
    org.junit.jupiter.api.Assertions.assertFalse(EncoderRole.isPackageCpuOnly(null));
  }
}

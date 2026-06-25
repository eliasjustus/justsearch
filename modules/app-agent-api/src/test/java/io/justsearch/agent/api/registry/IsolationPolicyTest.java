package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies the explicit trust → isolation mapping (tempdoc 560 §5 / P2). */
class IsolationPolicyTest {

  @Test
  void requiredIsolationIsProportionalToTrust() {
    assertEquals(IsolationLevel.IN_PROCESS, IsolationPolicy.requiredFor(TrustTier.CORE));
    assertEquals(IsolationLevel.OUT_OF_PROCESS, IsolationPolicy.requiredFor(TrustTier.TRUSTED_PLUGIN));
    assertEquals(IsolationLevel.SANDBOXED, IsolationPolicy.requiredFor(TrustTier.UNTRUSTED_PLUGIN));
  }

  @Test
  void v1AdmitsCoreAndTrustedButDeniesUntrusted() {
    assertTrue(IsolationPolicy.isAdmissible(TrustTier.CORE));
    assertTrue(IsolationPolicy.isAdmissible(TrustTier.TRUSTED_PLUGIN), "out-of-process is the MCP-host's isolation");
    // No SANDBOXED runtime in V1 → untrusted contributions are denied, not silently downgraded.
    assertFalse(IsolationPolicy.isAdmissible(TrustTier.UNTRUSTED_PLUGIN));
    assertEquals(IsolationLevel.DENIED, IsolationPolicy.effectiveFor(TrustTier.UNTRUSTED_PLUGIN));
  }

  @Test
  void availabilityReflectsV1Runtime() {
    assertTrue(IsolationPolicy.isAvailable(IsolationLevel.IN_PROCESS));
    assertTrue(IsolationPolicy.isAvailable(IsolationLevel.OUT_OF_PROCESS));
    assertFalse(IsolationPolicy.isAvailable(IsolationLevel.SANDBOXED));
    assertFalse(IsolationPolicy.isAvailable(IsolationLevel.DENIED));
  }
}

package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies the Head's trust→composer-boolean adapter (tempdoc 560 §4.3). The actual enforcement of the
 * four substrates is generic and tested in {@code ContributionComposerTest}; this asserts only that the
 * Head projects its {@link TrustTier} onto the composer's admission booleans correctly.
 */
class ContributionSubstratesTest {

  @Test
  void isCoreOnlyForCoreTier() {
    assertTrue(ContributionSubstrates.isCore(TrustTier.CORE));
    assertFalse(ContributionSubstrates.isCore(TrustTier.TRUSTED_PLUGIN));
    assertFalse(ContributionSubstrates.isCore(TrustTier.UNTRUSTED_PLUGIN));
  }

  @Test
  void boundaryAdmitsCoreAndTrustedButRefusesUntrustedWhenNoSandboxRuntime() {
    assertTrue(ContributionSubstrates.boundaryAdmissible(TrustTier.CORE));
    assertTrue(ContributionSubstrates.boundaryAdmissible(TrustTier.TRUSTED_PLUGIN));
    // V1 has no SANDBOXED runtime — UNTRUSTED is inadmissible, the composer refuses it.
    assertFalse(ContributionSubstrates.boundaryAdmissible(TrustTier.UNTRUSTED_PLUGIN));
  }

  @Test
  void boundaryDetailNamesTheRequiredIsolation() {
    assertEquals(
        "requires " + IsolationPolicy.requiredFor(TrustTier.UNTRUSTED_PLUGIN),
        ContributionSubstrates.boundaryDetail(TrustTier.UNTRUSTED_PLUGIN));
  }
}

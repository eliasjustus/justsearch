package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Provenance round-trip + back-compat tests — Tempdoc 543 §22.7 follow-up.
 *
 * <p>Validates the additive 4-arg constructor (with PluginIdentity) and the back-compat
 * 3-arg constructor delegation (existing callsites continue to compile + produce
 * identity == null).
 */
class ProvenanceTest {

  @Test
  void fourArgConstructor_carriesIdentity() {
    PluginIdentity identity = new PluginIdentity(true, "sig:abc123");
    Provenance p =
        new Provenance(TrustTier.TRUSTED_PLUGIN, "acme-plugin", "2.5.0", identity);
    assertEquals(TrustTier.TRUSTED_PLUGIN, p.tier());
    assertEquals("acme-plugin", p.contributorId());
    assertEquals("2.5.0", p.version());
    assertSame(identity, p.identity());
    assertTrue(p.identity().verified());
    assertEquals("sig:abc123", p.identity().signature());
  }

  @Test
  void threeArgConstructor_backCompat_setsIdentityNull() {
    // V1.5.1 callsites still compile via this delegation.
    Provenance p = new Provenance(TrustTier.TRUSTED_PLUGIN, "legacy-plugin", "1.0.0");
    assertEquals(TrustTier.TRUSTED_PLUGIN, p.tier());
    assertEquals("legacy-plugin", p.contributorId());
    assertEquals("1.0.0", p.version());
    assertNull(p.identity(), "back-compat constructor leaves identity null");
  }

  @Test
  void coreFactory_stampsVerifiedCoreIdentity() {
    Provenance p = Provenance.core("0");
    assertEquals(TrustTier.CORE, p.tier());
    assertEquals("core", p.contributorId());
    assertEquals("0", p.version());
    assertNotNull(p.identity(), "CORE provenance carries an identity");
    assertTrue(p.identity().verified());
    assertNull(p.identity().signature(), "CORE has no signature — verified by construction");
  }

  @Test
  void pluginIdentity_nullSignatureAllowed() {
    PluginIdentity identity = new PluginIdentity(true);
    assertTrue(identity.verified());
    assertNull(identity.signature());
  }

  @Test
  void pluginIdentity_unverifiedWithoutSignature() {
    PluginIdentity identity = new PluginIdentity(false);
    assertFalse(identity.verified());
    assertNull(identity.signature());
  }

  @Test
  void pluginIdentity_verifiedCoreHelper() {
    PluginIdentity core = PluginIdentity.verifiedCore();
    assertTrue(core.verified());
    assertNull(core.signature());
  }

  @Test
  void requiredFieldsValidated() {
    assertThrows(
        NullPointerException.class,
        () -> new Provenance(null, "contributor", "1.0", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Provenance(TrustTier.CORE, "", "1.0", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Provenance(TrustTier.CORE, "core", "", null));
  }
}

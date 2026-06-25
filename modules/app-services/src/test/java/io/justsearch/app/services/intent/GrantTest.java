package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 550 thesis IV — the one Grant primitive. The consent capsule is realized as a Grant
 * (single-use, BoundAction scope); this pins the primitive's scope authorization, expiry, and the
 * narrowing-only attenuation partial order that the model is built on.
 */
@DisplayName("Grant — the one capability/grant primitive (tempdoc 550 thesis IV)")
class GrantTest {

  private static Grant bound(String op, String hash, Instant expiry) {
    return new Grant("g1", new Grant.BoundAction(op, hash), expiry, true);
  }

  @Test
  @DisplayName("a BoundAction scope authorizes exactly its (operation, argsHash) pair")
  void boundActionAuthorizes() {
    Grant g = bound("core.reindex", "h1", Instant.parse("2026-05-26T00:05:00Z"));
    assertTrue(g.scope().authorizes("core.reindex", "h1"));
    assertFalse(g.scope().authorizes("core.reindex", "h2")); // different args
    assertFalse(g.scope().authorizes("core.other", "h1")); // different op
  }

  @Test
  @DisplayName("a CapabilityFamily scope is conservative (fail-closed) without a durable consumer")
  void capabilityFamilyConservative() {
    Grant.GrantScope fam = new Grant.CapabilityFamily("indexing");
    assertFalse(fam.authorizes("core.reindex", "h1"));
  }

  @Test
  @DisplayName("isExpired compares against the expiry instant")
  void expiry() {
    Grant g = bound("core.x", "h", Instant.parse("2026-05-26T00:05:00Z"));
    assertFalse(g.isExpired(Instant.parse("2026-05-26T00:04:59Z")));
    assertTrue(g.isExpired(Instant.parse("2026-05-26T00:05:01Z")));
  }

  @Test
  @DisplayName("attenuation narrows but never widens scope or extends expiry")
  void attenuation() {
    Instant exp = Instant.parse("2026-05-26T00:05:00Z");
    Grant g = bound("core.x", "h", exp);

    // Narrowing to the same bound action + an earlier expiry is allowed; the authority (id) holds.
    Grant narrower = g.attenuate(new Grant.BoundAction("core.x", "h"), exp.minusSeconds(60));
    assertEquals("g1", narrower.grantId());
    assertEquals(exp.minusSeconds(60), narrower.expiry());

    // Extending the expiry is rejected (cannot widen in time).
    assertThrows(
        IllegalArgumentException.class,
        () -> g.attenuate(new Grant.BoundAction("core.x", "h"), exp.plusSeconds(60)));

    // Switching to a looser/different scope is rejected (cannot widen scope).
    assertThrows(
        IllegalArgumentException.class,
        () -> g.attenuate(new Grant.CapabilityFamily("anything"), exp));
  }
}

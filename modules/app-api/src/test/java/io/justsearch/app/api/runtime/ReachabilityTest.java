package io.justsearch.app.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 501 Phase 30 (§13.4.2 reachability axis): verifies the typed
 * Reachability record's invariants and its public projection (§13.4.5).
 */
class ReachabilityTest {

  @Test
  void publicProjectionStripsAudienceFullTransports() {
    Reachability r =
        new Reachability(
            List.of(
                new Reachability.Transport(
                    Reachability.KIND_HTTP_REST,
                    "http://127.0.0.1:5000/api/runtime/manifest",
                    Reachability.AUDIENCE_PUBLIC),
                new Reachability.Transport(
                    Reachability.KIND_FILESYSTEM,
                    "/data/runtime/manifest.json",
                    Reachability.AUDIENCE_FULL)));

    Reachability publicView = r.publicProjection();

    assertNotEquals(r, publicView, "filesystem transport must be filtered out");
    assertEquals(1, publicView.transports().size());
    assertEquals(Reachability.AUDIENCE_PUBLIC, publicView.transports().get(0).audience());
  }

  @Test
  void publicProjectionIsIdentityWhenAllPublic() {
    Reachability r =
        new Reachability(
            List.of(
                new Reachability.Transport(
                    Reachability.KIND_HTTP_REST,
                    "http://127.0.0.1:5000/x",
                    Reachability.AUDIENCE_PUBLIC),
                new Reachability.Transport(
                    Reachability.KIND_SSE,
                    "http://127.0.0.1:5000/x/stream",
                    Reachability.AUDIENCE_PUBLIC)));

    assertEquals(r, r.publicProjection());
  }

  @Test
  void emptyTransportsListAccepted() {
    Reachability r = new Reachability(null);
    assertTrue(r.transports().isEmpty(), "null transports normalized to empty list");
  }

  @Test
  void transportConstructorDefaultsAudienceToPublic() {
    Reachability.Transport t = new Reachability.Transport("kind", "url", null);
    assertEquals(Reachability.AUDIENCE_PUBLIC, t.audience());
  }
}

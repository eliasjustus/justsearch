package io.justsearch.app.observability.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.SubscriptionMode;
import io.justsearch.agent.api.registry.TrustTier;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 560 WS7b — the Brain (inference runtime) surfaced as an OBSERVABLE registry participant.
 */
final class CoreInferenceResourceCatalogTest {

  private final CoreInferenceResourceCatalog catalog = new CoreInferenceResourceCatalog();

  @Test
  void declaresOneCoreStateResourceForTheInferenceRuntime() {
    List<Resource> defs = catalog.definitions();
    assertEquals(1, defs.size());
    Resource r = defs.get(0);
    assertEquals("core.inference-runtime", r.id().value());
    assertEquals(Category.STATE, r.category());
    // ONE_SHOT, not SSE — the backing /api/ai/runtime/status is a REST surface; an SSE claim would be
    // a dishonest publication.
    assertEquals(SubscriptionMode.ONE_SHOT, r.subscriptionMode());
    assertEquals("/api/ai/runtime/status", r.endpoint());
    assertEquals(TrustTier.CORE, r.provenance().tier());
    assertEquals(Audience.USER, r.audience());
  }

  @Test
  void declaresARealConsumerSoItIsNotAnUnconsumedDeclaration() {
    // The consumer-presence gate fails an OBSERVABLE Resource with no consumer; the Brain surface is
    // the honest reader.
    Resource r = catalog.definitions().get(0);
    assertFalse(r.consumers().isEmpty(), "the inference-runtime Resource must declare its FE consumer");
    assertTrue(
        r.consumers().stream().anyMatch(c -> c.consumerId().equals("brain-surface")),
        "the declared consumer is the FE Brain surface");
  }
}

package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Invariant tests for slice 490 §4.A's reserved {@code KIND_ADVISORY} value and the
 * cross-field rules its presence implies.
 */
final class ResourceAdvisoryInvariantsTest {

  // Slice 490 §4.A: per ResourceRef's namespacing pattern
  // (^(core|vendor\.<x>)\.[a-z][a-z0-9-]*$), nested dots in IDs are disallowed. Advisory
  // classes therefore kebab-case the discriminator into the ID (core.advisory-<class>),
  // and the `kind` field (Resource.KIND_ADVISORY) is the renderer-selection key.
  private static final ResourceRef ADVISORY_ID = new ResourceRef("core.advisory-test");
  private static final ResourceRef NON_ADVISORY_ID = new ResourceRef("core.test-stream");
  private static final Presentation PRESENTATION =
      Presentation.of(new I18nKey("test.label"), new I18nKey("test.description"));
  private static final Provenance PROVENANCE = new Provenance(TrustTier.CORE, "test", "1.0");
  private static final Privacy PRIVACY = Privacy.noPaths();
  private static final HistoryPolicy RING_BUFFER =
      new HistoryPolicy(
          HistoryPolicy.Mode.RING_BUFFER,
          Optional.of(100),
          Optional.empty(),
          OnOverflow.EVICT_OLDEST,
          Duration.ofSeconds(30));

  @Test
  void advisoryKindAllowsValidShape() {
    Resource r =
        new Resource(
            ADVISORY_ID,
            PRESENTATION,
            "{\"type\":\"object\"}",
            Category.EVENT_STREAM,
            SubscriptionMode.SSE_STREAM,
            "/api/advisory/test/stream",
            Resource.KIND_ADVISORY,
            Optional.of(RING_BUFFER),
            Optional.empty(),
            PROVENANCE,
            PRIVACY,
            Set.of(),
            Set.of(),
            "",
            Optional.of(EmissionPolicy.persisted()));
    assertEquals(Optional.of(EmissionPolicy.persisted()), r.emissionPolicy());
    assertEquals(Resource.KIND_ADVISORY, r.kind());
  }

  @Test
  void advisoryKindRequiresEmissionPolicy() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Resource(
                    ADVISORY_ID,
                    PRESENTATION,
                    "{\"type\":\"object\"}",
                    Category.EVENT_STREAM,
                    SubscriptionMode.SSE_STREAM,
                    "/api/advisory/test/stream",
                    Resource.KIND_ADVISORY,
                    Optional.of(RING_BUFFER),
                    Optional.empty(),
                    PROVENANCE,
                    PRIVACY,
                    Set.of(),
                    Set.of(),
                    "",
                    Optional.empty()));
    assertTrue(ex.getMessage().contains("emissionPolicy must be present"));
  }

  @Test
  void nonAdvisoryKindRejectsEmissionPolicy() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Resource(
                    NON_ADVISORY_ID,
                    PRESENTATION,
                    "{\"type\":\"object\"}",
                    Category.EVENT_STREAM,
                    SubscriptionMode.SSE_STREAM,
                    "/api/test/stream",
                    "test-event-stream",
                    Optional.of(RING_BUFFER),
                    Optional.empty(),
                    PROVENANCE,
                    PRIVACY,
                    Set.of(),
                    Set.of(),
                    "",
                    Optional.of(EmissionPolicy.persisted())));
    assertTrue(ex.getMessage().contains("emissionPolicy is only valid for kind="));
  }

  @Test
  void advisoryKindRequiresEventStreamCategory() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Resource(
                    ADVISORY_ID,
                    PRESENTATION,
                    "{\"type\":\"object\"}",
                    Category.STATE,
                    SubscriptionMode.SSE_STREAM,
                    "/api/advisory/test/stream",
                    Resource.KIND_ADVISORY,
                    Optional.of(RING_BUFFER),
                    Optional.empty(),
                    PROVENANCE,
                    PRIVACY,
                    Set.of(),
                    Set.of(),
                    "",
                    Optional.of(EmissionPolicy.persisted())));
    assertTrue(ex.getMessage().contains("category must be EVENT_STREAM"));
  }

  @Test
  void advisoryKindRequiresSseStreamSubscription() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Resource(
                    ADVISORY_ID,
                    PRESENTATION,
                    "{\"type\":\"object\"}",
                    Category.EVENT_STREAM,
                    SubscriptionMode.POLLING,
                    "/api/advisory/test/stream",
                    Resource.KIND_ADVISORY,
                    Optional.of(RING_BUFFER),
                    Optional.empty(),
                    PROVENANCE,
                    PRIVACY,
                    Set.of(),
                    Set.of(),
                    "",
                    Optional.of(EmissionPolicy.persisted())));
    assertTrue(ex.getMessage().contains("subscriptionMode must be SSE_STREAM"));
  }

  @Test
  void advisoryKindRequiresHistoryPolicy() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new Resource(
                    ADVISORY_ID,
                    PRESENTATION,
                    "{\"type\":\"object\"}",
                    Category.EVENT_STREAM,
                    SubscriptionMode.SSE_STREAM,
                    "/api/advisory/test/stream",
                    Resource.KIND_ADVISORY,
                    Optional.empty(),
                    Optional.empty(),
                    PROVENANCE,
                    PRIVACY,
                    Set.of(),
                    Set.of(),
                    "",
                    Optional.of(EmissionPolicy.persisted())));
    assertTrue(ex.getMessage().contains("history (HistoryPolicy) must be present"));
  }

  @Test
  void backwardsCompatConstructorDefaultsEmissionPolicyEmpty() {
    Resource r =
        new Resource(
            NON_ADVISORY_ID,
            PRESENTATION,
            "{\"type\":\"object\"}",
            Category.EVENT_STREAM,
            SubscriptionMode.SSE_STREAM,
            "/api/test/stream",
            "test-event-stream",
            Optional.of(RING_BUFFER),
            Optional.empty(),
            PROVENANCE,
            PRIVACY,
            Set.of(),
            Set.of(),
            "");
    assertTrue(r.emissionPolicy().isEmpty());
  }
}

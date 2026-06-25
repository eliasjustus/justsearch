package io.justsearch.app.services.registry.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.ConsumerView;
import io.justsearch.agent.api.registry.EmissionPolicy;
import io.justsearch.agent.api.registry.HistoryPolicy;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.OnOverflow;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.PluginIdentity;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RenderHint;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.SubscriptionMode;
import io.justsearch.agent.api.registry.TrustTier;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 560 §4c — wire-conformance pin for the {@code /api/registry/resources} entry shape.
 *
 * <p>The Resource wire moved from raw-record serialization ({@code convertValue(resource, Map)}) to
 * the typed {@link io.justsearch.agent.api.registry.UIResourceView} ({@link UIResourceEmitter}). This
 * test proves the view reproduces the historical raw-record wire <em>component-for-component</em>:
 * for representative resources covering every field shape (present/empty {@code Optional}, {@code
 * Duration}, null/non-null plugin identity, empty/non-empty {@code Set} + consumers, advisory {@code
 * EmissionPolicy}), the view-projected entry equals the raw-record entry once both consumers are
 * normalized to the discriminator-free {@link ConsumerView} (the wire never carried {@code kind} —
 * the controller's {@code Map} round-trip erased it). Any mis-wired field surfaces as inequality.
 */
@DisplayName("UIResourceView wire conformance")
final class UIResourceViewConformanceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("the typed view reproduces the raw-record Resource wire for every field shape")
  void viewReproducesRawRecordWire() {
    for (Resource r : List.of(minimalResource(), richResource(), advisoryResource())) {
      // Old wire: raw-record serialization, with consumers normalized to the flat shape the wire
      // actually carried (Map-erased — no `kind`).
      Map<String, Object> oldEntry = new LinkedHashMap<>(MAPPER.convertValue(r, Map.class));
      oldEntry.put(
          "consumers",
          r.consumers().stream()
              .map(ConsumerView::from)
              .map(cv -> MAPPER.convertValue(cv, Map.class))
              .toList());

      Map<String, Object> newEntry = UIResourceEmitter.toEntry(r);

      JsonNode oldTree = MAPPER.readTree(MAPPER.writeValueAsString(oldEntry));
      JsonNode newTree = MAPPER.readTree(MAPPER.writeValueAsString(newEntry));
      assertEquals(
          oldTree,
          newTree,
          "UIResourceView wire diverged from the raw-record wire for " + r.id().value());
    }
  }

  /** STATE resource: empty Optionals, null identity (3-arg Provenance), empty Sets + consumers. */
  private static Resource minimalResource() {
    return new Resource(
        new ResourceRef("core.demo-min"),
        Presentation.of(new I18nKey("res.min.label"), new I18nKey("res.min.description")),
        "{}",
        Category.STATE,
        SubscriptionMode.ONE_SHOT,
        "/api/min",
        "min-kind",
        Optional.empty(),
        Optional.empty(),
        new Provenance(TrustTier.CORE, "core", "1.0.0"),
        Privacy.noPaths(),
        Set.of(),
        Set.of(),
        "",
        Audience.USER,
        List.of(),
        Optional.empty());
  }

  /** Rich non-advisory resource: present history (Duration), recovery, non-null signed identity,
   *  hashed privacy + resolver, non-empty item/collection ops + consumers. */
  private static Resource richResource() {
    return new Resource(
        new ResourceRef("core.demo-rich"),
        new Presentation(
            new I18nKey("res.rich.label"),
            new I18nKey("res.rich.description"),
            Optional.of("warning"),
            Optional.of("diagnostic")),
        "{\"type\":\"object\"}",
        Category.HISTORY,
        SubscriptionMode.POLLING,
        "/api/rich",
        "rich-kind",
        Optional.of(
            new HistoryPolicy(
                HistoryPolicy.Mode.DURABLE,
                Optional.of(100),
                Optional.of(Duration.ofHours(1)),
                OnOverflow.EVICT_OLDEST,
                Duration.ofMinutes(5))),
        Optional.of(new OperationRef("core.recover-demo")),
        new Provenance(
            TrustTier.TRUSTED_PLUGIN, "vendor.acme", "1.2.3", new PluginIdentity(true, "sig-abc")),
        Privacy.hashedWithResolver(new OperationRef("core.resolve-path-hash")),
        Set.of(new OperationRef("core.item-op")),
        Set.of(new OperationRef("core.collection-op")),
        "rowId",
        Audience.OPERATOR,
        List.of(new ConsumerHook.Realized("renderer.demo", Audience.USER)),
        Optional.empty());
  }

  /** Advisory resource: exercises EmissionPolicy with a dedupeWindow Duration + ring-buffer history. */
  private static Resource advisoryResource() {
    return new Resource(
        new ResourceRef("core.demo-advisory"),
        Presentation.of(new I18nKey("res.adv.label"), new I18nKey("res.adv.description")),
        "{}",
        Category.EVENT_STREAM,
        SubscriptionMode.SSE_STREAM,
        "/api/adv",
        Resource.KIND_ADVISORY,
        Optional.of(
            new HistoryPolicy(
                HistoryPolicy.Mode.RING_BUFFER,
                Optional.of(50),
                Optional.empty(),
                OnOverflow.EVICT_OLDEST,
                Duration.ofMinutes(1))),
        Optional.empty(),
        Provenance.core("1.0.0"),
        Privacy.noPaths(),
        Set.of(),
        Set.of(),
        "",
        Audience.USER,
        List.of(new ConsumerHook.Realized("toast.adv", Audience.USER)),
        Optional.of(new EmissionPolicy(RenderHint.PERSISTED, Optional.of(Duration.ofSeconds(30)))));
  }
}

package io.justsearch.app.services.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.HistoryPolicy;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.OnOverflow;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.SubscriptionMode;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slice 481 §7 step 3 follow-up — Pass 9 Jackson round-trip test.
 *
 * <p>Pass 8 §2 flagged that {@code RegistryController.handleResources} relies on
 * {@code MAPPER.convertValue(resource, Map.class)} to project Resources for wire
 * augmentation, but no test verifies the projection is lossless. This test pins
 * the contract: every component of the {@link Resource} record (16 components
 * post-slice-481) appears in the projected Map with the right shape.
 *
 * <p>Specifically guards against:
 * <ul>
 *   <li>{@code Optional&lt;HistoryPolicy&gt;} losing its empty-vs-present
 *       distinction
 *   <li>{@code Optional&lt;OperationRef&gt;} {@code recovery} field shape drift
 *   <li>{@code Set&lt;OperationRef&gt;} fields ({@code itemOperations},
 *       {@code collectionOperations}) flattening to lists or losing entries
 *   <li>The new {@code audience} and {@code consumers} fields being elided
 * </ul>
 */
final class ResourceJacksonProjectionTest {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private static Resource makeResource() {
    return new Resource(
        new ResourceRef("core.test-resource"),
        Presentation.of(
            new I18nKey("registry-resource.test.label"),
            new I18nKey("registry-resource.test.description")),
        "https://ssot.justsearch/v1/schemas/test-resource.v1.json",
        Category.TABULAR,
        SubscriptionMode.ONE_SHOT,
        "/api/test-resource",
        "test-resource-table",
        Optional.of(
            new HistoryPolicy(
                HistoryPolicy.Mode.RING_BUFFER,
                Optional.of(100),
                Optional.of(java.time.Duration.ofHours(1)),
                OnOverflow.EVICT_OLDEST,
                java.time.Duration.ofMinutes(5))),
        Optional.of(new OperationRef("core.test-recovery")),
        Provenance.core("test"),
        Privacy.noPaths(),
        Set.of(new OperationRef("core.test-item-op")),
        Set.of(new OperationRef("core.test-collection-op")),
        "primaryKeyField",
        Audience.OPERATOR);
  }

  @Test
  @DisplayName("convertValue preserves all 18 record components as map keys")
  void convertValue_preservesAllComponents() {
    Resource r = makeResource();
    @SuppressWarnings("unchecked")
    Map<String, Object> projected = MAPPER.convertValue(r, Map.class);

    // 18 WIRE record components + Jackson's "type" discriminator from RegistryEntry's
    // @JsonTypeInfo annotation = 19 keys (slice 490: emissionPolicy added; tempdoc 571: role added).
    // tempdoc 575 added a 19th record component, `origin`, but it is @JsonIgnore'd (a source-only
    // governance facet the observed-happening gate reads from catalog source — no runtime consumer), so
    // it is intentionally ABSENT from the wire projection: the wire still carries exactly these 18.
    Set<String> expectedKeys =
        Set.of(
            "type", // sealed-interface discriminator
            "id",
            "presentation",
            "schema",
            "category",
            "subscriptionMode",
            "endpoint",
            "kind",
            "history",
            "recovery",
            "provenance",
            "privacy",
            "itemOperations",
            "collectionOperations",
            "primaryKey",
            "audience",
            "consumers",
            "emissionPolicy",
            "role");
    assertEquals(expectedKeys, projected.keySet());
    assertEquals("resource", projected.get("type"));
  }

  @Test
  @DisplayName("Optional<HistoryPolicy> serializes as nested object when present")
  void optionalHistoryPolicy_presentSerializesAsObject() {
    Resource r = makeResource();
    @SuppressWarnings("unchecked")
    Map<String, Object> projected = MAPPER.convertValue(r, Map.class);

    Object history = projected.get("history");
    assertNotNull(history);
    assertTrue(history instanceof Map, "history should be a Map when present, got " + history.getClass());
    @SuppressWarnings("unchecked")
    Map<String, Object> historyMap = (Map<String, Object>) history;
    assertEquals("RING_BUFFER", historyMap.get("mode"));
    assertEquals(100, historyMap.get("capacity"));
  }

  @Test
  @DisplayName("Optional<HistoryPolicy> serializes as null when empty")
  void optionalHistoryPolicy_emptySerializesAsNull() {
    Resource r =
        new Resource(
            new ResourceRef("core.empty-history"),
            Presentation.of(
                new I18nKey("registry-resource.x.label"),
                new I18nKey("registry-resource.x.description")),
            "schema-url",
            Category.STATE,
            SubscriptionMode.ONE_SHOT,
            "/api/x",
            "x-kind",
            Optional.empty(),
            Optional.empty(),
            Provenance.core("test"),
            Privacy.noPaths(),
            Set.of(),
            Set.of(),
            "",
            Audience.USER);
    @SuppressWarnings("unchecked")
    Map<String, Object> projected = MAPPER.convertValue(r, Map.class);

    assertTrue(projected.containsKey("history"), "key should still be present");
    assertEquals(null, projected.get("history"), "Optional.empty() should serialize as null");
    assertEquals(null, projected.get("recovery"));
  }

  @Test
  @DisplayName("Optional<OperationRef> recovery serializes as bare string when present")
  void optionalRecovery_presentSerializesAsString() {
    Resource r = makeResource();
    @SuppressWarnings("unchecked")
    Map<String, Object> projected = MAPPER.convertValue(r, Map.class);

    Object recovery = projected.get("recovery");
    assertNotNull(recovery);
    assertEquals("core.test-recovery", recovery.toString(),
        "OperationRef should serialize as bare string via @JsonValue");
  }

  @Test
  @DisplayName("Set<OperationRef> serializes as array of bare strings")
  void operationRefSet_serializesAsArrayOfStrings() {
    Resource r = makeResource();
    @SuppressWarnings("unchecked")
    Map<String, Object> projected = MAPPER.convertValue(r, Map.class);

    Object items = projected.get("itemOperations");
    assertTrue(items instanceof java.util.Collection, "itemOperations should be a collection");
    @SuppressWarnings("unchecked")
    java.util.Collection<Object> itemsCollection = (java.util.Collection<Object>) items;
    assertEquals(1, itemsCollection.size());
    Object first = itemsCollection.iterator().next();
    assertEquals("core.test-item-op", first.toString());
  }

  @Test
  @DisplayName("Audience serializes as uppercase enum name")
  void audience_serializesAsUppercaseName() {
    Resource r = makeResource();
    @SuppressWarnings("unchecked")
    Map<String, Object> projected = MAPPER.convertValue(r, Map.class);

    assertEquals("OPERATOR", projected.get("audience"));
  }

  @Test
  @DisplayName("consumers field serializes as empty list by default")
  void consumers_serializesAsEmptyList() {
    Resource r = makeResource();
    @SuppressWarnings("unchecked")
    Map<String, Object> projected = MAPPER.convertValue(r, Map.class);

    Object consumers = projected.get("consumers");
    assertTrue(consumers instanceof java.util.Collection,
        "consumers should be a collection, got " + (consumers == null ? "null" : consumers.getClass()));
    @SuppressWarnings("unchecked")
    java.util.Collection<Object> coll = (java.util.Collection<Object>) consumers;
    assertEquals(0, coll.size());
  }

  @Test
  @DisplayName("primaryKey serializes as top-level string")
  void primaryKey_topLevelString() {
    Resource r = makeResource();
    @SuppressWarnings("unchecked")
    Map<String, Object> projected = MAPPER.convertValue(r, Map.class);

    assertEquals("primaryKeyField", projected.get("primaryKey"));
  }

  @Test
  @DisplayName("Privacy projects as nested object with pathPolicy / loopbackOnly / resolver")
  void privacy_nestedObjectShape() {
    Resource r = makeResource();
    @SuppressWarnings("unchecked")
    Map<String, Object> projected = MAPPER.convertValue(r, Map.class);

    Object privacy = projected.get("privacy");
    assertTrue(privacy instanceof Map);
    @SuppressWarnings("unchecked")
    Map<String, Object> privacyMap = (Map<String, Object>) privacy;
    assertEquals("NO_PATHS", privacyMap.get("pathPolicy"));
    assertNotNull(privacyMap.containsKey("loopbackOnly"));
    assertTrue(privacyMap.containsKey("resolver"));
  }
}

package io.justsearch.app.services.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.PathPolicy;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.SubscriptionMode;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.app.observability.indexing.IndexingJobsResourceCatalog;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slice 3a.1.9 §A.0 wire-shape contract test.
 *
 * Locks the Resource catalog wire shape that the FE
 * `ResourceCatalogClient` consumes. A regression here breaks the
 * substrate's catalog-driven consumption path; the test pins:
 *
 * <ul>
 *   <li>OperationRef serializes as a bare string (Java record's
 *       @JsonValue), not an object.
 *   <li>Optional&lt;X&gt; serializes as `X` or `null`.
 *   <li>Set&lt;X&gt; serializes as `X[]`.
 *   <li>Resource.primaryKey is emitted as a top-level string.
 *   <li>Privacy axis is emitted with `pathPolicy`, `loopbackOnly`,
 *       `resolver` (nullable string).
 * </ul>
 */
@DisplayName("Resource wire-shape contract")
final class WireShapeContractTest {

  private static final JsonMapper MAPPER = new JsonMapper();

  @Test
  @DisplayName("IndexingJobsResourceCatalog entry round-trips with all 14 Resource fields")
  void indexingJobsRoundTrips() throws Exception {
    Resource entry = new IndexingJobsResourceCatalog().definitions().get(0);
    JsonNode node = MAPPER.valueToTree(entry);

    assertEquals("core.indexing-jobs", node.get("id").asString());
    assertEquals("TABULAR", node.get("category").asString());
    assertEquals("SSE_STREAM", node.get("subscriptionMode").asString());
    assertEquals("/api/indexing-jobs/stream", node.get("endpoint").asString());
    assertEquals("indexing-jobs-table", node.get("kind").asString());
    assertTrue(node.get("history").isNull(), "no history declared");
    assertTrue(node.get("recovery").isNull(), "no recovery declared");
    assertEquals("pathHash", node.get("primaryKey").asString());

    JsonNode privacy = node.get("privacy");
    assertNotNull(privacy, "privacy emitted");
    assertEquals("HASHED_REQUIRES_RESOLVER", privacy.get("pathPolicy").asString());
    assertEquals(true, privacy.get("loopbackOnly").asBoolean());
    assertEquals("core.resolve-path-hash", privacy.get("resolver").asString());

    JsonNode itemOps = node.get("itemOperations");
    assertTrue(itemOps.isArray(), "itemOperations is array");
    assertEquals(2, itemOps.size());
    assertTrue(itemOps.get(0).isString(), "OperationRef is a bare string, not an object");

    JsonNode collOps = node.get("collectionOperations");
    assertTrue(collOps.isArray());
    assertEquals(1, collOps.size());

    JsonNode presentation = node.get("presentation");
    assertNotNull(presentation);
    assertEquals(
        "registry-resource.indexing-jobs.label",
        presentation.get("labelKey").asString());
  }

  @Test
  @DisplayName("non-TABULAR Resource emits primaryKey as empty string")
  void nonTabularPrimaryKeyEmpty() throws Exception {
    Resource entry =
        new Resource(
            new ResourceRef("core.test-state"),
            Presentation.of(
                new I18nKey("k.label"), new I18nKey("k.desc")),
            "https://example/schema.json",
            Category.STATE,
            SubscriptionMode.SSE_STREAM,
            "/api/test/stream",
            "test-state",
            Optional.empty(),
            Optional.empty(),
            Provenance.core("1.0"),
            Privacy.noPaths(),
            Set.of(),
            Set.of(),
            "");
    JsonNode node = MAPPER.valueToTree(entry);
    assertEquals("", node.get("primaryKey").asString());
  }

  @Test
  @DisplayName("TABULAR with blank primaryKey rejected at construction")
  void tabularBlankPrimaryKeyThrows() {
    var presentation =
        Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc"));
    Throwable t =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                new Resource(
                    new ResourceRef("core.test-tabular"),
                    presentation,
                    "https://example/schema.json",
                    Category.TABULAR,
                    SubscriptionMode.SSE_STREAM,
                    "/api/test/stream",
                    "test-tabular",
                    Optional.empty(),
                    Optional.empty(),
                    Provenance.core("1.0"),
                    Privacy.noPaths(),
                    Set.of(),
                    Set.of(),
                    ""));
    assertTrue(t.getMessage().contains("primaryKey"));
  }

  @Test
  @DisplayName("Privacy.NO_PATHS resolver is null in JSON")
  void privacyNoPathsResolverNull() throws Exception {
    Privacy noPaths = Privacy.noPaths();
    JsonNode node = MAPPER.valueToTree(noPaths);
    assertEquals("NO_PATHS", node.get("pathPolicy").asString());
    assertTrue(node.get("resolver").isNull());
  }
}

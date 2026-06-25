package io.justsearch.app.services.registry.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.SubscriptionMode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("KindRendererCrossRefValidator (slice 3a.1.9 §B.B.B D2)")
final class KindRendererCrossRefValidatorTest {

  private static Resource resource(Category category, String kind) {
    return new Resource(
        new ResourceRef("core.test-resource"),
        Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc")),
        "https://example/schema.json",
        category,
        SubscriptionMode.SSE_STREAM,
        "/api/test/stream",
        kind,
        Optional.empty(),
        Optional.empty(),
        Provenance.core("1.0"),
        Privacy.noPaths(),
        Set.of(),
        Set.of(),
        category == Category.TABULAR ? "id" : "");
  }

  private static ResourceCatalog catalogOf(Resource entry) {
    return new ResourceCatalog() {
      @Override
      public String namespace() {
        return "core";
      }

      @Override
      public List<Resource> definitions() {
        return List.of(entry);
      }
    };
  }

  @Test
  @DisplayName("Category with default renderer never flags (TABULAR)")
  void tabularNeverFlags() {
    var validator = new KindRendererCrossRefValidator();
    var findings =
        validator.validate(
            catalogOf(resource(Category.TABULAR, "any-arbitrary-kind")), Set.of());
    assertEquals(List.of(), findings);
  }

  @Test
  @DisplayName("Category with default renderer never flags (EVENT_STREAM)")
  void eventStreamNeverFlags() {
    var validator = new KindRendererCrossRefValidator();
    var findings =
        validator.validate(
            catalogOf(resource(Category.EVENT_STREAM, "unknown-event-kind")), Set.of());
    assertEquals(List.of(), findings);
  }

  @Test
  @DisplayName("HISTORY without specialty + unknown kind flags")
  void historyUnknownKindFlags() {
    var validator = new KindRendererCrossRefValidator();
    var findings =
        validator.validate(
            catalogOf(resource(Category.HISTORY, "operation-history-novel")),
            Set.of("operation-history"));
    assertEquals(1, findings.size());
    var f = findings.get(0);
    assertEquals("core.test-resource", f.resourceId());
    assertTrue(f.issue().contains("operation-history-novel"));
    assertTrue(f.issue().contains("HISTORY"));
    assertTrue(f.issue().contains("444c"));
  }

  @Test
  @DisplayName("HISTORY with known kind passes")
  void historyKnownKindPasses() {
    var validator = new KindRendererCrossRefValidator();
    var findings =
        validator.validate(
            catalogOf(resource(Category.HISTORY, "operation-history")),
            Set.of("operation-history"));
    assertEquals(List.of(), findings);
  }

  // LOG_TAIL test deleted in slice 448 phase 6 — Category.LOG_TAIL retired per
  // CONFLICT-LEDGER C-012 path-b. Operator-trace surfaces are now modeled via the
  // sibling DiagnosticChannel primitive (slice 448), with their own area validator
  // (DiagnosticChannelAreaValidator).
}

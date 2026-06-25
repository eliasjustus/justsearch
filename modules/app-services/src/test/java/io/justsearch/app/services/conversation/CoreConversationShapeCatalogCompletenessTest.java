package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.app.observability.surface.CoreSurfaceCatalog;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 491 §9.D Phase E (C5) — Pass-9 audit on {@link CoreConversationShapeCatalog}.
 *
 * <p>Mirrors {@code CoreIntentSourceCatalogTest.everyTransportEitherHasACoreSourceOrIsExplicitlyExempt}
 * (per §9.E A9): data-driven completeness test enumerating each shape and asserting structural
 * shape rules. Each shape must:
 *
 * <ul>
 *   <li>Have a well-formed id ({@code core.<thing>} pattern; non-blank).
 *   <li>Declare a non-null Presentation with non-null I18nKeys.
 *   <li>Declare an Audience.
 *   <li>Declare a Provenance.
 *   <li>Declare a non-null eventSchema (may be empty for placeholder shapes; today all
 *       shipped shapes declare at least {@code chunk} / {@code done} / {@code error}).
 * </ul>
 *
 * <p>The FE-side view-factory coverage (every USER-audience shape has a matching
 * {@code registerViewFactory(...)} call) is enforced by
 * {@code scripts/ci/check-shape-view-coverage.mjs} since it requires a string-grep over
 * the FE tree — this test handles the backend half of the audit.
 */
final class CoreConversationShapeCatalogCompletenessTest {

  @Test
  @DisplayName("Catalog ships >0 shapes and every shape has a well-formed core.* id")
  void everyShapeHasAWellFormedId() {
    List<ConversationShape> defs = CoreConversationShapeCatalog.catalog().definitions();
    assertFalse(defs.isEmpty(), "catalog should ship at least one shape");
    for (ConversationShape shape : defs) {
      assertNotNull(shape.id(), "id must not be null");
      String value = shape.id().value();
      assertNotNull(value, "id.value() must not be null");
      assertFalse(value.isBlank(), () -> "id must not be blank: " + shape);
      assertTrue(
          value.startsWith("core."),
          () -> "core-tier shape id must start with 'core.': " + value);
      assertTrue(
          value.matches("core\\.[a-z][a-z0-9-]*"),
          () -> "id must match core.<kebab>: " + value);
    }
  }

  @Test
  @DisplayName("Every shape declares Presentation + Audience + Provenance + eventSchema")
  void everyShapeDeclaresRequiredFields() {
    for (ConversationShape shape : CoreConversationShapeCatalog.catalog().definitions()) {
      assertNotNull(shape.presentation(), () -> "presentation null on " + shape.id());
      assertNotNull(
          shape.presentation().labelKey(), () -> "labelKey null on " + shape.id());
      assertNotNull(
          shape.presentation().descriptionKey(),
          () -> "descriptionKey null on " + shape.id());
      assertNotNull(shape.audience(), () -> "audience null on " + shape.id());
      assertNotNull(shape.provenance(), () -> "provenance null on " + shape.id());
      assertNotNull(shape.eventSchema(), () -> "eventSchema null on " + shape.id());
      // Don't assert non-empty eventSchema: a future placeholder shape may declare zero
      // events legitimately. The non-null check is the structural invariant.
    }
  }

  @Test
  @DisplayName("CoreConversationShapeCatalog namespace is 'core' (provenance integrity)")
  void coreNamespace() {
    assertTrue(
        CoreConversationShapeCatalog.catalog().definitions().stream()
            .allMatch(s -> "core".equals(s.provenance().contributorId())),
        "every core-catalog shape must declare provenance.contributorId='core'");
  }

  @Test
  @DisplayName(
      "Slice 491 §9.D Phase E (C4 + F1): AgentRunShape declares core.url-extractor in"
          + " streamConsumerIds (load-bearing — ToolIteratingShapeRunner resolves the"
          + " id via the shared StreamConsumerRegistry)")
  void agentRunShapeDeclaresUrlExtractor() {
    ConversationShape agentShape =
        CoreConversationShapeCatalog.catalog()
            .findById(AgentRunShape.ID)
            .orElseThrow(() -> new AssertionError("AgentRunShape missing from catalog"));
    assertTrue(
        agentShape.streamConsumerIds().contains("core.url-extractor"),
        () ->
            "AgentRunShape.streamConsumerIds should declare 'core.url-extractor' as the"
                + " capability id (resolved by ToolIteratingShapeRunner via the shared"
                + " StreamConsumerRegistry); actual = "
                + agentShape.streamConsumerIds());
  }

  /**
   * Slice 491 F4 — Real Pass-9 audit. Mirrors {@link
   * io.justsearch.app.services.intent.CoreIntentSourceCatalogTest#everyTransportEitherHasACoreSourceOrIsExplicitlyExempt}
   * (per §9.E A9): every USER-audience shape must be discoverable from a user-clickable
   * mount path (reverse reference from some Surface's
   * {@code SurfaceConsumes.conversationShapes}), OR be explicitly exempt with documented
   * rationale.
   *
   * <p>G4 Pass-8 follow-up: the original F4 audit also accepted a forward
   * {@code ShapeProjections} declaration on the shape, but that field was retracted as
   * C-018 substrate-without-consumer (only this test read it). The audit collapses to
   * surface-reverse-reference + EXEMPT only.
   */
  private static final Set<ConversationShapeRef> EXEMPT_FROM_USER_MOUNT =
      Set.of(
          // NavigateChatShape's audience is the agent-runner via URLExtractor, NOT a
          // top-level user. The shape has no surface entry by design — it exists to
          // be invoked via /api/chat/url-emit for probes + future consumers without
          // agent-loop machinery. Documented in §9.F Q7 resolution + §9.D row 320.
          // This is the only architectural exemption; all four F4-temporary entries
          // were lifted in F5 (AgentSurface + BrowseSurface gained reverse references
          // in CoreSurfaceCatalog; SummarizeView extended to handle batch +
          // hierarchical by shape-id branching).
          new ConversationShapeRef("core.navigate-chat"));

  @Test
  @DisplayName(
      "F4 (G4-Pass-8 follow-up): every USER-audience shape is reverse-referenced by a"
          + " Surface's conversationShapes set, OR is explicitly exempt")
  void everyUserAudienceShapeHasASurfaceConsumer() {
    List<ConversationShape> shapes = CoreConversationShapeCatalog.catalog().definitions();
    var surfaces = new CoreSurfaceCatalog().definitions();
    for (ConversationShape shape : shapes) {
      if (shape.audience() != Audience.USER) continue;
      boolean hasReverseReference =
          surfaces.stream()
              .anyMatch(s -> s.consumes().conversationShapes().contains(shape.id()));
      if (EXEMPT_FROM_USER_MOUNT.contains(shape.id())) {
        // Sanity-check: exempt shapes MUST NOT also have a discoverable mount, else
        // the exemption is stale (and a future reader will think the exemption is
        // the only path when in fact there's a real consumer).
        assertFalse(
            hasReverseReference,
            () ->
                "Shape "
                    + shape.id()
                    + " is in EXEMPT_FROM_USER_MOUNT but ALSO has a Surface reverse"
                    + " reference. Drop the exemption.");
        continue;
      }
      assertTrue(
          hasReverseReference,
          () ->
              "USER-audience shape "
                  + shape.id()
                  + " has no Surface consumer. Either: (a) add this id to some"
                  + " Surface's SurfaceConsumes.conversationShapes set, OR (b) add"
                  + " the id to EXEMPT_FROM_USER_MOUNT with rationale (e.g., the"
                  + " shape is agent-runner-only or invoked programmatically only).");
    }
  }
}

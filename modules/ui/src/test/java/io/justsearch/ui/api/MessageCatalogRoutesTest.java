package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import io.javalin.Javalin;
import io.javalin.router.InternalRouter;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.ui.api.routes.MessageCatalogRoutes;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 583 Stage 1: behaviour-preservation oracle for the MessageCatalog route extraction.
 *
 * <p>Before Stage 1, LocalApiServer constructed nine {@code MessageCatalogController} fields and
 * bound nine {@code GET /api/messages/&lt;ns&gt;/{locale}} routes inline. The declarative {@link
 * MessageCatalogRoutes} table must bind exactly the same nine routes — no more, no fewer — so the
 * i18n surface is byte-for-byte unchanged. Enumerates the registered route set via Javalin's
 * {@link InternalRouter} (the same technique as {@code LegacyEndpointGuardTest}).
 */
@DisplayName("MessageCatalogRoutes registers exactly the nine i18n catalog routes")
class MessageCatalogRoutesTest {

  @Test
  void registersExactlyTheNineHistoricalCatalogRoutes() {
    Javalin app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
    MessageCatalogRoutes.register(app, mock(Telemetry.class));

    InternalRouter router = app.unsafeConfig().pvt.internalRouter;
    Set<String> registered =
        router.allHttpHandlers().stream()
            .filter(pe -> pe.getEndpoint().getMethod().isHttpMethod())
            .map(pe -> pe.getEndpoint().getMethod() + " " + pe.getEndpoint().getPath())
            .collect(Collectors.toUnmodifiableSet());

    // The exact set bound inline before the Stage 1 extraction (commit history: 431 / 429 §E.17 /
    // 430 / 518 / 565 §27.4).
    Set<String> expected =
        Set.of(
            "GET /api/messages/errors/{locale}",
            "GET /api/messages/registry-operation/{locale}",
            "GET /api/messages/registry-resource/{locale}",
            "GET /api/messages/registry-prompt/{locale}",
            "GET /api/messages/registry-diagnostic/{locale}",
            "GET /api/messages/registry-surface/{locale}",
            "GET /api/messages/health-events/{locale}",
            "GET /api/messages/inference-failures/{locale}",
            "GET /api/messages/registry-workflow/{locale}");

    assertEquals(
        expected,
        registered,
        "MessageCatalogRoutes must bind exactly the nine historical /api/messages routes");
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RouteLifecycleHeadersTest {
  private Javalin app;

  @AfterEach
  void stop() {
    if (app != null) app.stop();
  }

  @Test
  void matchedRouteHeadersSurviveNormalAndExceptionMappedResponses() throws Exception {
    Instant deprecated = Instant.parse("2026-01-01T00:00:00Z");
    Instant sunset = Instant.parse("2026-05-01T00:00:00Z");
    RouteContractPolicy.Contract contract =
        new RouteContractPolicy.Contract(
            "GET",
            "/fake/{id}",
            RouteContractPolicy.Stability.REFERENCE_CLIENT,
            null,
            null,
            List.of(),
            Map.of(200, "runtime-live-response.v1.json"),
            ApiSecurityFilters.contractSecurity("GET", "/fake/{id}"),
            new RouteContractPolicy.Lifecycle(
                deprecated,
                sunset,
                "GET /replacement",
                URI.create("https://docs.justsearch.example/deprecations/fake")),
            null);

    app = Javalin.create(config -> config.showJavalinBanner = false);
    app.exception(IllegalStateException.class, (error, ctx) -> ctx.status(500).result("mapped"));
    AtomicReference<String> observedKey = new AtomicReference<>();
    RouteLifecycleHeaders.install(
        app,
        (method, path) -> {
          observedKey.set(method + " " + path);
          return contract;
        });
    app.get(
        "/fake/{id}",
        ctx -> {
          if ("fail".equals(ctx.pathParam("id"))) throw new IllegalStateException("boom");
          ctx.result("ok");
        });
    app.start("127.0.0.1", 0);

    HttpResponse<String> ok = send("ok");
    assertEquals(contract.key(), observedKey.get());
    assertLifecycle(ok, 200, deprecated);
    assertLifecycle(send("fail"), 500, deprecated);
  }

  private HttpResponse<String> send(String id) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + app.port() + "/fake/" + id))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static void assertLifecycle(
      HttpResponse<String> response, int expectedStatus, Instant deprecated) {
    assertEquals(expectedStatus, response.statusCode());
    assertEquals(
        "@" + deprecated.getEpochSecond(),
        response.headers().firstValue("Deprecation").orElse(null));
    assertEquals(
        "Fri, 1 May 2026 00:00:00 GMT",
        response.headers().firstValue("Sunset").orElse(null));
    assertEquals(
        "<https://docs.justsearch.example/deprecations/fake>; rel=\"deprecation\"",
        response.headers().firstValue("Link").orElse(null));
  }
}

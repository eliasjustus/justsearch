/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.javalin.Javalin;
import io.justsearch.app.services.ai.install.AiInstallService;
import io.justsearch.telemetry.Telemetry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 824 §3.5 (round-16 F4) — {@code POST /api/ai/install/repair} answers a body-less request
 * with a TYPED error body, on the wire.
 *
 * <p>Round 16 reported "400 with an empty body" and could not go further: the whole
 * {@code /api/ai/install/*} family was absent from {@code docs/reference/api-contract-map.md}, so a
 * caller had no documented shape to check against, and the single trace span (a 0.76 ms 400) was
 * consistent with both a real body-suppression bug and PowerShell 5.1's {@code Invoke-RestMethod}
 * discarding the response stream on a non-2xx. This test settles it by reading the raw bytes
 * instead of arguing: if it passes the observation was client-side and the doc entry is the fix; if
 * it ever fails, the regression home already exists.
 *
 * <p>It also pins the surprising half of the contract the doc now states: <b>repair IS start</b>
 * ({@code AiInstallService.repair} delegates straight to {@code startInstall}), so it requires
 * {@code acceptTerms} exactly like a first install — which is why an empty body is a 400 at all.
 */
final class AiInstallApiContractTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @TempDir Path aiHome;

  private Javalin app;

  @AfterEach
  void stopServer() {
    if (app != null) {
      app.stop();
      app = null;
    }
  }

  private HttpResponse<String> postRepair(String body) throws Exception {
    AiInstallController controller =
        new AiInstallController(
            new AiInstallService(null, null, null, null, aiHome), mock(Telemetry.class));
    app =
        Javalin.create(
                cfg -> {
                  cfg.showJavalinBanner = false;
                  cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
                })
            .post("/api/ai/install/repair", controller::handleRepair)
            .start(0);

    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/ai/install/repair"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("repair with {} ⇒ 400 carrying a NON-EMPTY typed TERMS_REQUIRED body")
  void repairWithoutTermsReturnsTypedBody() throws Exception {
    HttpResponse<String> resp = postRepair("{}");

    assertEquals(400, resp.statusCode());
    assertFalse(
        resp.body() == null || resp.body().isBlank(),
        "the round-16 observation was an EMPTY body — this is the assertion that settles it");
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("TERMS_REQUIRED", json.path("errorCode").asText());
    assertTrue(json.has("error"), "error message");
    assertTrue(json.has("errorClass"), "error class");
    assertTrue(json.has("retryable"), "retryable flag");
  }

  /** A completely absent body is the same contract — {@code parseAcceptTerms} tolerates it. */
  @Test
  @DisplayName("repair with an absent body ⇒ the same typed 400")
  void repairWithAbsentBodyReturnsTypedBody() throws Exception {
    HttpResponse<String> resp = postRepair("");

    assertEquals(400, resp.statusCode());
    assertFalse(resp.body().isBlank());
    assertEquals("TERMS_REQUIRED", MAPPER.readTree(resp.body()).path("errorCode").asText());
  }
}

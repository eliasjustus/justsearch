package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UI-ready handshake endpoint contract")
class UiReadyControllerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private HttpClient client;
  private Javalin app;
  private int port;

  @BeforeEach
  void setup() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
  }

  private void startServer() {
    EventBuffer eventBuffer = new EventBuffer();
    UiReadyController controller = new UiReadyController(eventBuffer, null);
    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); });
    app.get("/api/ui/ready", controller::handleGet);
    app.post("/api/ui/ready", controller::handlePost);
    app.start("127.0.0.1", 0);
    port = app.port();
  }

  @Test
  @DisplayName("GET returns ready=false before any handshake is recorded")
  void getBeforeHandshake() throws Exception {
    startServer();

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ui/ready"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    Map<?, ?> json = MAPPER.readValue(resp.body(), Map.class);
    assertEquals(false, json.get("ready"));
  }

  @Test
  @DisplayName("POST records handshake + backend-observed Origin/User-Agent and GET echoes it")
  void postThenGet() throws Exception {
    startServer();

    String body =
        """
        {
          "schema": "UI_READY_HANDSHAKE_V1",
          "runId": "run-123",
          "runtime": "tauri",
          "apiSource": "tauri",
          "uiConnectedAtMs": 1735260000000,
          "meta": { "build": "test" }
        }
        """;

    HttpResponse<String> post =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ui/ready"))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .header("Origin", "https://tauri.localhost")
                .header("User-Agent", "UiReadyControllerTest/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, post.statusCode());
    Map<?, ?> postJson = MAPPER.readValue(post.body(), Map.class);
    assertEquals(true, postJson.get("ok"));
    assertEquals("https://tauri.localhost", postJson.get("originHeader"));
    assertEquals("UiReadyControllerTest/1.0", postJson.get("userAgent"));

    HttpResponse<String> get =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ui/ready"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, get.statusCode());
    Map<?, ?> getJson = MAPPER.readValue(get.body(), Map.class);
    assertEquals(true, getJson.get("ready"));
    assertEquals("https://tauri.localhost", getJson.get("originHeader"));
    assertEquals("UiReadyControllerTest/1.0", getJson.get("userAgent"));
    assertNotNull(getJson.get("receivedAtEpochMs"));
    assertNotNull(getJson.get("handshake"));
  }

  @Test
  @DisplayName("POST rejects invalid schema with 400")
  void postRejectsInvalidSchema() throws Exception {
    startServer();

    String body = """
      { "schema": "NOPE" }
      """;

    HttpResponse<String> post =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ui/ready"))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(400, post.statusCode());
    Map<?, ?> json = MAPPER.readValue(post.body(), Map.class);
    assertEquals("INVALID_SCHEMA", json.get("errorCode"));
  }
}

package io.justsearch.app.observability;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.justsearch.infra.health.InfraHealthAggregator;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InfraHealthControllerTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void returnsDiagnosticsSnapshot() throws Exception {
    InfraDiagnosticsService diagnostics = new InfraDiagnosticsService(config());
    diagnostics.setNrtLagSupplier(() -> 5L);
    diagnostics.setTranslatorHandshakeSupplier(() -> Instant.now());
    diagnostics.setAnnReadySupplier(() -> 80);
    diagnostics.setMetadataSupplier(() -> Map.of("build", "test"));

    InfraHealthController controller = new InfraHealthController(diagnostics);

    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    try {
      server.createContext("/infra/health", controller);
      server.start();

      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + server.getAddress().getPort() + "/infra/health"))
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      JsonNode node = JSON.readTree(response.body());
      assertEquals("healthy", node.get("status").asText());
      assertTrue(node.get("components").isArray());
      assertEquals("application/json; charset=utf-8", response.headers().firstValue("content-type").orElse(null));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void returns405ForUnsupportedMethod() throws Exception {
    InfraHealthController controller = new InfraHealthController(new InfraDiagnosticsService(config()));

    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    try {
      server.createContext("/infra/health", controller);
      server.start();

      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + server.getAddress().getPort() + "/infra/health"))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(405, response.statusCode());
      assertEquals("Method Not Allowed", response.body());
    } finally {
      server.stop(0);
    }
  }

  private static InfraHealthAggregator.Config config() {
    return new InfraHealthAggregator.Config(
        Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20), 50);
  }
}

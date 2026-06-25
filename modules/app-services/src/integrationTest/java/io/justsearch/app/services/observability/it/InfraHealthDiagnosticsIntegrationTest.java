package io.justsearch.app.observability.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.config.ConfigManagerBootstrap;
import io.justsearch.app.observability.InfraDiagnosticsService;
import io.justsearch.app.observability.InfraHealthBootstrap;
import io.justsearch.app.observability.InfraHealthController;
import io.justsearch.app.util.RepoPaths;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/** Integration coverage for the infra health diagnostics endpoint and evidence capture. */
final class InfraHealthDiagnosticsIntegrationTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void capturesInfraHealthPayloads() throws Exception {
    ConfigManagerBootstrap configManager = new ConfigManagerBootstrap();
    InfraDiagnosticsService diagnostics =
        new InfraDiagnosticsService(new io.justsearch.infra.health.InfraHealthAggregator.Config(
            java.time.Duration.ofMillis(5000),
            java.time.Duration.ofMillis(30000),
            java.time.Duration.ofMillis(120000),
            75));
    diagnostics.setMetadataSupplier(
        () ->
            Map.of(
                "config_loaded_at", configManager.currentSnapshot().loadedAt().toString(),
                "snapshot_sequence", "1"));
    InfraHealthBootstrap bootstrap = new InfraHealthBootstrap(diagnostics);
    bootstrap.bindConfigManager(configManager);

    AtomicLong nrtLagMs = new AtomicLong(12_000L);
    AtomicReference<Instant> handshakeRef = new AtomicReference<>(Instant.now());
    AtomicInteger annReadyPercent = new AtomicInteger(95);
    AtomicBoolean configValid = new AtomicBoolean(true);

    diagnostics.setNrtLagSupplier(nrtLagMs::get);
    diagnostics.setTranslatorHandshakeSupplier(handshakeRef::get);
    diagnostics.setAnnReadySupplier(annReadyPercent::get);
    diagnostics.setConfigValidSupplier(configValid::get);

    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/infra/health", new InfraHealthController(diagnostics));
    server.start();

    List<JsonNode> snapshots = new ArrayList<>();
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/infra/health");

    try {
      JsonNode healthy = fetchSnapshot(client, uri);
      assertEquals("healthy", healthy.path("status").asText());
      snapshots.add(healthy);

      configValid.set(false);
      JsonNode degraded = fetchSnapshot(client, uri);
      assertEquals("degraded", degraded.path("status").asText());
      assertTrue(degraded.path("components").isArray());
      snapshots.add(degraded);

      configValid.set(true);
      nrtLagMs.set(90_000L);
      handshakeRef.set(Instant.now().minus(Duration.ofMinutes(10)));
      annReadyPercent.set(0);
      JsonNode critical = fetchSnapshot(client, uri);
      assertEquals("critical", critical.path("status").asText());
      assertNotNull(
          streamComponents(critical)
              .filter(component -> "translator".equals(component.path("componentId").asText()))
              .findFirst()
              .orElse(null));
      snapshots.add(critical);
    } finally {
      server.stop(0);
    }

    writeEvidence(snapshots);
    writeLog(snapshots);
  }

  private static JsonNode fetchSnapshot(HttpClient client, URI uri) throws IOException, InterruptedException {
    HttpResponse<String> response =
        client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
    return JSON.readTree(response.body());
  }

  private static void writeEvidence(List<JsonNode> snapshots) throws IOException {
    Path evidenceDir = RepoPaths.findRepoRoot().resolve("reports/phase7/health");
    Files.createDirectories(evidenceDir);
    ArrayNode array = JSON.createArrayNode();
    for (JsonNode snapshot : snapshots) {
      array.add(snapshot);
    }
    ObjectNode payload = JSON.createObjectNode();
    payload.set("snapshots", array);
    Files.writeString(
        evidenceDir.resolve("health-response.json"),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static void writeLog(List<JsonNode> snapshots) throws IOException {
    Path evidenceDir = RepoPaths.findRepoRoot().resolve("reports/phase7/health");
    Files.createDirectories(evidenceDir);
    Path log = evidenceDir.resolve("health-history.log");
    StringBuilder builder = new StringBuilder();
    for (JsonNode snapshot : snapshots) {
      builder
          .append(snapshot.path("generatedAt").asText())
          .append(" status=")
          .append(snapshot.path("status").asText())
          .append(" components=")
          .append(snapshot.path("components").size())
          .append(System.lineSeparator());
    }
    Files.writeString(
        log,
        builder.toString(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static java.util.stream.Stream<JsonNode> streamComponents(JsonNode snapshot) {
    return snapshot.path("components").isArray()
        ? StreamSupport.stream(snapshot.path("components").spliterator(), false)
        : java.util.stream.Stream.empty();
  }
}

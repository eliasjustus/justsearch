package io.justsearch.app.observability.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import com.google.protobuf.Empty;
import com.google.protobuf.util.JsonFormat;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.justsearch.app.config.ConfigManagerBootstrap;
import io.justsearch.app.observability.InfraDiagnosticsService;
import io.justsearch.app.observability.InfraHealthBootstrap;
import io.justsearch.app.observability.InfraHealthGrpcService;
import io.justsearch.app.util.RepoPaths;
import io.justsearch.ipc.v1.InfraDiagnosticsServiceGrpc;
import io.justsearch.ipc.v1.InfraHealthSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Verifies the gRPC diagnostics service mirrors the JSON diagnostics payload. */
final class InfraHealthGrpcServiceIntegrationTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void currentSnapshotReflectsDiagnosticsState() throws Exception {
    ConfigManagerBootstrap configManager = new ConfigManagerBootstrap();
    InfraDiagnosticsService diagnostics =
        new InfraDiagnosticsService(new io.justsearch.infra.health.InfraHealthAggregator.Config(
            java.time.Duration.ofMillis(5000),
            java.time.Duration.ofMillis(30000),
            java.time.Duration.ofMillis(120000),
            75));
    InfraHealthBootstrap bootstrap = new InfraHealthBootstrap(diagnostics);
    bootstrap.bindConfigManager(configManager);

    AtomicLong nrtLagMs = new AtomicLong(8_000L);
    AtomicReference<Instant> handshake = new AtomicReference<>(Instant.now());
    AtomicInteger annReadyPercent = new AtomicInteger(88);
    AtomicBoolean configValid = new AtomicBoolean(true);
    diagnostics.setMetadataSupplier(
        () ->
            Map.of(
                "config_loaded_at", configManager.currentSnapshot().loadedAt().toString(),
                "deployment", "integration-test"));
    diagnostics.setNrtLagSupplier(nrtLagMs::get);
    diagnostics.setTranslatorHandshakeSupplier(handshake::get);
    diagnostics.setAnnReadySupplier(annReadyPercent::get);
    diagnostics.setConfigValidSupplier(configValid::get);

    String serverName = InProcessServerBuilder.generateName();
    var server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(new InfraHealthGrpcService(diagnostics))
            .build()
            .start();
    try {
      InfraDiagnosticsServiceGrpc.InfraDiagnosticsServiceBlockingStub stub =
          InfraDiagnosticsServiceGrpc.newBlockingStub(
              InProcessChannelBuilder.forName(serverName).directExecutor().build());

      InfraHealthSnapshot healthy = stub.currentSnapshot(Empty.getDefaultInstance());
      assertEquals("healthy", healthy.getStatus());
      assertEquals(3, healthy.getComponentsCount());
      assertTrue(healthy.getMetadataMap().containsKey("deployment"));

      configValid.set(false);
      annReadyPercent.set(40);
      nrtLagMs.set(120_000L);
      InfraHealthSnapshot degraded = stub.currentSnapshot(Empty.getDefaultInstance());
      assertEquals("critical", degraded.getStatus());
      assertTrue(
          degraded.getComponentsList().stream()
              .anyMatch(component -> "ann_cache".equals(component.getComponentId())));
      writeEvidence(healthy, degraded);
    } finally {
      server.shutdownNow();
    }
  }

  private static void writeEvidence(InfraHealthSnapshot healthy, InfraHealthSnapshot degraded)
      throws Exception {
    ArrayNode array = JSON.createArrayNode();
    array.add(toJson(healthy));
    array.add(toJson(degraded));
    Path dir = RepoPaths.findRepoRoot().resolve("reports/phase7/health");
    Files.createDirectories(dir);
    Path output = dir.resolve("health-grpc-response.json");
    JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), array);
  }

  private static JsonNode toJson(InfraHealthSnapshot snapshot) throws Exception {
    String json = JsonFormat.printer().includingDefaultValueFields().print(snapshot);
    return JSON.readTree(json);
  }
}

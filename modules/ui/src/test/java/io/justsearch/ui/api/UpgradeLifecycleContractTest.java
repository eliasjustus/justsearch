package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.services.lease.OperationLeaseServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

final class UpgradeLifecycleContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void prepareFreezesMutationsKeepsReadsAndCommitRequestsOrderlyShutdown(@TempDir Path tmp)
      throws Exception {
    var leases = new OperationLeaseServiceImpl();
    var shutdown = new CountDownLatch(1);
    LocalApiServer server =
        LocalApiServer.builder(
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                tmp.resolve("index"))
            .operationLeaseService(leases)
            .upgradeShutdownAction(shutdown::countDown)
            .build();
    try {
      HttpClient client =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
      HttpResponse<String> prepared = post(client, server, "/api/upgrade/prepare", "{}");
      assertEquals(200, prepared.statusCode());
      String preparationId = JSON.readTree(prepared.body()).get("preparationId").asText();
      assertTrue(!preparationId.isBlank());

      HttpResponse<String> rejected =
          post(client, server, "/api/settings/v2", "{\"ui\":{}}");
      assertEquals(503, rejected.statusCode());
      assertTrue(rejected.body().contains("UPGRADE_PREPARING"));

      HttpResponse<String> status =
          client.send(
              HttpRequest.newBuilder(uri(server, "/api/status"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, status.statusCode());

      HttpResponse<String> committed =
          post(
              client,
              server,
              "/api/upgrade/commit-shutdown",
              "{\"preparationId\":\"" + preparationId + "\"}");
      assertEquals(200, committed.statusCode());
      assertTrue(shutdown.await(2, TimeUnit.SECONDS));
    } finally {
      server.stop();
    }
  }

  @Test
  void mustCompleteLeaseBlocksShutdownUntilReleased(@TempDir Path tmp) throws Exception {
    var leases = new OperationLeaseServiceImpl();
    var active =
        leases.register("indexing.migration", OpCriticality.MUST_COMPLETE, 60, Map.of());
    LocalApiServer server =
        LocalApiServer.builder(
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                tmp.resolve("index"))
            .operationLeaseService(leases)
            .build();
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpResponse<String> prepared = post(client, server, "/api/upgrade/prepare", "{}");
      String preparationId = JSON.readTree(prepared.body()).get("preparationId").asText();

      HttpResponse<String> blocked =
          post(
              client,
              server,
              "/api/upgrade/commit-shutdown",
              "{\"preparationId\":\"" + preparationId + "\"}");
      assertEquals(409, blocked.statusCode());
      assertTrue(blocked.body().contains("indexing.migration"));

      active.close();
      HttpResponse<String> accepted =
          post(
              client,
              server,
              "/api/upgrade/commit-shutdown",
              "{\"preparationId\":\"" + preparationId + "\"}");
      assertEquals(200, accepted.statusCode());
    } finally {
      server.stop();
    }
  }

  @Test
  void interruptibleLeaseAlsoBlocksUntilOwnerAcknowledgesRelease(@TempDir Path tmp)
      throws Exception {
    var leases = new OperationLeaseServiceImpl();
    var active =
        leases.register("agent.answer", OpCriticality.INTERRUPTIBLE, 60, Map.of());
    LocalApiServer server =
        LocalApiServer.builder(
                new UiSettingsStore(UiSettingsStore.PersistenceMode.IN_MEMORY),
                tmp.resolve("index"))
            .operationLeaseService(leases)
            .build();
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpResponse<String> prepared = post(client, server, "/api/upgrade/prepare", "{}");
      String preparationId = JSON.readTree(prepared.body()).get("preparationId").asText();
      assertEquals(false, JSON.readTree(prepared.body()).get("ready").asBoolean());

      HttpResponse<String> blocked =
          post(
              client,
              server,
              "/api/upgrade/commit-shutdown",
              "{\"preparationId\":\"" + preparationId + "\"}");
      assertEquals(409, blocked.statusCode());
      assertTrue(blocked.body().contains("agent.answer"));

      active.close();
      assertEquals(
          200,
          post(
                  client,
                  server,
                  "/api/upgrade/commit-shutdown",
                  "{\"preparationId\":\"" + preparationId + "\"}")
              .statusCode());
    } finally {
      server.stop();
    }
  }

  private static HttpResponse<String> post(
      HttpClient client, LocalApiServer server, String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(uri(server, path))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static URI uri(LocalApiServer server, String path) {
    return URI.create("http://127.0.0.1:" + server.getPort() + path);
  }
}

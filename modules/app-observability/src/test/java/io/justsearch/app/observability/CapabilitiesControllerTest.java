package io.justsearch.app.observability;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CapabilitiesControllerTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void rendersCapabilitiesAsJson() throws Exception {
    CapabilitiesService service = realService();
    CapabilitiesService.CapabilitiesView expected = service.capabilities();
    CapabilitiesController controller = new CapabilitiesController(service);

    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    try {
      server.createContext("/infra/capabilities", controller);
      server.start();

      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + server.getAddress().getPort() + "/infra/capabilities"))
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      JsonNode node = JSON.readTree(response.body());
      assertNotNull(node.get("schema_versions"));
      assertEquals("application/json; charset=utf-8", response.headers().firstValue("content-type").orElse(null));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void returns405ForNonGet() throws Exception {
    CapabilitiesController controller =
        new CapabilitiesController(realService());

    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    try {
      server.createContext("/infra/capabilities", controller);
      server.start();

      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + server.getAddress().getPort() + "/infra/capabilities"))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(405, response.statusCode());
      assertEquals("Method Not Allowed", response.body());
    } finally {
      server.stop(0);
    }
  }

  private CapabilitiesService realService() throws Exception {
    Path repo = Files.createTempDirectory("repo");
    Files.createDirectories(repo.resolve("SSOT/prompts"));

    Path prompt = repo.resolve("SSOT/prompts/task-a.v1.json");
    Files.writeString(prompt, "{ \"task_id\": \"task-a\", \"template\": \"text\" }");

    return new CapabilitiesService(repo);
  }
}

package io.justsearch.app.observability.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.observability.CapabilitiesController;
import io.justsearch.app.observability.CapabilitiesService;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 429 verification gate item 8 (soft-fail discipline): a request that declares an
 * unknown client capability (via query parameter, since the controller is GET-only) MUST
 * NOT error and MUST return the exact same {@code serverCapabilities} as a baseline
 * request without any client capability declarations. This mirrors LSP 3.17's
 * {@code dynamicRegistration} discipline — unknown capabilities are silently ignored,
 * never rejected.
 */
@DisplayName("Capabilities handshake soft-fail discipline")
final class CapabilitiesSoftFailTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  @DisplayName("unknown client capability declarations are ignored, response unchanged")
  void unknownClientCapabilityIsIgnored() throws Exception {
    CapabilitiesService service = new CapabilitiesService();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/infra/capabilities", new CapabilitiesController(service));
    server.start();
    try {
      URI base =
          URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/infra/capabilities");
      URI withUnknown =
          URI.create(
              "http://127.0.0.1:"
                  + server.getAddress().getPort()
                  + "/infra/capabilities?clientCapability=jus.unknown.capability&jus_unknown=true");

      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

      HttpResponse<String> baseResponse =
          client.send(HttpRequest.newBuilder(base).GET().build(), HttpResponse.BodyHandlers.ofString());
      HttpResponse<String> unknownResponse =
          client.send(
              HttpRequest.newBuilder(withUnknown).GET().build(),
              HttpResponse.BodyHandlers.ofString());

      assertEquals(
          HttpURLConnection.HTTP_OK,
          baseResponse.statusCode(),
          "Baseline GET must succeed");
      assertEquals(
          HttpURLConnection.HTTP_OK,
          unknownResponse.statusCode(),
          "Unknown-capability GET must succeed (LSP soft-fail discipline — unknown capabilities are ignored)");

      JsonNode baseRoot = JSON.readTree(baseResponse.body());
      JsonNode unknownRoot = JSON.readTree(unknownResponse.body());

      JsonNode baseSc = baseRoot.path("serverCapabilities");
      JsonNode unknownSc = unknownRoot.path("serverCapabilities");
      assertTrue(baseSc.isObject(), "Baseline response must declare serverCapabilities");
      assertTrue(unknownSc.isObject(), "Unknown-capability response must declare serverCapabilities");

      // Per tempdoc 429 §E.2 + verification gate item 8: serverCapabilities must be
      // byte-identical between the baseline and unknown-capability requests. The backend
      // does not advertise extra capabilities just because the client mentioned them, and
      // does not error on unknown declarations.
      assertEquals(
          baseSc,
          unknownSc,
          "serverCapabilities must be unchanged when the client declares unknown capabilities. "
              + "Per tempdoc 429 §E.2: LSP soft-fail discipline — unknown declarations ignored.");

      // catalogVersion is a long value that increments on broadcast — between two
      // back-to-back GETs (no broadcast in between), it must be equal.
      assertEquals(
          baseSc.path("catalogVersion").asLong(),
          unknownSc.path("catalogVersion").asLong(),
          "catalogVersion must be stable across back-to-back requests with no intervening broadcast.");
    } finally {
      server.stop(0);
    }
  }
}

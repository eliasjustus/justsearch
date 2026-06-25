/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Lightweight HTTP handler that exposes `/infra/capabilities` as JSON.
 *
 * <p>The controller is framework-agnostic so it can be registered against any {@link
 * com.sun.net.httpserver.HttpServer} instance used by launchers or tests.
 */
public final class CapabilitiesController implements HttpHandler {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final CapabilitiesService service;

  public CapabilitiesController(CapabilitiesService service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendStatus(exchange, 405, "Method Not Allowed");
        return;
      }
      CapabilitiesService.CapabilitiesView view = service.capabilities();
      byte[] payload = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(view);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(200, payload.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(payload);
      }
    }
  }

  private static void sendStatus(HttpExchange exchange, int status, String message) throws IOException {
    byte[] body = message.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(body);
    }
  }
}

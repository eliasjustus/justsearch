/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** HTTP controller exposing the `/infra/health` diagnostics endpoint. */
public final class InfraHealthController implements HttpHandler {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final InfraDiagnosticsService diagnostics;

  public InfraHealthController(InfraDiagnosticsService diagnostics) {
    this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        send(exchange, 405, "Method Not Allowed");
        return;
      }
      byte[] payload = JSON.writerWithDefaultPrettyPrinter()
          .writeValueAsBytes(diagnostics.currentPayload());
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(200, payload.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(payload);
      }
    }
  }

  private static void send(HttpExchange exchange, int status, String message) throws IOException {
    byte[] body = message.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(body);
    }
  }
}

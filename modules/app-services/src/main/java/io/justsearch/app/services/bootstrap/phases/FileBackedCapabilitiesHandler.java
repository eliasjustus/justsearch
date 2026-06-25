/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Tempdoc 519 §10 final-push: extracted from {@code HeadAssembly}. Test fixture path that
 * serves a static JSON file at {@code /infra/capabilities}. Used when the test sysprop
 * {@code app.api.fake_capabilities} or env var {@code APP_API_FAKE_CAPABILITIES} is set.
 */
public final class FileBackedCapabilitiesHandler implements HttpHandler {

  private final Path source;

  public FileBackedCapabilitiesHandler(Path source) {
    this.source = Objects.requireNonNull(source, "source");
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendStatus(exchange, 405, "Method Not Allowed");
        return;
      }
      if (!Files.exists(source)) {
        sendStatus(exchange, 503, "Capabilities fixture not found: " + source);
        return;
      }
      byte[] payload = Files.readAllBytes(source);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(200, payload.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(payload);
      }
    }
  }

  private void sendStatus(HttpExchange exchange, int status, String message) throws IOException {
    byte[] body = message.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(body);
    }
  }
}

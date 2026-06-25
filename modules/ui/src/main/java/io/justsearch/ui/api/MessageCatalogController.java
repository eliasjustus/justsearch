/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.telemetry.Telemetry;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generalized message catalog HTTP handler.
 *
 * <p>Per tempdoc 429 §E.8.b + §E.17: refactor of slice 1.1.d's
 * {@code ErrorCatalogController} from a hardcoded-namespace controller into a
 * parameterized one. One instance per namespace; {@link io.justsearch.ui.api.LocalApiServer}
 * constructs four instances (errors + 3 registry primitives) and binds matching routes.
 *
 * <p>Endpoint pattern: {@code GET /api/messages/{namespace}/{locale}}. V1 ships
 * {@code en} only; non-en locales return 404 with a hint that translation is V1.5+
 * work per tempdoc 434 §"What it does NOT support".
 *
 * <p>Response envelope (per tempdoc 434 §"Catalog file shape"):
 *
 * <pre>{@code
 * {
 *   "$schema": "https://ssot.justsearch/v1/schemas/i18n-catalog.json",
 *   "schemaVersion": "1.0",
 *   "locale": "en",
 *   "namespace": "<this controller's namespace>",
 *   "messages": { "<key>": "<message>", ... }
 * }
 * }</pre>
 *
 * <p>HTTP caching (per tempdoc 434 §4): the response carries
 * {@code Cache-Control: public, max-age=3600} and a strong {@code ETag} computed once
 * at construction from a SHA-256 of the JSON body. Conditional GETs that match the
 * ETag receive a 304 Not Modified.
 */
public final class MessageCatalogController {

  private static final Logger log = LoggerFactory.getLogger(MessageCatalogController.class);

  private static final String SCHEMA_URI = "https://ssot.justsearch/v1/schemas/i18n-catalog.json";
  private static final String SCHEMA_VERSION = "1.0";
  private static final String LOCALE = "en";
  private static final String CACHE_CONTROL = "public, max-age=3600";

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  private final String namespace;
  private final String resourcePath;
  private final Telemetry telemetry;

  private final byte[] cachedJsonBytes;
  private final String etag;

  /**
   * Eager constructor: loads the properties at startup so a missing-resource error
   * surfaces at boot rather than at first request.
   *
   * @param namespace catalog namespace (e.g., {@code "errors"}, {@code "registry-operation"})
   * @param resourcePath classpath resource path (e.g., {@code "/messages/errors.en.properties"})
   * @param telemetry telemetry handle for error reporting
   */
  public MessageCatalogController(String namespace, String resourcePath, Telemetry telemetry) {
    this.namespace = Objects.requireNonNull(namespace, "namespace");
    this.resourcePath = Objects.requireNonNull(resourcePath, "resourcePath");
    this.telemetry = telemetry;
    this.cachedJsonBytes = buildCatalogJson();
    this.etag = computeEtag(this.cachedJsonBytes);
    log.info(
        "MessageCatalogController[{}] loaded: {} messages from {} (etag={})",
        namespace,
        loadProperties().size(),
        resourcePath,
        etag);
  }

  public void handle(Context ctx) {
    String locale = ctx.pathParam("locale");
    if (!LOCALE.equalsIgnoreCase(locale)) {
      ctx.status(404)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.NOT_FOUND,
                  "Locale '" + locale + "' is not available; V1 ships en-only "
                      + "(non-en catalogs are V1.5+ work per tempdoc 434).",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
      return;
    }

    String ifNoneMatch = ctx.header("If-None-Match");
    if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
      ctx.header("ETag", etag);
      ctx.header("Cache-Control", CACHE_CONTROL);
      ctx.status(304);
      return;
    }

    ctx.header("ETag", etag);
    ctx.header("Cache-Control", CACHE_CONTROL);
    ctx.contentType("application/json").result(cachedJsonBytes);
  }

  private byte[] buildCatalogJson() {
    Properties props = loadProperties();
    Map<String, String> messages = new TreeMap<>();
    for (String name : props.stringPropertyNames()) {
      messages.put(name, props.getProperty(name));
    }
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("$schema", SCHEMA_URI);
    envelope.put("schemaVersion", SCHEMA_VERSION);
    envelope.put("locale", LOCALE);
    envelope.put("namespace", namespace);
    envelope.put("messages", messages);
    try {
      return MAPPER.writeValueAsBytes(envelope);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize message catalog JSON", e);
    }
  }

  private Properties loadProperties() {
    Properties props = new Properties();
    try (InputStream is = MessageCatalogController.class.getResourceAsStream(resourcePath);
        InputStreamReader reader =
            new InputStreamReader(
                Objects.requireNonNull(is, "Resource not found: " + resourcePath),
                StandardCharsets.UTF_8)) {
      props.load(reader);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to load message catalog resource " + resourcePath, e);
    }
    return props;
  }

  private static String computeEtag(byte[] body) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(body);
      StringBuilder sb = new StringBuilder(digest.length * 2 + 2);
      sb.append('"');
      for (byte b : digest) {
        sb.append(Character.forDigit((b >> 4) & 0xf, 16));
        sb.append(Character.forDigit(b & 0xf, 16));
      }
      sb.append('"');
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability;

import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.ErrorClass;
import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Tag schemas for {@code api.*} metrics emitted by the Head HTTP layer.
 *
 * <ul>
 *   <li>{@link ApiRequestTags} — for {@code api.request_ms} (route + method + status + class)
 *   <li>{@link ApiStreamTags} — for {@code api.stream.ttft_ms} (route + method + transport)
 *   <li>{@link ApiErrorTags} — for {@code api.error.total} (code + class + route)
 * </ul>
 *
 * <p>{@code route} is a bounded {@code String} (cardinality limited at the View layer); the
 * codebase has ~10–20 distinct routes today.
 *
 * <p>Tempdoc 417 Phase 2c (relocated from {@code modules/ui} in F1 to satisfy the
 * LayeringEnforcementTest rule that prevents {@code applauncher → ui} imports).
 */
public final class HeadApiTags {

  private HeadApiTags() {}

  static final String KEY_ROUTE = "route";
  static final String KEY_HTTP_METHOD = "http_method";
  static final String KEY_HTTP_STATUS = "http_status";
  static final String KEY_HTTP_STATUS_CLASS = "http_status_class";
  static final String KEY_STREAM_TRANSPORT = "stream_transport";
  static final String KEY_ERROR_CODE = "error_code";
  static final String KEY_ERROR_CLASS = "error_class";

  static final Set<String> REQUEST_KEYS;
  static final Set<String> STREAM_KEYS;
  static final Set<String> ERROR_KEYS;

  static {
    Set<String> rk = new LinkedHashSet<>();
    rk.add(KEY_ROUTE);
    rk.add(KEY_HTTP_METHOD);
    rk.add(KEY_HTTP_STATUS);
    rk.add(KEY_HTTP_STATUS_CLASS);
    REQUEST_KEYS = rk;

    Set<String> sk = new LinkedHashSet<>();
    sk.add(KEY_ROUTE);
    sk.add(KEY_HTTP_METHOD);
    sk.add(KEY_STREAM_TRANSPORT);
    STREAM_KEYS = sk;

    Set<String> ek = new LinkedHashSet<>();
    ek.add(KEY_ERROR_CODE);
    ek.add(KEY_ERROR_CLASS);
    ek.add(KEY_ROUTE);
    ERROR_KEYS = ek;
  }

  /** Tag schema for {@code api.request_ms}. */
  public record ApiRequestTags(
      String route, HttpMethod method, String httpStatus, HttpStatusClass statusClass)
      implements TagSchema {

    public ApiRequestTags {
      Objects.requireNonNull(route, "route");
      Objects.requireNonNull(method, "method");
      Objects.requireNonNull(httpStatus, "httpStatus");
      Objects.requireNonNull(statusClass, "statusClass");
    }

    @Override
    public Set<String> allowedKeys() {
      return REQUEST_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.builder()
          .put(AttributeKey.stringKey(KEY_ROUTE), route)
          .put(AttributeKey.stringKey(KEY_HTTP_METHOD), method.wireValue())
          .put(AttributeKey.stringKey(KEY_HTTP_STATUS), httpStatus)
          .put(AttributeKey.stringKey(KEY_HTTP_STATUS_CLASS), statusClass.wireValue())
          .build();
    }
  }

  /** Tag schema for {@code api.stream.ttft_ms}. */
  public record ApiStreamTags(String route, HttpMethod method, StreamTransport transport)
      implements TagSchema {

    public ApiStreamTags {
      Objects.requireNonNull(route, "route");
      Objects.requireNonNull(method, "method");
      Objects.requireNonNull(transport, "transport");
    }

    @Override
    public Set<String> allowedKeys() {
      return STREAM_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.builder()
          .put(AttributeKey.stringKey(KEY_ROUTE), route)
          .put(AttributeKey.stringKey(KEY_HTTP_METHOD), method.wireValue())
          .put(AttributeKey.stringKey(KEY_STREAM_TRANSPORT), transport.wireValue())
          .build();
    }
  }

  /** Tag schema for {@code api.error.total}. Reuses existing {@link ApiErrorCode} enum. */
  public record ApiErrorTags(ApiErrorCode code, ErrorClass klass, String route)
      implements TagSchema {

    public ApiErrorTags {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(klass, "klass");
      Objects.requireNonNull(route, "route");
    }

    @Override
    public Set<String> allowedKeys() {
      return ERROR_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.builder()
          .put(AttributeKey.stringKey(KEY_ERROR_CODE), code.name())
          .put(AttributeKey.stringKey(KEY_ERROR_CLASS), klass.name())
          .put(AttributeKey.stringKey(KEY_ROUTE), route)
          .build();
    }
  }
}

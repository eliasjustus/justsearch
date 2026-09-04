/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.BiFunction;

/** Emits lifecycle response headers from the route-pattern contract authority. */
final class RouteLifecycleHeaders {
  private static final DateTimeFormatter IMF_FIXDATE =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
          .withZone(ZoneOffset.UTC);

  private RouteLifecycleHeaders() {}

  static void install(Javalin app) {
    install(app, RouteContractPolicy::forRoute);
  }

  static void install(
      Javalin app,
      BiFunction<String, String, RouteContractPolicy.Contract> contractLookup) {
    app.after(
        ctx -> {
          // endpointHandlerPath() is null for an unmatched request and retains the concrete
          // endpoint pattern (for example /x/{id}) for normal and exception-mapped responses.
          String matchedPath = ctx.endpointHandlerPath();
          if (matchedPath == null || matchedPath.isBlank() || "*".equals(matchedPath)) return;
          RouteContractPolicy.Contract contract =
              contractLookup.apply(ctx.method().name(), matchedPath);
          if (contract == null || contract.lifecycle() == null) return;

          RouteContractPolicy.Lifecycle lifecycle = contract.lifecycle();
          ctx.header("Deprecation", "@" + lifecycle.deprecatedSince().getEpochSecond());
          if (lifecycle.sunsetAt() != null) {
            ctx.header("Sunset", IMF_FIXDATE.format(lifecycle.sunsetAt()));
          }
          ctx.res()
              .addHeader(
                  "Link", "<" + lifecycle.documentationUri() + ">; rel=\"deprecation\"");
        });
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import java.util.Locale;

/**
 * Tempdoc 875 open item — declare the charset of every JSON response.
 *
 * <p><b>The defect.</b> {@code POST /api/chat/agent/undo} returned the undo summary's em-dash as
 * {@code â€"} while the SSE plane was clean. The bytes were never wrong: {@code SseWriter} declares
 * {@code text/event-stream; charset=utf-8}, but Javalin's {@code ctx.json(...)} declares bare
 * {@code application/json} (its {@code ContentType.APPLICATION_JSON} carries no parameter), and so
 * does every hand-rolled {@code contentType("application/json")} in this package. A client that
 * honours the response charset — and defaults to ISO-8859-1 when the parameter is absent, as HTTP
 * has always allowed — decodes UTF-8 bytes as Latin-1, which is exactly the {@code â€"} shape.
 *
 * <p><b>Why an after-handler and not a per-route edit.</b> The fault is not the undo route's; it is
 * every JSON route's, because they share one emitter. Patching the reported route would have left
 * ~200 siblings mojibaking and made the next report look like a new bug. This is the one place the
 * whole plane passes through. It only ADDS the parameter to a declaration that has none — a
 * response that already declares a charset (SSE, downloads, anything explicit) is untouched, and so
 * is a non-JSON response.
 *
 * <p>Scope check that keeps this honest: the label is only half the contract, so the regression test
 * asserts the BYTES too. Javalin's {@code Context.result(String)} encodes with
 * {@code responseCharset()} at call time — if that ever stopped resolving to UTF-8 the em-dash would
 * be written as {@code ?} and relabelling would make it worse, not better, which is precisely what
 * {@code AgentSessionControllerUndoCharsetTest} fails on.
 */
final class JsonResponseCharset {

  private static final String JSON = "application/json";
  private static final String JSON_UTF8 = "application/json; charset=utf-8";

  private JsonResponseCharset() {}

  /**
   * Register the after-handler that adds {@code charset=utf-8} to a bare {@code application/json}
   * declaration, on every route this {@code app} serves.
   */
  static void install(Javalin app) {
    app.after(JsonResponseCharset::declareUtf8);
  }

  /** Visible for the after-handler and for direct testing of the decision. */
  static void declareUtf8(Context ctx) {
    String declared = ctx.res().getContentType();
    if (declared == null) {
      return;
    }
    String lower = declared.toLowerCase(Locale.ROOT);
    if (lower.startsWith(JSON) && !lower.contains("charset=")) {
      ctx.res().setContentType(JSON_UTF8);
    }
  }
}

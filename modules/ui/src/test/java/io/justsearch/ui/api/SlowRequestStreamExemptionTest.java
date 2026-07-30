package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.javalin.Javalin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sandbox round 7 — a long-lived SSE stream is not a slow handler.
 *
 * <p>{@link ApiSecurityFilters#maybeCaptureSlowRequestDump} measures wall-clock from the before-hook
 * timestamp, but for a stream the after-hook does not run until the connection CLOSES: a stream held
 * open for minutes reported a multi-minute "duration" and tripped the slow-request thread dump on
 * every disconnect — a diagnostic that describes the client's subscription lifetime while claiming to
 * describe handler latency.
 *
 * <p>These tests pin both halves: the content-type predicate (media type only, case-insensitive,
 * charset ignored — NOT the {@code /stream} path convention), and the live behaviour that a
 * long-elapsed streaming response writes no dump while an equally long-elapsed non-streaming
 * response still does. The live half asserts against the real dump directory rather than a seam, so
 * {@code SlowRequestDumper} stays untouched.
 */
@DisplayName("Slow-request dump: streaming responses are exempt")
class SlowRequestStreamExemptionTest {

  /** Older than SLOW_REQUEST_THRESHOLD_MS (3s) without any test actually sleeping. */
  private static final long ELAPSED_NS = TimeUnit.SECONDS.toNanos(30);

  private Javalin app;
  private int port;
  private ExecutorService dumpExecutor;
  private String previousDataDir;
  private final java.util.concurrent.CountDownLatch afterHookRan =
      new java.util.concurrent.CountDownLatch(1);
  private final java.util.concurrent.atomic.AtomicReference<String> observedSseContentType =
      new java.util.concurrent.atomic.AtomicReference<>();

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
    if (dumpExecutor != null) {
      dumpExecutor.shutdownNow();
      dumpExecutor = null;
    }
    if (previousDataDir == null) {
      System.clearProperty("justsearch.data.dir");
    } else {
      System.setProperty("justsearch.data.dir", previousDataDir);
    }
  }

  // ---- pure predicate ----

  @Test
  @DisplayName("isStreamingContentType: text/event-stream is detected with or without parameters")
  void detectsEventStreamContentType() {
    assertTrue(ApiSecurityFilters.isStreamingContentType("text/event-stream"));
    // SseEnvelopeWriter.forceSseHeaders sets exactly this form.
    assertTrue(ApiSecurityFilters.isStreamingContentType("text/event-stream; charset=utf-8"));
    assertTrue(ApiSecurityFilters.isStreamingContentType("TEXT/EVENT-STREAM"));
    assertTrue(ApiSecurityFilters.isStreamingContentType("  text/event-stream  "));
  }

  @Test
  @DisplayName("isStreamingContentType: ordinary responses (and an absent content-type) are not streams")
  void rejectsNonStreamContentTypes() {
    assertFalse(ApiSecurityFilters.isStreamingContentType("application/json"));
    assertFalse(ApiSecurityFilters.isStreamingContentType("text/plain"));
    // Not a prefix match: a hypothetical sibling media type must not ride the exemption.
    assertFalse(ApiSecurityFilters.isStreamingContentType("text/event-stream-ish"));
    assertFalse(ApiSecurityFilters.isStreamingContentType(null));
    assertFalse(ApiSecurityFilters.isStreamingResponse(null));
  }

  // ---- live after-hook behaviour ----

  @Test
  @DisplayName("Live: a 30s-elapsed SSE response writes NO slow-request dump")
  void streamingResponseIsExempt(@TempDir Path dataDir) throws Exception {
    startSlowDumpServer(dataDir);
    assertEquals(200, get("/sse"));
    awaitDumps();
    assertEquals(
        List.of(),
        dumpFiles(dataDir),
        "A stream's open duration is not handler latency — no dump may be written");
  }

  @Test
  @DisplayName("Live: an equally-elapsed ordinary response still writes a dump (the exemption is narrow)")
  void nonStreamingResponseStillDumps(@TempDir Path dataDir) throws Exception {
    startSlowDumpServer(dataDir);
    assertEquals(200, get("/plain"));
    awaitDumps();
    assertEquals(
        1,
        dumpFiles(dataDir).size(),
        "A genuinely slow non-streaming request must still be captured");
  }

  @Test
  @DisplayName("Live: a REAL app.sse() route — the registration production uses — is exempt too")
  void javalinSseRouteIsExempt(@TempDir Path dataDir) throws Exception {
    // The two tests above set the content-type by hand. Production registers its streams with
    // `app.sse(...)` (ResourceApiModule / InfraRoutes / RuntimeApiRoutes) and lets Javalin set the
    // header — so assert the exemption against THAT, not against the hand-set form only. Without
    // this case the exemption could be green here and inert on every real stream.
    //
    // `Accept: text/event-stream` is required, and is not ceremony: Javalin's SseHandler upgrades
    // the response (and sets the content-type) only when the client negotiates SSE, which every
    // browser `EventSource` does — and `EventSource` is what the shell uses (Shell.ts:740,
    // AdvisoryStore, ActionLedgerView). Without the header the route is not a stream at all, which
    // the last assertion below pins.
    startSlowDumpServer(dataDir);
    assertEquals(200, get("/real-sse", "text/event-stream"));
    assertTrue(afterHookRan.await(10, TimeUnit.SECONDS), "the after-hook never ran for the SSE route");
    awaitDumps();
    assertEquals(
        "text/event-stream",
        observedSseContentType.get() == null
            ? null
            : observedSseContentType.get().split(";")[0].trim(),
        "Javalin must still report the stream content-type when the after-hook runs");
    assertEquals(List.of(), dumpFiles(dataDir), "a real SSE route must not trip the slow dump");
  }

  @Test
  @DisplayName("Live: the same route WITHOUT SSE negotiation is not treated as a stream")
  void unnegotiatedSseRouteIsNotExempt(@TempDir Path dataDir) throws Exception {
    // The exemption keys on what the response IS, not on the route's name. A request to the same
    // path that does not negotiate SSE is never upgraded by Javalin (it answers text/plain, not a
    // stream), so it stays subject to the slow-request dump. This is what makes the content-type
    // detection strictly narrower than the `/stream` path convention, rather than a rename of it.
    startSlowDumpServer(dataDir);
    assertEquals(200, get("/real-sse", null));
    awaitDumps();
    assertEquals(
        1,
        dumpFiles(dataDir).size(),
        "a non-upgraded request on an SSE path is an ordinary slow response");
  }

  // ---- helpers ----

  /**
   * Minimal hermetic server wired exactly like production's shared after-hook: a before-filter
   * stamps {@code __request_start_ns__} (backdated so the request reads as long-running without the
   * test sleeping), and the after-filter calls the real {@link
   * ApiSecurityFilters#maybeCaptureSlowRequestDump}.
   */
  private void startSlowDumpServer(Path dataDir) {
    previousDataDir = System.getProperty("justsearch.data.dir");
    System.setProperty("justsearch.data.dir", dataDir.toAbsolutePath().toString());

    dumpExecutor = Executors.newSingleThreadExecutor();
    ApiSecurityFilters filters =
        new ApiSecurityFilters(false, "test-token", null, dumpExecutor, null);

    app = Javalin.create(cfg -> {
      cfg.showJavalinBanner = false;
      cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });

    app.before(ctx -> ctx.attribute("__request_start_ns__", System.nanoTime() - ELAPSED_NS));
    app.get("/sse", ctx -> {
      // What SseEnvelopeWriter.forceSseHeaders does for an ad-hoc (non-EventSource) client.
      ctx.contentType("text/event-stream; charset=utf-8");
      ctx.result("event: connected\ndata: {}\n\n");
    });
    app.get("/plain", ctx -> ctx.json(Map.of("status", "ok")));
    // The production registration style: Javalin owns the content-type header.
    app.sse("/real-sse", client -> {
      client.sendEvent("connected", "{}");
      client.close();
    });
    app.after(ctx -> {
      if ("/real-sse".equals(ctx.path())) {
        observedSseContentType.set(ctx.res() == null ? null : ctx.res().getContentType());
      }
      filters.maybeCaptureSlowRequestDump(ctx);
      if ("/real-sse".equals(ctx.path())) {
        afterHookRan.countDown();
      }
    });

    app.start("127.0.0.1", 0);
    port = app.port();
  }

  private int get(String path) throws Exception {
    return get(path, null);
  }

  /** GET {@code path}, optionally negotiating a content type via the {@code Accept} header. */
  private int get(String path, String accept) throws Exception {
    java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
    java.net.http.HttpRequest.Builder req =
        java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://127.0.0.1:" + port + path))
            .GET();
    if (accept != null) {
      req.header("Accept", accept);
    }
    return client
        .send(req.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        .statusCode();
  }

  /** The dump is submitted to the executor off the response path — drain it before asserting. */
  private void awaitDumps() throws InterruptedException {
    dumpExecutor.shutdown();
    assertTrue(dumpExecutor.awaitTermination(10, TimeUnit.SECONDS), "dump executor did not drain");
  }

  private static List<String> dumpFiles(Path dataDir) throws Exception {
    Path dir = dataDir.resolve("slowapi");
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (var stream = Files.list(dir)) {
      return stream.map(p -> p.getFileName().toString()).sorted().toList();
    }
  }
}

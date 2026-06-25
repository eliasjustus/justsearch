package io.justsearch.app.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.app.api.SearchRequest;
import io.justsearch.app.api.SearchResponse;
import io.justsearch.app.config.ConfigManagerBootstrap;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.core.dto.Result;
import io.justsearch.core.search.SearchPort;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadAssemblyTest {
  @TempDir private Path tempDir;
  private String previousCapabilitiesProp;
  private ConfigStore previousStore;

  @BeforeEach
  void captureOverrides() {
    previousCapabilitiesProp = System.getProperty("app.api.fake_capabilities");
    previousStore = ConfigStore.globalOrNull();
    System.clearProperty("app.api.fake_capabilities");
    TestResolvedConfigHelper.storeFromEnvironment();
  }

  @AfterEach
  void restoreOverrides() {
    if (previousCapabilitiesProp == null) {
      System.clearProperty("app.api.fake_capabilities");
    } else {
      System.setProperty("app.api.fake_capabilities", previousCapabilitiesProp);
    }
    TestResolvedConfigHelper.restoreGlobal(previousStore);
  }

  @Test
  void appFacadeExposesFacade() throws Exception {
    SearchPort searchPort =
        intent -> new Result(List.of(), Map.of(), null, Map.of());
    Telemetry telemetry = new NoopTelemetry();

    try (HeadAssembly bootstrap = HeadAssembly.bootForSearchPortOnly(searchPort, telemetry)) {
      // Tempdoc 519 §5 / Step 4: bootstrap is itself the AppFacade (no separate accessor).
      assertNotNull(bootstrap);
    }
  }

  @Test
  void defaultConstructorBootsSearchRuntime() throws Exception {
    Telemetry telemetry = new NoopTelemetry();

    String prevPort = System.getProperty("justsearch.infra.health.port");
    try {
      System.setProperty("justsearch.infra.health.port", "0");
      try (HeadAssembly bootstrap = new HeadAssembly(telemetry, new ConfigManagerBootstrap(), null, new io.justsearch.app.services.settings.UiSettingsStore(io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY), null)) {
        SearchRequest request = new SearchRequest(5, 0, true, null, List.of(), List.of(), null);
        SearchResponse response = bootstrap.workers().search().search(request);
        assertNotNull(response);
        assertNotNull(response.hits());
      }
    } finally {
      if (prevPort == null) {
        System.clearProperty("justsearch.infra.health.port");
      } else {
        System.setProperty("justsearch.infra.health.port", prevPort);
      }
    }
  }

  /**
   * Regression (543-fwd hotfix 299b2ba69 + ordering fix 0febc18fb): async-path
   * connectKnowledgeServer must trigger agent-tool registration AFTER (a) the
   * worker-capability bridge transitions the local capability to READY (else registerLateBound
   * skips → "No handler registered for binding core.search-index"), AND (b) this.services is
   * reassembled with the fresh worker services (else this.services.worker().indexing() is null
   * → NPE in registerLateBound on indexingService::getWatchedPaths → HeadlessApp boot crash).
   * Asserts: connect does NOT throw (the boot NPE) AND the Memoized registration resolved true.
   */
  @Test
  void connectKnowledgeServerRegistersAgentToolsWithoutBootNpe() throws Exception {
    Telemetry telemetry = new NoopTelemetry();
    String prevPort = System.getProperty("justsearch.infra.health.port");
    try {
      System.setProperty("justsearch.infra.health.port", "0");
      // Tempdoc 627 Deliverable 10: share the capability the mocked KS reports, so the HeadAssembly's
      // localCap IS ks.workerCapability() (the production invariant) and no mirror is needed.
      var cap = new io.justsearch.app.services.lifecycle.WorkerCapability();
      try (HeadAssembly bootstrap =
          new HeadAssembly(
              telemetry,
              new ConfigManagerBootstrap(),
              null,
              new io.justsearch.app.services.settings.UiSettingsStore(
                  io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY), cap)) {
        var ks =
            org.mockito.Mockito.mock(
                io.justsearch.app.services.worker.KnowledgeServerBootstrap.class);
        var client =
            org.mockito.Mockito.mock(io.justsearch.app.services.worker.RemoteKnowledgeClient.class);
        cap.transition(io.justsearch.app.api.lifecycle.CapabilityHealth.READY, null);
        org.mockito.Mockito.when(ks.workerCapability()).thenReturn(cap);
        org.mockito.Mockito.when(ks.isReady()).thenReturn(true);
        org.mockito.Mockito.when(ks.client()).thenReturn(client);

        // Must NOT throw the boot NPE, and the agent-tool handlers must register.
        bootstrap.connectKnowledgeServer(ks);
        assertTrue(
            bootstrap.agentToolsRegistration().get(),
            "agent-tool handlers must register on worker connect");
      }
    } finally {
      if (prevPort == null) {
        System.clearProperty("justsearch.infra.health.port");
      } else {
        System.setProperty("justsearch.infra.health.port", prevPort);
      }
    }
  }

  @Test
  void fileBackedCapabilitiesHandlerServesPayload() throws Exception {
    Path payload = tempDir.resolve("caps.json");
    Files.writeString(payload, "{\"features\":[\"one\"]}", StandardCharsets.UTF_8);
    System.setProperty("app.api.fake_capabilities", payload.toString());

    try (HeadAssembly bootstrap = new HeadAssembly(new NoopTelemetry(), new ConfigManagerBootstrap(), null, new io.justsearch.app.services.settings.UiSettingsStore(io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY), null)) {
      FakeHttpExchange exchange = fakeExchange("GET", URI.create("http://localhost/infra/capabilities"));
      bootstrap.capabilitiesHandler().handle(exchange);
      assertEquals(200, exchange.statusCode);
      assertEquals("application/json; charset=utf-8", exchange.getResponseHeaders().getFirst("Content-Type"));
      assertEquals("{\"features\":[\"one\"]}", exchange.body());
    }
  }

  @Test
  void fileBackedCapabilitiesHandlerHandlesMissingFile() throws Exception {
    Path missing = tempDir.resolve("missing.json");
    System.setProperty("app.api.fake_capabilities", missing.toString());

    try (HeadAssembly bootstrap = new HeadAssembly(new NoopTelemetry(), new ConfigManagerBootstrap(), null, new io.justsearch.app.services.settings.UiSettingsStore(io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY), null)) {
      FakeHttpExchange exchange = fakeExchange("GET", URI.create("http://localhost/infra/capabilities"));
      bootstrap.capabilitiesHandler().handle(exchange);
      assertEquals(503, exchange.statusCode);
      assertTrue(exchange.body().contains("Capabilities fixture not found"));
    }
  }

  @Test
  void fileBackedCapabilitiesHandlerRejectsUnsupportedMethod() throws Exception {
    Path payload = tempDir.resolve("caps.json");
    Files.writeString(payload, "{\"features\":[]}", StandardCharsets.UTF_8);
    System.setProperty("app.api.fake_capabilities", payload.toString());

    try (HeadAssembly bootstrap = new HeadAssembly(new NoopTelemetry(), new ConfigManagerBootstrap(), null, new io.justsearch.app.services.settings.UiSettingsStore(io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY), null)) {
      FakeHttpExchange exchange = fakeExchange("POST", URI.create("http://localhost/infra/capabilities"));
      bootstrap.capabilitiesHandler().handle(exchange);
      assertEquals(405, exchange.statusCode);
      assertEquals("text/plain; charset=utf-8", exchange.getResponseHeaders().getFirst("Content-Type"));
    }
  }

  /**
   * Tempdoc 374 alpha.26 hotfix regression guard — adapted for the slice 1.1.a (430 / 429
   * substrate) merge that replaced the reflective {@code knowledgeServerRef} mechanism with
   * direct method dispatch via {@code defaultFacade.lateBindWorkerServices(...)}.
   *
   * <p>Original round-15 evidence: alpha.25 U14-B introduced a fatal NPE because the
   * {@code knowledgeServerRef} field was {@code final}; constructing with
   * {@code knowledgeServer=null} left it null and the late-bind path crashed.
   * Alpha.26 made the field {@code volatile} non-final.
   *
   * <p>Post-merge: the 429 substrate retired the reflective field. The same contract is now
   * enforced by direct dispatch through {@code defaultFacade.lateBindWorkerServices(client,
   * client, documentService)} — compile-time checked, no reflection needed. The behavioral
   * pin retained here is: constructing with {@code knowledgeServer=null} and calling
   * {@code connectKnowledgeServer(null)} must not throw.
   */
  @Test
  void connectKnowledgeServerLateBindDoesNotThrowOnNullCtor() throws Exception {
    String prevPort = System.getProperty("justsearch.infra.health.port");
    try {
      System.setProperty("justsearch.infra.health.port", "0");

      // Construct with knowledgeServer=null (the round-15 cold-start sequence).
      try (HeadAssembly bootstrap =
          new HeadAssembly(new NoopTelemetry(), new ConfigManagerBootstrap(), null, new io.justsearch.app.services.settings.UiSettingsStore(io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY), null)) {

        // connectKnowledgeServer(null) is documented as a no-op (early return on ks == null).
        // Post-merge, this is the entire contract — the 429 substrate dispatches via
        // defaultFacade.lateBindWorkerServices when ks != null, no reflective field probe.
        bootstrap.connectKnowledgeServer(null);
      }
    } finally {
      if (prevPort == null) {
        System.clearProperty("justsearch.infra.health.port");
      } else {
        System.setProperty("justsearch.infra.health.port", prevPort);
      }
    }
  }

  @Test
  void chooseFirstNonBlankPrefersFirstValue() throws Exception {
    Method method =
        HeadAssembly.class.getDeclaredMethod("chooseFirstNonBlank", String[].class);
    method.setAccessible(true);
    Object result = method.invoke(null, new Object[] {new String[] {"  ", "VALUE", "ignored"}});
    assertEquals("VALUE", result);
  }

  @Test
  void chooseFirstNonBlankReturnsNullWhenEmpty() throws Exception {
    Method method =
        HeadAssembly.class.getDeclaredMethod("chooseFirstNonBlank", String[].class);
    method.setAccessible(true);
    Object result = method.invoke(null, new Object[] {new String[] {null, "   ", ""}});
    assertNull(result);
  }

  @Test
  void chooseFirstNonBlankRemainsPrivateStaticVarargsShim() throws Exception {
    Method method =
        HeadAssembly.class.getDeclaredMethod("chooseFirstNonBlank", String[].class);
    assertTrue(Modifier.isPrivate(method.getModifiers()));
    assertTrue(Modifier.isStatic(method.getModifiers()));
    assertTrue(method.isVarArgs());
  }

  @Test
  void fileBackedCapabilitiesHandlerDirectInvocation() throws Exception {
    Path payload = tempDir.resolve("caps.json");
    String json = "{\"features\":[\"a\",\"b\"]}";
    Files.writeString(payload, json, StandardCharsets.UTF_8);
    // §31 Phase 1 followup: FileBackedCapabilitiesHandler was extracted to a top-level class in
    // bootstrap/phases/ during the structural cleanup. Reflection load by FQN.
    Class<?> handlerClass =
        Class.forName(
            "io.justsearch.app.services.bootstrap.phases.FileBackedCapabilitiesHandler");
    assertNotNull(handlerClass, "FileBackedCapabilitiesHandler not found");
    Constructor<?> ctor = handlerClass.getDeclaredConstructor(Path.class);
    ctor.setAccessible(true);
    Object handler = ctor.newInstance(payload);
    Method handle = handlerClass.getDeclaredMethod("handle", HttpExchange.class);
    handle.setAccessible(true);

    FakeHttpExchange exchange = fakeExchange("GET", URI.create("http://localhost/infra/capabilities"));
    handle.invoke(handler, exchange);
    assertEquals(200, exchange.statusCode);
    assertEquals(json, exchange.body());
  }

  private FakeHttpExchange fakeExchange(String method, URI uri) {
    return new FakeHttpExchange(method, uri);
  }

  private static final class NoopTelemetry implements Telemetry {
    @Override
    public void close() {}
  }

  private static final class FakeHttpExchange extends HttpExchange {
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final String method;
    private final URI uri;
    private final ByteArrayInputStream requestBody = new ByteArrayInputStream(new byte[0]);
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private int statusCode = -1;

    FakeHttpExchange(String method, URI uri) {
      this.method = method;
      this.uri = uri;
    }

    String body() {
      return responseBody.toString(StandardCharsets.UTF_8);
    }

    @Override
    public Headers getRequestHeaders() {
      return requestHeaders;
    }

    @Override
    public Headers getResponseHeaders() {
      return responseHeaders;
    }

    @Override
    public URI getRequestURI() {
      return uri;
    }

    @Override
    public String getRequestMethod() {
      return method;
    }

    @Override
    public HttpContext getHttpContext() {
      return null;
    }

    @Override
    public void close() {}

    @Override
    public InputStream getRequestBody() {
      return requestBody;
    }

    @Override
    public OutputStream getResponseBody() {
      return responseBody;
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) {
      this.statusCode = rCode;
    }

    @Override
    public int getResponseCode() {
      return statusCode;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
      return InetSocketAddress.createUnresolved("localhost", 0);
    }

    @Override
    public InetSocketAddress getLocalAddress() {
      return InetSocketAddress.createUnresolved("localhost", 0);
    }

    @Override
    public String getProtocol() {
      return "HTTP/1.1";
    }

    @Override
    public Object getAttribute(String name) {
      return null;
    }

    @Override
    public void setAttribute(String name, Object value) {}

    @Override
    public void setStreams(InputStream i, OutputStream o) {}

    @Override
    public HttpPrincipal getPrincipal() {
      return null;
    }
  }
}

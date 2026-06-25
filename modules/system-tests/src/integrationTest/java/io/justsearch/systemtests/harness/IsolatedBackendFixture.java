package io.justsearch.systemtests.harness;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Per-class isolated backend fixture (tempdoc 419 / T6.2).
 *
 * <p>Spawns {@code io.justsearch.ui.HeadlessApp} in a child JVM with:
 *
 * <ul>
 *   <li>{@code JUSTSEARCH_LITE_MODE=true} — skip the AI stack (T6.1 substrate)
 *   <li>A fresh tempdir as {@code JUSTSEARCH_DATA_DIR}
 *   <li>{@code JUSTSEARCH_API_PORT=0} — request an OS-assigned ephemeral port
 *   <li>The test JVM's full classpath, passed via Java's {@code @argfile} syntax to
 *       sidestep the Windows 8191-character command-line limit
 * </ul>
 *
 * <p>Tests use the fixture as a {@code @BeforeAll} / {@code @AfterAll} pair:
 *
 * <pre>{@code
 * class MyTest {
 *   static final IsolatedBackendFixture backend = new IsolatedBackendFixture();
 *   @BeforeAll static void setup() throws Exception { backend.start(); }
 *   @AfterAll  static void teardown()              { backend.stop(); }
 *   @Test void myTest() throws Exception {
 *     int port = backend.port();
 *     // ...HTTP calls against localhost:port...
 *   }
 * }
 * }</pre>
 *
 * <p>The startup sequence is two-phase: poll {@code dataDir/runtime/manifest.json} for the
 * port (HeadlessApp writes it after Javalin binds, per tempdoc 501), then poll
 * {@code /api/health} until it returns 200. Empirical measurement (PR8 spike report, commit
 * {@code e7ceeba8e}): the manifest appears ~200&nbsp;ms before the health endpoint accepts
 * requests on Windows; both phases are required.
 *
 * <p>Cleanup uses {@link Process#destroyForcibly()} (~0.42&nbsp;s on Windows in the spike).
 * The spawned Head spawns its own Worker subprocess; killing the parent kills the child too
 * via the existing {@code WorkerSpawner} Job-Object cleanup. SQLite WAL files released
 * cleanly in the spike — the tempdir delete retry loop is defense-in-depth, not load-bearing.
 *
 * <p><strong>Known limitation:</strong> if the test JVM itself crashes, the spawned Head is
 * orphaned (no Job Object binds it to the test JVM). The orphan listens on an ephemeral
 * port (no stuck-port issue) but must be killed manually. Test-JVM crashes are rare;
 * mitigation is not in scope for T6.2.
 */
public final class IsolatedBackendFixture {

  private static final long PORT_FILE_TIMEOUT_MS = 60_000L;
  private static final long HEALTH_TIMEOUT_MS = 30_000L;
  private static final long WORKER_READY_TIMEOUT_MS = 90_000L;
  private static final long POLL_INTERVAL_MS = 200L;
  private static final long STOP_GRACE_MS = 5_000L;
  private static final int CLEANUP_ATTEMPTS = 3;
  private static final long CLEANUP_BACKOFF_MS = 500L;
  private static final int LOG_TAIL_LINES = 500;

  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();

  private Path dataDir;
  private Path runtimeDir;
  private Path backendLog;
  private Process process;
  private int port = -1;

  /** Spawns the backend and blocks until {@code /api/health} returns 200. */
  public void start() throws IOException, InterruptedException {
    long t0 = System.nanoTime();
    dataDir = Files.createTempDirectory("isolated-backend-");
    runtimeDir = Files.createDirectories(dataDir.resolve("runtime"));
    backendLog = runtimeDir.resolve("backend.log");

    Path repoRoot = resolveRepoRoot();
    Path argfile = writeArgfile();
    Map<String, String> env = buildEnv(repoRoot);

    List<String> cmd = new ArrayList<>();
    cmd.add(resolveJavaExecutable());
    cmd.add("@" + argfile.toAbsolutePath());
    // Hand the child JVM the same repo and data context the test JVM resolved, so the
    // configuration loader and SSOT discovery don't have to walk up from user.dir.
    cmd.add("-Djustsearch.repo.root=" + repoRoot.toAbsolutePath());
    cmd.add("-Djustsearch.data.dir=" + dataDir.toAbsolutePath());
    cmd.add("io.justsearch.ui.HeadlessApp");

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.environment().clear();
    pb.environment().putAll(env);
    pb.directory(repoRoot.toFile());
    pb.redirectErrorStream(true);
    pb.redirectOutput(backendLog.toFile());

    System.err.println(
        "[IsolatedBackendFixture] phase=spawn dataDir=" + dataDir + " log=" + backendLog);
    process = pb.start();

    try {
      int observedPort = awaitPortFile();
      System.err.println(
          "[IsolatedBackendFixture] phase=port-file port=" + observedPort
              + " elapsedMs=" + ((System.nanoTime() - t0) / 1_000_000));
      awaitHealthOk(observedPort);
      this.port = observedPort;
      System.err.println(
          "[IsolatedBackendFixture] phase=health-ok port=" + observedPort
              + " elapsedMs=" + ((System.nanoTime() - t0) / 1_000_000));
      // /api/health=200 only proves Javalin bound. Lite mode reports DEGRADED while the
      // Worker subprocess is still connecting; ingest accepts the request but the index
      // never receives the doc until worker.state=READY. Block on that explicitly so
      // tests don't have to.
      awaitWorkerReady(observedPort);
      System.err.println(
          "[IsolatedBackendFixture] phase=ready port=" + observedPort
              + " elapsedMs=" + ((System.nanoTime() - t0) / 1_000_000));
    } catch (Exception startupFailure) {
      tailLogToStderr();
      preserveLogOnFailure();
      stop();
      throw new IllegalStateException(
          "Backend failed to become ready: " + startupFailure.getMessage(), startupFailure);
    }
  }

  /**
   * Returns the ephemeral port the backend bound to. Only valid after {@link #start()}
   * returns successfully.
   */
  public int port() {
    if (port < 0) {
      throw new IllegalStateException("port() called before successful start()");
    }
    return port;
  }

  /**
   * Returns the tempdir used as {@code JUSTSEARCH_DATA_DIR}. Tests can place corpora under
   * here so the same tempdir cleanup nukes them.
   */
  public Path dataDir() {
    return dataDir;
  }

  /** Force-kills the backend and removes the tempdir. Safe to call even if start() failed. */
  public void stop() {
    if (process != null && process.isAlive()) {
      process.destroyForcibly();
      try {
        if (!process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
          System.err.println(
              "[IsolatedBackendFixture] WARNING: process did not exit within "
                  + STOP_GRACE_MS + "ms after destroyForcibly()");
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
    if (Boolean.getBoolean("isolatedBackend.preserveLogs")) {
      preserveLogOnFailure();
    }
    deleteDataDirWithRetry();
  }

  // --------------------------------------------------------------------------------
  // Spawn helpers
  // --------------------------------------------------------------------------------

  private Path writeArgfile() throws IOException {
    String classpath = System.getProperty("java.class.path", "");
    if (classpath.isBlank()) {
      throw new IllegalStateException("java.class.path is empty — cannot spawn child JVM");
    }
    // Java's @argfile syntax: tokens are whitespace-separated. Wrap the classpath in
    // double-quotes so embedded spaces (e.g. "Program Files") are preserved as a single
    // token. Backslashes are literal inside quoted argfile strings — no escaping needed.
    String quoted = "\"" + classpath.replace("\"", "\\\"") + "\"";
    String body = "-classpath " + quoted + System.lineSeparator();
    Path argfile = runtimeDir.resolve("argfile");
    Files.writeString(argfile, body, StandardCharsets.UTF_8);
    return argfile;
  }

  private Map<String, String> buildEnv(Path repoRoot) {
    Map<String, String> env = new LinkedHashMap<>(System.getenv());
    env.put("JUSTSEARCH_LITE_MODE", "true");
    env.put("JUSTSEARCH_AI_DISABLED", "true");
    env.put("JUSTSEARCH_DATA_DIR", dataDir.toAbsolutePath().toString());
    env.put("JUSTSEARCH_API_PORT", "0");
    env.put("JUSTSEARCH_REPO_ROOT", repoRoot.toAbsolutePath().toString());
    // The test JVM's working dir is the module, not the repo root, so the dev-layout
    // lookup in KnowledgeServerConfig.resolveWorkerLibDir would fail. The Gradle task
    // wires justsearch.worker.lib.dir as a system property; forward it as the env var
    // KnowledgeServerConfig actually reads.
    String workerLibDir = System.getProperty("justsearch.worker.lib.dir");
    if (workerLibDir != null && !workerLibDir.isBlank()) {
      env.put("JUSTSEARCH_WORKER_LIB_DIR", workerLibDir);
    }
    return env;
  }

  private static Path resolveRepoRoot() {
    Path current = Path.of(System.getProperty("user.dir", "."));
    while (current != null) {
      if (Files.isDirectory(current.resolve("SSOT").resolve("catalogs"))) {
        return current.toAbsolutePath();
      }
      current = current.getParent();
    }
    throw new IllegalStateException(
        "Repo root with SSOT/catalogs not found by walking up from user.dir="
            + System.getProperty("user.dir"));
  }

  private static String resolveJavaExecutable() {
    String javaHome = System.getProperty("java.home");
    if (javaHome == null || javaHome.isBlank()) {
      return isWindows() ? "java.exe" : "java";
    }
    Path bin = Path.of(javaHome, "bin", isWindows() ? "java.exe" : "java");
    return bin.toString();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  // --------------------------------------------------------------------------------
  // Await helpers
  // --------------------------------------------------------------------------------

  private int awaitPortFile() throws IOException, InterruptedException {
    // Tempdoc 501 Phase 18: read from the canonical manifest.json instead of the
    // deprecated api-port.txt. The manifest's head.apiPort carries the same value the
    // legacy mirror used to expose; reading from the canonical source removes the last
    // integration-test dependency on the deprecated file.
    Path manifestPath = runtimeDir.resolve("manifest.json");
    long deadline = System.currentTimeMillis() + PORT_FILE_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      if (process != null && !process.isAlive()) {
        throw new IllegalStateException(
            "Backend process exited before writing manifest (exit code "
                + process.exitValue() + ")");
      }
      if (Files.exists(manifestPath)) {
        try {
          String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
          var node = new tools.jackson.databind.ObjectMapper().readTree(json);
          var headNode = node.get("head");
          if (headNode != null && headNode.get("apiPort") != null) {
            int port = headNode.get("apiPort").asInt();
            if (port > 0) {
              return port;
            }
          }
        } catch (Exception parseErr) {
          // partial write — keep polling
        }
      }
      Thread.sleep(POLL_INTERVAL_MS);
    }
    throw new IllegalStateException(
        "Manifest " + manifestPath + " did not appear within " + PORT_FILE_TIMEOUT_MS + "ms");
  }

  private void awaitWorkerReady(int observedPort) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WORKER_READY_TIMEOUT_MS;
    URI uri = URI.create("http://localhost:" + observedPort + "/api/health");
    HttpRequest req =
        HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(5)).GET().build();
    String lastBody = "<no response>";
    while (System.currentTimeMillis() < deadline) {
      if (process != null && !process.isAlive()) {
        throw new IllegalStateException(
            "Backend process exited while waiting for worker.state=READY (exit code "
                + process.exitValue() + ")");
      }
      try {
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        lastBody = resp.body();
        // The response shape (LifecycleSnapshotV1) serialises components in declaration
        // order — head, worker, inference — and Jackson omits null fields, so the worker
        // component always begins with "worker":{"state":"<STATE>". We avoid pulling
        // Jackson into the fixture for a single readiness probe. Tempdoc 548 (§4.1) collapsed
        // LifecycleState onto the proto enum, so the wire value is now the prefixed
        // "LIFECYCLE_STATE_READY"; accept both the prefixed and the legacy short form so the
        // probe is robust across that serialization change.
        if (resp.statusCode() == 200
            && (lastBody.contains("\"worker\":{\"state\":\"LIFECYCLE_STATE_READY\"")
                || lastBody.contains("\"worker\":{\"state\":\"READY\""))) {
          return;
        }
      } catch (IOException ioe) {
        // keep polling
      }
      Thread.sleep(POLL_INTERVAL_MS);
    }
    throw new IllegalStateException(
        "components.worker.state did not reach READY within " + WORKER_READY_TIMEOUT_MS
            + "ms. Last /api/health body: " + lastBody);
  }

  private void awaitHealthOk(int observedPort) throws InterruptedException {
    long deadline = System.currentTimeMillis() + HEALTH_TIMEOUT_MS;
    URI uri = URI.create("http://localhost:" + observedPort + "/api/health");
    HttpRequest req =
        HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(5)).GET().build();
    Throwable lastError = null;
    while (System.currentTimeMillis() < deadline) {
      if (process != null && !process.isAlive()) {
        throw new IllegalStateException(
            "Backend process exited before /api/health responded (exit code "
                + process.exitValue() + ")");
      }
      try {
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) {
          return;
        }
        // Lite mode reports DEGRADED with status 503; that's fine for tests that only need
        // diagnostics + ingestion endpoints. We accept any 2xx as ready, but per the spike
        // /api/health returns 200 in lite mode (DEGRADED is reported in the body).
      } catch (IOException ioe) {
        lastError = ioe;
      }
      Thread.sleep(POLL_INTERVAL_MS);
    }
    String hint = lastError == null ? "" : " (last error: " + lastError + ")";
    throw new IllegalStateException(
        "/api/health did not return 200 within " + HEALTH_TIMEOUT_MS + "ms" + hint);
  }

  // --------------------------------------------------------------------------------
  // Cleanup helpers
  // --------------------------------------------------------------------------------

  private void deleteDataDirWithRetry() {
    if (dataDir == null || !Files.exists(dataDir)) {
      return;
    }
    for (int attempt = 1; attempt <= CLEANUP_ATTEMPTS; attempt++) {
      try {
        deleteRecursively(dataDir);
        return;
      } catch (IOException io) {
        if (attempt == CLEANUP_ATTEMPTS) {
          System.err.println(
              "[IsolatedBackendFixture] WARNING: failed to delete tempdir " + dataDir
                  + " after " + CLEANUP_ATTEMPTS + " attempts: " + io.getMessage());
          return;
        }
        try {
          Thread.sleep(CLEANUP_BACKOFF_MS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    } catch (RuntimeException re) {
      if (re.getCause() instanceof IOException io) {
        throw io;
      }
      throw re;
    }
  }

  private void preserveLogOnFailure() {
    Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
    copyIfPresent(backendLog, tmp.resolve("isolated-backend-failure.log"));
    if (dataDir != null) {
      copyIfPresent(
          dataDir.resolve("logs").resolve("app.log"),
          tmp.resolve("isolated-backend-app.log"));
      copyIfPresent(
          dataDir.resolve("logs").resolve("worker.log"),
          tmp.resolve("isolated-backend-worker.log"));
      Path crashesDir = dataDir.resolve("crashes");
      if (Files.exists(crashesDir)) {
        try (Stream<Path> walk = Files.walk(crashesDir)) {
          walk.filter(Files::isRegularFile)
              .forEach(
                  p -> copyIfPresent(p, tmp.resolve("isolated-backend-" + p.getFileName())));
        } catch (IOException io) {
          System.err.println(
              "[IsolatedBackendFixture] failed to walk crashes dir: " + io);
        }
      }
    }
  }

  private void copyIfPresent(Path source, Path target) {
    if (source == null || !Files.exists(source)) {
      return;
    }
    try {
      Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      System.err.println("[IsolatedBackendFixture] log preserved at " + target);
    } catch (IOException io) {
      System.err.println("[IsolatedBackendFixture] failed to copy " + source + ": " + io);
    }
  }

  private void tailLogToStderr() {
    if (backendLog == null || !Files.exists(backendLog)) {
      System.err.println("[IsolatedBackendFixture] no backend log to tail");
      return;
    }
    try {
      List<String> lines = Files.readAllLines(backendLog, StandardCharsets.UTF_8);
      int from = Math.max(0, lines.size() - LOG_TAIL_LINES);
      System.err.println(
          "[IsolatedBackendFixture] last " + (lines.size() - from)
              + " lines of " + backendLog + ":");
      for (int i = from; i < lines.size(); i++) {
        System.err.println("  | " + lines.get(i));
      }
    } catch (IOException io) {
      System.err.println("[IsolatedBackendFixture] failed to read log " + backendLog + ": " + io);
    }
  }
}

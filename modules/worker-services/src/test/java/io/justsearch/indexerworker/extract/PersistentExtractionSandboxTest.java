package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.telemetry.catalog.TestMetricRegistry;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Protocol and lifecycle tests for {@link PersistentExtractionSandbox} (tempdoc 885 item 14).
 *
 * <p>Successor to {@code ProcessExtractionSandboxTest}, which exercised the retired
 * one-child-JVM-per-file sandbox. Every stub child here runs the same length-prefixed serve loop
 * the real child runs and branches on the request's file name, so "the next request succeeds" is
 * asserted against the same stub that just failed — which is the property the pool exists for.
 */
final class PersistentExtractionSandboxTest {

  @TempDir Path tempDir;

  private PersistentExtractionSandbox sandbox(List<String> command, Duration timeout) {
    return sandbox(command, timeout, null);
  }

  private PersistentExtractionSandbox sandbox(
      List<String> command, Duration timeout, ExtractionMetricCatalog catalog) {
    return new PersistentExtractionSandbox(
        command, TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled(), timeout, 1, 500, catalog);
  }

  private Path file(String name) throws Exception {
    Path path = tempDir.resolve(name);
    Files.writeString(path, "sandbox child content");
    return path;
  }

  @Test
  @Timeout(40)
  void realChildExtractsFileContent() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        sandbox(javaCommand(ExtractionSandboxChild.class), Duration.ofSeconds(30))) {
      ExtractionArtifact artifact = sandbox.extract(file("sandbox.txt"));

      assertEquals("tika-default-v1", artifact.policyId());
      assertTrue(artifact.result().content().contains("sandbox child content"));
    }
  }

  @Test
  @Timeout(40)
  void oneChildServesThreeRequestsWithoutRespawning() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        sandbox(javaCommand(ScriptedChild.class), Duration.ofSeconds(20))) {
      assertEquals("ok-1.txt", sandbox.extract(file("ok-1.txt")).result().content());
      long pid = sandbox.firstChildPid();
      assertEquals("ok-2.txt", sandbox.extract(file("ok-2.txt")).result().content());
      assertEquals("ok-3.txt", sandbox.extract(file("ok-3.txt")).result().content());

      assertEquals(pid, sandbox.firstChildPid(), "all three requests must share one child");
      assertEquals(1L, sandbox.spawnCount());
      assertEquals(0L, sandbox.restartCount());
    }
  }

  @Test
  @Timeout(60)
  void hangingChildIsKilledAtTheDeadlineAndTheNextRequestSucceeds() throws Exception {
    try (TestMetricRegistry registry = new TestMetricRegistry(ExtractionMetricCatalog.DEFINITIONS)) {
      ExtractionMetricCatalog catalog = new ExtractionMetricCatalog(registry);
      try (PersistentExtractionSandbox sandbox =
          sandbox(javaCommand(ScriptedChild.class), Duration.ofSeconds(2), catalog)) {
        Path hanging = file("hang.txt");
        assertThrows(
            TimeboxedContentExtractor.ExtractionTimeoutException.class,
            () -> sandbox.extract(hanging));

        assertEquals(1L, sandbox.restartCount());
        assertEquals(
            1L,
            registry.counterValue(
                ExtractionMetricCatalog.SANDBOX_RESTART_TOTAL,
                ExtractionSandboxRestartTags.of(PersistentExtractionSandbox.REASON_TIMEOUT)));

        assertEquals("next-after-timeout.txt", sandbox.extract(file("next-after-timeout.txt")).result().content());
        assertEquals(2L, sandbox.spawnCount(), "the killed child must be replaced, not reused");
      }
    }
  }

  @Test
  @Timeout(60)
  void crashingChildReportsItsExitCodeAndTheNextRequestSucceeds() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        sandbox(javaCommand(ScriptedChild.class), Duration.ofSeconds(20))) {
      Path crashing = file("crash.txt");
      SandboxExtractionException failure =
          assertThrows(SandboxExtractionException.class, () -> sandbox.extract(crashing));
      assertTrue(
          failure.getMessage().contains("exited with code 3"),
          "failure reason must carry the child exit code; got: " + failure.getMessage());

      assertEquals("next-after-exit.txt", sandbox.extract(file("next-after-exit.txt")).result().content());
      assertEquals(2L, sandbox.spawnCount());
    }
  }

  @Test
  @Timeout(60)
  void childHeapExhaustionIsAPermanentParseFailure() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        sandbox(javaCommand(ScriptedChild.class, "-Xmx64m"), Duration.ofSeconds(45))) {
      Path oom = file("oom.txt");
      ContentExtractor.ExtractionException failure =
          assertThrows(
              ContentExtractor.ExtractionException.class, () -> sandbox.extract(oom));

      // PERMANENT means: not the retryable SandboxExtractionException. JobBatchExtractor maps a
      // plain ExtractionException to PARSER_FAILED + IngestionRetryPolicy.NONE.
      assertEquals(
          ContentExtractor.ExtractionException.class,
          failure.getClass(),
          "an OOM child must not be reported as a retryable sandbox failure: "
              + failure.getMessage());
      assertTrue(
          failure.getMessage().contains("exhausted its heap"),
          "got: " + failure.getMessage());
    }
  }

  @Test
  @Timeout(40)
  void pollutedStdoutDoesNotCorruptTheFrame() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        sandbox(javaCommand(ScriptedChild.class), Duration.ofSeconds(20))) {
      assertEquals("pollute.txt", sandbox.extract(file("pollute.txt")).result().content());
      assertEquals(0L, sandbox.restartCount());
    }
  }

  @Test
  @Timeout(40)
  void malformedResponseIsASandboxFailure() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        sandbox(javaCommand(ScriptedChild.class), Duration.ofSeconds(20))) {
      Path malformed = file("malformed.txt");
      assertThrows(SandboxExtractionException.class, () -> sandbox.extract(malformed));
    }
  }

  @Test
  @Timeout(40)
  void oversizedFrameIsASandboxFailure() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        new PersistentExtractionSandbox(
            javaCommand(ScriptedChild.class),
            TikaExtractionPolicy.defaults(),
            OcrRoutingConfig.disabled(),
            Duration.ofSeconds(20),
            1,
            500,
            null,
            2048,
            4096)) {
      Path oversized = file("oversized.txt");
      assertThrows(SandboxExtractionException.class, () -> sandbox.extract(oversized));
    }
  }

  @Test
  @Timeout(40)
  void ocrConfigReachesTheChild() throws Exception {
    OcrRoutingConfig ocrConfig =
        new OcrRoutingConfig(true, List.of("deu"), 1_234, 4, 2048, 8_000_000, null, null);
    try (PersistentExtractionSandbox sandbox =
        new PersistentExtractionSandbox(
            javaCommand(ScriptedChild.class),
            TikaExtractionPolicy.defaults(),
            ocrConfig,
            Duration.ofSeconds(20),
            1,
            500,
            null)) {
      assertEquals("ocr-enabled:deu", sandbox.extract(file("ocr.txt")).result().content());
    }
  }

  @Test
  @Timeout(40)
  void childRecyclesAfterItsRequestBudget() throws Exception {
    try (PersistentExtractionSandbox sandbox =
        new PersistentExtractionSandbox(
            javaCommand(ScriptedChild.class),
            TikaExtractionPolicy.defaults(),
            OcrRoutingConfig.disabled(),
            Duration.ofSeconds(20),
            1,
            2,
            null)) {
      sandbox.extract(file("ok-1.txt"));
      long first = sandbox.firstChildPid();
      sandbox.extract(file("ok-2.txt"));
      sandbox.extract(file("ok-3.txt"));

      assertNotEquals(first, sandbox.firstChildPid(), "child must be recycled after 2 requests");
      assertEquals(2L, sandbox.spawnCount());
      assertEquals(1L, sandbox.restartCount());
    }
  }

  @Test
  @Timeout(60)
  void childExitsWhenItsParentPidIsGone() throws Exception {
    Process victim = new ProcessBuilder(javaCommand(ExitingChild.class)).start();
    assertTrue(victim.waitFor(30, TimeUnit.SECONDS), "helper process must exit");
    // Keep the Process handle referenced for the rest of the test: on Windows an open handle stops
    // the OS recycling the PID, so "dead pid" stays unambiguously dead.
    long deadPid = victim.pid();

    List<String> command = new ArrayList<>(javaCommand(ExtractionSandboxChild.class));
    command.add(ExtractionSandboxChild.PARENT_PID_FLAG + deadPid);
    Process orphan = new ProcessBuilder(command).start();
    try {
      assertTrue(
          orphan.waitFor(30, TimeUnit.SECONDS),
          "child must halt itself once its parent PID is gone (no orphan after a Worker kill)");
    } finally {
      orphan.destroyForcibly();
      // Holds the handle to the end of the test so Windows cannot recycle deadPid underneath us.
      victim.destroyForcibly();
    }
  }

  static List<String> javaCommand(Class<?> mainClass, String... jvmArgs) {
    String executable =
        Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
    List<String> command = new ArrayList<>();
    command.add(executable);
    command.addAll(List.of(jvmArgs));
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(mainClass.getName());
    return List.copyOf(command);
  }

  private static boolean windows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /** Trivial helper whose only job is to produce a PID that is definitely dead. */
  public static final class ExitingChild {
    public static void main(String[] args) {
      // Intentionally empty: the JVM exits immediately.
    }
  }

  /**
   * One stub child covering every failure mode, selected by the request's file name. Runs the real
   * serve loop, so a request that follows a failure lands on a genuinely fresh child.
   */
  public static final class ScriptedChild {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
      PrintStream protocolOut = System.out;
      System.setOut(new PrintStream(System.err, true, StandardCharsets.UTF_8));
      InputStream in = System.in;
      byte[] frame;
      List<byte[]> retained = new ArrayList<>();
      while ((frame = SandboxFrames.read(in, SandboxFrames.MAX_FRAME_BYTES)) != null) {
        SandboxExtractionRequest request =
            MAPPER.readValue(
                new String(frame, StandardCharsets.UTF_8), SandboxExtractionRequest.class);
        String name = Path.of(request.path()).getFileName().toString();

        if (name.contains("hang")) {
          Thread.sleep(600_000L);
        }
        if (name.contains("crash")) {
          System.exit(3);
        }
        if (name.contains("oom")) {
          while (true) {
            retained.add(new byte[8 * 1024 * 1024]);
          }
        }
        if (name.contains("malformed")) {
          writeRaw(protocolOut, "{not-json".getBytes(StandardCharsets.UTF_8));
          continue;
        }
        if (name.contains("oversized")) {
          writeRaw(protocolOut, "x".repeat(8192).getBytes(StandardCharsets.UTF_8));
          continue;
        }
        if (name.contains("pollute")) {
          // Goes to stderr thanks to the redirect above — the pollution the frame must survive.
          System.out.println("parser chatter {\"schemaVersion\":1}");
        }

        OcrRoutingConfig ocr = request.ocrConfig();
        String content =
            name.contains("ocr") && ocr != null && ocr.enabled()
                ? "ocr-enabled:" + String.join(",", ocr.languages())
                : name;
        ExtractionArtifact artifact =
            ExtractionArtifact.full(
                new ContentExtractor.ExtractionResult(content, null, "text/plain"),
                request.policy(),
                "scripted-child",
                false);
        SandboxFrames.write(
            protocolOut, MAPPER.writeValueAsBytes(SandboxExtractionResponse.fromArtifact(artifact)));
      }
    }

    private static void writeRaw(OutputStream out, byte[] payload) throws java.io.IOException {
      SandboxFrames.write(out, payload);
    }
  }
}

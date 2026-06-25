package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class ProcessExtractionSandboxTest {
  @TempDir Path tempDir;

  @Test
  @Timeout(20)
  void currentJavaSandboxReturnsValidatedArtifact() throws Exception {
    Path file = tempDir.resolve("sandbox.txt");
    Files.writeString(file, "sandbox child content");

    ProcessExtractionSandbox sandbox =
        new ProcessExtractionSandbox(
            javaCommand(ExtractionSandboxChild.class), TikaExtractionPolicy.defaults(), Duration.ofSeconds(10));
    ExtractionArtifact artifact = sandbox.extract(file);

    assertEquals("tika-default-v1", artifact.policyId());
    assertTrue(artifact.result().content().contains("sandbox child content"));
  }

  @Test
  @Timeout(10)
  void processSandboxSendsOcrConfigToChild() throws Exception {
    Path file = tempDir.resolve("sandbox.txt");
    Files.writeString(file, "sandbox child content");
    OcrRoutingConfig ocrConfig =
        new OcrRoutingConfig(true, List.of("deu"), 1_234, 4, 2048, 8_000_000);

    ProcessExtractionSandbox sandbox =
        new ProcessExtractionSandbox(
            javaCommand(EchoOcrConfigChild.class),
            TikaExtractionPolicy.defaults(),
            ocrConfig,
            Duration.ofSeconds(5));
    ExtractionArtifact artifact = sandbox.extract(file);

    assertEquals("ocr-enabled:deu", artifact.result().content());
  }

  @Test
  @Timeout(10)
  void malformedResponseIsSandboxFailure() throws Exception {
    Path file = tempDir.resolve("ignored.txt");
    Files.writeString(file, "ignored");

    ProcessExtractionSandbox sandbox =
        new ProcessExtractionSandbox(javaCommand(MalformedChild.class), TikaExtractionPolicy.defaults(), Duration.ofSeconds(5));

    assertThrows(ProcessExtractionSandbox.SandboxExtractionException.class, () -> sandbox.extract(file));
  }

  @Test
  @Timeout(10)
  void stdoutPollutionIsSandboxFailure() throws Exception {
    Path file = tempDir.resolve("ignored.txt");
    Files.writeString(file, "ignored");

    ProcessExtractionSandbox sandbox =
        new ProcessExtractionSandbox(javaCommand(PollutedChild.class), TikaExtractionPolicy.defaults(), Duration.ofSeconds(5));

    assertThrows(ProcessExtractionSandbox.SandboxExtractionException.class, () -> sandbox.extract(file));
  }

  @Test
  @Timeout(10)
  void oversizedResponseIsSandboxFailure() throws Exception {
    Path file = tempDir.resolve("ignored.txt");
    Files.writeString(file, "ignored");

    ProcessExtractionSandbox sandbox =
        new ProcessExtractionSandbox(
            javaCommand(OversizedChild.class), TikaExtractionPolicy.defaults(), Duration.ofSeconds(5), 1024, 1024);

    assertThrows(ProcessExtractionSandbox.SandboxExtractionException.class, () -> sandbox.extract(file));
  }

  @Test
  @Timeout(10)
  void timeoutIsParserTimeout() throws Exception {
    Path file = tempDir.resolve("ignored.txt");
    Files.writeString(file, "ignored");

    ProcessExtractionSandbox sandbox =
        new ProcessExtractionSandbox(
            javaCommand(SleepingChild.class), TikaExtractionPolicy.defaults(), Duration.ofMillis(200));

    assertThrows(TimeboxedContentExtractor.ExtractionTimeoutException.class, () -> sandbox.extract(file));
  }

  private static List<String> javaCommand(Class<?> mainClass) {
    String executable =
        Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
    return List.of(executable, "-cp", System.getProperty("java.class.path"), mainClass.getName());
  }

  private static boolean windows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
  }

  public static final class MalformedChild {
    public static void main(String[] args) {
      System.out.print("{not-json");
    }
  }

  public static final class OversizedChild {
    public static void main(String[] args) {
      System.out.print("x".repeat(2048));
    }
  }

  public static final class PollutedChild {
    public static void main(String[] args) {
      System.out.print("log line {\"schemaVersion\":1}");
    }
  }

  public static final class SleepingChild {
    public static void main(String[] args) throws Exception {
      Thread.sleep(5_000);
    }
  }

  public static final class EchoOcrConfigChild {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
      SandboxExtractionRequest request =
          MAPPER.readValue(System.in.readAllBytes(), SandboxExtractionRequest.class);
      OcrRoutingConfig ocrConfig = request.ocrConfig();
      String content =
          ocrConfig != null && ocrConfig.enabled()
              ? "ocr-enabled:" + String.join(",", ocrConfig.languages())
              : "ocr-disabled";
      ExtractionArtifact artifact =
          ExtractionArtifact.full(
              new ContentExtractor.ExtractionResult(content, null, "text/plain"),
              request.policy(),
              "echo-child",
              false);
      System.out.write(MAPPER.writeValueAsBytes(SandboxExtractionResponse.fromArtifact(artifact)));
    }
  }
}

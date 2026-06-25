/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Out-of-process extraction sandbox using a bounded JSON request/response protocol. */
public final class ProcessExtractionSandbox implements ExtractionSandbox {
  private static final Logger log = LoggerFactory.getLogger(ProcessExtractionSandbox.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final int DEFAULT_MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
  private static final int DEFAULT_MAX_STDERR_BYTES = 64 * 1024;

  private final List<String> command;
  private final TikaExtractionPolicy policy;
  private final OcrRoutingConfig ocrConfig;
  private final Duration timeout;
  private final int maxResponseBytes;
  private final int maxStderrBytes;

  public ProcessExtractionSandbox(List<String> command, TikaExtractionPolicy policy, Duration timeout) {
    this(command, policy, OcrRoutingConfig.disabled(), timeout, DEFAULT_MAX_RESPONSE_BYTES, DEFAULT_MAX_STDERR_BYTES);
  }

  public ProcessExtractionSandbox(
      List<String> command, TikaExtractionPolicy policy, OcrRoutingConfig ocrConfig, Duration timeout) {
    this(command, policy, ocrConfig, timeout, DEFAULT_MAX_RESPONSE_BYTES, DEFAULT_MAX_STDERR_BYTES);
  }

  public ProcessExtractionSandbox(
      List<String> command,
      TikaExtractionPolicy policy,
      Duration timeout,
      int maxResponseBytes,
      int maxStderrBytes) {
    this(command, policy, OcrRoutingConfig.disabled(), timeout, maxResponseBytes, maxStderrBytes);
  }

  public ProcessExtractionSandbox(
      List<String> command,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      int maxResponseBytes,
      int maxStderrBytes) {
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("Sandbox command must not be empty");
    }
    this.command = List.copyOf(command);
    this.policy = policy == null ? TikaExtractionPolicy.defaults() : policy;
    this.ocrConfig = ocrConfig == null ? OcrRoutingConfig.disabled() : ocrConfig;
    this.timeout = timeout == null ? TimeboxedContentExtractor.DEFAULT_TIMEOUT : timeout;
    this.maxResponseBytes = Math.max(1024, maxResponseBytes);
    this.maxStderrBytes = Math.max(1024, maxStderrBytes);
  }

  TikaExtractionPolicy policy() {
    return policy;
  }

  @Override
  public ExtractionArtifact extract(Path file) throws IOException, ContentExtractor.ExtractionException {
    Objects.requireNonNull(file, "file");
    Process process = new ProcessBuilder(command).start();
    CompletableFuture<LimitedBytes> stdout =
        CompletableFuture.supplyAsync(() -> readLimited(process.getInputStream(), maxResponseBytes));
    CompletableFuture<LimitedBytes> stderr =
        CompletableFuture.supplyAsync(() -> readLimited(process.getErrorStream(), maxStderrBytes));

    try {
      byte[] request =
          MAPPER.writeValueAsBytes(
              new SandboxExtractionRequest(
                  SandboxExtractionRequest.CURRENT_SCHEMA_VERSION,
                  file.toAbsolutePath().toString(),
                  policy,
                  ocrConfig));
      process.getOutputStream().write(request);
      process.getOutputStream().close();
    } catch (IOException | RuntimeException e) {
      // Tempdoc 588 F-3: serializing the request can throw, and writing to the child's stdin can
      // throw a broken-pipe IOException if the child already exited/crashed. Unlike the interrupt
      // and timeout branches below, this path previously leaked the child process AND both stdout/
      // stderr reader threads. destroyForcibly() kills the child, which closes its streams and so
      // completes (un-leaks) the two reader futures. Re-throw the original failure unchanged.
      process.destroyForcibly();
      throw e;
    }

    boolean exited;
    try {
      exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw sandboxException("Sandbox interrupted", e);
    }
    if (!exited) {
      process.destroyForcibly();
      throw new TimeboxedContentExtractor.ExtractionTimeoutException("Sandbox extraction timed out", null);
    }

    LimitedBytes output = stdout.join();
    LimitedBytes error = stderr.join();
    if (output.readError() != null) {
      throw sandboxException("Sandbox stdout read failed", output.readError());
    }
    if (output.truncated()) {
      throw sandboxException("Sandbox response exceeded max bytes", null);
    }
    if (process.exitValue() != 0) {
      throw sandboxException(
          "Sandbox process failed: " + summarize(error.bytes()), error.readError());
    }
    // Tempdoc 410 review fix #3: child stderr on the success path is normally empty. Log any
    // non-empty content at debug so operators can see JNI / native-loader noise without it
    // crossing the protocol boundary into the response.
    if (error.length() > 0 && log.isDebugEnabled()) {
      log.debug("Sandbox child stderr (success path): {}", summarize(error.bytes()));
    }
    try {
      SandboxExtractionResponse response =
          MAPPER.readValue(requireProtocolOnlyJson(output.bytes()), SandboxExtractionResponse.class);
      validateResponse(response);
      ExtractionArtifact artifact = response.toArtifact();
      if (response.status() == ExtractionStatus.BUDGET_EXCEEDED) {
        throw new ContentExtractor.BudgetExceededException(
            "Sandbox extraction budget exceeded",
            response.reasonCode() == null ? "EXTRACTED_TEXT_TOO_LARGE" : response.reasonCode());
      }
      if (response.status() == ExtractionStatus.TIMED_OUT) {
        throw new TimeboxedContentExtractor.ExtractionTimeoutException("Sandbox parser timed out", null);
      }
      if (response.status() == ExtractionStatus.FAILED) {
        throw new ContentExtractor.ExtractionException("Sandbox parser failed");
      }
      return artifact.validateContentBoundsOnly(policy.maxExtractedChars());
    } catch (ContentExtractor.ExtractionException e) {
      throw e;
    } catch (RuntimeException e) {
      throw sandboxException("Malformed sandbox response", e);
    }
  }

  private static void validateResponse(SandboxExtractionResponse response) {
    if (response == null || response.schemaVersion() != SandboxExtractionResponse.CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported sandbox response schema");
    }
  }

  private static ContentExtractor.ExtractionException sandboxException(String message, Throwable cause) {
    return new SandboxExtractionException(message, cause);
  }

  private static LimitedBytes readLimited(java.io.InputStream stream, int maxBytes) {
    try (stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int total = 0;
      int read;
      while ((read = stream.read(buffer)) >= 0) {
        total += read;
        if (total > maxBytes) {
          return new LimitedBytes(out.toByteArray(), true, null);
        }
        out.write(buffer, 0, read);
      }
      return new LimitedBytes(out.toByteArray(), false, null);
    } catch (IOException e) {
      return new LimitedBytes(new byte[0], false, e);
    }
  }

  private static String summarize(byte[] bytes) {
    String value = new String(bytes, StandardCharsets.UTF_8).replaceAll("[\\r\\n\\t]+", " ").trim();
    return value.length() <= 256 ? value : value.substring(0, 256);
  }

  private static String requireProtocolOnlyJson(byte[] bytes) {
    String raw = new String(bytes, StandardCharsets.UTF_8);
    String trimmed = raw.trim();
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
      throw new IllegalArgumentException("Sandbox stdout contained non-protocol data");
    }
    return trimmed;
  }

  /** Sandbox infrastructure failure distinct from parser failure. */
  public static final class SandboxExtractionException extends ContentExtractor.ExtractionException {
    public SandboxExtractionException(String message, Throwable cause) {
      super(message + " [" + IngestionReasonCodes.SANDBOX_FAILED + "]", cause);
    }
  }

  private static final class LimitedBytes {
    private final byte[] bytes;
    private final boolean truncated;
    private final IOException readError;

    private LimitedBytes(byte[] bytes, boolean truncated, IOException readError) {
      this.bytes = bytes == null ? new byte[0] : bytes.clone();
      this.truncated = truncated;
      this.readError = readError;
    }

    byte[] bytes() {
      return bytes.clone();
    }

    /** Length of the underlying buffer without forcing a defensive clone. */
    int length() {
      return bytes.length;
    }

    boolean truncated() {
      return truncated;
    }

    /** Non-null when the read aborted with an {@link IOException}; threaded into
     * the typed sandbox exception so operators see the underlying cause. */
    IOException readError() {
      return readError;
    }
  }
}

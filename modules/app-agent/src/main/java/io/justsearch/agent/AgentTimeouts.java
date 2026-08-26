/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tempdoc 877 §2.8 — the one holder for every agent-loop duration, in milliseconds. Replaces nine
 * literals scattered across seven files in three different units (ms / seconds / minutes), with no
 * single place to see the relationships between them. The one relationship a call-site comment
 * previously stated wrong: {@code AgentStepRunner}'s virtual-tool-wait javadoc claimed its 30s
 * default "aligns with" the approval gate's 300s timeout — the two differ by 10x, and deliberately
 * so (see {@link #virtualToolMs()}).
 *
 * <p>These are policy constants, not configuration: there is no {@code
 * io.justsearch.configuration.resolved.ResolvedConfig.Agent} field for any of them today, and
 * inventing config keys for them is out of scope of this centralisation. Two already carried a
 * system-property override before this class existed ({@code justsearch.agent.budgetGateTimeoutSec},
 * {@code justsearch.agent.contextGateTimeoutSec}); both are preserved verbatim — same property name,
 * same SECONDS unit, same "0 falls through immediately" behaviour — and read fresh on every call so
 * a test can flip them mid-run rather than freezing a value at class-init.
 *
 * <p>The class and most accessors are package-private, matching the rest of this package's helper
 * classes. {@link #toolFetchMs()} and {@link #fileOpConflictToleranceMs()} are {@code public}
 * (matching {@code ToolResultCarrier}'s precedent for the same boundary) because their consumers —
 * {@code ReadDocumentTool} and {@code FileOperationsTool} — live one package over, in {@code
 * io.justsearch.agent.tools}; a package-private accessor those callers cannot reach would be a
 * dead declaration the moment that workstream lands.
 */
public final class AgentTimeouts {

  private AgentTimeouts() {}

  /** {@code AgentLlmCaller} — how long the agent loop blocks on one LLM round-trip. */
  static long llmCallMs() {
    return TimeUnit.MINUTES.toMillis(5);
  }

  /**
   * Tempdoc 577 §2.14 Root I (#13) — {@code AgentSessionRegistry#attachToRun} blocks while
   * streaming a live run; this caps how long it waits for a terminal event before releasing (a
   * safety net, not the normal exit path).
   */
  static long sessionAttachMs() {
    return TimeUnit.MINUTES.toMillis(30);
  }

  /** {@code AgentToolDispatcher} — how long an approval gate waits for the human's decision. */
  static long approvalGateMs() {
    return TimeUnit.SECONDS.toMillis(300);
  }

  /**
   * Tempdoc 508 §11.5 / §13.5 Phase B — how long {@code AgentStepRunner#handleVirtualToolCall}
   * waits for the FE to resolve a virtual (FE-executed) tool call. Deliberately much SHORTER than
   * {@link #approvalGateMs()}: an FE that never answers a virtual tool must not hold the agent
   * loop for the full five-minute approval window.
   */
  static long virtualToolMs() {
    return TimeUnit.SECONDS.toMillis(30);
  }

  /**
   * Tempdoc 577 §2.14 Root II — how long the context gate holds an interactive run awaiting the
   * human's decision before falling back to CONTINUE (proceed with the large prompt — the safe,
   * non-destructive default, so a watcherless run is never silently truncated). Long enough to
   * notice and click; short enough that an unattended run still completes on its own.
   * System-property override ({@code justsearch.agent.contextGateTimeoutSec}, SECONDS) exists for
   * tests — 0 makes the gate fall through immediately.
   */
  static long contextGateMs() {
    return TimeUnit.SECONDS.toMillis(Long.getLong("justsearch.agent.contextGateTimeoutSec", 120L));
  }

  /**
   * Tempdoc 577 §2.12 Move 2 — how long the budget gate holds an interactive run awaiting the
   * human's decision before falling back to the legacy finalize-else-error behavior. Same "long
   * enough to notice and click, short enough that an unattended run completes" rationale as {@link
   * #contextGateMs()}. System-property override ({@code justsearch.agent.budgetGateTimeoutSec},
   * SECONDS) exists for tests — 0 makes the gate fall through immediately (exactly the legacy
   * behavior).
   */
  static long budgetGateMs() {
    return TimeUnit.SECONDS.toMillis(Long.getLong("justsearch.agent.budgetGateTimeoutSec", 120L));
  }

  /**
   * {@code AgentCitationResolver} — how long the agent loop blocks on the citation matcher before
   * citing sources without inline marks.
   */
  static long citationMatchMs() {
    return 4000L;
  }

  /**
   * How long a tool's synchronous Worker fetch may block the agent loop thread before it is
   * abandoned — see {@link #call(String, Callable)}. Applied by {@code ReadDocumentTool}'s slice
   * fetch, {@code SearchTool}'s search, {@code BrowseTool}'s folder/file listings and {@code
   * IngestTool}'s scan-root dispatch. {@code public}: those consumers live in {@code
   * io.justsearch.agent.tools}.
   */
  public static long toolFetchMs() {
    return TimeUnit.SECONDS.toMillis(15);
  }

  /**
   * Tempdoc 577 §2.14 Root III (#16) — {@code FileOperationsTool}'s conflict-detection tolerance:
   * the slack between an op's filesystem write and the log's {@code Instant.now()} record, so the
   * agent's own write never reads as a user since-edit. {@code public}: the consumer lives in
   * {@code io.justsearch.agent.tools}.
   */
  public static long fileOpConflictToleranceMs() {
    return TimeUnit.SECONDS.toMillis(2);
  }

  /**
   * The budget for {@code IngestTool}'s directory dispatch, which is NOT a fetch: {@code
   * KnowledgeHttpApiAdapter.scanRoot} blocks on a server-streaming {@code ScanRoot} RPC until the
   * Worker's terminal frame, i.e. until an entire directory tree has been walked. Applying {@link
   * #toolFetchMs()} there would abandon a legitimate large-root ingest after 15s while the scan kept
   * running Worker-side — a worse failure than the unbounded block it replaces, so the guard is
   * kept and only the number differs.
   */
  public static long toolScanMs() {
    return TimeUnit.MINUTES.toMillis(10);
  }

  /**
   * Tempdoc 877 §2.8 — run one synchronous Worker-backed tool fetch under {@link #toolFetchMs()},
   * abandoning it on timeout.
   *
   * <p>Three of the four tool fetches had NO timeout: an unresponsive Worker blocked the agent loop
   * thread indefinitely while the fourth ({@code ReadDocumentTool}) degraded in 15 seconds. One
   * question, two answers — so this is the one answer.
   *
   * <p>The callable runs on its own VIRTUAL thread (Java 25 toolchain), which is why there is no
   * pool to size or shut down: an abandoned virtual thread costs a few hundred bytes and unmounts
   * itself when its blocking call finally returns. A {@link TimeoutException} is thrown so {@code
   * AgentToolErrors} classifies the failure as a RETRYABLE timeout rather than an internal fault.
   */
  public static <T> T call(String label, Callable<T> work) throws Exception {
    return call(label, toolFetchMs(), work);
  }

  /** {@link #call(String, Callable)} with an explicit budget ({@link #toolScanMs()}, or a test's). */
  public static <T> T call(String label, long timeoutMs, Callable<T> work) throws Exception {
    CompletableFuture<T> outcome = new CompletableFuture<>();
    Thread worker =
        Thread.ofVirtual()
            .name("agent-tool-fetch-" + label)
            .start(
                () -> {
                  try {
                    outcome.complete(work.call());
                  } catch (Throwable t) {
                    outcome.completeExceptionally(t);
                  }
                });
    try {
      return outcome.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      // Best-effort nudge; the thread is abandoned either way rather than joined, because joining
      // an unresponsive Worker call is the very block this exists to end.
      worker.interrupt();
      throw new TimeoutException(
          label + " did not respond within " + timeoutMs + "ms; the Worker may be unavailable");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception checked) {
        throw checked;
      }
      throw e;
    }
  }
}

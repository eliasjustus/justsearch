/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.agenthistory;

import io.justsearch.app.services.worker.RemoteKnowledgeClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 585 §D Phase 4 (D4a) — "search-your-own-agent-history", the ingestion half: when an agent
 * run finishes, synthesise a markdown transcript (the final answer + what the agent found) and index
 * it into a dedicated {@code agent-history} Lucene collection, so the user can later search their
 * agent history with the SAME hybrid retrieval the app uses for documents. Uniquely enabled by
 * 585's durable-runs × the product's search core (§D.4) — the product searching its own assistant's
 * memory.
 *
 * <p>Wiring: a terminal-run listener on {@code RunEventStore.addEventListener} (registered in
 * {@code HeadAssembly}). The {@code done}/{@code error} record carries the run's final answer +
 * grounding sources, so no full-history replay is needed. The transcript is written atomically
 * (temp + {@code ATOMIC_MOVE}) and indexed via the explicit-collection ingest API
 * ({@link RemoteKnowledgeClient#submitBatch(List, boolean, String)} with {@code "agent-history"}) —
 * which sidesteps the YAML-only watched-collection config (the transcript does not need a watched
 * root). The search-side scoping (default-exclude + an "Agent history" scope) is the D4b half.
 *
 * <p>Off the hot path: the listener fires synchronously on the terminal-event append, so the
 * write + the blocking ingest RPC run on a daemon single-thread executor — a slow/hung worker can
 * never stall the agent loop's final emit. Fully fail-soft: any error is logged, never propagated.
 */
public final class AgentHistoryIndexer {

  /** The reserved collection tag for indexed agent transcripts (shared with the D4b search scope). */
  public static final String COLLECTION = "agent-history";

  private static final Logger LOG = LoggerFactory.getLogger(AgentHistoryIndexer.class);

  private final Path historyDir;
  private final Supplier<RemoteKnowledgeClient> clientSupplier;
  private final ExecutorService executor;

  /**
   * Wire a terminal-run transcript indexer onto a {@code RunEventStore} listener registrar (the
   * one-line composition seam, mirroring {@code AgentDispositionWiring.register}).
   */
  public static AgentHistoryIndexer register(
      java.util.function.Consumer<java.util.function.BiConsumer<String, Map<String, Object>>>
          addEventListener,
      Path historyDir,
      Supplier<RemoteKnowledgeClient> clientSupplier) {
    var indexer = new AgentHistoryIndexer(historyDir, clientSupplier);
    addEventListener.accept(indexer::onEvent);
    return indexer;
  }

  public AgentHistoryIndexer(Path historyDir, Supplier<RemoteKnowledgeClient> clientSupplier) {
    this.historyDir = historyDir;
    this.clientSupplier = clientSupplier;
    this.executor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "agent-history-indexer");
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * The {@code RunEventStore} listener: on a terminal {@code done}/{@code error} record, schedule the
   * transcript write + ingest off the hot path. Non-terminal events are ignored.
   */
  @SuppressWarnings("unchecked")
  public void onEvent(String sessionId, Map<String, Object> record) {
    if (record == null) {
      return;
    }
    String eventType = String.valueOf(record.get("eventType"));
    if (!"done".equals(eventType) && !"error".equals(eventType)) {
      return;
    }
    Map<String, Object> payload =
        record.get("payload") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
    boolean errored = "error".equals(eventType);
    executor.execute(() -> writeAndIndex(sessionId, payload, errored));
  }

  /**
   * Tempdoc 629 (#E faithful import) — re-index a RESTORED run's transcript from its (already-persisted)
   * events. Faithful backup-import does not fire listeners (replaying historical events must not
   * re-trigger live projectors as if they were happening now), so a restored run never reaches the live
   * {@link #onEvent} path and would be viewable-but-not-searchable. This replays the restored events
   * through {@link #onEvent}, which self-filters to the terminal {@code done}/{@code error} event and
   * indexes its transcript via the SAME off-the-hot-path, fail-soft route live runs use. Import is
   * skip-existing-by-session, so only NEW runs reach here — the re-index can never duplicate.
   */
  @SuppressWarnings("unchecked")
  public void reindexRestoredRun(String sessionId, List<?> events) {
    if (events == null) {
      return;
    }
    for (Object ev : events) {
      if (ev instanceof Map<?, ?>) {
        onEvent(sessionId, (Map<String, Object>) ev);
      }
    }
  }

  private void writeAndIndex(String sessionId, Map<String, Object> payload, boolean errored) {
    try {
      Files.createDirectories(historyDir);
      Path target = historyDir.resolve(sessionId + ".md");
      atomicWrite(target, renderTranscript(sessionId, payload, errored));
      RemoteKnowledgeClient client = clientSupplier.get();
      if (client != null) {
        client.submitBatch(List.of(target), true, COLLECTION);
      }
    } catch (Exception e) {
      // Fail-soft — a failed history index must never affect the run or the user.
      LOG.warn("Failed to index agent-history transcript for session {}", sessionId, e);
    }
  }

  /** Build the searchable markdown: the final answer + what the agent found (its grounding sources). */
  @SuppressWarnings("unchecked")
  static String renderTranscript(String sessionId, Map<String, Object> payload, boolean errored) {
    StringBuilder md = new StringBuilder();
    if (errored) {
      md.append("# Agent run (error)\n\n");
      md.append(str(payload.get("error"))).append("\n");
      return md.toString();
    }
    String answer = str(payload.get("finalResponse"));
    md.append("# Agent run\n\n");
    md.append(answer).append("\n");

    Object sourcesObj = payload.get("sources");
    if (sourcesObj instanceof List<?> sources && !sources.isEmpty()) {
      md.append("\n## What the agent found\n\n");
      for (Object s : sources) {
        if (s instanceof Map<?, ?> src) {
          Map<String, Object> m = (Map<String, Object>) src;
          String title = str(m.get("title"));
          String path = str(m.get("path"));
          String excerpt = str(m.get("excerpt"));
          md.append("- **").append(title.isBlank() ? path : title).append("**");
          if (!path.isBlank()) {
            md.append(" (").append(path).append(")");
          }
          if (!excerpt.isBlank()) {
            md.append(": ").append(excerpt);
          }
          md.append("\n");
        }
      }
    }

    md.append("\n---\n");
    md.append(
        String.format(
            Locale.ROOT,
            "Iterations: %s · Tool calls: %s · Tokens: %s\n",
            str(payload.get("iterationsUsed")),
            str(payload.get("toolCallsExecuted")),
            str(payload.get("totalTokensUsed"))));
    return md.toString();
  }

  private static String str(Object o) {
    return o == null ? "" : String.valueOf(o);
  }

  /** Atomic write (temp + ATOMIC_MOVE) — the FileOperationLog pattern. */
  private static void atomicWrite(Path target, String content) throws IOException {
    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
    Files.writeString(tmp, content, StandardCharsets.UTF_8);
    try {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}

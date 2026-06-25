package io.justsearch.app.services.agenthistory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tempdoc 585 §D Phase 4 (D4a) — the agent-history transcript rendering + 629 restored-run re-index. */
class AgentHistoryIndexerTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("a finished run renders the answer + what it found (grounding sources) + a footer")
  void doneTranscriptCarriesAnswerAndSources() {
    Map<String, Object> payload =
        Map.of(
            "finalResponse", "Your Q3 taxes are in tax-2024.pdf.",
            "iterationsUsed", 3,
            "toolCallsExecuted", 2,
            "totalTokensUsed", 1500,
            "sources",
            List.of(
                Map.of(
                    "title", "Q3 Tax Summary",
                    "path", "/docs/tax-2024.pdf",
                    "excerpt", "Total owed: $4,200")));

    String md = AgentHistoryIndexer.renderTranscript("sess-1", payload, false);

    // The answer is searchable body text.
    assertTrue(md.contains("Your Q3 taxes are in tax-2024.pdf."), "answer present");
    // "What the agent found" — the grounding source title + excerpt are indexed too.
    assertTrue(md.contains("What the agent found"), "sources section present");
    assertTrue(md.contains("Q3 Tax Summary"), "source title present");
    assertTrue(md.contains("Total owed: $4,200"), "source excerpt present");
    // The run footer.
    assertTrue(md.contains("Iterations: 3"), "iteration count present");
    assertTrue(md.contains("Tool calls: 2"), "tool-call count present");
  }

  @Test
  @DisplayName("an errored run renders the error, not an empty answer")
  void errorTranscriptCarriesTheError() {
    Map<String, Object> payload = Map.of("error", "model timed out");
    String md = AgentHistoryIndexer.renderTranscript("sess-2", payload, true);
    assertTrue(md.contains("error"), "marked as error");
    assertTrue(md.contains("model timed out"), "error message present");
    assertFalse(md.contains("What the agent found"), "no sources section for an error");
  }

  @Test
  @DisplayName("629: re-indexing a RESTORED run writes its transcript from the terminal event")
  void reindexRestoredRunWritesTranscriptFromTerminalEvent() throws Exception {
    Path historyDir = tempDir.resolve("agent-history");
    // null client → the .md is still written; submitBatch (the only client-gated step) is skipped.
    var indexer = new AgentHistoryIndexer(historyDir, () -> null);
    var events =
        List.of(
            Map.of(
                "eventType", "session_started",
                "payload", Map.of("sessionId", "sess-restored")),
            Map.of(
                "eventType",
                "done",
                "payload",
                Map.of(
                    "finalResponse", "RESTORED answer marker ZQXRESTORE.",
                    "iterationsUsed", 1,
                    "toolCallsExecuted", 0,
                    "totalTokensUsed", 10,
                    "sources",
                    List.of(
                        Map.of("title", "Doc A", "path", "/d/a.md", "excerpt", "found-snippet")))));

    indexer.reindexRestoredRun("sess-restored", events);

    Path md = historyDir.resolve("sess-restored.md");
    for (int i = 0; i < 150 && !Files.exists(md); i++) {
      Thread.sleep(20); // the write runs on the indexer's daemon executor (off the hot path)
    }
    assertTrue(Files.exists(md), "restored run's transcript .md was written");
    String content = Files.readString(md);
    assertTrue(content.contains("RESTORED answer marker ZQXRESTORE."), "the answer is indexed (searchable)");
    assertTrue(content.contains("What the agent found"), "grounding sources are indexed");
    assertTrue(content.contains("Doc A"), "source title indexed");
  }

  @Test
  @DisplayName("629: a restored run with no terminal event indexes nothing (the self-filter holds)")
  void reindexRestoredRunWithNoTerminalEventWritesNothing() throws Exception {
    Path historyDir = tempDir.resolve("agent-history-2");
    var indexer = new AgentHistoryIndexer(historyDir, () -> null);
    indexer.reindexRestoredRun(
        "sess-partial", List.of(Map.of("eventType", "session_started", "payload", Map.of())));
    Thread.sleep(100); // give the (would-be) executor time; a terminal-less run must write nothing
    assertFalse(
        Files.exists(historyDir.resolve("sess-partial.md")), "no terminal event → no transcript");
  }
}

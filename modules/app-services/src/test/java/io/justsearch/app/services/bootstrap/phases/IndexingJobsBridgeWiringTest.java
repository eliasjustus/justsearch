package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.indexing.IndexingJobView;
import io.justsearch.app.observability.ledger.ActionEvent;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 550 thesis I — the indexing-jobs bridge's terminal-outcome translator. The decision that
 * matters (which job transitions become a {@code kind=index} action-event) is the pure
 * {@link IndexingJobsBridgeWiring#terminalIndexEvent} function: TERMINAL states (DONE/FAILED) emit
 * exactly one Index event; in-flight states (PENDING/PROCESSING) emit none — that live state is the
 * rail's Resource projection, and per-transition emission would flood the bounded action-event ring.
 */
@DisplayName("IndexingJobsBridgeWiring.terminalIndexEvent")
final class IndexingJobsBridgeWiringTest {

  private static IndexingJobView job(String pathHash, String state, int attempts, String err) {
    // IndexingJobView(pathHash, state, attempts, lastUpdatedMs, errorMessage, retryAfterMs, collection)
    return new IndexingJobView(pathHash, state, attempts, 1_700_000_000_000L, err, 0L, "default");
  }

  @Test
  @DisplayName("DONE emits one index event (originator=system) with the terminal state")
  void doneEmits() {
    Optional<ActionEvent> evt = IndexingJobsBridgeWiring.terminalIndexEvent(job("h1", "DONE", 0, ""));
    assertTrue(evt.isPresent());
    ActionEvent.Index idx = (ActionEvent.Index) evt.get();
    assertEquals("system", idx.originator());
    assertEquals("WORKER_INDEXER", idx.transport());
    assertEquals("h1", idx.pathHash());
    assertEquals("DONE", idx.state());
  }

  @Test
  @DisplayName("FAILED emits one index event carrying the failure detail")
  void failedEmits() {
    Optional<ActionEvent> evt =
        IndexingJobsBridgeWiring.terminalIndexEvent(job("h2", "FAILED", 3, "boom"));
    assertTrue(evt.isPresent());
    ActionEvent.Index idx = (ActionEvent.Index) evt.get();
    assertEquals("FAILED", idx.state());
    assertEquals(3, idx.attempts());
    assertEquals("boom", idx.errorMessage());
  }

  @Test
  @DisplayName("PENDING and PROCESSING (in-flight) emit NO ledger event")
  void inFlightEmitsNothing() {
    assertTrue(IndexingJobsBridgeWiring.terminalIndexEvent(job("h3", "PENDING", 0, "")).isEmpty());
    assertTrue(
        IndexingJobsBridgeWiring.terminalIndexEvent(job("h4", "PROCESSING", 0, "")).isEmpty());
  }

  @Test
  @DisplayName("state is matched case-insensitively; null/unknown rows are safely ignored")
  void caseInsensitiveAndDefensive() {
    assertTrue(IndexingJobsBridgeWiring.terminalIndexEvent(job("h5", "done", 0, "")).isPresent());
    assertTrue(IndexingJobsBridgeWiring.terminalIndexEvent(job("h6", "queued", 0, "")).isEmpty());
    assertTrue(IndexingJobsBridgeWiring.terminalIndexEvent(null).isEmpty());
  }
}

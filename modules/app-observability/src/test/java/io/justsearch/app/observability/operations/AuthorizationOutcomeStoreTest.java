package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Tempdoc 550 Outcome face: the gate-decision ring buffer (mirrors OperationHistoryStore). */
class AuthorizationOutcomeStoreTest {

  private static AuthorizationOutcomeEntry entry(int i) {
    return new AuthorizationOutcomeEntry(
        "core.op-" + i,
        TransportTag.LLM_EMISSION,
        SourceTier.UNTRUSTED,
        RiskTier.MEDIUM,
        GateBehavior.TYPED_CONFIRM,
        AuthorizationDisposition.GATED,
        Instant.ofEpochSecond(i));
  }

  @Test
  void appendsAndReturnsChronological() {
    AuthorizationOutcomeStore store = new AuthorizationOutcomeStore();
    store.append(entry(1));
    store.append(entry(2));
    var recent = store.recent();
    assertEquals(2, recent.size());
    assertEquals("core.op-1", recent.get(0).operationId());
    assertEquals("core.op-2", recent.get(1).operationId(), "most recent last");
  }

  @Test
  void evictsOldestPastCapacity() {
    AuthorizationOutcomeStore store = new AuthorizationOutcomeStore(2);
    store.append(entry(1));
    store.append(entry(2));
    store.append(entry(3));
    var recent = store.recent();
    assertEquals(2, recent.size(), "bounded ring buffer");
    assertEquals("core.op-2", recent.get(0).operationId(), "oldest evicted");
    assertEquals("core.op-3", recent.get(1).operationId());
  }
}

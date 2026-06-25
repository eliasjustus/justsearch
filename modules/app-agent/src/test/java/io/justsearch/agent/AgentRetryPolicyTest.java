package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.agent.api.RetryAction;
import org.junit.jupiter.api.Test;

class AgentRetryPolicyTest {

  @Test
  void llmTransient_hasExpectedRetryProfile() {
    var decision = AgentRetryPolicy.forCode(AgentErrorCode.LLM_TRANSIENT);
    assertEquals(RetryAction.RETRY, decision.action());
    assertEquals(2, decision.maxRetries());

    long firstDelay = decision.delayMsForAttempt(1);
    long secondDelay = decision.delayMsForAttempt(2);

    assertTrue(firstDelay >= 200 && firstDelay <= 300, "first retry delay should include jitter");
    assertTrue(secondDelay >= 600 && secondDelay <= 900, "second retry delay should include jitter");
  }

  @Test
  void toolTransientReadOnly_hasExpectedRetryProfile() {
    var decision = AgentRetryPolicy.forCode(AgentErrorCode.TOOL_TRANSIENT_READ_ONLY);
    assertEquals(RetryAction.RETRY, decision.action());
    assertEquals(1, decision.maxRetries());

    long delay = decision.delayMsForAttempt(1);
    assertTrue(delay >= 120 && delay <= 180, "tool retry delay should include jitter");
  }

  @Test
  void emptyResponse_hasSingleDeterministicRetry() {
    var decision = AgentRetryPolicy.forCode(AgentErrorCode.EMPTY_RESPONSE);
    assertEquals(RetryAction.RETRY, decision.action());
    assertEquals(1, decision.maxRetries());
    assertEquals(250L, decision.delayMsForAttempt(1));
    assertEquals(250L, decision.delayMsForAttempt(2));
  }

  @Test
  void nonRetryableCodes_abortImmediately() {
    AgentErrorCode[] abortCodes = {
      AgentErrorCode.BUDGET_EXHAUSTED,
      AgentErrorCode.POLICY_DENIED,
      AgentErrorCode.TOOL_CONTRACT,
      AgentErrorCode.UNKNOWN_TOOL,
      AgentErrorCode.CANCELLED,
      AgentErrorCode.INTERNAL_ERROR,
      AgentErrorCode.NO_TOOLS,
      AgentErrorCode.UNAVAILABLE,
      AgentErrorCode.UNSUPPORTED_RESUME_STATE
    };

    for (AgentErrorCode code : abortCodes) {
      var decision = AgentRetryPolicy.forCode(code);
      assertEquals(RetryAction.ABORT, decision.action(), "unexpected action for " + code);
      assertEquals(0, decision.maxRetries(), "unexpected retry count for " + code);
      assertEquals(0L, decision.delayMsForAttempt(1), "delay should be zero for " + code);
    }
  }
}

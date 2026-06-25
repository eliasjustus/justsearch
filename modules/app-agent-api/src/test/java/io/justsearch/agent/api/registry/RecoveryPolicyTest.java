package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ResolutionRecoveryPolicy (tempdoc 499 §3)")
final class RecoveryPolicyTest {

  private static final String ENTRY = "test-entry";
  private static final ResolutionResult.Suggestion<String> SUGGESTION =
      new ResolutionResult.Suggestion<>("suggested", "core.suggested", 0.8, "dl=1");

  private static final ResolutionResult<String> RESOLVED =
      new ResolutionResult.Resolved<>(ENTRY);
  private static final ResolutionResult<String> REDIRECTED =
      new ResolutionResult.Redirected<>(ENTRY, "core.old", ResolutionResult.RedirectReason.ALIAS);
  private static final ResolutionResult<String> UNRESOLVED_WITH_ALTS =
      new ResolutionResult.Unresolved<>("core.typo",
          new ResolutionResult.UnresolvedDiagnosis(ResolutionResult.FailureMode.TYPO, "not found"),
          List.of(SUGGESTION));
  private static final ResolutionResult<String> UNRESOLVED_NO_ALTS =
      new ResolutionResult.Unresolved<>("core.ghost",
          new ResolutionResult.UnresolvedDiagnosis(ResolutionResult.FailureMode.UNKNOWN, "not found"),
          List.of());

  @Nested
  @DisplayName("strict()")
  class Strict {
    private final ResolutionRecoveryPolicy<String> policy = ResolutionRecoveryPolicy.strict();

    @Test void resolved_proceeds() {
      assertInstanceOf(RecoveryAction.Proceed.class, policy.decide(RESOLVED));
    }

    @Test void redirected_aborts() {
      assertInstanceOf(RecoveryAction.Abort.class, policy.decide(REDIRECTED));
    }

    @Test void unresolved_aborts() {
      assertInstanceOf(RecoveryAction.Abort.class, policy.decide(UNRESOLVED_WITH_ALTS));
    }
  }

  @Nested
  @DisplayName("mcp()")
  class Mcp {
    private final ResolutionRecoveryPolicy<String> policy = ResolutionRecoveryPolicy.mcp();

    @Test void resolved_proceeds() {
      assertInstanceOf(RecoveryAction.Proceed.class, policy.decide(RESOLVED));
    }

    @Test void redirected_proceeds() {
      assertInstanceOf(RecoveryAction.Proceed.class, policy.decide(REDIRECTED));
    }

    @Test void unresolved_with_alts_suggests() {
      var action = policy.decide(UNRESOLVED_WITH_ALTS);
      assertInstanceOf(RecoveryAction.SuggestToUser.class, action);
      var suggest = (RecoveryAction.SuggestToUser<String>) action;
      assertEquals(1, suggest.alternatives().size());
    }

    @Test void unresolved_no_alts_aborts() {
      assertInstanceOf(RecoveryAction.Abort.class, policy.decide(UNRESOLVED_NO_ALTS));
    }
  }

  @Nested
  @DisplayName("agentTool()")
  class AgentTool {
    private final ResolutionRecoveryPolicy<String> policy = ResolutionRecoveryPolicy.agentTool();

    @Test void resolved_proceeds() {
      assertInstanceOf(RecoveryAction.Proceed.class, policy.decide(RESOLVED));
    }

    @Test void redirected_proceeds() {
      assertInstanceOf(RecoveryAction.Proceed.class, policy.decide(REDIRECTED));
    }

    @Test void unresolved_with_alts_injects_hint() {
      var action = policy.decide(UNRESOLVED_WITH_ALTS);
      assertInstanceOf(RecoveryAction.InjectHint.class, action);
      var hint = (RecoveryAction.InjectHint<String>) action;
      assertTrue(hint.hintMessage().contains("core.suggested"));
      assertTrue(hint.hintMessage().contains("Did you mean"));
    }

    @Test void unresolved_no_alts_aborts() {
      assertInstanceOf(RecoveryAction.Abort.class, policy.decide(UNRESOLVED_NO_ALTS));
    }
  }
}

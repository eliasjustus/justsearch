package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import org.junit.jupiter.api.Test;

/**
 * Per tempdoc 487 §4.4: explicit per-cell coverage of the 9-cell
 * {@code (SourceTier × RiskTier) → GateBehavior} matrix.
 *
 * <p>Cell values are the slice-implementation-time inputs to Pass-8 review. Tests
 * exercise the full matrix so any future refactor that drifts a cell value fails
 * loudly. Aligns with the slice-execution discipline "audit-driven fixes need a
 * runnable test, not just a passing audit."
 */
final class CoreTrustEvaluatorTest {

  private final CoreTrustEvaluator evaluator = new CoreTrustEvaluator();

  @Test
  void trustedLow() {
    assertEquals(GateBehavior.AUTO, evaluator.evaluate(SourceTier.TRUSTED, RiskTier.LOW));
  }

  @Test
  void trustedMedium() {
    assertEquals(GateBehavior.AUTO, evaluator.evaluate(SourceTier.TRUSTED, RiskTier.MEDIUM));
  }

  @Test
  void trustedHigh() {
    assertEquals(
        GateBehavior.TYPED_CONFIRM, evaluator.evaluate(SourceTier.TRUSTED, RiskTier.HIGH));
  }

  @Test
  void mediumLow() {
    assertEquals(GateBehavior.AUTO, evaluator.evaluate(SourceTier.MEDIUM, RiskTier.LOW));
  }

  @Test
  void mediumMedium() {
    assertEquals(
        GateBehavior.INLINE_CONFIRM, evaluator.evaluate(SourceTier.MEDIUM, RiskTier.MEDIUM));
  }

  @Test
  void mediumHigh() {
    assertEquals(
        GateBehavior.TYPED_CONFIRM, evaluator.evaluate(SourceTier.MEDIUM, RiskTier.HIGH));
  }

  @Test
  void untrustedLow() {
    assertEquals(GateBehavior.AUTO, evaluator.evaluate(SourceTier.UNTRUSTED, RiskTier.LOW));
  }

  @Test
  void untrustedMedium() {
    assertEquals(
        GateBehavior.TYPED_CONFIRM, evaluator.evaluate(SourceTier.UNTRUSTED, RiskTier.MEDIUM));
  }

  @Test
  void untrustedHigh() {
    assertEquals(
        GateBehavior.TYPED_CONFIRM, evaluator.evaluate(SourceTier.UNTRUSTED, RiskTier.HIGH));
  }
}

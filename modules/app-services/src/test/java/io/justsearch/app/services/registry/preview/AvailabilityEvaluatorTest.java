package io.justsearch.app.services.registry.preview;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.AvailabilityExpression;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Unit coverage for the Preview-face {@link AvailabilityEvaluator} (tempdoc 550 F2). */
final class AvailabilityEvaluatorTest {

  private static final Predicate<String> NONE_FIRING = id -> false;

  private static Predicate<String> firing(String... ids) {
    Set<String> set = Set.of(ids);
    return set::contains;
  }

  @Test
  void alwaysIsTrue() {
    assertTrue(AvailabilityEvaluator.evaluate(new AvailabilityExpression.Always(), NONE_FIRING));
  }

  @Test
  void conditionMatchesReflectsFiringState() {
    AvailabilityExpression expr = new AvailabilityExpression.ConditionMatches("index.unavailable");
    assertTrue(AvailabilityEvaluator.evaluate(expr, firing("index.unavailable")));
    assertFalse(AvailabilityEvaluator.evaluate(expr, NONE_FIRING));
  }

  @Test
  void allOfIsConjunctionAndEmptyIsVacuouslyTrue() {
    assertTrue(
        AvailabilityEvaluator.evaluate(new AvailabilityExpression.AllOf(List.of()), NONE_FIRING));
    AvailabilityExpression both =
        new AvailabilityExpression.AllOf(
            List.of(
                new AvailabilityExpression.ConditionMatches("a"),
                new AvailabilityExpression.ConditionMatches("b")));
    assertTrue(AvailabilityEvaluator.evaluate(both, firing("a", "b")));
    assertFalse(AvailabilityEvaluator.evaluate(both, firing("a")));
  }

  @Test
  void anyOfIsDisjunctionAndEmptyIsVacuouslyFalse() {
    assertFalse(
        AvailabilityEvaluator.evaluate(new AvailabilityExpression.AnyOf(List.of()), NONE_FIRING));
    AvailabilityExpression either =
        new AvailabilityExpression.AnyOf(
            List.of(
                new AvailabilityExpression.ConditionMatches("a"),
                new AvailabilityExpression.ConditionMatches("b")));
    assertTrue(AvailabilityEvaluator.evaluate(either, firing("b")));
    assertFalse(AvailabilityEvaluator.evaluate(either, NONE_FIRING));
  }

  @Test
  void notNegates() {
    AvailabilityExpression expr =
        new AvailabilityExpression.Not(new AvailabilityExpression.ConditionMatches("blocked"));
    assertTrue(AvailabilityEvaluator.evaluate(expr, NONE_FIRING));
    assertFalse(AvailabilityEvaluator.evaluate(expr, firing("blocked")));
  }

  @Test
  void nestedComposition() {
    // available = AllOf( Not(ConditionMatches "down"), AnyOf(ConditionMatches "ready") )
    AvailabilityExpression expr =
        new AvailabilityExpression.AllOf(
            List.of(
                new AvailabilityExpression.Not(new AvailabilityExpression.ConditionMatches("down")),
                new AvailabilityExpression.AnyOf(
                    List.of(new AvailabilityExpression.ConditionMatches("ready")))));
    assertTrue(AvailabilityEvaluator.evaluate(expr, firing("ready")));
    assertFalse(AvailabilityEvaluator.evaluate(expr, firing("ready", "down")));
    assertFalse(AvailabilityEvaluator.evaluate(expr, NONE_FIRING));
  }
}

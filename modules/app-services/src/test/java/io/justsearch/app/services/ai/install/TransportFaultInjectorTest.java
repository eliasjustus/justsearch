package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.EnvRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fault injector exists so a sandbox round can verify the retry policy by TRIGGERING transport
 * failures instead of hoping for them. The load-bearing case here is the refusal: a shipped build
 * must not be able to break its own downloads because a stray property survived into it.
 */
final class TransportFaultInjectorTest {

  private String savedProd;

  @BeforeEach
  void captureProdFlag() {
    savedProd = System.getProperty(EnvRegistry.PROD_MODE.sysProp());
    TransportFaultInjector.resetWarningState();
  }

  @AfterEach
  void restoreProdFlag() {
    if (savedProd == null) {
      System.clearProperty(EnvRegistry.PROD_MODE.sysProp());
    } else {
      System.setProperty(EnvRegistry.PROD_MODE.sysProp(), savedProd);
    }
    TransportFaultInjector.resetWarningState();
  }

  @Test
  void unsetOrZeroPercentInjectsNothing() {
    assertEquals(0, TransportFaultInjector.parsePct(null));
    assertEquals(0, TransportFaultInjector.parsePct("  "));
    assertFalse(TransportFaultInjector.shouldFailAttempt(0, 0));
  }

  @Test
  void malformedPercentIsIgnoredRatherThanBreakingDownloads() {
    assertEquals(0, TransportFaultInjector.parsePct("forty"));
    assertEquals(100, TransportFaultInjector.parsePct("400"), "clamped, not rejected");
    assertEquals(0, TransportFaultInjector.parsePct("-5"));
  }

  @Test
  void configuredPercentFailsThatFractionOfAttempts() {
    System.clearProperty(EnvRegistry.PROD_MODE.sysProp());
    // A machine with JUSTSEARCH_PROD exported would (correctly) refuse injection instead.
    Assumptions.assumeFalse(EnvRegistry.PROD_MODE.getBoolean(false), "not a production environment");

    assertTrue(TransportFaultInjector.shouldFailAttempt(40, 0), "a draw under the pct fails");
    assertTrue(TransportFaultInjector.shouldFailAttempt(40, 39));
    assertFalse(TransportFaultInjector.shouldFailAttempt(40, 40), "a draw at/over the pct passes");
    assertFalse(TransportFaultInjector.shouldFailAttempt(40, 99));
  }

  /** The refusal: prod=true makes the injector inert no matter what the property says. */
  @Test
  void injectionIsRefusedInProductionEvenAtOneHundredPercent() {
    System.setProperty(EnvRegistry.PROD_MODE.sysProp(), "true");

    for (int roll = 0; roll < 100; roll++) {
      assertFalse(
          TransportFaultInjector.shouldFailAttempt(100, roll),
          "fault injection must be inert under prod=true (roll " + roll + ")");
    }
  }

  @Test
  void theSyntheticFailureLooksLikeTheOneRound16Measured() {
    TransportFailure failure = TransportFaultInjector.syntheticFailure();

    assertEquals("curl exit 52", failure.code());
    assertTrue(failure.retryable(), "the injected failure must exercise the retry path");
    assertTrue(failure.detail().contains(TransportFaultInjector.PCT_PROPERTY), failure.detail());
  }
}

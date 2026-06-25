package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DownloadExecutorTest {

  @Test
  void bitsCountExpressionHandlesUInt64UnknownSentinelBeforeSignedCast() {
    String expression = DownloadExecutor.bitsCountExpression("$job.BytesTotal");

    assertTrue(expression.contains("[UInt64]$v"));
    assertTrue(expression.contains("[UInt64]::MaxValue"));
    assertTrue(expression.contains("[Int64]::MaxValue"));
    assertFalse(
        expression.contains("[Int64]$job.BytesTotal"),
        "the BITS field must not be cast directly to Int64 because unknown totals are UInt64 max");
  }
}

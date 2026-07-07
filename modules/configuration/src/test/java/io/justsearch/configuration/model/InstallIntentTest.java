package io.justsearch.configuration.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class InstallIntentTest {

  @Test
  void nullValue_fallsBackToDefault() {
    assertSame(InstallIntent.DEFAULT, InstallIntent.fromConfig(null));
  }

  @Test
  void blankValue_fallsBackToDefault() {
    assertSame(InstallIntent.DEFAULT, InstallIntent.fromConfig("   "));
  }

  @Test
  void recognizedKebabCase_resolvesExactly() {
    assertSame(InstallIntent.HEADLESS, InstallIntent.fromConfig("headless"));
    assertSame(InstallIntent.MCP_LITE, InstallIntent.fromConfig("mcp-lite"));
    assertSame(InstallIntent.FULL_DESKTOP, InstallIntent.fromConfig("full-desktop"));
  }

  @Test
  void recognizedValue_tolerantOfCaseAndUnderscores() {
    assertSame(InstallIntent.MCP_LITE, InstallIntent.fromConfig("MCP_LITE"));
    assertSame(InstallIntent.HEADLESS, InstallIntent.fromConfig("Headless"));
  }

  /**
   * FIX-7 (post-merge review, tempdoc 657): an unrecognized value — e.g. a typo like {@code
   * "headles"} — must still fall back to {@link InstallIntent#DEFAULT} rather than throwing or
   * bricking the install. This test pins that the documented fallback behavior holds; the WARN log
   * added alongside it is a discoverability improvement, not a behavior change, so it is not
   * asserted here (no test-visible log capture in this module).
   */
  @Test
  void unrecognizedValue_fallsBackToDefault_doesNotThrow() {
    assertSame(InstallIntent.DEFAULT, InstallIntent.fromConfig("headles"));
    assertSame(InstallIntent.DEFAULT, InstallIntent.fromConfig("not-a-real-mode"));
  }

  @Test
  void id_isKebabCase() {
    assertEquals("full-desktop", InstallIntent.FULL_DESKTOP.id());
    assertEquals("headless", InstallIntent.HEADLESS.id());
    assertEquals("mcp-lite", InstallIntent.MCP_LITE.id());
  }
}

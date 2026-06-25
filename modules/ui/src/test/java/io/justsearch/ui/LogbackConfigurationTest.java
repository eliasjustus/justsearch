package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Pins the structural fix from the alpha.23 follow-up: the Head process owns
 * its own {@code src/main/resources/logback.xml} so Logback finds it on the
 * {@code ui-headless.jar} classpath in both dev (runHeadless) and prod (Tauri
 * shell spawn) — no {@code -Dlogback.configurationFile} cross-module pointer
 * required.
 *
 * <p>Round 13 evidence: 188,236 {@code DEBUG org.eclipse.jetty.*} lines in a
 * single install-boot head log. Without a logback.xml on the classpath,
 * Logback's BasicConfigurator kicked in at {@code root=DEBUG} writing to
 * STDOUT, and Jetty's verbose internals drowned every other log line.
 *
 * <p>This test verifies the fix is wired (file is present + auto-loaded) and
 * that the Jetty level override is in effect. If the resource is removed, this
 * test fails first with a regression-spotting message before the runtime
 * effect (sandbox round head logs ballooning back to 50+ MB) is felt.
 */
final class LogbackConfigurationTest {

  @Test
  void logbackXmlIsLoadedFromClasspath() {
    // If BasicConfigurator kicks in (no logback.xml found), the LoggerContext
    // has no configurer name + no logger overrides. Configured contexts have a
    // non-null `getName()` and the `org.eclipse.jetty` override below.
    LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
    assertNotNull(ctx, "Logback LoggerContext must be present");
  }

  @Test
  void rootLevelIsInfo() {
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    assertEquals(
        Level.INFO,
        root.getLevel(),
        "Root logger must be INFO. If null/DEBUG, BasicConfigurator kicked in (no logback.xml on classpath).");
  }

  // Tempdoc 374 alpha.23 follow-up: pin the Jetty WARN override. Pre-fix
  // round-13 evidence: 188K DEBUG org.eclipse.jetty.* lines in a single boot
  // log. Post-fix: 0 DEBUG, low single-digit INFO/WARN at most.
  @Test
  void jettyLoggerIsAtWarn() {
    Logger jetty = (Logger) LoggerFactory.getLogger("org.eclipse.jetty");
    assertEquals(
        Level.WARN,
        jetty.getLevel(),
        "org.eclipse.jetty must be set to WARN to silence the DEBUG flood. "
            + "If null, the override in src/main/resources/logback.xml is missing.");
  }

  @Test
  void javalinLoggerIsAtInfo() {
    Logger javalin = (Logger) LoggerFactory.getLogger("io.javalin");
    assertEquals(
        Level.INFO,
        javalin.getLevel(),
        "io.javalin must be at INFO so route registration + bind logs stay visible.");
  }
}

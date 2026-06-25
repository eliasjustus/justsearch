/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.diagnostic;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import java.util.Objects;
import org.slf4j.LoggerFactory;

/**
 * Programmatic attach/detach hook for {@link DiagnosticChannelAppender} on the root
 * Logback logger.
 *
 * <p>Per slice 448 phase 3: kept here in {@code app-observability} (rather than in
 * {@code app-services} where {@code HeadAssembly} lives) so module callers don't
 * need a direct compile-classpath dependency on {@code ch.qos.logback.classic}. The
 * Logback dependency is already declared on this module for the appender itself; this
 * installer is the public-API surface bootstrap callers use.
 *
 * <p>Logback's {@code scan="true"} config-reload preserves programmatically attached
 * appenders (the reload only re-applies XML-declared appenders; runtime references stay
 * intact). Callers do not need to reattach on reload.
 */
public final class DiagnosticChannelAppenderInstaller {

  private final DiagnosticChannelAppender appender;
  private boolean attached;

  public DiagnosticChannelAppenderInstaller(DiagnosticChannelAppender appender) {
    this.appender = Objects.requireNonNull(appender, "appender");
  }

  /**
   * Attaches the appender to the root Logback logger. Idempotent: repeat calls are no-ops.
   */
  public synchronized void attach() {
    if (attached) {
      return;
    }
    final LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
    appender.setContext(lc);
    appender.setName("DIAGNOSTIC_CHANNEL");
    appender.start();
    final Logger root = (Logger) lc.getLogger(Logger.ROOT_LOGGER_NAME);
    root.addAppender(appender);
    attached = true;
  }

  /** Detaches and stops the appender. Idempotent. */
  public synchronized void detach() {
    if (!attached) {
      return;
    }
    try {
      final LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
      final Logger root = (Logger) lc.getLogger(Logger.ROOT_LOGGER_NAME);
      root.detachAppender(appender);
      appender.stop();
    } finally {
      attached = false;
    }
  }
}

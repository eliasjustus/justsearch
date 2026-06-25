/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import com.sun.net.httpserver.HttpHandler;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;

public final class BootstrapCapabilitiesFactory {
  private BootstrapCapabilitiesFactory() {}

  public static HttpHandler createCapabilitiesHandler(
      String systemOverride,
      String envOverride,
      Function<Path, HttpHandler> fileBackedHandlerFactory,
      Supplier<HttpHandler> defaultHandlerFactory) {
    String override = BootstrapFlagResolver.chooseFirstNonBlank(systemOverride, envOverride);
    if (override != null && !override.isBlank()) {
      Path path = Path.of(override).toAbsolutePath();
      return fileBackedHandlerFactory.apply(path);
    }
    return defaultHandlerFactory.get();
  }
}

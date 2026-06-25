/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

public final class BootstrapFlagResolver {
  private BootstrapFlagResolver() {}

  public static String chooseFirstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  public static boolean chooseFlag(String systemValue, String envValue, boolean defaultValue) {
    String candidate = chooseFirstNonBlank(systemValue, envValue);
    if (candidate == null) {
      return defaultValue;
    }
    return Boolean.parseBoolean(candidate.trim());
  }
}

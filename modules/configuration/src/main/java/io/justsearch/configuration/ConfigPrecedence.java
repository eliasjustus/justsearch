/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

/**
 * Centralized env/sysprop resolution helpers.
 *
 * <p>Prefer {@link EnvRegistry} for known configuration keys. This class exists for legacy/adhoc keys where
 * introducing a new {@link EnvRegistry} entry is not yet justified, but we still want to keep direct
 * {@link System#getProperty(String)} / {@link System#getenv(String)} usage out of non-configuration modules.
 */
public final class ConfigPrecedence {
  private ConfigPrecedence() {}

  /**
   * Resolves a value with precedence: system property &gt; environment variable &gt; fallback.
   *
   * @param envKey environment variable key (nullable)
   * @param propKey system property key (nullable)
   * @param fallback fallback value (nullable)
   */
  public static String envOrProperty(String envKey, String propKey, String fallback) {
    if (propKey != null && !propKey.isBlank()) {
      String prop = System.getProperty(propKey);
      if (prop != null && !prop.isBlank()) {
        return prop;
      }
    }
    if (envKey != null && !envKey.isBlank()) {
      String env = System.getenv(envKey);
      if (env != null && !env.isBlank()) {
        return env;
      }
    }
    return fallback;
  }
}

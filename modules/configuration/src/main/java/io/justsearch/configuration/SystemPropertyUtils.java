/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

/**
 * Conditional system property setters — shared across HeadlessApp and AI services.
 *
 * <p>These utilities implement the "if-blank guard" pattern: a system property is only set if it is
 * currently unset or blank, preserving explicit values from JVM flags or earlier resolution stages.
 */
public final class SystemPropertyUtils {

  private SystemPropertyUtils() {}

  /**
   * Sets a system property only if it is currently blank or not set. Trims the value before
   * setting.
   *
   * @param key the property key (if null/blank, does nothing)
   * @param value the property value (if null/blank, does nothing)
   */
  public static void setSysPropIfBlank(String key, String value) {
    if (key == null || key.isBlank()) return;
    String cur = System.getProperty(key);
    if (cur != null && !cur.isBlank()) return;
    if (value == null || value.isBlank()) return;
    System.setProperty(key, value.trim());
  }

  /**
   * Sets a system property and optionally a source-tracking property, only if the main property is
   * currently blank or not set. Trims the main value before setting.
   *
   * @param key the property key
   * @param value the property value
   * @param sourceKey the source property key (optional, can be null)
   * @param sourceValue the source property value (optional, can be null)
   */
  public static void setSysPropIfBlankWithSource(
      String key, String value, String sourceKey, String sourceValue) {
    if (key == null || key.isBlank() || value == null || value.isBlank()) return;
    String existing = System.getProperty(key);
    if (existing != null && !existing.isBlank()) return;
    System.setProperty(key, value.trim());
    if (sourceKey != null && !sourceKey.isBlank()
        && sourceValue != null && !sourceValue.isBlank()) {
      System.setProperty(sourceKey, sourceValue);
    }
  }
}

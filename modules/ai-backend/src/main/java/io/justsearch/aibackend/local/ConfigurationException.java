/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.local;

import java.util.ArrayList;
import java.util.List;

/**
 * Thrown when configuration validation fails.
 *
 * <p>May contain multiple validation errors discovered during validation.
 */
public class ConfigurationException extends RuntimeException {

  private final List<String> errors;

  public ConfigurationException(String message) {
    super(message);
    this.errors = List.of(message);
  }

  public ConfigurationException(List<String> errors) {
    super(formatErrors(errors));
    this.errors = List.copyOf(errors);
  }

  /**
   * Returns all validation errors.
   */
  public List<String> errors() {
    return errors;
  }

  /**
   * Returns true if there are multiple validation errors.
   */
  public boolean hasMultipleErrors() {
    return errors.size() > 1;
  }

  private static String formatErrors(List<String> errors) {
    if (errors == null || errors.isEmpty()) {
      return "Configuration validation failed";
    }
    if (errors.size() == 1) {
      return errors.get(0);
    }
    StringBuilder sb = new StringBuilder("Configuration validation failed with ");
    sb.append(errors.size()).append(" errors:\n");
    for (int i = 0; i < errors.size(); i++) {
      sb.append("  ").append(i + 1).append(". ").append(errors.get(i));
      if (i < errors.size() - 1) {
        sb.append("\n");
      }
    }
    return sb.toString();
  }

  /**
   * Builder for collecting multiple validation errors.
   */
  public static class Builder {
    private final List<String> errors = new ArrayList<>();

    public Builder addError(String error) {
      if (error != null && !error.isBlank()) {
        errors.add(error);
      }
      return this;
    }

    public Builder require(boolean condition, String errorIfFalse) {
      if (!condition) {
        errors.add(errorIfFalse);
      }
      return this;
    }

    public boolean hasErrors() {
      return !errors.isEmpty();
    }

    public void throwIfErrors() throws ConfigurationException {
      if (!errors.isEmpty()) {
        throw new ConfigurationException(errors);
      }
    }

    public ConfigurationException build() {
      return new ConfigurationException(errors);
    }
  }

  public static Builder builder() {
    return new Builder();
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.summary;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Structured metadata describing a summary rejection so UI/client layers can react consistently.
 */
public final class SummaryRejection {

  private final String reasonCode;
  private final String messageKey;
  private final Map<String, String> attributes;

  private SummaryRejection(Builder builder) {
    this.reasonCode = sanitize(builder.reasonCode, "unknown");
    this.messageKey = sanitizeMessageKey(builder.messageKey, "summary.toast.unknown");
    this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
  }

  public String reasonCode() {
    return reasonCode;
  }

  public String messageKey() {
    return messageKey;
  }

  public Map<String, String> attributes() {
    return attributes;
  }

  public String attribute(String key) {
    return attributes.get(key);
  }

  @Override
  public String toString() {
    return "SummaryRejection{"
        + "reasonCode='"
        + reasonCode
        + '\''
        + ", messageKey='"
        + messageKey
        + '\''
        + ", attributes="
        + attributes
        + '}';
  }

  public static Builder newBuilder(String reasonCode, String messageKey) {
    return new Builder(reasonCode, messageKey);
  }

  private static String sanitize(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private static String sanitizeMessageKey(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim();
  }

  public static final class Builder {
    private final String reasonCode;
    private final String messageKey;
    private final Map<String, String> attributes = new LinkedHashMap<>();

    private Builder(String reasonCode, String messageKey) {
      this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
      this.messageKey = Objects.requireNonNull(messageKey, "messageKey");
    }

    public Builder putAttribute(String key, Object value) {
      if (key == null || key.isBlank() || value == null) {
        return this;
      }
      attributes.put(key.trim().toLowerCase(Locale.ROOT), value.toString());
      return this;
    }

    public SummaryRejection build() {
      return new SummaryRejection(this);
    }
  }
}

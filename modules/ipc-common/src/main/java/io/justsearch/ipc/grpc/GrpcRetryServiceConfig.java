/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ipc.grpc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared gRPC retry service-config builder for idempotent service methods. */
public final class GrpcRetryServiceConfig {
  public static final String DEFAULT_POLICY_ID = "grpc-default-unavailable-v1";
  public static final int DEFAULT_MAX_RETRIES = 2;
  public static final long DEFAULT_INITIAL_BACKOFF_MS = 100L;
  public static final long DEFAULT_MAX_BACKOFF_MS = 1_000L;
  public static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0d;
  public static final List<String> DEFAULT_RETRYABLE_STATUS_CODES = List.of("UNAVAILABLE");

  public record RetryPolicyProfile(
      String policyId,
      int maxRetries,
      long initialBackoffMs,
      long maxBackoffMs,
      double backoffMultiplier,
      List<String> retryableStatusCodes) {
    public RetryPolicyProfile {
      if (policyId == null || policyId.trim().isEmpty()) {
        throw new IllegalArgumentException("policyId must be non-blank");
      }
      if (maxRetries < 0) {
        throw new IllegalArgumentException("maxRetries must be >= 0");
      }
      if (initialBackoffMs < 0L) {
        throw new IllegalArgumentException("initialBackoffMs must be >= 0");
      }
      if (maxBackoffMs < 0L) {
        throw new IllegalArgumentException("maxBackoffMs must be >= 0");
      }
      if (backoffMultiplier <= 0.0d) {
        throw new IllegalArgumentException("backoffMultiplier must be positive");
      }
      List<String> normalizedCodes = sanitizeStatusCodes(retryableStatusCodes);
      if (normalizedCodes.isEmpty()) {
        throw new IllegalArgumentException("retryableStatusCodes must contain at least one code");
      }
      retryableStatusCodes = normalizedCodes;
      policyId = policyId.trim();
    }
  }

  public record MethodScope(String serviceName, List<String> methodNames) {
    public MethodScope {
      if (serviceName == null || serviceName.trim().isEmpty()) {
        throw new IllegalArgumentException("serviceName must be non-blank");
      }
      serviceName = serviceName.trim();
      methodNames = sanitizeMethodNames(methodNames);
    }
  }

  private GrpcRetryServiceConfig() {}

  public static Map<String, Object> forServiceNames(List<String> serviceNames) {
    return forPolicyProfile(defaultProfile(), serviceNames, List.of());
  }

  /**
   * Builds a service-level retry config for the provided gRPC service names.
   *
   * <p>The config follows gRPC's JSON-ish shape:
   * numbers as {@link Double}, duration values as strings with "s" suffix.
   */
  public static Map<String, Object> forServiceNames(int maxRetries, List<String> serviceNames) {
    return forPolicyProfile(defaultProfile(maxRetries), serviceNames, List.of());
  }

  public static RetryPolicyProfile defaultProfile() {
    return defaultProfile(DEFAULT_MAX_RETRIES);
  }

  public static RetryPolicyProfile defaultProfile(int maxRetries) {
    return profile(
        DEFAULT_POLICY_ID,
        maxRetries,
        DEFAULT_INITIAL_BACKOFF_MS,
        DEFAULT_MAX_BACKOFF_MS,
        DEFAULT_BACKOFF_MULTIPLIER,
        DEFAULT_RETRYABLE_STATUS_CODES);
  }

  public static RetryPolicyProfile profile(
      String policyId,
      int maxRetries,
      long initialBackoffMs,
      long maxBackoffMs,
      double backoffMultiplier,
      List<String> retryableStatusCodes) {
    return new RetryPolicyProfile(
        policyId, maxRetries, initialBackoffMs, maxBackoffMs, backoffMultiplier, retryableStatusCodes);
  }

  /**
   * Builds a mixed retry config with service-level and method-level entries governed by one
   * policy profile.
   */
  public static Map<String, Object> forPolicyProfile(
      RetryPolicyProfile profile, List<String> serviceLevelNames, List<MethodScope> methodScopes) {
    RetryPolicyProfile checkedProfile =
        profile == null ? defaultProfile() : profile;
    Set<String> uniqueNames = sanitizeServiceNames(serviceLevelNames);
    Map<String, Set<String>> scopedMethods = sanitizeMethodScopes(methodScopes);
    if (uniqueNames.isEmpty() && scopedMethods.isEmpty()) {
      throw new IllegalArgumentException(
          "Retry config requires at least one service-level or method-level scope");
    }
    Map<String, Object> retryPolicy = retryPolicy(checkedProfile);
    List<Map<String, Object>> methodConfig = new ArrayList<>();
    for (String serviceName : uniqueNames) {
      methodConfig.add(
          Map.of(
              "name", List.of(Map.of("service", serviceName)),
              "retryPolicy", retryPolicy));
    }
    for (Map.Entry<String, Set<String>> entry : scopedMethods.entrySet()) {
      String serviceName = entry.getKey();
      for (String methodName : entry.getValue()) {
        methodConfig.add(
            Map.of(
                "name", List.of(Map.of("service", serviceName, "method", methodName)),
                "retryPolicy", retryPolicy));
      }
    }
    return Map.of("methodConfig", methodConfig);
  }

  private static Map<String, Object> retryPolicy(RetryPolicyProfile profile) {
    int attempts = Math.max(1, profile.maxRetries() + 1);
    return Map.of(
        "maxAttempts", Double.valueOf(attempts),
        "initialBackoff", msToSeconds(profile.initialBackoffMs()),
        "maxBackoff", msToSeconds(profile.maxBackoffMs()),
        "backoffMultiplier", Double.valueOf(profile.backoffMultiplier()),
        "retryableStatusCodes", profile.retryableStatusCodes());
  }

  private static Set<String> sanitizeServiceNames(List<String> serviceNames) {
    if (serviceNames == null || serviceNames.isEmpty()) {
      return Set.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String serviceName : serviceNames) {
      if (serviceName == null) {
        continue;
      }
      String trimmed = serviceName.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }

  private static Map<String, Set<String>> sanitizeMethodScopes(List<MethodScope> methodScopes) {
    if (methodScopes == null || methodScopes.isEmpty()) {
      return Map.of();
    }
    Map<String, Set<String>> out = new LinkedHashMap<>();
    for (MethodScope scope : methodScopes) {
      if (scope == null) {
        continue;
      }
      String service = scope.serviceName();
      if (service == null || service.trim().isEmpty()) {
        continue;
      }
      Set<String> methods = out.computeIfAbsent(service.trim(), ignored -> new LinkedHashSet<>());
      methods.addAll(scope.methodNames());
    }
    for (Map.Entry<String, Set<String>> entry : out.entrySet()) {
      entry.setValue(Collections.unmodifiableSet(entry.getValue()));
    }
    return Collections.unmodifiableMap(out);
  }

  private static List<String> sanitizeStatusCodes(List<String> statusCodes) {
    if (statusCodes == null || statusCodes.isEmpty()) {
      return List.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String statusCode : statusCodes) {
      if (statusCode == null) {
        continue;
      }
      String trimmed = statusCode.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed.toUpperCase(Locale.ROOT));
      }
    }
    return List.copyOf(out);
  }

  private static List<String> sanitizeMethodNames(List<String> methodNames) {
    if (methodNames == null || methodNames.isEmpty()) {
      return List.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String methodName : methodNames) {
      if (methodName == null) {
        continue;
      }
      String trimmed = methodName.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return List.copyOf(out);
  }

  private static String msToSeconds(long ms) {
    long safe = Math.max(0L, ms);
    return String.format(Locale.ROOT, "%.3fs", safe / 1000.0d);
  }
}

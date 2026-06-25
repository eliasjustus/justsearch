package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.SearchServiceGrpc;
import io.justsearch.ipc.HealthServiceGrpc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for gRPC retry policy configuration.
 *
 * <p>Verifies that the retry policy is correctly scoped:
 * <ul>
 *   <li>SearchService and HealthService have service-level retry (all methods are idempotent)</li>
 *   <li>IngestService has per-method retry only for idempotent methods</li>
 *   <li>Non-idempotent methods like MarkVduProcessing are excluded from retry</li>
 * </ul>
 */
@DisplayName("RemoteKnowledgeClient Retry Policy")
class RemoteKnowledgeClientRetryConfigTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  @DisplayName("Retry policy includes SearchService (service-level)")
  void retryPolicyIncludesSearchService() throws Exception {
    Map<String, Object> config = invokeRetryConfigBuilder(3);
    assertTrue(hasServiceLevelRetryPolicy(config, SearchServiceGrpc.getServiceDescriptor().getName()),
        "SearchService should have service-level retry policy");
  }

  @Test
  @DisplayName("Retry policy includes HealthService (service-level)")
  void retryPolicyIncludesHealthService() throws Exception {
    Map<String, Object> config = invokeRetryConfigBuilder(3);
    assertTrue(hasServiceLevelRetryPolicy(config, HealthServiceGrpc.getServiceDescriptor().getName()),
        "HealthService should have service-level retry policy");
  }

  @Test
  @DisplayName("IngestService has no service-level retry (only per-method)")
  void retryPolicyNoServiceLevelForIngestService() throws Exception {
    Map<String, Object> config = invokeRetryConfigBuilder(3);
    assertFalse(hasServiceLevelRetryPolicy(config, IngestServiceGrpc.getServiceDescriptor().getName()),
        "IngestService should NOT have service-level retry policy");
  }

  @Test
  @DisplayName("Retry policy includes idempotent IngestService methods")
  void retryPolicyIncludesIdempotentIngestMethods() throws Exception {
    Map<String, Object> config = invokeRetryConfigBuilder(3);
    String ingestService = IngestServiceGrpc.getServiceDescriptor().getName();

    // These methods are idempotent and should have retry policy
    assertTrue(hasMethodRetryPolicy(config, ingestService, "IndexStatus"),
        "IndexStatus should have retry policy");
    assertTrue(hasMethodRetryPolicy(config, ingestService, "QueryPendingVdu"),
        "QueryPendingVdu should have retry policy");
    assertTrue(hasMethodRetryPolicy(config, ingestService, "PruneMissing"),
        "PruneMissing should have retry policy");
    assertTrue(hasMethodRetryPolicy(config, ingestService, "RecoverVduProcessing"),
        "RecoverVduProcessing should have retry policy");
    assertTrue(hasMethodRetryPolicy(config, ingestService, "SyncDirectory"),
        "SyncDirectory should have retry policy");
  }

  @Test
  @DisplayName("Retry policy excludes non-idempotent IngestService methods")
  void retryPolicyExcludesNonIdempotentIngestMethods() throws Exception {
    Map<String, Object> config = invokeRetryConfigBuilder(3);
    String ingestService = IngestServiceGrpc.getServiceDescriptor().getName();

    // These methods are NOT idempotent and should NOT have retry policy
    assertFalse(hasMethodRetryPolicy(config, ingestService, "MarkVduProcessing"),
        "MarkVduProcessing should NOT have retry policy");
    assertFalse(hasMethodRetryPolicy(config, ingestService, "UpdateVduResult"),
        "UpdateVduResult should NOT have retry policy");
    assertFalse(hasMethodRetryPolicy(config, ingestService, "SubmitBatch"),
        "SubmitBatch should NOT have retry policy");
  }

  @Test
  @DisplayName("Retry policy uses RKC profile backoff and attempts")
  void retryPolicyUsesRkcProfile() throws Exception {
    int configuredMaxRetries = 3;
    Map<String, Object> config = invokeRetryConfigBuilder(configuredMaxRetries);
    Map<String, Object> retryPolicy = firstRetryPolicy(config);
    JsonNode profile = loadProfile("grpc-rkc-unavailable-v1");

    double expectedMaxRetries =
        profile.path("maxRetriesSource").isTextual()
            ? configuredMaxRetries
            : profile.path("maxRetriesDefault").asDouble();
    double expectedMaxAttempts = expectedMaxRetries + 1.0d;
    String expectedInitialBackoff = String.format(java.util.Locale.ROOT, "%.3fs", profile.path("initialBackoffMs").asDouble() / 1000.0d);
    String expectedMaxBackoff = String.format(java.util.Locale.ROOT, "%.3fs", profile.path("maxBackoffMs").asDouble() / 1000.0d);
    double expectedBackoffMultiplier = profile.path("backoffMultiplier").asDouble();

    assertEquals(expectedMaxAttempts, retryPolicy.get("maxAttempts"));
    assertEquals(expectedInitialBackoff, retryPolicy.get("initialBackoff"));
    assertEquals(expectedMaxBackoff, retryPolicy.get("maxBackoff"));
    assertEquals(expectedBackoffMultiplier, retryPolicy.get("backoffMultiplier"));
    assertEquals(
        jsonTextArray(profile.path("retryableStatusCodes")),
        retryPolicy.get("retryableStatusCodes"));
  }

  /**
   * Reflectively invokes the private buildGrpcRetryServiceConfig method.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> invokeRetryConfigBuilder(int maxRetries) throws Exception {
    Method method = RemoteKnowledgeClient.class
        .getDeclaredMethod("buildGrpcRetryServiceConfig", int.class);
    method.setAccessible(true);
    return (Map<String, Object>) method.invoke(null, maxRetries);
  }

  /**
   * Checks if the config has a service-level retryPolicy (no method specified).
   */
  @SuppressWarnings("unchecked")
  private static boolean hasServiceLevelRetryPolicy(Map<String, Object> config, String serviceName) {
    Object methodConfigObj = config.get("methodConfig");
    if (!(methodConfigObj instanceof List<?> methodConfigs)) {
      return false;
    }

    for (Object entry : methodConfigs) {
      if (!(entry instanceof Map<?, ?> entryMap)) continue;
      if (!entryMap.containsKey("retryPolicy")) continue;

      Object nameList = entryMap.get("name");
      if (!(nameList instanceof List<?> names)) continue;

      for (Object nameEntry : names) {
        if (!(nameEntry instanceof Map<?, ?> nameMap)) continue;

        Object service = nameMap.get("service");
        Object method = nameMap.get("method");

        // Service-level means service matches AND no method specified
        if (serviceName.equals(service) && method == null) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Checks if the config has a method-specific retryPolicy.
   */
  @SuppressWarnings("unchecked")
  private static boolean hasMethodRetryPolicy(Map<String, Object> config, String serviceName, String methodName) {
    Object methodConfigObj = config.get("methodConfig");
    if (!(methodConfigObj instanceof List<?> methodConfigs)) {
      return false;
    }

    for (Object entry : methodConfigs) {
      if (!(entry instanceof Map<?, ?> entryMap)) continue;
      if (!entryMap.containsKey("retryPolicy")) continue;

      Object nameList = entryMap.get("name");
      if (!(nameList instanceof List<?> names)) continue;

      for (Object nameEntry : names) {
        if (!(nameEntry instanceof Map<?, ?> nameMap)) continue;

        Object service = nameMap.get("service");
        Object method = nameMap.get("method");

        // Method-level means both service and method match
        if (serviceName.equals(service) && methodName.equals(method)) {
          return true;
        }
      }
    }

    return false;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> firstRetryPolicy(Map<String, Object> config) {
    Object methodConfigObj = config.get("methodConfig");
    if (!(methodConfigObj instanceof List<?> methodConfigs) || methodConfigs.isEmpty()) {
      throw new IllegalStateException("No methodConfig entries");
    }
    Object first = methodConfigs.get(0);
    if (!(first instanceof Map<?, ?> firstMap)) {
      throw new IllegalStateException("Invalid methodConfig entry");
    }
    Object retryPolicyObj = firstMap.get("retryPolicy");
    if (!(retryPolicyObj instanceof Map<?, ?> retryMap)) {
      throw new IllegalStateException("Missing retryPolicy");
    }
    return (Map<String, Object>) retryMap;
  }

  private static JsonNode loadProfile(String policyId) throws Exception {
    Path path = resolveRepoRoot()
        .resolve("scripts")
        .resolve("resilience")
        .resolve("contracts")
        .resolve("grpc-retry-policy-profiles.v1.json");
    JsonNode root = JSON.readTree(Files.readString(path));
    for (JsonNode profile : root.path("profiles")) {
      if (policyId.equals(profile.path("policyId").asText())) {
        return profile;
      }
    }
    throw new IllegalStateException("Missing policy profile: " + policyId);
  }

  private static Path resolveRepoRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (current != null) {
      if (Files.exists(current.resolve("scripts").resolve("resilience").resolve("contracts"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException(
        "Unable to resolve repo root from " + Path.of(System.getProperty("user.dir")));
  }

  private static List<String> jsonTextArray(JsonNode arrayNode) {
    List<String> out = new ArrayList<>();
    if (arrayNode == null || !arrayNode.isArray()) {
      return out;
    }
    for (JsonNode value : arrayNode) {
      out.add(value.asText());
    }
    return out;
  }
}

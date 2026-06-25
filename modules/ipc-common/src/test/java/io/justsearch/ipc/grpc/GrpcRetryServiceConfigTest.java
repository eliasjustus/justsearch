package io.justsearch.ipc.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class GrpcRetryServiceConfigTest {
  @Test
  void buildsServiceLevelRetryConfigForDistinctServices() {
    Map<String, Object> config =
        GrpcRetryServiceConfig.forServiceNames(
            2,
            List.of(
                "io.justsearch.ipc.v1.HealthService",
                "io.justsearch.ipc.v1.AiService",
                "io.justsearch.ipc.v1.AiService"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> methodConfig = (List<Map<String, Object>>) config.get("methodConfig");
    assertEquals(2, methodConfig.size());

    @SuppressWarnings("unchecked")
    Map<String, Object> retryPolicy = (Map<String, Object>) methodConfig.get(0).get("retryPolicy");
    assertEquals(3.0d, retryPolicy.get("maxAttempts"));
    assertEquals("0.100s", retryPolicy.get("initialBackoff"));
    assertEquals("1.000s", retryPolicy.get("maxBackoff"));
    assertEquals(2.0d, retryPolicy.get("backoffMultiplier"));
    assertEquals(List.of("UNAVAILABLE"), retryPolicy.get("retryableStatusCodes"));
  }

  @Test
  void rejectsEmptyServiceList() {
    assertThrows(IllegalArgumentException.class, () -> GrpcRetryServiceConfig.forServiceNames(List.of("  ")));
  }

  @Test
  void buildsMixedServiceAndMethodRetryConfigFromSingleProfile() {
    GrpcRetryServiceConfig.RetryPolicyProfile profile =
        GrpcRetryServiceConfig.profile(
            "grpc-rkc-unavailable-v1", 3, 125L, 2_000L, 2.0d, List.of("UNAVAILABLE"));
    Map<String, Object> config =
        GrpcRetryServiceConfig.forPolicyProfile(
            profile,
            List.of("io.justsearch.ipc.SearchService"),
            List.of(
                new GrpcRetryServiceConfig.MethodScope(
                    "io.justsearch.ipc.IngestService",
                    List.of("IndexStatus", "SyncDirectory"))));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> methodConfig = (List<Map<String, Object>>) config.get("methodConfig");
    assertEquals(3, methodConfig.size());

    @SuppressWarnings("unchecked")
    Map<String, Object> retryPolicy = (Map<String, Object>) methodConfig.get(0).get("retryPolicy");
    assertEquals(4.0d, retryPolicy.get("maxAttempts"));
    assertEquals("0.125s", retryPolicy.get("initialBackoff"));
    assertEquals("2.000s", retryPolicy.get("maxBackoff"));
    assertEquals(List.of("UNAVAILABLE"), retryPolicy.get("retryableStatusCodes"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> name0 = (List<Map<String, Object>>) methodConfig.get(0).get("name");
    assertEquals("io.justsearch.ipc.SearchService", name0.get(0).get("service"));
    assertTrue(!name0.get(0).containsKey("method"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> name1 = (List<Map<String, Object>>) methodConfig.get(1).get("name");
    assertEquals("io.justsearch.ipc.IngestService", name1.get(0).get("service"));
    assertEquals("IndexStatus", name1.get(0).get("method"));
  }

  @Test
  void rejectsInvalidPolicyProfile() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GrpcRetryServiceConfig.profile(
                " ",
                1,
                100L,
                1000L,
                2.0d,
                List.of("UNAVAILABLE")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GrpcRetryServiceConfig.profile(
                "grpc-invalid",
                1,
                100L,
                1000L,
                2.0d,
                List.of()));
  }

  @Test
  void defaultProfileMatchesJsonPolicyRegistry() throws Exception {
    PolicyProfileData profile = loadProfile("grpc-default-unavailable-v1");
    GrpcRetryServiceConfig.RetryPolicyProfile defaultProfile = GrpcRetryServiceConfig.defaultProfile();
    assertEquals(profile.policyId(), defaultProfile.policyId());
    assertEquals(profile.maxRetriesDefault(), defaultProfile.maxRetries());
    assertEquals(profile.initialBackoffMs(), defaultProfile.initialBackoffMs());
    assertEquals(profile.maxBackoffMs(), defaultProfile.maxBackoffMs());
    assertEquals(profile.backoffMultiplier(), defaultProfile.backoffMultiplier());
    assertEquals(profile.retryableStatusCodes(), defaultProfile.retryableStatusCodes());
  }

  private static PolicyProfileData loadProfile(String policyId) throws Exception {
    Path path = resolveRepoRoot()
        .resolve("scripts")
        .resolve("resilience")
        .resolve("contracts")
        .resolve("grpc-retry-policy-profiles.v1.json");
    String json = Files.readString(path);
    String key = "\"policyId\"\\s*:\\s*\"" + Pattern.quote(policyId) + "\"";
    Matcher matcher = Pattern.compile(key).matcher(json);
    if (!matcher.find()) {
      throw new IllegalStateException("Missing policy profile: " + policyId);
    }
    int keyIndex = matcher.start();
    int objectStart = json.lastIndexOf('{', keyIndex);
    if (objectStart < 0) {
      throw new IllegalStateException("Unable to resolve JSON object for policy profile: " + policyId);
    }
    int objectEnd = findObjectEnd(json, objectStart);
    String object = json.substring(objectStart, objectEnd + 1);
    return new PolicyProfileData(
        extractString(object, "policyId"),
        extractInt(object, "maxRetriesDefault"),
        extractLong(object, "initialBackoffMs"),
        extractLong(object, "maxBackoffMs"),
        extractDouble(object, "backoffMultiplier"),
        extractStringArray(object, "retryableStatusCodes"));
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

  private static int findObjectEnd(String json, int objectStart) {
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int i = objectStart; i < json.length(); i++) {
      char ch = json.charAt(i);
      if (inString) {
        if (escaped) {
          escaped = false;
          continue;
        }
        if (ch == '\\') {
          escaped = true;
        } else if (ch == '"') {
          inString = false;
        }
        continue;
      }
      if (ch == '"') {
        inString = true;
      } else if (ch == '{') {
        depth++;
      } else if (ch == '}') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    throw new IllegalStateException("Unterminated JSON object");
  }

  private static String extractString(String object, String key) {
    Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(object);
    if (!matcher.find()) {
      throw new IllegalStateException("Missing string field: " + key);
    }
    return matcher.group(1);
  }

  private static int extractInt(String object, String key) {
    Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(object);
    if (!matcher.find()) {
      throw new IllegalStateException("Missing int field: " + key);
    }
    return Integer.parseInt(matcher.group(1));
  }

  private static long extractLong(String object, String key) {
    Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(object);
    if (!matcher.find()) {
      throw new IllegalStateException("Missing long field: " + key);
    }
    return Long.parseLong(matcher.group(1));
  }

  private static double extractDouble(String object, String key) {
    Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(object);
    if (!matcher.find()) {
      throw new IllegalStateException("Missing double field: " + key);
    }
    return Double.parseDouble(matcher.group(1));
  }

  private static List<String> extractStringArray(String object, String key) {
    Matcher arrayMatcher =
        Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL)
            .matcher(object);
    if (!arrayMatcher.find()) {
      throw new IllegalStateException("Missing array field: " + key);
    }
    List<String> out = new ArrayList<>();
    Matcher valueMatcher = Pattern.compile("\"([^\"]+)\"").matcher(arrayMatcher.group(1));
    while (valueMatcher.find()) {
      out.add(valueMatcher.group(1));
    }
    return out;
  }

  private record PolicyProfileData(
      String policyId,
      int maxRetriesDefault,
      long initialBackoffMs,
      long maxBackoffMs,
      double backoffMultiplier,
      List<String> retryableStatusCodes) {}
}

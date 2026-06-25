/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.pack;

import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.justsearch.app.api.EffectivePolicy;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates whether a pack is allowlisted based on the trust model in {@code 14-ai-pack-spec.md}.
 *
 * <p>Order:
 * <ul>
 *   <li>If machine policy exists, it is authoritative (including the deny-all case when its allowlist is empty).</li>
 *   <li>Else, if user policy has a non-empty pack allowlist, it is primary (power-user enablement).</li>
 *   <li>Else, fall back to the app-bundled allowlist resource.</li>
 * </ul>
 */
public final class PackAllowlistService {
  private static final Logger log = LoggerFactory.getLogger(PackAllowlistService.class);

  private static final String APP_ALLOWLIST_RESOURCE = "ai/pack-allowlist.v1.json";

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
          .build();

  private final Set<String> appAllowlistedManifestSha256;

  public PackAllowlistService() {
    this.appAllowlistedManifestSha256 = loadAppAllowlistBestEffort();
  }

  /** Test/support constructor: use an explicit app allowlist set (values are normalized). */
  public PackAllowlistService(Set<String> appAllowlistedManifestSha256) {
    this.appAllowlistedManifestSha256 = normalizeSetFromSet(appAllowlistedManifestSha256);
  }

  public record Decision(boolean allowed, String errorCode, String message) {}

  public Decision evaluatePackManifestSha256(String manifestSha256, EffectivePolicy effectivePolicy) {
    String norm = normalizeSha256(manifestSha256);
    if (norm == null) {
      return new Decision(false, "PACK_MANIFEST_SHA_INVALID", "Invalid pack manifest SHA-256.");
    }

    EffectivePolicy.PolicySource machine = effectivePolicy.machine();
    if (machine != null && machine.present() && !machine.loaded()) {
      return new Decision(
          false,
          "MACHINE_POLICY_INVALID",
          "Machine policy exists but could not be loaded; refusing offline pack install.");
    }

    // Authoritative machine policy (deny-all when allowlist is empty).
    if (machine != null && machine.present() && machine.loaded() && machine.parsed() != null) {
      List<String> machineList =
          machine.parsed().allowlists == null ? null : machine.parsed().allowlists.packManifestSha256;
      Set<String> normalizedMachine = normalizeSet(machineList);
      if (!normalizedMachine.contains(norm)) {
        return new Decision(
            false, "PACK_NOT_ALLOWLISTED_BY_MACHINE_POLICY", "This AI Pack is not allowlisted by machine policy.");
      }

      // User restrictive-only allowlist (intersection).
      EffectivePolicy.PolicySource user = effectivePolicy.user();
      if (user != null && user.loaded() && user.parsed() != null) {
        List<String> userList =
            user.parsed().allowlists == null ? null : user.parsed().allowlists.packManifestSha256;
        Set<String> normalizedUser = normalizeSet(userList);
        if (!normalizedUser.isEmpty() && !normalizedUser.contains(norm)) {
          return new Decision(
              false,
              "PACK_NOT_ALLOWLISTED_BY_USER_POLICY",
              "This AI Pack is not allowlisted by user policy.");
        }
      }

      return new Decision(true, null, null);
    }

    // Machine policy absent: allow power-users to enable pack import via user policy allowlist (primary).
    EffectivePolicy.PolicySource user = effectivePolicy.user();
    if (user != null && user.loaded() && user.parsed() != null) {
      List<String> userList =
          user.parsed().allowlists == null ? null : user.parsed().allowlists.packManifestSha256;
      Set<String> normalizedUser = normalizeSet(userList);
      if (!normalizedUser.isEmpty()) {
        if (!normalizedUser.contains(norm)) {
          return new Decision(
              false,
              "PACK_NOT_ALLOWLISTED_BY_USER_POLICY",
              "This AI Pack is not allowlisted by user policy.");
        }
        return new Decision(true, null, null);
      }
    }

    // Fall back to app-bundled allowlist.
    if (!appAllowlistedManifestSha256.contains(norm)) {
      return new Decision(false, "PACK_NOT_ALLOWLISTED", "This AI Pack is not allowlisted.");
    }

    return new Decision(true, null, null);
  }

  public Set<String> appAllowlistedManifestSha256() {
    return Set.copyOf(appAllowlistedManifestSha256);
  }

  private static Set<String> loadAppAllowlistBestEffort() {
    try (InputStream in =
        PackAllowlistService.class.getClassLoader().getResourceAsStream(APP_ALLOWLIST_RESOURCE)) {
      if (in == null) {
        log.warn("App pack allowlist resource not found: {}", APP_ALLOWLIST_RESOURCE);
        return Set.of();
      }
      byte[] bytes = in.readAllBytes();
      String json = new String(bytes, StandardCharsets.UTF_8);
      AppAllowlist parsed = MAPPER.readValue(json, AppAllowlist.class);
      if (parsed == null || parsed.schemaVersion != 1) {
        log.warn("App pack allowlist has unsupported schemaVersion");
        return Set.of();
      }
      return normalizeSet(parsed.allowlistedPackManifestSha256);
    } catch (Exception e) {
      log.warn("Failed to load app pack allowlist; offline pack installs will be denied", e);
      return Set.of();
    }
  }

  private static Set<String> normalizeSet(List<String> values) {
    if (values == null || values.isEmpty()) return Set.of();
    Set<String> out = new HashSet<>();
    for (String v : values) {
      String n = normalizeSha256(v);
      if (n != null) {
        out.add(n);
      }
    }
    return out;
  }

  private static Set<String> normalizeSetFromSet(Set<String> values) {
    if (values == null || values.isEmpty()) return Set.of();
    Set<String> out = new HashSet<>();
    for (String v : values) {
      String n = normalizeSha256(v);
      if (n != null) {
        out.add(n);
      }
    }
    return out;
  }

  private static String normalizeSha256(String value) {
    if (value == null) return null;
    String s = value.trim().toLowerCase(Locale.ROOT);
    if (s.length() != 64) return null;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
      if (!ok) return null;
    }
    return s;
  }

  /**
   * Checks whether all model file SHA-256 digests in the manifest are allowed by policy.
   *
   * <p>Returns {@code true} if neither machine nor user policy restricts model digests, or if all
   * digests pass the active allowlists. Returns {@code false} on any restriction failure or
   * unexpected error (fail-closed).
   */
  public boolean evaluateModelFileAllowlist(AiPackManifestV1 manifest, EffectivePolicy p) {
    try {
      if (manifest == null || manifest.files == null || p == null) return true;
      Set<String> machine = new HashSet<>();
      if (p.machine() != null
          && p.machine().loaded()
          && p.machine().parsed() != null
          && p.machine().parsed().allowlists != null) {
        var list = p.machine().parsed().allowlists.modelSha256;
        if (list != null) {
          for (String s : list) {
            if (s != null && !s.isBlank()) machine.add(s.trim().toLowerCase(Locale.ROOT));
          }
        }
      }
      Set<String> user = new HashSet<>();
      if (p.user() != null
          && p.user().loaded()
          && p.user().parsed() != null
          && p.user().parsed().allowlists != null) {
        var list = p.user().parsed().allowlists.modelSha256;
        if (list != null) {
          for (String s : list) {
            if (s != null && !s.isBlank()) user.add(s.trim().toLowerCase(Locale.ROOT));
          }
        }
      }
      boolean machineRestricts = !machine.isEmpty();
      boolean userRestricts = !user.isEmpty();
      if (!machineRestricts && !userRestricts) return true;
      for (AiPackManifestV1.FileEntry f : manifest.files) {
        if (f == null || f.sha256 == null || f.sha256.isBlank()) return false;
        String sha = f.sha256.trim().toLowerCase(Locale.ROOT);
        if (machineRestricts && !machine.contains(sha)) return false;
        if (userRestricts && !user.contains(sha)) return false;
      }
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static final class AppAllowlist {
    public int schemaVersion = 0;
    public List<String> allowlistedPackManifestSha256;
  }
}

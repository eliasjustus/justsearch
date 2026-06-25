/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.policy;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.EnterprisePolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Safe-by-design helper: create a user policy file (under AI Home) only if it does not exist.
 *
 * <p>This must never edit machine policy and must not overwrite existing user policy.
 */
public final class UserPolicyWriter {
  private static final ObjectMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private UserPolicyWriter() {}

  public record Result(Path path) {}
  public record AppendResult(Path path, boolean changed, int allowlistedCount) {}

  public static Result createUserPolicyForPackImportIfMissing(
      EffectivePolicy effectivePolicy, String manifestSha256) {
    if (effectivePolicy == null) {
      throw new UserPolicyWriteException(
          500, ApiErrorCode.POLICY_UNAVAILABLE, "Effective policy unavailable; cannot create user policy.");
    }

    String norm = normalizeSha256(manifestSha256);
    if (norm == null) {
      throw new UserPolicyWriteException(400, ApiErrorCode.PACK_MANIFEST_SHA_INVALID, "Invalid manifest SHA-256.");
    }

    EffectivePolicy.PolicySource machine = effectivePolicy.machine();
    if (machine != null && machine.present()) {
      throw new UserPolicyWriteException(
          409,
          ApiErrorCode.MACHINE_POLICY_PRESENT,
          "Machine policy is present. User policy cannot enable pack import on managed machines.");
    }

    EffectivePolicy.PolicySource user = effectivePolicy.user();
    Path userPath = user == null ? null : user.path();
    if (userPath == null) {
      throw new UserPolicyWriteException(
          500, ApiErrorCode.USER_POLICY_PATH_UNAVAILABLE, "User policy path unavailable.");
    }

    // Safe-by-design: never overwrite existing user policy (even if it exists but is not a regular file).
    if (Files.exists(userPath)) {
      throw new UserPolicyWriteException(
          409, ApiErrorCode.USER_POLICY_ALREADY_EXISTS, "User policy already exists; refusing to overwrite.");
    }

    Path parent = userPath.getParent();
    if (parent == null) {
      throw new UserPolicyWriteException(
          500, ApiErrorCode.USER_POLICY_PATH_INVALID, "User policy path is invalid: " + userPath);
    }

    try {
      Files.createDirectories(parent);
    } catch (Exception e) {
      throw new UserPolicyWriteException(
          500,
          ApiErrorCode.USER_POLICY_DIR_CREATE_FAILED,
          "Failed to create user policy directory: " + e.getMessage());
    }

    // If pack import is already enabled via the app-bundled allowlist (machine/user policy absent),
    // preserve that allowlist so creating user policy does not accidentally *reduce* what can be imported.
    Set<String> allowlisted = new LinkedHashSet<>();
    if ("app".equalsIgnoreCase(effectivePolicy.packAllowlistSource())
        && effectivePolicy.allowlistedPackManifestSha256() != null) {
      for (String s : effectivePolicy.allowlistedPackManifestSha256()) {
        String n = normalizeSha256(s);
        if (n != null) {
          allowlisted.add(n);
        }
      }
    }
    allowlisted.add(norm);
    List<String> packManifestSha256 = new ArrayList<>(allowlisted);

    // Minimal policy: only schemaVersion/updatedAt/allowlists. Booleans are omitted to avoid changing behavior.
    Map<String, Object> doc =
        Map.of(
            "schemaVersion",
            1,
            "updatedAt",
            Instant.now().toString(),
            "allowlists",
            Map.of("packManifestSha256", packManifestSha256));

    String json;
    try {
      json = MAPPER.writeValueAsString(doc);
    } catch (Exception e) {
      throw new UserPolicyWriteException(
          500, ApiErrorCode.USER_POLICY_SERIALIZE_FAILED, "Failed to serialize policy JSON: " + e.getMessage());
    }

    Path tmp =
        userPath.resolveSibling(userPath.getFileName().toString() + ".tmp-" + UUID.randomUUID());
    try {
      Files.writeString(tmp, json, StandardCharsets.UTF_8);
      moveAtomicCreateOnly(tmp, userPath);
      return new Result(userPath);
    } catch (FileAlreadyExistsException e) {
      // Race: someone created it between our checks.
      tryDelete(tmp);
      throw new UserPolicyWriteException(
          409, ApiErrorCode.USER_POLICY_ALREADY_EXISTS, "User policy already exists; refusing to overwrite.");
    } catch (Exception e) {
      tryDelete(tmp);
      throw new UserPolicyWriteException(
          500, ApiErrorCode.USER_POLICY_WRITE_FAILED, "Failed to write user policy: " + e.getMessage());
    }
  }

  public static AppendResult addPackManifestShaToUserPolicyAllowlist(
      EffectivePolicy effectivePolicy, String manifestSha256) {
    if (effectivePolicy == null) {
      throw new UserPolicyWriteException(
          500, ApiErrorCode.POLICY_UNAVAILABLE, "Effective policy unavailable; cannot update user policy.");
    }

    String norm = normalizeSha256(manifestSha256);
    if (norm == null) {
      throw new UserPolicyWriteException(400, ApiErrorCode.PACK_MANIFEST_SHA_INVALID, "Invalid manifest SHA-256.");
    }

    EffectivePolicy.PolicySource machine = effectivePolicy.machine();
    if (machine != null && machine.present()) {
      throw new UserPolicyWriteException(
          409,
          ApiErrorCode.MACHINE_POLICY_PRESENT,
          "Machine policy is present. User policy cannot update pack allowlist on managed machines.");
    }

    EffectivePolicy.PolicySource user = effectivePolicy.user();
    Path userPath = user == null ? null : user.path();
    if (userPath == null) {
      throw new UserPolicyWriteException(
          500, ApiErrorCode.USER_POLICY_PATH_UNAVAILABLE, "User policy path unavailable.");
    }

    // This endpoint only updates an existing user policy file; creating is handled elsewhere.
    if (!Files.isRegularFile(userPath)) {
      throw new UserPolicyWriteException(
          409,
          ApiErrorCode.USER_POLICY_MISSING,
          "User policy does not exist; create it first before appending allowlist entries.");
    }

    final tools.jackson.databind.node.ObjectNode root;
    try {
      byte[] bytes = Files.readAllBytes(userPath);
      tools.jackson.databind.JsonNode parsed = MAPPER.readTree(bytes);
      if (!(parsed instanceof tools.jackson.databind.node.ObjectNode obj)) {
        throw new UserPolicyWriteException(
            400, ApiErrorCode.USER_POLICY_INVALID_JSON, "User policy JSON must be an object.");
      }
      root = obj;
    } catch (UserPolicyWriteException e) {
      throw e;
    } catch (Exception e) {
      throw new UserPolicyWriteException(
          400, ApiErrorCode.USER_POLICY_INVALID_JSON, "User policy is not valid JSON: " + e.getMessage());
    }

    int schemaVersion = root.path("schemaVersion").asInt(0);
    if (schemaVersion != 1) {
      throw new UserPolicyWriteException(
          409, ApiErrorCode.USER_POLICY_UNSUPPORTED_SCHEMA, "Unsupported user policy schemaVersion: " + schemaVersion);
    }

    tools.jackson.databind.JsonNode allowlistsNode = root.get("allowlists");
    final tools.jackson.databind.node.ObjectNode allowlists;
    if (allowlistsNode == null || allowlistsNode.isNull()) {
      allowlists = MAPPER.createObjectNode();
      root.set("allowlists", allowlists);
    } else if (allowlistsNode instanceof tools.jackson.databind.node.ObjectNode obj) {
      allowlists = obj;
    } else {
      throw new UserPolicyWriteException(
          400, ApiErrorCode.USER_POLICY_INVALID_JSON, "Field allowlists must be an object.");
    }

    // Read existing allowlist entries (best-effort, only valid SHA-256 hex values are preserved).
    Set<String> allowlisted = new LinkedHashSet<>();
    tools.jackson.databind.JsonNode listNode = allowlists.get("packManifestSha256");
    if (listNode != null && !listNode.isNull()) {
      if (!listNode.isArray()) {
        throw new UserPolicyWriteException(
            400, ApiErrorCode.USER_POLICY_INVALID_JSON, "Field allowlists.packManifestSha256 must be an array.");
      }
      for (tools.jackson.databind.JsonNode n : listNode) {
        if (n == null || !n.isTextual()) continue;
        String v = normalizeSha256(n.asText());
        if (v != null) {
          allowlisted.add(v);
        }
      }
    }

    // Guardrail: if user allowlist was empty and the app allowlist was previously authoritative,
    // preserve app allowlist digests so we don't accidentally reduce what can be imported.
    if (allowlisted.isEmpty()
        && "app".equalsIgnoreCase(effectivePolicy.packAllowlistSource())
        && effectivePolicy.allowlistedPackManifestSha256() != null) {
      for (String s : effectivePolicy.allowlistedPackManifestSha256()) {
        String n = normalizeSha256(s);
        if (n != null) {
          allowlisted.add(n);
        }
      }
    }

    int before = allowlisted.size();
    allowlisted.add(norm);
    boolean changed = allowlisted.size() != before;

    if (!changed) {
      return new AppendResult(userPath, false, allowlisted.size());
    }

    // Write updated allowlist
    tools.jackson.databind.node.ArrayNode arr = MAPPER.createArrayNode();
    for (String s : allowlisted) {
      arr.add(s);
    }
    allowlists.set("packManifestSha256", arr);

    // Touch updatedAt (add if missing)
    root.put("updatedAt", Instant.now().toString());

    String json;
    try {
      json = MAPPER.writeValueAsString(root);
    } catch (Exception e) {
      throw new UserPolicyWriteException(
          500, ApiErrorCode.USER_POLICY_SERIALIZE_FAILED, "Failed to serialize policy JSON: " + e.getMessage());
    }

    Path tmp =
        userPath.resolveSibling(userPath.getFileName().toString() + ".tmp-" + UUID.randomUUID());
    try {
      Files.writeString(tmp, json, StandardCharsets.UTF_8);
      moveAtomicReplace(tmp, userPath);
      return new AppendResult(userPath, true, allowlisted.size());
    } catch (Exception e) {
      tryDelete(tmp);
      throw new UserPolicyWriteException(
          500, ApiErrorCode.USER_POLICY_WRITE_FAILED, "Failed to write user policy: " + e.getMessage());
    }
  }

  private static void moveAtomicReplace(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      // Best-effort fallback: still replace existing.
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void moveAtomicCreateOnly(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      // Best-effort fallback: still create-only (no REPLACE_EXISTING).
      Files.move(from, to);
    }
  }

  private static void tryDelete(Path p) {
    try {
      if (p != null) {
        Files.deleteIfExists(p);
      }
    } catch (Exception ignored) {
      // best-effort
    }
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

  public static final class UserPolicyWriteException extends RuntimeException {
    private final int httpStatus;
    private final ApiErrorCode errorCode;

    public UserPolicyWriteException(int httpStatus, ApiErrorCode errorCode, String message) {
      super(message);
      this.httpStatus = httpStatus;
      this.errorCode = errorCode == null ? ApiErrorCode.USER_POLICY_WRITE_FAILED : errorCode;
    }

    public int httpStatus() {
      return httpStatus;
    }

    public ApiErrorCode errorCode() {
      return errorCode;
    }
  }
}

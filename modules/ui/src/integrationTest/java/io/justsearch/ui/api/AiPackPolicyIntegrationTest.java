package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import tools.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@DisplayName("AI Pack policy + API wiring integration tests")
class AiPackPolicyIntegrationTest extends LocalApiIntegrationTestBase {

  @Test
  @DisplayName("Preflight returns manifest digest and does not install (no writes)")
  void preflightReturnsDigestAndDoesNotInstall() throws Exception {
    BuiltPack pack = buildZipModelsPack(tmp.resolve("pack-preflight.zip"));

    HttpJsonResponse resp =
        postJson("/api/ai/packs/preflight", java.util.Map.of("path", pack.zipPath().toString()));
    assertEquals(200, resp.statusCode(), resp.body());

    JsonNode json = resp.json();
    assertEquals(pack.packId(), json.path("packId").asText());
    assertEquals(pack.packVersion(), json.path("packVersion").asText());
    assertEquals(pack.manifestSha256(), json.path("manifestSha256").asText());

    assertFalse(Files.exists(aiHome.resolve("models")), "Preflight must not create models dir");
    assertFalse(
        Files.exists(aiHome.resolve("installed-packs.v1.json")),
        "Preflight must not create installed packs record");
    assertFalse(
        Files.exists(aiHome.resolve("pack-import-state.json")),
        "Preflight must not create import state");
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  @DisplayName("Machine policy present + empty allowlist => deny-all")
  void machinePresentEmptyAllowlistDeniesAll() throws Exception {
    // Safety: refuse to touch real %PROGRAMDATA% unless Gradle sandboxed it for integrationTest.
    assumeTrue(
        isProgramDataSandbox(),
        "PROGRAMDATA is not sandboxed (expected build/it-programdata). "
            + "Run via Gradle :modules:ui:integrationTest.");

    BuiltPack pack = buildZipModelsPack(tmp.resolve("pack-machine-deny.zip"));

    // Create machine policy with an explicit empty allowlist (authoritative deny-all).
    writeMachinePolicyWithEmptyPackAllowlist();
    // Also allowlist in user policy to prove machine allowlist is authoritative.
    writeUserPolicyAllowlist(pack.manifestSha256());

    HttpJsonResponse start =
        postJson(
            "/api/ai/packs/import",
            java.util.Map.of("path", pack.zipPath().toString(), "allowDowngrade", false));
    assertEquals(200, start.statusCode(), start.body());

    JsonNode finalStatus = awaitPackImportDone(Duration.ofSeconds(10));
    assertEquals("failed", finalStatus.path("state").asText(), finalStatus.toString());
    assertEquals(
        "PACK_NOT_ALLOWLISTED_BY_MACHINE_POLICY",
        finalStatus.path("errorCode").asText(),
        finalStatus.toString());

    HttpJsonResponse policyResp = getJson("/api/policy/effective");
    assertEquals(200, policyResp.statusCode(), policyResp.body());
    JsonNode policy = policyResp.json();
    assertTrue(policy.path("machine").path("present").asBoolean(false), policy.toString());
    assertEquals("machine", policy.path("packAllowlistSource").asText(), policy.toString());
    assertFalse(policy.path("packAllowlistConfigured").asBoolean(true), policy.toString());
  }

  @Test
  @DisplayName("Machine absent + user allowlist => allow")
  void machineAbsentUserAllowlistAllowsImport() throws Exception {
    // Ensure machine policy is absent (best-effort; only meaningful on Windows sandbox).
    cleanupMachinePolicySandboxBestEffort();

    BuiltPack pack = buildZipModelsPack(tmp.resolve("pack-user-allow.zip"));
    writeUserPolicyAllowlist(pack.manifestSha256());

    HttpJsonResponse start =
        postJson(
            "/api/ai/packs/import",
            java.util.Map.of("path", pack.zipPath().toString(), "allowDowngrade", false));
    assertEquals(200, start.statusCode(), start.body());

    JsonNode finalStatus = awaitPackImportDone(Duration.ofSeconds(10));
    assertEquals("completed", finalStatus.path("state").asText(), finalStatus.toString());

    // Verify installed endpoint shows the pack.
    HttpJsonResponse installedResp = getJson("/api/ai/packs/installed");
    assertEquals(200, installedResp.statusCode(), installedResp.body());
    JsonNode packs = installedResp.json().path("packs");
    assertTrue(packs.isArray(), installedResp.body());
    boolean found = false;
    for (JsonNode p : packs) {
      if (pack.packId().equals(p.path("packId").asText())) {
        found = true;
        assertEquals(pack.manifestSha256(), p.path("manifestSha256").asText());
      }
    }
    assertTrue(found, "Expected installed packs to contain packId=" + pack.packId());

    // Verify expected files exist under AI home.
    Path modelsDir = aiHome.resolve("models");
    assertTrue(Files.isDirectory(modelsDir), "models/ should exist after successful import");
    assertTrue(Files.isRegularFile(modelsDir.resolve("chat.gguf")));
    assertTrue(Files.isRegularFile(modelsDir.resolve("embed.gguf")));
    assertTrue(Files.isRegularFile(aiHome.resolve("installed-packs.v1.json")));

    // Verify effective policy reflects user allowlist when machine policy is absent.
    HttpJsonResponse policyResp = getJson("/api/policy/effective");
    assertEquals(200, policyResp.statusCode(), policyResp.body());
    JsonNode policy = policyResp.json();
    assertFalse(policy.path("machine").path("present").asBoolean(true), policy.toString());
    assertTrue(policy.path("user").path("present").asBoolean(false), policy.toString());
    assertEquals("user", policy.path("packAllowlistSource").asText(), policy.toString());
  }
}

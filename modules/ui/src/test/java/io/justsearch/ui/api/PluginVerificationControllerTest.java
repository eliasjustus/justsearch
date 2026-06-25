/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.settings.PluginAllowlistStore;
import io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 543 §28.W12 — operator-allowlist verification tests.
 *
 * Verifies the allowlist mechanism shipped in §28.W12 (operator
 * explicit admission, no Sigstore chain). The handleVerify HTTP
 * path is exercised indirectly via the allowlist + listing helpers
 * since spinning up a full Javalin server in unit-test scope is
 * heavier than the test ergonomics warrant; the integration test
 * suite covers the HTTP round-trip.
 */
final class PluginVerificationControllerTest {

  private static final String VALID_SHA256 =
      "abc123def456abc123def456abc123def456abc123def456abc123def4567890";
  private static final String OTHER_SHA256 =
      "0000111122223333444455556666777788889999aaaabbbbccccddddeeeeffff";

  private PluginVerificationController controller;

  @BeforeEach
  void setUp() {
    controller = new PluginVerificationController();
    controller.__resetForTest();
  }

  @Test
  void allowlist_starts_empty() {
    assertEquals(0, controller.listAllowlist().size());
  }

  @Test
  void addToAllowlist_returns_true_on_first_add_false_on_duplicate() {
    assertTrue(controller.addToAllowlist(VALID_SHA256));
    assertFalse(controller.addToAllowlist(VALID_SHA256));
    assertEquals(1, controller.listAllowlist().size());
  }

  @Test
  void addToAllowlist_rejects_malformed_input() {
    assertFalse(controller.addToAllowlist(null));
    assertFalse(controller.addToAllowlist(""));
    assertFalse(controller.addToAllowlist("not-hex"));
    assertFalse(controller.addToAllowlist("too-short"));
    // 63 hex chars (one short)
    assertFalse(
        controller.addToAllowlist(
            "abc123def456abc123def456abc123def456abc123def456abc123def456789"));
    assertEquals(0, controller.listAllowlist().size());
  }

  @Test
  void addToAllowlist_normalizes_to_lowercase() {
    assertTrue(
        controller.addToAllowlist(
            "ABC123DEF456ABC123DEF456ABC123DEF456ABC123DEF456ABC123DEF4567890"));
    // The stored entry should be lowercase.
    assertTrue(controller.listAllowlist().contains(VALID_SHA256));
  }

  @Test
  void removeFromAllowlist_returns_true_on_hit_false_on_miss() {
    controller.addToAllowlist(VALID_SHA256);
    assertTrue(controller.removeFromAllowlist(VALID_SHA256));
    assertFalse(controller.removeFromAllowlist(VALID_SHA256));
    assertEquals(0, controller.listAllowlist().size());
  }

  @Test
  void removeFromAllowlist_normalizes_to_lowercase() {
    controller.addToAllowlist(VALID_SHA256);
    assertTrue(
        controller.removeFromAllowlist(
            "ABC123DEF456ABC123DEF456ABC123DEF456ABC123DEF456ABC123DEF4567890"));
  }

  @Test
  void listAllowlist_returns_unmodifiable_snapshot() {
    controller.addToAllowlist(VALID_SHA256);
    controller.addToAllowlist(OTHER_SHA256);
    var snapshot = controller.listAllowlist();
    assertEquals(2, snapshot.size());
    assertTrue(snapshot.contains(VALID_SHA256));
    assertTrue(snapshot.contains(OTHER_SHA256));
    // Subsequent adds don't leak into the snapshot.
    String third = "1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff";
    controller.addToAllowlist(third);
    assertEquals(2, snapshot.size());
  }

  @Test
  void __resetForTest_clears_allowlist() {
    controller.addToAllowlist(VALID_SHA256);
    controller.addToAllowlist(OTHER_SHA256);
    controller.__resetForTest();
    assertEquals(0, controller.listAllowlist().size());
  }

  @Test
  void allowlist_state_is_thread_visible() {
    // Smoke test: rapid sequential ops don't crash. Concurrent stress
    // is out of scope for unit tests (synchronizedSet has the
    // happens-before contract that V1 needs).
    for (int i = 0; i < 100; i++) {
      controller.addToAllowlist(VALID_SHA256);
      controller.removeFromAllowlist(VALID_SHA256);
    }
    assertEquals(0, controller.listAllowlist().size());
  }

  @Test
  void controller_instance_is_independent_per_construction() {
    var other = new PluginVerificationController();
    controller.addToAllowlist(VALID_SHA256);
    assertEquals(1, controller.listAllowlist().size());
    assertEquals(0, other.listAllowlist().size());
    assertNotNull(other);
  }

  @Test
  void approval_persists_across_controllers(@TempDir Path dir) {
    // Tempdoc 560 §28 — an operator approval written through the store is reloaded by a fresh
    // controller (the restart path that, unpersisted, would silently drop the plugin to UNTRUSTED).
    Path file = dir.resolve("ui").resolve("plugin-allowlist.json");
    var c1 =
        new PluginVerificationController(new PluginAllowlistStore(PersistenceMode.READ_WRITE, file));
    assertTrue(c1.addToAllowlist(VALID_SHA256));
    var c2 =
        new PluginVerificationController(new PluginAllowlistStore(PersistenceMode.READ_WRITE, file));
    assertTrue(c2.listAllowlist().contains(VALID_SHA256));
  }
}

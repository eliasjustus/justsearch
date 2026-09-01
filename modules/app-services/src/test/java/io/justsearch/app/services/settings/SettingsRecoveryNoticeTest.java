/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 882 item 24: the settings-reset notice asserts and clears on the condition store. */
class SettingsRecoveryNoticeTest {

  private static final Source HEAD = new Source("head", "test-instance", Optional.empty());

  @Test
  @DisplayName("publish asserts the closed-vocabulary condition naming only the backup file name")
  void publishAssertsCondition() {
    ConditionStore store = new ConditionStore();
    var recovery =
        new UiSettingsStore.RecoveredFromCorrupt(
            Path.of("C:", "data", "ui", "settings.json.corrupt-20260901-101112"), "expected a JSON object");

    SettingsRecoveryNotice.publish(
        store, new HealthEventChangeRegistry(), recovery, HEAD, Clock.systemUTC());

    Optional<HealthEvent> found =
        store.find(LifecycleReasonCode.SETTINGS_RESET_FROM_CORRUPT.code(), "settings");
    assertTrue(found.isPresent(), "the condition must be asserted under the reason code as its id");
    assertEquals(Severity.WARNING, found.get().severity());
    AssertedCondition condition = (AssertedCondition) found.get().body();
    String message = condition.message().orElseThrow();
    assertTrue(
        message.contains("settings.json.corrupt-20260901-101112"),
        "the backup file name is the actionable part: " + message);
    // The full path is machine-local noise in a user-facing sentence, and leaks the data dir.
    assertFalse(
        message.contains(recovery.backupPath().toString()),
        "the message must name the file, not the full path: " + message);
  }

  @Test
  @DisplayName("clear removes the condition a prior publish asserted")
  void clearRemovesCondition() {
    ConditionStore store = new ConditionStore();
    HealthEventChangeRegistry changes = new HealthEventChangeRegistry();
    var recovery =
        new UiSettingsStore.RecoveredFromCorrupt(
            Path.of("settings.json.corrupt-20260901-101112"), "cannot parse");
    SettingsRecoveryNotice.publish(store, changes, recovery, HEAD, Clock.systemUTC());

    SettingsRecoveryNotice.clear(store, changes);

    assertTrue(
        store.find(LifecycleReasonCode.SETTINGS_RESET_FROM_CORRUPT.code(), "settings").isEmpty(),
        "the notice must come down once the user re-authors settings");
  }
}

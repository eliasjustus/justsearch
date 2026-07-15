/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Round-trip tests for {@link RuntimeSpecStore} over a temp-dir {@link UiSettingsStore}. */
final class RuntimeSpecStoreTest {

  @TempDir Path tmp;

  private UiSettingsStore store() {
    return new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
  }

  @Test
  void freshProfileIsUnsetAndResolvesFalse() {
    RuntimeSpec spec = new RuntimeSpecStore(store()).load();
    assertFalse(spec.chatEnabled(), "null resolves to false (autostart-default-false)");
    assertFalse(spec.chatEnabledExplicit(), "never persisted");
  }

  @Test
  void setChatEnabledPersistsAndSurvivesReload() {
    UiSettingsStore store = store();
    new RuntimeSpecStore(store).setChatEnabled(true);

    RuntimeSpec reloaded = new RuntimeSpecStore(store).load();
    assertTrue(reloaded.chatEnabled());
    assertTrue(reloaded.chatEnabledExplicit());
  }

  @Test
  void recordUserEnabledSetsTrueOnFreshProfile() {
    UiSettingsStore store = store();
    new RuntimeSpecStore(store).recordUserEnabled();
    assertTrue(new RuntimeSpecStore(store).load().chatEnabled());
  }

  @Test
  void seedAutostartSeedsOnlyWhenUnset() {
    UiSettingsStore store = store();
    RuntimeSpecStore spec = new RuntimeSpecStore(store);

    assertTrue(spec.seedAutostartIfUnset(), "seeds a fresh profile");
    assertTrue(spec.load().chatEnabled());
    assertFalse(spec.seedAutostartIfUnset(), "does not re-seed once explicit");
  }

  @Test
  void seedAutostartDoesNotOverrideExplicitOff() {
    UiSettingsStore store = store();
    RuntimeSpecStore spec = new RuntimeSpecStore(store);
    spec.setChatEnabled(false); // explicit user off

    assertFalse(spec.seedAutostartIfUnset(), "explicit off is not overridden by the env seed");
    assertFalse(spec.load().chatEnabled());
    assertTrue(spec.load().chatEnabledExplicit());
  }
}

/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.settings.UiSettingsStore;

/**
 * Read/write access to the {@link RuntimeSpec} desired-state, backed by the existing
 * {@link UiSettingsStore} (tempdoc 737 §12a / R5). Every write is load-mutate-save of the single
 * {@code chatEnabled} field, the same pattern {@code RuntimeActivationService} uses for its
 * settings writes — no new persistence store, no {@code <dataDir>} ceremony.
 */
public final class RuntimeSpecStore {

  private final UiSettingsStore settingsStore;

  public RuntimeSpecStore(UiSettingsStore settingsStore) {
    this.settingsStore = settingsStore;
  }

  /** Current desired state. */
  public RuntimeSpec load() {
    if (settingsStore == null) {
      return RuntimeSpec.fromSettings(null);
    }
    return RuntimeSpec.fromSettings(settingsStore.load());
  }

  /** Persist an explicit chat-enabled intent (user or policy). */
  public void setChatEnabled(boolean enabled) {
    if (settingsStore == null) {
      return;
    }
    UiSettings s = settingsStore.load();
    s.setChatEnabled(enabled);
    settingsStore.save(s);
  }

  /**
   * A user has successfully brought AI up — persist {@code chatEnabled=true} so the engine
   * returns on restart (fixes the documented "AI offline after reopen" confusion). Idempotent:
   * a no-op when already explicitly enabled. Null-safe.
   */
  public void recordUserEnabled() {
    if (settingsStore == null) {
      return;
    }
    RuntimeSpec current = load();
    if (current.chatEnabled() && current.chatEnabledExplicit()) {
      return;
    }
    setChatEnabled(true);
  }

  /**
   * Boot autostart seed (subsumes {@code JUSTSEARCH_AI_AUTOSTART_ENABLED} semantics, §12a): if
   * the bit has NEVER been explicitly persisted, seed {@code chatEnabled=true} once so the boot
   * reconcile brings the engine up. Returns {@code true} if it seeded. If the user already made
   * an explicit choice, the env flag does not override it.
   */
  public boolean seedAutostartIfUnset() {
    if (settingsStore == null) {
      return false;
    }
    if (load().chatEnabledExplicit()) {
      return false;
    }
    setChatEnabled(true);
    return true;
  }
}

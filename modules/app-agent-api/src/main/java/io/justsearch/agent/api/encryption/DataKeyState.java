/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.encryption;

/**
 * Tempdoc 629 (LAYER) — the read-only view of the data-encryption-key lifecycle that the
 * {@link StoreCipher} consumes. Implemented Head-side by {@code DataKeyManager} (app-services, which
 * owns the passphrase/KEK/keystore logic). Lives in app-agent-api so both store-owning modules
 * (app-services conversations; app-agent memories/agent-runs) can read the key state without
 * depending on the Head-side key manager.
 */
public interface DataKeyState {

  /** True when encryption is configured (a keystore exists), regardless of lock state. */
  boolean enabled();

  /** True when encryption is configured but the data key is not in memory (awaiting unlock). */
  boolean locked();

  /**
   * The 32-byte data-encryption key (DEK). Callers MUST NOT mutate or retain the array.
   *
   * @throws KeyLockedException if encryption is locked or not configured.
   */
  byte[] dek();

  /** The state for a process with no encryption configured — everything is plaintext passthrough. */
  DataKeyState DISABLED =
      new DataKeyState() {
        @Override
        public boolean enabled() {
          return false;
        }

        @Override
        public boolean locked() {
          return false;
        }

        @Override
        public byte[] dek() {
          throw new KeyLockedException();
        }
      };
}

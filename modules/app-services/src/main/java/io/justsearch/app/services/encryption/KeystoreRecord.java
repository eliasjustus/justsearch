/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.encryption;

/**
 * Tempdoc 629 (LAYER) — the persisted at-rest encryption keystore (the small JSON file under
 * {@code <dataDir>/encryption/keystore.json}). It holds ONLY wrapped material — never the raw data
 * key or the passphrase. The data key (DEK) is wrapped twice: once under a passphrase-derived KEK,
 * once under a recovery-key-derived KEK (so a forgotten passphrase can be recovered).
 *
 * <p>The {@code kdf}/{@code iterations} are stored in the header so the KDF is upgradeable later
 * (e.g. PBKDF2 → Argon2id) without a data migration — the next unlock re-wraps under the new KDF.
 *
 * <p>All byte fields are Base64 (standard). Jackson record (app-api/app-services already use it).
 */
public record KeystoreRecord(
    int version,
    String kdf,
    int iterations,
    String salt,
    String wrappedDek,
    String recoverySalt,
    String recoveryWrappedDek) {}

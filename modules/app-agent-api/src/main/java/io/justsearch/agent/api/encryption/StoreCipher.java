/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.encryption;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Tempdoc 629 (LAYER) — the per-store AEAD helper the AUTHORED stores use to seal/open content with
 * the data key. Pure JDK (AES-256-GCM); the passphrase/KEK side lives Head-side (app-services).
 *
 * <p>Uniform {@link #seal}/{@link #open} over a String works for both write patterns (629 §L4):
 * append-only line logs ({@code messages.jsonl}, {@code events.ndjson}) seal/open each line;
 * full-rewrite files ({@code meta.json}, {@code memory.json}) seal/open the whole content.
 *
 * <p>Backward compatibility: sealed values carry a {@value #MAGIC} prefix, so {@link #open} passes
 * through legacy plaintext untouched — existing un-encrypted stores keep working, and only NEW
 * writes (once encryption is enabled+unlocked) are ciphertext.
 *
 * <p>Locked semantics (629 §L4): {@link #open} of an encrypted value while locked throws
 * {@link KeyLockedException} (NOT empty), so the gate surfaces "locked" not "deleted". {@link #seal}
 * while enabled-but-locked also throws (we can't encrypt without the key, and must never silently
 * write plaintext when the user expects encryption).
 */
public final class StoreCipher {

  static final String MAGIC = "JSEv1:";
  private static final int NONCE_LEN = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RNG = new SecureRandom();

  private final DataKeyState key;

  public StoreCipher(DataKeyState key) {
    this.key = Objects.requireNonNull(key, "key");
  }

  /** A passthrough cipher for processes/stores with no encryption configured. */
  public static StoreCipher disabled() {
    return new StoreCipher(DataKeyState.DISABLED);
  }

  public boolean enabled() {
    return key.enabled();
  }

  public boolean locked() {
    return key.locked();
  }

  /** True if {@code stored} is already a sealed value (carries the magic prefix) — guards double-seal. */
  public boolean isSealed(String stored) {
    return stored != null && stored.startsWith(MAGIC);
  }

  /** Encrypt {@code plaintext} for storage. Passthrough when encryption is disabled. */
  public String seal(String plaintext) {
    if (plaintext == null || !key.enabled()) {
      return plaintext;
    }
    byte[] dek = key.dek(); // throws KeyLockedException if locked
    try {
      byte[] nonce = new byte[NONCE_LEN];
      RNG.nextBytes(nonce);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(dek, "AES"),
          new GCMParameterSpec(TAG_BITS, nonce));
      byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[NONCE_LEN + ct.length];
      System.arraycopy(nonce, 0, out, 0, NONCE_LEN);
      System.arraycopy(ct, 0, out, NONCE_LEN, ct.length);
      return MAGIC + Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("at-rest seal failed", e);
    }
  }

  /**
   * Decrypt a stored value. Legacy plaintext (no {@value #MAGIC} prefix) is returned untouched.
   *
   * @throws KeyLockedException if the value is encrypted but the key is locked/disabled.
   */
  public String open(String stored) {
    if (stored == null || !stored.startsWith(MAGIC)) {
      return stored; // legacy plaintext passthrough
    }
    if (!key.enabled() || key.locked()) {
      throw new KeyLockedException();
    }
    byte[] dek = key.dek();
    try {
      byte[] raw = Base64.getDecoder().decode(stored.substring(MAGIC.length()));
      byte[] nonce = Arrays.copyOfRange(raw, 0, NONCE_LEN);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(dek, "AES"),
          new GCMParameterSpec(TAG_BITS, nonce));
      byte[] pt = c.doFinal(raw, NONCE_LEN, raw.length - NONCE_LEN);
      return new String(pt, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("at-rest open failed (corrupt data or wrong key)", e);
    }
  }
}

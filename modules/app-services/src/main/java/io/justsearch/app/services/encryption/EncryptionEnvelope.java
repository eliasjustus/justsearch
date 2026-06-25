/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.encryption;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Tempdoc 629 (LAYER) — the envelope-encryption core: a random data-encryption key (DEK) wrapped by
 * a key-encryption key (KEK) derived from the user's passphrase (and a second wrap under a recovery
 * key). AES-256-GCM for wrap/unwrap.
 *
 * <p>KDF (629 remaining-work, 2026-06-23): NEW keystores derive the KEK with <b>Argon2id</b>
 * (RFC-9106, m=64&nbsp;MiB / t=3 / p=1) via BouncyCastle — OWASP's first-choice, memory-hard KDF.
 * BouncyCastle's {@code bcprov-jdk18on} is now on app-services' classpath (it was already resolved in
 * the worker modules at 1.81.1, so this is a version-aligned add, not a new transitive). The KDF
 * parameters travel <i>inside</i> the {@link KeystoreRecord#kdf()} header
 * ({@value #ARGON2_KDF}-shaped, self-describing), so the export container stays offline-decryptable.
 * Legacy {@code PBKDF2-HMAC-SHA256} (600k) keystores remain unlockable via the legacy branch in
 * {@link #deriveKek} — back-compat by construction. Because the single {@code kdf} header governs
 * <i>both</i> wraps, only {@link #setup} (which mints both fresh) adopts Argon2id; passphrase/recovery
 * rotation preserves the record's own KDF so the preserved wrap never mismatches.
 *
 * <p>Stateless/static — holds no key material between calls; zeroes derived KEKs in {@code finally}.
 */
public final class EncryptionEnvelope {

  static final int VERSION = 1;
  /** Legacy KDF header — still READ (unlock/recover) so pre-Argon2id keystores keep working. */
  static final String KDF = "PBKDF2-HMAC-SHA256";
  static final int ITERATIONS = 600_000;
  /** KDF header written for NEW keystores: Argon2id, params self-described in the header string. */
  static final String ARGON2_PREFIX = "ARGON2ID";
  static final int ARGON2_MEMORY_KIB = 65_536; // 64 MiB (RFC-9106 config-2)
  static final int ARGON2_ITERATIONS = 3; // time cost t
  static final int ARGON2_PARALLELISM = 1;
  static final String ARGON2_KDF =
      ARGON2_PREFIX
          + ";v=19;m="
          + ARGON2_MEMORY_KIB
          + ";t="
          + ARGON2_ITERATIONS
          + ";p="
          + ARGON2_PARALLELISM;
  private static final int DEK_LEN = 32;
  private static final int SALT_LEN = 16;
  private static final int NONCE_LEN = 12;
  private static final int TAG_BITS = 128;
  private static final int KEK_BITS = 256;
  private static final int RECOVERY_BYTES = 16; // 128-bit recovery key, shown as hex
  private static final SecureRandom RNG = new SecureRandom();

  private EncryptionEnvelope() {}

  /** Result of first-time setup: the keystore to persist + the one-time recovery key to display. */
  public record SetupResult(KeystoreRecord record, String recoveryKey) {}

  /** Generate a fresh DEK, wrap it under the passphrase and a new recovery key. */
  public static SetupResult setup(char[] passphrase) {
    byte[] dek = random(DEK_LEN);
    byte[] salt = random(SALT_LEN);
    byte[] kek = deriveKek(ARGON2_KDF, passphrase, salt, ARGON2_ITERATIONS);
    String recoveryKey = HexFormat.of().formatHex(random(RECOVERY_BYTES));
    byte[] rSalt = random(SALT_LEN);
    byte[] rKek = deriveKek(ARGON2_KDF, recoveryKey.toCharArray(), rSalt, ARGON2_ITERATIONS);
    try {
      KeystoreRecord rec =
          new KeystoreRecord(
              VERSION,
              ARGON2_KDF,
              ARGON2_ITERATIONS,
              b64(salt),
              wrap(kek, dek),
              b64(rSalt),
              wrap(rKek, dek));
      return new SetupResult(rec, recoveryKey);
    } finally {
      Arrays.fill(dek, (byte) 0);
      Arrays.fill(kek, (byte) 0);
      Arrays.fill(rKek, (byte) 0);
    }
  }

  /** Unwrap the DEK using the passphrase. Caller owns/zeroes the returned key. */
  public static byte[] unlock(KeystoreRecord rec, char[] passphrase) {
    byte[] kek = deriveKek(rec.kdf(), passphrase, debase64(rec.salt()), rec.iterations());
    try {
      return unwrap(kek, rec.wrappedDek());
    } finally {
      Arrays.fill(kek, (byte) 0);
    }
  }

  /** Unwrap the DEK using the recovery key (whitespace/dashes are tolerated). */
  public static byte[] recover(KeystoreRecord rec, String recoveryKey) {
    byte[] rKek = deriveKek(rec.kdf(), normalizeRecovery(recoveryKey).toCharArray(), debase64(rec.recoverySalt()), rec.iterations());
    try {
      return unwrap(rKek, rec.recoveryWrappedDek());
    } finally {
      Arrays.fill(rKek, (byte) 0);
    }
  }

  /** Re-wrap the same DEK under a new passphrase (no bulk re-encryption). Keeps the recovery wrap. */
  public static KeystoreRecord changePassphrase(KeystoreRecord rec, char[] oldPass, char[] newPass) {
    byte[] dek = unlock(rec, oldPass); // throws WrongPassphraseException if old is wrong
    byte[] salt = random(SALT_LEN);
    // Preserve the record's own KDF: the single header governs BOTH wraps, and we keep the recovery
    // wrap untouched here — deriving the new passphrase wrap under a different KDF would mismatch it.
    byte[] kek = deriveKek(rec.kdf(), newPass, salt, rec.iterations());
    try {
      return new KeystoreRecord(
          rec.version(),
          rec.kdf(),
          rec.iterations(),
          b64(salt),
          wrap(kek, dek),
          rec.recoverySalt(),
          rec.recoveryWrappedDek());
    } finally {
      Arrays.fill(dek, (byte) 0);
      Arrays.fill(kek, (byte) 0);
    }
  }

  /**
   * Tempdoc 629 (#4): re-wrap the (already-unlocked) DEK under a fresh recovery key, preserving the
   * passphrase wrap — the symmetric twin of {@link #changePassphrase}. The recovery key is never
   * stored (only its wrap), so it cannot be re-displayed; regeneration is the only recovery-management
   * path. Caller supplies the live DEK (held unlocked by the {@code DataKeyManager}) and still owns it.
   */
  public static SetupResult changeRecoveryKey(KeystoreRecord rec, byte[] dek) {
    String recoveryKey = HexFormat.of().formatHex(random(RECOVERY_BYTES));
    byte[] rSalt = random(SALT_LEN);
    // Preserve the record's own KDF (the passphrase wrap we keep was derived under it).
    byte[] rKek = deriveKek(rec.kdf(), recoveryKey.toCharArray(), rSalt, rec.iterations());
    try {
      KeystoreRecord updated =
          new KeystoreRecord(
              rec.version(),
              rec.kdf(),
              rec.iterations(),
              rec.salt(),
              rec.wrappedDek(),
              b64(rSalt),
              wrap(rKek, dek));
      return new SetupResult(updated, recoveryKey);
    } finally {
      Arrays.fill(rKek, (byte) 0);
    }
  }

  // --- crypto helpers ---

  /**
   * Tempdoc 629: derive the KEK using the algorithm named in the keystore header, so the {@code kdf}
   * field is actually READ. NEW keystores use Argon2id ({@link #ARGON2_PREFIX}-prefixed header, params
   * parsed from the header itself); legacy {@code PBKDF2-HMAC-SHA256} keystores keep working via the
   * PBKDF2 branch (back-compat). For Argon2id the {@code iterations} argument is ignored — the
   * time-cost travels in the header. An unknown KDF fails closed rather than silently using the wrong
   * primitive.
   */
  private static byte[] deriveKek(String kdf, char[] passphrase, byte[] salt, int iterations) {
    if (KDF.equals(kdf)) {
      try {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEK_BITS);
        try {
          return f.generateSecret(spec).getEncoded();
        } finally {
          spec.clearPassword();
        }
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("KDF failed", e);
      }
    }
    if (kdf != null && kdf.startsWith(ARGON2_PREFIX)) {
      return deriveArgon2id(kdf, passphrase, salt);
    }
    throw new IllegalStateException("unsupported at-rest KDF in keystore header: " + kdf);
  }

  /** Argon2id KEK derivation; reads m/t/p from the self-describing header (falls back to defaults). */
  private static byte[] deriveArgon2id(String kdf, char[] passphrase, byte[] salt) {
    int memoryKib = parseParam(kdf, "m", ARGON2_MEMORY_KIB);
    int timeCost = parseParam(kdf, "t", ARGON2_ITERATIONS);
    int parallelism = parseParam(kdf, "p", ARGON2_PARALLELISM);
    Argon2Parameters params =
        new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKib)
            .withIterations(timeCost)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build();
    Argon2BytesGenerator gen = new Argon2BytesGenerator();
    gen.init(params);
    byte[] pwBytes = toUtf8Bytes(passphrase);
    try {
      byte[] out = new byte[KEK_BITS / 8];
      gen.generateBytes(pwBytes, out, 0, out.length);
      return out;
    } finally {
      Arrays.fill(pwBytes, (byte) 0);
    }
  }

  /** Parse a {@code key=int} parameter from the {@code ;}-delimited KDF header; fall back if absent. */
  private static int parseParam(String kdf, String key, int fallback) {
    for (String part : kdf.split(";")) {
      int eq = part.indexOf('=');
      if (eq > 0 && part.substring(0, eq).trim().equals(key)) {
        try {
          return Integer.parseInt(part.substring(eq + 1).trim());
        } catch (NumberFormatException ignored) {
          return fallback;
        }
      }
    }
    return fallback;
  }

  /** Encode a passphrase to UTF-8 bytes without an intermediate immutable {@code String} copy. */
  private static byte[] toUtf8Bytes(char[] chars) {
    ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
    byte[] out = new byte[bb.remaining()];
    bb.get(out);
    if (bb.hasArray()) {
      Arrays.fill(bb.array(), (byte) 0);
    }
    return out;
  }

  private static String wrap(byte[] kek, byte[] dek) {
    try {
      byte[] nonce = random(NONCE_LEN);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
      byte[] ct = c.doFinal(dek);
      byte[] out = new byte[NONCE_LEN + ct.length];
      System.arraycopy(nonce, 0, out, 0, NONCE_LEN);
      System.arraycopy(ct, 0, out, NONCE_LEN, ct.length);
      return b64(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("DEK wrap failed", e);
    }
  }

  private static byte[] unwrap(byte[] kek, String wrapped) {
    try {
      byte[] raw = debase64(wrapped);
      byte[] nonce = Arrays.copyOfRange(raw, 0, NONCE_LEN);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
      return c.doFinal(raw, NONCE_LEN, raw.length - NONCE_LEN);
    } catch (AEADBadTagException e) {
      throw new WrongPassphraseException(); // wrong key → GCM tag mismatch
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("DEK unwrap failed (corrupt keystore)", e);
    }
  }

  private static byte[] random(int n) {
    byte[] b = new byte[n];
    RNG.nextBytes(b);
    return b;
  }

  private static String b64(byte[] b) {
    return Base64.getEncoder().encodeToString(b);
  }

  private static byte[] debase64(String s) {
    return Base64.getDecoder().decode(s);
  }

  /** Strip display grouping (dashes/whitespace) and lowercase, so the hex recovery key round-trips. */
  static String normalizeRecovery(String s) {
    return s == null ? "" : s.replaceAll("[^0-9a-fA-F]", "").toLowerCase(java.util.Locale.ROOT);
  }
}

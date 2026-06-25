package io.justsearch.app.services.encryption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Tempdoc 629 (LAYER) — the envelope: setup/unlock round-trip, wrong-passphrase, recovery, rotate. */
final class EncryptionEnvelopeTest {

  @Test
  void newKeystoreUsesArgon2id() {
    EncryptionEnvelope.SetupResult r = EncryptionEnvelope.setup("pw".toCharArray());
    assertTrue(
        r.record().kdf().startsWith(EncryptionEnvelope.ARGON2_PREFIX),
        "new keystores derive the KEK with Argon2id (OWASP first choice), header=" + r.record().kdf());
  }

  @Test
  void legacyPbkdf2KeystoreStillUnlocks() throws Exception {
    // Build a keystore exactly as the pre-Argon2id code did (PBKDF2-HMAC-SHA256 KEK + AES-256-GCM
    // wrap) and assert the legacy branch in deriveKek still unlocks it — back-compat by construction.
    SecureRandom rng = new SecureRandom();
    byte[] dek = new byte[32];
    rng.nextBytes(dek);
    byte[] salt = new byte[16];
    rng.nextBytes(salt);
    byte[] rSalt = new byte[16];
    rng.nextBytes(rSalt);
    String pass = "legacy-pw";
    String recoveryKey = "deadbeefdeadbeefdeadbeefdeadbeef"; // 128-bit hex, already normalized
    KeystoreRecord legacy =
        new KeystoreRecord(
            1,
            "PBKDF2-HMAC-SHA256",
            600_000,
            b64(salt),
            gcmWrap(pbkdf2(pass.toCharArray(), salt, 600_000), dek),
            b64(rSalt),
            gcmWrap(pbkdf2(recoveryKey.toCharArray(), rSalt, 600_000), dek));
    assertArrayEquals(
        dek, EncryptionEnvelope.unlock(legacy, pass.toCharArray()), "legacy PBKDF2 passphrase unlocks");
    assertArrayEquals(
        dek, EncryptionEnvelope.recover(legacy, recoveryKey), "legacy PBKDF2 recovery unlocks");
  }

  @Test
  void unknownKdfFailsClosed() {
    KeystoreRecord bad =
        new KeystoreRecord(1, "SCRYPT-NOT-IMPLEMENTED", 1, "AAAA", "AAAA", "AAAA", "AAAA");
    assertThrows(
        IllegalStateException.class, () -> EncryptionEnvelope.unlock(bad, "x".toCharArray()));
  }

  private static byte[] pbkdf2(char[] pass, byte[] salt, int iters) throws Exception {
    SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
    return f.generateSecret(new PBEKeySpec(pass, salt, iters, 256)).getEncoded();
  }

  private static String gcmWrap(byte[] kek, byte[] dek) throws Exception {
    byte[] nonce = new byte[12];
    new SecureRandom().nextBytes(nonce);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(128, nonce));
    byte[] ct = c.doFinal(dek);
    byte[] out = new byte[12 + ct.length];
    System.arraycopy(nonce, 0, out, 0, 12);
    System.arraycopy(ct, 0, out, 12, ct.length);
    return b64(out);
  }

  private static String b64(byte[] b) {
    return Base64.getEncoder().encodeToString(b);
  }

  @Test
  void setupUnlockAndRecoverYieldSameDek() {
    EncryptionEnvelope.SetupResult r = EncryptionEnvelope.setup("hunter2".toCharArray());
    byte[] viaPass = EncryptionEnvelope.unlock(r.record(), "hunter2".toCharArray());
    assertEquals(32, viaPass.length, "256-bit DEK");
    byte[] viaRecovery = EncryptionEnvelope.recover(r.record(), r.recoveryKey());
    assertArrayEquals(viaPass, viaRecovery, "passphrase and recovery key unwrap the same DEK");
  }

  @Test
  void wrongPassphraseThrows() {
    EncryptionEnvelope.SetupResult r = EncryptionEnvelope.setup("right".toCharArray());
    assertThrows(WrongPassphraseException.class, () -> EncryptionEnvelope.unlock(r.record(), "wrong".toCharArray()));
  }

  @Test
  void recoveryKeyToleratesDisplayGrouping() {
    EncryptionEnvelope.SetupResult r = EncryptionEnvelope.setup("p".toCharArray());
    String grouped = r.recoveryKey().replaceAll("(....)(?=.)", "$1-"); // e.g. ab12-cd34-...
    byte[] dek = EncryptionEnvelope.recover(r.record(), grouped);
    assertArrayEquals(EncryptionEnvelope.unlock(r.record(), "p".toCharArray()), dek);
  }

  @Test
  void changePassphraseRewrapsAndPreservesRecovery() {
    EncryptionEnvelope.SetupResult r = EncryptionEnvelope.setup("old".toCharArray());
    byte[] dek = EncryptionEnvelope.unlock(r.record(), "old".toCharArray());
    KeystoreRecord updated = EncryptionEnvelope.changePassphrase(r.record(), "old".toCharArray(), "new".toCharArray());
    assertArrayEquals(dek, EncryptionEnvelope.unlock(updated, "new".toCharArray()), "same DEK under new passphrase");
    assertThrows(WrongPassphraseException.class, () -> EncryptionEnvelope.unlock(updated, "old".toCharArray()));
    assertArrayEquals(dek, EncryptionEnvelope.recover(updated, r.recoveryKey()), "recovery survives passphrase change");
  }
}

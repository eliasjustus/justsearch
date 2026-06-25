package io.justsearch.agent.api.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tempdoc 629 (LAYER) — StoreCipher: seal/open round-trips, legacy passthrough, locked semantics. */
final class StoreCipherTest {

  private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  private static DataKeyState key(boolean locked) {
    return new DataKeyState() {
      @Override
      public boolean enabled() {
        return true;
      }

      @Override
      public boolean locked() {
        return locked;
      }

      @Override
      public byte[] dek() {
        if (locked) {
          throw new KeyLockedException();
        }
        return KEY;
      }
    };
  }

  @Test
  void sealOpenRoundTrip() {
    StoreCipher c = new StoreCipher(key(false));
    String sealed = c.seal("{\"role\":\"user\",\"content\":\"secret message\"}");
    assertTrue(sealed.startsWith("JSEv1:"), "sealed value carries the magic prefix");
    assertNotEquals("{\"role\":\"user\",\"content\":\"secret message\"}", sealed, "ciphertext != plaintext");
    assertEquals("{\"role\":\"user\",\"content\":\"secret message\"}", c.open(sealed));
  }

  @Test
  void legacyPlaintextPassesThrough() {
    StoreCipher c = new StoreCipher(key(false));
    assertEquals("{\"plain\":true}", c.open("{\"plain\":true}"), "no magic prefix → untouched (back-compat)");
  }

  @Test
  void disabledIsPassthrough() {
    StoreCipher c = StoreCipher.disabled();
    assertEquals("x", c.seal("x"));
    assertEquals("x", c.open("x"));
  }

  @Test
  void openEncryptedWhileLockedThrows() {
    String sealed = new StoreCipher(key(false)).seal("secret");
    StoreCipher locked = new StoreCipher(key(true));
    assertThrows(KeyLockedException.class, () -> locked.open(sealed), "locked open of ciphertext = locked, not empty");
    assertEquals("plain", locked.open("plain"), "but legacy plaintext still passes through while locked");
  }

  @Test
  void sealWhileLockedThrows() {
    StoreCipher locked = new StoreCipher(key(true));
    assertThrows(KeyLockedException.class, () -> locked.seal("secret"), "never silently write plaintext when locked");
  }
}

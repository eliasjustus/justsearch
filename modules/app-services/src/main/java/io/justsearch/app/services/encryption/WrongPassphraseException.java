/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.encryption;

/**
 * Tempdoc 629 (LAYER) — thrown when an unlock/recover/change-passphrase attempt uses the wrong
 * passphrase or recovery key (detected as an AES-GCM authentication-tag mismatch on unwrap). The API
 * maps this to a clean "wrong passphrase" response, distinct from a corrupt-keystore error.
 */
public final class WrongPassphraseException extends RuntimeException {
  public WrongPassphraseException() {
    super("wrong passphrase or recovery key");
  }
}

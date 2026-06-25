/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.services.encryption.DataKeyManager;
import io.justsearch.app.services.encryption.WrongPassphraseException;
import java.util.Arrays;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 629 (LAYER) — the at-rest encryption control endpoints for the AUTHORED stores.
 *
 * <ul>
 *   <li>{@code GET  /api/conversations/encryption} → {@code {state}} (not_configured | locked | unlocked)
 *   <li>{@code POST .../setup} {passphrase} → {state, recoveryKey} (one-time recovery key)
 *   <li>{@code POST .../unlock} {passphrase} → {state} (401 on wrong passphrase)
 *   <li>{@code POST .../lock} → {state}
 *   <li>{@code POST .../recover} {recoveryKey} → {state}
 *   <li>{@code POST .../change-passphrase} {oldPassphrase,newPassphrase} → {state}
 * </ul>
 *
 * <p>Session-token auth is automatic (the global {@code ApiSecurityFilters} before-filter requires it
 * on POST). Passphrases are zeroed after use.
 */
final class ConversationEncryptionController {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final int MIN_PASSPHRASE = 8;

  private final DataKeyManager keys;

  ConversationEncryptionController(DataKeyManager keys) {
    this.keys = keys;
  }

  void handleStatus(Context ctx) {
    ctx.json(Map.of("state", state()));
  }

  void handleSetup(Context ctx) {
    char[] pass = field(ctx, "passphrase").toCharArray();
    if (pass.length < MIN_PASSPHRASE) {
      ctx.status(400).json(Map.of("error", "passphrase must be at least " + MIN_PASSPHRASE + " characters"));
      return;
    }
    try {
      String recoveryKey = keys.setup(pass);
      ctx.json(Map.of("state", state(), "recoveryKey", recoveryKey));
    } catch (IllegalStateException e) {
      ctx.status(409).json(Map.of("error", e.getMessage()));
    } finally {
      Arrays.fill(pass, '\0');
    }
  }

  void handleUnlock(Context ctx) {
    char[] pass = field(ctx, "passphrase").toCharArray();
    try {
      keys.unlock(pass);
      ctx.json(Map.of("state", state()));
    } catch (WrongPassphraseException e) {
      ctx.status(401).json(Map.of("error", "wrong passphrase"));
    } catch (IllegalStateException e) {
      ctx.status(409).json(Map.of("error", e.getMessage()));
    } finally {
      Arrays.fill(pass, '\0');
    }
  }

  void handleLock(Context ctx) {
    keys.lock();
    ctx.json(Map.of("state", state()));
  }

  /** Tempdoc 629 (#4): issue a fresh recovery key (re-wrap; requires unlocked). Shown once. */
  void handleRegenerateRecovery(Context ctx) {
    try {
      String recoveryKey = keys.regenerateRecovery();
      ctx.json(Map.of("state", state(), "recoveryKey", recoveryKey));
    } catch (IllegalStateException e) {
      ctx.status(409).json(Map.of("error", e.getMessage()));
    }
  }

  void handleRecover(Context ctx) {
    String recoveryKey = field(ctx, "recoveryKey");
    try {
      keys.recover(recoveryKey);
      ctx.json(Map.of("state", state()));
    } catch (WrongPassphraseException e) {
      ctx.status(401).json(Map.of("error", "wrong recovery key"));
    } catch (IllegalStateException e) {
      ctx.status(409).json(Map.of("error", e.getMessage()));
    }
  }

  void handleChangePassphrase(Context ctx) {
    char[] oldPass = field(ctx, "oldPassphrase").toCharArray();
    char[] newPass = field(ctx, "newPassphrase").toCharArray();
    if (newPass.length < MIN_PASSPHRASE) {
      ctx.status(400).json(Map.of("error", "passphrase must be at least " + MIN_PASSPHRASE + " characters"));
      Arrays.fill(oldPass, '\0');
      Arrays.fill(newPass, '\0');
      return;
    }
    try {
      keys.changePassphrase(oldPass, newPass);
      ctx.json(Map.of("state", state()));
    } catch (WrongPassphraseException e) {
      ctx.status(401).json(Map.of("error", "wrong passphrase"));
    } catch (IllegalStateException e) {
      ctx.status(409).json(Map.of("error", e.getMessage()));
    } finally {
      Arrays.fill(oldPass, '\0');
      Arrays.fill(newPass, '\0');
    }
  }

  private static String field(Context ctx, String name) {
    String body = ctx.body();
    if (body == null || body.isBlank()) {
      return "";
    }
    try {
      JsonNode node = MAPPER.readTree(body);
      return node.path(name).asString("");
    } catch (tools.jackson.core.JacksonException e) {
      return ""; // malformed JSON → treat as a missing field (yields a 400/401, never a 500)
    }
  }

  private String state() {
    return switch (keys.state()) {
      case NOT_CONFIGURED -> "not_configured";
      case LOCKED -> "locked";
      case UNLOCKED -> "unlocked";
    };
  }
}

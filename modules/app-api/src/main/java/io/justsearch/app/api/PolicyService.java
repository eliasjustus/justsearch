/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.Map;

/**
 * User-policy mutation surface exposed to the AppFacade.
 *
 * <p>Slice 3a-2-c continuation (BrainPackImport policy cluster): backs
 * {@code core.create-user-policy} (creates a fresh user policy file when
 * none exists) and {@code core.allowlist-add-digest} (appends a pack
 * manifest digest to the existing user-policy allowlist).
 *
 * <p>Production wiring: {@code PolicyController} implements this interface;
 * {@code LocalApiServer} late-binds it onto {@code HeadAssembly}.
 *
 * <p>Read-side policy state (effective policy snapshot, validation results)
 * is intentionally NOT on this interface — those are Resource-primitive
 * territory per ADR-09. This interface is action-only (write operations).
 *
 * <p>Stability: stable (API contract).
 */
public interface PolicyService {

  /**
   * Creates a user policy file under AI Home if one doesn't already exist.
   * Rejects if a machine policy is present (machine policy is admin-managed
   * and shouldn't be shadowed by a user-created file). Returns the absolute
   * path of the created file.
   *
   * @param manifestSha256 the manifest digest to seed the allowlist with
   * @return result map with {@code path} (absolute path to the created file)
   * @throws IllegalArgumentException for null/blank manifestSha256
   * @throws Exception on policy-write failure (UserPolicyWriteException
   *     subclass; carries typed http status + error code)
   */
  Map<String, Object> createUserPolicy(String manifestSha256) throws Exception;

  /**
   * Append a pack manifest digest to the existing user-policy allowlist.
   * Preserves all existing user-policy fields; only updates
   * allowlists.packManifestSha256 + updatedAt.
   *
   * @param manifestSha256 the manifest digest to append
   * @return result map with {@code path}, {@code changed} (boolean), and
   *     {@code allowlistedCount} (post-append count)
   * @throws IllegalArgumentException for null/blank manifestSha256
   * @throws Exception on policy-write failure
   */
  Map<String, Object> addDigestToAllowlist(String manifestSha256) throws Exception;

}
